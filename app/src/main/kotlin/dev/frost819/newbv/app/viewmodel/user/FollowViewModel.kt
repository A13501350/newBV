package dev.frost819.newbv.app.viewmodel.user

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.user.FollowedUser
import dev.frost819.newbv.biliapi.repositories.UserRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.repository.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

private const val LOAD_TIMEOUT_MS = 30_000L

/**
 * 关注列表页的列表类型。
 *
 * @property tabParam 对应 [dev.frost819.newbv.app.ui.navigation.FollowRoute] 的 tab 参数值。
 */
enum class FollowListType(
    val tabParam: String,
) {
    /** 关注列表（mid 关注的用户）。 */
    Following("following"),

    /** 粉丝列表（关注 mid 的用户），仅本人主页可见。 */
    Followers("followers"),
    ;

    companion object {
        /**
         * 从路由 tab 参数解析列表类型。
         *
         * @param tab 路由参数值（"following"/"followers"），未知值回退 [Following]。
         * @return 对应的列表类型。
         */
        fun fromTabParam(tab: String): FollowListType =
            entries.firstOrNull { it.tabParam == tab } ?: Following
    }
}

/**
 * 关注列表页 UiState。
 *
 * @property mid 当前查看的用户 mid。
 * @property listType 当前列表类型（关注/粉丝）。
 * @property isSelf 是否查看本人主页，决定粉丝 Tab 是否可见。
 * @property users 用户列表。
 * @property loading 是否加载中。
 * @property error 是否加载失败。
 */
data class FollowUiState(
    val mid: Long = 0L,
    val listType: FollowListType = FollowListType.Following,
    val isSelf: Boolean = false,
    val users: List<FollowedUser> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
)

/**
 * 关注/粉丝列表 ViewModel。
 *
 * 支持关注与粉丝双列表切换，一次性加载全量列表
 * （`getFollowedUsers`/`getFollowers` 内部已分页处理）。
 * 粉丝列表仅当查看本人主页（mid 等于当前登录 uid）时可用。
 *
 * @param userRepository 用户数据仓库。
 * @param accountRepository 账户仓库，用于判断是否查看本人主页。
 */
@HiltViewModel
class FollowViewModel
    @Inject
    constructor(
        private val userRepository: UserRepository,
        private val accountRepository: AccountRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("FollowViewModel")

        private fun prefApiType(): ApiType =
            if (Prefs.apiType == dev.frost819.newbv.data.datastore.ApiType.App) ApiType.App else ApiType.Web

        private val _uiState = MutableStateFlow(FollowUiState())
        val uiState: StateFlow<FollowUiState> = _uiState.asStateFlow()

        /**
         * 初始化页面。
         *
         * 同一 mid + 同一列表类型且已加载数据时幂等；路由携带的粉丝 tab 仅在
         * 本人主页生效，非本人一律回退关注列表（粉丝接口对他人返回 22110007）。
         *
         * @param mid 用户 mid。
         * @param listType 初始列表类型，默认关注列表。
         */
        fun init(
            mid: Long,
            listType: FollowListType = FollowListType.Following,
        ) {
            val isSelf = mid != 0L && accountRepository.currentUid() == mid
            val effectiveType = if (isSelf) listType else FollowListType.Following
            if (_uiState.value.mid == mid &&
                _uiState.value.listType == effectiveType &&
                _uiState.value.users.isNotEmpty()
            ) {
                return
            }
            _uiState.update {
                it.copy(
                    mid = mid,
                    listType = effectiveType,
                    isSelf = isSelf,
                    loading = true,
                    error = false,
                    users = emptyList(),
                )
            }
            load(mid, effectiveType)
        }

        /**
         * 切换列表类型（关注/粉丝）。
         *
         * 清空当前列表并重新加载；切换到相同类型为幂等操作。
         *
         * @param listType 目标列表类型。
         */
        fun switchListType(listType: FollowListType) {
            if (_uiState.value.listType == listType) return
            val mid = _uiState.value.mid
            _uiState.update {
                it.copy(listType = listType, loading = true, error = false, users = emptyList())
            }
            load(mid, listType)
        }

        /**
         * 刷新当前列表（清空重载）。
         */
        fun refresh() {
            val current = _uiState.value
            _uiState.update { it.copy(loading = true, error = false, users = emptyList()) }
            load(current.mid, current.listType)
        }

        private fun load(
            mid: Long,
            listType: FollowListType,
        ) {
            viewModelScope.launch {
                runCatching {
                    withTimeout(LOAD_TIMEOUT_MS) {
                        when (listType) {
                            FollowListType.Following ->
                                userRepository.getFollowedUsers(mid, preferApiType = prefApiType())

                            FollowListType.Followers ->
                                userRepository.getFollowers(mid, preferApiType = prefApiType())
                        }
                    }
                }.onSuccess { users ->
                    _uiState.update {
                        it.copy(
                            users = users,
                            loading = false,
                            error = false,
                        )
                    }
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    logger.error(error) { "Failed to load $listType users" }
                    _uiState.update { it.copy(loading = false, error = true) }
                }
            }
        }
    }
