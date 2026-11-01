package dev.frost819.newbv.app.ui.component.player

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.video.VideoChapterSegment
import org.junit.jupiter.api.Test

/**
 * [VideoChaptersDebugFake] 的单元测试（调试分支专用）。
 *
 * 验证示例章节注入：有真实章节时原样透传，无章节时按片长四等分。
 */
class VideoChaptersDebugFakeTest {
    @Test
    fun `returns normalized segments unchanged when present`() {
        val normalized =
            listOf(
                VideoChapterSegment(0L, 60_000L, "开场"),
                VideoChapterSegment(60_000L, 120_000L, "正片"),
            )

        assertThat(VideoChaptersDebugFake.effectiveSegments(normalized, 120_000L))
            .isEqualTo(normalized)
    }

    @Test
    fun `generates four equal segments when none exist`() {
        val segments = VideoChaptersDebugFake.effectiveSegments(emptyList(), 400_000L)

        assertThat(segments).hasSize(4)
        assertThat(segments[0].fromMs).isEqualTo(0L)
        assertThat(segments[0].toMs).isEqualTo(100_000L)
        // 最后一段延伸到片尾
        assertThat(segments[3].toMs).isEqualTo(400_000L)
        assertThat(segments.map { it.content })
            .containsExactly(
                "示例章节 1",
                "示例章节 2",
                "示例章节 3",
                "示例章节 4",
        ).inOrder()
    }

    @Test
    fun `returns input unchanged for non-positive duration`() {
        assertThat(VideoChaptersDebugFake.effectiveSegments(emptyList(), 0L)).isEmpty()
        assertThat(VideoChaptersDebugFake.effectiveSegments(emptyList(), -1L)).isEmpty()
    }
}
