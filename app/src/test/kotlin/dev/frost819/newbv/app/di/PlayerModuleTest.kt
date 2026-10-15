package dev.frost819.newbv.app.di

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.player.impl.exo.ExoPlayerFactory
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

/**
 * [PlayerModule] 的单元测试。
 *
 * 验证播放器工厂持有的是注入的**共享** OkHttpClient。
 *
 * 背景：`OkHttpClient` 的 ConnectionPool 是「每个 client 一个」，且 HTTP/2 连接保活期间
 * 会各占一个线程。若工厂按播放器实例新建 client，每个播放会话都会新增一个连接池，
 * 进程线程数会随播放轮次缓慢上升（见 `providePlayerHttpClient` 的说明）。
 */
class PlayerModuleTest {
    @Test
    fun `provideExoPlayerFactory keeps the shared http client`() {
        // Given
        val sharedClient = mockk<OkHttpClient>(relaxed = true)

        // When
        val factory: ExoPlayerFactory = PlayerModule.provideExoPlayerFactory(sharedClient)

        // Then：工厂必须复用同一 client（= 同一 ConnectionPool）
        assertThat(factory.client).isSameInstanceAs(sharedClient)
    }

    @Test
    fun `provideExoPlayerFactory returns a new factory for distinct clients`() {
        // Given
        val clientA = mockk<OkHttpClient>(relaxed = true)
        val clientB = mockk<OkHttpClient>(relaxed = true)

        // When
        val factoryA = PlayerModule.provideExoPlayerFactory(clientA)
        val factoryB = PlayerModule.provideExoPlayerFactory(clientB)

        // Then：工厂本身不做缓存（单例由 Hilt 保证）
        assertThat(factoryA.client).isSameInstanceAs(clientA)
        assertThat(factoryB.client).isSameInstanceAs(clientB)
        assertThat(factoryA.client).isNotSameInstanceAs(factoryB.client)
    }
}
