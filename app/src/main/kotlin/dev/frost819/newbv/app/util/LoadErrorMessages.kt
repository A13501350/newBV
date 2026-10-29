package dev.frost819.newbv.app.util

import dev.frost819.newbv.biliapi.http.entity.RiskControlException
import java.io.IOException

/** 异常链最多回溯层数（防异常链成环）。 */
private const val MAX_CAUSE_DEPTH = 8

/**
 * 「拿不到播放数据」类失败的用户提示。
 *
 * 覆盖播放前的数据加载链路：获取播放地址、直播流地址、房间信息等。
 * 与 `player` 模块的 `Throwable.toPlaybackErrorMessage()` 分工：
 * 那条负责播放器自身报出的错误（解码、音频、IO），这条负责前置的数据获取失败。
 */
fun Throwable.toPlaybackLoadErrorMessage(): String =
    when {
        hasCause<RiskControlException>() -> "触发风控限制，请稍后再试"
        hasCause<IOException>() -> "网络异常，请检查网络后重试"
        else -> "视频源加载失败，请稍后重试"
    }

/** 自身或原因链中是否存在指定类型的异常（还原被包装过的底层原因）。 */
private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is T) return true
        current = current.cause
        depth++
    }
    return false
}
