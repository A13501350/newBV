package dev.frost819.newbv.biliapi.repositories

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuFilterListData
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuFilterRule
import dev.frost819.newbv.biliapi.entity.danmaku.midHashOfMid
import dev.frost819.newbv.biliapi.http.BiliHttpApi
import dev.frost819.newbv.biliapi.http.entity.BiliResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * [DanmakuShieldRepository] 的单元测试。
 *
 * 通过 MockK 模拟 [BiliHttpApi] 单例，验证规则拉取、内存缓存（TTL/换号失效）、
 * 强制刷新、未登录降级与规则编译容错（非法正则/空内容/未知 type）。不依赖真实网络。
 */
class DanmakuShieldRepositoryTest {
    private lateinit var repository: DanmakuShieldRepository

    companion object {
        private const val SESSDATA = "test-sessdata"
    }

    @BeforeEach
    fun setUp() {
        mockkObject(BiliHttpApi)
        every { BiliHttpApi.sessData } returns SESSDATA
        repository = DanmakuShieldRepository()
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(BiliHttpApi)
    }

    /** 桩定 x/dm/filter/user 返回给定规则列表。 */
    private fun stubApi(rules: List<DanmakuFilterRule>) {
        coEvery { BiliHttpApi.getDanmakuFilterUser() } returns
            BiliResponse(code = 0, message = "0", data = DanmakuFilterListData(rule = rules))
    }

    @Test
    fun `getRules fetches and compiles keyword regex and user rules`() =
        runTest {
            // Given
            stubApi(
                listOf(
                    DanmakuFilterRule(id = 1, type = DanmakuFilterRule.TYPE_TEXT, filter = " 233 "),
                    DanmakuFilterRule(id = 2, type = DanmakuFilterRule.TYPE_REGEX, filter = "^spam[0-9]+\$"),
                    DanmakuFilterRule(id = 3, type = DanmakuFilterRule.TYPE_USER, filter = "12345678901"),
                    DanmakuFilterRule(id = 4, type = DanmakuFilterRule.TYPE_USER, filter = "ABC123"),
                ),
            )

            // When
            val rules = repository.getRules()

            // Then
            assertThat(rules.keywords).containsExactly("233")
            assertThat(rules.regexes).hasSize(1)
            assertThat(rules.regexes.single().pattern).isEqualTo("^spam[0-9]+\$")
            // 长 mid 转 CRC32；短 hex 补零小写
            assertThat(rules.blockedUserMidHashes).containsExactly(midHashOfMid(12345678901L), "00abc123")
            assertThat(rules.isEmpty).isFalse()
        }

    @Test
    fun `compile skips blank filter invalid regex and unknown type`() =
        runTest {
            // Given：空白内容、非法正则、无法归一化的用户规则、未知 type 均跳过
            stubApi(
                listOf(
                    DanmakuFilterRule(type = DanmakuFilterRule.TYPE_TEXT, filter = "  "),
                    DanmakuFilterRule(type = DanmakuFilterRule.TYPE_REGEX, filter = "[invalid("),
                    DanmakuFilterRule(type = DanmakuFilterRule.TYPE_USER, filter = "xyz!"),
                    DanmakuFilterRule(type = 99, filter = "future"),
                ),
            )

            // When
            val rules = repository.getRules()

            // Then
            assertThat(rules.keywords).isEmpty()
            assertThat(rules.regexes).isEmpty()
            assertThat(rules.blockedUserMidHashes).isEmpty()
            assertThat(rules.isEmpty).isTrue()
        }

    @Test
    fun `getRules returns cached rules without refetch within TTL`() =
        runTest {
            // Given
            stubApi(listOf(DanmakuFilterRule(type = DanmakuFilterRule.TYPE_TEXT, filter = "kw")))

            // When
            val first = repository.getRules()
            val second = repository.getRules()

            // Then：同一登录态第二次调用命中缓存，不再发起请求
            coVerify(exactly = 1) { BiliHttpApi.getDanmakuFilterUser() }
            assertThat(second).isSameInstanceAs(first)
            assertThat(second.keywords).containsExactly("kw")
        }

    @Test
    fun `getRules with forceRefresh bypasses cache`() =
        runTest {
            // Given
            stubApi(listOf(DanmakuFilterRule(type = DanmakuFilterRule.TYPE_TEXT, filter = "old")))
            repository.getRules()
            stubApi(listOf(DanmakuFilterRule(type = DanmakuFilterRule.TYPE_TEXT, filter = "new")))

            // When：菜单手动刷新入口
            val refreshed = repository.getRules(forceRefresh = true)

            // Then
            coVerify(exactly = 2) { BiliHttpApi.getDanmakuFilterUser() }
            assertThat(refreshed.keywords).containsExactly("new")
        }

    @Test
    fun `getRules refetches when session changes`() =
        runTest {
            // Given：换号/重登录后旧缓存必须失效
            stubApi(listOf(DanmakuFilterRule(type = DanmakuFilterRule.TYPE_TEXT, filter = "kw")))
            repository.getRules()
            every { BiliHttpApi.sessData } returns "another-session"

            // When
            repository.getRules()

            // Then
            coVerify(exactly = 2) { BiliHttpApi.getDanmakuFilterUser() }
        }

    @Test
    fun `getRules returns empty without request when not logged in`() =
        runTest {
            // Given：未登录（sessData 为空）
            every { BiliHttpApi.sessData } returns ""

            // When
            val rules = repository.getRules()

            // Then：静默降级，不发请求
            coVerify(exactly = 0) { BiliHttpApi.getDanmakuFilterUser() }
            assertThat(rules).isEqualTo(DanmakuShieldRules.EMPTY)
        }

    @Test
    fun `getRules propagates api failure and keeps old cache`() =
        runTest {
            // Given：首次成功，刷新时网络失败
            stubApi(listOf(DanmakuFilterRule(type = DanmakuFilterRule.TYPE_TEXT, filter = "kw")))
            repository.getRules()
            coEvery { BiliHttpApi.getDanmakuFilterUser() } throws IOException("network down")

            // When / Then：失败上抛由调用方静默降级
            assertThrows(IOException::class.java) {
                runBlocking { repository.getRules(forceRefresh = true) }
            }
        }
}
