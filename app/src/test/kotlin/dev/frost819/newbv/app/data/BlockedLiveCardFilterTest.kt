package dev.frost819.newbv.app.data

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.app.ui.component.livecard.LiveRoomCardData
import org.junit.jupiter.api.Test

/**
 * [filterBlockedLiveCards] 的单元测试。
 *
 * 验证屏蔽过滤纯函数：命中主播 UID 或父分区 ID 的卡片被移除，其余保持原顺序。
 * 不依赖 Prefs（屏蔽集由调用方传入）。
 */
class BlockedLiveCardFilterTest {

    @Test
    fun `filter removes cards of blocked ups`() {
        // Given
        val cards = listOf(card(roomId = 1, uid = 100L), card(roomId = 2, uid = 200L))

        // When
        val filtered = filterBlockedLiveCards(cards, blockedUps = setOf(200L), blockedAreas = emptySet())

        // Then
        assertThat(filtered.map { it.roomId }).containsExactly(1L)
    }

    @Test
    fun `filter removes cards of blocked parent areas`() {
        // Given
        val cards =
            listOf(
                card(roomId = 1, uid = 100L, parentAreaId = 210),
                card(roomId = 2, uid = 101L, parentAreaId = 213),
            )

        // When
        val filtered = filterBlockedLiveCards(cards, blockedUps = emptySet(), blockedAreas = setOf(210))

        // Then
        assertThat(filtered.map { it.roomId }).containsExactly(2L)
    }

    @Test
    fun `filter keeps all cards when block sets are empty`() {
        // Given
        val cards = listOf(card(roomId = 1, uid = 100L), card(roomId = 2, uid = 200L))

        // When
        val filtered = filterBlockedLiveCards(cards, blockedUps = emptySet(), blockedAreas = emptySet())

        // Then
        assertThat(filtered).isEqualTo(cards)
    }

    @Test
    fun `filter keeps cards with unknown uid or parent area zero`() {
        // Given: uid/parentAreaId 为 0 表示数据缺失；屏蔽集来自 BlockedLiveStore，不含非正数 ID
        val cards = listOf(card(roomId = 1, uid = 0L, parentAreaId = 0))

        // When
        val filtered = filterBlockedLiveCards(cards, blockedUps = setOf(1L), blockedAreas = setOf(1))

        // Then: 数据缺失的卡片不会被误过滤
        assertThat(filtered).hasSize(1)
    }

    @Test
    fun `filter keeps original order of remaining cards`() {
        // Given
        val cards =
            listOf(
                card(roomId = 1, uid = 100L, parentAreaId = 210),
                card(roomId = 2, uid = 200L, parentAreaId = 210),
                card(roomId = 3, uid = 100L, parentAreaId = 213),
                card(roomId = 4, uid = 300L, parentAreaId = 217),
                card(roomId = 5, uid = 200L, parentAreaId = 217),
            )

        // When: 屏蔽主播 100 与分区 210
        val filtered = filterBlockedLiveCards(cards, blockedUps = setOf(100L), blockedAreas = setOf(210))

        // Then: 剩余卡片保持原相对顺序
        assertThat(filtered.map { it.roomId }).containsExactly(4L, 5L).inOrder()
    }

    /** 构造测试用直播卡片数据。 */
    private fun card(
        roomId: Long,
        uid: Long,
        parentAreaId: Int = 210,
    ): LiveRoomCardData =
        LiveRoomCardData(
            roomId = roomId,
            title = "房间$roomId",
            uname = "主播$uid",
            uid = uid,
            cover = "",
            face = "",
            areaV2Name = "子分区",
            areaV2ParentName = "父分区",
            parentAreaId = parentAreaId,
            onlineString = "1.2万",
            watchedString = "",
        )
}
