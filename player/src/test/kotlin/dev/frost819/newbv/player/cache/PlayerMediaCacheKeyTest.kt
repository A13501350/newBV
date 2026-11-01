package dev.frost819.newbv.player.cache

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [PlayerMediaCache.normalizeCacheKey] 的单元测试。
 *
 * 缓存 key 规范化直接决定 SimpleCache 命中率：B 站播放地址的 query 全部是
 * 会话级签名/过期参数（每次请求都变），不剥离则同一视频每次播放都是新 key。
 * 这里只覆盖纯函数语义，不触碰磁盘与数据库。
 */
class PlayerMediaCacheKeyTest {
    @Test
    fun `bilibili upos url strips volatile signature params`() {
        // Given：真实 upos 播放地址形态（e/oi/os/trid/deadline/gen/upsig 等均为会话级参数）
        val url =
            "https://upos-sz-mirror08c.bilivideo.com/upsv/a1b2c3d4/30280.m4s" +
                "?e=abc123&oi=1873924302&os=123456&ogfi=100110&trid=7f3a1c&mid=0" +
                "&deadline=1730000000&gen=playurlv2&og=cos&upsig=deadbeef" +
                "&uparams=e,oi,os,ogfi,trid,mid,deadline,gen,og,upsig&cdnid=1234&bvc=vod&nettype=0"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then：scheme/host 与全部 query 剥离，仅保留资源路径（跨 CDN 源共享 key）
        assertThat(key).isEqualTo("/upsv/a1b2c3d4/30280.m4s")
    }

    @Test
    fun `baseUrl and backupUrl resolve to the same key`() {
        // Given：同一路径、不同 CDN host（playurl 的 baseUrl 与 backupUrl 典型形态）
        val baseUrl =
            "https://upos-sz-mirror08c.bilivideo.com/upsv/a1b2c3d4/30280.m4s" +
                "?deadline=1730000000&upsig=aaa"
        val backupUrl =
            "https://upos-sz-mirrorccn.bilivideo.com/upsv/a1b2c3d4/30280.m4s" +
                "?deadline=1730000100&upsig=bbb"

        // When
        val baseKey = PlayerMediaCache.normalizeCacheKey(baseUrl)
        val backupKey = PlayerMediaCache.normalizeCacheKey(backupUrl)

        // Then：host 与会话参数不同不影响 key 一致
        assertThat(baseKey).isEqualTo(backupKey)
    }

    @Test
    fun `mcdn proxy url keeps embedded origin path as key`() {
        // Given：第三方 PCDN 域名把源站 host 编码在首段路径中，query 为 expires/deadline 等
        val url =
            "https://xy601x212x16xy.maidoudou.top/upos-dm-mdc-va1.mcdn.bilivideo.com:443" +
                "/v1/resource/64945/7/a1b2/30280.m4s" +
                "?expires=1730000000&deadline=1730000000&plt=4&rch=1&req_id=abc"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then：外层 host 剥离，路径（含内嵌源站 host）保留为 key
        assertThat(key)
            .isEqualTo("/upos-dm-mdc-va1.mcdn.bilivideo.com:443/v1/resource/64945/7/a1b2/30280.m4s")
    }

    @Test
    fun `url without query params is returned as-is`() {
        // Given：无 query 的普通地址（非 B 站域名）
        val url = "https://example.com/media/video.mp4"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then：无参数可剥离，原样返回
        assertThat(key).isEqualTo(url)
    }

    @Test
    fun `non bilibili url strips only known volatile params`() {
        // Given：非 B 站域名，混合已知会话参数与普通参数
        val url =
            "https://mirror.example.com/v.mp4?Expires=123&DEADLINE=456&upsig=789&id=7&sig=keepme"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then：已知会话参数按大小写不敏感剥离，其余参数原样保留
        assertThat(key).isEqualTo("https://mirror.example.com/v.mp4?id=7&sig=keepme")
    }

    @Test
    fun `fragment is stripped from key`() {
        // Given：带 fragment 的地址
        val url = "https://example.com/media/video.mp4#t=10"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then
        assertThat(key).isEqualTo("https://example.com/media/video.mp4")
    }

    @Test
    fun `stripping last volatile param drops dangling question mark`() {
        // Given：非 B 站地址仅剩会话参数
        val url = "https://mirror.example.com/v.mp4?deadline=123"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then：参数清空后不保留悬空的 '?'
        assertThat(key).isEqualTo("https://mirror.example.com/v.mp4")
    }

    @Test
    fun `bilibili url without query params yields path-only key`() {
        // Given：B 站 CDN 地址但无 query
        val url = "https://upos-sz-mirror08c.bilivideo.com/upsv/a1b2c3d4/30280.m4s"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then：host 剥离，路径保留（与带会话参数的地址同 key）
        assertThat(key).isEqualTo("/upsv/a1b2c3d4/30280.m4s")
    }

    @Test
    fun `url without scheme and path is returned as-is`() {
        // Given：非标准输入（无 scheme）
        val url = "some-raw-content-id"

        // When
        val key = PlayerMediaCache.normalizeCacheKey(url)

        // Then：无法解析 scheme，原样返回
        assertThat(key).isEqualTo(url)
    }
}
