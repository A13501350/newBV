package dev.frost819.newbv.biliapi.entity.video

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.http.entity.video.VideoMoreInfo
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * [VideoChapter]、[VideoChapterSegment] 与 [VideoChapters] 的单元测试。
 *
 * 覆盖 HTTP 层 [VideoMoreInfo.ViewPoint] 的 JSON 解析容错、秒→毫秒转换，
 * 以及归一化（过滤/排序/重叠截开/越界裁剪）、命中查询与跳章目标计算。
 */
class VideoChapterTest {
    private val json =
        Json {
            coerceInputValues = true
            ignoreUnknownKeys = true
        }

    // ── JSON 解析 ────────────────────────────────────────────

    @Test
    fun `parses view_points array with imgUrl and unknown fields`() {
        val raw =
            """
            [
              {"from": 0, "to": 65, "content": "开场", "imgUrl": "https://i0.hdslb.com/x.png", "fromX": 0},
              {"from": 65, "to": 300.5, "content": "正片"}
            ]
            """.trimIndent()

        val points = json.decodeFromString<List<VideoMoreInfo.ViewPoint>>(raw)

        assertThat(points).hasSize(2)
        assertThat(points[0].from).isEqualTo(0.0)
        assertThat(points[0].to).isEqualTo(65.0)
        assertThat(points[0].content).isEqualTo("开场")
        assertThat(points[0].imgUrl).isEqualTo("https://i0.hdslb.com/x.png")
        assertThat(points[1].to).isEqualTo(300.5)
        assertThat(points[1].imgUrl).isNull()
    }

    @Test
    fun `view_point with missing fields falls back to defaults`() {
        val point = json.decodeFromString<VideoMoreInfo.ViewPoint>("""{"from": 10}""")

        assertThat(point.from).isEqualTo(10.0)
        assertThat(point.to).isEqualTo(0.0)
        assertThat(point.content).isEmpty()
        assertThat(point.imgUrl).isNull()
    }

    // ── 秒 → 毫秒转换 ────────────────────────────────────────

    @Test
    fun `fromViewPoint converts seconds to milliseconds`() {
        val chapter =
            VideoChapter.fromViewPoint(
                VideoMoreInfo.ViewPoint(from = 1.5, to = 65.0, content = "开场"),
            )

        assertThat(chapter.fromMs).isEqualTo(1500L)
        assertThat(chapter.toMs).isEqualTo(65_000L)
        assertThat(chapter.content).isEqualTo("开场")
        assertThat(chapter.imgUrl).isNull()
    }

    // ── normalize ────────────────────────────────────────────

    @Test
    fun `normalize returns empty for empty input or non-positive duration`() {
        val chapters = listOf(VideoChapter(0L, 1000L, "章节"))

        assertThat(VideoChapters.normalize(emptyList(), 60_000L)).isEmpty()
        assertThat(VideoChapters.normalize(chapters, 0L)).isEmpty()
        assertThat(VideoChapters.normalize(chapters, -1L)).isEmpty()
    }

    @Test
    fun `normalize filters blank content and invalid ranges`() {
        val chapters =
            listOf(
                VideoChapter(0L, 10_000L, "  "),
                VideoChapter(10_000L, 10_000L, "零长度"),
                VideoChapter(5_000L, 3_000L, "倒置"),
                VideoChapter(10_000L, 20_000L, "有效"),
            )

        val segments = VideoChapters.normalize(chapters, 60_000L)

        assertThat(segments).hasSize(1)
        assertThat(segments[0].content).isEqualTo("有效")
    }

    @Test
    fun `normalize sorts by from and clips overlaps to previous end`() {
        val chapters =
            listOf(
                VideoChapter(50_000L, 90_000L, "晚声明"),
                VideoChapter(0L, 60_000L, "开场"),
            )

        val segments = VideoChapters.normalize(chapters, 120_000L)

        assertThat(segments).hasSize(2)
        assertThat(segments[0].fromMs).isEqualTo(0L)
        assertThat(segments[0].toMs).isEqualTo(60_000L)
        // 重叠部分从上一段结尾截开
        assertThat(segments[1].fromMs).isEqualTo(60_000L)
        assertThat(segments[1].toMs).isEqualTo(90_000L)
    }

