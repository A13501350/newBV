package dev.frost819.newbv.app.util

import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.biliapi.util.AvBvConverter
import dev.frost819.newbv.core.log.Loggers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * 公益广告数据仓库（青少年模式休息期播放源）。
 *
 * 内置公开公益广告视频 BV 号列表，随机选一条交给播放器播放。
 * 与 MyTVB 不同：不预拉分 P 列表 —— cid 传 0，由播放器按"先加载视频详情
 * 再取正确 cid"的既定顺序解析（见 PlayerScreens 的加载流程）。
 */
object PsasRepository {
    private val logger = Loggers.get("PsasRepository")

    /**
     * 内置公益广告视频 BV 号列表。
     *
     * 首条为央视公益广告合集（MyTVB 已验证可用）；补充新条目前须人工验证可播。
     */
    val PSAS_BVIDS: List<String> = listOf("BV1oW41157Z4")

    private val _activePsasAid = MutableStateFlow(0L)

    /**
     * 正在播放的公益广告 AV 号（0 表示无会话）。
     *
     * 休息遮罩据此在公益广告播放期间隐藏自己，播放结束后恢复显示。
     */
    val activePsasAid: StateFlow<Long> = _activePsasAid.asStateFlow()

    /**
     * 随机选择一条公益广告。
     *
     * @param random 随机源，默认系统随机（测试可注入固定序列）。
     * @return 播放列表项（aid 由 BV 号换算，cid 传 0 由播放器解析详情）；列表为空返回 null。
     */
    fun getRandomVideo(random: Random = Random.Default): VideoListItem? {
        val bvid = PSAS_BVIDS.randomOrNull(random) ?: return null
        val aid = AvBvConverter.bv2av(bvid)
        logger.info { "psas selected bvid=$bvid aid=$aid" }
        return VideoListItem(
            aid = aid,
            cid = 0L,
            title = "公益广告",
        )
    }

    /**
     * 标记公益广告播放会话开始。
     *
     * @param aid 公益广告 AV 号。
     */
    fun beginPlayback(aid: Long) {
        _activePsasAid.value = aid
    }

    /**
     * 播放器退出（或播放器内切到其它视频）时调用：若传入的是当前公益广告的
     * AV 号则结束会话，休息遮罩恢复显示；传入其它 AV 号时不做任何事。
     *
     * @param aid 退出的视频 AV 号。
     */
    fun endPlaybackIfCurrent(aid: Long) {
        if (_activePsasAid.value == aid && aid != 0L) {
            _activePsasAid.value = 0L
        }
    }

    /**
     * 指定视频是否为进行中的公益广告会话。
     *
     * @param aid 视频 AV 号。
     * @return 是当前公益广告会话返回 true。
     */
    fun isPsasPlayback(aid: Long): Boolean = aid != 0L && _activePsasAid.value == aid
}
