package dev.frost819.newbv.app.util

/**
 * 音乐区视频判定策略。
 *
 * 供播放器「音乐区默认 1 倍速」等功能判断视频是否属于 B 站音乐区。
 * 详情接口（App gRPC `Arc.type_id` / Web `VideoInfo.tid`）返回的是视频所属
 * 子分区 id，通常不是主区 id（3），因此集合必须覆盖全部子分区。
 *
 * tid 集合来源：MyTVB 参考项目 `PlayerScreenLogic.MUSIC_ZONE_TIDS`
 * （app/src/main/java/com/mytvb/feature/player/PlayerScreenLogic.kt），
 * 覆盖音乐主区及其全部子分区，与该实现保持一致。
 *
 * 维护方式：B 站新增音乐子分区时在此追加一行即可；未收录的新子分区不会被判定为音乐区。
 */
object MusicZonePolicy {
    /** 音乐主区（3）及全部子分区 tid。 */
    val MUSIC_ZONE_TIDS: Set<Int> =
        setOf(
            3, // 音乐（主区）
            28, // 原创音乐
            29, // 三次元音乐
            30, // VOCALOID·UTAU
            31, // 翻唱
            59, // 演奏
            130, // 音乐综合
            193, // MV
            194, // 电音
        )

    /**
     * 判断给定分区 id 是否属于音乐区。
     *
     * @param tid 视频分区 id（详情接口返回的子分区 id 或主区 id）
     * @return 属于音乐区时返回 true
     */
    fun isMusicZone(tid: Int): Boolean = tid in MUSIC_ZONE_TIDS
}
