package com.github.avbox.osc.util

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.HashSet
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 第三方 spider.jar 的「dex 拔牙」。
 *
 * 移植自酒店 IPTV Launcher 项目（Launcher-jd）已验证的兼容方案：市面上不少 jar
 * （潇洒哥 / np.protect 一类防护壳）会在 Init.init() 里校验宿主包名、App 名、签名，
 * 校验不通过就起线程 sleep 5 秒后调用 android.os.Process.killProcess(myPid())，
 * 表现为「切到该源约 5 秒后直接闪退」，logcat 只有 SIG: 9、没有 Java 崩溃栈。
 * 源在别人服务器上，改不了远端 jar，只能在宿主侧加载前把自杀指令抹掉。
 *
 * 做法：下载/缓存完成后，先生成一份 jar 副本，把其中每个 classes*.dex 里
 * Process.killProcess / System.exit / Runtime.exit / Runtime.halt 的 invoke 指令
 * 整条替换为两个 nop，并重算 dex header 的 SHA-1 与 Adler32；宿主优先加载这份副本。
 * 不含这些调用或解析失败的 jar 返回 null，宿主直接用原 jar，零副作用。
 *
 * 为什么不是「跳过 Init.init()」：Init 里除了校验还有本地代理、静态配置等初始化，
 * 整段跳过会让 spider:// / 127.0.0.1 代理类源失效；只拔掉牙，其余逻辑照常执行。
 */
object JarDexNeutralizer {

    // 后缀带算法版本：升级拔牙逻辑后自动弃用设备上缓存的旧副本，
    // 否则覆盖安装会一直复用上一版生成的 .fix.jar。改算法或改日志时把版本号 +1。
    private const val ALGO_VERSION = 4
    private val SUFFIX = ".fix.v" + ALGO_VERSION + ".jar"
    private const val MAX_TABLE_ITEMS = 2_000_000
    private val LOCK = Any()

