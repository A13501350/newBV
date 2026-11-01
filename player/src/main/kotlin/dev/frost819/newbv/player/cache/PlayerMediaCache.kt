package dev.frost819.newbv.player.cache

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.Locale

/**
 * 播放媒体磁盘缓存（Media3 [SimpleCache] 边播边缓存）。
 *
 * 进程级单例（由 `PlayerModule` 提供），承载以下职责：
 * - 唯一实例：同一目录同时存在两个 [SimpleCache] 会因文件锁直接抛
 *   "Another SimpleCache instance uses the folder"，因此必须收敛到单实例，
 *   由 [currentCache] 统一管理创建与释放。
 * - 容量档位：容量来自 [capacityMb] 提供者（默认读 `Prefs.playbackCacheMb`，
 *   0 = 关闭），用 [LeastRecentlyUsedCacheEvictor] 淘汰最久未用的分片。
 *   档位在每次创建播放器时惰性读取，变更在下一个播放会话生效（LRU 容量在
 *   [SimpleCache] 创建时固定，无法原地调整）。
 * - key 规范化：B 站播放地址的 query 全部是会话级签名/过期/路由参数（每次请求都变），
 *   直接用 URL 做 key 命中率约等于零。[normalizeCacheKey] 把 key 收敛为资源路径，
 *   详见其 KDoc。
 * - 容错：缓存初始化失败（目录不可写等）时降级为不缓存，不拖垮播放；
 *   读缓存出错时由 [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR] 自动回退上游。
 *
 * @param cacheDir 缓存目录（位于应用 cacheDir 下，见 [CACHE_DIR_NAME]）。
 * @param databaseProvider 分片索引数据库提供者（与图片缓存等共用磁盘时互不影响）。
 * @param capacityMb 容量档位（MB）提供者，0 = 关闭；每次创建播放器时读取最新值。
 */
