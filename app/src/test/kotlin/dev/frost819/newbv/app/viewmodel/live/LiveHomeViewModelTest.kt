package dev.frost819.newbv.app.viewmodel.live

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.data.BlockedLiveStore
import dev.frost819.newbv.biliapi.http.entity.live.FollowLiveResponse
import dev.frost819.newbv.biliapi.http.entity.live.FollowLiveRoom
import dev.frost819.newbv.biliapi.http.entity.live.LiveRecommendResponse
import dev.frost819.newbv.biliapi.http.entity.live.LiveRoomItem
import dev.frost819.newbv.biliapi.repositories.LiveRepository
import dev.frost819.newbv.data.datastore.Prefs
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [LiveHomeViewModel] 的单元测试。
 *
 * 验证关注/推荐列表在 map 阶段按屏蔽集（主播/父分区）过滤，
 * 以及 blockUp/blockArea 触发列表刷新使屏蔽立即生效。
 * 使用 MockK mock [LiveRepository]，每个测试使用独立临时 DataStore 初始化 Prefs。
 */
class LiveHomeViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var liveRepository: LiveRepository

    @BeforeEach
    fun setUp() {
        // 临时文件交给 deleteOnExit，tearDown 不删除：blockUp/blockArea 的持久化是异步的
        // （launchPersist 在 IO 协程），若写入完成前删除文件，edit 会抛未捕获 IOException，
        // 被 coroutines-test 记账为下一个测试的 UncaughtExceptionsBeforeTest
        val file = File.createTempFile("test_live_home_vm", ".preferences_pb")
        file.deleteOnExit()
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { file },
            )
        Dispatchers.setMain(testDispatcher)
        Prefs.resetForTesting()
        Prefs.init(dataStore)
        liveRepository = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        Prefs.resetForTesting()
    }

    // ===== 加载过滤 =====

    @Test
    fun `loadRecommend filters blocked ups`() {
        runTest(testDispatcher) {
            // Given: 已屏蔽主播 uid=200
            BlockedLiveStore.blockUp(200L)
            stubRecommend(
                listOf(
                    liveRoomItem(roomId = 1, uid = 100L),
                    liveRoomItem(roomId = 2, uid = 200L),
                ),
            )

            // When: 构造 ViewModel 触发初始加载
            val viewModel = LiveHomeViewModel(liveRepository)
            advanceUntilIdle()

            // Then: uid=200 的房间被过滤
            val recommendItems = viewModel.uiState.value.recommendItems
            assertThat(recommendItems.map { it.roomId }).containsExactly(1L)
        }
    }

    @Test
    fun `loadFollowLive filters blocked parent areas`() {
        runTest(testDispatcher) {
            // Given: 已屏蔽父分区 210
            BlockedLiveStore.blockArea(210)
            stubFollow(
                listOf(
                    followRoom(roomId = 11, uid = 100L, parentAreaId = 210),
                    followRoom(roomId = 12, uid = 101L, parentAreaId = 213),
                ),
            )

            // When
            val viewModel = LiveHomeViewModel(liveRepository)
            advanceUntilIdle()

            // Then: 分区 210 的房间被过滤
            val followItems = viewModel.uiState.value.followItems
            assertThat(followItems.map { it.roomId }).containsExactly(12L)
        }
    }

    @Test
    fun `loadMoreRecommend filters blocked ups in new batch`() {
        runTest(testDispatcher) {
            // Given: 首屏返回房间 1(uid=100)/2(uid=200)，随后屏蔽 uid=100
            stubRecommend(
                listOf(
                    liveRoomItem(roomId = 1, uid = 100L),
                    liveRoomItem(roomId = 2, uid = 200L),
                ),
            )
            val viewModel = LiveHomeViewModel(liveRepository)
            advanceUntilIdle()
            viewModel.blockUp(100L)
            advanceUntilIdle()

            // When: 加载更多返回新批次，其中房间 3 属于已屏蔽主播
            coEvery { liveRepository.getLiveRecommend() } returns
                LiveRecommendResponse(
                    list =
                        listOf(
                            liveRoomItem(roomId = 3, uid = 100L),
                            liveRoomItem(roomId = 4, uid = 300L),
                        ),
                )
            viewModel.loadMoreRecommend()
            advanceUntilIdle()

            // Then: 仅追加未屏蔽的房间 4，顺序保持
            val recommendItems = viewModel.uiState.value.recommendItems
            assertThat(recommendItems.map { it.roomId }).containsExactly(2L, 4L).inOrder()
        }
    }

    // ===== 屏蔽操作 =====

    @Test
    fun `blockUp refreshes recommend list immediately`() {
        runTest(testDispatcher) {
            // Given
            stubRecommend(
                listOf(
                    liveRoomItem(roomId = 1, uid = 100L),
                    liveRoomItem(roomId = 2, uid = 200L),
                ),
            )
            val viewModel = LiveHomeViewModel(liveRepository)
            advanceUntilIdle()

            // When: 屏蔽 uid=100
            viewModel.blockUp(100L)
            advanceUntilIdle()

            // Then: 屏蔽已记录，推荐列表仅剩未屏蔽房间
            val recommendItems = viewModel.uiState.value.recommendItems
            assertThat(BlockedLiveStore.isUpBlocked(100L)).isTrue()
            assertThat(recommendItems.map { it.roomId }).containsExactly(2L)
        }
    }

    @Test
    fun `blockArea refreshes follow list immediately`() {
        runTest(testDispatcher) {
            // Given
            stubFollow(
                listOf(
                    followRoom(roomId = 11, uid = 100L, parentAreaId = 210),
                    followRoom(roomId = 12, uid = 101L, parentAreaId = 213),
                ),
            )
            val viewModel = LiveHomeViewModel(liveRepository)
            advanceUntilIdle()

            // When: 屏蔽父分区 210
            viewModel.blockArea(210)
            advanceUntilIdle()

            // Then: 屏蔽已记录，关注列表仅剩未屏蔽分区的房间
            val followItems = viewModel.uiState.value.followItems
            assertThat(BlockedLiveStore.isAreaBlocked(210)).isTrue()
            assertThat(followItems.map { it.roomId }).containsExactly(12L)
        }
    }

    @Test
    fun `blockUp with non positive mid is ignored`() {
        runTest(testDispatcher) {
            // Given
            stubRecommend(listOf(liveRoomItem(roomId = 1, uid = 100L)))
            val viewModel = LiveHomeViewModel(liveRepository)
            advanceUntilIdle()

            // When
            viewModel.blockUp(0L)
            advanceUntilIdle()

            // Then: 屏蔽集不变，列表不受影响
            val recommendItems = viewModel.uiState.value.recommendItems
            assertThat(BlockedLiveStore.blockedUps()).isEmpty()
            assertThat(recommendItems.map { it.roomId }).containsExactly(1L)
        }
    }

    // ===== 测试辅助 =====

    /** stub 推荐接口返回指定房间列表。 */
    private fun stubRecommend(items: List<LiveRoomItem>) {
        coEvery { liveRepository.getLiveRecommend() } returns LiveRecommendResponse(list = items)
    }

    /** stub 关注接口返回指定房间列表。 */
    private fun stubFollow(rooms: List<FollowLiveRoom>) {
        coEvery { liveRepository.getFollowLive() } returns FollowLiveResponse(rooms = rooms)
    }

    /** 构造测试用推荐/分区列表房间（在推荐与分区列表接口中通用）。 */
    private fun liveRoomItem(
        roomId: Long,
        uid: Long,
        parentAreaId: Int = 217,
    ): LiveRoomItem =
        LiveRoomItem(
            roomId = roomId.toInt(),
            uid = uid,
            title = "房间$roomId",
            uname = "主播$uid",
            online = 100,
            areaV2Name = "子分区",
            areaV2ParentName = "父分区",
            areaV2ParentId = parentAreaId,
            liveStatus = 1,
        )

    /** 构造测试用关注列表房间。 */
    private fun followRoom(
        roomId: Long,
        uid: Long,
        parentAreaId: Int = 213,
    ): FollowLiveRoom =
        FollowLiveRoom(
            roomId = roomId,
            uid = uid,
            title = "房间$roomId",
            uname = "主播$uid",
            online = 100,
            liveStatus = 1,
            areaV2ParentId = parentAreaId,
        )
}
