package dev.frost819.newbv.app.viewmodel.live

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.data.BlockedLiveStore
import dev.frost819.newbv.biliapi.http.entity.live.AreaLiveListResult
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
 * [LiveAreaListViewModel] 的单元测试。
 *
 * 验证分区列表在 map 阶段按屏蔽集（主播/父分区）过滤，
 * 以及 blockUp/blockArea 触发回到第一页重新加载使屏蔽立即生效。
 * 使用 MockK mock [LiveRepository]，每个测试使用独立临时 DataStore 初始化 Prefs。
 */
class LiveAreaListViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var tempDir: File
    private lateinit var liveRepository: LiveRepository

    @BeforeEach
    fun setUp() {
        tempDir =
            kotlin.io.path
                .createTempDirectory(prefix = "live_area_vm_test")
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
    fun `area list filters blocked ups and areas`() {
        runTest(testDispatcher) {
            // Given: 已屏蔽主播 uid=100 与父分区 210
            BlockedLiveStore.blockUp(100L)
            BlockedLiveStore.blockArea(210)
            stubAreaList(
                listOf(
                    liveRoomItem(roomId = 1, uid = 100L),
                    liveRoomItem(roomId = 2, uid = 300L, parentAreaId = 210),
                    liveRoomItem(roomId = 3, uid = 101L),
                ),
            )

            // When: 构造 ViewModel 触发第一页加载
            val viewModel = LiveAreaListViewModel(liveRepository, testSavedStateHandle())
            advanceUntilIdle()

            // Then: 命中主播或分区的房间被过滤
            val items = viewModel.uiState.value.items
            assertThat(items.map { it.roomId }).containsExactly(3L)
        }
    }

    @Test
    fun `blockUp refreshes area list from first page`() {
        runTest(testDispatcher) {
            // Given
            stubAreaList(
                listOf(
                    liveRoomItem(roomId = 1, uid = 100L),
                    liveRoomItem(roomId = 2, uid = 200L),
                ),
            )
            val viewModel = LiveAreaListViewModel(liveRepository, testSavedStateHandle())
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.items).hasSize(2)

            // When: 屏蔽 uid=100
            viewModel.blockUp(100L)
            advanceUntilIdle()

            // Then: 回到第一页重新加载，仅剩未屏蔽房间
            val items = viewModel.uiState.value.items
            assertThat(BlockedLiveStore.isUpBlocked(100L)).isTrue()
            assertThat(items.map { it.roomId }).containsExactly(2L)
        }
    }

    /** stub 分区列表接口返回指定房间列表（无更多分页）。 */
    private fun stubAreaList(items: List<LiveRoomItem>) {
        coEvery {
            liveRepository.getAreaLiveList(any(), any(), any(), any())
        } returns AreaLiveListResult(list = items, hasMore = false)
    }

    /** 构造测试用分区路由参数（LiveAreaRoute: parentAreaId/areaId/title）。 */
    private fun testSavedStateHandle(): SavedStateHandle =
        SavedStateHandle(
            mapOf(
                "parentAreaId" to 217,
                "areaId" to 0,
                "title" to "互动玩法",
            ),
        )

    /** 构造测试用分区列表房间。 */
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
}
