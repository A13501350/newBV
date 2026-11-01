package dev.frost819.newbv.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.navigation.VideoPlayerRoute
import dev.frost819.newbv.app.util.PsasRepository
import dev.frost819.newbv.app.util.TeenModeTimer
import dev.frost819.newbv.app.util.formatHourMinSec
import dev.frost819.newbv.core.focus.touchClickable
import dev.frost819.newbv.core.log.Loggers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private val logger = Loggers.get("TeenModeRestOverlay")

/** 休息倒计时刷新间隔（毫秒）。 */
private const val COUNTDOWN_REFRESH_MS = 1_000L

/**
 * 青少年模式全屏休息遮罩。
 *
 * 挂在 MainActivity 顶层（单 Activity 架构下覆盖一切路由），休息期内：
 * - 以独立 Dialog 窗口渲染全屏遮罩，D-Pad 焦点无法穿透到下层内容，
 *   返回键被 [Dialog] 拦截（onDismissRequest 为空实现），无法退出休息；
 * - 显示休息倒计时与今日观看时长提示；
 * - 提供"看一集公益广告"入口：遮罩在公益广告播放期间自动隐藏，
 *   播放器退出（或切到其它视频）后自动恢复显示，倒计时全程继续。
 *
 * @param navController 导航控制器，用于跳转公益广告播放器。
 * @param modifier 修饰符。
 */
@Composable
fun TeenModeRestOverlay(
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val state by TeenModeTimer.state.collectAsState()
    val psasAid by PsasRepository.activePsasAid.collectAsState()

    // 冷启动 / 进程重建：同步持久化的休息状态（休息中杀进程重启仍然遮罩）
    LaunchedEffect(Unit) {
        TeenModeTimer.refresh()
    }

    // 休息期每秒刷新倒计时；到期由 refresh() 内部自动复位并隐藏遮罩。
    // 公益广告播放期间循环持续运行（key 只看 resting），保证倒计时照常推进
    LaunchedEffect(state.resting) {
        if (!state.resting) return@LaunchedEffect
        while (isActive) {
            delay(COUNTDOWN_REFRESH_MS)
            TeenModeTimer.refresh()
        }
    }

    if (!state.resting || psasAid != 0L) return

    Dialog(
        onDismissRequest = {
            // 休息期强制遮罩：返回键不关闭
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        RestOverlayContent(
            remainingSeconds = state.remainingRestSeconds,
            watchedMinutes = state.watchedMs / 60_000L,
            onPlayPsas = {
                val video = PsasRepository.getRandomVideo()
                if (video != null) {
                    // 先标记会话再导航：遮罩随状态更新立即隐藏，避免盖住播放器
                    PsasRepository.beginPlayback(video.aid)
                    logger.info { "launch psas playback. aid=${video.aid}" }
                    navController.navigate(
                        VideoPlayerRoute(
                            aid = video.aid,
                            cid = video.cid,
                            title = video.title,
                        ),
                    ) {
                        launchSingleTop = true
                    }
                }
            },
        )
    }
}

/**
 * 休息遮罩内容。
 *
 * @param remainingSeconds 休息剩余秒数。
 * @param watchedMinutes 今日已观看分钟数。
 * @param onPlayPsas 播放公益广告回调。
 */
@Composable
private fun RestOverlayContent(
    remainingSeconds: Long,
    watchedMinutes: Long,
    onPlayPsas: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    // 弹窗内容出现时不会自动聚焦，手动请求到公益广告按钮保证 D-Pad 可用
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(48.dp),
        ) {
            Text(
                text = "休息一下",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
            )
            Text(
                text = "还需休息 ${(remainingSeconds * 1000L).formatHourMinSec()}",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
            )
            Text(
                text = "今日已观看 $watchedMinutes 分钟，已到达观看上限",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "休息结束后可继续观看，也可以先看一集公益广告",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Button(
                modifier =
                    Modifier
                        .focusRequester(focusRequester)
                        .touchClickable(onClick = onPlayPsas),
                onClick = onPlayPsas,
            ) {
                Text(text = "看一集公益广告")
            }
        }
    }
}
