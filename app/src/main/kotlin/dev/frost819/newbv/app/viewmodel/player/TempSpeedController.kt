package dev.frost819.newbv.app.viewmodel.player

/**
 * 长按确认键临时倍速的放大倍数（对齐 MyTVB：按住 OK 临时 2 倍速，松开恢复）。
 */
private const val TEMP_SPEED_MULTIPLIER = 2f

/**
 * 长按确认键临时倍速的上限（MyTVB 默认倍速上限 3.0）。
 */
private const val MAX_TEMP_SPEED = 3f

/**
 * 长按确认键临时倍速状态机。
 *
 * 纯 JVM 协作者，通过构造注入的 lambda 读/写速度与提示回调，便于单元测试。
 * 语义（对齐 MyTVB 的 OK 长按临时倍速）：
 * - [startTempSpeed]：记录用户设定速度为恢复基准，把播放器引擎速度临时设为
 *   原速 ×[TEMP_SPEED_MULTIPLIER]（封顶 [MAX_TEMP_SPEED]），并通过 [onTip] 提示实际速度
 * - [stopTempSpeed]：恢复记录的原速
 *
 * 临时速度不写入用户设定速度（[PlayerUiState.playSpeed]），倍速菜单显示保持不变。
 *
 * @param readBaseSpeed 读取用户设定速度（临时倍速的恢复基准）
 * @param writeEngineSpeed 写播放器引擎速度；播放器未就绪等场景允许抛异常
 * @param onTip 临时倍速提示回调，参数为实际生效速度文本（如 "2.0x"）
 */
class TempSpeedController(
    private val readBaseSpeed: () -> Float,
    private val writeEngineSpeed: (Float) -> Unit,
    private val onTip: (String) -> Unit,
) {
    /** 临时倍速会话是否激活。 */
    var isTempSpeedActive: Boolean = false
        private set

    /** 进入临时倍速前记录的用户设定速度，松开确认键后恢复。 */
    private var speedBeforeTempBoost: Float = 1f

    /**
     * 进入临时倍速会话。
     *
     * 已激活时直接返回（去重长按 repeat 补发的 KeyDown）。
     * 写速度 lambda 抛异常时保持未激活（不记录基准、不提示），避免播放器未就绪时
     * 进入激活态导致确认键 KeyUp 被劫持为「恢复原速」而丢失短按播放/暂停语义。
     */
    fun startTempSpeed() {
        if (isTempSpeedActive) return
        val baseSpeed = readBaseSpeed()
        // 临时速度 = 用户设定速度 ×2，超过上限封顶（MyTVB 默认倍速上限 3.0）
        val boostedSpeed = (baseSpeed * TEMP_SPEED_MULTIPLIER).coerceAtMost(MAX_TEMP_SPEED)
        // 先写引擎、成功后才激活：写失败时不进入激活态，
        // 确认键 KeyUp 仍走短按播放/暂停，引擎速度也不会滞留在加速状态
        val writeResult = runCatching { writeEngineSpeed(boostedSpeed) }
        if (writeResult.isFailure) return
        speedBeforeTempBoost = baseSpeed
        isTempSpeedActive = true
        onTip("${boostedSpeed}x")
    }

    /**
     * 退出临时倍速会话并恢复记录的用户设定速度。
     *
     * 未激活时调用无副作用（退出播放器、覆盖层打开等兜底路径可随意调用）。
     * 恢复写速度失败时吞掉异常：激活态必须退出以还原确认键 KeyUp 语义，
     * 恢复失败通常意味着播放器已不可用，无从再恢复。
     */
    fun stopTempSpeed() {
        if (!isTempSpeedActive) return
        isTempSpeedActive = false
        runCatching { writeEngineSpeed(speedBeforeTempBoost) }
    }
}
