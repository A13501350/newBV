package dev.frost819.newbv.app.util

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.util.AvBvConverter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [PsasRepository] 的单元测试。
 *
 * 验证随机选取、BV → AV 换算与播放会话标记逻辑。
 */
class PsasRepositoryTest {
    @BeforeEach
    fun setUp() {
        // 对象单例的会话状态在每个用例前复位
        PsasRepository.endPlaybackIfCurrent(PsasRepository.activePsasAid.value)
    }

    @AfterEach
    fun tearDown() {
        PsasRepository.endPlaybackIfCurrent(PsasRepository.activePsasAid.value)
    }

    @Test
    fun `psas list is not empty and entries are valid bvids`() {
        assertThat(PsasRepository.PSAS_BVIDS).isNotEmpty()
        PsasRepository.PSAS_BVIDS.forEach { bvid ->
            assertThat(bvid).startsWith("BV")
            assertThat(bvid).hasLength(12)
        }
    }

    @Test
    fun `getRandomVideo returns item with converted aid and zero cid`() {
        // When: 随机选取一条公益广告
        val video = checkNotNull(PsasRepository.getRandomVideo())

        // Then: aid 由内置 BV 号换算，cid 传 0 交给播放器解析详情
        assertThat(video.aid).isEqualTo(AvBvConverter.bv2av(PsasRepository.PSAS_BVIDS.first()))
        assertThat(video.cid).isEqualTo(0L)
        assertThat(video.title).isNotEmpty()
    }

    @Test
    fun `playback session marks and clears only for current aid`() {
        // Given: 会话未开始
        assertThat(PsasRepository.activePsasAid.value).isEqualTo(0L)

        // When: 标记公益广告开始播放
        PsasRepository.beginPlayback(100L)

        // Then: 会话标记生效
        assertThat(PsasRepository.activePsasAid.value).isEqualTo(100L)
        assertThat(PsasRepository.isPsasPlayback(100L)).isTrue()

        // When: 其它视频退出
        PsasRepository.endPlaybackIfCurrent(200L)

        // Then: 会话保持
        assertThat(PsasRepository.isPsasPlayback(100L)).isTrue()

        // When: 当前公益广告退出
        PsasRepository.endPlaybackIfCurrent(100L)

        // Then: 会话结束
        assertThat(PsasRepository.activePsasAid.value).isEqualTo(0L)
        assertThat(PsasRepository.isPsasPlayback(100L)).isFalse()
    }

    @Test
    fun `isPsasPlayback returns false for zero aid`() {
        // Given: 会话未开始
        assertThat(PsasRepository.activePsasAid.value).isEqualTo(0L)

        // Then: 0 号位不算公益广告会话
        assertThat(PsasRepository.isPsasPlayback(0L)).isFalse()
    }
}
