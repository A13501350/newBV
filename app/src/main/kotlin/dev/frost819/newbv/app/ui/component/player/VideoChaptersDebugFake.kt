package dev.frost819.newbv.app.ui.component.player

import dev.frost819.newbv.biliapi.entity.video.VideoChapterSegment

/**
 * 调试用示例章节生成器（仅调试构建在开启章节调试后使用）。
 *
 * 用于在无章节数据的视频上验证分段渲染：将片长四等分为示例章节。
 * 调用方须以 `BuildConfig.VIDEO_CHAPTERS_DEBUG && Prefs.debugChapterStats` 守卫。
 */
object VideoChaptersDebugFake {
    /** 示例章节标题。 */
    private val FAKE_TITLES = listOf("示例章节 1", "示例章节 2", "示例章节 3", "示例章节 4")

    /**
     * 返回进度条应使用的章节分段：有真实章节时原样返回；
     * 无真实章节且时长有效时生成四等分的示例分段。
     *
     * @param normalized 已归一化的真实章节分段
     * @param durationMs 视频总时长（毫秒），<= 0 时原样返回
     */
    fun effectiveSegments(
        normalized: List<VideoChapterSegment>,
        durationMs: Long,
    ): List<VideoChapterSegment> {
        if (normalized.isNotEmpty() || durationMs <= 0L) return normalized
        val step = durationMs / FAKE_TITLES.size
        return FAKE_TITLES.mapIndexed { index, title ->
            val from = index * step
            val to = if (index == FAKE_TITLES.lastIndex) durationMs else (index + 1) * step
            VideoChapterSegment(fromMs = from, toMs = to, content = title)
        }
    }
}
