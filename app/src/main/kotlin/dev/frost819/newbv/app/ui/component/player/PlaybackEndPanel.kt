package dev.frost819.newbv.app.ui.component.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.videocard.SmallVideoCard
import dev.frost819.newbv.app.ui.component.videocard.VideoCardData
import dev.frost819.newbv.app.util.PlayerConstants
import dev.frost819.newbv.core.focus.touchClickable
import dev.frost819.newbv.core.theme.BVTheme

/**
 * 连播倒计时总秒数，由 [PlayerConstants.COUNTDOWN_DURATION_MS] 换算。
 */
private val COUNTDOWN_TOTAL_SECONDS: Int = (PlayerConstants.COUNTDOWN_DURATION_MS / 1_000L).toInt()

/**
 * 播放结束推荐面板。
 *
 * YouTube TV 形态的播放结束覆盖层：黑色半透明底、左侧重播按钮
 * （连播倒计时激活时显示数字环）、右侧相关视频 4 列网格。
 * 无相关视频时仅居中显示重播按钮。
 *
 * 焦点交互：面板显示时焦点落在重播按钮（见 [AGENTS.md] 11.2.2，
 * requestFocus 以 runCatching 保护）；方向键由 Compose 焦点系统在
 * 重播按钮与网格卡片之间自然导航；OK 激活当前聚焦的按钮/卡片。
 *
 * @param modifier 修饰符
 * @param visible 是否显示
 * @param countdownSeconds 连播倒计时剩余秒数，0 表示无连播倒计时（不显示数字环）
 * @param relatedVideos 相关视频列表
 * @param onReplay 点击重播按钮回调
 * @param onVideoClicked 点击相关视频卡片回调
 */
@Composable
fun PlaybackEndPanel(
    modifier: Modifier = Modifier,
    visible: Boolean,
    countdownSeconds: Int,
    relatedVideos: List<VideoCardData>,
    onReplay: () -> Unit,
    onVideoClicked: (VideoCardData) -> Unit,
) {
    val replayFocusRequester = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            // 面板组合完成后再请求焦点；节点未就绪时 requestFocus 会抛异常，静默忽略
            runCatching { replayFocusRequester.requestFocus() }
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.92f)),
            ) {
                if (relatedVideos.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ReplayButton(
                            countdownSeconds = countdownSeconds,
                            onReplay = onReplay,
                            focusRequester = replayFocusRequester,
                        )
                    }
                } else {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 64.dp, vertical = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(40.dp),
                    ) {
                        ReplayButton(
                            countdownSeconds = countdownSeconds,
                            onReplay = onReplay,
                            focusRequester = replayFocusRequester,
                        )
                        RelatedVideoGrid(
                            modifier = Modifier.weight(1f),
                            relatedVideos = relatedVideos,
                            onVideoClicked = onVideoClicked,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 重播按钮：圆形按钮 + 连播倒计时数字环。
 *
 * [countdownSeconds] 大于 0 时在按钮外圈绘制倒计时进度环、按钮内显示
 * 剩余秒数；否则按钮内显示重播图标。
 *
 * @param countdownSeconds 连播倒计时剩余秒数，0 表示无倒计时
 * @param onReplay 点击回调
 * @param focusRequester 焦点请求器，用于面板显示时聚焦本按钮
 */
@Composable
private fun ReplayButton(
    countdownSeconds: Int,
    onReplay: () -> Unit,
    focusRequester: FocusRequester,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (countdownSeconds > 0) {
                CountdownRing(countdownSeconds = countdownSeconds)
            }
            Surface(
                onClick = onReplay,
                modifier =
                    Modifier
                        .size(112.dp)
                        .focusRequester(focusRequester)
                        .touchClickable(onClick = onReplay),
                shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                colors =
                    ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.16f),
                        focusedContainerColor = Color.White.copy(alpha = 0.32f),
                    ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (countdownSeconds > 0) {
                        Text(
                            text = "$countdownSeconds",
                            style = MaterialTheme.typography.displayMedium,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Replay,
                            contentDescription = "重播",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        val label = if (countdownSeconds > 0) "秒后自动播放下一集" else "重播"
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/**
 * 倒计时数字环。
 *
 * 以 [countdownSeconds] 占连播倒计时总时长的比例绘制圆环进度：
 * 满环起步，随倒计时递减逐渐消退。纯视觉组件，无交互。
 *
 * @param countdownSeconds 剩余秒数，须大于 0
 */
@Composable
private fun CountdownRing(countdownSeconds: Int) {
    val fraction =
        if (COUNTDOWN_TOTAL_SECONDS > 0) {
            (countdownSeconds.toFloat() / COUNTDOWN_TOTAL_SECONDS).coerceIn(0f, 1f)
        } else {
            0f
        }
    Canvas(modifier = Modifier.size(140.dp)) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        // 底环：淡色完整圆环，让进度环的消退可感知
        drawCircle(color = Color.White.copy(alpha = 0.2f), style = stroke)
        drawArc(
            color = Color.White,
            startAngle = -90f,
            sweepAngle = 360f * fraction,
            useCenter = false,
            style = stroke,
        )
    }
}

/**
 * 相关视频 4 列网格。
 *
 * @param modifier 修饰符
 * @param relatedVideos 相关视频列表
 * @param onVideoClicked 点击视频卡片回调
 */
@Composable
private fun RelatedVideoGrid(
    modifier: Modifier = Modifier,
    relatedVideos: List<VideoCardData>,
    onVideoClicked: (VideoCardData) -> Unit,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        Text(
            modifier = Modifier.padding(bottom = 12.dp),
            text = "相关视频",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = relatedVideos,
                // avid 理论上可能重复，拼接 cid 保证 key 唯一，避免组合崩溃
                key = { "${it.avid}_${it.cid}" },
            ) { video ->
                SmallVideoCard(
                    modifier = Modifier.fillMaxWidth(),
                    data = video,
                    onClick = { onVideoClicked(video) },
                )
            }
        }
    }
}

// region Previews

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlaybackEndPanelWithVideosPreview() {
    val sampleVideos =
        listOf(
            VideoCardData(
                avid = 1,
                cid = 101,
                title = "相关视频标题 1",
                cover = "",
                upName = "UP主A",
                playString = "1.2万播放",
                danmakuString = "300弹幕",
                timeString = "10:30",
            ),
            VideoCardData(
                avid = 2,
                cid = 102,
                title = "相关视频标题 2",
                cover = "",
                upName = "UP主B",
                playString = "5000播放",
                danmakuString = "100弹幕",
                timeString = "5:20",
            ),
        )

    BVTheme {
        PlaybackEndPanel(
            visible = true,
            countdownSeconds = 3,
            relatedVideos = sampleVideos,
            onReplay = {},
            onVideoClicked = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlaybackEndPanelReplayOnlyPreview() {
    BVTheme {
        PlaybackEndPanel(
            visible = true,
            countdownSeconds = 0,
            relatedVideos = emptyList(),
            onReplay = {},
            onVideoClicked = {},
        )
    }
}

// endregion
