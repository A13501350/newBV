package dev.frost819.newbv.app.viewmodel.pgc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.biliapi.entity.pgc.PgcItem
import dev.frost819.newbv.biliapi.entity.pgc.PgcType
import dev.frost819.newbv.biliapi.entity.pgc.index.Area
import dev.frost819.newbv.biliapi.entity.pgc.index.Copyright
import dev.frost819.newbv.biliapi.entity.pgc.index.IndexOrder
import dev.frost819.newbv.biliapi.entity.pgc.index.IndexOrderType
import dev.frost819.newbv.biliapi.entity.pgc.index.IsFinish
import dev.frost819.newbv.biliapi.entity.pgc.index.PgcIndexData
import dev.frost819.newbv.biliapi.entity.pgc.index.PgcIndexParam
import dev.frost819.newbv.biliapi.entity.pgc.index.Producer
import dev.frost819.newbv.biliapi.entity.pgc.index.ReleaseDate
import dev.frost819.newbv.biliapi.entity.pgc.index.SeasonMonth
import dev.frost819.newbv.biliapi.entity.pgc.index.SeasonStatus
import dev.frost819.newbv.biliapi.entity.pgc.index.SeasonVersion
import dev.frost819.newbv.biliapi.entity.pgc.index.SpokenLanguage
import dev.frost819.newbv.biliapi.entity.pgc.index.Style
import dev.frost819.newbv.biliapi.entity.pgc.index.Year
import dev.frost819.newbv.biliapi.repositories.PgcRepository
import dev.frost819.newbv.core.log.Loggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

private const val LOAD_TIMEOUT_MS = 10_000L

/** 每页请求数量，与 B 站索引接口默认值一致。 */
private const val PAGE_SIZE = 20

/**
 * 番剧/影视索引筛选维度。
 *
 * 每个维度对应一个可选值列表（[pgcIndexDimensionOptions]），
 * 仅当维度在当前分区下存在可选值时才展示。
 *
 * @property displayName 维度的中文展示名。
 */
enum class PgcIndexDimension(
    val displayName: String,
) {
    Order("排序"),
    OrderType("排序方式"),
    SeasonVersion("类型"),
    SpokenLanguage("配音"),
    Area("地区"),
    IsFinish("状态"),
    Copyright("版权"),
    SeasonStatus("付费"),
    SeasonMonth("季度"),
    Producer("出品方"),
    Year("年份"),
    ReleaseDate("上映时间"),
    Style("风格"),
}

/**
 * 读取筛选维度在指定分区下的可选值列表。
 *
 * 各维度的可选值随分区收敛（如「配音」仅番剧有），无可选值的维度不在 UI 中展示。
 *
 * @param dimension 筛选维度。
 * @param pgcType 当前分区。
 * @return 可选值列表（首项为该分区下的默认值），恒不为空列表时才应展示该维度。
 */
fun pgcIndexDimensionOptions(
    dimension: PgcIndexDimension,
    pgcType: PgcType,
): List<PgcIndexParam> =
    when (dimension) {
        PgcIndexDimension.Order -> IndexOrder.getList(pgcType)
        PgcIndexDimension.OrderType -> IndexOrderType.entries
        PgcIndexDimension.SeasonVersion -> SeasonVersion.getList(pgcType)
        PgcIndexDimension.SpokenLanguage -> SpokenLanguage.getList(pgcType)
        PgcIndexDimension.Area -> Area.getList(pgcType)
        PgcIndexDimension.IsFinish -> IsFinish.getList(pgcType)
        PgcIndexDimension.Copyright -> Copyright.getList(pgcType)
        PgcIndexDimension.SeasonStatus -> SeasonStatus.getList(pgcType)
        PgcIndexDimension.SeasonMonth -> SeasonMonth.getList(pgcType)
        PgcIndexDimension.Producer -> Producer.getList(pgcType)
        PgcIndexDimension.Year -> Year.getList(pgcType)
        PgcIndexDimension.ReleaseDate -> ReleaseDate.getList(pgcType)
        PgcIndexDimension.Style -> Style.getList(pgcType)
    }

