package dev.frost819.newbv.app.util

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.http.entity.RiskControlException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * [toPlaybackLoadErrorMessage] 的单元测试。
 *
 * 保证播放数据加载失败时不再把英文异常信息直接丢给用户，且能识别被包装过的底层原因。
 */
class LoadErrorMessagesTest {
    @Test
    fun `risk control exception maps to risk control message`() {
        val error = RiskControlException("请求过于频繁")

        assertThat(error.toPlaybackLoadErrorMessage()).isEqualTo("触发风控限制，请稍后再试")
    }

    @Test
    fun `io exception maps to network message`() {
        assertThat(IOException("Connection reset").toPlaybackLoadErrorMessage())
            .isEqualTo("网络异常，请检查网络后重试")
        assertThat(SocketTimeoutException("timeout").toPlaybackLoadErrorMessage())
            .isEqualTo("网络异常，请检查网络后重试")
    }

    @Test
    fun `wrapped io exception maps to network message`() {
        // Given: 仓库层常见包装（业务异常包着底层 IO）
        val error = IllegalStateException("load failed", SocketTimeoutException("timeout"))

        // Then
        assertThat(error.toPlaybackLoadErrorMessage()).isEqualTo("网络异常，请检查网络后重试")
    }

    @Test
    fun `wrapped risk control exception wins over network detection`() {
        val error = RuntimeException("wrapped", RiskControlException("风控"))

        assertThat(error.toPlaybackLoadErrorMessage()).isEqualTo("触发风控限制，请稍后再试")
    }

    @Test
    fun `unknown exception maps to load failure message`() {
        assertThat(IllegalStateException("boom").toPlaybackLoadErrorMessage())
            .isEqualTo("视频源加载失败，请稍后重试")
    }

    @Test
    fun `cyclic cause chain does not hang`() {
        // Given: 互相引用形成的环（防御性用例，避免回溯死循环）
        val first = IllegalStateException("first")
        val second = IllegalStateException("second")
        first.initCause(second)
        second.initCause(first)

        // Then
        assertThat(first.toPlaybackLoadErrorMessage()).isEqualTo("视频源加载失败，请稍后重试")
    }
}
