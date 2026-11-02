package dev.frost819.newbv.app.viewmodel.live

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.data.BlockedLiveStore
import dev.frost819.newbv.biliapi.http.entity.live.FollowLiveRoom
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
 * [LiveFollowViewModel] 的单元测试。
 *
 * 验证关注列表在 map 阶段按屏蔽集（主播/父分区）过滤，
 * 以及 blockUp/blockArea 触发列表刷新使屏蔽立即生效。
 * 使用 MockK mock [LiveRepository]，每个测试使用独立临时 DataStore 初始化 Prefs。
 */
class LiveFollowViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var tempDir: File
    private lateinit var liveRepository: LiveRepository

    @BeforeEach
    fun setUp() {
        tempDir =
            kotlin.io.path
                .createTempDirectory(prefix = "live_follow_vm_test")
                .toFile()
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(tempDir, "Test.preferences_pb") },
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
        tempDir.deleteRecursively()
    }

    @Test
    fun `loadFollowLive filters blocked ups and areas`() {
        runTest(testDispatcher) {
            // Given: 已屏蔽主播 uid=100 与父分区 210
            BlockedLiveStore.blockUp(100L)
            BlockedLiveStore.blockArea(210)
            coEvery { liveRepository.getFollowLive() } returns
                FollowLiveResponse(
                    rooms =
                        listOf(
                            followRoom(roomId = 11, uid = 100L, parentAreaId = 213),
                            followRoom(roomId = 12, uid = 101L, parentAreaId = 210),
                            followRoom(roomId = 13, uid = 102L, parentAreaId = 213),
                        ),
                )

            // When: 构造 ViewModel 触发初始加载
            val viewModel = LiveFollowViewModel(liveRepository)
            advanceUntilIdle()

            // Then: 命中主播或分区的房间被过滤
            val items = viewModel.uiState.value.items
            assertThat(items.map { it.roomId }).containsExactly(13L)
        }
    }

    @Test
    fun `blockUp refreshes follow list immediately`() {
        runTest(testDispatcher) {
            // Given
            coEvery { liveRepository.getFollowLive() } returns
                FollowLiveResponse(
                    rooms =
                        listOf(
                            followRoom(roomId = 11, uid = 100L),
                            followRoom(roomId = 12, uid = 200L),
                        ),
                )
            val viewModel = LiveFollowViewModel(liveRepository)
            advanceUntilIdle()

            // When: 屏蔽 uid=100
            viewModel.blockUp(100L)
            advanceUntilIdle()

            // Then: 屏蔽已记录，关注列表仅剩未屏蔽房间
            val items = viewModel.uiState.value.items
            assertThat(BlockedLiveStore.isUpBlocked(100L)).isTrue()
            assertThat(items.map { it.roomId }).containsExactly(12L)
        }
    }

    @Test
    fun `blockArea with non positive id is ignored`() {
        runTest(testDispatcher) {
            // Given
            coEvery { liveRepository.getFollowLive() } returns
                FollowLiveResponse(rooms = listOf(followRoom(roomId = 11, uid = 100L)))
            val viewModel = LiveFollowViewModel(liveRepository)
            advanceUntilIdle()

            // When
            viewModel.blockArea(0)
            advanceUntilIdle()

            // Then: 屏蔽集不变，列表不受影响
            val items = viewModel.uiState.value.items
            assertThat(BlockedLiveStore.blockedAreas()).isEmpty()
            assertThat(items.map { it.roomId }).containsExactly(11L)
        }
    }

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
