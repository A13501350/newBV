package dev.frost819.newbv.player

import dev.frost819.newbv.player.entity.AudioBalanceLevel

/**
 * 播放器配置选项。
 *
 * @param userAgent 自定义 User-Agent，用于 HTTP 请求
 * @param referer 自定义 Referer，用于 B 站防盗链验证
 * @param enableSoftwareVideoDecoder 是否强制使用软件视频解码（用于不支持硬解的编码）
 * @param audioBalanceLevel 音量均衡初始档位（PCM 级自适应响度归一），播放中可通过
 *   [AbstractVideoPlayer.volumeBalanceLevel] 即时调整，默认关闭
 */
data class VideoPlayerOptions(
    val userAgent: String? = null,
    val referer: String? = null,
    val enableSoftwareVideoDecoder: Boolean = false,
    val audioBalanceLevel: AudioBalanceLevel = AudioBalanceLevel.OFF,
)
