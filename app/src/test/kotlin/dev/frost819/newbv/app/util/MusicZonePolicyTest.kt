package dev.frost819.newbv.app.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [MusicZonePolicy] 的单元测试。
 *
 * 验证音乐主区（tid=3）与全部子分区命中，非音乐区与未知 tid 不命中。
 * tid 集合以 MyTVB 参考实现为权威来源（见 [MusicZonePolicy] KDoc）。
 */
class MusicZonePolicyTest {
    @Test
    fun `music main zone tid hits`() {
        // Given: 音乐主区 tid=3

        // When/Then: 命中音乐区
        assertThat(MusicZonePolicy.isMusicZone(3)).isTrue()
    }

    @Test
    fun `music sub zone tids hit`() {
        // Given: MyTVB 收录的全部音乐子分区 tid
        val subZoneTids = listOf(28, 29, 30, 31, 59, 130, 193, 194)

        // When/Then: 逐一命中
        subZoneTids.forEach { tid ->
            assertThat(MusicZonePolicy.isMusicZone(tid)).isTrue()
        }
    }

    @Test
    fun `non music tids miss`() {
        // Given: 常见非音乐区 tid（动画 1、游戏 4、单机 17、影视 231 等）
        val nonMusicTids = listOf(1, 4, 17, 231)

        // When/Then: 均不命中
        nonMusicTids.forEach { tid ->
            assertThat(MusicZonePolicy.isMusicZone(tid)).isFalse()
        }
    }

    @Test
    fun `unknown tids miss`() {
        // Given: 未知 tid 与边界值
        val unknownTids = listOf(0, -1, 99999)

        // When/Then: 均不命中
        unknownTids.forEach { tid ->
            assertThat(MusicZonePolicy.isMusicZone(tid)).isFalse()
        }
    }

    @Test
    fun `music zone tids set matches documented source`() {
        // Given: MyTVB PlayerScreenLogic.MUSIC_ZONE_TIDS 的权威集合

        // When/Then: 集合内容与来源一致，防止误删子分区
        assertThat(MusicZonePolicy.MUSIC_ZONE_TIDS)
            .containsExactly(3, 28, 29, 30, 31, 59, 130, 193, 194)
    }
}
