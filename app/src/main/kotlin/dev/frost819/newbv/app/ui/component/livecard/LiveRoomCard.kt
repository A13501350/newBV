package dev.frost819.newbv.app.ui.component.livecard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.frost819.newbv.core.focus.touchClickable

/**
 * 直播卡片。
 *
 * 展示直播封面、分区名、人气数、标题、主播名。
 * 点击卡片进入直播间。
 * 长按卡片弹出屏蔽操作面板（提供 [onBlockUp] 或 [onBlockArea] 回调时），
 * 支持「屏蔽该主播」「屏蔽该分区」。
 *
 * @param data 卡片数据。
 * @param onClick 点击回调。
 * @param onBlockUp 屏蔽该主播回调，null 时面板不显示该操作。
 * @param onBlockArea 屏蔽该分区回调，null 或卡片缺少父分区 ID 时不显示该操作。
 */
@Composable
fun LiveRoomCard(
    modifier: Modifier = Modifier,
    data: LiveRoomCardData,
    onClick: () -> Unit,
    onBlockUp: ((LiveRoomCardData) -> Unit)? = null,
    onBlockArea: ((LiveRoomCardData) -> Unit)? = null,
) {
    var showBlockDialog by remember { mutableStateOf(false) }
    // 主播操作随回调走；分区操作还需卡片携带有效父分区 ID（缺失时隐藏该操作）
    val showUpAction = onBlockUp != null
    val showAreaAction = onBlockArea != null && data.parentAreaId > 0
    val hasBlockActions = showUpAction || showAreaAction

    Column(modifier = modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            onLongClick = { if (hasBlockActions) showBlockDialog = true },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.78f)
                    .touchClickable(
                        onClick = onClick,
                        onLongClick = { if (hasBlockActions) showBlockDialog = true },
                    ),
            shape = CardDefaults.shape(MaterialTheme.shapes.large),
            border =
                CardDefaults.border(
                    focusedBorder =
                        Border(
                            border = androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.border),
                            shape = MaterialTheme.shapes.large,
                        ),
                ),
        ) {
            LiveCardCover(
                cover = data.cover,
                areaName = data.areaV2Name,
                onlineString = data.onlineString,
            )
        }

        LiveCardInfo(
            modifier = Modifier.fillMaxWidth(),
            title = data.title,
            uname = data.uname,
        )
    }

    if (showBlockDialog) {
        LiveCardBlockDialog(
            data = data,
            showUpAction = showUpAction,
            showAreaAction = showAreaAction,
            onDismiss = { showBlockDialog = false },
            onBlockUp = { onBlockUp?.invoke(data) },
            onBlockArea = { onBlockArea?.invoke(data) },
        )
    }
}

/**
 * 直播卡片屏蔽操作面板。
 *
 * 使用 [Dialog]（独立窗口）承载，D-Pad 焦点被限制在面板内，不会穿透到底层列表。
 * 面板仅含「屏蔽该主播」「屏蔽该分区」两个操作，打开时自动聚焦第一个可见操作，
 * 点击操作先关闭面板再执行回调。
 *
 * @param data 卡片数据。
 * @param showUpAction 是否显示「屏蔽该主播」操作。
 * @param showAreaAction 是否显示「屏蔽该分区」操作。
 * @param onDismiss 关闭面板回调。
 * @param onBlockUp 屏蔽主播回调。
 * @param onBlockArea 屏蔽分区回调。
 */
@Composable
private fun LiveCardBlockDialog(
    data: LiveRoomCardData,
    showUpAction: Boolean,
    showAreaAction: Boolean,
    onDismiss: () -> Unit,
    onBlockUp: () -> Unit,
    onBlockArea: () -> Unit,
) {
    val firstItemRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // 面板组合完成后请求焦点；节点未挂载时 requestFocus 会抛异常，静默忽略
        runCatching { firstItemRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.width(360.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "屏蔽选项",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (showUpAction) {
                        BlockActionItem(
                            modifier = Modifier.focusRequester(firstItemRequester),
                            text = "屏蔽主播：${data.uname}",
                            onClick = {
                                onDismiss()
                                onBlockUp()
                            },
                        )
                    }
                    if (showAreaAction) {
                        BlockActionItem(
                            modifier = if (showUpAction) Modifier else Modifier.focusRequester(firstItemRequester),
                            text = "屏蔽分区：${data.areaV2ParentName}",
                            onClick = {
                                onDismiss()
                                onBlockArea()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 屏蔽操作面板中的单个操作项。
 *
 * @param text 操作文案。
 * @param onClick 点击回调。
 */
@Composable
private fun BlockActionItem(
    modifier: Modifier = Modifier,
    text: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = modifier.touchClickable(onClick = onClick),
        headlineContent = { Text(text = text) },
        onClick = onClick,
    )
}

@Composable
private fun LiveCardCover(
    modifier: Modifier = Modifier,
    cover: String,
    areaName: String,
    onlineString: String,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .clip(MaterialTheme.shapes.large),
    ) {
        AsyncImage(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.large),
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.6f),
                                ),
                        ),
                    ),
        )

        if (areaName.isNotBlank()) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = areaName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                )
            }
        }

        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.error),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = onlineString,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LiveCardInfo(
    modifier: Modifier = Modifier,
    title: String,
    uname: String,
) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = uname,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LiveRoomCardPreview() {
    dev.frost819.newbv.core.theme.BVTheme {
        Box(modifier = Modifier.width(380.dp)) {
            LiveRoomCard(
                data =
                    LiveRoomCardData(
                        roomId = 2537621,
                        title = "你控灯，我来拍。互动拍拍灯(砸地鼠玩法)",
                        uname = "诺艾尔",
                        uid = 33306582,
                        cover = "",
                        face = "",
                        areaV2Name = "搞笑整蛊",
                        areaV2ParentName = "互动玩法",
                        parentAreaId = 9,
                        onlineString = "1.2万",
                        watchedString = "3.5万",
                    ),
                onClick = {},
            )
        }
    }
}
