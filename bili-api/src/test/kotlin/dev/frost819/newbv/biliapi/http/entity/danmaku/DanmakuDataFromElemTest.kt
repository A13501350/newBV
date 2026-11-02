package dev.frost819.newbv.biliapi.http.entity.danmaku

import bilibili.community.service.dm.v1.danmakuElem
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [DanmakuData.fromDanmakuElem] 的单元测试。
 *
 * 验证 protobuf [bilibili.community.service.dm.v1.DanmakuElem] 到实体字段映射：
 * progress（ms）→ time（s）、ctime 不除 1000、weight → level、mode/size/color/pool/midHash/id、
 * attr bit2（值 4）→ highLike。
 * 对应文档：docs/bilibili-API-collect-master/docs/danmaku/danmaku_proto.md
 */
class DanmakuDataFromElemTest {
    @Test
    fun `maps all proto fields with progress ms to time s`() {
        val elem =
            danmakuElem {
                id = 123456789L
                progress = 61_500
                mode = 4
                fontsize = 25
                color = 0xFFFFFF
                midHash = "abcd1234"
                content = "测试弹幕"
                ctime = 1_700_000_000L
                weight = 8
                pool = 1
            }

        val data = DanmakuData.fromDanmakuElem(elem)

        assertThat(data.time).isEqualTo(61.5f)
        assertThat(data.type).isEqualTo(4)
        assertThat(data.size).isEqualTo(25)
        assertThat(data.color).isEqualTo(0xFFFFFF)
        assertThat(data.timestamp).isEqualTo(1_700_000_000)
        assertThat(data.pool).isEqualTo(1)
        assertThat(data.midHash).isEqualTo("abcd1234")
        assertThat(data.dmid).isEqualTo(123456789L)
        assertThat(data.level).isEqualTo(8)
        assertThat(data.text).isEqualTo("测试弹幕")
    }

    @Test
    fun `timestamp keeps ctime as seconds without dividing`() {
        // ctime 文档中已是秒级时间戳；若误除 1000 会得到约 1700000
        val elem =
            danmakuElem {
                ctime = 1_700_000_001L
                content = "x"
            }
        val data = DanmakuData.fromDanmakuElem(elem)
        assertThat(data.timestamp).isEqualTo(1_700_000_001)
    }

    @Test
    fun `weight maps to level for smart danmaku filtering`() {
        val elem =
            danmakuElem {
                weight = 3
                content = "x"
            }
        val data = DanmakuData.fromDanmakuElem(elem)
        assertThat(data.level).isEqualTo(3)
    }

    @Test
    fun `zero defaults map to zero entity fields`() {
        val elem = danmakuElem {}
        val data = DanmakuData.fromDanmakuElem(elem)
        assertThat(data.time).isEqualTo(0f)
        assertThat(data.type).isEqualTo(0)
        assertThat(data.size).isEqualTo(0)
        assertThat(data.color).isEqualTo(0)
        assertThat(data.timestamp).isEqualTo(0)
        assertThat(data.pool).isEqualTo(0)
        assertThat(data.midHash).isEmpty()
        assertThat(data.dmid).isEqualTo(0L)
        assertThat(data.level).isEqualTo(0)
        assertThat(data.text).isEmpty()
        assertThat(data.highLike).isFalse()
    }

    @Test
    fun `attr bit2 maps to highLike for samples 4 and 6`() {
        // Given: attr=4（仅 bit2 高赞）
        val highLikeOnly =
            danmakuElem {
                attr = 4
                content = "x"
            }

        // When
        val data4 = DanmakuData.fromDanmakuElem(highLikeOnly)

        // Then
        assertThat(data4.highLike).isTrue()

        // Given: attr=6（bit1 直播 + bit2 高赞，混合位仅取 bit2）
        val liveAndHighLike =
            danmakuElem {
                attr = 6
                content = "x"
            }

        // When
        val data6 = DanmakuData.fromDanmakuElem(liveAndHighLike)

        // Then
        assertThat(data6.highLike).isTrue()
    }

    @Test
    fun `attr without bit2 does not map to highLike`() {
        // Given: attr=0（无属性位）与 attr=2（仅 bit1 直播弹幕）
        val plain = danmakuElem { attr = 0 }
        val liveOnly =
            danmakuElem {
                attr = 2
                content = "x"
            }

        // When
        val data0 = DanmakuData.fromDanmakuElem(plain)
        val data2 = DanmakuData.fromDanmakuElem(liveOnly)

        // Then: bit2 未置位时不是高赞弹幕
        assertThat(data0.highLike).isFalse()
        assertThat(data2.highLike).isFalse()
    }
}
