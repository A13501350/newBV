package dev.frost819.newbv.app.ui.screen.pgc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.frost819.newbv.app.ui.component.FocusSaver
import dev.frost819.newbv.app.ui.component.ListFooterTip
import dev.frost819.newbv.app.ui.component.TopNav
import dev.frost819.newbv.app.ui.component.TvLazyVerticalGrid
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.rememberFocusSaver
import dev.frost819.newbv.app.ui.component.videocard.SeasonCard
import dev.frost819.newbv.app.ui.component.videocard.SeasonCardData
import dev.frost819.newbv.app.ui.navigation.PgcFeatureRoute
import dev.frost819.newbv.app.viewmodel.pgc.PgcIndexDimension
import dev.frost819.newbv.app.viewmodel.pgc.PgcIndexViewModel
import dev.frost819.newbv.app.viewmodel.pgc.pgcIndexDimensionOptions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.material3.Scaffold as Material3Scaffold

/** 距离底部该条数时触发加载更多。 */
private const val LOAD_MORE_THRESHOLD = 10

/**
 * 番剧/影视索引筛选页路由注册。
 *
 * [PgcIndexViewModel] 通过 SavedStateHandle 读取 [PgcIndexRoute.pgcType]。
 */
fun NavGraphBuilder.pgcIndexScreen(navController: NavController) {
    composable<PgcIndexRoute> {
        PgcIndexScreen(navController = navController)
    }
}

/**
 * 番剧/影视索引筛选页。
 *
 * 布局：顶部为分区 TopNav，内容区左侧为筛选面板（维度 + 可选值两列），
 * 右侧为 3 列结果网格 + 无限滚动。菜单键刷新当前筛选结果。
 *
 * @param navController 导航控制器。
 */
@Composable
private fun PgcIndexScreen(
    navController: NavController,
) {
    val viewModel: PgcIndexViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    val focusSaver = rememberFocusSaver()
    focusSaver.RestoreFocus()

    val navFocusRequester = remember { FocusRequester() }
    val panelFocusRequester = remember { FocusRequester() }
    var focusOnContent by remember { mutableStateOf(false) }

    var selectedDimension by remember { mutableStateOf(PgcIndexDimension.Order) }
    // 分区切换后回到第一个维度，避免停留在已不可用的维度上
    LaunchedEffect(state.filter.pgcType) {
        selectedDimension = PgcIndexDimension.Order
    }

    // 首次进入（无保存焦点）时聚焦筛选面板的维度列表
    LaunchedEffect(Unit) {
        if (focusSaver.savedKeyValue().isEmpty()) {
            runCatching { panelFocusRequester.requestFocus() }
        }
    }

    val dimensions =
        PgcIndexDimension.entries.filter { dimension ->
            pgcIndexDimensionOptions(dimension, state.filter.pgcType).isNotEmpty()
        }
    // 分区切换重组瞬间选中维度可能已不可用，回退到排序维度
    val activeDimension = if (selectedDimension in dimensions) selectedDimension else PgcIndexDimension.Order
    val options = pgcIndexDimensionOptions(activeDimension, state.filter.pgcType)
    val selectedOption = state.filter.valueOf(activeDimension)

    Material3Scaffold(
        topBar = {
            TopNav(
                modifier = Modifier.focusRequester(navFocusRequester),
                items = PgcTabItem.entries.toList(),
                selectedIndex = PgcTabItem.entries.indexOfFirst { it.pgcType == state.filter.pgcType },
                isLargePadding = !focusOnContent,
                onSelectedChanged = { nav ->
                    val tab = nav as PgcTabItem
                    if (tab.pgcType != state.filter.pgcType) {
                        viewModel.switchType(tab.pgcType)
                    }
                },
                onClick = {
                    viewModel.refresh()
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .padding(innerPadding)
                    .onFocusChanged { focusOnContent = it.hasFocus }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Menu && event.type == KeyEventType.KeyUp) {
                            viewModel.refresh()
                            runCatching { navFocusRequester.requestFocus() }
                            return@onPreviewKeyEvent true
                        }
                        false
                    },
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                PgcIndexFilterPanel(
                    dimensions = dimensions,
                    selectedDimension = activeDimension,
                    options = options,
                    selectedOption = selectedOption,
                    onDimensionFocused = { dimension -> selectedDimension = dimension },
                    onOptionSelect = { option ->
                        viewModel.selectOption(activeDimension, options.indexOf(option))
                    },
                    modifier = Modifier.width(432.dp),
                    dimensionListFocusRequester = panelFocusRequester,
                )
                PgcIndexGrid(
                    modifier = Modifier.weight(1f),
                    viewModel = viewModel,
                    navController = navController,
                    focusSaver = focusSaver,
                )
            }
        }
    }
}

/**
 * 索引结果网格。
 *
 * 3 列番剧/影视卡片 + 无限滚动，距离底部 [LOAD_MORE_THRESHOLD] 条时触发加载更多。
 *
 * @param viewModel 索引筛选页 ViewModel。
 * @param navController 导航控制器。
 * @param focusSaver 焦点恢复器。
 * @param modifier 修饰符。
 */
@Composable
private fun PgcIndexGrid(
    viewModel: PgcIndexViewModel,
    navController: NavController,
    focusSaver: FocusSaver,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo
                .lastOrNull()
                ?.index
        }.distinctUntilChanged()
            .filter { index ->
                index != null && index >= state.items.size - LOAD_MORE_THRESHOLD
            }.collect {
                viewModel.loadMore()
            }
    }

    TvLazyVerticalGrid(
        modifier = modifier,
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.items.isEmpty() && !state.loading && !state.error) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "没有符合条件的作品",
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        itemsIndexed(
            items = state.items,
            key = { index, _ -> index },
        ) { index, item ->
            val cardData =
                remember(item) {
                    SeasonCardData(
                        seasonId = item.seasonId,
                        title = item.title,
                        subTitle = item.subTitle,
                        cover = item.cover,
                        rating = item.rating,
                    )
                }
            SeasonCard(
                data = cardData,
                onClick = {
                    navController.navigate(PgcFeatureRoute(seasonId = item.seasonId.toLong()))
                },
                onGoToDetailPage = {
                    navController.navigate(PgcFeatureRoute(seasonId = item.seasonId.toLong()))
                },
                modifier = Modifier.focusSaverItem(focusSaver, "pgc_index_$index"),
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            ListFooterTip(
                isLoading = state.loading,
                isError = state.error,
                hasMore = state.hasMore,
                itemsIsEmpty = state.items.isEmpty(),
            )
        }
    }
}