@OptIn(UnstableApi::class)
class PlayerMediaCache(
    private val cacheDir: File,
    private val databaseProvider: DatabaseProvider,
    private val capacityMb: () -> Int,
) {
    /**
     * 从 Android [Context] 构建（[PlayerModule] 使用）。
     *
     * @param context 任意 Context（内部取 applicationContext 语义的 cacheDir）。
     * @param capacityMb 容量档位（MB）提供者，0 = 关闭。
     */
    constructor(
        context: Context,
        capacityMb: () -> Int,
    ) : this(
        cacheDir = File(context.cacheDir, CACHE_DIR_NAME),
        databaseProvider = StandaloneDatabaseProvider(context),
        capacityMb = capacityMb,
    )

    /** 当前持有的 [SimpleCache] 实例（惰性创建，容量变化时重建）。 */
    private var cache: SimpleCache? = null

    /** 当前实例对应的容量（字节），用于判断档位是否变化。 */
    private var cacheCapacityBytes = Long.MIN_VALUE

    /**
     * 用缓存数据源工厂包裹上游工厂。
     *
     * 缓存开启（档位 > 0）时返回 [CacheDataSource] 工厂：读命中走磁盘、未命中回源
     * 并写入缓存；缓存关闭或初始化失败时原样返回 [upstream]，播放行为不变。
     *
     * @param upstream 上游数据源工厂（网络加载）。
     * @return 包裹后的数据源工厂。
     */
    fun wrap(upstream: DataSource.Factory): DataSource.Factory {
        val simpleCache = currentCache() ?: return upstream
        return CacheDataSource
            .Factory()
            .setCache(simpleCache)
            .setCacheKeyFactory(CACHE_KEY_FACTORY)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 取当前可用的 [SimpleCache]，按需创建 / 重建。
     *
     * 首次创建涉及磁盘与数据库 I/O；容量档位变化时先释放旧实例再按新容量重建
     * （正在使用旧实例的播放会话会因读缓存失败自动回退上游，不中断播放）。
     */
    @Synchronized
    private fun currentCache(): SimpleCache? {
        val mb = capacityMb().coerceAtLeast(0)
        if (mb <= 0) {
            // 关闭：释放实例（同目录双实例会锁冲突，重建前也必须先释放）
            releaseHeldCache()
            return null
        }
        val capacityBytes = mb.toLong() * BYTES_PER_MB
        if (cache != null && cacheCapacityBytes == capacityBytes) return cache
        releaseHeldCache()
        cacheCapacityBytes = capacityBytes
        // SimpleCache 初始化可能因目录不可写等磁盘问题失败；降级为不缓存，
        // 不能让播放器跟着挂
        cache =
            runCatching {
                SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(capacityBytes), databaseProvider)
            }.getOrNull()
        return cache
    }

    /** 释放当前持有的实例（若存在）。 */
    private fun releaseHeldCache() {
        val held = cache ?: return
        cache = null
        runCatching { held.release() }
    }

    companion object {
        /** 播放媒体缓存目录名（位于应用 cacheDir 下，供 CacheManager 并入清理逻辑）。 */
        const val CACHE_DIR_NAME = "player_media_cache"

        private const val BYTES_PER_MB: Long = 1024L * 1024L

        private const val SCHEME_SEPARATOR = "://"

        /**
         * B 站媒体 CDN 域名后缀（不区分大小写，匹配完整域名或子域）。
         *
         * 覆盖 upos（upos-sz-*.bilivideo.com）、mcdn（xy*.mcdn.bilivideo.com）、
         * PCDN（*.szbdyd.com）、海外（*.akamaized.net）等已知家族；未列出的
         * 第三方 PCDN 域名靠 [isBilibiliMediaTarget] 的内嵌路径识别兜底。
         */
        private val CDN_HOST_SUFFIXES =
            listOf("bilivideo.com", "bilivideo.cn", "hdslb.com", "akamaized.net", "szbdyd.com")

        /**
         * 已知会话级 query 参数（非 B 站域名的兜底剥离清单，不区分大小写）。
         *
         * B 站 CDN 域名直接丢弃全部 query，不经过此清单；此清单只为
         * 未经识别的代理域名尽量命中（其余参数保持原样，避免误伤资源标识）。
         */
        private val VOLATILE_QUERY_PARAMS = setOf("expires", "deadline", "upsig", "uparams")

        /** 缓存 key 工厂：对 DataSpec 的 URI 做规范化（见 [normalizeCacheKey]）。 */
        private val CACHE_KEY_FACTORY =
            CacheKeyFactory { dataSpec -> normalizeCacheKey(dataSpec.uri.toString()) }

        /**
         * 规范化缓存 key（纯函数，无 Android 依赖，便于 JVM 单测）。
         *
         * B 站播放地址的 query（`e`/`oi`/`os`/`trid`/`deadline`/`upsig`/`uparams`/
         * `expires` 等）全部是会话级签名、过期与路由参数，每次请求都变，直接当
         * key 会让命中率归零；而同一路径在 baseUrl 与 backupUrl 之间共享（仅 host
         * 不同）。因此规则为：
         * 1. 始终剥离 fragment；
         * 2. 指向 B 站媒体资源（host 或首段路径命中 [CDN_HOST_SUFFIXES]）时，
         *    丢弃 scheme + host + 全部 query，key = 资源路径 —— 跨 CDN 源与
         *    baseUrl/backupUrl 天然共享 key；
         * 3. 其余 URL 原样保留，仅剥离 [VOLATILE_QUERY_PARAMS] 中的已知会话参数
         *    （大小写不敏感）。
         *
         * @param url 原始播放地址。
         * @return 规范化后的缓存 key。
         */
        fun normalizeCacheKey(url: String): String {
            val withoutFragment = url.substringBefore('#')
            val schemeEnd = withoutFragment.indexOf(SCHEME_SEPARATOR)
            if (schemeEnd < 0) return withoutFragment
            val authorityStart = schemeEnd + SCHEME_SEPARATOR.length
            val pathStart = withoutFragment.indexOf('/', authorityStart)
            val authority =
                if (pathStart < 0) {
                    withoutFragment.substring(authorityStart)
                } else {
                    withoutFragment.substring(authorityStart, pathStart)
                }
            val pathAndQuery = if (pathStart < 0) "" else withoutFragment.substring(pathStart)

            return if (isBilibiliMediaTarget(authority, pathAndQuery)) {
                pathAndQuery.substringBefore('?').ifEmpty { withoutFragment }
            } else {
                stripVolatileQueryParams(withoutFragment)
            }
        }

        /**
         * 判断 URL 是否指向 B 站媒体资源。
         *
         * 两种命中方式：
         * 1. host 本身是 B 站 CDN 域名（如 `upos-sz-mirror08c.bilivideo.com`、
         *    `xy601x212x16xy.mcdn.bilivideo.com:4483`）；
         * 2. 第三方 PCDN 把源站 host 编码在首段路径里
         *    （如 `https://xy*.maidoudou.top/upos-dm-mdc-va1.mcdn.bilivideo.com:443/v1/...`）。
         *
         * @param authority URL 的 authority（host[:port]）。
         * @param pathAndQuery URL 的路径与 query 部分。
         * @return 是否为 B 站媒体资源。
         */
        private fun isBilibiliMediaTarget(
            authority: String,
            pathAndQuery: String,
        ): Boolean {
            val host = authority.substringBefore(':').lowercase(Locale.US)
            if (matchesCdnSuffix(host)) return true
            val firstSegment =
                pathAndQuery
                    .substringBefore('?')
                    .removePrefix("/")
                    .substringBefore('/')
                    .substringBefore(':')
                    .lowercase(Locale.US)
            return matchesCdnSuffix(firstSegment)
        }

        /**
         * 判断 host 是否为 B 站 CDN 域名（完整匹配后缀或其后缀为子域名）。
         *
         * @param host 已转小写的 host。
         * @return 是否命中已知 CDN 后缀。
         */
        private fun matchesCdnSuffix(host: String): Boolean =
            CDN_HOST_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }

        /**
         * 兜底剥离已知会话级 query 参数（不区分大小写），其余参数原样保留；
         * 剥离后无剩余参数时不保留悬空的 `?`。
         *
         * @param url 已剥离 fragment 的完整 URL。
         * @return 剥离后的 URL。
         */
        private fun stripVolatileQueryParams(url: String): String {
            val queryStart = url.indexOf('?')
            if (queryStart < 0) return url
            val base = url.substring(0, queryStart)
            val retained =
                url
                    .substring(queryStart + 1)
                    .split('&')
                    .filter { it.isNotEmpty() }
                    .filterNot { pair ->
                        pair.substringBefore('=').lowercase(Locale.US) in VOLATILE_QUERY_PARAMS
                    }
            return if (retained.isEmpty()) base else "$base?${retained.joinToString("&")}"
        }
    }
}
