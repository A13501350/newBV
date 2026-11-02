package dev.frost819.newbv.app.ui.state.live

import dev.frost819.newbv.biliapi.repositories.LivePlayLine
import dev.frost819.newbv.biliapi.repositories.LiveQuality

/**
 * 直播播放状态。
 *
 * 与点播的 [dev.frost819.newbv.app.ui.state.player.PlayerState] 保持同构：
 * 错误文案随状态一起携带，离开错误态即自然消失，因此不需要额外的
 * `errorMessage` 字段，也不会出现「状态已恢复但旧文案残留」。
 */
sealed class LivePlayerState {
    /** 未开始。 */
    data object Idle : LivePlayerState()

    /** 加载中（首次拉流 / 切换画质或线路）。 */
    data object Loading : LivePlayerState()

    /** 正在播放。 */
    data object Playing : LivePlayerState()

    /** 已暂停。 */
    data object Paused : LivePlayerState()

    /** 播放结束。 */
    data object Ended : LivePlayerState()

    /**
     * 发生错误。
     *
     * @param message 面向用户的错误文案
     */
    data class Error(
        val message: String,
    ) : LivePlayerState()
}

/** 当前状态的错误文案；非错误态为 null。 */
val LivePlayerState.errorMessage: String?
    get() = (this as? LivePlayerState.Error)?.message

/**
 * 直播播放器 UI 状态。
 */
data class LivePlayerUiState(
    val title: String = "",
    val cover: String = "",
    val roomId: Long = 0,
    val realRoomId: Int = 0,
    val liveStatus: Int = 0,
    val areaName: String = "",
    val onlineCount: String = "0",
    val playerState: LivePlayerState = LivePlayerState.Idle,
    val isBuffering: Boolean = false,
    val availableQualities: List<LiveQuality> = emptyList(),
    /** 当前画质档位标识（[LiveQuality.key]，qn * 100 + hdrType * 10 + eotf）。 */
    val currentQuality: Int = 0,
    val availableLines: List<LivePlayLine> = emptyList(),
    val currentLine: Int = 0,
    /**
     * 醒目留言（SC）卡片队列。
     *
     * 列表头部为最新 SC（UI 纵向渲染时新卡片从顶部进入），
     * 到期或被服务端删除的卡片由 [dev.frost819.newbv.app.viewmodel.live.LivePlayerViewModel] 移除。
     */
    val superChats: List<SuperChatUiItem> = emptyList(),
)

/**
 * 醒目留言（SC）卡片 UI 数据。
 *
 * 就近放在本文件（AGENTS.md 6.5）：仅 [LivePlayerUiState] 与直播播放器 UI 使用。
 *
 * @property id SC 唯一标识（服务端下发，删除事件按此匹配）
 * @property username 发送者昵称
 * @property face 发送者头像 URL
 * @property message 留言内容
 * @property price 金额（人民币元），UI 据此映射底色梯度
 * @property backgroundColor 服务端下发的底色（不透明 ARGB 长整型），null 时按 [price] 取官方梯度兜底
 * @property expireAtMs 到期时间戳（毫秒，墙钟），仅供调试展示，移除由 ViewModel 计时驱动
 */
data class SuperChatUiItem(
    val id: Long,
    val username: String,
    val face: String,
    val message: String,
    val price: Int,
    val backgroundColor: Long?,
    val expireAtMs: Long,
)
