package dev.frost819.newbv.app.ui.component.player

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.video.VideoChapterSegment
import org.junit.jupiter.api.Test

/**
 * [computeChapterSegmentBounds] 与 [chapterPlayedFraction] 的单元测试。
 *
 * 验证章节分段在进度条上的像素区间映射：时间比例换算、相邻分段缺口缩进
 * （抵消 StrokeCap.Round 半圆帽）、时间空隙保留、过窄分段占位不错位。
 */
class VideoProgressSeekChapterBoundsTest {
    private val strokeWidthPx = 8f
    private val gapPx = 2f

    @Test
    fun `returns empty for empty segments or non-positive duration or width`() {
        val segments = listOf(VideoChapterSegment(0L, 500L, "章节"))

        assertThat(computeChapterSegmentBounds(emptyList(), 1_000L, 1_000f, strokeWidthPx, gapPx)).isEmpty()
        assertThat(computeChapterSegmentBounds(segments, 0L, 1_000f, strokeWidthPx, gapPx)).isEmpty()
        assertThat(computeChapterSegmentBounds(segments, -1L, 1_000f, strokeWidthPx, gapPx)).isEmpty()
        assertThat(computeChapterSegmentBounds(segments, 1_000L, 0f, strokeWidthPx, gapPx)).isEmpty()
    }

    @Test
    fun `maps segment bounds proportionally to time`() {
        val segments =
            listOf(
                VideoChapterSegment(0L, 200_000L, "A"),
                VideoChapterSegment(200_000L, 800_000L, "B"),
            )
        // 时长 1s=1px：无相邻缩进的部分应精确按时间比例落位

        val bounds =
            computeChapterSegmentBounds(segments, 1_000_000L, 1_000f, strokeWidthPx = 0f, gapPx = 0f)

        assertThat(bounds).containsExactly(0f to 200f, 200f to 800f)
    }

    @Test
    fun `insets contiguous segments to expose visible gap`() {
        val segments =
            listOf(
                VideoChapterSegment(0L, 500L, "A"),
                VideoChapterSegment(500L, 1_000L, "B"),
            )
        // inset = strokeWidth/2 + gap/2 = 5px；可见缺口 = 2*inset - strokeWidth = 2px

        val bounds = computeChapterSegmentBounds(segments, 1_000L, 1_000f, strokeWidthPx, gapPx)

        assertThat(bounds[0].first).isEqualTo(0f)
        assertThat(bounds[0].second).isEqualTo(495f)
        assertThat(bounds[1].first).isEqualTo(505f)
        assertThat(bounds[1].second).isEqualTo(1_000f)
    }

    @Test
    fun `keeps time gaps between segments without extra inset`() {
        val segments =
            listOf(
                VideoChapterSegment(0L, 300L, "A"),
                VideoChapterSegment(600L, 1_000L, "B"),
            )

        val bounds = computeChapterSegmentBounds(segments, 1_000L, 1_000f, strokeWidthPx, gapPx)

        // 300-600ms 的空隙天然存在，不再缩进
        assertThat(bounds).containsExactly(0f to 300f, 600f to 1_000f)
    }

    @Test
    fun `collapses too narrow segment to placeholder keeping alignment`() {
        val segments =
            listOf(
                VideoChapterSegment(0L, 100L, "A"),
                VideoChapterSegment(100L, 105L, "过窄"),
                VideoChapterSegment(105L, 1_000L, "B"),
            )

        val bounds = computeChapterSegmentBounds(segments, 1_000L, 1_000f, strokeWidthPx, gapPx)

        // 返回与输入一一对应：过窄段收成空区间，绘制时跳过
        assertThat(bounds).hasSize(3)
        assertThat(bounds[1].first).isEqualTo(bounds[1].second)
        assertThat(bounds[0].first).isEqualTo(0f)
        assertThat(bounds[2].second).isEqualTo(1_000f)
    }

    // ── chapterPlayedFraction ────────────────────────────────

    @Test
    fun `played fraction is zero before segment and one at or after end`() {
        val segment = VideoChapterSegment(100L, 200L, "章节")

        assertThat(chapterPlayedFraction(segment, 50L)).isEqualTo(0f)
        assertThat(chapterPlayedFraction(segment, 100L)).isEqualTo(0f)
        assertThat(chapterPlayedFraction(segment, 200L)).isEqualTo(1f)
        assertThat(chapterPlayedFraction(segment, 999L)).isEqualTo(1f)
    }

    @Test
    fun `played fraction is proportional within segment`() {
        val segment = VideoChapterSegment(100L, 300L, "章节")

        assertThat(chapterPlayedFraction(segment, 200L)).isEqualTo(0.5f)
        assertThat(chapterPlayedFraction(segment, 150L)).isEqualTo(0.25f)
    }
}
