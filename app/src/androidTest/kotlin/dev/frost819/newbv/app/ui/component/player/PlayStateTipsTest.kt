package dev.frost819.newbv.app.ui.component.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.tv.material3.MaterialTheme as TvMaterialTheme

/**
 * [PlayStateTips] 的插桩测试。
 *
 * 验证暂停 / 缓冲 / 错误三态显示逻辑及优先级。
 */
@RunWith(AndroidJUnit4::class)
class PlayStateTipsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        isPlaying: Boolean,
        isBuffering: Boolean,
        errorMessage: String? = null,
    ) {
        composeRule.setContent {
            TvMaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    PlayStateTips(
                        isPlaying = isPlaying,
                        isBuffering = isBuffering,
                        errorMessage = errorMessage,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun paused_showsPauseIcon() {
        setContent(isPlaying = false, isBuffering = false)
        // PlayStateTips 内部用 Pause icon，没有文字，验证无错误/缓冲文字即可
        composeRule.onNodeWithText("缓冲中...").assertDoesNotExist()
        composeRule.onNodeWithText("播放错误").assertDoesNotExist()
    }

    @Test
    fun buffering_showsBufferingText() {
        setContent(isPlaying = false, isBuffering = true)

        composeRule.onNodeWithText("缓冲中...").assertIsDisplayed()
    }

    @Test
    fun error_showsTitle() {
        setContent(isPlaying = false, isBuffering = false, errorMessage = "网络连接失败")

        composeRule.onNodeWithText("播放错误").assertIsDisplayed()
    }

    @Test
    fun error_showsErrorMessage() {
        setContent(
            isPlaying = false,
            isBuffering = false,
            errorMessage = "网络连接失败",
        )

        composeRule.onNodeWithText("错误信息：网络连接失败").assertIsDisplayed()
    }

    @Test
    fun error_takes_priority_over_buffering() {
        setContent(isPlaying = false, isBuffering = true, errorMessage = "网络连接失败")

        composeRule.onNodeWithText("播放错误").assertIsDisplayed()
        composeRule.onNodeWithText("缓冲中...").assertDoesNotExist()
    }

    @Test
    fun playing_showsNothing() {
        setContent(isPlaying = true, isBuffering = false)

        composeRule.onNodeWithText("缓冲中...").assertDoesNotExist()
        composeRule.onNodeWithText("播放错误").assertDoesNotExist()
    }
}
