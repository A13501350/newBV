package dev.frost819.newbv.biliapi.entity.video

import dev.frost819.newbv.biliapi.http.entity.video.VideoMoreInfo
import kotlin.math.max

/**
 * 视频章节（view_points）领域实体。
 *
 * 与 HTTP 层 [VideoMoreInfo.ViewPoint] 的区别：时间统一为毫秒。
 *
 * @param fromMs 章节起点（毫秒）
 * @param toMs 章节终点（毫秒）
 * @param content 章节标题
 * @param imgUrl 章节配图 URL
 */
data class VideoChapter(
    val fromMs: Long,
    val toMs: Long,
    val content: String,
    val imgUrl: String? = null,
) {
    companion object {
        /**
         * 从 HTTP 层章节点转换（秒 → 毫秒，截断取整，与上游实现一致）。
         */
        fun fromViewPoint(viewPoint: VideoMoreInfo.ViewPoint): VideoChapter =
            VideoChapter(
                fromMs = (viewPoint.from * 1000).toLong(),
                toMs = (viewPoint.to * 1000).toLong(),
                content = viewPoint.content,
                imgUrl = viewPoint.imgUrl,
            )
    }
}

/**
 * 归一化后的章节分段：时间已裁剪进片长、互不重叠、按 fromMs 升序。
 *
 * 章节之间的空隙（接口未覆盖的时间段）不属于任何分段，渲染时显示为普通轨道。
 */
data class VideoChapterSegment(
    val fromMs: Long,
    val toMs: Long,
    val content: String,
)

/**
 * 章节（view_points）归一化与查询工具。
 *
 * 归一化规则：过滤无效段（标题空白或终点不大于起点）→ 按起点排序 →
 * 重叠段从上一段结尾处截开 → 两端裁剪进片长；章节之间的空隙保留为无分段区。
 */
object VideoChapters {
    /**
     * 归一化章节列表。
     *
     * @param chapters 原始章节列表（来自接口，顺序与连续性无保证）
     * @param durationMs 视频总时长（毫秒）；<= 0 时返回空列表
     * @return 归一化后的分段列表，可直接用于渲染与查询
     */
    fun normalize(
        chapters: List<VideoChapter>,
        durationMs: Long,
    ): List<VideoChapterSegment> {
        if (durationMs <= 0L) return emptyList()
        return chapters
            .asSequence()
            .filter { it.content.isNotBlank() && it.toMs > it.fromMs }
            .sortedBy { it.fromMs }
            .fold(mutableListOf<VideoChapterSegment>()) { acc, chapter ->
                val from = max(chapter.fromMs, acc.lastOrNull()?.toMs ?: 0L).coerceIn(0L, durationMs)
                val to = chapter.toMs.coerceIn(0L, durationMs)
                if (to > from) {
                    acc += VideoChapterSegment(fromMs = from, toMs = to, content = chapter.content)
                }
                acc
            }.toList()
    }

    /**
     * 命中 [positionMs] 所在章节；最后一段之后（延伸到片尾）仍算最后一章。
     *
     * @return 命中的分段；列表为空或位置在所有分段之前（章节未从 0 开始时）返回 null
     */
    fun findSegmentAt(
        segments: List<VideoChapterSegment>,
        positionMs: Long,
    ): VideoChapterSegment? {
        if (segments.isEmpty()) return null
        segments.lastOrNull { positionMs >= it.fromMs && positionMs < it.toMs }?.let { return it }
        val last = segments.last()
        return last.takeIf { positionMs >= last.toMs }
    }

    /**
     * 遥控跳章目标点（供未来接入跳章交互，当前仅作为纯工具函数维护）。
     *
     * forward=true → 第一个起点在当前位置 1 秒之后的章节（已在某章开头附近则跳到再下一章）；
     * forward=false → 最后一个起点早于当前位置 3 秒的章节（刚跳到章首再按"上一章"
     * 能回到上一章而非原地）。
     *
     * @return 目标章节起点（毫秒）；无可跳目标返回 null
     */
    fun seekChapterMs(
        segments: List<VideoChapterSegment>,
        positionMs: Long,
        forward: Boolean,
    ): Long? =
        if (segments.isEmpty()) {
            null
        } else if (forward) {
            segments.firstOrNull { it.fromMs > positionMs + CHAPTER_FORWARD_EPSILON_MS }?.fromMs
        } else {
            segments.lastOrNull { it.fromMs < positionMs - CHAPTER_BACKWARD_EPSILON_MS }?.fromMs
        }

    private const val CHAPTER_FORWARD_EPSILON_MS = 1_000L
    private const val CHAPTER_BACKWARD_EPSILON_MS = 3_000L
}