/**
 * 获取索引筛选选项的中文展示名。
 *
 * @param param 筛选选项（[PgcIndexParam] 的枚举实现）。
 * @return 选项的中文展示名。
 */
fun pgcIndexOptionLabel(param: PgcIndexParam): String =
    when (param) {
        is IndexOrder ->
            when (param) {
                IndexOrder.UpdateTime -> "更新时间"
                IndexOrder.DanmakuCount -> "弹幕数量"
                IndexOrder.PlayCount -> "播放数量"
                IndexOrder.FollowCount -> "追番人数"
                IndexOrder.Score -> "最高评分"
                IndexOrder.StartTime -> "开播时间"
                IndexOrder.PublishTime -> "上映时间"
            }

        is IndexOrderType ->
            when (param) {
                IndexOrderType.Desc -> "降序"
                IndexOrderType.Asc -> "升序"
            }

        is SeasonVersion ->
            when (param) {
                SeasonVersion.All -> "全部"
                SeasonVersion.FeatureFilm -> "正片"
                SeasonVersion.Movies -> "电影"
                SeasonVersion.Other -> "其他"
            }

        is SpokenLanguage ->
            when (param) {
                SpokenLanguage.All -> "全部"
                SpokenLanguage.OriginalSoundtrack -> "原声"
                SpokenLanguage.ChineseDubbing -> "中文配音"
            }

        is Area ->
            when (param) {
                Area.All -> "全部"
                Area.MainlandChina -> "中国大陆"
                Area.Japan -> "日本"
                Area.America -> "美国"
                Area.Britain -> "英国"
                Area.Other -> "其他"
                Area.ChinaHongKongTaiwan -> "中国港台"
                Area.Korea -> "韩国"
                Area.France -> "法国"
                Area.Thailand -> "泰国"
                Area.Spain -> "西班牙"
                Area.Germany -> "德国"
                Area.Italy -> "意大利"
            }

        is IsFinish ->
            when (param) {
                IsFinish.All -> "全部"
                IsFinish.Finished -> "完结"
                IsFinish.Serialization -> "连载"
            }

        is Copyright ->
            when (param) {
                Copyright.All -> "全部"
                Copyright.Exclusive -> "独家"
                Copyright.Other -> "其他"
            }

        is SeasonStatus ->
            when (param) {
                SeasonStatus.All -> "全部"
                SeasonStatus.Free -> "免费"
                SeasonStatus.Paid -> "付费"
                SeasonStatus.Prime -> "大会员"
            }

        is SeasonMonth ->
            when (param) {
                SeasonMonth.All -> "全部"
                SeasonMonth.January -> "1月"
                SeasonMonth.April -> "4月"
                SeasonMonth.July -> "7月"
                SeasonMonth.October -> "10月"
            }

        is Producer ->
            when (param) {
                Producer.All -> "全部"
                Producer.BBC -> "BBC"
                Producer.NHK -> "NHK"
                Producer.SKY -> "SKY"
                Producer.CCTV -> "央视"
                Producer.ITV -> "ITV"
                Producer.HistoryChannel -> "历史频道"
                Producer.DiscoveryChannel -> "探索频道"
                Producer.SatelliteTV -> "卫视"
                Producer.SelfMade -> "自制"
                Producer.ZDF -> "ZDF"
                Producer.Cooperation -> "合作机构"
                Producer.DomesticOther -> "国内其他"
                Producer.ForeignOther -> "国外其他"
                Producer.NationalGeographic -> "国家地理"
                Producer.Sony -> "索尼"
                Producer.Universal -> "环球"
                Producer.Paramount -> "派拉蒙"
                Producer.Warner -> "华纳"
                Producer.Disney -> "迪士尼"
                Producer.HBO -> "HBO"
            }

        is Year ->
            when (param) {
                Year.All -> "全部"
                Year.Year2026 -> "2026"
                Year.Year2025 -> "2025"
                Year.Year2024 -> "2024"
                Year.Year2023 -> "2023"
                Year.Year2022 -> "2022"
                Year.Year2021 -> "2021"
                Year.Year2020 -> "2020"
                Year.Year2019 -> "2019"
                Year.Year2018 -> "2018"
                Year.Year2017 -> "2017"
                Year.Year2016 -> "2016"
                Year.Year2015 -> "2015"
                Year.Year2014_2010 -> "2014-2010"
                Year.Year2009_2005 -> "2009-2005"
                Year.Year2004_2000 -> "2004-2000"
                Year.Year199x -> "90年代"
                Year.Year198x -> "80年代"
                Year.Earlier -> "更早"
            }

        is ReleaseDate ->
            when (param) {
                ReleaseDate.All -> "全部"
                ReleaseDate.Year2026 -> "2026"
                ReleaseDate.Year2025 -> "2025"
                ReleaseDate.Year2024 -> "2024"
                ReleaseDate.Year2023 -> "2023"
                ReleaseDate.Year2022 -> "2022"
                ReleaseDate.Year2021 -> "2021"
                ReleaseDate.Year2020 -> "2020"
                ReleaseDate.Year2019 -> "2019"
                ReleaseDate.Year2018 -> "2018"
                ReleaseDate.Year2017 -> "2017"
                ReleaseDate.Year2016 -> "2016"
                ReleaseDate.Year2015_2010 -> "2015-2010"
                ReleaseDate.Year2009_2005 -> "2009-2005"
                ReleaseDate.Year2004_2000 -> "2004-2000"
                ReleaseDate.Year199x -> "90年代"
                ReleaseDate.Year198x -> "80年代"
                ReleaseDate.Earlier -> "更早"
            }

        is Style ->
            when (param) {
                Style.All -> "全部"
                Style.Movie -> "电影"
                Style.Original -> "原创"
                Style.Comic -> "漫画改"
                Style.Novel -> "小说改"
                Style.Game -> "游戏改"
                Style.Animation -> "动态漫"
                Style.Puppetry -> "布袋戏"
                Style.HotBlood -> "热血"
                Style.TimeTravel -> "穿越"
                Style.Fantasy -> "奇幻"
                Style.XuanHuan -> "玄幻"
                Style.Fight -> "战斗"
                Style.Funny -> "搞笑"
                Style.Daily -> "日常"
                Style.ScienceFiction -> "科幻"
                Style.Moe -> "萌系"
                Style.Healing -> "治愈"
                Style.School -> "校园"
                Style.Children -> "少儿"
                Style.InstantNoodles -> "泡面"
                Style.InLove -> "恋爱"
                Style.Girl -> "少女"
                Style.Magic -> "魔法"
                Style.Adventure -> "冒险"
                Style.History -> "历史"
                Style.Fiction -> "架空"
                Style.Mecha -> "机战"
                Style.GodDemon -> "神魔"
                Style.VoiceControl -> "声控"
                Style.Sports -> "运动"
                Style.Inspirational -> "励志"
                Style.Music -> "音乐"
                Style.Reasoning -> "推理"
                Style.Club -> "社团"
                Style.WisdomFight -> "智斗"
                Style.Tearjerker -> "催泪"
                Style.Food -> "美食"
                Style.Idol -> "偶像"
                Style.Maiden -> "乙女"
                Style.Workplace -> "职场"
                Style.AncientStyle -> "古风"
                Style.Plot -> "剧情"
                Style.Comedy -> "喜剧"
                Style.Love -> "爱情"
                Style.Action -> "动作"
                Style.Terror -> "恐怖"
                Style.Offense -> "犯罪"
                Style.Thriller -> "惊悚"
                Style.Suspense -> "悬疑"
                Style.War -> "战争"
                Style.Biography -> "传记"
                Style.Family -> "家庭"
                Style.Opera -> "歌剧"
                Style.Documentary -> "纪实"
                Style.Disaster -> "灾难"
                Style.Humanities -> "人文"
                Style.Technology -> "科技"
                Style.Explore -> "探险"
                Style.Universal -> "通用"
                Style.CutePet -> "萌宠"
                Style.Social -> "社会"
                Style.Animal -> "动物"
                Style.Nature -> "自然"
                Style.Medical -> "医疗"
                Style.Military -> "军事"
                Style.Crime -> "罪案"
                Style.Mystery -> "神秘"
                Style.Travel -> "旅行"
                Style.MartialArts -> "武侠"
                Style.Youth -> "青春"
                Style.City -> "都市"
                Style.AncientCostume -> "古装"
                Style.SpyWar -> "谍战"
                Style.Classic -> "经典"
                Style.Emotion -> "情感"
                Style.Myth -> "神话"
                Style.Age -> "年代"
                Style.Rural -> "农村"
                Style.CriminalInvestigation -> "刑侦"
                Style.MilitaryLife -> "军旅"
                Style.Interview -> "访谈"
                Style.TalkShow -> "脱口秀"
                Style.RealityShow -> "真人秀"
                Style.Selection -> "选秀"
                Style.Tourism -> "旅游"
                Style.Concert -> "演唱会"
                Style.ParentChild -> "亲子"
                Style.EveningParty -> "晚会"
                Style.Cultivate -> "养成"
                Style.Culture -> "文化"
                Style.SpecialEffects -> "特摄"
                Style.ShortPlay -> "短剧"
                Style.ShortFilm -> "短片"
            }
    }

