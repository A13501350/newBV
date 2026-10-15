package dev.frost819.newbv.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.player.impl.exo.ExoMediaPlayer
import dev.frost819.newbv.player.impl.exo.ExoPlayerFactory
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [ExoPlayerFactory] 的插桩测试。
 *
 * 核心断言：同一工厂创建的所有播放器复用注入的**共享** OkHttpClient，
 * 即共用一个 ConnectionPool。
 *
 * 背景：`OkHttpClient` 的 ConnectionPool 是「每个 client 一个」（默认 5 条空闲连接 /
 * 5 分钟保活），HTTP/2 连接在池中保活期间各占一个线程。若改回「每个播放器实例新建
 * client」，每个播放会话都会新增一个连接池，进程线程数随播放轮次上升，同时每次
 * 进播放器都要重建 SSLContext/KeyStore。
 */
@RunWith(AndroidJUnit4::class)
class ExoPlayerFactoryTest {
    private lateinit var context: Context

    /** 已创建的播放器，测试结束后统一释放 */
    private val createdPlayers = mutableListOf<ExoMediaPlayer>()

    private fun <T> runOnMain(block: () -> T): T {
        val result = arrayOfNulls<Any>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result[0] = block()
        }
        @Suppress("UNCHECKED_CAST")
        return result[0] as T
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun teardown() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            createdPlayers.forEach { it.release() }
        }
        createdPlayers.clear()
    }

    @Test
    fun create_reusesInjectedHttpClientAcrossPlayers() {
        // Given：模拟 PlayerModule 提供的进程级共享 client
        val sharedClient = OkHttpUtil.generateCustomSslOkHttpClient(context)
        val factory = ExoPlayerFactory(sharedClient)

        // When
        val player1 = runOnMain { factory.create(context, VideoPlayerOptions()) }
        val player2 = runOnMain { factory.create(context, VideoPlayerOptions()) }
        createdPlayers += player1
        createdPlayers += player2

        // Then：工厂与所有播放器持有同一实例 → 同一 ConnectionPool
        assertThat(factory.client).isSameInstanceAs(sharedClient)
        assertThat(player1.httpClient).isSameInstanceAs(sharedClient)
        assertThat(player2.httpClient).isSameInstanceAs(sharedClient)
        assertThat(player1.httpClient).isSameInstanceAs(player2.httpClient)
    }
}
