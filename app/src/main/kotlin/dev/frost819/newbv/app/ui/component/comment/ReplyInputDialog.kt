package dev.frost819.newbv.app.ui.component.comment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.search.SoftKeyboard
import dev.frost819.newbv.app.viewmodel.comment.MAX_REPLY_LENGTH
import dev.frost819.newbv.app.viewmodel.comment.REPLY_LOGIN_PROMPT
import dev.frost819.newbv.app.viewmodel.comment.ReplyInputState
import kotlinx.coroutines.delay

/**
 * 回复输入弹窗。
 *
 * 使用独立 Dialog 窗口承载，D-Pad 焦点被限制在弹窗内不外逃；输入通过 [SoftKeyboard]
 * （遥控器）或文本框（触屏）完成，焦点链与搜索输入页一致（首个键盘按键自动聚焦）。
 * 未登录引导态下隐藏输入区，仅展示登录提示与关闭入口。
 *
 * @param state 回复输入弹窗状态，来自 [dev.frost819.newbv.app.viewmodel.comment.CommentUiState.replyInput]
 * @param onDraftChange 输入内容变化回调
 * @param onSubmit 提交回调
 * @param onDismiss 关闭回调
 */
@Composable
internal fun ReplyInputDialog(
    state: ReplyInputState,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(onBack = onDismiss)

        val keyboardFocusRequester = remember { FocusRequester() }
        // 延迟等待 Dialog 内容完成组合后再请求焦点，避免 FocusRequester 未挂载静默失败
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { keyboardFocusRequester.requestFocus() }
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.width(460.dp),
                shape = RoundedCornerShape(16.dp),
                colors =
                    SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text =
                            state.target?.let { target -> "回复 @${target.quotedAuthor}" }
                                ?: "发表评论",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    state.target?.takeIf { it.quotedMessage.isNotEmpty() }?.let { target ->
                        Text(
                            text = target.quotedMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (state.notLoggedIn) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = state.error ?: REPLY_LOGIN_PROMPT,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(16.dp))
                        DialogActionButton(
                            text = "知道了",
                            icon = Icons.Outlined.Close,
                            onClick = onDismiss,
                            focusRequester = keyboardFocusRequester,
                        )
                    } else {
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            value = state.draft,
                            onValueChange = onDraftChange,
                            maxLines = 4,
                            shape = MaterialTheme.shapes.large,
                            colors =
                                OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.border,
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    cursorColor = MaterialTheme.colorScheme.primary,
                                ),
                            placeholder = {
                                Text(if (state.target != null) "回复 @${state.target?.quotedAuthor}" else "说点什么吧…")
                            },
                            isError = state.error != null,
                            supportingText = {
                                Text(text = "${state.draft.length}/$MAX_REPLY_LENGTH")
                            },
                        )
                        state.error?.let { error ->
                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        SoftKeyboard(
                            firstButtonFocusRequester = keyboardFocusRequester,
                            actionLabel = if (state.submitting) "发送中" else "发送",
                            onClick = { onDraftChange(state.draft + it) },
                            onClear = { onDraftChange("") },
                            onDelete = {
                                if (state.draft.isNotEmpty()) {
                                    onDraftChange(state.draft.dropLast(1))
                                }
                            },
                            onSearch = onSubmit,
                        )
                    }
                }
            }
        }
    }
}
