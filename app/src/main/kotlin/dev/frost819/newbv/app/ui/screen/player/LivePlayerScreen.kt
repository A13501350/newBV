package dev.frost819.newbv.app.ui.screen.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.frost819.newbv.app.ui.component.player.LivePlayerController
import dev.frost819.newbv.app.ui.component.rememberDoublePressExit
import dev.frost819.newbv.app.ui.navigation.UserSpaceRoute
import dev.frost819.newbv.app.ui.state.live.LivePlayerState
import dev.frost819.newbv.app.ui.state.live.SuperChatUiItem
import dev.frost819.newbv.app.ui.state.live.errorMessage
import dev.frost819.newbv.app.viewmodel.live.LivePlayerViewModel
import dev.frost819.newbv.app.viewmodel.player.DanmakuViewModel
import dev.frost819.newbv.danmaku.component.DanmakuPlayerCompose
import dev.frost819.newbv.player.BvVideoPlayer

/**
 * 直播播放器页面。
 *
 * 使用 [LivePlayerController] 作为根控制器，整合 [LivePlayerViewModel]（播放控制）
 * 和 [DanmakuViewModel]（弹幕配置）。
 *
 * UI 层负责协调两者：
 * - 播放/暂停 → 同步弹幕播放/暂停
 * - 弹幕开关 → 调用 DanmakuViewModel.toggleDanmaku()
 * - 弹幕设置 → 调用 DanmakuViewModel.updateDanmakuState()
 */
@Composable
fun LivePlayerScreen(
    navController: NavController,
    viewModel: LivePlayerViewModel,
    danmakuViewModel: DanmakuViewModel,
) {
    val uiState by viewModel.uiState.collectAsState()
    val danmakuState by danmakuViewModel.danmakuState.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val videoPlayer = viewModel.videoPlayer
    val danmakuPlayer = danmakuViewModel.danmakuPlayer

    val danmakuEnabled = danmakuState.enabledTypes.isNotEmpty()

    // 双击退出：TV 遥控器（Controller onBack）和非 TV（BackHandler）共用同一计时器
    val handleBack =
        rememberDoublePressExit(
            onExit = { navController.popBackStack() },
            message = "再按一次退出播放",
        )
    BackHandler { handleBack() }

    LaunchedEffect(uiState.playerState) {
        when (uiState.playerState) {
            LivePlayerState.Playing -> danmakuViewModel.play()
            LivePlayerState.Paused, LivePlayerState.Ended, is LivePlayerState.Error -> danmakuViewModel.pause()
            else -> {}
        }
    }

    LaunchedEffect(uiState.isBuffering) {
        if (uiState.isBuffering) {
            danmakuViewModel.pause()
        } else if (uiState.playerState == LivePlayerState.Playing) {
            danmakuViewModel.play()
        }
    }

    LivePlayerController(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        isPlaying = uiState.playerState == LivePlayerState.Playing,
        isBuffering = uiState.isBuffering,
        errorMessage = uiState.playerState.errorMessage,
        title = uiState.title,
        areaName = uiState.areaName,
        onlineCount = uiState.onlineCount,
        clock = getClock(),
        danmakuEnabled = danmakuEnabled,
        danmakuState = danmakuState,
        availableQualities = uiState.availableQualities,
        currentQuality = uiState.currentQuality,
        availableLines = uiState.availableLines,
        currentLine = uiState.currentLine,
        onBack = handleBack,
        onPlayPause = { viewModel.togglePlayPause() },
        onRefresh = { viewModel.refresh() },
        onGoToUpPage = {
            val mid = uiState.upMid
            if (mid != 0L) {
                navController.navigate(
                    UserSpaceRoute(mid = mid, name = uiState.upName, face = uiState.upFace),
                ) {
                    launchSingleTop = true
                }
            }
        },
        onToggleDanmaku = { danmakuViewModel.toggleDanmaku() },
        onQualityChange = { viewModel.changeQuality(it) },
        onLineChange = { viewModel.changeLine(it) },
        onDanmakuSettingChange = { danmakuViewModel.updateDanmakuState(it) },
        debugInfo = debugInfo,
    ) {
        if (videoPlayer != null) {
            val aspectRatio =
                if (videoPlayer.videoWidth > 0 && videoPlayer.videoHeight > 0) {
                    videoPlayer.videoWidth.toFloat() / videoPlayer.videoHeight.toFloat()
                } else {
                    16f / 9f
                }
            BvVideoPlayer(
                modifier = Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(aspectRatio),
                videoPlayer = videoPlayer,
            )
        }

        if (danmakuPlayer != null && danmakuEnabled) {
            DanmakuPlayerCompose(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(danmakuState.alpha),
                danmakuPlayer = danmakuPlayer,
            )
        }

        // 醒目留言（SC）卡片队列：贴右上角展示，新 SC 从顶部进入
        if (uiState.superChats.isNotEmpty()) {
            SuperChatQueue(
                superChats = uiState.superChats,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        // 顶部让出控制器信息栏高度，避免与标题栏叠压
                        .padding(top = 80.dp, end = 16.dp),
            )
        }
    }
}

