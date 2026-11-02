package dev.frost819.newbv.app.util

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * [TeenModeTimer] 状态机的单元测试。
 *
 * 验证播放段累计、达上限进入休息、休息期满恢复、时钟回拨防绕过等行为。
 * 注入 [FakeTeenModeClock] 假时钟并使用临时文件 DataStore，不依赖 Android 环境。
 *
 * DataStore 生命周期沿用 `AccountRepositoryImplTest` 的安全模式：类级创建一次、
 * 结束时不删除不重置 —— Prefs 的异步写入队列若命中已重置/已删除的存储会抛
 * 未捕获异常，被 coroutines-test 归因到无关的 runTest 造成偶发失败。
 * 用例间隔离靠 [setUp] 内的重写与 [TeenModeTimer.onSettingsChanged] 清零实现。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TeenModeTimerTest {
    private lateinit var clock: FakeTeenModeClock

    /** 可手动推进的假时钟：单调时钟与墙钟独立控制。 */
    private class FakeTeenModeClock(
        var elapsedMs: Long = 0L,
        var wallMs: Long = 1_000_000_000L,
    ) : TeenModeClock {
        override fun elapsedRealtime(): Long = elapsedMs

        override fun currentTimeMillis(): Long = wallMs

        /** 推进单调时钟。 */
        fun advanceElapsed(ms: Long) {
            elapsedMs += ms
        }

        /** 推进墙钟。 */
        fun advanceWall(ms: Long) {
            wallMs += ms
        }
    }

    @BeforeAll
    fun initPrefs() {
        Prefs.resetForTesting()
        val file = File.createTempFile("test_teen_mode_timer", ".preferences_pb")
        file.deleteOnExit()
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { file },
            )
        Prefs.init(dataStore)
    }

    @BeforeEach
    fun setUp() {
        clock = FakeTeenModeClock()
        TeenModeTimer.clock = clock
        // Given: 保护开启，60 分钟观看上限、20 分钟休息
        Prefs.teenModeEnabled = true
        Prefs.teenWatchLimitMin = 60
        Prefs.teenRestLimitMin = 20
        TeenModeTimer.onSettingsChanged()
    }

    @Test
    fun `disabled mode does not accumulate watch time`() {
        // Given: 保护开关关闭
        Prefs.teenModeEnabled = false
        TeenModeTimer.onSettingsChanged()

        // When: 播放一段时间并结算
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(30 * 60_000L)
        TeenModeTimer.tick()
        TeenModeTimer.onPlayStop()
        TeenModeTimer.refresh()

        // Then: 不累计也不休息
        assertThat(TeenModeTimer.state.value.resting).isFalse()
        assertThat(TeenModeTimer.state.value.watchedMs).isEqualTo(0L)
    }

    @Test
    fun `zero watch limit disables limit even when enabled`() {
        // Given: 保护开启但上限为 0（不限制）
        Prefs.teenWatchLimitMin = 0
        TeenModeTimer.onSettingsChanged()

        // When: 播放并结算
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(120 * 60_000L)
        TeenModeTimer.tick()

        // Then: 功能不生效
        assertThat(TeenModeTimer.isLimitEnabled()).isFalse()
        assertThat(TeenModeTimer.state.value.resting).isFalse()
    }

    @Test
    fun `segments accumulate across play stop and restart`() {
        // When: 播放 15 分钟周期结算后停止，再播放 5 分钟停止
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(15 * 60_000L)
        TeenModeTimer.tick()
        clock.advanceElapsed(5 * 60_000L)
        TeenModeTimer.onPlayStop()
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(5 * 60_000L)
        TeenModeTimer.onPlayStop()
        TeenModeTimer.refresh()

        // Then: 跨段累计 25 分钟且未休息
        assertThat(TeenModeTimer.state.value.watchedMs).isEqualTo(25 * 60_000L)
        assertThat(TeenModeTimer.state.value.resting).isFalse()
    }

    @Test
    fun `watched time includes running segment before settlement`() {
        // When: 播放 30 秒后未结算直接刷新
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(30_000L)
        TeenModeTimer.refresh()

        // Then: 展示值包含进行中的播放段
        assertThat(TeenModeTimer.state.value.watchedMs).isEqualTo(30_000L)
    }

    @Test
    fun `reaching watch limit enters rest with wall clock stamp`() {
        // When: 累计达到 60 分钟上限
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()

        // Then: 进入休息，休息开始时间等于当前墙钟
        assertThat(TeenModeTimer.state.value.resting).isTrue()
        assertThat(Prefs.teenRestStartMs).isEqualTo(1_000_000_000L)
        assertThat(TeenModeTimer.state.value.remainingRestSeconds).isEqualTo(20 * 60L)
    }

    @Test
    fun `tick during rest discards segment and keeps accumulation frozen`() {
        // Given: 已进入休息
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()
        val frozenMs = TeenModeTimer.state.value.watchedMs

        // When: 休息期间播放器仍在上报周期结算
        clock.advanceElapsed(5 * 60_000L)
        TeenModeTimer.tick()

        // Then: 休息期不累计
        assertThat(TeenModeTimer.state.value.resting).isTrue()
        assertThat(TeenModeTimer.state.value.watchedMs).isEqualTo(frozenMs)
    }

    @Test
    fun `rest expiry resumes and clears accumulation`() {
        // Given: 已进入休息
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()
        assertThat(TeenModeTimer.state.value.resting).isTrue()

        // When: 墙钟走完 20 分钟休息时长后刷新
        clock.advanceWall(20 * 60_000L)
        TeenModeTimer.refresh()

        // Then: 休息结束、累计清零，可重新计时
        assertThat(TeenModeTimer.state.value.resting).isFalse()
        assertThat(TeenModeTimer.state.value.remainingRestSeconds).isEqualTo(0L)
        assertThat(Prefs.teenAccumulatedMs).isEqualTo(0L)
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60_000L)
        TeenModeTimer.onPlayStop()
        assertThat(TeenModeTimer.state.value.watchedMs).isEqualTo(60_000L)
    }

    @Test
    fun `clock rollback does not bypass rest`() {
        // Given: 已进入休息，剩余 20 分钟
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()

        // When: 用户把系统时间回拨 1 小时后刷新
        clock.advanceWall(-3_600_000L)
        TeenModeTimer.refresh()

        // Then: 休息未被绕过，剩余时间钳制为完整休息时长
        assertThat(TeenModeTimer.state.value.resting).isTrue()
        assertThat(TeenModeTimer.state.value.remainingRestSeconds).isEqualTo(20 * 60L)
    }

    @Test
    fun `playback during rest does not accumulate or reset rest start`() {
        // Given: 已进入休息
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()

        // When: 休息期间播放器继续上报开始/停止
        clock.advanceElapsed(10 * 60_000L)
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60_000L)
        TeenModeTimer.onPlayStop()

        // Then: 累计与休息开始时间均不变
        assertThat(Prefs.teenAccumulatedMs).isEqualTo(60 * 60_000L)
        assertThat(Prefs.teenRestStartMs).isEqualTo(1_000_000_000L)
        assertThat(TeenModeTimer.state.value.resting).isTrue()
    }

    @Test
    fun `final stop after limit crossing does not reset rest start`() {
        // Given: 周期结算触发进入休息
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()
        val restStartMs = Prefs.teenRestStartMs

        // When: 播放器随后上报停止
        clock.advanceElapsed(1_000L)
        TeenModeTimer.onPlayStop()

        // Then: 休息开始时间不被重置（避免休息被意外延长）
        assertThat(Prefs.teenRestStartMs).isEqualTo(restStartMs)
    }

    @Test
    fun `tick without active segment does nothing`() {
        // When: 未调用 onPlayStart 直接周期结算
        clock.advanceElapsed(15_000L)
        TeenModeTimer.tick()

        // Then: 无累计（newBV 的播放上报可靠，tick 不自启动播放段）
        assertThat(Prefs.teenAccumulatedMs).isEqualTo(0L)
        assertThat(TeenModeTimer.state.value.resting).isFalse()
    }

    @Test
    fun `time after onPlayStop is not counted by later tick`() {
        // Given: 播放 1 分钟后停止（对齐退出播放器时的 onDispose 兜底结算）
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60_000L)
        TeenModeTimer.onPlayStop()

        // When: 停止后流逝 20 分钟（用户在其它页面浏览），随后周期结算被调用
        clock.advanceElapsed(20 * 60_000L)
        TeenModeTimer.tick()
        TeenModeTimer.refresh()

        // Then: 非播放时间不计入累计
        assertThat(TeenModeTimer.state.value.watchedMs).isEqualTo(60_000L)
    }

    @Test
    fun `settings change resets accumulation and rest`() {
        // Given: 已累计并进入休息
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()
        assertThat(TeenModeTimer.state.value.resting).isTrue()

        // When: 调整上限后通知计时器
        Prefs.teenWatchLimitMin = 90
        TeenModeTimer.onSettingsChanged()

        // Then: 状态清零，重新开始
        assertThat(TeenModeTimer.state.value.resting).isFalse()
        assertThat(TeenModeTimer.state.value.watchedMs).isEqualTo(0L)
        assertThat(Prefs.teenRestStartMs).isEqualTo(0L)
    }

    @Test
    fun `remaining seconds round up to next second`() {
        // Given: 已进入休息
        TeenModeTimer.onPlayStart()
        clock.advanceElapsed(60 * 60_000L)
        TeenModeTimer.tick()

        // When: 休息还剩 1.5 秒时刷新
        clock.advanceWall(20 * 60_000L - 1_500L)
        TeenModeTimer.refresh()

        // Then: 剩余秒数向上取整为 2
        assertThat(TeenModeTimer.state.value.remainingRestSeconds).isEqualTo(2L)
    }
}
