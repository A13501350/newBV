package dev.frost819.newbv.app.viewmodel.user

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.user.FollowedUser
import dev.frost819.newbv.biliapi.repositories.UserRepository
import dev.frost819.newbv.data.repository.AccountRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * [FollowViewModel] 的单元测试。
 *
 * 验证关注/粉丝双列表的加载、切换、刷新、幂等性、错误与超时处理，
 * 以及粉丝 Tab 仅本人可见（非本人回退关注列表）的收口逻辑。
 * 使用 MockK mock [UserRepository] 与 [AccountRepository]。
 */
class FollowViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var userRepository: UserRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var viewModel: FollowViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        userRepository = mockk()
        accountRepository = mockk()
        every { accountRepository.currentUid() } returns CURRENT_UID
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): FollowViewModel =
        FollowViewModel(
            userRepository = userRepository,
            accountRepository = accountRepository,
        )

    private fun fakeFollowedUser(mid: Long) =
        FollowedUser(
            mid = mid,
            name = "用户$mid",
            avatar = "https://example.com/avatar$mid.jpg",
            sign = "签名$mid",
        )

    private fun fakeUsers(count: Int): List<FollowedUser> = (1..count).map { fakeFollowedUser(it.toLong()) }

    // ------------------------------------------------------------------
    // init
    // ------------------------------------------------------------------

    @Test
    fun `init loads followed users successfully`() =
        runTest(testDispatcher) {
            val users = fakeUsers(3)
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns users

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.mid).isEqualTo(1L)
            assertThat(state.listType).isEqualTo(FollowListType.Following)
            assertThat(state.isSelf).isTrue()
            assertThat(state.users).hasSize(3)
            assertThat(state.users[0].name).isEqualTo("用户1")
            assertThat(state.loading).isFalse()
            assertThat(state.error).isFalse()
        }

    @Test
    fun `init loads empty list successfully`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns emptyList()

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.users).isEmpty()
            assertThat(state.loading).isFalse()
            assertThat(state.error).isFalse()
        }

    @Test
    fun `init is idempotent when same mid and users already loaded`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            viewModel.init(1L)
            advanceUntilIdle()

            coVerify(exactly = 1) { userRepository.getFollowedUsers(1L, any()) }
        }

    @Test
    fun `init reloads when mid changes`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)
            coEvery { userRepository.getFollowedUsers(2L, any()) } returns fakeUsers(3)

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            viewModel.init(2L)
            advanceUntilIdle()

            coVerify(exactly = 1) { userRepository.getFollowedUsers(1L, any()) }
            coVerify(exactly = 1) { userRepository.getFollowedUsers(2L, any()) }
            assertThat(viewModel.uiState.value.users).hasSize(3)
        }

    @Test
    fun `init is not idempotent when users list is empty`() =
        runTest(testDispatcher) {
            // When users is empty, init should reload even for the same mid
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns emptyList()

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            viewModel.init(1L)
            advanceUntilIdle()

            coVerify(exactly = 2) { userRepository.getFollowedUsers(1L, any()) }
        }

    // ------------------------------------------------------------------
    // init: 粉丝列表与收口
    // ------------------------------------------------------------------

    @Test
    fun `init with followers listType loads followers for self`() =
        runTest(testDispatcher) {
            // Given: 本人主页（mid 等于登录 uid）
            coEvery { userRepository.getFollowers(1L, any()) } returns fakeUsers(2)

            viewModel = createViewModel()

            // When
            viewModel.init(1L, FollowListType.Followers)
            advanceUntilIdle()

            // Then: 走粉丝仓库且不触发关注仓库
            val state = viewModel.uiState.value
            assertThat(state.listType).isEqualTo(FollowListType.Followers)
            assertThat(state.isSelf).isTrue()
            assertThat(state.users).hasSize(2)
            coVerify(exactly = 1) { userRepository.getFollowers(1L, any()) }
            coVerify(exactly = 0) { userRepository.getFollowedUsers(any(), any()) }
        }

    @Test
    fun `init clamps followers tab to following when viewing other user`() =
        runTest(testDispatcher) {
            // Given: 查看他人主页且路由伪造粉丝 tab
            every { accountRepository.currentUid() } returns OTHER_UID
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)

            viewModel = createViewModel()

            // When
            viewModel.init(1L, FollowListType.Followers)
            advanceUntilIdle()

            // Then: 回退关注列表，粉丝接口从未被调用（避免 22110007）
            val state = viewModel.uiState.value
            assertThat(state.isSelf).isFalse()
            assertThat(state.listType).isEqualTo(FollowListType.Following)
            assertThat(state.users).hasSize(2)
            coVerify(exactly = 0) { userRepository.getFollowers(any(), any()) }
            coVerify(exactly = 1) { userRepository.getFollowedUsers(1L, any()) }
        }

    @Test
    fun `init marks isSelf false when viewing other user`() =
        runTest(testDispatcher) {
            every { accountRepository.currentUid() } returns OTHER_UID
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.isSelf).isFalse()
        }

    @Test
    fun `fromTabParam resolves known values and falls back to following`() {
        // Given / When / Then
        assertThat(FollowListType.fromTabParam("following")).isEqualTo(FollowListType.Following)
        assertThat(FollowListType.fromTabParam("followers")).isEqualTo(FollowListType.Followers)
        assertThat(FollowListType.fromTabParam("unknown")).isEqualTo(FollowListType.Following)
    }

    // ------------------------------------------------------------------
    // switchListType
    // ------------------------------------------------------------------

    @Test
    fun `switchListType clears users and loads followers`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)
            coEvery { userRepository.getFollowers(1L, any()) } returns fakeUsers(4)

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            // When: 切换立即清空列表进入 loading（同步状态，先于协程执行）
            viewModel.switchListType(FollowListType.Followers)

            // Then
            val switching = viewModel.uiState.value
            assertThat(switching.listType).isEqualTo(FollowListType.Followers)
            assertThat(switching.users).isEmpty()
            assertThat(switching.loading).isTrue()
            assertThat(switching.error).isFalse()

            advanceUntilIdle()

            val loaded = viewModel.uiState.value
            assertThat(loaded.users).hasSize(4)
            assertThat(loaded.loading).isFalse()
            coVerify(exactly = 1) { userRepository.getFollowers(1L, any()) }
        }

    @Test
    fun `switchListType back to following reloads followed users`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returnsMany
                listOf(fakeUsers(2), fakeUsers(5))
            coEvery { userRepository.getFollowers(1L, any()) } returns fakeUsers(4)

            viewModel = createViewModel()
            viewModel.init(1L, FollowListType.Followers)
            advanceUntilIdle()

            viewModel.switchListType(FollowListType.Following)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.users).hasSize(5)
            assertThat(viewModel.uiState.value.listType).isEqualTo(FollowListType.Following)
            coVerify(exactly = 2) { userRepository.getFollowedUsers(1L, any()) }
        }

    @Test
    fun `switchListType to same type is idempotent`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            viewModel.switchListType(FollowListType.Following)
            advanceUntilIdle()

            coVerify(exactly = 1) { userRepository.getFollowedUsers(1L, any()) }
            assertThat(viewModel.uiState.value.users).hasSize(2)
        }

    @Test
    fun `switchListType sets error on followers load failure`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)
            coEvery { userRepository.getFollowers(1L, any()) } throws IOException("network error")

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            viewModel.switchListType(FollowListType.Followers)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.error).isTrue()
            assertThat(state.loading).isFalse()
            assertThat(state.users).isEmpty()
            assertThat(state.listType).isEqualTo(FollowListType.Followers)
        }

    @Test
    fun `switchListType emits cleared loading state before followers arrive`() =
        runTest(testDispatcher) {
            // Given: 关注列表已加载，粉丝列表延迟 1s 返回
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2)
            coEvery { userRepository.getFollowers(1L, any()) } coAnswers {
                delay(1_000)
                fakeUsers(4)
            }
            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            viewModel.uiState.test {
                // Given: 初始关注列表已就绪
                assertThat(awaitItem().users).hasSize(2)

                // When: 切换到粉丝列表
                viewModel.switchListType(FollowListType.Followers)

                // Then: 先同步发射清空 + loading 的中间态
                val cleared = awaitItem()
                assertThat(cleared.users).isEmpty()
                assertThat(cleared.loading).isTrue()

                advanceUntilIdle()
                val loaded = awaitItem()
                assertThat(loaded.users).hasSize(4)
                assertThat(loaded.loading).isFalse()

                cancelAndIgnoreRemainingEvents()
            }
        }

    // ------------------------------------------------------------------
    // refresh
    // ------------------------------------------------------------------

    @Test
    fun `refresh clears and reloads current list type`() =
        runTest(testDispatcher) {
            val firstLoad = fakeUsers(2)
            val secondLoad = fakeUsers(4)
            coEvery { userRepository.getFollowedUsers(1L, any()) } returnsMany listOf(firstLoad, secondLoad)

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.users).hasSize(2)

            viewModel.refresh()
            advanceUntilIdle()

            coVerify(exactly = 2) { userRepository.getFollowedUsers(1L, any()) }
            assertThat(viewModel.uiState.value.users).hasSize(4)
            assertThat(viewModel.uiState.value.loading).isFalse()
            assertThat(viewModel.uiState.value.error).isFalse()
        }

    @Test
    fun `refresh reloads followers when followers tab active`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowers(1L, any()) } returnsMany
                listOf(fakeUsers(2), fakeUsers(3))

            viewModel = createViewModel()
            viewModel.init(1L, FollowListType.Followers)
            advanceUntilIdle()

            viewModel.refresh()
            advanceUntilIdle()

            coVerify(exactly = 2) { userRepository.getFollowers(1L, any()) }
            coVerify(exactly = 0) { userRepository.getFollowedUsers(any(), any()) }
            assertThat(viewModel.uiState.value.users).hasSize(3)
        }

    @Test
    fun `refresh sets error on network failure`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } returns fakeUsers(2) andThenThrows
                IOException("network error")

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isFalse()

            viewModel.refresh()
            advanceUntilIdle()

            assertThat(viewModel.uiState.value.error).isTrue()
            assertThat(viewModel.uiState.value.loading).isFalse()
        }

    // ------------------------------------------------------------------
    // error handling
    // ------------------------------------------------------------------

    @Test
    fun `init sets error on network failure`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(1L, any()) } throws IOException("network error")

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.error).isTrue()
            assertThat(state.loading).isFalse()
            assertThat(state.users).isEmpty()
        }

    // ------------------------------------------------------------------
    // timeout handling
    // ------------------------------------------------------------------

    @Test
    fun `init sets error on timeout`() =
        runTest(testDispatcher) {
            coEvery { userRepository.getFollowedUsers(any(), any()) } coAnswers {
                delay(31_000)
                fakeUsers(2)
            }

            viewModel = createViewModel()
            viewModel.init(1L)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertThat(state.error).isTrue()
            assertThat(state.loading).isFalse()
        }

    private companion object {
        private const val CURRENT_UID = 1L
        private const val OTHER_UID = 999L
    }
}
