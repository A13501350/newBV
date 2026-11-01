package dev.frost819.newbv.app.ui.screen.pgc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.LoadingTip
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.rememberFocusSaver
import dev.frost819.newbv.app.ui.navigation.PgcFeatureRoute
import dev.frost819.newbv.app.ui.navigation.TimelineRoute
import dev.frost819.newbv.app.viewmodel.pgc.TimelineUiState
import dev.frost819.newbv.app.viewmodel.pgc.TimelineViewModel
import dev.frost819.newbv.biliapi.entity.season.Timeline
import dev.frost819.newbv.biliapi.entity.season.TimelineEp
import dev.frost819.newbv.biliapi.entity.season.TimelineFilter
import dev.frost819.newbv.core.focus.focusInvertedColors
import dev.frost819.newbv.core.focus.touchClickable
import kotlinx.coroutines.delay

/** 星期标签表，[Timeline.dayOfWeek] 为 1（周一）~ 7（周日）。 */
private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 首次进入页面时等待 LazyRow 子项组合完成的延迟（毫秒）。 */
private const val FOCUS_DELAY_MS = 100L

/**
 * 筛选项的中文显示名称。
 *
 * @param filter 时间表筛选项。
 * @return 显示名称。
 */
private fun timelineFilterName(filter: TimelineFilter): String =
    when (filter) {
        TimelineFilter.All -> "全部"
        TimelineFilter.Anime -> "番剧"
        TimelineFilter.Following -> "追番"
        TimelineFilter.GuoChuang -> "国创"
    }

/**
 * 星期的中文标签。
 *
 * @param dayOfWeek 星期序号（1~7，1 为周一），越界时返回空串。
 * @return 显示标签。
 */
private fun weekdayLabel(dayOfWeek: Int): String = WEEKDAY_LABELS.getOrElse(dayOfWeek - 1) { "" }

/**
 * 新番放送时间表页路由注册。
 *
 * [TimelineViewModel] 无路由参数，筛选与日期均为页面内部状态。
 */
fun NavGraphBuilder.timelineScreen(navController: NavController) {
    composable<TimelineRoute> {
        val viewModel: TimelineViewModel = hiltViewModel()
        TimelineScreen(viewModel = viewModel, navController = navController)
    }
}

/**
 * 新番放送时间表页。
 *
 * 布局自上而下：标题 + 筛选条 + 横向日期条（今天高亮）+ 选中日的番剧纵向列表。
 * 点击番剧卡片经 [PgcFeatureRoute] 进入番剧详情（详情页内可再进播放器）。
 */
@Composable
private fun TimelineScreen(
    viewModel: TimelineViewModel,
    navController: NavController,
) {
    val state by viewModel.uiState.collectAsState()
    val focusSaver = rememberFocusSaver()

    focusSaver.RestoreFocus()

    when {
        state.isLoading && state.timelines.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                LoadingTip()
            }
        }

        state.isError && state.timelines.isEmpty() -> {
            TimelineErrorScreen(
                message = state.errorTip,
                onRetry = viewModel::loadTimeline,
            )
        }

        else -> {
            TimelineContent(
                state = state,
                viewModel = viewModel,
                navController = navController,
                focusSaver = focusSaver,
            )
        }
    }
}

/**
 * 时间表主体内容。
 *
 * 首次进入（无已保存焦点）时聚焦日期条上的选中日，便于遥控器直接上下浏览。
 */
