package dev.frost819.newbv.app.ui.screen.pgc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.frost819.newbv.app.ui.component.player.ifElse
import dev.frost819.newbv.app.ui.component.player.menu.component.MenuListItem
import dev.frost819.newbv.app.viewmodel.pgc.PgcIndexDimension
import dev.frost819.newbv.app.viewmodel.pgc.pgcIndexOptionLabel
import dev.frost819.newbv.biliapi.entity.pgc.index.PgcIndexParam

/**
 * 番剧/影视索引筛选面板。
 *
 * 复用播放器三级菜单（[dev.frost819.newbv.app.ui.component.player.menu.component.PlayerThreeLevelMenu] /
 * [dev.frost819.newbv.app.ui.component.player.menu.component.RadioMenuList]）同款三级焦点范式：
 * 左列为维度列表，右列为当前维度的可选值列表，焦点在「维度 ↔ 选项 ↔ 结果网格」间移动：
 * - 维度列表 RIGHT → 进入该维度的可选值列表；
 * - 可选值列表 LEFT → 返回维度列表，RIGHT → 移入结果网格；
 * - 点击选项即应用筛选（由调用方触发重新加载）。
 *
 * 选项高亮始终反映当前生效的筛选值（而非焦点位置），焦点滑过不触发加载。
 *
 * @param dimensions 展示的维度列表（已按当前分区收敛，均存在可选值）。
 * @param selectedDimension 当前查看的维度。
 * @param options 当前维度的可选值列表。
 * @param selectedOption 当前生效的选项。
 * @param onDimensionFocused 维度获得焦点/点击回调（切换右列可选值）。
 * @param onOptionSelect 点击应用某选项回调。
 * @param modifier 修饰符。
 * @param dimensionListFocusRequester 维度列表焦点请求器，供页面首次聚焦与可选值列表返回时使用。
 */
@Composable
fun PgcIndexFilterPanel(
    dimensions: List<PgcIndexDimension>,
    selectedDimension: PgcIndexDimension,
    options: List<PgcIndexParam>,
    selectedOption: PgcIndexParam?,
    onDimensionFocused: (PgcIndexDimension) -> Unit,
    onOptionSelect: (PgcIndexParam) -> Unit,
    modifier: Modifier = Modifier,
    dimensionListFocusRequester: FocusRequester = remember { FocusRequester() },
) {
    // 防空：分区切换瞬间维度/选项可能为空，直接不渲染
    if (dimensions.isEmpty() || options.isEmpty()) return

    val optionListFocusRequester = remember { FocusRequester() }
    // 焦点恢复目标：维度列恢复到首项，选项列恢复到当前生效选项
    val dimensionRestorer = remember { FocusRequester() }
    val optionRestorer = remember { FocusRequester() }
    val selectedOptionIndex = options.indexOf(selectedOption).coerceAtLeast(0)

    Row(modifier = modifier.fillMaxHeight()) {
        LazyColumn(
            modifier =
                Modifier
                    .focusRequester(dimensionListFocusRequester)
                    .width(216.dp)
                    .padding(horizontal = 8.dp)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp) {
                            return@onPreviewKeyEvent !listOf(Key.Enter, Key.DirectionCenter).contains(it.key)
                        }
                        when (it.key) {
                            Key.DirectionRight -> {
                                runCatching { optionListFocusRequester.requestFocus() }
                                true
                            }

                            else -> false
                        }
                    }.focusRestorer(dimensionRestorer),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(dimensions) { index, dimension ->
                MenuListItem(
                    modifier =
                        Modifier
                            .ifElse(index == 0, Modifier.focusRequester(dimensionRestorer)),
                    text = dimension.displayName,
                    selected = dimension == selectedDimension,
                    onFocus = { onDimensionFocused(dimension) },
                    onClick = {
                        onDimensionFocused(dimension)
                        runCatching { optionListFocusRequester.requestFocus() }
                    },
                )
            }
        }

        LazyColumn(
            modifier =
                Modifier
                    .width(216.dp)
                    .padding(horizontal = 8.dp)
                    .onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyUp) {
                            return@onPreviewKeyEvent !listOf(Key.Enter, Key.DirectionCenter).contains(it.key)
                        }
                        when (it.key) {
                            Key.DirectionLeft -> {
                                runCatching { dimensionListFocusRequester.requestFocus() }
                                true
                            }

                            else -> false
                        }
                    }.focusRestorer(optionRestorer),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(options) { index, option ->
                MenuListItem(
                    modifier =
                        Modifier
                            .ifElse(index == selectedOptionIndex, Modifier.focusRequester(optionRestorer)),
                    text = pgcIndexOptionLabel(option),
                    selected = option == selectedOption,
                    onClick = { onOptionSelect(option) },
                )
            }
        }
    }
}
