package dev.frost819.newbv.app.viewmodel.pgc

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.season.Timeline
import dev.frost819.newbv.biliapi.entity.season.TimelineEp
import dev.frost819.newbv.biliapi.entity.season.TimelineFilter
import dev.frost819.newbv.biliapi.repositories.SeasonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Date

/**
 * [TimelineViewModel] 的单元测试。
 *
 * 验证时间表加载、筛选切换、日期选择与错误重试的状态流转，
 * 以及筛选收敛/日期窗口/分集排序纯函数。
 *
 * 注意：JVM 测试不初始化 Prefs，`Prefs.apiType` 返回默认值 Web，
 * 因此 ViewModel 集成路径覆盖 Web 通道；App 通道的筛选收敛
 * 由 [availableFilters] 纯函数用例覆盖。
 */
class TimelineViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var seasonRepository: SeasonRepository
    private lateinit var viewModel: TimelineViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        seasonRepository = mockk()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeEp(
        seasonId: Int,
        publishIndex: String,
        pubTs: Long,
    ) = TimelineEp(
        cover = "https://example.com/$seasonId.jpg",
        title = "番剧$seasonId",
        seasonId = seasonId,
        publishIndex = publishIndex,
        publishTime = "12:00",
        publishDate = Date(pubTs * 1000L),
    )

    private fun fakeTimeline(
        dateString: String,
        isToday: Boolean = false,
        episodes: List<TimelineEp> = emptyList(),
    ) = Timeline(
        dateString = dateString,
        date = Date(0L),
        dayOfWeek = 1,
        isToday = isToday,
        episodes = episodes,
    )

    /** 三天数据：昨天（分集乱序）+ 今天 + 明天（空）。 */
    private fun fakeTimelines() =
        listOf(
            fakeTimeline(
                "10-27",
                episodes =
                    listOf(
                        fakeEp(2, "第2话", 2000L),
                        fakeEp(1, "第1话", 1000L),
                    ),
            ),
            fakeTimeline(
                "10-28",
                isToday = true,
                episodes = listOf(fakeEp(3, "第3话", 3000L)),
            ),
            fakeTimeline("10-29"),
        )

    // ── 纯函数：筛选收敛 ─────────────────────────────────────────────

    @Test
    fun `availableFilters returns anime and guochuang for Web apiType`() {
        // Given / When
        val filters = availableFilters(ApiType.Web)

        // Then: Web 端点无「追番」过滤
        assertThat(filters).isEqualTo(listOf(TimelineFilter.Anime, TimelineFilter.GuoChuang))
    }

    @Test
    fun `availableFilters returns all filters for App apiType`() {
        // Given / When
        val filters = availableFilters(ApiType.App)

        // Then
        assertThat(filters).isEqualTo(TimelineFilter.appFilters)
        assertThat(filters).contains(TimelineFilter.Following)
    }

    // ── 纯函数：日期选择 ─────────────────────────────────────────────

    @Test
    fun `resolveSelectedDateString keeps current selection when still present`() {
        // Given
        val timelines = fakeTimelines()

        // When
        val selected = resolveSelectedDateString(timelines, "10-27")

        // Then
        assertThat(selected).isEqualTo("10-27")
    }

    @Test
    fun `resolveSelectedDateString prefers today when current is missing`() {
        // Given
        val timelines = fakeTimelines()

        // When
        val selected = resolveSelectedDateString(timelines, "09-01")

        // Then
        assertThat(selected).isEqualTo("10-28")
    }

    @Test
    fun `resolveSelectedDateString falls back to first day when today absent`() {
        // Given
        val timelines =
            listOf(
                fakeTimeline("10-27"),
                fakeTimeline("10-28"),
            )

        // When
        val selected = resolveSelectedDateString(timelines, "")

        // Then
        assertThat(selected).isEqualTo("10-27")
    }

    @Test
    fun `resolveSelectedDateString returns empty for empty timelines`() {
        // Given / When / Then
        assertThat(resolveSelectedDateString(emptyList(), "10-27")).isEmpty()
    }

    // ── 纯函数：日期条窗口 ───────────────────────────────────────────

    @Test
    fun `dateBarDays centers window on today`() {
        // Given: 前后各 7 天共 15 天，今天位于中间（index 7）
        val timelines =
            (1..15).map { index ->
                fakeTimeline("day-$index", isToday = index == 8)
            }

        // When
        val bar = dateBarDays(timelines)

        // Then: 今天前后各 3 天共 7 天
        assertThat(bar).hasSize(7)
        assertThat(bar.first().dateString).isEqualTo("day-5")
        assertThat(bar.map { it.dateString }).contains("day-8")
    }

    @Test
    fun `dateBarDays clamps window at list start`() {
        // Given: 今天是第一天
        val timelines =
            (1..10).map { index ->
                fakeTimeline("day-$index", isToday = index == 1)
            }

        // When
        val bar = dateBarDays(timelines)

        // Then: 窗口起点收敛到 0，只展示今天及之后 3 天
        assertThat(bar).hasSize(4)
        assertThat(bar.first().dateString).isEqualTo("day-1")
    }

    @Test
    fun `dateBarDays falls back to leading days when today absent`() {
        // Given
        val timelines =
            (1..10).map { index ->
                fakeTimeline("day-$index")
            }

        // When
        val bar = dateBarDays(timelines)

        // Then
        assertThat(bar).hasSize(7)
        assertThat(bar.first().dateString).isEqualTo("day-1")
    }

    // ── 纯函数：分集排序 ─────────────────────────────────────────────

    @Test
    fun `sortEpisodes sorts by publish date ascending`() {
        // Given
        val episodes =
            listOf(
                fakeEp(2, "第2话", 2000L),
                fakeEp(1, "第1话", 1000L),
                fakeEp(3, "第3话", 3000L),
            )

        // When
        val sorted = sortEpisodes(episodes)

        // Then
        assertThat(sorted.map { it.seasonId }).containsExactly(1, 2, 3).inOrder()
    }

    // ── ViewModel：加载与状态流转 ─────────────────────────────────────

    @Test
    fun `init emits loading then success states`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } returns fakeTimelines()

            // When
            viewModel = TimelineViewModel(seasonRepository)

            viewModel.uiState.test {
                // Then: 构造后同步进入 loading
                assertThat(awaitItem().isLoading).isTrue()

                advanceUntilIdle()

                // Then: 成功后 loading 结束且数据就绪
                val loaded = awaitItem()
                assertThat(loaded.isLoading).isFalse()
                assertThat(loaded.isError).isFalse()
                assertThat(loaded.timelines).hasSize(3)
            }
        }

    @Test
    fun `init loads timeline and selects today`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } returns fakeTimelines()

            // When
            viewModel = TimelineViewModel(seasonRepository)
            advanceUntilIdle()

            // Then: 默认 Web 通道、番剧筛选、选中今天
            val state = viewModel.uiState.value
            assertThat(state.timelines).hasSize(3)
            assertThat(state.selectedDateString).isEqualTo("10-28")
            assertThat(state.selectedFilter).isEqualTo(TimelineFilter.Anime)
            assertThat(state.filters).isEqualTo(TimelineFilter.webFilters)
            assertThat(state.isLoading).isFalse()
            assertThat(state.isError).isFalse()
            coVerify(exactly = 1) {
                seasonRepository.getTimeline(filter = TimelineFilter.Anime, preferApiType = ApiType.Web)
            }
        }

    @Test
    fun `init selects first day when data has no today`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } returns
                listOf(fakeTimeline("10-27"), fakeTimeline("10-28"))

            // When
            viewModel = TimelineViewModel(seasonRepository)
            advanceUntilIdle()

            // Then
            assertThat(viewModel.uiState.value.selectedDateString).isEqualTo("10-27")
        }

    @Test
    fun `init emits loading then error state on repository failure`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } throws IOException("network error")

            // When
            viewModel = TimelineViewModel(seasonRepository)

            viewModel.uiState.test {
                assertThat(awaitItem().isLoading).isTrue()

                advanceUntilIdle()

                // Then
                val failed = awaitItem()
                assertThat(failed.isError).isTrue()
                assertThat(failed.isLoading).isFalse()
                assertThat(failed.timelines).isEmpty()
                assertThat(failed.errorTip).contains("network error")
            }
        }

    @Test
    fun `loadTimeline retry clears error and reloads`() =
        runTest(testDispatcher) {
            // Given: 首次加载失败
            coEvery { seasonRepository.getTimeline(any(), any()) } throws IOException("network error")
            viewModel = TimelineViewModel(seasonRepository)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.isError).isTrue()

            // When: 重试（改为返回成功数据）
            coEvery { seasonRepository.getTimeline(any(), any()) } returns fakeTimelines()
            viewModel.loadTimeline()
            advanceUntilIdle()

            // Then: 错误清除且数据就绪（不再断言中间 loading 态，避免 StateFlow 合并导致抖动）
            val state = viewModel.uiState.value
            assertThat(state.isError).isFalse()
            assertThat(state.isLoading).isFalse()
            assertThat(state.timelines).hasSize(3)
            assertThat(state.selectedDateString).isEqualTo("10-28")
        }

    // ── ViewModel：筛选切换 ──────────────────────────────────────────

    @Test
    fun `selectFilter reloads with new filter and resets date selection`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } returns fakeTimelines()
            viewModel = TimelineViewModel(seasonRepository)
            advanceUntilIdle()
            viewModel.selectDate("10-27")

            // When
            viewModel.selectFilter(TimelineFilter.GuoChuang)
            advanceUntilIdle()

            // Then: 两次请求分别是默认筛选与新筛选，日期重置回今天
            coVerify(exactly = 1) {
                seasonRepository.getTimeline(filter = TimelineFilter.Anime, preferApiType = ApiType.Web)
            }
            coVerify(exactly = 1) {
                seasonRepository.getTimeline(filter = TimelineFilter.GuoChuang, preferApiType = ApiType.Web)
            }
            assertThat(viewModel.uiState.value.selectedFilter).isEqualTo(TimelineFilter.GuoChuang)
            assertThat(viewModel.uiState.value.selectedDateString).isEqualTo("10-28")
        }

    @Test
    fun `selectFilter with same filter does not reload`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } returns fakeTimelines()
            viewModel = TimelineViewModel(seasonRepository)
            advanceUntilIdle()

            // When: 重复选择当前筛选
            viewModel.selectFilter(TimelineFilter.Anime)
            advanceUntilIdle()

            // Then: 仅 init 时的一次请求
            coVerify(exactly = 1) { seasonRepository.getTimeline(any(), any()) }
        }

    // ── ViewModel：日期选择与分集展示 ─────────────────────────────────

    @Test
    fun `selectDate updates selected episodes sorted by publish time`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } returns fakeTimelines()
            viewModel = TimelineViewModel(seasonRepository)
            advanceUntilIdle()

            // When
            viewModel.selectDate("10-27")

            // Then: 当日分集按播出时间升序（源数据为乱序）
            val episodes = viewModel.uiState.value.selectedEpisodes
            assertThat(episodes.map { it.seasonId }).containsExactly(1, 2).inOrder()
        }

    @Test
    fun `selectedEpisodes is empty when selected day has no episodes`() =
        runTest(testDispatcher) {
            // Given
            coEvery { seasonRepository.getTimeline(any(), any()) } returns fakeTimelines()
            viewModel = TimelineViewModel(seasonRepository)
            advanceUntilIdle()

            // When
            viewModel.selectDate("10-29")

            // Then
            assertThat(viewModel.uiState.value.selectedEpisodes).isEmpty()
        }
}
