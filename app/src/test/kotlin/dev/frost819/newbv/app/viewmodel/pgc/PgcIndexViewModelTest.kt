package dev.frost819.newbv.app.viewmodel.pgc

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.pgc.PgcItem
import dev.frost819.newbv.biliapi.entity.pgc.PgcType
import dev.frost819.newbv.biliapi.entity.pgc.index.Area
import dev.frost819.newbv.biliapi.entity.pgc.index.Copyright
import dev.frost819.newbv.biliapi.entity.pgc.index.IndexOrder
import dev.frost819.newbv.biliapi.entity.pgc.index.IndexOrderType
import dev.frost819.newbv.biliapi.entity.pgc.index.IsFinish
import dev.frost819.newbv.biliapi.entity.pgc.index.PgcIndexData
import dev.frost819.newbv.biliapi.entity.pgc.index.Producer
import dev.frost819.newbv.biliapi.entity.pgc.index.ReleaseDate
import dev.frost819.newbv.biliapi.entity.pgc.index.SeasonMonth
import dev.frost819.newbv.biliapi.entity.pgc.index.SeasonStatus
import dev.frost819.newbv.biliapi.entity.pgc.index.SeasonVersion
import dev.frost819.newbv.biliapi.entity.pgc.index.SpokenLanguage
import dev.frost819.newbv.biliapi.entity.pgc.index.Style
import dev.frost819.newbv.biliapi.entity.pgc.index.Year
import dev.frost819.newbv.biliapi.http.SeasonIndexType
import dev.frost819.newbv.biliapi.repositories.PgcRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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

/**
 * [PgcIndexViewModel] 的单元测试。
 *
 * 验证筛选维度到请求参数的映射、翻页去重、刷新代际失效与错误处理。
 * 使用 MockK mock [PgcRepository]，Turbine 观察状态流。
 */
class PgcIndexViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var pgcRepository: PgcRepository
    private lateinit var viewModel: PgcIndexViewModel

    /** 捕获每次请求的排序参数。 */
    private val capturedOrders = mutableListOf<IndexOrder>()

    /** 捕获每次请求的页码。 */
    private val capturedPages = mutableListOf<Int>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        pgcRepository = mockk()
        capturedOrders.clear()
        capturedPages.clear()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(pgcType: String? = null): PgcIndexViewModel {
        val stateHandle =
            if (pgcType == null) {
                SavedStateHandle(emptyMap())
            } else {
                SavedStateHandle(mapOf("pgcType" to pgcType))
            }
        return PgcIndexViewModel(pgcRepository, stateHandle)
    }

    private fun fakePgcItem(seasonId: Int) =
        PgcItem(
            cover = "https://example.com/cover$seasonId.jpg",
            title = "作品 $seasonId",
            subTitle = "副标题 $seasonId",
            seasonId = seasonId,
            episodeId = seasonId * 100,
            seasonType = SeasonIndexType.Anime,
            rating = "9.0",
        )

    private fun fakeIndexData(
        seasonIds: List<Int>,
        hasNext: Boolean,
        nextPageNum: Int = 2,
    ) = PgcIndexData(
        list = seasonIds.map { fakePgcItem(it) },
        nextPage =
            PgcIndexData.PgcIndexPage(
                totalSize = seasonIds.size,
                nextPage = nextPageNum,
                hasNext = hasNext,
            ),
    )

    /** 记录每次请求的排序与页码，并按调用序返回 [respond]。 */
    private fun stubGetPgcIndex(respond: (callIndex: Int) -> PgcIndexData) {
        coEvery {
            pgcRepository.getPgcIndex(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } answers {
            capturedOrders.add(secondArg())
            capturedPages.add(arg<PgcIndexData.PgcIndexPage>(14).nextPage)
            respond(capturedOrders.size)
        }
    }

    /** 校验 [count] 次索引请求（不限制参数）。 */
    private fun verifyRepoCalls(count: Int = 1) {
        coVerify(exactly = count) {
            pgcRepository.getPgcIndex(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `init loads first page with default params from route`() =
        runTest(testDispatcher) {
            // Given: 路由指定电影分区
            stubGetPgcIndex { fakeIndexData(listOf(10), hasNext = true) }

            // When: 初始化 ViewModel
            viewModel = createViewModel("Movie")
            advanceUntilIdle()

            // Then: 电影分区默认排序为播放数量，全部维度不筛选，请求第一页
            coVerify {
                pgcRepository.getPgcIndex(
                    PgcType.Movie,
                    IndexOrder.PlayCount,
                    IndexOrderType.Desc,
                    SeasonVersion.All,
                    SpokenLanguage.All,
                    Area.All,
                    IsFinish.All,
                    Copyright.All,
                    SeasonStatus.All,
                    SeasonMonth.All,
                    Producer.All,
                    Year.All,
                    ReleaseDate.All,
                    Style.All,
                    PgcIndexData.PgcIndexPage(currentPage = 1, pageSize = 20, nextPage = 1),
                )
            }
            assertThat(viewModel.uiState.value.items).hasSize(1)
        }

    @Test
    fun `invalid route pgcType falls back to anime`() =
        runTest(testDispatcher) {
            // Given: 路由携带非法分区名
            stubGetPgcIndex { fakeIndexData(listOf(1), hasNext = false) }

            // When: 初始化 ViewModel
            viewModel = createViewModel("Nonsense")
            advanceUntilIdle()

            // Then: 回退到番剧分区
            assertThat(viewModel.uiState.value.filter.pgcType).isEqualTo(PgcType.Anime)
        }

    @Test
    fun `switchType resets dimensions to new type defaults and reloads page one`() =
        runTest(testDispatcher) {
            // Given: 番剧分区已按追番人数加载
            stubGetPgcIndex { fakeIndexData(listOf(1), hasNext = false) }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()
            assertThat(
                viewModel.uiState.value.filter.order,
            ).isEqualTo(IndexOrder.FollowCount)

            // When: 切换到电视剧分区
            viewModel.switchType(PgcType.Tv)
            advanceUntilIdle()

            // Then: 排序重置为电视剧默认（播放数量），页码重置为 1
            assertThat(
                viewModel.uiState.value.filter.order,
            ).isEqualTo(IndexOrder.PlayCount)
            assertThat(capturedPages.last()).isEqualTo(1)
        }

    @Test
    fun `switchType to same type with existing items is no-op`() =
        runTest(testDispatcher) {
            // Given: 番剧分区已加载完成
            stubGetPgcIndex { fakeIndexData(listOf(1), hasNext = false) }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()

            viewModel.uiState.test {
                awaitItem() // 消费订阅时 StateFlow 发出的当前值
                // When: 再次切换到番剧分区（顶部导航焦点滑过会重复触发）
                viewModel.switchType(PgcType.Anime)
                advanceUntilIdle()

                // Then: 状态无变化，也不重新请求
                expectNoEvents()
            }
            verifyRepoCalls(1)
        }

    @Test
    fun `selectOption maps dimension option to request param and resets pagination`() =
        runTest(testDispatcher) {
            // Given: 番剧分区默认排序已加载两页
            stubGetPgcIndex { call ->
                when (call) {
                    1 -> fakeIndexData(listOf(1, 2), hasNext = true, nextPageNum = 2)
                    2 -> fakeIndexData(listOf(2, 3), hasNext = true, nextPageNum = 3)
                    else -> fakeIndexData(listOf(5), hasNext = false, nextPageNum = 2)
                }
            }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()
            viewModel.loadMore()
            advanceUntilIdle()
            assertThat(capturedPages).containsExactly(1, 2).inOrder()

            // When: 选择排序维度的第 3 项（最高评分）
            viewModel.selectOption(PgcIndexDimension.Order, 2)
            advanceUntilIdle()

            // Then: 请求参数携带 Score 且页码重置为 1，旧数据被替换
            assertThat(capturedOrders.last()).isEqualTo(IndexOrder.Score)
            assertThat(capturedPages.last()).isEqualTo(1)
            assertThat(
                viewModel.uiState.value.filter.order,
            ).isEqualTo(IndexOrder.Score)
            assertThat(
                viewModel.uiState.value.items
                    .map { it.seasonId },
            ).containsExactly(5)
        }

    @Test
    fun `selectOption with same option is no-op`() =
        runTest(testDispatcher) {
            // Given: 番剧分区默认排序已是列表首项（追番人数）
            stubGetPgcIndex { fakeIndexData(listOf(1), hasNext = false) }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()

            viewModel.uiState.test {
                awaitItem() // 消费订阅时 StateFlow 发出的当前值
                // When: 再次选择列表首项
                viewModel.selectOption(PgcIndexDimension.Order, 0)
                advanceUntilIdle()

                // Then: 状态无变化，也不重新请求
                expectNoEvents()
            }
            verifyRepoCalls(1)
        }

    @Test
    fun `selectOption with out-of-range index is ignored`() =
        runTest(testDispatcher) {
            // Given: 番剧分区已加载
            stubGetPgcIndex { fakeIndexData(listOf(1), hasNext = false) }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()

            viewModel.uiState.test {
                awaitItem() // 消费订阅时 StateFlow 发出的当前值
                // When: 传入越界的选项下标
                viewModel.selectOption(PgcIndexDimension.Order, 99)
                advanceUntilIdle()

                // Then: 忽略本次选择
                expectNoEvents()
            }
            verifyRepoCalls(1)
        }

    @Test
    fun `loadMore appends and dedupes across pages`() =
        runTest(testDispatcher) {
            // Given: 第一页返回 [1,2]，第二页返回 [2,3]
            stubGetPgcIndex { call ->
                if (call == 1) {
                    fakeIndexData(listOf(1, 2), hasNext = true, nextPageNum = 2)
                } else {
                    fakeIndexData(listOf(2, 3), hasNext = false, nextPageNum = 3)
                }
            }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()

            // When: 加载第二页
            viewModel.loadMore()
            advanceUntilIdle()

            // Then: 跨页重复的 seasonId 被去重，顺序保持
            assertThat(
                viewModel.uiState.value.items
                    .map { it.seasonId },
            ).containsExactly(1, 2, 3).inOrder()
            assertThat(viewModel.uiState.value.hasMore).isFalse()
        }

    @Test
    fun `loadMore is no-op when hasMore is false`() =
        runTest(testDispatcher) {
            // Given: 只有一页数据且没有更多
            stubGetPgcIndex { fakeIndexData(listOf(1), hasNext = false) }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.hasMore).isFalse()

            // When: 触发加载更多
            viewModel.loadMore()
            advanceUntilIdle()

            // Then: 不发起新请求
            verifyRepoCalls(1)
        }

    @Test
    fun `switchType discards stale response from previous generation`() =
        runTest(testDispatcher) {
            // Given: 番剧首页请求挂起，电影分区请求立即返回
            val animeGate = CompletableDeferred<PgcIndexData>()
            val animeData = fakeIndexData(listOf(1), hasNext = false)
            val movieData = fakeIndexData(listOf(200), hasNext = false)
            coEvery {
                pgcRepository.getPgcIndex(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers {
                when (firstArg<PgcType>()) {
                    PgcType.Anime -> animeGate.await()
                    else -> movieData
                }
            }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()
            viewModel.switchType(PgcType.Movie)
            advanceUntilIdle()
            assertThat(
                viewModel.uiState.value.items
                    .map { it.seasonId },
            ).containsExactly(200)

            viewModel.uiState.test {
                awaitItem() // 消费订阅时 StateFlow 发出的当前值
                // When: 陈旧的番剧响应到达（代际已过期）
                animeGate.complete(animeData)
                advanceUntilIdle()

                // Then: 不产生任何状态更新
                expectNoEvents()
                assertThat(
                    viewModel.uiState.value.items
                        .map { it.seasonId },
                ).containsExactly(200)
            }
        }

    @Test
    fun `selectOption discards stale response from previous generation`() =
        runTest(testDispatcher) {
            // Given: 默认排序请求挂起，最高评分排序请求立即返回
            val staleGate = CompletableDeferred<PgcIndexData>()
            val fastData = fakeIndexData(listOf(9), hasNext = false)
            coEvery {
                pgcRepository.getPgcIndex(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } coAnswers {
                if (secondArg<IndexOrder>() == IndexOrder.FollowCount) {
                    staleGate.await()
                } else {
                    fastData
                }
            }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()
            viewModel.selectOption(PgcIndexDimension.Order, 2)
            advanceUntilIdle()
            assertThat(
                viewModel.uiState.value.items
                    .map { it.seasonId },
            ).containsExactly(9)

            viewModel.uiState.test {
                awaitItem() // 消费订阅时 StateFlow 发出的当前值
                // When: 陈旧的默认排序响应到达（代际已过期）
                staleGate.complete(fakeIndexData(listOf(1), hasNext = false))
                advanceUntilIdle()

                // Then: 不产生任何状态更新
                expectNoEvents()
                assertThat(
                    viewModel.uiState.value.items
                        .map { it.seasonId },
                ).containsExactly(9)
            }
        }

    @Test
    fun `load failure sets error and refresh clears it`() =
        runTest(testDispatcher) {
            // Given: 首次请求失败，刷新后成功
            var callCount = 0
            stubGetPgcIndex { _ ->
                callCount++
                if (callCount == 1) {
                    throw IOException("network error")
                }
                fakeIndexData(listOf(7), hasNext = false)
            }
            viewModel = createViewModel("Anime")
            advanceUntilIdle()

            // Then: error 置位且无数据
            assertThat(viewModel.uiState.value.error).isTrue()
            assertThat(viewModel.uiState.value.items).isEmpty()
            assertThat(viewModel.uiState.value.loading).isFalse()

            // When: 刷新
            viewModel.refresh()
            advanceUntilIdle()

            // Then: 错误清除并成功加载
            assertThat(viewModel.uiState.value.error).isFalse()
            assertThat(
                viewModel.uiState.value.items
                    .map { it.seasonId },
            ).containsExactly(7)
        }
}
