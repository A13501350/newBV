package dev.frost819.newbv.app.ui.screen.settings.content

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.data.AccountRepositoryImpl
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.settings.OptionDialog
import dev.frost819.newbv.app.ui.component.settings.SettingListItem
import dev.frost819.newbv.app.util.TeenModeTimer
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 青少年模式设置 ViewModel。
 *
 * 负责读取当前用户的锁密码（[dev.frost819.newbv.data.db.entity.UserEntity.lock]），
 * 供设置修改前的验证使用。
 *
 * @property accountRepository 账户仓库。
 */
@HiltViewModel
class TeenModeSettingsViewModel
    @Inject
    constructor(
        private val accountRepository: AccountRepositoryImpl,
    ) : ViewModel() {
        private var cachedLock: String? = null

        /**
         * 当前登录用户的锁密码。
         *
         * 进程内缓存，避免每次修改设置都查库；切换账号后重新进入设置页会重新读取。
         *
         * @return 锁密码；未登录或未设置锁时为空串。
         */
        suspend fun currentLock(): String {
            cachedLock?.let { return it }
            val lock = accountRepository.findUserByUid(Prefs.uid)?.lock.orEmpty()
            cachedLock = lock
            return lock
        }
    }

/**
 * 青少年模式设置页。
 *
 * 保护开关、每日观看时长（0-120 分钟，0 = 不限制）、单次休息时长。
 * 已设置用户锁时，修改任何设置前需通过 D-Pad 方向键序列密码验证；
 * 未设置锁则直接放行（最小实现，密码形态与用户锁设置页 `UserLockSettingsScreen` 一致）。
 *
 * @param modifier 修饰符。
 * @param screenFocusSaver 设置页共享焦点恢复器。
 */
@Composable
fun TeenModeSetting(
    modifier: Modifier = Modifier,
    screenFocusSaver: FocusSaver? = null,
) {
    val viewModel: TeenModeSettingsViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val teenState by TeenModeTimer.state.collectAsState()

    var enabled by remember { mutableStateOf(Prefs.teenModeEnabled) }
    var watchLimitMin by remember { mutableStateOf(Prefs.teenWatchLimitMin) }
    var restLimitMin by remember { mutableStateOf(Prefs.teenRestLimitMin) }

    // 本次进入设置页只需验证一次；验证状态不跨页面保留
    var verified by rememberSaveable { mutableStateOf(false) }
    var showVerifyDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showWatchLimitDialog by remember { mutableStateOf(false) }
    var showRestDurationDialog by remember { mutableStateOf(false) }

    // 进入页面刷新一次展示值（含进行中的播放段），并让外部设置变更同步到计时器
    LaunchedEffect(Unit) {
        TeenModeTimer.refresh()
    }

    val watchLimitLabel = watchLimitMin.takeIf { it > 0 }?.let { "$it 分钟" } ?: "不限制"

    /**
     * 需要验证的设置修改入口。
     *
     * 未设置锁密码或本次已验证直接放行；否则记录待执行动作并弹出验证弹窗。
     */
    fun withVerification(action: () -> Unit) {
        scope.launch {
            val lock = viewModel.currentLock()
            if (verified || lock.isEmpty()) {
                action()
            } else {
                pendingAction = action
                showVerifyDialog = true
            }
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "青少年模式",
            style = MaterialTheme.typography.displaySmall,
        )
        Spacer(modifier = Modifier.height(12.dp))

        SettingListItem(
            title = "保护开关",
            supportText = "开启后限制每日观看时长，到达上限强制休息",
            trailingContent = {
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        withVerification {
                            enabled = checked
                            Prefs.teenModeEnabled = checked
                            TeenModeTimer.onSettingsChanged()
                        }
                    },
                )
            },
            onClick = {
                withVerification {
                    enabled = !enabled
                    Prefs.teenModeEnabled = enabled
                    TeenModeTimer.onSettingsChanged()
                }
            },
        )

        SettingListItem(
            title = "每日观看时长",
            supportText = "上限：$watchLimitLabel · 已观看 ${teenState.watchedMs / 60_000L} 分钟",
            onClick = {
                withVerification { showWatchLimitDialog = true }
            },
        )

        SettingListItem(
            modifier = screenFocusSaver?.let { Modifier.focusSaverItem(it, "content_teen_mode") } ?: Modifier,
            title = "单次休息时长",
            supportText = "到达上限后需连续休息 $restLimitMin 分钟",
            onClick = {
                withVerification { showRestDurationDialog = true }
            },
        )
    }

    if (showWatchLimitDialog) {
        OptionDialog(
            options = WatchLimitOption.entries.toTypedArray(),
            selectedOption = WatchLimitOption.fromMinutes(watchLimitMin),
            onDismiss = { showWatchLimitDialog = false },
            onSelect = { option ->
                withVerification {
                    watchLimitMin = option.minutes
                    Prefs.teenWatchLimitMin = option.minutes
                    TeenModeTimer.onSettingsChanged()
                }
            },
            getDisplayName = { it.label },
        )
    }

    if (showRestDurationDialog) {
        OptionDialog(
            options = RestDurationOption.entries.toTypedArray(),
            selectedOption = RestDurationOption.fromMinutes(restLimitMin),
            onDismiss = { showRestDurationDialog = false },
            onSelect = { option ->
                withVerification {
                    restLimitMin = option.minutes
                    Prefs.teenRestLimitMin = option.minutes
                    TeenModeTimer.onSettingsChanged()
                }
            },
            getDisplayName = { it.label },
        )
    }

    if (showVerifyDialog) {
        TeenModeVerifyDialog(
            onVerified = {
                verified = true
                showVerifyDialog = false
                pendingAction?.invoke()
                pendingAction = null
            },
            onDismiss = {
                showVerifyDialog = false
                pendingAction = null
            },
        )
    }
}

