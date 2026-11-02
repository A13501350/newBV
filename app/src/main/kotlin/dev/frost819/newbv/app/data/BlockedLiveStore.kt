package dev.frost819.newbv.app.data

import dev.frost819.newbv.app.ui.component.livecard.LiveRoomCardData
import dev.frost819.newbv.data.datastore.Prefs

/**
 * 直播屏蔽存储。
 *
 * 基于 [Prefs] 的两个逗号分隔字符串键（`blocked_live_ups` / `blocked_live_areas`）
 * 维护已屏蔽主播 UID 集合与已屏蔽父分区 ID 集合，提供增删查清空能力。
 *
 * 集合语义（自动去重）；非正数 ID 在写入时被忽略，非数字片段在解析时被忽略，
 * 因此屏蔽集永远不含非正数 ID，数据缺失（uid/parentAreaId 为 0）的卡片不会被误过滤。
 */
object BlockedLiveStore {
    /**
     * 读取已屏蔽主播 UID 集合。
     *
     * @return 去重后的 UID 集合，未屏蔽任何主播时为空集合。
     */
    fun blockedUps(): Set<Long> = parseLongs(Prefs.blockedLiveUps)

    /**
     * 读取已屏蔽父分区 ID 集合。
     *
     * @return 去重后的分区 ID 集合，未屏蔽任何分区时为空集合。
     */
    fun blockedAreas(): Set<Int> = parseInts(Prefs.blockedLiveAreas)

    /**
     * 判断主播是否已被屏蔽。
     *
     * @param mid 主播 UID。
     * @return 已屏蔽返回 true。
     */
    fun isUpBlocked(mid: Long): Boolean = mid in blockedUps()

    /**
     * 判断父分区是否已被屏蔽。
     *
     * @param parentAreaId 父分区 ID。
     * @return 已屏蔽返回 true。
     */
    fun isAreaBlocked(parentAreaId: Int): Boolean = parentAreaId in blockedAreas()

    /**
     * 屏蔽指定主播。
     *
     * @param mid 主播 UID，非正数忽略。
     * @return 屏蔽后的最新主播集合。
     */
    fun blockUp(mid: Long): Set<Long> {
        if (mid <= 0L) return blockedUps()
        return saveUps(blockedUps() + mid)
    }

    /**
     * 取消屏蔽指定主播。
     *
     * @param mid 主播 UID。
     * @return 取消屏蔽后的最新主播集合。
     */
    fun unblockUp(mid: Long): Set<Long> = saveUps(blockedUps() - mid)

    /**
     * 屏蔽指定父分区。
     *
     * @param parentAreaId 父分区 ID，非正数忽略。
     * @return 屏蔽后的最新分区集合。
     */
    fun blockArea(parentAreaId: Int): Set<Int> {
        if (parentAreaId <= 0) return blockedAreas()
        return saveAreas(blockedAreas() + parentAreaId)
    }

    /**
     * 取消屏蔽指定父分区。
     *
     * @param parentAreaId 父分区 ID。
     * @return 取消屏蔽后的最新分区集合。
     */
    fun unblockArea(parentAreaId: Int): Set<Int> = saveAreas(blockedAreas() - parentAreaId)

    /**
     * 清空已屏蔽主播。
     *
     * @return 清空后的主播集合（空集合）。
     */
    fun clearUps(): Set<Long> = saveUps(emptySet())

    /**
     * 清空已屏蔽父分区。
     *
     * @return 清空后的分区集合（空集合）。
     */
    fun clearAreas(): Set<Int> = saveAreas(emptySet())

    /** 清空全部直播屏蔽记录（主播 + 分区）。 */
    fun clearAll() {
        clearUps()
        clearAreas()
    }

    /** 写入主播屏蔽集并返回写入后的集合（内存缓存同步更新，磁盘异步持久化）。 */
    private fun saveUps(ups: Set<Long>): Set<Long> {
        Prefs.blockedLiveUps = ups.joinToString(",")
        return blockedUps()
    }

    /** 写入分区屏蔽集并返回写入后的集合（内存缓存同步更新，磁盘异步持久化）。 */
    private fun saveAreas(areas: Set<Int>): Set<Int> {
        Prefs.blockedLiveAreas = areas.joinToString(",")
        return blockedAreas()
    }

    /** 解析逗号分隔的 Long 集合，非数字片段忽略（空串返回空集合）。 */
    private fun parseLongs(raw: String): Set<Long> = raw.split(",").mapNotNull { it.toLongOrNull() }.toSet()

    /** 解析逗号分隔的 Int 集合，非数字片段忽略（空串返回空集合）。 */
    private fun parseInts(raw: String): Set<Int> = raw.split(",").mapNotNull { it.toIntOrNull() }.toSet()
}

/**
 * 按屏蔽集合过滤直播卡片列表。
 *
 * 移除主播 UID 命中 [blockedUps] 或父分区 ID 命中 [blockedAreas] 的卡片。
 * 两个屏蔽集应来自 [BlockedLiveStore]（不含非正数 ID），因此 uid/parentAreaId
 * 为 0 的缺失数据不会被误过滤。
 *
 * @param items 待过滤的直播卡片列表。
 * @param blockedUps 已屏蔽主播 UID 集合。
 * @param blockedAreas 已屏蔽父分区 ID 集合。
 * @return 过滤后的卡片列表（保持原顺序）。
 */
fun filterBlockedLiveCards(
    items: List<LiveRoomCardData>,
    blockedUps: Set<Long>,
    blockedAreas: Set<Int>,
): List<LiveRoomCardData> = items.filter { card -> card.uid !in blockedUps && card.parentAreaId !in blockedAreas }
