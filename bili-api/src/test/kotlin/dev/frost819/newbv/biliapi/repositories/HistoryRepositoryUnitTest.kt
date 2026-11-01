package dev.frost819.newbv.biliapi.repositories

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.user.HistoryItemType
import dev.frost819.newbv.biliapi.http.BiliHttpApi
import dev.frost819.newbv.biliapi.http.entity.BiliResponse
import dev.frost819.newbv.biliapi.http.entity.history.HistoryData
import dev.frost819.newbv.biliapi.http.entity.history.HistoryItem
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [HistoryRepository] 的单元测试。
 *
 * 通过 MockK 模拟 [BiliHttpApi] 单例，验证历史记录获取与过滤逻辑。
 * 不依赖真实网络。仅测试 Web API 路径（App 路径依赖 gRPC）。
 */
class HistoryRepositoryUnitTest {
    private lateinit var repository: HistoryRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var channelRepository: ChannelRepository

    @BeforeEach
    fun setUp() {
        mockkObject(BiliHttpApi)
        authRepository = AuthRepository()
        authRepository.sessionData = "test-sessdata"
        channelRepository = ChannelRepository()
        repository = HistoryRepository(authRepository, channelRepository)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(BiliHttpApi)
    }

    @Test
    fun `getHistories Web maps response and filters archive and pgc items`() =
        runTest {
            coEvery { BiliHttpApi.getHistories(any(), any(), any(), any()) } returns
                BiliResponse(
                    code = 0,
                    message = "",
                    data =
                        HistoryData(
                            cursor = HistoryData.Cursor(max = 0, viewAt = 1700000000, business = "archive", ps = 20),
                            tab = emptyList(),
                            list =
                                listOf(
                                    fakeHistoryItem(business = "archive", title = "UGC视频"),
                                    fakeHistoryItem(business = "pgc", title = "番剧"),
                                    fakeHistoryItem(business = "live", title = "直播"),
                                ),
                        ),
                )

            val result = repository.getHistories(cursor = 0L, preferApiType = ApiType.Web)

            assertThat(result.cursor).isEqualTo(1700000000L)
            assertThat(result.data).hasSize(2)
            assertThat(result.data[0].title).isEqualTo("UGC视频")
            assertThat(result.data[0].type).isEqualTo(HistoryItemType.Archive)
            assertThat(result.data[1].title).isEqualTo("番剧")
            assertThat(result.data[1].type).isEqualTo(HistoryItemType.Pgc)
        }

    @Test
    fun `getHistories Web passes viewAt as cursor parameter`() =
        runTest {
            coEvery { BiliHttpApi.getHistories(any(), any(), any(), any()) } returns
                BiliResponse(
                    code = 0,
                    message = "",
                    data =
                        HistoryData(
                            cursor = HistoryData.Cursor(max = 0, viewAt = 0, business = "archive", ps = 20),
                            tab = emptyList(),
                            list = emptyList(),
                        ),
                )

            repository.getHistories(cursor = 12345L, preferApiType = ApiType.Web)

            coVerify { BiliHttpApi.getHistories(max = 0, business = "", viewAt = 12345L, pageSize = 20) }
        }

    @Test
    fun `getHistories Web returns empty list when no archive or pgc items`() =
        runTest {
            coEvery { BiliHttpApi.getHistories(any(), any(), any(), any()) } returns
                BiliResponse(
                    code = 0,
                    message = "",
                    data =
                        HistoryData(
                            cursor = HistoryData.Cursor(max = 0, viewAt = 0, business = "archive", ps = 20),
                            tab = emptyList(),
                            list =
                                listOf(
                                    fakeHistoryItem(business = "live", title = "直播1"),
                                    fakeHistoryItem(business = "article", title = "专栏"),
                                ),
                        ),
                )

            val result = repository.getHistories(cursor = 0L, preferApiType = ApiType.Web)

            assertThat(result.data).isEmpty()
        }

    @Test
    fun `deleteHistory posts encoded arc kid with csrf`() =
        runTest {
            // Given
            authRepository.biliJct = "test-csrf"
            coEvery { BiliHttpApi.deleteHistory(any(), any()) } returns Pair(true, "0")

            // When
            repository.deleteHistory(fakeDomainItem(type = HistoryItemType.Archive, oid = 993403941L))

            // Then
            coVerify { BiliHttpApi.deleteHistory(kid = "arc_993403941", csrf = "test-csrf") }
        }

    @Test
    fun `deleteHistory posts encoded pgc kid with csrf`() =
        runTest {
            // Given
            authRepository.biliJct = "test-csrf"
            coEvery { BiliHttpApi.deleteHistory(any(), any()) } returns Pair(true, "0")

            // When
            repository.deleteHistory(fakeDomainItem(type = HistoryItemType.Pgc, oid = 100L, seasonId = 328146))

            // Then
            coVerify { BiliHttpApi.deleteHistory(kid = "pgc_328146", csrf = "test-csrf") }
        }

    @Test
    fun `deleteHistory throws IllegalStateException when response code is not 0`() =
        runTest {
            // Given
            authRepository.biliJct = "test-csrf"
            coEvery { BiliHttpApi.deleteHistory(any(), any()) } returns Pair(false, "csrf无效")

            // When
            val exception =
                runCatching {
                    repository.deleteHistory(fakeDomainItem(type = HistoryItemType.Archive, oid = 1L))
                }.exceptionOrNull()

            // Then
            assertThat(exception).isInstanceOf(IllegalStateException::class.java)
            assertThat(exception?.message).isEqualTo("csrf无效")
        }

    @Test
    fun `deleteHistory throws IllegalArgumentException when kid cannot be encoded`() =
        runTest {
            // Given: PGC 条目缺少 season id，无法构造合法 kid

            // When
            val exception =
                runCatching {
                    repository.deleteHistory(fakeDomainItem(type = HistoryItemType.Pgc, oid = 1L, seasonId = null))
                }.exceptionOrNull()

            // Then
            assertThat(exception).isInstanceOf(IllegalArgumentException::class.java)
            coVerify(exactly = 0) { BiliHttpApi.deleteHistory(any(), any()) }
        }

    private fun fakeDomainItem(
        type: HistoryItemType,
        oid: Long,
        seasonId: Int? = null,
    ) = dev.frost819.newbv.biliapi.entity.user.HistoryItem(
        oid = oid,
        bvid = "BV1xx",
        cid = 1051761130L,
        kid = 0L,
        epid = null,
        seasonId = seasonId,
        title = "标题",
        cover = "http://cover.test",
        author = "UP主",
        mid = 12345L,
        duration = 300,
        progress = 120,
        type = type,
    )

    private fun fakeHistoryItem(
        business: String,
        title: String,
    ) = HistoryItem(
        title = title,
        longTitle = "",
        cover = "https://example.com/cover.jpg",
        covers = null,
        uri = "https://www.bilibili.com",
        history =
            HistoryItem.HistoryInfo(
                oid = 123L,
                epid = 0,
                bvid = "BV1xx411c7mD",
                page = 1,
                cid = 456L,
                part = "第一P",
                business = business,
                dt = 2,
            ),
        videos = 1,
        authorName = "UP主",
        authorFace = "https://example.com/face.jpg",
        authorMid = 789L,
        viewAt = 1700000000,
        progress = 60,
        badge = "",
        showTitle = "",
        duration = 300,
        current = "",
        total = 0,
        newDesc = "",
        isFinish = 0,
        isFav = 0,
        kid = 0L,
        tagName = "综合",
        liveStatus = 0,
    )
}
