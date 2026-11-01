package dev.frost819.newbv.app.util

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 青少年模式时钟抽象。
 *
 * 拆分两个时钟来源，分别承担不同的抗绕过职责（算法移植自 MyTVB TeenModeTimer）：
 * - [elapsedRealtime] 单调时钟：只关心"实际播放了多久"，用户修改系统时间不影响累计；
 * - [currentTimeMillis] 墙钟：休息期以真实时间流逝判定，杀进程 / 重启应用都无法绕过，
 *   系统时间回拨只会让休息"更长"而不会提前结束。
 */
interface TeenModeClock {
    /**
     * 单调流逝时间（毫秒），等价于 `SystemClock.elapsedRealtime()`。
     *
     * @return 自开机以来的毫秒数（含深度睡眠）。
     */
    fun elapsedRealtime(): Long

    /**
     * 墙钟时间（毫秒），等价于 `System.currentTimeMillis()`。
     *
     * @return Unix 纪元毫秒数，可能被用户修改。
     */
    fun currentTimeMillis(): Long
}

/**
 * 生产环境时钟实现。
 */
object SystemTeenModeClock : TeenModeClock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

/**
 * 青少年模式对外状态。
 *
 * @property resting 是否处于强制休息期。
 * @property remainingRestSeconds 休息剩余秒数（向上取整），未休息时为 0。
 * @property watchedMs 本次观看周期内累计观看毫秒（含进行中的播放段）。
 * @property watchLimitMinutes 每日观看上限（分钟），0 表示不限制。
 * @property restLimitMinutes 单次休息时长（分钟）。
 */
data class TeenModeState(
    val resting: Boolean = false,
    val remainingRestSeconds: Long = 0L,
    val watchedMs: Long = 0L,
    val watchLimitMinutes: Int = 0,
    val restLimitMinutes: Int = 0,
)

/**
 * 青少年模式 - 观看/休息时长计时器。
 *
 * 移植 MyTVB 的计时算法并改造为 Compose 状态流形态：
 * - 累计观看毫秒持久化到 [Prefs]，杀进程 / 重启后继续累加；
 * - 播放段计时用 [TeenModeClock.elapsedRealtime]，抗系统时间修改；
 * - 休息判定用 [TeenModeClock.currentTimeMillis]，防改时间 / 杀进程绕过；
 * - 达到观看上限后进入休息，休息到期清零累计进入新一轮观看周期；
 * - 保护开关关闭或观看上限为 0 时功能完全不生效。
 *
 * 线程模型：播放段起点仅在主线程读写（调用方为 Compose 效果与设置页），
 * 状态发布通过 [state]；偏好读写走 [Prefs] 的内存缓存（写磁盘异步）。
 */
object TeenModeTimer {
    private val logger = Loggers.get("TeenModeTimer")

    /** 播放段结算周期（毫秒）：播放中每 15 秒结算一次，与 MyTVB 一致。 */
    const val TICK_INTERVAL_MS: Long = 15_000L

    /** 每分钟毫秒数。 */
    private const val MILLIS_PER_MINUTE: Long = 60_000L

    /** 每秒毫秒数。 */
    private const val MILLIS_PER_SECOND: Long = 1_000L

    /** 时钟注入点（仅测试用），生产实现为 [SystemTeenModeClock]。 */
    @VisibleForTesting
    internal var clock: TeenModeClock = SystemTeenModeClock

    /** 当前播放段起始单调时钟；null 表示未在播放。 */
    private var segmentStartElapsedMs: Long? = null

    private val _state = MutableStateFlow(TeenModeState())

    /**
     * 青少年模式状态流。
     *
     * 随 [refresh]、播放段结算与休息到期自动更新，UI 收集后渲染休息遮罩与倒计时。
     */
    val state: StateFlow<TeenModeState> = _state.asStateFlow()

    /**
     * 时间限制是否生效。
     *
     * @return 保护开关开启且观看上限大于 0 时返回 true（0 分钟视为不限制）。
     */
    fun isLimitEnabled(): Boolean = Prefs.teenModeEnabled && Prefs.teenWatchLimitMin > 0

    /**
     * 播放开始（isPlaying 变 true）。
     *
     * 休息期间不重新计时，避免休息期后台播放继续累计；重复调用幂等。
     */
    fun onPlayStart() {
        if (!isLimitEnabled()) return
        if (_state.value.resting) return
        if (segmentStartElapsedMs != null) return
        segmentStartElapsedMs = clock.elapsedRealtime()
    }

    /**
     * 播放停止（isPlaying 变 false / 退出播放器）。
     *
     * 结算本段实际播放时长并累加，达上限则进入休息。
     * 休息中的残余段直接丢弃：不结算也不重置休息开始时间，避免休息被意外延长。
     */
    fun onPlayStop() {
        val startMs = segmentStartElapsedMs ?: return
        segmentStartElapsedMs = null
        if (!isLimitEnabled() || isRestingInternal()) {
            refresh()
            return
        }
        settleSegment(startMs)
    }

