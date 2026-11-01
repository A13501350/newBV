package dev.frost819.newbv.app.ui.screen.user

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import dev.frost819.newbv.app.ui.component.TopNav
import dev.frost819.newbv.app.ui.component.TopNavItem
import dev.frost819.newbv.app.ui.component.TvLazyVerticalGrid
import dev.frost819.newbv.app.ui.component.focusSaverItem
import dev.frost819.newbv.app.ui.component.rememberFocusSaver
import dev.frost819.newbv.app.ui.navigation.FollowRoute
import dev.frost819.newbv.app.ui.navigation.UserSpaceRoute
import dev.frost819.newbv.app.viewmodel.user.FollowListType
import dev.frost819.newbv.app.viewmodel.user.FollowViewModel
import dev.frost819.newbv.biliapi.entity.user.FollowedUser
import dev.frost819.newbv.core.focus.touchClickable

/**
 * 关注/粉丝列表页。
 *
 * 顶部两段式 Tab 切换关注与粉丝列表（粉丝 Tab 仅本人主页可见），
 * 点击卡片跳转到对应用户空间页。
 */
fun NavGraphBuilder.followScreen(navController: NavController) {
    composable<FollowRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<FollowRoute>()
        val viewModel: FollowViewModel = hiltViewModel()
        FollowScreen(
            mid = route.mid,
            initialTab = route.tab,
            viewModel = viewModel,
            navController = navController,
        )
    }
}

@Composable
private fun FollowScreen(
    mid: Long,
    initialTab: String,
    viewModel: FollowViewModel,
    navController: NavController,
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()
    val focusSaver = rememberFocusSaver()

    LaunchedEffect(mid, initialTab) {
        viewModel.init(mid, FollowListType.fromTabParam(initialTab))
    }

    focusSaver.RestoreFocus()

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.isSelf) {
            FollowTabRow(
                selectedType = state.listType,
                onSelectedChanged = viewModel::switchListType,
                onRefresh = viewModel::refresh,
            )
        }
        TvLazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.loading && state.users.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.error && state.users.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "加载失败",
                            color = Color.Gray,
                            fontSize = 14.sp,
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = state.users,
                    key = { _, user -> user.mid },
                ) { index, user ->
                    FollowedUserCard(
                        modifier = Modifier.focusSaverItem(focusSaver, "follow_$index"),
                        user = user,
                        onClick = {
                            navController.navigate(UserSpaceRoute(mid = user.mid, name = user.name, face = user.avatar))
                        },
                    )
                }
            }
        }
    }
}

/**
 * 关注/粉丝两段式 Tab 行。
 *
 * 仅本人主页展示（粉丝接口对非本人返回 22110007），复用 TV 焦点导航的 [TopNav]。
 * 焦点移到另一个 Tab 即切换列表；点击当前选中 Tab 刷新。
 *
 * @param selectedType 当前选中的列表类型。
 * @param onSelectedChanged Tab 焦点切换回调。
 * @param onRefresh 点击当前选中 Tab 时的刷新回调。
 */
@Composable
private fun FollowTabRow(
    selectedType: FollowListType,
    onSelectedChanged: (FollowListType) -> Unit,
    onRefresh: () -> Unit,
) {
    val items = FollowListType.entries.map { FollowTabItem(it) }
    TopNav(
        items = items,
        selectedIndex = items.indexOfFirst { it.type == selectedType },
        isLargePadding = true,
        onSelectedChanged = { tab -> onSelectedChanged((tab as FollowTabItem).type) },
        onClick = { tab -> if ((tab as FollowTabItem).type == selectedType) onRefresh() },
    )
}

/**
 * 关注/粉丝 Tab 项。
 *
 * @property type 对应的列表类型。
 */
private data class FollowTabItem(
    val type: FollowListType,
) : TopNavItem {
    override val displayName: String =
        when (type) {
            FollowListType.Following -> "关注"
            FollowListType.Followers -> "粉丝"
        }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FollowedUserCard(
    modifier: Modifier = Modifier,
    user: FollowedUser,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.touchClickable(onClick = onClick),
        onClick = onClick,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = user.avatar,
                contentDescription = user.name,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )

            Spacer(modifier = Modifier.size(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = user.sign.ifEmpty { "这个人很神秘" },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