/**
 * 番剧/影视索引筛选状态。
 *
 * 聚合 12 个筛选维度 + 排序方式（升/降序），随分区切换整体重置（[forType]）。
 *
 * @property pgcType 当前分区。
 * @property order 排序字段。
 * @property orderType 排序方式（升序/降序）。
 * @property seasonVersion 类型（正片/电影等）。
 * @property spokenLanguage 配音。
 * @property area 地区。
 * @property isFinish 完结状态。
 * @property copyright 版权。
 * @property seasonStatus 付费状态。
 * @property seasonMonth 季度。
 * @property producer 出品方。
 * @property year 年份（番剧/国创）。
 * @property releaseDate 上映时间（电影/纪录片/电视剧）。
 * @property style 风格。
 */
data class PgcIndexFilter(
    val pgcType: PgcType,
    val order: IndexOrder,
    val orderType: IndexOrderType,
    val seasonVersion: SeasonVersion,
    val spokenLanguage: SpokenLanguage,
    val area: Area,
    val isFinish: IsFinish,
    val copyright: Copyright,
    val seasonStatus: SeasonStatus,
    val seasonMonth: SeasonMonth,
    val producer: Producer,
    val year: Year,
    val releaseDate: ReleaseDate,
    val style: Style,
) {
    /**
     * 读取指定维度当前的筛选值。
     *
     * @param dimension 筛选维度。
     * @return 该维度当前生效的选项。
     */
    fun valueOf(dimension: PgcIndexDimension): PgcIndexParam =
        when (dimension) {
            PgcIndexDimension.Order -> order
            PgcIndexDimension.OrderType -> orderType
            PgcIndexDimension.SeasonVersion -> seasonVersion
            PgcIndexDimension.SpokenLanguage -> spokenLanguage
            PgcIndexDimension.Area -> area
            PgcIndexDimension.IsFinish -> isFinish
            PgcIndexDimension.Copyright -> copyright
            PgcIndexDimension.SeasonStatus -> seasonStatus
            PgcIndexDimension.SeasonMonth -> seasonMonth
            PgcIndexDimension.Producer -> producer
            PgcIndexDimension.Year -> year
            PgcIndexDimension.ReleaseDate -> releaseDate
            PgcIndexDimension.Style -> style
        }

    /**
     * 返回应用了指定维度选项后的新筛选状态。
     *
     * @param dimension 要修改的筛选维度。
     * @param option 新选项，必须是 [pgcIndexDimensionOptions] 中该维度的选项。
     * @return 应用选项后的新 [PgcIndexFilter]。
     */
    fun withOption(
        dimension: PgcIndexDimension,
        option: PgcIndexParam,
    ): PgcIndexFilter =
        when (dimension) {
            PgcIndexDimension.Order -> copy(order = option as IndexOrder)
            PgcIndexDimension.OrderType -> copy(orderType = option as IndexOrderType)
            PgcIndexDimension.SeasonVersion -> copy(seasonVersion = option as SeasonVersion)
            PgcIndexDimension.SpokenLanguage -> copy(spokenLanguage = option as SpokenLanguage)
            PgcIndexDimension.Area -> copy(area = option as Area)
            PgcIndexDimension.IsFinish -> copy(isFinish = option as IsFinish)
            PgcIndexDimension.Copyright -> copy(copyright = option as Copyright)
            PgcIndexDimension.SeasonStatus -> copy(seasonStatus = option as SeasonStatus)
            PgcIndexDimension.SeasonMonth -> copy(seasonMonth = option as SeasonMonth)
            PgcIndexDimension.Producer -> copy(producer = option as Producer)
            PgcIndexDimension.Year -> copy(year = option as Year)
            PgcIndexDimension.ReleaseDate -> copy(releaseDate = option as ReleaseDate)
            PgcIndexDimension.Style -> copy(style = option as Style)
        }

    companion object {
        /**
         * 创建指定分区的默认筛选状态。
         *
         * 排序取该分区可选列表的首项，其余维度全部为「全部」。
         *
         * @param pgcType 目标分区。
         * @return 默认筛选状态。
         */
        fun forType(pgcType: PgcType): PgcIndexFilter =
            PgcIndexFilter(
                pgcType = pgcType,
                order = IndexOrder.getList(pgcType).first(),
                orderType = IndexOrderType.Desc,
                seasonVersion = SeasonVersion.All,
                spokenLanguage = SpokenLanguage.All,
                area = Area.All,
                isFinish = IsFinish.All,
                copyright = Copyright.All,
                seasonStatus = SeasonStatus.All,
                seasonMonth = SeasonMonth.All,
                producer = Producer.All,
                year = Year.All,
                releaseDate = ReleaseDate.All,
                style = Style.All,
            )
    }
}

