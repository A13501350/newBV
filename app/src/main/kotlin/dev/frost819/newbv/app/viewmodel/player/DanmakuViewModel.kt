package dev.frost819.newbv.app.viewmodel.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ecs.component.filter.DuplicateMergedFilter
import com.kuaishou.akdanmaku.ecs.component.filter.TextColorFilter
import com.kuaishou.akdanmaku.ecs.component.filter.TypeFilter
import com.kuaishou.akdanmaku.render.SimpleRenderer
import com.kuaishou.akdanmaku.ui.DanmakuPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.BuildConfig
import dev.frost819.newbv.app.ui.action.player.DanmakuSettingAction
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.danmaku.BiliDanmakuFilterConfig
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMask
import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuMeta
import dev.frost819.newbv.biliapi.http.entity.danmaku.DanmakuData
import dev.frost819.newbv.biliapi.http.entity.danmaku.DanmakuFilterRule
import dev.frost819.newbv.biliapi.repositories.AuthRepository
import dev.frost819.newbv.biliapi.repositories.VideoPlayRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.danmaku.config.DanmakuState
import dev.frost819.newbv.data.datastore.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.zip.CRC32
import javax.inject.Inject
import kotlin.math.abs
import dev.frost819.newbv.danmaku.entity.DanmakuType as DanmakuEntityDanmakuType
import dev.frost819.newbv.data.datastore.ApiType as DataApiType
import dev.frost819.newbv.data.datastore.DanmakuType as DataDanmakuType

/**
 * 弹幕 ViewModel。
 *
 * 管理弹幕播放器生命周期、弹幕数据加载（分段 → [DanmakuItemData]）、
 * 弹幕配置（大小/透明度/区域/速度/类型/蒙版）、播放同步。
 *
 * 弹幕数据采用分段加载（每段时长由 dm/view 元数据决定，通常 6 分钟）：
 * - [loadDanmaku] 获取元数据并按初始位置加载首段
 * - [onVideoPositionChanged] 由 UI 层喂入播放进度，内部按段号去重后驱动加载
 * - 每次加载目标段及预取下一段，已加载/加载中的段自动跳过
 * - 失败重试若干次后放弃（不降级 XML），进度进入下一段时自然恢复
 *
 * 与 [PlayerViewModel] 的同步由 UI 层协调：
 * - 播放进度 → 调用 [onVideoPositionChanged]：既驱动分段加载，也自动对齐弹幕
 *   引擎时钟（位置跳变超过阈值时 seek 引擎），因此任何改变视频位置的入口
 *   （断点续播、切集、用户 seek、回到开头）都无需单独通知本 VM
 * - 视频播放/暂停/缓冲 → 调用 [play] / [pause]
 * - 播放倍速 → 调用 [updateSpeed]：引擎时钟按倍速缩放，与视频同速，避免弹幕乱跳
 * - 切换视频 → 调用 [clearDanmaku] + [loadDanmaku]
 */
