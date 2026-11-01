package dev.frost819.newbv.app.ui.state.player

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [PlayerUiState] 的单元测试。
 *
 * 验证番剧（PGC）判定与画面镜像状态的默认值/copy 语义：
 * PGC 判定仅取决于 [PlayerUiState.epid]，与播放侧 [PlayerUiState.fromSeason] 解耦；
 * 镜像为会话级状态，copy（如切集时的部分字段重置）自然保留。
 */
class PlayerUiStateTest {
    @Test
    fun `isPgc is false when epid is null`() {
        assertThat(PlayerUiState(epid = null).isPgc).isFalse()
    }

    @Test
    fun `isPgc is false when epid is zero`() {
        assertThat(PlayerUiState(epid = 0).isPgc).isFalse()
    }

    @Test
    fun `isPgc is true when epid is positive`() {
        assertThat(PlayerUiState(epid = 12345).isPgc).isTrue()
    }

    @Test
    fun `isPgc is independent of fromSeason`() {
        assertThat(PlayerUiState(epid = null, fromSeason = true).isPgc).isFalse()
        assertThat(PlayerUiState(epid = 1, fromSeason = false).isPgc).isTrue()
    }

    @Test
    fun `isMirrored defaults to false`() {
        assertThat(PlayerUiState().isMirrored).isFalse()
    }

    @Test
    fun `copy preserves isMirrored`() {
        // 切集（playNewVideo）走部分字段重置的 copy 路径：
        // 未显式重置的镜像状态应与宽高比一样自然保留
        val state = PlayerUiState(isMirrored = true)

        val copied = state.copy(title = "New Video", lastPlayed = 0)

        assertThat(copied.isMirrored).isTrue()
    }
}