/**
 * 番剧/影视索引页 UI 状态。
 *
 * @property filter 当前筛选状态（含分区）。
 * @property items 当前筛选条件下的番剧/影视列表（已跨页去重）。
 * @property loading 是否正在加载。
 * @property hasMore 是否还有更多数据。
 * @property error 加载是否失败。
 */
data class PgcIndexUiState(
    val filter: PgcIndexFilter = PgcIndexFilter.forType(PgcType.Anime),
    val items: List<PgcItem> = emptyList(),
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val error: Boolean = false,
)

/**
 * 番剧/影视索引筛选页 ViewModel。
 *
 * 管理 12 个筛选维度 + 排序方式的组合筛选与分页加载：
 * - 维度选择/分区切换都会重置分页并重新加载第一页；
 * - 翻页按 seasonId 去重，避免跨页重复项；
 * - 每次重置递增 generation，陈旧响应（代际不匹配）直接丢弃。
 *
 * @param pgcRepository PGC 数据仓库。
 * @param savedStateHandle Navigation 传递的参数（[dev.frost819.newbv.app.ui.navigation.PgcIndexRoute]）。
 */
@HiltViewModel
class PgcIndexViewModel
    @Inject
    constructor(
        private val pgcRepository: PgcRepository,
        savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val logger = Loggers.get("PgcIndexViewModel")

        private val _uiState = MutableStateFlow(PgcIndexUiState())
        val uiState: StateFlow<PgcIndexUiState> = _uiState.asStateFlow()

        /** 请求代际计数：切换分区/筛选/刷新时递增，用于丢弃陈旧响应。 */
        private var generation = 0

        /** 下一次请求的页码（也是当前已加载到的页码 + 1）。 */
        private var nextPage = 1

        /** 已加载过的 seasonId 集合，用于跨页去重。 */
        private val seenSeasonIds = mutableSetOf<Int>()

        init {
            val routeType =
                savedStateHandle
                    .get<String>("pgcType")
                    ?.let { type ->
                        runCatching { PgcType.valueOf(type) }.getOrNull()
                    } ?: PgcType.Anime
            switchType(routeType)
        }

        /**
         * 切换分区。
         *
         * 全部筛选维度重置为该分区默认值，并从第一页重新加载。
         * 同分区且已有数据时为 no-op（顶部导航焦点滑过会重复触发）。
         *
         * @param type 目标分区。
         */
        fun switchType(type: PgcType) {
            if (type == _uiState.value.filter.pgcType && _uiState.value.items.isNotEmpty()) return
            resetAndLoad(PgcIndexFilter.forType(type))
        }

        /**
         * 选择筛选维度的选项。
         *
         * 筛选变化后重置分页并从第一页重新加载；选项与当前值相同时为 no-op。
         *
         * @param dimension 筛选维度。
         * @param optionIndex 选项在 [pgcIndexDimensionOptions] 返回列表中的下标，越界时忽略。
         */
        fun selectOption(
            dimension: PgcIndexDimension,
            optionIndex: Int,
        ) {
            val current = _uiState.value
            val options = pgcIndexDimensionOptions(dimension, current.filter.pgcType)
            if (optionIndex !in options.indices) return
            val newFilter = current.filter.withOption(dimension, options[optionIndex])
            if (newFilter == current.filter) return
            resetAndLoad(newFilter)
        }

        /**
         * 刷新当前筛选条件下的数据（从第一页重新加载）。
         */
        fun refresh() {
            resetAndLoad(_uiState.value.filter)
        }

        /**
         * 加载下一页。
         *
         * 正在加载或没有更多数据时为 no-op。
         */
        fun loadMore() {
            val current = _uiState.value
            if (current.loading || !current.hasMore) return
            loadPage(generation)
        }

        /**
         * 重置分页状态并按新筛选加载第一页。
         *
         * @param filter 新的筛选状态。
         */
        private fun resetAndLoad(filter: PgcIndexFilter) {
            generation += 1
            nextPage = 1
            seenSeasonIds.clear()
            _uiState.value =
                PgcIndexUiState(
                    filter = filter,
                    items = emptyList(),
                    loading = false,
                    hasMore = true,
                    error = false,
                )
            loadPage(generation)
        }

        /**
         * 加载指定代际的一页数据。
         *
         * 响应返回时若代际已过期（期间切换了分区/筛选/刷新），丢弃数据且不改动加载状态。
         *
         * @param requestGeneration 发起请求时的代际。
         */
        private fun loadPage(requestGeneration: Int) {
            if (_uiState.value.loading) return
            val filter = _uiState.value.filter
            viewModelScope.launch {
                _uiState.update { it.copy(loading = true, error = false) }

                runCatching {
                    withTimeout(LOAD_TIMEOUT_MS) {
                        val page =
                            PgcIndexData.PgcIndexPage(
                                currentPage = nextPage,
                                pageSize = PAGE_SIZE,
                                nextPage = nextPage,
                            )
                        val data =
                            pgcRepository.getPgcIndex(
                                pgcType = filter.pgcType,
                                indexOrder = filter.order,
                                indexOrderType = filter.orderType,
                                seasonVersion = filter.seasonVersion,
                                spokenLanguage = filter.spokenLanguage,
                                area = filter.area,
                                isFinish = filter.isFinish,
                                copyright = filter.copyright,
                                seasonStatus = filter.seasonStatus,
                                seasonMonth = filter.seasonMonth,
                                producer = filter.producer,
                                year = filter.year,
                                releaseDate = filter.releaseDate,
                                style = filter.style,
                                page = page,
                            )
                        if (requestGeneration != generation) {
                            // 期间已切换分区/筛选/刷新，本次响应已陈旧，直接丢弃
                            return@withTimeout
                        }
                        // 跨页去重：仅追加未出现过的 seasonId
                        val freshItems = data.list.filter { seenSeasonIds.add(it.seasonId) }
                        nextPage = data.nextPage.nextPage
                        _uiState.update {
                            it.copy(
                                items = it.items + freshItems,
                                hasMore = data.nextPage.hasNext,
                            )
                        }
                    }
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) {
                        throw error
                    }
                    if (requestGeneration == generation) {
                        logger.error(error) { "Failed to load pgc index: ${filter.pgcType}" }
                        _uiState.update { it.copy(error = true) }
                    }
                }

                if (requestGeneration == generation) {
                    _uiState.update { it.copy(loading = false) }
                }
            }
        }
    }
