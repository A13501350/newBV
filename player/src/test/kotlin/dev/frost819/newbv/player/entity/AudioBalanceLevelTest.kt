package dev.frost819.newbv.player.entity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [AudioBalanceLevel] 的单元测试。
 *
 * 验证档位枚举名与档位的双向映射，及未知值的回退行为。
 */
class AudioBalanceLevelTest {
    @Test
    fun `fromName returns correct level`() {
        assertEquals(AudioBalanceLevel.OFF, AudioBalanceLevel.fromName("OFF"))
        assertEquals(AudioBalanceLevel.LOW, AudioBalanceLevel.fromName("LOW"))
        assertEquals(AudioBalanceLevel.MEDIUM, AudioBalanceLevel.fromName("MEDIUM"))
        assertEquals(AudioBalanceLevel.HIGH, AudioBalanceLevel.fromName("HIGH"))
    }

    @Test
    fun `fromName falls back to OFF for null or unknown name`() {
        assertEquals(AudioBalanceLevel.OFF, AudioBalanceLevel.fromName(null))
        assertEquals(AudioBalanceLevel.OFF, AudioBalanceLevel.fromName(""))
        assertEquals(AudioBalanceLevel.OFF, AudioBalanceLevel.fromName("off"))
        assertEquals(AudioBalanceLevel.OFF, AudioBalanceLevel.fromName("unknown"))
    }

    @Test
    fun `fromName roundtrip with persisted name`() {
        AudioBalanceLevel.entries.forEach { level -> assertEquals(level, AudioBalanceLevel.fromName(level.name)) }
    }

    @Test
    fun `display names are non-blank`() {
        AudioBalanceLevel.entries.forEach { level ->
            assert(level.displayName.isNotBlank()) { "${level.name} displayName is blank" }
        }
    }
}
