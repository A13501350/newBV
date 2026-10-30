package dev.frost819.newbv.app.ui.action.player

import dev.frost819.newbv.danmaku.entity.DanmakuType
import dev.frost819.newbv.data.datastore.Audio
import dev.frost819.newbv.data.datastore.VideoCodec

/**
 * 弹幕设置动作。
 *
 * 使用 sealed interface 实现类型安全的状态更新，避免在 ViewModel 中写大量 when 分支。
 */
sealed interface DanmakuSettingAction {
    data class SetScale(
        val scale: Float,
    ) : DanmakuSettingAction

    data class SetOpacity(
        val opacity: Float,
    ) : DanmakuSettingAction

    data class SetArea(
        val area: Float,
    ) : DanmakuSettingAction

    data class SetSpeedFactor(
        val factor: Float,
    ) : DanmakuSettingAction

    data class SetMaskEnabled(
        val enabled: Boolean,
    ) : DanmakuSettingAction

    data class SetEnabledTypes(
        val types: List<DanmakuType>,
    ) : DanmakuSettingAction

    /**
     * 设置是否屏蔽彩色弹幕（非白色弹幕）。
     *
     * @property enabled true 表示屏蔽彩色弹幕
     */
    data class SetBlockColorful(
        val enabled: Boolean,
    ) : DanmakuSettingAction

    /**
     * 设置是否合并重复弹幕。
     *
     * 开启后相同内容的弹幕合并为一条并显示 ×N 计数。
     *
     * @property enabled true 表示合并重复弹幕
     */
    data class SetBlockRepeat(
        val enabled: Boolean,
    ) : DanmakuSettingAction

    /**
     * 设置是否屏蔽高级弹幕（mode 7/8/9）。
     *
     * @property enabled true 表示屏蔽高级弹幕
     */
    data class SetBlockSpecial(
        val enabled: Boolean,
    ) : DanmakuSettingAction

    /**
     * 设置是否启用账号云端屏蔽规则。
     *
     * 规则来自 B 站 `/x/dm/filter/user` 接口，包含关键词、正则与用户屏蔽。
     *
     * @property enabled true 表示启用云端屏蔽规则
     */
    data class SetBlockByUserFilter(
        val enabled: Boolean,
    ) : DanmakuSettingAction

    /**
     * 设置 AI 云屏蔽阈值。
     *
     * @property level 丢弃阈值：0 表示关闭，大于 0 时丢弃 weight 小于该值的弹幕
     */
    data class SetAiCloudLevel(
        val level: Int,
    ) : DanmakuSettingAction
}

/**
 * 字幕设置动作。
 */
sealed interface SubtitleSettingAction {
    data class SetFontSize(
        val sp: Int,
    ) : SubtitleSettingAction

    data class SetOpacity(
        val opacity: Float,
    ) : SubtitleSettingAction

    data class SetBottomPadding(
        val dp: Int,
    ) : SubtitleSettingAction
}

/**
 * 媒体格式设置动作。
 */
sealed interface MediaProfileSettingAction {
    data class SetQuality(
        val qualityId: Int,
    ) : MediaProfileSettingAction

    data class SetVideoCodec(
        val codec: VideoCodec,
    ) : MediaProfileSettingAction

    data class SetAudio(
        val audio: Audio,
    ) : MediaProfileSettingAction
}
