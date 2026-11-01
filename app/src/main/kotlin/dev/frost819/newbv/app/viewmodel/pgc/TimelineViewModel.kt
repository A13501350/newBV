package dev.frost819.newbv.app.viewmodel.pgc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.season.Timeline
import dev.frost819.newbv.biliapi.entity.season.TimelineEp
import dev.frost819.newbv.biliapi.entity.season.TimelineFilter
import dev.frost819.newbv.biliapi.repositories.SeasonRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/** 时间表加载超时时间（毫秒）。 */
private const val LOAD_TIMEOUT_MS = 10_000L

/** 日期条上「今天」两侧各展示的天数（共 7 天窗口）。 */
private const val DATE_BAR_HALF_WINDOW = 3

/**
 * 根据接口类型收敛时间表筛选项。
 *
 * Web 端点的放送时间表不支持「追番」过滤（仅 App 端提供），
 * 因此 Web 下只返回番剧/国创两个选项，App 下返回全部选项。
 *
 * @param preferApiType 当前接口类型。
 * @return 该接口类型下可用的时间表筛选项。
 */
fun availableFilters(preferApiType: ApiType): List<TimelineFilter> =
    when (preferApiType) {
        ApiType.Web -> TimelineFilter.webFilters
        ApiType.App -> TimelineFilter.appFilters
    }

/**
 * 解析时间表应选中的日期。
 *
 * 用户已选日期仍存在于新数据中时保留；否则优先选中「今天」；
 * 数据中没有今天时回退到第一天。
 *
 * @param timelines 按日分组的时间表。
 * @param current 当前已选中的日期字符串。
 * @return 应选中的日期字符串，[timelines] 为空时返回空串。
 */
fun resolveSelectedDateString(
    timelines: List<Timeline>,
    current: String,
): String {
    if (timelines.isEmpty()) return ""
    if (timelines.any { it.dateString == current }) return current
    return timelines.firstOrNull { it.isToday }?.dateString ?: timelines.first().dateString
}

/**
 * 截取日期条展示的日期窗口。
 *
 * 以「今天」为中心展示前后各 [halfWindow] 天；数据中没有今天时展示最前的
 * (2 * halfWindow + 1) 天。Web 端点会返回前后各 7 天的数据，全量展示过长。
 *
 * @param timelines 按日分组的时间表。
 * @param halfWindow 今天两侧展示的天数。
 * @return 日期条展示的天列表。
 */
fun dateBarDays(
    timelines: List<Timeline>,
    halfWindow: Int = DATE_BAR_HALF_WINDOW,
): List<Timeline> {
    val todayIndex = timelines.indexOfFirst { it.isToday }
    if (todayIndex < 0) return timelines.take(halfWindow * 2 + 1)
    val from = (todayIndex - halfWindow).coerceAtLeast(0)
    val to = (todayIndex + halfWindow).coerceAtMost(timelines.lastIndex)
    return timelines.subList(from, to + 1)
}

/**
 * 将当日分集按播出时间升序排序。
 *
 * 服务端返回顺序不保证按播出时间排列，展示前统一排序（稳定排序，同刻保持原序）。
 *
 * @param episodes 当日分集列表。
 * @return 按播出时间升序排序后的分集列表。
 */
fun sortEpisodes(episodes: List<TimelineEp>): List<TimelineEp> = episodes.sortedBy { it.publishDate }

/**
 * 新番放送时间表页 UI 状态。
 *
 * @property timelines 按日分组的时间表数据。
 * @property dateBar 日期条展示的天窗口（见 [dateBarDays]）。
 * @property filters 当前接口类型可用的筛选项（见 [availableFilters]）。
 * @property selectedFilter 当前筛选类型，首次加载前为 null。
 * @property selectedDateString 当前选中的日期字符串。
 * @property isLoading 是否正在加载。
 * @property isError 是否加载失败。
 * @property errorTip 错误提示文本。
 */
data class TimelineUiState(
    val timelines: List<Timeline> = emptyList(),
    val dateBar: List<Timeline> = emptyList(),
    val filters: List<TimelineFilter> = emptyList(),
    val selectedFilter: TimelineFilter? = null,
    val selectedDateString: String = "",
    val isLoading: Boolean = false,
    val isError: Boolean = false,
    val errorTip: String = "",
) {
    /** 当前选中日的分集列表（按播出时间升序），无选中日或当日无分集时为空。 */
    val selectedEpisodes: List<TimelineEp>
        get() =
            timelines
                .firstOrNull { it.dateString == selectedDateString }
                ?.let { sortEpisodes(it.episodes) }
                ?: emptyList()
}

/**
 * 新番放送时间表页 ViewModel。
 *
 * 加载按日分组的放送时间表，管理筛选类型切换（按接口类型收敛）与日期选择。
 *
 * @param seasonRepository 番剧仓库（提供时间表查询）。
 */
@HiltViewModel
class TimelineViewModel
    @Inject
    constructor(
        private val seasonRepository: SeasonRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("TimelineViewModel")

        private val _uiState = MutableStateFlow(TimelineUiState())
        val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

        init {
            loadTimeline()
        }

        /**
         * 加载放送时间表。
         *
         * 首次加载使用当前接口类型下的默认筛选（App 为全部，Web 为番剧）；
         * 再次调用（重试）沿用已选筛选。失败时标记 error，保留旧数据。
         */
        fun loadTimeline() {
            val filter = _uiState.value.selectedFilter ?: availableFilters(prefApiType()).first()
            _uiState.update {
                it.copy(isLoading = true, isError = false, errorTip = "")
            }

            viewModelScope.launch {
                runCatching {
                    withTimeout(LOAD_TIMEOUT_MS) {
                        seasonRepository.getTimeline(
                            filter = filter,
                            preferApiType = prefApiType(),
                        )
                    }
                }.onSuccess { timelines ->
                    _uiState.update {
                        it.copy(
                            timelines = timelines,
                            dateBar = dateBarDays(timelines),
                            filters = availableFilters(prefApiType()),
                            selectedFilter = filter,
                            selectedDateString = resolveSelectedDateString(timelines, it.selectedDateString),
                            isLoading = false,
                            isError = false,
                        )
                    }
                }.onFailure { error ->
                    // 超时以外的取消（如页面退出）必须继续传播，避免破坏协程取消机制
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    logger.warn(error) { "Failed to load timeline" }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isError = true,
                            errorTip = error.localizedMessage ?: "加载失败",
                        )
                    }
                }
            }
        }

        /**
         * 切换筛选类型并重新加载。
         *
         * 重复选择当前筛选时不重新请求；切换会清空旧数据并重置日期选择。
         *
         * @param filter 目标筛选类型。
         */
        fun selectFilter(filter: TimelineFilter) {
            if (filter == _uiState.value.selectedFilter) return
            _uiState.update {
                it.copy(
                    selectedFilter = filter,
                    timelines = emptyList(),
                    dateBar = emptyList(),
                    selectedDateString = "",
                )
            }
            loadTimeline()
        }

        /**
         * 选择展示的日期（本地切换，不重新请求）。
         *
         * @param dateString 目标日期字符串。
         */
        fun selectDate(dateString: String) {
            _uiState.update { it.copy(selectedDateString = dateString) }
        }

        /** 读取用户偏好中的接口类型，并映射为 bili-api 的 [ApiType]。 */
        private fun prefApiType(): ApiType =
            if (Prefs.apiType == dev.frost819.newbv.data.datastore.ApiType.App) ApiType.App else ApiType.Web
    }
