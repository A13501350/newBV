package dev.frost819.newbv.app.ui.component.player

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.video.VideoChapter
import dev.frost819.newbv.biliapi.entity.video.VideoChapters
import org.junit.jupiter.api.Test
import java.util.Locale
import kotlin.math.abs

/**
 * [VideoChapters] / [computeChapterSegmentBounds] 归一化与布局探针
 * （调试分支专用调参工具，非普通单测）。
 *
 * 用 6 种章节场景 × 2 种轨道宽度输出归一化结果与像素布局摘要表，
 * 供分段渲染参数（缺口宽度、缩进策略）调优时对照参考。
 *
 * 同时断言基本健康约束，作为参数改动的回归守卫：
 * - 归一化输出互不重叠、升序、全部落在片长内
 * - 像素区间单调不回退、不越出轨道
 * - 时间上首尾相接的分段之间存在可见缺口
 */
class VideoChaptersNormalizeProbeTest {
    @Test
    fun `normalize and layout probe across scenes and track widths`() {
        val report = StringBuilder("\n[probe] ── 章节归一化与分段布局（轨道 1080px / 480px）──\n")

        for (scene in scenes) {
            val segments = VideoChapters.normalize(scene.chapters, scene.durationMs)

            // Then：归一化健康约束
            assertThat(segments.map { it.fromMs }).isInOrder()
            segments.zipWithNext { prev, next ->
                assertThat(next.fromMs).isAtLeast(prev.toMs)
            }
            segments.forEach { segment ->
                assertThat(segment.fromMs).isAtLeast(0L)
                assertThat(segment.toMs).isAtMost(scene.durationMs)
                assertThat(segment.toMs).isGreaterThan(segment.fromMs)
            }

            for (trackWidth in listOf(1080f, 480f)) {
                val bounds =
                    computeChapterSegmentBounds(
                        segments,
                        scene.durationMs,
                        trackWidth,
                        strokeWidthPx = 8f,
                        gapPx = 2f,
                    )
                assertThat(bounds).hasSize(segments.size)

                // Then：布局健康约束
                bounds.forEach { (startX, endX) ->
                    assertThat(endX).isAtLeast(startX)
                    assertThat(startX).isAtLeast(0f)
                    assertThat(endX).isAtMost(trackWidth)
                }
                bounds.zipWithNext { prev, next ->
                    if (prev.second > prev.first && next.second > next.first) {
                        // 相邻可见分段不重叠（间隙可画）
                        assertThat(next.first).isAtLeast(prev.second)
                    }
                }

                val visibleGapMs =
                    segments
                        .zipWithNext { prev, next -> next.fromMs - prev.toMs }
                        .firstOrNull { it == 0L }
                report.appendLine(
                    "[probe] scene=${scene.name.padEnd(16)} width=${trackWidth.toInt().toString().padEnd(4)} " +
                        "chapters=${scene.chapters.size} segments=${segments.size} " +
                        "contiguous=${visibleGapMs != null} " +
                        "bounds=${bounds.joinToString { "(${fmt(it.first)},${fmt(it.second)})" }}",
                )
            }
        }
        println(report)
    }

    @Test
    fun `contiguous segments expose visible gap at every boundary`() {
        val segments =
            VideoChapters.normalize(
                listOf(
                    VideoChapter(0L, 300_000L, "A"),
                    VideoChapter(300_000L, 600_000L, "B"),
                    VideoChapter(600_000L, 900_000L, "C"),
                ),
                900_000L,
            )
        val bounds =
            computeChapterSegmentBounds(segments, 900_000L, 1080f, strokeWidthPx = 8f, gapPx = 2f)

        bounds.zipWithNext { prev, next ->
            val gap = next.first - prev.second
            assertThat(gap).isGreaterThan(0f)
        }
    }

    @Test
    fun `single segment spans full track without inset`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(0L, 60_000L, "全片")), 60_000L)
        val bounds =
            computeChapterSegmentBounds(segments, 60_000L, 1080f, strokeWidthPx = 8f, gapPx = 2f)

        assertThat(bounds).containsExactly(0f to 1080f)
    }

    /** 缩略浮点输出，保持摘要表可读。 */
    private fun fmt(value: Float): String =
        if (abs(value - value.toInt()) < 0.01f) "${value.toInt()}" else "%.1f".format(Locale.US, value)

    /** 章节场景：接口可能下发的各种形态。 */
    private data class Scene(
        val name: String,
        val durationMs: Long,
        val chapters: List<VideoChapter>,
    )

    private val scenes =
        listOf(
            Scene(
                name = "连续无空隙",
                durationMs = 600_000L,
                chapters =
                    listOf(
                        VideoChapter(0L, 60_000L, "开场"),
                        VideoChapter(60_000L, 300_000L, "正片"),
                        VideoChapter(300_000L, 560_000L, "高能"),
                        VideoChapter(560_000L, 600_000L, "片尾"),
                    ),
            ),
            Scene(
                name = "带时间空隙",
                durationMs = 600_000L,
                chapters =
                    listOf(
                        VideoChapter(0L, 60_000L, "开场"),
                        VideoChapter(120_000L, 300_000L, "正片"),
                    ),
            ),
            Scene(
                name = "重叠乱序",
                durationMs = 600_000L,
                chapters =
                    listOf(
                        VideoChapter(300_000L, 450_000L, "高能"),
                        VideoChapter(0L, 320_000L, "正片"),
                        VideoChapter(50_000L, 80_000L, "无效短段"),
                        VideoChapter(0L, 60_000L, "开场"),
                    ),
            ),
            Scene(
                name = "越界裁剪",
                durationMs = 300_000L,
                chapters =
                    listOf(
                        VideoChapter(-100L, 60_000L, "负起点"),
                        VideoChapter(100_000L, 999_999L, "超长"),
                    ),
            ),
            Scene(
                name = "全无效段",
                durationMs = 600_000L,
                chapters =
                    listOf(
                        VideoChapter(0L, 60_000L, ""),
                        VideoChapter(60_000L, 30_000L, "倒置"),
                    ),
            ),
            Scene(
                name = "单章节",
                durationMs = 60_000L,
                chapters = listOf(VideoChapter(0L, 60_000L, "全片")),
            ),
        )
}
