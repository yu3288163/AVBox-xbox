package com.github.avbox.osc.player.pip

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.github.avbox.osc.ui.DetailActivity
import com.github.avbox.osc.ui.LivePlayActivity
import com.github.avbox.osc.ui.MusicPlayerActivity
import com.github.avbox.osc.util.ActivityStack
import com.github.avbox.osc.util.AppLog

/**
 * 播放页「是否还在前端」的宿主级判定。
 *
 * 点播 / 直播 / 音乐三种播放页共用同一个 PlaybackService + PlaybackEngine，
 * 关闭画中画窗口时系统通常只把承载 Activity stop 掉、并不 finish，
 * 单靠 isFinishing 判断会漏掉收尾，后台就会继续出声。
 * 这里统一看三种播放页是否都不在前端，由宿主决定停不停后台播放进程。
 */
object PlaybackForegroundGuard {

    private const val TAG = "echo-pip-guard"
    private const val SETTLE_DELAY_MS = 300L

    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<Int, Runnable>()

    /**
     * 因「新播放页接管」而被主动 finish 掉的旧画中画页。
     *
     * 系统画中画窗口绑定在具体的 Activity 实例上：用户按 Home 把影片 A 缩成小窗后，
     * 再从桌面点开影片 B，会新建一个播放页实例去播 B；只要 A 不 finish，系统小窗
     * 就一直浮在最上层，而且播放内核已被 B 接管，A 里只剩一个黑框（用户截图里的「展开」）。
     *
     * 被这样关掉的页**不能**走「用户手动关掉小窗」的停播放收尾：三种播放页共用同一个
     * PlaybackEngine，停掉会把 B 刚起播的内容一起掐断。用弱引用集合，Activity 销毁后自动回收。
     */
    private val superseded: MutableSet<Activity> = java.util.Collections.synchronizedSet(
        java.util.Collections.newSetFromMap<Activity>(java.util.WeakHashMap<Activity, Boolean>())
    )

    /**
     * 新播放页接管播放时调用：把仍挂在系统画中画里的其它播放页收掉。
     * 只在有真正处于 PiP 模式的旧页时才动作，正常进页不受影响。
     */
    @JvmStatic
    fun dismissStalePipPages(current: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        for (activity in ActivityStack.getInstance().activities()) {
            if (activity === current) continue
            if (!isPlaybackPage(activity)) continue
            if (activity.isFinishing || activity.isDestroyed) continue
            val inPip = try {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode
            } catch (th: Throwable) {
                false
            }
            if (!inPip) continue
            superseded.add(activity)
            AppLog.i(TAG + " dismiss stale pip page=" + activity.javaClass.simpleName)
            try {
                activity.finish()
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }
    }

    /**
     * 本次退出画中画是否因为被新播放页接管：是则跳过停播放收尾，避免误停新内容。
     * 集合是弱引用，Activity 回收后自动消失，不要在 onDestroy 里主动清：
     * 延迟 300ms 的退出判定还要靠这个标记兜底，提前清掉会绕过保护。
     */
    @JvmStatic
    fun isSuperseded(activity: Activity): Boolean = superseded.contains(activity)

    @JvmStatic
    fun hasFrontPlaybackPage(): Boolean {
        for (activity in ActivityStack.getInstance().activities()) {
            if (!isPlaybackPage(activity)) continue
            if (activity.isFinishing || activity.isDestroyed) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) {
                return true
            }
            // 三种播放页互相切换时会出现"旧页已 onStop、新页还没 onResume"的中间态，
            // 只认 RESUMED 会把这一帧误判成"没有前端播放页"而停播。
            if (activity.isChangingConfigurations) return true
            val owner = activity as? LifecycleOwner ?: continue
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return true
        }
        return false
    }

    private fun isPlaybackPage(activity: Activity): Boolean =
        activity is DetailActivity ||
            activity is LivePlayActivity ||
            activity is MusicPlayerActivity

    /**
     * 退出小窗后延迟一帧再判定：等 onStop / 配置变化 / 返回全屏的生命周期稳定。
     * 同一 Activity 只保留最后一次判定，避免 onPictureInPictureModeChanged 与 onStop 重复收尾。
     */
    @JvmStatic
    fun schedulePipExitCheck(activity: Activity, onFront: Runnable, onGone: Runnable) {
        val key = System.identityHashCode(activity)
        pending[key]?.let { main.removeCallbacks(it) }
        val task = Runnable {
            pending.remove(key)
            try {
                if (hasFrontPlaybackPage()) {
                    AppLog.i(TAG + " front playback page alive, restore ui")
                    onFront.run()
                } else {
                    AppLog.i(TAG + " no front playback page, stop background playback")
                    onGone.run()
                }
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }
        pending[key] = task
        main.postDelayed(task, SETTLE_DELAY_MS)
    }

    @JvmStatic
    fun cancel(activity: Activity) {
        val key = System.identityHashCode(activity)
        pending.remove(key)?.let { main.removeCallbacks(it) }
    }
}