    @Test
    fun `normalize coerces segments into duration and keeps gaps`() {
        val chapters =
            listOf(
                VideoChapter(-500L, 10_000L, "负起点"),
                VideoChapter(20_000L, 500_000L, "越界"),
            )

        val segments = VideoChapters.normalize(chapters, 30_000L)

        assertThat(segments).hasSize(2)
        assertThat(segments[0].fromMs).isEqualTo(0L)
        assertThat(segments[0].toMs).isEqualTo(10_000L)
        // 10s-20s 的空隙保留
        assertThat(segments[1].fromMs).isEqualTo(20_000L)
        assertThat(segments[1].toMs).isEqualTo(30_000L)
    }

    // ── findSegmentAt ────────────────────────────────────────

    @Test
    fun `findSegmentAt hits containing segment`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(0L, 60_000L, "A"), VideoChapter(60_000L, 120_000L, "B")), 180_000L)

        assertThat(VideoChapters.findSegmentAt(segments, 30_000L)?.content).isEqualTo("A")
        assertThat(VideoChapters.findSegmentAt(segments, 60_000L)?.content).isEqualTo("B")
    }

    @Test
    fun `findSegmentAt extends last segment to end of video`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(0L, 60_000L, "A")), 180_000L)

        assertThat(VideoChapters.findSegmentAt(segments, 179_999L)?.content).isEqualTo("A")
    }

    @Test
    fun `findSegmentAt returns null before first segment or on empty input`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(60_000L, 120_000L, "A")), 180_000L)

        assertThat(VideoChapters.findSegmentAt(segments, 30_000L)).isNull()
        assertThat(VideoChapters.findSegmentAt(emptyList(), 30_000L)).isNull()
    }

    // ── seekChapterMs ────────────────────────────────────────

    @Test
    fun `seekChapterMs forward jumps to next chapter start`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(0L, 60_000L, "A"), VideoChapter(60_000L, 120_000L, "B"), VideoChapter(120_000L, 180_000L, "C")), 180_000L)

        // 正片进行到 30s → 下一章 60s
        assertThat(VideoChapters.seekChapterMs(segments, 30_000L, forward = true)).isEqualTo(60_000L)
    }

    @Test
    fun `seekChapterMs forward skips adjacent chapter when near its start`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(0L, 60_000L, "A"), VideoChapter(60_000L, 120_000L, "B"), VideoChapter(120_000L, 180_000L, "C")), 180_000L)

        // 已在 B 章开头 500ms 处 → 直接跳到 C
        assertThat(VideoChapters.seekChapterMs(segments, 60_500L, forward = true)).isEqualTo(120_000L)
    }

    @Test
    fun `seekChapterMs backward returns to previous chapter start`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(0L, 60_000L, "A"), VideoChapter(60_000L, 120_000L, "B"), VideoChapter(120_000L, 180_000L, "C")), 180_000L)

        // B 章进行到 65s（刚跳过章首）→ 回到 A 章首
        assertThat(VideoChapters.seekChapterMs(segments, 65_000L, forward = false)).isEqualTo(0L)
        // B 章深处 90s → 回到 B 章首
        assertThat(VideoChapters.seekChapterMs(segments, 90_000L, forward = false)).isEqualTo(60_000L)
    }

    @Test
    fun `seekChapterMs returns null when no target or empty input`() {
        val segments = VideoChapters.normalize(listOf(VideoChapter(0L, 60_000L, "A"), VideoChapter(60_000L, 120_000L, "B")), 120_000L)

        // 已在最后一章内 → 无下一章
        assertThat(VideoChapters.seekChapterMs(segments, 90_000L, forward = true)).isNull()
        // 已在第一章开头 → 无上一章
        assertThat(VideoChapters.seekChapterMs(segments, 1_000L, forward = false)).isNull()
        assertThat(VideoChapters.seekChapterMs(emptyList(), 1_000L, forward = true)).isNull()
    }
}