/**
 * 每日观看时长选项。
 *
 * @property minutes 上限分钟数，0 表示不限制。
 * @property label 显示文案。
 */
private enum class WatchLimitOption(
    val minutes: Int,
    val label: String,
) {
    Unlimited(0, "不限制"),
    Min15(15, "15 分钟"),
    Min30(30, "30 分钟"),
    Min45(45, "45 分钟"),
    Min60(60, "60 分钟"),
    Min90(90, "90 分钟"),
    Min120(120, "120 分钟"),
    ;

    companion object {
        /**
         * 按分钟数匹配选项。
         *
         * @param minutes 分钟数。
         * @return 匹配的选项；无匹配回退"不限制"。
         */
        fun fromMinutes(minutes: Int): WatchLimitOption = entries.firstOrNull { it.minutes == minutes } ?: Unlimited
    }
}

/**
 * 单次休息时长选项。
 *
 * @property minutes 休息分钟数。
 * @property label 显示文案。
 */
private enum class RestDurationOption(
    val minutes: Int,
    val label: String,
) {
    Min10(10, "10 分钟"),
    Min20(20, "20 分钟"),
    Min30(30, "30 分钟"),
    Min45(45, "45 分钟"),
    Min60(60, "60 分钟"),
    ;

    companion object {
        /**
         * 按分钟数匹配选项。
         *
         * @param minutes 分钟数。
         * @return 匹配的选项；无匹配回退默认 20 分钟。
         */
        fun fromMinutes(minutes: Int): RestDurationOption = entries.firstOrNull { it.minutes == minutes } ?: Min20
    }
}

/**
 * 青少年模式设置修改验证弹窗。
 *
 * 复用用户锁的 D-Pad 方向键序列密码：方向键输入（u/d/l/r），
 * 确认键提交，返回键退格、输入为空时取消。
 *
 * @param onVerified 验证通过回调。
 * @param onDismiss 取消回调。
 */
@Composable
private fun TeenModeVerifyDialog(
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    val viewModel: TeenModeSettingsViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var inputPassword by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth(0.4f)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> {
                                inputPassword += "u"
                                true
                            }
                            Key.DirectionDown -> {
                                inputPassword += "d"
                                true
                            }
                            Key.DirectionLeft -> {
                                inputPassword += "l"
                                true
                            }
                            Key.DirectionRight -> {
                                inputPassword += "r"
                                true
                            }
                            Key.DirectionCenter -> {
                                scope.launch {
                                    val lock = viewModel.currentLock()
                                    if (inputPassword == lock) {
                                        onVerified()
                                    } else {
                                        message = "密码错误"
                                        inputPassword = ""
                                    }
                                }
                                true
                            }
                            Key.Back -> {
                                if (inputPassword.isNotEmpty()) {
                                    inputPassword = inputPassword.drop(1)
                                } else {
                                    onDismiss()
                                }
                                true
                            }
                            else -> false
                        }
                    },
            shape = RoundedCornerShape(12.dp),
            colors =
                SurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
            tonalElevation = 4.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text(
                    text = "输入密码验证",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = displayDpadPassword(inputPassword),
                    style = MaterialTheme.typography.headlineLarge,
                )
                if (message.isNotEmpty()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "方向键输入 · 确认键提交 · 返回键删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** 将方向键序列转为箭头符号展示。 */
private fun displayDpadPassword(password: String): String =
    password
        .replace("u", "↑")
        .replace("d", "↓")
        .replace("l", "←")
        .replace("r", "→")
        .ifEmpty { " " }
