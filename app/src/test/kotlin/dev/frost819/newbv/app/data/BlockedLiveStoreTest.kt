package dev.frost819.newbv.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [BlockedLiveStore] 的单元测试。
 *
 * 验证屏蔽集合的增删查、去重、非法 ID 防御与清空，以及通过 Prefs/DataStore 的持久化。
 * 每个测试使用独立的临时文件 DataStore，互不污染。
 */
class BlockedLiveStoreTest {
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        tempDir =
            kotlin.io.path
                .createTempDirectory(prefix = "blocked_live_store_test")
                .toFile()
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
                produceFile = { File(tempDir, "Test.preferences_pb") },
            )
        Prefs.resetForTesting()
        Prefs.init(dataStore)
    }

    @AfterEach
    fun tearDown() {
        Prefs.resetForTesting()
        tempDir.deleteRecursively()
    }

    // ===== 默认值 =====

    @Test
    fun `blocked sets are empty by default`() {
        // Given / When: 初始化后未做任何屏蔽操作
        // Then
        assertThat(BlockedLiveStore.blockedUps()).isEmpty()
        assertThat(BlockedLiveStore.blockedAreas()).isEmpty()
        assertThat(BlockedLiveStore.isUpBlocked(1L)).isFalse()
        assertThat(BlockedLiveStore.isAreaBlocked(100)).isFalse()
    }

    // ===== 屏蔽主播 =====

    @Test
    fun `blockUp adds mid and syncs prefs key`() {
        // When
        val ups = BlockedLiveStore.blockUp(42L)

        // Then: 返回值、查询接口与 Prefs 键（内存缓存）同步更新
        assertThat(ups).containsExactly(42L)
        assertThat(BlockedLiveStore.isUpBlocked(42L)).isTrue()
        assertThat(Prefs.blockedLiveUps).isEqualTo("42")
    }

    @Test
    fun `blockUp deduplicates repeated ids`() {
        // When
        BlockedLiveStore.blockUp(7L)
        val ups = BlockedLiveStore.blockUp(7L)

        // Then: 集合语义，重复添加只保留一个
        assertThat(ups).containsExactly(7L)
        assertThat(Prefs.blockedLiveUps).isEqualTo("7")
    }

    @Test
    fun `blockUp keeps existing entries`() {
        // Given
        BlockedLiveStore.blockUp(1L)
        BlockedLiveStore.blockUp(2L)

        // When
        val ups = BlockedLiveStore.blockUp(3L)

        // Then
        assertThat(ups).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun `blockUp ignores non positive ids`() {
        // When
        val zeroResult = BlockedLiveStore.blockUp(0L)
        val negativeResult = BlockedLiveStore.blockUp(-5L)

        // Then: 非法 ID 不写入，屏蔽集保持为空
        assertThat(zeroResult).isEmpty()
        assertThat(negativeResult).isEmpty()
        assertThat(Prefs.blockedLiveUps).isEmpty()
    }

    @Test
    fun `unblockUp removes given mid`() {
        // Given
        BlockedLiveStore.blockUp(1L)
        BlockedLiveStore.blockUp(2L)

        // When
        val ups = BlockedLiveStore.unblockUp(1L)

        // Then
        assertThat(ups).containsExactly(2L)
        assertThat(BlockedLiveStore.isUpBlocked(1L)).isFalse()
    }

    // ===== 屏蔽分区 =====

    @Test
    fun `blockArea adds parent area id and syncs prefs key`() {
        // When
        val areas = BlockedLiveStore.blockArea(210)

        // Then
        assertThat(areas).containsExactly(210)
        assertThat(BlockedLiveStore.isAreaBlocked(210)).isTrue()
        assertThat(Prefs.blockedLiveAreas).isEqualTo("210")
    }

    @Test
    fun `blockArea deduplicates repeated ids`() {
        // When
        BlockedLiveStore.blockArea(210)
        val areas = BlockedLiveStore.blockArea(210)

        // Then
        assertThat(areas).containsExactly(210)
        assertThat(Prefs.blockedLiveAreas).isEqualTo("210")
    }

    @Test
    fun `blockArea ignores non positive ids`() {
        // When
        val zeroResult = BlockedLiveStore.blockArea(0)
        val negativeResult = BlockedLiveStore.blockArea(-1)

        // Then
        assertThat(zeroResult).isEmpty()
        assertThat(negativeResult).isEmpty()
        assertThat(Prefs.blockedLiveAreas).isEmpty()
    }

    @Test
    fun `unblockArea removes given parent area id`() {
        // Given
        BlockedLiveStore.blockArea(210)
        BlockedLiveStore.blockArea(213)

        // When
        val areas = BlockedLiveStore.unblockArea(210)

        // Then
        assertThat(areas).containsExactly(213)
        assertThat(BlockedLiveStore.isAreaBlocked(210)).isFalse()
    }

    // ===== 清空 =====

    @Test
    fun `clearUps only clears ups`() {
        // Given
        BlockedLiveStore.blockUp(1L)
        BlockedLiveStore.blockArea(200)

        // When
        val ups = BlockedLiveStore.clearUps()

        // Then: 仅清空主播，分区保持
        assertThat(ups).isEmpty()
        assertThat(BlockedLiveStore.blockedAreas()).containsExactly(200)
    }

    @Test
    fun `clearAreas only clears areas`() {
        // Given
        BlockedLiveStore.blockUp(1L)
        BlockedLiveStore.blockArea(200)

        // When
        val areas = BlockedLiveStore.clearAreas()

        // Then: 仅清空分区，主播保持
        assertThat(areas).isEmpty()
        assertThat(BlockedLiveStore.blockedUps()).containsExactly(1L)
    }

    @Test
    fun `clearAll clears both sets`() {
        // Given
        BlockedLiveStore.blockUp(1L)
        BlockedLiveStore.blockArea(200)

        // When
        BlockedLiveStore.clearAll()

        // Then
        assertThat(BlockedLiveStore.blockedUps()).isEmpty()
        assertThat(BlockedLiveStore.blockedAreas()).isEmpty()
        assertThat(Prefs.blockedLiveUps).isEmpty()
        assertThat(Prefs.blockedLiveAreas).isEmpty()
    }

    // ===== 持久化 =====

    @Test
    fun `blocked entries survive reinit from disk`() {
        runBlocking {
            // Given
            BlockedLiveStore.blockUp(9L)
            BlockedLiveStore.blockArea(210)
            awaitAsyncWrite()

            // When: 模拟应用重启（重置内存后用同一 DataStore 重新初始化）
            Prefs.resetForTesting()
            Prefs.init(dataStore)

            // Then: 屏蔽记录从磁盘恢复
            assertThat(BlockedLiveStore.blockedUps()).containsExactly(9L)
            assertThat(BlockedLiveStore.blockedAreas()).containsExactly(210)
        }
    }

    /**
     * 等待异步写入完成。
     *
     * Prefs 的写入是异步的（launchPersist 在 IO 协程执行），
     * 需要短暂等待确保 DataStore 持久化完成。
     */
    private suspend fun awaitAsyncWrite() {
        delay(200)
    }
}