    /**
     * 播放中周期性结算（每 [TICK_INTERVAL_MS] 调用一次）。
     *
     * 与 MyTVB 不同：不自动初始化播放段起点 —— newBV 的播放上报来自
     * playerState 状态流，不会漏触发；仅在段进行中时结算。
     */
    fun tick() {
        if (!isLimitEnabled()) return
        val startMs = segmentStartElapsedMs ?: return
        if (isRestingInternal()) {
            // 休息中（含刚到期）：丢弃当前段，等休息结束后从头计时
            segmentStartElapsedMs = null
            refresh()
            return
        }
        settleSegment(startMs)
        segmentStartElapsedMs = clock.elapsedRealtime()
    }

    /**
     * 休息是否正在进行（含到期自动复位）。
     *
     * 到期（墙钟流逝 >= 休息时长）时清零累计与休息戳，恢复正常观看。
     *
     * @return 休息中返回 true；休息到期或功能未生效返回 false。
     */
    fun isResting(): Boolean = isRestingInternal()

    /**
     * 重新计算并对外发布状态。
     *
     * 调用时机：应用启动（同步持久化的休息状态）、休息倒计时循环、
     * 播放段结算、设置变更。
     */
    fun refresh() {
        if (!isLimitEnabled()) {
            // 开关关闭：清掉进行中的休息，避免重新开启后旧休息戳立刻误触发
            if (Prefs.teenRestStartMs != 0L) {
                Prefs.teenRestStartMs = 0L
            }
            segmentStartElapsedMs = null
            _state.value = TeenModeState()
            return
        }
        val resting = isRestingInternal()
        val restLimitMs = Prefs.teenRestLimitMin.toLong() * MILLIS_PER_MINUTE
        val watchedMs =
            if (resting) {
                Prefs.teenAccumulatedMs
            } else {
                // 未休息时把进行中的播放段也算进展示值，设置页/遮罩的显示更即时
                val liveSegmentMs =
                    segmentStartElapsedMs
                        ?.let { (clock.elapsedRealtime() - it).coerceAtLeast(0L) }
                        ?: 0L
                Prefs.teenAccumulatedMs + liveSegmentMs
            }
        val remainingMs =
            if (resting) {
                val elapsedMs = (clock.currentTimeMillis() - Prefs.teenRestStartMs).coerceAtLeast(0L)
                // 时钟回拨时 elapsed 变小甚至为负，钳制到完整休息时长（休息只会更长不会提前结束）
                (restLimitMs - elapsedMs).coerceIn(0L, restLimitMs)
            } else {
                0L
            }
        _state.value =
            TeenModeState(
                resting = resting,
                // 向上取整：剩余 30 毫秒也显示 1 秒，倒计时不出现 0
                remainingRestSeconds = (remainingMs + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND,
                watchedMs = watchedMs,
                watchLimitMinutes = Prefs.teenWatchLimitMin,
                restLimitMinutes = Prefs.teenRestLimitMin,
            )
    }

    /**
     * 设置变更后调用：清零累计与休息状态。
     *
     * 上限调整后旧累计可能已超过新上限，不清零会立刻误触发休息。
     */
    fun onSettingsChanged() {
        Prefs.teenAccumulatedMs = 0L
        Prefs.teenRestStartMs = 0L
        segmentStartElapsedMs = null
        refresh()
    }

    /**
     * 休息是否正在进行（内部判定，含到期自动复位）。
     *
     * 到期时清零累计与休息戳并清播放段起点，进入新一轮观看周期。
     */
    private fun isRestingInternal(): Boolean {
        if (!isLimitEnabled()) return false
        val restStartMs = Prefs.teenRestStartMs
        if (restStartMs <= 0L) return false
        val restLimitMs = Prefs.teenRestLimitMin.toLong() * MILLIS_PER_MINUTE
        val elapsedMs = clock.currentTimeMillis() - restStartMs
        if (elapsedMs >= restLimitMs) {
            // 休息到期：清零累计，恢复正常观看
            Prefs.teenRestStartMs = 0L
            Prefs.teenAccumulatedMs = 0L
            segmentStartElapsedMs = null
            logger.info { "rest ended, reset accumulation. elapsedMs=$elapsedMs" }
            return false
        }
        return true
    }

    /**
     * 结算从 [fromElapsedMs] 至当前的实际播放时长并累加到持久化累计值。
     *
     * 达到观看上限时进入休息：休息开始时间使用墙钟（杀进程 / 重启都无法绕过）。
     *
     * @param fromElapsedMs 播放段起始单调时钟（毫秒）。
     */
    private fun settleSegment(fromElapsedMs: Long) {
        val deltaMs = (clock.elapsedRealtime() - fromElapsedMs).coerceAtLeast(0L)
        if (deltaMs <= 0L) return
        val accumulatedMs = Prefs.teenAccumulatedMs + deltaMs
        Prefs.teenAccumulatedMs = accumulatedMs
        val limitMs = Prefs.teenWatchLimitMin.toLong() * MILLIS_PER_MINUTE
        if (accumulatedMs >= limitMs) {
            Prefs.teenRestStartMs = clock.currentTimeMillis()
            logger.info { "watch limit reached, enter rest. accumulatedMs=$accumulatedMs" }
        }
        refresh()
    }
}
