package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.biliapi.entity.video.VideoChapterSegment
import dev.frost819.newbv.core.theme.BVTheme

/**
 * 视频进度条。
 *
 * 使用 Canvas 绘制多层进度线：背景轨道、章节分段高亮、缓冲进度、播放进度。
 * 三段轨道用「色相 + 明度」双重区分，避免混在一起：
 * 已播放用品牌色（primary），已缓冲用较亮的中性灰，未缓冲用很暗的中性灰。
 * 播放器固定运行在深色主题下，均能保证黑底上的可见性。
 *
 * 有章节数据（[chapterSegments] 非空）时，在背景轨道上按时间比例绘制章节分段：
 * 相邻分段之间留出缺口形成可见的章节边界，播放进度按分段内比例填充；
 * 交互模式还会在进度条上方按分段显示章节名标签（过窄的分段不显示）。
 * 无章节数据时回退为单线绘制，渲染与原行为完全一致。
 *
 * 支持两种显示模式：
 * - **常显模式**（[isPersistentSeek] = true）：2dp 细线，不显示缓冲进度，
 *   用于播放器底部始终可见的进度条。
 * - **交互模式**（[isPersistentSeek] = false）：8dp 粗线，显示缓冲进度，
 *   用于控制器信息栏中的可交互 seek bar。
 *
 * @param modifier 修饰符
 * @param duration 视频总时长（毫秒）
 * @param position 当前播放位置（毫秒）
 * @param bufferedPercentage 缓冲百分比（0-100）
 * @param isPersistentSeek 是否为常显模式
 * @param chapterSegments 归一化后的章节分段（[VideoChapters.normalize] 的产物），
 *   需已按 fromMs 升序、互不重叠、裁剪进片长
 */