/**
 * 获取当前时钟（小时，分钟）。
 */
private fun getClock(): Pair<Int, Int> {
    val calendar = java.util.Calendar.getInstance()
    return calendar.get(java.util.Calendar.HOUR_OF_DAY) to calendar.get(java.util.Calendar.MINUTE)
}

/**
 * 醒目留言（SC）卡片纵向队列。
 *
 * 列表头部为最新 SC，新卡片从顶部进入；最多渲染 [maxVisible] 张，溢出的旧卡片
 * 保留在状态中继续倒计时、仅超出部分不渲染，避免长时间高密度 SC 遮挡画面。
 *
 * @param superChats SC 卡片列表，头部为最新
 * @param modifier 布局修饰符
 * @param maxVisible 最多同时渲染的卡片数
 */
@Composable
private fun SuperChatQueue(
    superChats: List<SuperChatUiItem>,
    modifier: Modifier = Modifier,
    maxVisible: Int = 3,
) {
    Column(
        modifier = modifier.width(240.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        superChats.take(maxVisible).forEach { item ->
            SuperChatCard(item = item)
        }
    }
}

/**
 * 单张醒目留言卡片。
 *
 * 展示发送者头像/昵称、金额与留言内容；底色优先用服务端下发的
 * [SuperChatUiItem.backgroundColor]，缺失时按金额取 B 站官方 SC 梯度兜底。
 */
@Composable
private fun SuperChatCard(item: SuperChatUiItem) {
    val backgroundColor = item.backgroundColor?.let { Color(it) } ?: superChatPriceColor(item.price)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.face.isNotBlank()) {
                AsyncImage(
                    modifier =
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape),
                    model = item.face,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.username,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "¥${item.price}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.message,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * B 站官方 SC 金额→底色梯度（web 端配色：蓝/蓝/绿/灰/橙/红），
 * 服务端未下发 background_color 时兜底。Pair 首项为档位下限（人民币元）。
 */
private val SUPER_CHAT_PRICE_COLORS: List<Pair<Int, Color>> =
    listOf(
        30 to Color(0xFF1E90FF),
        50 to Color(0xFF1E90FF),
        100 to Color(0xFF43D19E),
        500 to Color(0xFF999999),
        1000 to Color(0xFFFFA127),
        10000 to Color(0xFFFF4B4C),
    )

/** 按金额取 SC 底色：低于最小档取最浅档，高于最大档取最深档。 */
private fun superChatPriceColor(price: Int): Color =
    SUPER_CHAT_PRICE_COLORS.lastOrNull { price >= it.first }?.second ?: SUPER_CHAT_PRICE_COLORS.first().second
