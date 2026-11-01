package dev.frost819.newbv.player.impl.exo

import android.content.Context
import dev.frost819.newbv.player.VideoPlayerOptions
import dev.frost819.newbv.player.cache.PlayerMediaCache
import dev.frost819.newbv.player.factory.PlayerFactory
import okhttp3.OkHttpClient

/**
 * ExoPlayer 播放器工厂。
 *
 * 创建 [ExoMediaPlayer] 实例，供 Hilt Module 注入或直接使用。
 *
 * @param httpClient 播放器媒体请求复用的共享 OkHttpClient。
 *   由 `PlayerModule` 提供进程级单例，保证所有播放器实例共用一个连接池。
 * @param mediaCache 播放媒体磁盘缓存（进程级单例，可空）。
 *   非空且容量档位开启时按档位边播边缓存；null 表示完全关闭缓存。
 */
class ExoPlayerFactory(
    private val httpClient: OkHttpClient,
    private val mediaCache: PlayerMediaCache? = null,
) : PlayerFactory<ExoMediaPlayer>() {
    /**
     * 本工厂注入的共享 OkHttpClient。
     *
     * 供测试断言「同一工厂创建的所有播放器复用同一 client」，
     * 防止有人改回按播放器实例创建（会导致每轮播放新增一个连接池）。
     */
    val client: OkHttpClient
        get() = httpClient

    override fun create(
        context: Context,
        options: VideoPlayerOptions,
    ): ExoMediaPlayer = ExoMediaPlayer(context, options, httpClient, mediaCache)
}