@Composable
fun VideoProgressSeek(
    modifier: Modifier = Modifier,
    duration: Long,
    position: Long,
    bufferedPercentage: Int,
    isPersistentSeek: Boolean,
    chapterSegments: List<VideoChapterSegment> = emptyList(),
) {
    val trackWidthDp = if (isPersistentSeek) 2.dp else 8.dp
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val bufferedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    // 章节分段高亮：比背景亮、比缓冲暗，让章节覆盖范围在轨道上可辨
    val chapterTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    // 分段缺口宽度（可见部分）；常显模式线更细，缺口等比缩小
    val chapterGap = if (isPersistentSeek) 1.dp else 2.dp

    // 章节名标签只在交互模式、且有有效章节与时长时显示
    val showChapterLabels = !isPersistentSeek && duration > 0 && chapterSegments.isNotEmpty()
    val labelLayout = chapterLabelLayout(chapterSegments, duration)

    Column(modifier = modifier) {
        if (showChapterLabels) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(ChapterLabelHeight)) {
                val labelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                labelLayout.forEachIndexed { index, label ->
                    if (label == null) return@forEachIndexed
                    Text(
                        text = label.content,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .offset(x = maxWidth * label.leftFraction)
                                .width(maxWidth * label.widthFraction),
                        color = labelColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        Canvas(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(trackWidthDp)
                    .clip(RoundedCornerShape(50)),
        ) {
            val trackWidthPx = trackWidthDp.toPx()

            // 背景轨道
            drawLine(
                color = inactiveTrackColor,
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = trackWidthPx,
                cap = StrokeCap.Round,
            )

            // 章节分段高亮（有章节数据时）
            val segmentBounds =
                computeChapterSegmentBounds(
                    segments = chapterSegments,
                    durationMs = duration,
                    trackWidthPx = size.width,
                    strokeWidthPx = trackWidthPx,
                    gapPx = chapterGap.toPx(),
                )
            for ((startX, endX) in segmentBounds) {
                if (endX <= startX) continue
                drawLine(
                    color = chapterTrackColor,
                    start = Offset(startX, center.y),
                    end = Offset(endX, center.y),
                    strokeWidth = trackWidthPx,
                    cap = StrokeCap.Round,
                )
            }

            // 缓冲进度（仅交互模式显示）
            if (!isPersistentSeek && bufferedPercentage > 0) {
                drawLine(
                    color = bufferedTrackColor,
                    start = Offset(trackWidthPx / 2, center.y),
                    end = Offset(size.width * bufferedPercentage / 100, center.y),
                    strokeWidth = trackWidthPx,
                    cap = StrokeCap.Round,
                )
            }

            // 播放进度
            if (duration > 0) {
                if (segmentBounds.isEmpty()) {
                    val progressRatio = (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                    drawLine(
                        color = activeTrackColor,
                        start = Offset(trackWidthPx / 2, center.y),
                        end = Offset(size.width * progressRatio, center.y),
                        strokeWidth = trackWidthPx,
                        cap = StrokeCap.Round,
                    )
                } else {
                    // 有章节时按分段填充：段内部分填充 primary，章节之间的空隙不填充
                    chapterSegments.forEachIndexed { index, segment ->
                        val (startX, endX) = segmentBounds[index]
                        if (endX <= startX) return@forEachIndexed
                        val playedFraction = chapterPlayedFraction(segment, position)
                        if (playedFraction <= 0f) return@forEachIndexed
                        drawLine(
                            color = activeTrackColor,
                            start = Offset(startX, center.y),
                            end = Offset(startX + (endX - startX) * playedFraction, center.y),
                            strokeWidth = trackWidthPx,
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 计算章节分段在进度条上的绘制区间（像素）。
 *
 * 每段按时间比例映射到轨道宽度；时间上首尾相接的相邻分段之间在边界处各缩进
 * [gapPx] 形成可见缺口，时间上不连续的分段之间天然有间隙，不额外缩进。
 * 返回列表与 [segments] 一一对应（过窄的分段收成空区间，绘制时跳过）。
 *
 * @param segments 归一化后的章节分段（升序、不重叠、已裁剪进片长）
 * @param durationMs 视频总时长（毫秒）；<= 0 或分段为空时返回空列表
 * @param trackWidthPx 轨道总宽度（像素）
 * @param strokeWidthPx 分段描边宽度（像素），用于抵消 StrokeCap.Round 半圆帽的延伸
 * @param gapPx 相邻分段间可见缺口的宽度（像素）
 * @return 每段的 (startX, endX) 像素区间，与输入顺序一一对应
 */
internal fun computeChapterSegmentBounds(
    segments: List<VideoChapterSegment>,
    durationMs: Long,
    trackWidthPx: Float,
    strokeWidthPx: Float,
    gapPx: Float,
): List<Pair<Float, Float>> {
    if (durationMs <= 0L || segments.isEmpty() || trackWidthPx <= 0f) return emptyList()
    // StrokeCap.Round 的半圆帽会向端点外侧各延伸 strokeWidth/2，
    // 缺口需加上这部分宽度才能在相邻分段之间露出可见间隙
    val inset = strokeWidthPx / 2f + gapPx / 2f
    val bounds = ArrayList<Pair<Float, Float>>(segments.size)
    for ((index, segment) in segments.withIndex()) {
        val startX = segment.fromMs / durationMs.toFloat() * trackWidthPx
        val endX = segment.toMs / durationMs.toFloat() * trackWidthPx
        val previous = segments.getOrNull(index - 1)
        val next = segments.getOrNull(index + 1)
        val adjustedStart = if (previous != null && previous.toMs >= segment.fromMs) startX + inset else startX
        val adjustedEnd = if (next != null && next.fromMs <= segment.toMs) endX - inset else endX
        bounds +=
            if (adjustedEnd > adjustedStart) {
                adjustedStart to adjustedEnd
            } else {
                // 过窄的分段无法容纳缺口：收成空区间占位，保持与输入一一对应
                startX to startX
            }
    }
    return bounds
}

/**
 * 计算播放位置在单个章节分段内的已播比例（0-1）。
 *
 * 位置在分段终点之后（含最后一段延伸到片尾的部分）视为已播满，
 * 在起点之前（章节间空隙）为未播放。
 */
internal fun chapterPlayedFraction(
    segment: VideoChapterSegment,
    positionMs: Long,
): Float =
    when {
        positionMs >= segment.toMs -> 1f
        positionMs <= segment.fromMs -> 0f
        else -> (positionMs - segment.fromMs).toFloat() / (segment.toMs - segment.fromMs).toFloat()
    }

/** 章节名标签行高度；单行文本，留出足够空间避免被裁切。 */
private val ChapterLabelHeight = 18.dp

/** 章节名标签在进度条上的布局：水平起点与宽度均为轨道宽度比例（0-1）。 */
internal data class ChapterLabelLayout(
    val content: String,
    val leftFraction: Float,
    val widthFraction: Float,
)

/**
 * 计算章节名标签的布局比例，供交互模式在进度条上方按分段显示标签。
 *
 * 每个分段按时间比例映射到轨道水平区间 [leftFraction, leftFraction + widthFraction]；
 * 过窄（宽度比例 <= [minWidthFraction]）的分段返回 null 表示不显示标签，避免文字重叠。
 * 返回列表与 [segments] 一一对应，便于与分段高亮对齐。
 *
 * @param segments 归一化后的章节分段（升序、不重叠、已裁剪进片长）
 * @param durationMs 视频总时长（毫秒）；<= 0 时全部返回 null
 * @param minWidthFraction 标签显示的最小宽度比例，默认 0.08（约 8% 片长）
 * @return 每段的标签布局；过窄或无效分段为 null，与输入顺序一一对应
 */
internal fun chapterLabelLayout(
    segments: List<VideoChapterSegment>,
    durationMs: Long,
    minWidthFraction: Float = 0.08f,
): List<ChapterLabelLayout?> {
    if (durationMs <= 0L) return List(segments.size) { null }
    return segments.map { segment ->
        val left = (segment.fromMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val right = (segment.toMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        val width = right - left
        if (width <= minWidthFraction || segment.content.isBlank()) {
            null
        } else {
            ChapterLabelLayout(
                content = segment.content,
                leftFraction = left,
                widthFraction = width,
            )
        }
    }
}

// region Previews

@Preview(showBackground = true)
@Composable
private fun VideoProgressSeekPersistentPreview() {
    BVTheme {
        VideoProgressSeek(
            duration = 100_000L,
            position = 35_000L,
            bufferedPercentage = 60,
            isPersistentSeek = true,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VideoProgressSeekNormalPreview() {
    BVTheme {
        VideoProgressSeek(
            duration = 100_000L,
            position = 35_000L,
            bufferedPercentage = 60,
            isPersistentSeek = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VideoProgressSeekChaptersPreview() {
    BVTheme {
        VideoProgressSeek(
            duration = 100_000L,
            position = 35_000L,
            bufferedPercentage = 60,
            isPersistentSeek = false,
            chapterSegments =
                listOf(
                    VideoChapterSegment(0L, 20_000L, "开场"),
                    VideoChapterSegment(20_000L, 45_000L, "正片"),
                    VideoChapterSegment(45_000L, 100_000L, "片尾"),
                ),
        )
    }
}

// endregion