    /**
     * 生成拔牙副本。返回 null 表示该 jar 无需拔牙或拔牙失败（调用方回退原 jar）。
     */
    @JvmStatic
    fun neutralize(src: File): File? {
        synchronized(LOCK) {
            val dst = File(src.parentFile, src.name + SUFFIX)
            try {
                if (!src.exists() || src.length() == 0L) return null
                if (dst.exists() && dst.length() > 0L && dst.lastModified() >= src.lastModified()) {
                    System.err.println("echo--jar-neutral: reuse " + dst.name)
                    return dst
                }
                if (dst.exists() && !dst.delete()) return null

                var totalPatched = 0
                var totalGuard = 0
                ZipInputStream(FileInputStream(src)).use { zin ->
                    ZipOutputStream(FileOutputStream(dst)).use { zout ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val entry = zin.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val bytes = ByteArrayOutputStream()
                            while (true) {
                                val n = zin.read(buffer)
                                if (n <= 0) break
                                bytes.write(buffer, 0, n)
                            }
                            var data = bytes.toByteArray()
                            if (entry.name.endsWith(".dex")) {
                                val patched = patchDex(data)
                                if (patched !== data) {
                                    totalPatched += countPatched
                                    totalGuard += countGuard
                                    data = patched
                                }
                            }
                            zout.putNextEntry(ZipEntry(entry.name))
                            zout.write(data)
                            zout.closeEntry()
                        }
                    }
                }
                if (totalPatched == 0 && totalGuard == 0) {
                    dst.delete()
                    return null
                }
                dst.setLastModified(src.lastModified())
                // release 的 proguard 规则会 assumenosideeffects 掉 AppLog.e 和 android.util.Log.e，
                // 所以这里用 System.err（logcat 里显示为 W/System.err）才能在正式包上核对拔牙是否生效。
                System.err.println("echo--jar-neutral: kill=" + totalPatched + " guard=" + totalGuard + ", " + src.name + " -> " + dst.name)
                return dst
            } catch (e: Throwable) {
                System.err.println("echo--jar-neutral: failed " + src.name + " | " + e)
                try {
                    dst.delete()
                } catch (ignored: Throwable) {
                }
                return null
            }
        }
    }

    /** patchDex 最近一次替换掉的调用点数量（只在 LOCK 内、单线程读取） */
    private var countPatched = 0
    private var countGuard = 0

    /** 对单个 dex 做拔牙；无改动返回原数组（同一引用），有改动返回新数组 */
    private fun patchDex(d: ByteArray): ByteArray {
        countPatched = 0
        countGuard = 0
        try {
            if (d.size < 0x70 || d[0] != 'd'.code.toByte() || d[1] != 'e'.code.toByte()
                    || d[2] != 'x'.code.toByte() || d[3] != '\n'.code.toByte()) return d
            val strN = u32(d, 0x38)
            val strOff = u32(d, 0x3C)
            val typN = u32(d, 0x40)
            val typOff = u32(d, 0x44)
            val mthN = u32(d, 0x58)
            val mthOff = u32(d, 0x5C)
            val clsN = u32(d, 0x60)
            val clsOff = u32(d, 0x64)
            // 表偏移/数量整体合法性（防护壳会在 header 层埋非法值让解析器崩溃）
            if (strN < 0 || strN > MAX_TABLE_ITEMS || strOff < 0 || strOff + 4L * strN > d.size) return d
            if (typN < 0 || typN > MAX_TABLE_ITEMS || typOff < 0 || typOff + 4L * typN > d.size) return d
            if (mthN < 0 || mthN > MAX_TABLE_ITEMS || mthOff < 0 || mthOff + 8L * mthN > d.size) return d
            if (clsN < 0 || clsN > MAX_TABLE_ITEMS || clsOff < 0 || clsOff + 32L * clsN > d.size) return d

            // 1) 目标字符串索引
            var sKill = -1
            var sExit = -1
            var sHalt = -1
            var sB = -1
            for (i in 0 until strN) {
                val s = stringAt(d, safeU32(d, strOff + 4 * i))
                if (s == "killProcess") sKill = i
                else if (s == "exit") sExit = i
                else if (s == "halt") sHalt = i
                else if (s == "b") sB = i
            }
            if (sKill < 0 && sExit < 0 && sHalt < 0) return d

            // 2) 目标类型索引
            var tProc = -1
            var tSys = -1
            var tRt = -1
            var tInit = -1
            for (i in 0 until typN) {
                val s = stringAt(d, safeU32(d, strOff + 4 * safeU32(d, typOff + 4 * i)))
                if (s == "Landroid/os/Process;") tProc = i
                else if (s == "Ljava/lang/System;") tSys = i
                else if (s == "Ljava/lang/Runtime;") tRt = i
                else if (s == "Lcom/github/catvod/spider/Init;") tInit = i
            }
            if (tProc < 0 && tSys < 0 && tRt < 0) return d

            // 3) 目标 method_id 集合：只按「类 + 方法名」匹配，不校验 proto。
            //    实测防护壳会把 killProcess 的 proto 改写成非法签名（反分析），
            //    按签名过滤会全部漏杀；这三个类里没有同名其它方法，按名匹配足够安全。
            val kill = HashSet<Int>()
            for (i in 0 until mthN) {
                val base = mthOff + 8 * i
                val cls = u16(d, base)
                val nm = safeU32(d, base + 4)
                if (cls == tProc && nm == sKill) kill.add(i)
                else if (cls == tSys && nm == sExit) kill.add(i)
                else if (cls == tRt && (nm == sExit || nm == sHalt)) kill.add(i)
            }
            if (kill.isEmpty()) return d

            // 3b) 防护壳的 Init.b(Context)Z 校验 App 显示名是否在作者白名单里。
            //     不匹配就 goto 失败分支，跳过本地代理 / 配置中心 / 网络任务初始化，
            //     站点全部「加载失败」。这里让 b() 恒返回 true（等价于 return true;）。
            //     只在已确认带 kill/exit 特征的 dex 上做，普通 jar 不受影响。
            val initB = HashSet<Int>()
            if (sB >= 0 && tInit >= 0) {
                for (i in 0 until mthN) {
                    val base = mthOff + 8 * i
                    if (u16(d, base) == tInit && safeU32(d, base + 4) == sB) initB.add(i)
                }
            }

            // 4) 遍历 class_data → code_item 替换调用点。
            //    韧性解析：单个 class_def 解析失败只跳过它自己，避免整包漏杀。
            val out = d.clone()
            var patched = 0
            var guard = 0
            for (i in 0 until clsN) {
                val cdOff = safeU32(d, clsOff + 32 * i + 24)
                if (cdOff == 0 || cdOff >= d.size) continue
                try {
                    val result = patchClassData(d, out, cdOff, kill, initB)
                    patched += result[0]
                    guard += result[1]
                } catch (ignored: Throwable) {
                }
            }
            if (patched == 0 && guard == 0) return d
            fixHeader(out)
            countPatched = patched
            countGuard = guard
            return out
        } catch (e: Throwable) {
            System.err.println("echo--jar-neutral: patchDex failed | " + e)
            return d
        }
    }

    /** 解析 class_data_item；返回 [kill 调用点数, Init.b 改写数] */
    private fun patchClassData(d: ByteArray, out: ByteArray, off: Int, kill: HashSet<Int>, initB: HashSet<Int>): IntArray {
        val result = IntArray(2)
        val v = IntArray(1)
        var p = uleb(d, off, v)
        val staticFields = v[0]
        p = uleb(d, p, v)
        val instanceFields = v[0]
        p = uleb(d, p, v)
        val directMethods = v[0]
        p = uleb(d, p, v)
        val virtualMethods = v[0]
        if (staticFields + instanceFields > MAX_TABLE_ITEMS || directMethods + virtualMethods > MAX_TABLE_ITEMS) return result
        for (i in 0 until staticFields + instanceFields) {
            p = uleb(d, p, v) // field_idx_diff
            p = uleb(d, p, v) // access_flags
        }
        // encoded_method 的 method_idx_diff 在 direct / virtual 两组里各自从 0 累积
        var methodIdx = 0
        for (group in 0 until 2) {
            methodIdx = 0
            val count = if (group == 0) directMethods else virtualMethods
            for (i in 0 until count) {
                p = uleb(d, p, v)
                methodIdx += v[0] // method_idx_diff
                p = uleb(d, p, v) // access_flags
                p = uleb(d, p, v) // code_off
                val codeOff = v[0]
                if (codeOff == 0 || codeOff + 16 > d.size) continue
                if (initB.contains(methodIdx)) {
                    result[1] += forceReturnTrue(d, out, codeOff)
                    continue // b() 已被整体短路，不要再按原指令流找 kill
                }
                result[0] += patchInsns(d, out, codeOff, kill)
            }
        }
        return result
    }

    /**
     * 把 b() 里所有 `const/4 v0,#0; return v0` 改成 `const/4 v0,#1; return v0`。
     * 只翻转一个字节（0x0012 → 0x1012），指令宽度、分支边界、try/catch handler
     * 全部原样不动，ART verifier 完全无感；失败路径和 catch 路径都变成返回 true。
     * 比「把方法开头换成 return true」安全：后者会留下半截不可达指令。
     */
    private fun forceReturnTrue(d: ByteArray, out: ByteArray, codeOff: Int): Int {
        val insnsOff = codeOff + 16
        val insnsSize = u32(d, codeOff + 12)
        var changed = 0
        var i = 0
        while (i + 1 < insnsSize) {
            val pos = insnsOff + 2 * i
            if (pos + 4 > d.size) break
            val word = u16(d, pos)
            val op = word and 0xFF
            // 1) 失败 / catch 路径的 `const/4 v0,#0` → `const/4 v0,#1`
            if (word == 0x0012 && u16(d, pos + 2) == 0x000F) {
                out[pos + 1] = 0x10.toByte()
                changed++
                i += 2
                continue
            }
            // 2) `if-nez vX, target` 的失败分支末尾常是裸 `return v0`（v0 为对象引用）。
            //    把它改成 `const/4 v0,#1`，顺序落到 target 处的 `return true`；
            //    指令宽度不变，分支边界与 try/catch handler 全部保持原样。
            if (op == 0x39 && (word ushr 8) <= 0x0F) {
                val target = i + u16(d, pos + 2).toShort().toInt()
                if (target in 1 until insnsSize) {
                    val retPos = insnsOff + 2 * (target - 1)
                    if (retPos + 2 <= d.size && u16(d, retPos) == 0x000F) {
                        out[retPos] = 0x12.toByte()
                        out[retPos + 1] = 0x10.toByte()
                        changed++
                    }
                }
                i += 2
                continue
            }
            i++
        }
        return if (changed > 0) 1 else 0
    }

    /**
     * code_item：registers/ins/outs/tries(u16×4) + debug_info_off(u32) + insns_size(u32)，
     * 之后是 u16 数组。invoke-virtual(0x6E)/invoke-static(0x71)/
     * invoke-virtual/range(0x74)/invoke-static/range(0x78) 的第二个 u16 都是 method_idx。
     * 命中 kill 集合 → 两个 u16 一起置 0（nop nop）。
     */
    private fun patchInsns(d: ByteArray, out: ByteArray, codeOff: Int, kill: HashSet<Int>): Int {
        val insnsOff = codeOff + 16
        val insnsSize = u32(d, codeOff + 12)
        if (insnsSize <= 0 || insnsSize > MAX_TABLE_ITEMS) return 0
        var patched = 0
        var i = 0
        while (i + 1 < insnsSize) {
            val pos = insnsOff + 2 * i
            if (pos + 4 > d.size) break
            val word = u16(d, pos)
            val op = word and 0xFF
            // 35c 的高字节 = A<<4|B，A 为参数个数（上限 5）。packed-switch 等
            // payload 的数据里也可能凑出「invoke 形状」的字节，靠 A<=5 排除，
            // 否则会把 payload 常数改成 0，导致 dex 校验失败或跳转错乱。
            val legal35c = (op == 0x6E || op == 0x71) && (word ushr 8) <= 0x5F
            val legal3rc = op == 0x74 || op == 0x78
            if (legal35c || legal3rc) {
                val methodIdx = u16(d, pos + 2)
                if (kill.contains(methodIdx)) {
                    out[pos] = 0.toByte()
                    out[pos + 1] = 0.toByte()
                    out[pos + 2] = 0.toByte()
                    out[pos + 3] = 0.toByte()
                    patched++
                    i++ // 下一个 u16 已作为 method_idx 消费
                }
            }
            i++
        }
        return patched
    }

    /** 重算 dex header 的 SHA-1 签名与 Adler32 校验值（改过指令后必须） */
    private fun fixHeader(d: ByteArray) {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(d, 0x20, d.size - 0x20)
        sha.digest().copyInto(d, 0x0C, 0, 20)
        val adler = Adler32()
        adler.update(d, 0x0C, d.size - 0x0C)
        val c = adler.value.toInt()
        d[0x08] = (c and 0xFF).toByte()
        d[0x09] = ((c shr 8) and 0xFF).toByte()
        d[0x0A] = ((c shr 16) and 0xFF).toByte()
        d[0x0B] = ((c shr 24) and 0xFF).toByte()
    }

    private fun u32(d: ByteArray, off: Int): Int {
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8) or
                ((d[off + 2].toInt() and 0xFF) shl 16) or ((d[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun u16(d: ByteArray, off: Int): Int {
        return (d[off].toInt() and 0xFF) or ((d[off + 1].toInt() and 0xFF) shl 8)
    }

    /** 越界安全的 u32（防壳在表项里埋非法偏移让解析器整体放弃） */
    private fun safeU32(d: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > d.size) return 0
        return u32(d, off)
    }

    private fun uleb(d: ByteArray, start: Int, out: IntArray): Int {
        var p = start
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = d[p++].toInt()
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while ((b and 0x80) != 0)
        out[0] = result
        return p
    }

    /** 读 string_ids 第 off 处的 MUTF8 字符串（NUL 结尾） */
    private fun stringAt(d: ByteArray, off: Int): String {
        return try {
            val v = IntArray(1)
            val start = uleb(d, off, v)
            var end = start
            while (end < d.size && d[end] != 0.toByte()) end++
            String(d, start, end - start, Charsets.UTF_8)
        } catch (ignored: Throwable) {
            ""
        }
    }
}
