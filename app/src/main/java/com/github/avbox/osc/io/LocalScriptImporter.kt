package com.github.avbox.osc.io

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import java.io.File

/**
 * 把 SAF 选中的脚本目录同步到应用私有目录，再交给 LocalScriptSource 扫描。
 *
 * 为什么不直接扫原始目录：Android 11+ 下 SAF 只授予 content:// 读权限，
 * File(path).listFiles() 在没有「所有文件访问权限」时返回 null（表现就是"该目录下没有找到
 * py/js 脚本"）；外置卷 / OTG 的 docId 也未必能映射成真实挂载路径。
 * 走 SAF 枚举 + 复制到 filesDir 后：不依赖 MANAGE_EXTERNAL_STORAGE，py 引擎要的
 * file:// 绝对路径一定可读，目录里 lscript.json 也写得进去（站点管理能持久化）。
 *
 * 同步是增量的：目录里新增 / 删掉的脚本会在下次加载订阅时自动反映到站点列表；
 * 重新选同一个目录会覆盖同名文件。
 */
object LocalScriptImporter {

    private const val MARKER = ".source_tree"
    private const val MAX_FILES = 2000
    private val EXTS = arrayOf(".py", ".js", ".html")

    data class Folder(val path: String, val name: String)

    private class Script(val docId: String, val rel: String)

    private val lock = Any()

    /** 配置加载与手动刷新可能同时同步同一目录，串行化避免把脚本文件写坏。 */
    fun sync(context: Context, tree: Uri): Folder? = synchronized(lock) { syncLocked(context, tree) }

    /** 同步目录内容，返回私有目录路径与用户看到的目录名；失败返回 null。 */
    private fun syncLocked(context: Context, tree: Uri): Folder? {
        // 部分厂商选择器不给持久权限，这里失败也要继续：本次会话内临时权限仍可读。
        try {
            context.contentResolver.takePersistableUriPermission(
                tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (th: Throwable) {
            AppLog.d("LocalScriptImporter", "persist uri permission: ${th.message}")
        }
        val rootId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (th: Throwable) {
            AppLog.e("LocalScriptImporter", th)
            return null
        }
        val digest = MD5.encode(tree.toString()) ?: return null
        val dir = File(File(context.filesDir, "scripts"), digest.take(12))
        if (!dir.isDirectory && !dir.mkdirs()) return null

        val found = ArrayList<Script>()
        val listed = collect(context, tree, rootId, "", found)
        // 连目录都没枚举到（权限没拿到 / provider 拒绝），别误报成"目录里没有脚本"。
        if (!listed && found.isEmpty()) return null

        val keep = HashSet<String>()
        for (s in found) {
            val target = File(dir, s.rel)
            if (copy(context, tree, s.docId, target)) keep.add(target.absolutePath)
        }
        // 只有「目录枚举成功 + 全部文件复制成功」才清理旧副本：
        // 权限被撤销 / 读失败时保留上次的副本，避免一次失败把站点全清空。
        // lscript.json / .source_tree 不是脚本扩展名，不会被删。
        if (listed && keep.size == found.size) {
            try {
                dir.walkTopDown()
                    .filter { it.isFile && isScript(it.name) && !keep.contains(it.absolutePath) }
                    .forEach { it.delete() }
            } catch (th: Throwable) {
                AppLog.e("LocalScriptImporter", th)
            }
        }
        try {
            File(dir, MARKER).writeText(tree.toString())
        } catch (th: Throwable) {
            AppLog.e("LocalScriptImporter", th)
        }
        return Folder(dir.absolutePath, displayName(rootId))
    }

    /** 配置加载前按 marker 记录的 tree URI 重新同步；无 marker（老的手填目录）直接跳过。 */
    fun syncIfNeeded(context: Context, dir: String) {
        val marker = File(dir, MARKER)
        if (!marker.isFile) return
        val text = try {
            marker.readText()
        } catch (th: Throwable) {
            return
        }
        if (text.isBlank()) return
        try {
            sync(context, Uri.parse(text))
        } catch (th: Throwable) {
            AppLog.e("LocalScriptImporter", th)
        }
    }

    /** 返回 false 表示目录枚举失败（权限掉了 / provider 报错），调用方据此保留旧副本。 */
    private fun collect(
        context: Context,
        tree: Uri,
        parentId: String,
        prefix: String,
        out: MutableList<Script>,
    ): Boolean {
        if (out.size >= MAX_FILES) return true
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cursor: Cursor = try {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null, null, null,
            )
        } catch (th: Throwable) {
            AppLog.e("LocalScriptImporter", th)
            return false
        } ?: return false
        var ok = true
        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idIdx < 0 || nameIdx < 0) {
                ok = false
                return@use
            }
            while (c.moveToNext() && out.size < MAX_FILES) {
                val id = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                val mime = if (mimeIdx >= 0) c.getString(mimeIdx).orEmpty() else ""
                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!collect(context, tree, id, rel, out)) ok = false
                } else if (isScript(name)) {
                    out.add(Script(id, rel))
                }
            }
        }
        return ok
    }

    private fun copy(context: Context, tree: Uri, docId: String, target: File): Boolean {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return try {
            val parent = target.parentFile ?: return false
            if (!parent.isDirectory && !parent.mkdirs()) return false
            (context.contentResolver.openInputStream(uri) ?: return false).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (th: Throwable) {
            AppLog.e("LocalScriptImporter", th)
            false
        }
    }

    private fun isScript(name: String): Boolean =
        EXTS.any { name.lowercase().endsWith(it) }

    /** 目录显示名：primary:MT2/yy -> yy；raw:/storage/xx/yy -> yy。 */
    private fun displayName(docId: String): String =
        docId.substringAfterLast(':').trimEnd('/').substringAfterLast('/').ifEmpty { "脚本" }
}
