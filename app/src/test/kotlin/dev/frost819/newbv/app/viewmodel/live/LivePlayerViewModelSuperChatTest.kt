package dev.frost819.newbv.app.viewmodel.live

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.http.entity.live.LiveEvent
import dev.frost819.newbv.biliapi.http.entity.live.SuperChatDeleteEvent
import dev.frost819.newbv.biliapi.http.entity.live.SuperChatEvent
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [LivePlayerViewModel] 醒目留言（SC）队列逻辑的单元测试。
 *
 * 直调 internal 的 `handleLiveEvent` 派发 SC 事件（生产代码由 WebSocket 事件流调用），
 * 用 Turbine 观察状态流、有界虚拟时间（advanceTimeBy + runCurrent，
 * 不用 advanceUntilIdle，见 AGENTS.md 11.5.4）验证到期移除；
 * 不触发 loadLive 的网络与 WebSocket。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePlayerViewModelSuperChatTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: LivePlayerViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel =
            LivePlayerViewModel(
                liveRepository = mockk(relaxed = true),
                exoPlayerFactory = mockk(relaxed = true),
                videoCapabilityProvider = mockk(relaxed = true),
                danmakuFilterRepository = mockk(relaxed = true),
            )
    }

    @AfterEach
    fun tearDown() {
        runCatching { viewModel.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `super chat events put newest card at queue head`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                // Given：初始队列为空
                assertThat(awaitItem().superChats).isEmpty()

                // When：先后收到两条 SC
                fireLiveEvent(superChatEvent(id = 1L))
                runCurrent()
                assertThat(awaitItem().superChats.map { it.id }).containsExactly(1L)
                fireLiveEvent(superChatEvent(id = 2L))
                runCurrent()

                // Then：新 SC 从顶部进入（列表头部为最新）
                assertThat(awaitItem().superChats.map { it.id }).containsExactly(2L, 1L).inOrder()
            }
        }

    @Test
    fun `super chat card is removed once its duration elapses`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                // Given：收到展示 5 秒的 SC
                assertThat(awaitItem().superChats).isEmpty()
                fireLiveEvent(superChatEvent(id = 1L, durationMs = 5_000L))
                runCurrent()
                assertThat(awaitItem().superChats).hasSize(1)

                // When：虚拟时间推进到第 4.999 秒
                advanceTimeBy(4_999L)
                runCurrent()

                // Then：未到期仍在展示
                assertThat(viewModel.uiState.value.superChats).hasSize(1)

                // When：推进到第 5 秒整，到期协程触发
                advanceTimeBy(1L)
                runCurrent()

                // Then：卡片被逐条 delay 协程移除
                assertThat(awaitItem().superChats).isEmpty()
            }
        }

    @Test
    fun `delete event removes matching cards immediately`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                // Given：队列中有两条 SC（默认 60 秒后到期）
                assertThat(awaitItem().superChats).isEmpty()
                fireLiveEvent(superChatEvent(id = 1L))
                runCurrent()
                assertThat(awaitItem().superChats.map { it.id }).containsExactly(1L)
                fireLiveEvent(superChatEvent(id = 2L))
                runCurrent()
                assertThat(awaitItem().superChats.map { it.id }).containsExactly(2L, 1L).inOrder()

                // When：服务端删除 id=1（连同不存在的 id=3）
                fireLiveEvent(SuperChatDeleteEvent(ids = listOf(1L, 3L)))
                runCurrent()

                // Then：仅移除命中的卡片
                assertThat(awaitItem().superChats.map { it.id }).containsExactly(2L)
            }
        }

    @Test
    fun `re-sent super chat refreshes expiry instead of being removed early`() =
        runTest(testDispatcher) {
            viewModel.uiState.test {
                // Given：id=1 的 SC（时长 5 秒）已展示 3 秒
                assertThat(awaitItem().superChats).isEmpty()
                fireLiveEvent(superChatEvent(id = 1L, durationMs = 5_000L))
                runCurrent()
                assertThat(awaitItem().superChats).hasSize(1)
                advanceTimeBy(3_000L)
                runCurrent()

                // When：同 id 重发（重连重投），时长刷新为 10 秒
                fireLiveEvent(superChatEvent(id = 1L, durationMs = 10_000L))
                runCurrent()
                assertThat(awaitItem().superChats).hasSize(1)

                // Then：旧计时已取消，原第 5 秒的到期不再触发
                advanceTimeBy(2_000L)
                runCurrent()
                assertThat(viewModel.uiState.value.superChats).hasSize(1)

                // 重发后第 10 秒（虚拟时间第 13 秒）才到期移除
                advanceTimeBy(8_000L)
                runCurrent()
                assertThat(awaitItem().superChats).isEmpty()
            }
        }

    // ── Helpers ──────────────────────────────────────────────

    /** 直调 internal 的 [LivePlayerViewModel.handleLiveEvent] 派发直播事件。 */
    private fun fireLiveEvent(event: LiveEvent) {
        viewModel.handleLiveEvent(event)
    }

    private fun superChatEvent(
        id: Long,
        durationMs: Long = 60_000L,
    ): SuperChatEvent =
        SuperChatEvent(
            id = id,
            price = 30,
            message = "主播加油！",
            username = "测试用户",
            face = "",
            backgroundColor = 0xFF1E90FF,
            durationMs = durationMs,
        )
}