@Composable
private fun TimelineContent(
    state: TimelineUiState,
    viewModel: TimelineViewModel,
    navController: NavController,
    focusSaver: FocusSaver,
) {
    LaunchedEffect(state.selectedDateString) {
        if (state.selectedDateString.isEmpty() || focusSaver.savedKeyValue().isNotEmpty()) {
            return@LaunchedEffect
        }
        // 等待 LazyRow 子项完成组合，避免 FocusRequester 未绑定节点导致请求静默失败
        delay(FOCUS_DELAY_MS)
        runCatching {
            focusSaver.focusRequesterFor("date_${state.selectedDateString}").requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 16.dp),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            text = "放送时间表",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) {
            items(
                items = state.filters,
                key = { it.name },
            ) { filter ->
                TimelineFilterChip(
                    filter = filter,
                    isSelected = filter == state.selectedFilter,
                    onClick = { viewModel.selectFilter(filter) },
                    modifier = Modifier.focusSaverItem(focusSaver, "filter_${filter.name}"),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 24.dp),
        ) {
            items(
                items = state.dateBar,
                key = { it.dateString },
            ) { day ->
                TimelineDateChip(
                    timeline = day,
                    isSelected = day.dateString == state.selectedDateString,
                    onClick = { viewModel.selectDate(day.dateString) },
                    modifier = Modifier.focusSaverItem(focusSaver, "date_${day.dateString}"),
                )
            }
        }

        if (state.selectedEpisodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "当天没有播出安排",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
            ) {
                items(
                    items = state.selectedEpisodes,
                    key = { "${it.seasonId}_${it.publishIndex}" },
                ) { episode ->
                    TimelineEpisodeCard(
                        episode = episode,
                        onClick = {
                            navController.navigate(
                                PgcFeatureRoute(seasonId = episode.seasonId.toLong()),
                            )
                        },
                        modifier = Modifier.focusSaverItem(
                            focusSaver,
                            "tl_ep_${state.selectedDateString}_${episode.seasonId}",
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 筛选类型 Chip。
 *
 * @param filter 筛选项。
 * @param isSelected 是否为当前筛选。
 * @param onClick 点击回调。
 * @param modifier Modifier。
 */
@Composable
private fun TimelineFilterChip(
    filter: TimelineFilter,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.touchClickable(onClick = onClick),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        colors =
            focusInvertedColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                contentColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            text = timelineFilterName(filter),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * 日期 Chip。
 *
 * 选中日用次级容器色高亮，今天额外叠加主题色描边。
 *
 * @param timeline 日期分组。
 * @param isSelected 是否为当前选中日。
 * @param onClick 点击回调。
 * @param modifier Modifier。
 */
@Composable
private fun TimelineDateChip(
    timeline: Timeline,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .clip(MaterialTheme.shapes.small)
                .then(
                    if (timeline.isToday) {
                        Modifier.border(
                            2.dp,
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.small,
                        )
                    } else {
                        Modifier
                    },
                ).touchClickable(onClick = onClick),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.small),
        colors =
            focusInvertedColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                contentColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = if (timeline.isToday) "今天" else weekdayLabel(timeline.dayOfWeek),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = timeline.dateString,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * 当日番剧卡片：封面 + 标题 + 更新集数 + 播出时间。
 *
 * @param episode 当日分集。
 * @param onClick 点击回调（进入番剧详情）。
 * @param modifier Modifier。
 */
@Composable
private fun TimelineEpisodeCard(
    episode: TimelineEp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.touchClickable(onClick = onClick),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.medium),
        colors =
            focusInvertedColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                modifier = Modifier
                    .width(160.dp)
                    .height(90.dp)
                    .clip(MaterialTheme.shapes.small),
                model = episode.cover,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episode.publishIndex,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = episode.publishTime,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 时间表加载失败页：错误信息 + 重试按钮。
 *
 * @param message 错误提示文本。
 * @param onRetry 重试回调。
 */
@Composable
private fun TimelineErrorScreen(
    message: String,
    onRetry: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = message.ifBlank { "加载失败" },
                color = MaterialTheme.colorScheme.onSurface,
            )
            Surface(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .touchClickable(onClick = onRetry),
                onClick = onRetry,
                shape = ClickableSurfaceDefaults.shape(shape = MaterialTheme.shapes.medium),
                border =
                    ClickableSurfaceDefaults.border(
                        focusedBorder =
                            Border(
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.border),
                                shape = MaterialTheme.shapes.medium,
                            ),
                    ),
                colors =
                    focusInvertedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    text = "重试",
                )
            }
        }
    }
}
