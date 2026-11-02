package dev.frost819.newbv.biliapi.http.entity.live

interface LiveEvent

data class DanmakuEvent(
    val content: String,
    val mid: Long,
    val username: String,
    val medalName: String? = null,
    val medalLevel: Int? = null,
) : LiveEvent

/** 进入直播间/关注主播事件。 */
data class InteractWordEvent(
    val uid: Long,
    val uname: String,
    val interactType: InteractType,
) : LiveEvent

enum class InteractType(
    val code: Int,
) {
    Enter(1),
    Follow(2),
    Share(3),
    ;

    companion object {
        fun fromCode(code: Int): InteractType? = entries.find { it.code == code }
    }
}

/** 在线人数变化事件。 */
data class OnlineRankCountEvent(
    val count: Int,
) : LiveEvent

/** 看过人数变化事件。 */
data class WatchedChangeEvent(
    val num: Int,
    val textLarge: String,
    val textSmall: String,
) : LiveEvent

/**
 * 醒目留言（SC，Super Chat）事件。
 *
 * 对应直播 WebSocket 消息 `SUPER_CHAT_MESSAGE`，`data` 载荷核心字段为
 * id/message/price/user_info.uname/user_info.face/background_color/duration。
 *
 * @property id SC 唯一标识，[SuperChatDeleteEvent] 按此匹配移除
 * @property price 金额（人民币元），UI 据此映射底色梯度
 * @property message 留言内容
 * @property username 发送者昵称
 * @property face 发送者头像 URL
 * @property backgroundColor 服务端下发的底色，已归一化为不透明 ARGB 长整型，字段缺失或无法解析时为 null
 * @property durationMs 展示时长（毫秒），到期后 UI 移除卡片
 */
data class SuperChatEvent(
    val id: Long,
    val price: Int,
    val message: String,
    val username: String,
    val face: String,
    val backgroundColor: Long?,
    val durationMs: Long,
) : LiveEvent

/**
 * 醒目留言删除事件。
 *
 * 对应直播 WebSocket 消息 `SUPER_CHAT_MESSAGE_DELETE`。
 *
 * @property ids 被删除的 SC id 列表
 */
data class SuperChatDeleteEvent(
    val ids: List<Long>,
) : LiveEvent
