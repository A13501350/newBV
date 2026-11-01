package dev.frost819.newbv.biliapi.http.entity.user

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.entity.user.FollowedUser
import dev.frost819.newbv.biliapi.http.entity.BiliResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * [UserFollowersData] 的单元测试。
 *
 * 验证粉丝列表响应的 JSON 解析（字段缺失/未知字段容忍）与 HTTP→Domain 映射。
 */
class UserFollowersDataTest {
    private val json =
        Json {
            coerceInputValues = true
            ignoreUnknownKeys = true
        }

    @Test
    fun `parses followers response with unknown fields`() {
        // Given: 真实粉丝接口响应形态（含 Follower 未建模的 attribute/official_verify/vip 等）
        val raw =
            """
            {
              "code": 0,
              "message": "0",
              "ttl": 1,
              "data": {
                "list": [
                  {
                    "mid": 123456,
                    "attribute": 2,
                    "mtime": 1600000000,
                    "special": 0,
                    "uname": "粉丝甲",
                    "face": "http://i0.hdslb.com/bfs/face/a.jpg",
                    "sign": "粉丝签名",
                    "official_verify": {"type": -1, "desc": ""},
                    "vip": {"vipType": 2, "vipDueDate": 0}
                  },
                  {
                    "mid": 654321,
                    "attribute": 0,
                    "mtime": 0,
                    "special": 0,
                    "uname": "粉丝乙",
                    "face": "http://i0.hdslb.com/bfs/face/b.jpg",
                    "sign": ""
                  }
                ],
                "re_version": 12345,
                "total": 2
              }
            }
            """.trimIndent()

        // When
        val response = json.decodeFromString<BiliResponse<UserFollowersData>>(raw)

        // Then
        assertThat(response.code).isEqualTo(0)
        val data = response.getResponseData()
        assertThat(data.total).isEqualTo(2)
        assertThat(data.reVersion).isEqualTo(12345)
        assertThat(data.list).hasSize(2)
        assertThat(data.list[0].mid).isEqualTo(123456L)
        assertThat(data.list[0].uname).isEqualTo("粉丝甲")
        assertThat(data.list[0].face).isEqualTo("http://i0.hdslb.com/bfs/face/a.jpg")
        assertThat(data.list[0].sign).isEqualTo("粉丝签名")
    }

    @Test
    fun `missing sign defaults to empty`() {
        // Given: 粉丝项缺失 sign 字段
        val raw =
            """
            {"code": 0, "message": "0", "data": {
              "list": [{"mid": 1, "uname": "粉丝", "face": "http://f.test"}],
              "total": 1
            }}
            """.trimIndent()

        // When
        val response = json.decodeFromString<BiliResponse<UserFollowersData>>(raw)

        // Then
        val follower = response.getResponseData().list.single()
        assertThat(follower.mid).isEqualTo(1L)
        assertThat(follower.uname).isEqualTo("粉丝")
        assertThat(follower.face).isEqualTo("http://f.test")
        assertThat(follower.sign).isEmpty()
    }

    @Test
    fun `empty data object defaults to empty list`() {
        // Given: 仅粉丝列表为空的 data
        val raw = """{"code": 0, "message": "0", "data": {"list": [], "re_version": 0, "total": 0}}"""

        // When
        val response = json.decodeFromString<BiliResponse<UserFollowersData>>(raw)

        // Then
        val data = response.getResponseData()
        assertThat(data.list).isEmpty()
        assertThat(data.total).isEqualTo(0)
    }

    @Test
    fun `FollowedUser fromHttpFollower maps mid uname face sign`() {
        // Given
        val follower =
            UserFollowersData.Follower(
                mid = 999L,
                uname = "粉丝用户",
                face = "http://face.test",
                sign = "粉丝签名内容",
            )

        // When
        val result = FollowedUser.fromHttpFollower(follower)

        // Then
        assertThat(result.mid).isEqualTo(999L)
        assertThat(result.name).isEqualTo("粉丝用户")
        assertThat(result.avatar).isEqualTo("http://face.test")
        assertThat(result.sign).isEqualTo("粉丝签名内容")
    }
}
