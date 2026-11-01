package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import dev.frost819.newbv.app.entity.player.SponsorActionType
import dev.frost819.newbv.app.entity.player.SponsorCategories
import dev.frost819.newbv.app.entity.player.SponsorSegment
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
 * 相邻分段之间留出缺口形成可见的章节边界，播放进度按分段内比例填充。
 * 无章节数据时回退为单线绘制，渲染与原行为完全一致。
 *
 * 支持两种显示模式：
 * - **常显模式**（[isPersistentSeek] = true）：2dp 细线，不显示缓冲进度，
 *   用于播放器底部始终可见的进度条。
 * - **交互模式**（[isPersistentSeek] = false）：8dp 粗线，显示缓冲进度，
 *   用于控制器信息栏中的可交互 seek bar。
 *
 * 空降片段标记（[skipSegments]）绘制在播放进度之上，按分类着色，
 * 使已播与未播区域的片段覆盖范围都清晰可辨。
 *
 * @param modifier 修饰符
 * @param duration 视频总时长（毫秒）
 * @param position 当前播放位置（毫秒）
 * @param bufferedPercentage 缓冲百分比（0-100）
 * @param isPersistentSeek 是否为常显模式
 * @param chapterSegments 归一化后的章节分段（[VideoChapters.normalize] 的产物），
 *   需已按 fromMs 升序、互不重叠、裁剪进片长
 * @param skipSegments 空降片段列表（已归一化、按起点升序），空列表不绘制标记层
 */
@Composable
fun VideoProgressSeek(
    modifier: Modifier = Modifier,
    duration: Long,
    position: Long,
    bufferedPercentage: Int,
    isPersistentSeek: Boolean,
    chapterSegments: List<VideoChapterSegment> = emptyList(),
    skipSegments: List<SponsorSegment> = emptyList(),
) {
    val trackWidthDp = if (isPersistentSeek) 2.dp else 8.dp
    val activeTrackColor = MaterialTheme.colorScheme.primary
    val bufferedTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    // 章节分段高亮：比背景亮、比缓冲暗，让章节覆盖范围在轨道上可辨
    val chapterTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    // 分段缺口宽度（可见部分）；常显模式线更细，缺口等比缩小
    val chapterGap = if (isPersistentSeek) 1.dp else 2.dp

    Canvas(
        modifier =
            modifier
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

        // 空降片段标记：绘制在播放进度之上，已播/未播区域都清晰可辨
        if (skipSegments.isNotEmpty() && duration > 0) {
            val skipBounds =
                computeChapterSegmentBounds(
                    segments =
                        skipSegments.map { segment ->
                            VideoChapterSegment(
                                fromMs = segment.startMs,
                                toMs = segment.endMs,
                                content = segment.category,
                            )
                        },
                    durationMs = duration,
                    trackWidthPx = size.width,
                    strokeWidthPx = trackWidthPx,
                    gapPx = chapterGap.toPx(),
                )
            skipSegments.forEachIndexed { index, segment ->
                val (startX, endX) = skipBounds[index]
                if (endX <= startX) return@forEachIndexed
                drawLine(
                    color = Color(SponsorCategories.colorOf(segment.category)).copy(alpha = 0.6f),
                    start = Offset(startX, center.y),
                    end = Offset(endX, center.y),
                    strokeWidth = trackWidthPx,
                    cap = StrokeCap.Round,
                )
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

@Preview(showBackground = true)
@Composable
private fun VideoProgressSeekSkipSegmentsPreview() {
    BVTheme {
        VideoProgressSeek(
            duration = 100_000L,
            position = 35_000L,
            bufferedPercentage = 60,
            isPersistentSeek = false,
            skipSegments =
                listOf(
                    SponsorSegment(
                        uuid = "1",
                        category = SponsorCategories.SPONSOR,
                        actionType = SponsorActionType.SKIP,
                        startMs = 10_000L,
                        endMs = 20_000L,
                    ),
                    SponsorSegment(
                        uuid = "2",
                        category = SponsorCategories.OUTRO,
                        actionType = SponsorActionType.SKIP,
                        startMs = 90_000L,
                        endMs = 98_000L,
                    ),
                ),
        )
    }
}

// endregion
