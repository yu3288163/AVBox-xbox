package com.github.avbox.osc.ui

internal enum class DetailPlaybackEntry {
    PlayCapsule,
    Episode,
    /** 详情数据就绪后在顶部小窗自动起播（OK影视模式） */
    AutoInline,
    Line,
    Cast,
    ExitFullscreen,
    SwitchRollback,
    MusicReturn,
}

internal data class DetailPlaybackPlan(
    val startPlayback: Boolean,
    val enterFullScreen: Boolean,
    val stopPlayback: Boolean,
)

internal object DetailPlaybackPolicy {

    /**
     * @param directPlay 「点击海报直接播放」开关：开启时播放器内嵌在详情页顶部，
     *   点播放/选集只换片不再跳全屏；关闭时保持原来的两步式（点播放 → 整屏播放器）。
     */
    fun plan(entry: DetailPlaybackEntry, resumable: Boolean = false, directPlay: Boolean = false): DetailPlaybackPlan = when (entry) {
        DetailPlaybackEntry.PlayCapsule,
        DetailPlaybackEntry.Episode -> DetailPlaybackPlan(
            startPlayback = true,
            enterFullScreen = !directPlay,
            stopPlayback = false,
        )

        DetailPlaybackEntry.AutoInline -> DetailPlaybackPlan(startPlayback = true, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.SwitchRollback,
        DetailPlaybackEntry.MusicReturn -> DetailPlaybackPlan(startPlayback = resumable, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.Line,
        DetailPlaybackEntry.Cast -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = false)

        DetailPlaybackEntry.ExitFullscreen -> DetailPlaybackPlan(startPlayback = false, enterFullScreen = false, stopPlayback = true)
    }
}
