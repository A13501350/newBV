package dev.frost819.newbv.biliapi.websocket

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.http.entity.live.SuperChatDeleteEvent
import dev.frost819.newbv.biliapi.http.entity.live.SuperChatEvent
import org.junit.jupiter.api.Test

/**
 * [LiveDataWebSocket] 醒目留言（SC）消息解析的单元测试。
 *
 * 直接验证 `handleJsonMessage` 对 `SUPER_CHAT_MESSAGE` / `SUPER_CHAT_MESSAGE_DELETE`
 * 的解析与字段缺失容错，不建立真实 WebSocket 连接。
 */
class LiveDataWebSocketSuperChatTest {
    private fun parse(json: String) = LiveDataWebSocket.handleJsonMessage(json.toByteArray(Charsets.UTF_8))

    @Test
    fun `SUPER_CHAT_MESSAGE parses full payload`() {
        // Given：完整字段样例（background_color 为十进制 RGB 数字串 16746155 = 0xFF86AB）
        val json =
            """
            {
              "cmd": "SUPER_CHAT_MESSAGE",
              "data": {
                "id": 3378881,
                "uid": 12345,
                "price": 30,
                "message": "主播加油！",
                "duration": 160,
                "background_color": "16746155",
                "user_info": {
                  "uname": "测试用户",
                  "face": "https://i0.hdslb.com/bfs/face/abc.jpg"
                }
              }
            }
            """.trimIndent()

        // When
        val event = parse(json)

        // Then：24 位 RGB 数字串归一化为不透明 ARGB
        assertThat(event).isEqualTo(
            SuperChatEvent(
                id = 3378881L,
                price = 30,
                message = "主播加油！",
                username = "测试用户",
                face = "https://i0.hdslb.com/bfs/face/abc.jpg",
                backgroundColor = 0xFF000000L or 16746155L,
                durationMs = 160_000L,
            ),
        )
    }

    @Test
    fun `SUPER_CHAT_MESSAGE tolerates missing optional fields`() {
        // Given：缺 user_info、background_color、duration 的最小载荷
        val json =
            """
            {
              "cmd": "SUPER_CHAT_MESSAGE",
              "data": {
                "id": 1,
                "price": 50,
                "message": "hello"
              }
            }
            """.trimIndent()

        // When
        val event = parse(json)

        // Then：可缺省字段回退默认值（时长按官方最短展示 60 秒兜底），事件仍正常下发
        assertThat(event).isEqualTo(
            SuperChatEvent(
                id = 1L,
                price = 50,
                message = "hello",
                username = "",
                face = "",
                backgroundColor = null,
                durationMs = 60_000L,
            ),
        )
    }

    @Test
    fun `SUPER_CHAT_MESSAGE falls back to time field when duration missing`() {
        // Given：仅含 time（秒）字段
        val json =
            """
            {"cmd": "SUPER_CHAT_MESSAGE", "data": {"id": 2, "price": 100, "message": "m", "time": 330}}
            """.trimIndent()

        // When
        val event = parse(json)

        // Then
        assertThat((event as? SuperChatEvent)?.durationMs).isEqualTo(330_000L)
    }

    @Test
    fun `SUPER_CHAT_MESSAGE parses hex background color`() {
        // Given：background_color 为 #RRGGBB 十六进制串
        val json =
            """
            {"cmd": "SUPER_CHAT_MESSAGE", "data": {"id": 3, "price": 30, "message": "m", "background_color": "#EDF5FF"}}
            """.trimIndent()

        // When
        val event = parse(json)

        // Then：补全不透明 alpha 的 ARGB
        assertThat((event as? SuperChatEvent)?.backgroundColor).isEqualTo(0xFFEDF5FF)
    }

    @Test
    fun `SUPER_CHAT_MESSAGE keeps 32-bit numeric argb unchanged`() {
        // Given：background_color 为已含 alpha 的 32 位 ARGB 数字串
        val argb = 0xFF1E90FF
        val json =
            """{"cmd": "SUPER_CHAT_MESSAGE", "data": {"id": 4, "price": 30, "message": "m", "background_color": "$argb"}}"""

        // When
        val event = parse(json)

        // Then：按原值保留，不重复补 alpha
        assertThat((event as? SuperChatEvent)?.backgroundColor).isEqualTo(argb)
    }

    @Test
    fun `SUPER_CHAT_MESSAGE drops payload missing required fields`() {
        // Given：缺 id / price / message 的载荷
        val noId = """{"cmd": "SUPER_CHAT_MESSAGE", "data": {"price": 30, "message": "m"}}"""
        val noPrice = """{"cmd": "SUPER_CHAT_MESSAGE", "data": {"id": 1, "message": "m"}}"""
        val noMessage = """{"cmd": "SUPER_CHAT_MESSAGE", "data": {"id": 1, "price": 30}}"""
        val noData = """{"cmd": "SUPER_CHAT_MESSAGE"}"""

        // When / Then：关键字段缺失时丢弃事件
        assertThat(parse(noId)).isNull()
        assertThat(parse(noPrice)).isNull()
        assertThat(parse(noMessage)).isNull()
        assertThat(parse(noData)).isNull()
    }

    @Test
    fun `SUPER_CHAT_MESSAGE_DELETE parses ids`() {
        // Given
        val json = """{"cmd": "SUPER_CHAT_MESSAGE_DELETE", "data": {"ids": [11, 22, 33]}}"""

        // When
        val event = parse(json)

        // Then
        assertThat(event).isEqualTo(SuperChatDeleteEvent(ids = listOf(11L, 22L, 33L)))
    }

    @Test
    fun `SUPER_CHAT_MESSAGE_DELETE without ids returns null`() {
        // Given：ids 缺失或为空
        val noIds = """{"cmd": "SUPER_CHAT_MESSAGE_DELETE"}"""
        val emptyIds = """{"cmd": "SUPER_CHAT_MESSAGE_DELETE", "data": {"ids": []}}"""

        // When / Then
        assertThat(parse(noIds)).isNull()
        assertThat(parse(emptyIds)).isNull()
    }
}