@HiltViewModel
class DanmakuViewModel
    @Inject
    constructor(
        private val videoPlayRepository: VideoPlayRepository,
        private val authRepository: AuthRepository,
    ) : ViewModel() {
        private val logger = Loggers.get("DanmakuViewModel")

        /**
         * 弹幕播放器实例，供 Compose `AndroidView` 绑定。
         * internal set 供单元测试注入 MockK 引擎，生产代码仅在 [init] / [release] 中赋值。
         */
        var danmakuPlayer: DanmakuPlayer? by mutableStateOf(null)
            internal set

        /**
         * 引擎是否应处于运行态（由最近一次 [play]/[pause] 决定）。
         *
         * `DanmakuPlayer.seekTo` 内部会 `timer.start()` 解暂停，对齐时钟时需据此还原，
         * 否则暂停/缓冲期间喂入跳变位置会让弹幕继续滚动。
         */
        private var danmakuRunning = false

        private var danmakuConfig = DanmakuConfig()
        private val danmakuTypeFilter = TypeFilter()
        private val danmakuTextColorFilter = TextColorFilter()
        private val danmakuDuplicateFilter = DuplicateMergedFilter()

        private val _danmakuState =
            MutableStateFlow(
                DanmakuState(
                    scale = Prefs.defaultDanmakuScale,
                    opacity = Prefs.defaultDanmakuOpacity,
                    area = Prefs.defaultDanmakuArea,
                    speedFactor = Prefs.defaultDanmakuSpeedFactor,
                    maskEnabled = Prefs.defaultDanmakuMask,
                    enabledTypes = Prefs.defaultDanmakuTypes.map { it.toDanmakuEntity() },
                    blockColorful = Prefs.defaultDanmakuBlockColorful,
                    blockRepeat = Prefs.defaultDanmakuBlockRepeat,
                    blockSpecial = Prefs.defaultDanmakuBlockSpecial,
                    blockByUserFilter = Prefs.defaultDanmakuBlockByUserFilter,
                    aiCloudLevel = Prefs.defaultDanmakuAiCloudLevel,
                ),
            )
        val danmakuState = _danmakuState.asStateFlow()

        /** 弹幕防遮挡蒙版数据，由 [loadDanmakuMask] 加载后存储。 */
        private val _danmakuMask = MutableStateFlow<DanmakuMask?>(null)
        val danmakuMask = _danmakuMask.asStateFlow()

        /** 蒙版请求独立于弹幕数据请求，切集时 cancel 旧请求即可。 */
        private var maskFetchJob: Job? = null

        // === 分段加载状态 ===

        /** 当前视频 aid。 */
        private var currentAid = 0L

        /** 当前视频 cid；<= 0 表示尚未加载视频。 */
        private var currentCid = 0L

        /** 每段时长（毫秒），由 dm/view 元数据决定，获取失败时回退默认值。 */
        private var segmentSizeMs = DEFAULT_SEGMENT_SIZE_MS

        /** 分段总数；0 表示未知，不用于越界限制。 */
        private var segmentTotal = 0

        /** 当前视频弹幕是否已关闭（dm/view state==1），关闭时不加载任何分段。 */
        private var danmakuClosed = false

        /** 已加载的分段号集合。 */
        private val loadedSegments = mutableSetOf<Int>()

        /** 加载中的段号，防止同一分段并发重复请求。 */
        private val loadingSegments = mutableSetOf<Int>()

        /**
         * 切集防陈旧计数：[loadDanmaku] 每次自增，
         * 旧协程返回后凭此比对丢弃结果，避免旧视频数据污染新视频。
         */
        private var loadGeneration = 0

        /** 云端账号屏蔽规则是否已请求过（本次会话只请求一次）。 */
        private var userFilterLoaded = false

        /** 云端账号屏蔽规则编译结果；分段过滤在 IO 协程读取，需跨线程可见。 */
        @Volatile private var userFilter = CompiledUserFilter()

        /**
         * B 站举报屏蔽词（dm/view `reportFilterContent`，当前 cid 维度）。
         *
         * 由服务端强制下发，与用户账号无关，过滤时不受本地开关控制；
         * 随每次 [loadDanmaku] 的元数据刷新。
         */
        @Volatile private var reportFilterKeywords: List<String> = emptyList()

        /** UI 层喂入的播放进度（毫秒），驱动分段加载；conflated，仅保留最新值。 */
        private val currentTimeFlow = MutableStateFlow(0L)

        /** 分段加载监听 Job，[loadDanmaku] 拿到元数据后启动。 */
        private var segmentWatchJob: Job? = null

        /** 初始化弹幕播放器。 */
        fun init() {
            if (danmakuPlayer != null) return
            danmakuPlayer = DanmakuPlayer(SimpleRenderer())
            // 仅 debug 包开启引擎侧过滤统计，用于核对各过滤器是否真正生效
            danmakuPlayer?.debugFilterStats = BuildConfig.DEBUG
            initDanmakuConfig()
        }

        /** 释放弹幕播放器资源。 */
        fun release() {
            maskFetchJob?.cancel()
            maskFetchJob = null
            segmentWatchJob?.cancel()
            segmentWatchJob = null
            danmakuPlayer?.release()
            danmakuPlayer = null
            _danmakuMask.update { null }
        }

        override fun onCleared() {
            release()
            super.onCleared()
        }

        /**
         * 切换视频时清空弹幕数据。
         *
         * 保留 [danmakuPlayer] 实例和配置，清空弹幕内容、蒙版和分段状态。
         * 调用后应接着 [loadDanmaku] 加载新视频的弹幕。
         */
        fun clearDanmaku() {
            maskFetchJob?.cancel()
            maskFetchJob = null
            segmentWatchJob?.cancel()
            segmentWatchJob = null
            loadGeneration++
            currentAid = 0L
            currentCid = 0L
            loadedSegments.clear()
            loadingSegments.clear()
            danmakuClosed = false
            currentTimeFlow.value = 0L
            // 引擎 updateData 是增量语义，必须调用 clearData 才能真正清空旧数据
            danmakuPlayer?.clearData()
            _danmakuMask.update { null }
        }

        /**
         * 加载弹幕数据（分段加载）。
         *
         * 流程：重置分段状态 → 获取 dm/view 元数据（失败回退默认 6 分钟分段）
         * → 启动进度监听 → 由初始位置触发首段加载。
         *
         * @param aid 视频 AV 号
         * @param cid 视频 CID
         * @param initialPositionMs 初始播放位置（毫秒，如断点续播位置），
         *        决定首个加载的分段；省略时从第 1 段开始
         */
        fun loadDanmaku(
            aid: Long,
            cid: Long,
            initialPositionMs: Long = 0L,
        ) {
            val generation = ++loadGeneration
            // 同步重置状态：切集后旧分段数据立即失效
            currentAid = aid
            currentCid = cid
            segmentSizeMs = DEFAULT_SEGMENT_SIZE_MS
            segmentTotal = 0
            danmakuClosed = false
            loadedSegments.clear()
            loadingSegments.clear()
            currentTimeFlow.value = initialPositionMs.coerceAtLeast(0L)
            danmakuPlayer?.clearData()

            viewModelScope.launch {
                // 元数据决定分段大小与总数；失败仅记日志，回退默认分段大小继续加载
                val meta =
                    runCatching {
                        withTimeout(SEGMENT_FETCH_TIMEOUT_MS) {
                            videoPlayRepository.getDanmakuMeta(aid = aid, cid = cid)
                        }
                    }.getOrElse { e ->
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        logger.warn { "Load danmaku meta failed, fallback to default segment size: $e" }
                        DanmakuMeta.DEFAULT
                    }
                if (generation != loadGeneration) return@launch

                if (meta.segmentSizeMs > 0) segmentSizeMs = meta.segmentSizeMs
                segmentTotal = meta.segTotal
                logDmViewFilterConfig(meta.filterConfig)
                if (meta.closed) {
                    danmakuClosed = true
                    logger.info { "Danmaku closed for cid=$cid, skip segment loading" }
                    return@launch
                }

                // 从 dm/view 响应中应用 B 站账号同步来的弹幕过滤配置
                applyBiliFilterConfig(meta.filterConfig)
                // 举报屏蔽词随每次元数据刷新（cid 维度，服务端强制内容）
                reportFilterKeywords = meta.filterConfig.reportFilterContent

                logger.info {
                    "Danmaku meta: segmentSizeMs=$segmentSizeMs, segTotal=$segmentTotal, count=${meta.count}"
                }

                // 账号云端屏蔽词/正则/用户规则：本次会话只拉取一次，失败不影响播放
                loadUserFilter()

                startSegmentWatcher()
                // 触发初始段加载：watcher 订阅 StateFlow 时会立即收到当前值
                currentTimeFlow.value = initialPositionMs.coerceAtLeast(0L)
            }
        }

        /**
         * 直播弹幕过滤准备：进入直播间时拉取账号云端屏蔽规则。
         *
         * 与点播共用会话内一次的拉取门控；未登录/失败仅记日志并降级。
         */
        fun prepareForLive() {
            viewModelScope.launch { loadUserFilter() }
        }

        /**
         * 直播弹幕单条过滤：账号云端规则（屏蔽词/正则/用户 midHash）。
         *
         * 直播 WS 弹幕无 weight/ai_flag（AI 云屏蔽无数据源）、无 mode 7/8/9
         * 高级弹幕，因此仅应用账号屏蔽规则；midHash 由 WS mid 现算 crc32。
         *
         * @return true 表示该弹幕应被丢弃
         */
        fun shouldDropLiveDanmaku(
            content: String,
            mid: Long,
        ): Boolean {
            val state = _danmakuState.value
            if (!state.blockByUserFilter) return false
            val rules = userFilter
            if (rules.isEmpty) return false
            val dropped = rules.matches(midHash = crc32Hex(mid.toString()), content = content)
            if (dropped && BuildConfig.DEBUG) {
                logger.debug { "Live danmaku dropped by user filter: mid=$mid, text=\"$content\"" }
            }
            return dropped
        }

        /**
         * 由 UI 层喂入视频播放位置（毫秒），弹幕时间轴的唯一同步入口。
         *
         * - 驱动分段加载：内部换算段号后去重，仅段号变化时触发请求，高频调用安全
         * - 对齐引擎时钟：引擎按自身时钟渲染、分段只加载目标段附近，时钟一旦
         *   偏离视频位置（断点续播 seek、切集、用户 seek 等）窗口内将无弹幕可
         *   渲染且不会自愈，因此位置跳变时必须显式对齐（见 [reconcileEngineTime]）
         */
        fun onVideoPositionChanged(timeMs: Long) {
            currentTimeFlow.value = timeMs
            reconcileEngineTime(videoTimeMs = timeMs, player = danmakuPlayer)
        }

        /**
         * 对齐弹幕引擎时钟：与视频位置偏差超过 [ENGINE_TIME_JUMP_THRESHOLD_MS] 时 seek 引擎。
         *
         * [player] 参数化仅为可测性（单元测试注入 MockK 引擎），生产调用方传 [danmakuPlayer]。
         *
         * 注意 `DanmakuPlayer.seekTo` 会经 `DanmakuTimer.start()` 把引擎从暂停态解开，
         * 因此 seek 后需按 [danmakuRunning] 还原暂停态，避免暂停/缓冲期间弹幕继续滚动。
         */
        internal fun reconcileEngineTime(
            videoTimeMs: Long,
            player: DanmakuPlayer?,
        ) {
            if (player == null) return
            val engineTimeMs = player.getCurrentTimeMs()
            if (abs(videoTimeMs - engineTimeMs) <= ENGINE_TIME_JUMP_THRESHOLD_MS) return
            logger.info { "Align danmaku engine clock: video=$videoTimeMs ms, engine=$engineTimeMs ms" }
            player.seekTo(videoTimeMs)
            if (!danmakuRunning) player.pause()
        }

        /**
         * 启动分段加载监听：播放进度换算段号，段号变化时加载目标段及预取段。
         *
         * 用响应式 Flow 而非定时轮询（AGENTS.md 11.5.4）：无 VM 内自调度循环，
         * 测试可安全使用 advanceUntilIdle。
         */
        private fun startSegmentWatcher() {
            segmentWatchJob?.cancel()
            segmentWatchJob =
                viewModelScope.launch {
                    currentTimeFlow
                        .map { segmentIndexOf(it) }
                        .distinctUntilChanged()
                        .collectLatest { targetSeg ->
                            ensureSegments(targetSeg)
                        }
                }
        }

        /**
         * 由播放位置换算分段索引（从 1 开始）。
         *
         * @param timeMs 播放位置（毫秒）
         */
        internal fun segmentIndexOf(timeMs: Long): Int =
            if (segmentSizeMs <= 0) {
                1
            } else {
                (timeMs / segmentSizeMs).toInt() + 1
            }

        /**
         * 确保目标段及预取段（[DEFAULT_SEGMENT_PREFETCH_AHEAD] 段之后）已加载。
         *
         * 已加载/加载中/越界（超过 [segmentTotal]）的段自动跳过。
         * 段内顺序串行请求，避免同一时刻多段并发。
         */
        private suspend fun ensureSegments(targetSeg: Int) {
            if (danmakuClosed || currentCid <= 0L) return
            val toLoad = (targetSeg..(targetSeg + DEFAULT_SEGMENT_PREFETCH_AHEAD)).filter { canLoadSegment(it) }
            for (seg in toLoad) {
                fetchSegmentWithRetry(seg)
            }
        }

        /**
         * 判断分段是否可以发起加载。
         *
         * @return true 表示可加载（同时标记为加载中）；false 表示已加载/加载中/越界
         */
        private fun canLoadSegment(segmentIndex: Int): Boolean {
            if (segmentIndex <= 0) return false
            if (segmentTotal > 0 && segmentIndex > segmentTotal) return false
            if (segmentIndex in loadedSegments) return false
            if (segmentIndex in loadingSegments) return false
            loadingSegments.add(segmentIndex)
            return true
        }

        /**
         * 「合并重复弹幕」开启时重载当前分段。
         *
         * 合并（[mergeDuplicates]）发生在分段加载时：以关闭态加载的分段
         * 全部是 NORMAL、没有合并版本可供 Filter 切换，开启后需清空引擎
         * 数据并重新拉取当前段及预取段，使 ×N 合并立即可见。
         * 已播放过的历史分段不重载（对当前观看无意义）。
         */
        private fun reloadSegmentsForMerge() {
            val player = danmakuPlayer ?: return
            if (loadedSegments.isEmpty() && loadingSegments.isEmpty()) return
            val targetSeg = segmentIndexOf(currentTimeFlow.value)
            viewModelScope.launch {
                player.clearData()
                loadedSegments.clear()
                logger.info { "Reload segments for blockRepeat merge: targetSeg=$targetSeg" }
                ensureSegments(targetSeg)
            }
        }

        /**
         * 拉取单个分段并追加到弹幕引擎，带超时与重试。
         *
         * 重试耗尽后放弃该段（记日志），进度进入其他段时自然恢复。
         * [TimeoutCancellationException] 视为普通失败参与重试；
         * 其余 [CancellationException] 属外层取消（collectLatest 切段 / 切集），必须上抛。
         */
        private suspend fun fetchSegmentWithRetry(segmentIndex: Int) {
            try {
                repeat(SEGMENT_MAX_ATTEMPTS) { attempt ->
                    try {
                        val dataList =
                            withTimeout(SEGMENT_FETCH_TIMEOUT_MS) {
                                videoPlayRepository.getDanmakuSegment(
                                    aid = currentAid,
                                    cid = currentCid,
                                    segmentIndex = segmentIndex,
                                    preferApiType = if (Prefs.apiType == DataApiType.App) ApiType.App else ApiType.Web,
                                )
                            }
                        logSegmentAiFlagStats(segmentIndex, dataList)
                        val filterStats = SegmentFilterStats()
                        val filteredList = dataList.filterForDisplay(filterStats)
                        val items =
                            if (_danmakuState.value.blockRepeat) {
                                filteredList.mergeDuplicates()
                            } else {
                                filteredList.map { it.toDanmakuItemData() }
                            }
                        // akdanmaku 内部维护有序数据并懒排序，无需预先排序
                        danmakuPlayer?.updateData(items)
                        loadedSegments.add(segmentIndex)
                        logger.info {
                            "Load danmaku segment $segmentIndex: raw=${dataList.size}, " +
                                "kept=${filteredList.size}, specialDropped=${filterStats.specialDropped}, " +
                                "reportDropped=${filterStats.reportDropped}, " +
                                "userDropped=${filterStats.userDropped}, " +
                                "aiDropped=${filterStats.aiDropped}, aiScored=${filterStats.aiScored}, " +
                                "weightRange=${filterStats.minWeight}..${filterStats.maxWeight}, " +
                                "aiLevel=${_danmakuState.value.aiCloudLevel}, " +
                                "blockSpecial=${_danmakuState.value.blockSpecial}, " +
                                "blockRepeat=${_danmakuState.value.blockRepeat}, " +
                                "blockUserFilter=${_danmakuState.value.blockByUserFilter}"
                        }
                        return
                    } catch (e: TimeoutCancellationException) {
                        if (attempt == SEGMENT_MAX_ATTEMPTS - 1) {
                            logger.warn {
                                "Load danmaku segment $segmentIndex timeout after $SEGMENT_MAX_ATTEMPTS attempts"
                            }
                            return
                        }
                        delay(RETRY_BACKOFF_BASE_MS shl attempt)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (attempt == SEGMENT_MAX_ATTEMPTS - 1) {
                            logger.warn {
                                "Load danmaku segment $segmentIndex failed after $SEGMENT_MAX_ATTEMPTS attempts: $e"
                            }
                            return
                        }
                        delay(RETRY_BACKOFF_BASE_MS shl attempt)
                    }
                }
            } finally {
                // 取消/失败/成功都要移除标记：失败段允许后续触发重试
                loadingSegments.remove(segmentIndex)
            }
        }

        /**
         * 将接口层弹幕数据转换为 akdanmaku 渲染数据。
         *
         * @param mergedType 合并弹幕类型，默认普通弹幕
         * @param mergedCount 重复条数，>1 时在文本后追加 " ×N"
         */
        private fun DanmakuData.toDanmakuItemData(
            mergedType: Int = DanmakuItemData.MERGED_TYPE_NORMAL,
            mergedCount: Int = 0,
        ): DanmakuItemData =
            DanmakuItemData(
                danmakuId = dmid,
                position = (time * 1000).toLong(),
                content = if (mergedCount > 1) "$text ×$mergedCount" else text,
                mode =
                    when (type) {
                        4 -> DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
                        5 -> DanmakuItemData.DANMAKU_MODE_CENTER_TOP
                        else -> DanmakuItemData.DANMAKU_MODE_ROLLING
                    },
                textSize = size,
                textColor = Color(color).toArgb(),
                mergedType = mergedType,
            )

        /**
         * 按内容合并重复弹幕。
         *
         * 相同文本的弹幕在 [MERGE_WINDOW_SECONDS] 时间窗内合并为一组：
         * - 组内首条标为 [DanmakuItemData.MERGED_TYPE_MERGED]，文本追加 " ×N"
         * - 组内其余标为 [DanmakuItemData.MERGED_TYPE_ORIGINAL]
         * - 无重复的弹幕保持 [DanmakuItemData.MERGED_TYPE_NORMAL]
         *
         * 合并弹幕与原件均送入引擎，由 [DuplicateMergedFilter] 根据 `blockRepeat` 开关
         * 决定展示哪一种（开启展示合并弹幕，关闭展示原件）。
         */
        internal fun List<DanmakuData>.mergeDuplicates(): List<DanmakuItemData> {
            if (isEmpty()) return emptyList()
            val result = ArrayList<DanmakuItemData>(size)
            for (group in groupBy { it.text }.values) {
                if (group.size == 1) {
                    result += group[0].toDanmakuItemData()
                    continue
                }
                val sorted = group.sortedBy { it.time }
                var i = 0
                while (i < sorted.size) {
                    val baseTime = sorted[i].time
                    var j = i
                    while (j < sorted.size && sorted[j].time - baseTime <= MERGE_WINDOW_SECONDS) j++
                    val windowSize = j - i
                    if (windowSize == 1) {
                        result += sorted[i].toDanmakuItemData()
                    } else {
                        result +=
                            sorted[i].toDanmakuItemData(
                                mergedType = DanmakuItemData.MERGED_TYPE_MERGED,
                                mergedCount = windowSize,
                            )
                        for (k in (i + 1) until j) {
                            result +=
                                sorted[k].toDanmakuItemData(
                                    mergedType = DanmakuItemData.MERGED_TYPE_ORIGINAL,
                                )
                        }
                    }
                    i = j
                }
            }
            return result
        }

        /** 分段过滤统计，用于运行日志核对接口数据是否被正确解析。 */
        private class SegmentFilterStats {
            var specialDropped: Int = 0
            var aiDropped: Int = 0
            var userDropped: Int = 0
            var reportDropped: Int = 0
            var aiScored: Int = 0
            var minWeight: Int = Int.MAX_VALUE
            var maxWeight: Int = Int.MIN_VALUE
        }

        /**
         * 按当前弹幕设置过滤分段数据。
         *
         * - 高级/代码/BAS 弹幕（mode 7/8/9）引擎无法渲染，开启屏蔽时直接丢弃
         * - 举报屏蔽词（`reportFilterContent`，cid 维度）：服务端强制内容，无条件生效
         * - 账号云端规则：屏蔽词、正则、用户（midHash）
         * - AI 云屏蔽按等级丢弃低分弹幕：评分取 weight 与服务端 ai_flag 的较大值
         */
        private fun List<DanmakuData>.filterForDisplay(stats: SegmentFilterStats): List<DanmakuData> {
            val state = _danmakuState.value
            val rules = userFilter
            val reportKeywords = reportFilterKeywords
            val kept = ArrayList<DanmakuData>(size)
            for (item in this) {
                if (item.aiFlagScore != 0) stats.aiScored++
                if (item.level > 0) {
                    stats.minWeight = minOf(stats.minWeight, item.level)
                    stats.maxWeight = maxOf(stats.maxWeight, item.level)
                }
                if (state.blockSpecial && item.type in SPECIAL_DANMAKU_MODES) {
                    stats.specialDropped++
                    continue
                }
                if (reportKeywords.isNotEmpty() && reportKeywords.any { item.text.contains(it) }) {
                    stats.reportDropped++
                    continue
                }
                if (state.blockByUserFilter && rules.matches(item.midHash, item.text)) {
                    stats.userDropped++
                    continue
                }
                if (state.aiCloudLevel > 0 && item.shouldDropByAi(state.aiCloudLevel)) {
                    stats.aiDropped++
                    continue
                }
                kept += item
            }
            if (stats.minWeight == Int.MAX_VALUE) stats.minWeight = 0
            if (stats.maxWeight == Int.MIN_VALUE) stats.maxWeight = 0
            return kept
        }

        /**
         * AI 云屏蔽等级 → 评分阈值映射。
         *
         * 等级 1-4 对应评分阈值 1/5/7/10，与 B 站官方行为一致。
         * 评分区间 1-10（weight/aiFlagScore），等级 0 表示关闭。
         */
        private val aiScoreThresholds = intArrayOf(0, 1, 5, 7, 10)

        internal fun DanmakuData.shouldDropByAi(aiLevel: Int): Boolean {
            val score =
                sequenceOf(level, aiFlagScore)
                    .filter { it != 0 }
                    .maxOfOrNull { abs(it) }
                    ?: return false
            val threshold = aiScoreThresholds.getOrElse(aiLevel) { 0 }
            return score < threshold
        }

        /**
         * 加载当前视频的弹幕防遮挡蒙版。
         *
         * 视频标识复用 [loadDanmaku] 记录的 [currentAid] / [currentCid]，需在其之后调用。
         * 防遮挡默认关闭且蒙版只在开启时需要渲染，因此关闭状态下直接跳过请求，
         * 待用户打开开关时由 [updateDanmakuState] 补加载。
         */
        fun loadDanmakuMask() {
            maskFetchJob?.cancel()
            maskFetchJob = null
            val aid = currentAid
            val cid = currentCid
            if (cid <= 0L) {
                logger.info { "No current video, skip loading danmaku mask" }
                return
            }
            if (!_danmakuState.value.maskEnabled) {
                logger.info { "Danmaku mask disabled, skip loading (aid=$aid, cid=$cid)" }
                return
            }
            maskFetchJob =
                viewModelScope.launch {
                    try {
                        val mask =
                            withTimeout(LOAD_TIMEOUT_MS) {
                                videoPlayRepository.getDanmakuMask(
                                    aid = aid,
                                    cid = cid,
                                    preferApiType = if (Prefs.apiType == DataApiType.App) ApiType.App else ApiType.Web,
                                )
                            }
                        _danmakuMask.update { mask }
                        logger.info { "Load danmaku mask segments: ${mask?.segmentCount ?: 0}" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        logger.warn { "Load danmaku mask failed: $e" }
                    }
                }
        }

        /** 播放弹幕（与视频播放同步）。 */
        fun play() {
            danmakuRunning = true
            danmakuPlayer?.start()
        }

        /** 暂停弹幕。 */
        fun pause() {
            danmakuRunning = false
            danmakuPlayer?.pause()
        }

        /**
         * 更新弹幕播放速度，与视频播放速度保持一致。
         *
         * 由 UI 层随播放器的 `playSpeed` 状态驱动：起播时的默认倍速与后续手动切换
         * 都经此应用到引擎（引擎时钟按倍速缩放，避免与视频不同速导致弹幕乱跳）。
         */
        fun updateSpeed(speed: Float) {
            danmakuPlayer?.updatePlaySpeed(speed)
        }

        /** 切换弹幕开关。 */
        fun toggleDanmaku() {
            val current = _danmakuState.value.enabledTypes
            if (current.isEmpty()) {
                updateDanmakuState(DanmakuSettingAction.SetEnabledTypes(DanmakuEntityDanmakuType.entries))
            } else {
                updateDanmakuState(DanmakuSettingAction.SetEnabledTypes(emptyList()))
            }
        }

        /**
         * 更新弹幕配置。
         *
         * 同时更新内存状态、akdanmaku 配置和 Prefs 持久化。
         */
        fun updateDanmakuState(action: DanmakuSettingAction) {
            val old = _danmakuState.value
            val new =
                when (action) {
                    is DanmakuSettingAction.SetScale -> old.copy(scale = action.scale)
                    is DanmakuSettingAction.SetOpacity -> old.copy(opacity = action.opacity)
                    is DanmakuSettingAction.SetArea -> old.copy(area = action.area)
                    is DanmakuSettingAction.SetSpeedFactor -> old.copy(speedFactor = action.factor)
                    is DanmakuSettingAction.SetMaskEnabled -> old.copy(maskEnabled = action.enabled)
                    is DanmakuSettingAction.SetEnabledTypes -> old.copy(enabledTypes = action.types)
                    is DanmakuSettingAction.SetBlockColorful -> old.copy(blockColorful = action.enabled)
                    is DanmakuSettingAction.SetBlockRepeat -> old.copy(blockRepeat = action.enabled)
                    is DanmakuSettingAction.SetBlockSpecial -> old.copy(blockSpecial = action.enabled)
                    is DanmakuSettingAction.SetBlockByUserFilter ->
                        old.copy(blockByUserFilter = action.enabled)

                    is DanmakuSettingAction.SetAiCloudLevel -> old.copy(aiCloudLevel = action.level)
                }
            if (old == new) return

            _danmakuState.update { new }

            val filterChanged =
                new.enabledTypes != old.enabledTypes ||
                    new.blockColorful != old.blockColorful ||
                    new.blockRepeat != old.blockRepeat

            if (filterChanged) {
                initDanmakuConfig()
                logger.info {
                    "Danmaku filter changed: $action, " +
                        "colorful=${new.blockColorful}, repeat=${new.blockRepeat}, " +
                        "special=${new.blockSpecial}, aiCloudLevel=${new.aiCloudLevel}"
                }
                // 合并发生在分段加载时：关闭态加载的分段没有合并版本，开启后需重载当前段
                if (new.blockRepeat && !old.blockRepeat) {
                    reloadSegmentsForMerge()
                }
            } else {
                // 账号屏蔽规则、高级弹幕、AI 云屏蔽均为分段加载前的本地过滤，
                // 不经过引擎 Filter 链，切换后从下一分段起生效
                if (new.blockByUserFilter != old.blockByUserFilter) {
                    logger.info {
                        "Danmaku user filter toggle: enabled=${new.blockByUserFilter}（从下一分段起生效）"
                    }
                }
                if (new.blockSpecial != old.blockSpecial) {
                    logger.info {
                        "Danmaku blockSpecial toggle: enabled=${new.blockSpecial}（从下一分段起生效）"
                    }
                }
                if (new.aiCloudLevel != old.aiCloudLevel) {
                    logger.info {
                        "Danmaku AI cloud level changed: level=${new.aiCloudLevel}（从下一分段起生效）"
                    }
                }
            }

            // 持久化
            if (new.enabledTypes != old.enabledTypes) {
                Prefs.defaultDanmakuTypes = new.enabledTypes.map { it.toDataDanmakuType() }
            }
            if (new.scale != old.scale) {
                updateDanmakuScale(new.scale)
                Prefs.defaultDanmakuScale = new.scale
            }
            if (new.speedFactor != old.speedFactor) {
                danmakuPlayer?.setDanmakuRollingSpeed(new.speedFactor)
                Prefs.defaultDanmakuSpeedFactor = new.speedFactor
            }
            if (new.area != old.area) {
                updateDanmakuArea(new.area)
                Prefs.defaultDanmakuArea = new.area
            }
            if (new.opacity != old.opacity) {
                Prefs.defaultDanmakuOpacity = new.opacity
            }
            if (new.maskEnabled != old.maskEnabled) {
                Prefs.defaultDanmakuMask = new.maskEnabled
                // 蒙版只在开启时请求：关闭期间不下载，打开后若当前视频尚未加载则补加载
                if (new.maskEnabled && _danmakuMask.value == null && currentCid > 0L) {
                    loadDanmakuMask()
                }
            }
            if (new.blockColorful != old.blockColorful) Prefs.defaultDanmakuBlockColorful = new.blockColorful
            if (new.blockRepeat != old.blockRepeat) Prefs.defaultDanmakuBlockRepeat = new.blockRepeat
            if (new.blockSpecial != old.blockSpecial) Prefs.defaultDanmakuBlockSpecial = new.blockSpecial
            if (new.blockByUserFilter != old.blockByUserFilter) {
                Prefs.defaultDanmakuBlockByUserFilter = new.blockByUserFilter
            }
            if (new.aiCloudLevel != old.aiCloudLevel) Prefs.defaultDanmakuAiCloudLevel = new.aiCloudLevel
        }

        private fun initDanmakuConfig() {
            val state = _danmakuState.value
            val types = state.enabledTypes
            val area = state.area
            val scale = state.scale
            val factor = state.speedFactor

            // 类型过滤：屏蔽不在 enabledTypes 中的弹幕类型
            danmakuTypeFilter.clear()
            if (!types.contains(DanmakuEntityDanmakuType.All)) {
                val allTypes = DanmakuEntityDanmakuType.entries.toMutableList()
                allTypes.remove(DanmakuEntityDanmakuType.All)
                allTypes.removeAll(types)
                allTypes
                    .mapNotNull {
                        when (it) {
                            DanmakuEntityDanmakuType.Rolling -> DanmakuItemData.DANMAKU_MODE_ROLLING
                            DanmakuEntityDanmakuType.Top -> DanmakuItemData.DANMAKU_MODE_CENTER_TOP
                            DanmakuEntityDanmakuType.Bottom -> DanmakuItemData.DANMAKU_MODE_CENTER_BOTTOM
                            else -> null
                        }
                    }.forEach { danmakuTypeFilter.addFilterItem(it) }
            }

            // 彩色弹幕过滤：仅允许白色 (0xFFFFFF) 通过
            danmakuTextColorFilter.filterColor.clear()
            if (state.blockColorful) {
                danmakuTextColorFilter.filterColor.add(0xFFFFFF)
            }

            val dataFilters =
                buildList {
                    add(danmakuTypeFilter)
                    if (state.blockColorful) add(danmakuTextColorFilter)
                    // DuplicateMergedFilter 始终挂载：
                    // - blockRepeat=true: enable=true，过滤原件(MERGED_TYPE_ORIGINAL)，展示合并弹幕(带 ×N)
                    // - blockRepeat=false: enable=false，过滤合并弹幕(MERGED_TYPE_MERGED)，展示原件
                    danmakuDuplicateFilter.enable = state.blockRepeat
                    add(danmakuDuplicateFilter)
                }

            danmakuConfig =
                danmakuConfig.copy(
                    density = 120,
                    textSizeScale = scale,
                    screenPart = area,
                    dataFilter = dataFilters,
                    rollingSpeedFactor = factor,
                )
            danmakuConfig.updateFilter()
            danmakuPlayer?.updateConfig(danmakuConfig)

            logger.info {
                "Danmaku filter chain rebuilt: " +
                    "filters=${dataFilters.map { it.javaClass.simpleName }}, " +
                    "types=$types, blockColorful=${state.blockColorful}, " +
                    "blockRepeat=${state.blockRepeat}, blockSpecial=${state.blockSpecial}, " +
                    "aiCloudLevel=${state.aiCloudLevel}"
            }
        }

        private fun updateDanmakuArea(area: Float) {
            danmakuConfig = danmakuConfig.copy(screenPart = area)
            danmakuPlayer?.updateConfig(danmakuConfig)
        }

        private fun updateDanmakuScale(scale: Float) {
            danmakuConfig = danmakuConfig.copy(textSizeScale = scale)
            danmakuPlayer?.updateConfig(danmakuConfig)
        }

        /**
         * 云端账号屏蔽规则（关键词 / 正则 / 用户）的编译结果。
         *
         * 用户规则为 type=2，接口下发的是 UID 的 crc32 hex（部分版本为原始 UID，
         * 编译时统一归一化为小写 midHash），与分段数据里的 [DanmakuData.midHash] 比对。
         */
        private class CompiledUserFilter(
            val keywords: List<String> = emptyList(),
            val regexes: List<Regex> = emptyList(),
            val blockedUserHashes: Set<String> = emptySet(),
            val invalidRegexCount: Int = 0,
        ) {
            /** 是否无任何可用规则（关键词/正则/用户均空），用于快速跳过过滤。 */
            val isEmpty: Boolean
                get() = keywords.isEmpty() && regexes.isEmpty() && blockedUserHashes.isEmpty()

            fun matches(
                midHash: String,
                content: String,
            ): Boolean {
                if (blockedUserHashes.isNotEmpty() &&
                    midHash.isNotEmpty() &&
                    midHash.lowercase() in blockedUserHashes
                ) {
                    return true
                }
                if (keywords.isNotEmpty() && keywords.any { content.contains(it) }) return true
                if (regexes.isNotEmpty() && regexes.any { it.containsMatchIn(content) }) return true
                return false
            }
        }

        /**
         * 拉取账号云端屏蔽规则（本次会话仅一次）。
         *
         * 未登录、接口异常或超时都只记录日志并降级为不使用云端规则。
         */
        private suspend fun loadUserFilter() {
            if (userFilterLoaded) return
            userFilterLoaded = true
            try {
                val rules =
                    withTimeout(USER_FILTER_TIMEOUT_MS) {
                        videoPlayRepository.getDanmakuFilterRules()
                    }
                val enabledRules = rules.filter { it.opened }
                val compiled = compileUserFilter(enabledRules)
                userFilter = compiled
                logger.info {
                    "Loaded danmaku user filter: total=${rules.size}, opened=${enabledRules.size}, " +
                        "keyword=${compiled.keywords.size}, regex=${compiled.regexes.size}, " +
                        "user=${compiled.blockedUserHashes.size}, " +
                        "invalidRegex=${compiled.invalidRegexCount}"
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Load danmaku user filter timeout after ${USER_FILTER_TIMEOUT_MS}ms" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 允许下一集重试
                userFilterLoaded = false
                logger.warn { "Load danmaku user filter failed: $e" }
            }
        }

        private fun compileUserFilter(rules: List<DanmakuFilterRule>): CompiledUserFilter {
            val keywords = mutableListOf<String>()
            val regexes = mutableListOf<Regex>()
            val userHashes = mutableSetOf<String>()
            var invalidRegex = 0
            for (rule in rules) {
                val value = rule.filter.trim()
                if (value.isEmpty()) continue
                when (rule.type) {
                    FILTER_TYPE_KEYWORD -> keywords += value
                    FILTER_TYPE_REGEX -> {
                        val pattern =
                            if (value.length > 1 && value.startsWith("/") && value.endsWith("/")) {
                                value.substring(1, value.length - 1)
                            } else {
                                value
                            }
                        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
                            .onSuccess { regexes += it }
                            .onFailure { invalidRegex++ }
                    }

                    FILTER_TYPE_USER -> userHashes += normalizeUserRule(value)
                }
            }
            return CompiledUserFilter(keywords, regexes, userHashes, invalidRegex)
        }

        /** 用户规则归一化：纯数字按原始 UID 转 crc32 hex，否则视为已下发的 midHash。 */
        private fun normalizeUserRule(value: String): String =
            if (value.all(Char::isDigit)) crc32Hex(value) else value.lowercase()

        private fun crc32Hex(value: String): String {
            val crc = CRC32()
            crc.update(value.toByteArray(Charsets.US_ASCII))
            return crc.value.toString(16).padStart(8, '0')
        }

        /** [临时调试] 打印 dm/view 下发的原始过滤配置，用于核对 AI 等级尺度与 block* 语义。 */
        private fun logDmViewFilterConfig(config: BiliDanmakuFilterConfig) {
            logger.info {
                "dmView config: valid=${config.hasValidConfig} aiSwitch=${config.aiSwitch} " +
                    "aiLevel=${config.aiLevel} aiLevelV2=${config.aiLevelV2} " +
                    "blockTop=${config.blockTop} blockScroll=${config.blockScroll} " +
                    "blockBottom=${config.blockBottom} blockColor=${config.blockColor} " +
                    "blockSpecial=${config.blockSpecial} " +
                    "reportWords=${config.reportFilterContent.size}"
            }
        }

        /** [临时调试] 打印分段的 ai_flag 评分与 weight 分布，用于核对负值编码与权重区间。 */
        private fun logSegmentAiFlagStats(
            segmentIndex: Int,
            dataList: List<DanmakuData>,
        ) {
            val scored = dataList.map { it.aiFlagScore }.filter { it != 0 }
            val weights = dataList.map { it.level }
            logger.info {
                "seg#$segmentIndex aiFlag: scored=${scored.size}/${dataList.size} " +
                    "min=${scored.minOrNull()} max=${scored.maxOrNull()} " +
                    "neg=${scored.count { it < 0 }} " +
                    "weight=${weights.minOrNull()}..${weights.maxOrNull()} " +
                    "weightNeg=${weights.count { it < 0 }}"
            }
        }

        /**
         * 将 B 站 dm/view 返回的用户过滤配置合并到本地状态并重建引擎 Filter 链。
         *
         * B 站配置作为本次会话的初始值：仅在首次加载元数据时应用一次，
         * 之后用户在本应用内的调整不会被后续切集的元数据覆盖。
         */
        private fun applyBiliFilterConfig(config: BiliDanmakuFilterConfig) {
            // 未登录时 dm/view 不返回 playerConfig/flag，得到的是全默认值的兜底对象，
            // 不应置位 synced，否则登录后永远不再同步
            if (!config.hasValidConfig) return

            // 同步标记与当前账号 mid 绑定：换账号后重新同步
            val currentMid = authRepository.mid ?: 0L
            if (Prefs.danmakuBiliFilterSynced && Prefs.danmakuBiliFilterSyncedMid == currentMid) return
            Prefs.danmakuBiliFilterSynced = true
            Prefs.danmakuBiliFilterSyncedMid = currentMid

            val current = _danmakuState.value
            // blocktop/blockscroll/blockbottom 与 B 站 playerConfig 同步
            val mergedTypes = mergeBiliDanmakuTypes(current.enabledTypes, config)
            val mergedState =
                current.copy(
                    enabledTypes = mergedTypes,
                    // 彩色/高级弹幕屏蔽：以 B 站账号设置为准作为初始值（单向 OR，只增不减）
                    blockColorful = if (config.blockColor) true else current.blockColorful,
                    blockSpecial = if (config.blockSpecial) true else current.blockSpecial,
                    // AI 云屏蔽：B 站开启且等级>0 时同步，否则保留本地设置
                    aiCloudLevel =
                        if (config.aiSwitch && (config.aiLevel > 0 || config.aiLevelV2 > 0)) {
                            (config.aiLevelV2.takeIf { it > 0 } ?: config.aiLevel).coerceIn(0, 4)
                        } else {
                            current.aiCloudLevel
                        },
                )
            if (mergedState == current) {
                logger.info { "Bili danmaku filter config applied with no state change: $config" }
                return
            }
            _danmakuState.update { mergedState }
            // 持久化 B 站同步的初始值，避免下次启动被重复覆盖
            Prefs.defaultDanmakuTypes = mergedState.enabledTypes.map { it.toDataDanmakuType() }
            Prefs.defaultDanmakuBlockColorful = mergedState.blockColorful
            Prefs.defaultDanmakuBlockSpecial = mergedState.blockSpecial
            Prefs.defaultDanmakuAiCloudLevel = mergedState.aiCloudLevel
            initDanmakuConfig()
            logger.info { "Applied Bili danmaku filter config: $config" }
        }

        /**
         * 将 B 站 playerConfig 的 blockTop/Scroll/Bottom 合并到 enabledTypes。
         *
         * B 站 block 语义 = 屏蔽该类型 → 我们需要把被屏蔽的类型从 enabledTypes 中移除。
         */
        private fun mergeBiliDanmakuTypes(
            current: List<DanmakuEntityDanmakuType>,
            config: BiliDanmakuFilterConfig,
        ): List<DanmakuEntityDanmakuType> {
            if (config.blockTop && config.blockScroll && config.blockBottom) {
                return emptyList()
            }
            if (config.blockTop || config.blockScroll || config.blockBottom) {
                // 展开 All 为具体类型再移除，否则 remove(Top/Rolling/Bottom) 不命中
                val expanded =
                    if (current.contains(DanmakuEntityDanmakuType.All)) {
                        mutableListOf(
                            DanmakuEntityDanmakuType.Rolling,
                            DanmakuEntityDanmakuType.Top,
                            DanmakuEntityDanmakuType.Bottom,
                        )
                    } else {
                        current.toMutableList()
                    }
                if (config.blockTop) expanded.remove(DanmakuEntityDanmakuType.Top)
                if (config.blockScroll) expanded.remove(DanmakuEntityDanmakuType.Rolling)
                if (config.blockBottom) expanded.remove(DanmakuEntityDanmakuType.Bottom)
                return expanded
            }
            return current
        }

        /** 将 data 层 DanmakuType 映射为 danmaku 模块的 DanmakuType。 */
        private fun DataDanmakuType.toDanmakuEntity(): DanmakuEntityDanmakuType =
            DanmakuEntityDanmakuType.entries.getOrElse(this.ordinal) { DanmakuEntityDanmakuType.All }

        /** 将 danmaku 模块的 DanmakuType 映射为 data 层 DanmakuType。 */
        private fun DanmakuEntityDanmakuType.toDataDanmakuType(): DataDanmakuType =
            DataDanmakuType.entries.getOrElse(this.ordinal) { DataDanmakuType.All }

        companion object {
            private const val LOAD_TIMEOUT_MS = 10_000L

            /**
             * 引擎时钟与视频位置的对齐阈值（毫秒）。
             *
             * 下限须高于 10Hz 进度喂入的正常采样抖动（约 200-300ms），
             * 避免正常播放中频繁 seek 引擎；上限远低于用户可感知的弹幕错位。
             */
            private const val ENGINE_TIME_JUMP_THRESHOLD_MS = 500L

            /** 分段请求超时（毫秒）。 */
            private const val SEGMENT_FETCH_TIMEOUT_MS = 10_000L

            /** dm/view 元数据不可用时的默认分段大小：6 分钟。 */
            private const val DEFAULT_SEGMENT_SIZE_MS = 360_000L

            /** 预取目标段之后的分段数。 */
            private const val DEFAULT_SEGMENT_PREFETCH_AHEAD = 1

            /** 单段最大尝试次数（首次 + 重试）。 */
            private const val SEGMENT_MAX_ATTEMPTS = 3

            /** 重试退避基数（毫秒），按指数递增：1s、2s。 */
            private const val RETRY_BACKOFF_BASE_MS = 1_000L

            /** 高级(7)/代码(8)/BAS(9) 弹幕，引擎无对应渲染器。 */
            private val SPECIAL_DANMAKU_MODES = setOf(7, 8, 9)

            /** 重复弹幕合并的时间窗（秒），窗口内相同文本的弹幕合并为一条。 */
            private const val MERGE_WINDOW_SECONDS = 5f

            /** 云端屏蔽规则类型：0=关键词 1=正则 2=用户。 */
            private const val FILTER_TYPE_KEYWORD = 0
            private const val FILTER_TYPE_REGEX = 1
            private const val FILTER_TYPE_USER = 2

            /** 云端屏蔽规则拉取超时（毫秒），超时则本次会话不使用云端规则。 */
            private const val USER_FILTER_TIMEOUT_MS = 3_000L
        }
    }
