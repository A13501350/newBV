package dev.frost819.newbv.biliapi.repositories

import dev.frost819.newbv.biliapi.entity.danmaku.DanmakuFilterRule
import dev.frost819.newbv.biliapi.entity.danmaku.normalizeUserRuleToMidHash
import dev.frost819.newbv.biliapi.http.BiliHttpApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 编译后的弹幕云屏蔽规则集（可直接应用于弹幕过滤）。
 *
 * - [keywords] 文字规则：弹幕内容包含任一关键字即屏蔽
 * - [regexes] 正则规则：弹幕内容命中任一正则即屏蔽（非法正则已在编译时剔除）
 * - [blockedUserMidHashes] 用户规则：弹幕作者 midHash（CRC32(mid) 的 8 位小写 hex）命中即屏蔽
 *
 * @property keywords 关键字列表（去重）
 * @property regexes 编译后的正则列表（按 pattern 去重）
 * @property blockedUserMidHashes 归一化后的用户 midHash 集合
 */
data class DanmakuShieldRules(
    val keywords: List<String> = emptyList(),
    val regexes: List<Regex> = emptyList(),
    val blockedUserMidHashes: Set<String> = emptySet(),
) {
    /** 是否没有任何规则。 */
    val isEmpty: Boolean
        get() = keywords.isEmpty() && regexes.isEmpty() && blockedUserMidHashes.isEmpty()

    companion object {
        /** 空规则集：未登录、无规则或请求失败降级时使用。 */
        val EMPTY = DanmakuShieldRules()
    }
}

/**
 * 将原始云屏蔽规则编译为 [DanmakuShieldRules]。
 *
 * 容错处理：空白 [DanmakuFilterRule.filter] 跳过、非法正则跳过、
 * 无法归一化的用户规则跳过；未知 type 不匹配任何分支直接忽略。
 * 所有规则（不区分 [DanmakuFilterRule.act]）均按屏蔽处理，
 * 与 MyTVB 已验证的 x/dm/filter/user 消费行为一致。
 *
 * @param rules 服务端返回的原始规则列表
 * @return 编译后的规则集（关键字/正则去重）
 */
internal fun compileDanmakuShieldRules(rules: List<DanmakuFilterRule>): DanmakuShieldRules {
    val keywords = mutableListOf<String>()
    val regexes = mutableListOf<Regex>()
    val userHashes = mutableSetOf<String>()
    rules.forEach { rule ->
        val raw = rule.filter.trim()
        if (raw.isEmpty()) return@forEach
        when (rule.type) {
            DanmakuFilterRule.TYPE_TEXT -> keywords.add(raw)

            DanmakuFilterRule.TYPE_REGEX ->
                // 非法正则（PatternSyntaxException）直接跳过，不影响其余规则
                runCatching { Regex(raw) }.getOrNull()?.let { regexes.add(it) }

            DanmakuFilterRule.TYPE_USER -> normalizeUserRuleToMidHash(raw)?.let { userHashes.add(it) }
        }
    }
    return DanmakuShieldRules(
        keywords = keywords.distinct(),
        regexes = regexes.distinctBy { it.pattern },
        blockedUserMidHashes = userHashes,
    )
}

/**
 * 弹幕云屏蔽仓库：拉取并缓存 B 站账号同步的弹幕屏蔽规则（x/dm/filter/user，需登录）。
 *
 * - 内存缓存绑定登录态（sessData）并带 TTL，换号/重登录自动失效；
 * - [getRules] 失败时抛出异常、保留旧缓存，由调用方决定降级策略
 *   （弹幕场景为静默跳过，不阻塞弹幕加载）；
 * - 未登录（sessData 为空）直接返回 [DanmakuShieldRules.EMPTY]，不发起请求。
 */
class DanmakuShieldRepository {
    private var cache: DanmakuShieldRules? = null
    private var cacheFetchedAtMs = 0L
    private var cacheSessData: String? = null
    private val mutex = Mutex()

    /**
     * 获取当前账号的云屏蔽规则（命中缓存时零网络开销）。
     *
     * @param forceRefresh true 时绕过缓存强制重新拉取（菜单手动刷新入口）
     * @return 编译后的规则集；未登录时为 [DanmakuShieldRules.EMPTY]
     * @throws Exception 网络失败或响应 code 非 0（如 -101 登录失效、-352 风控）时抛出
     */
    suspend fun getRules(forceRefresh: Boolean = false): DanmakuShieldRules =
        mutex.withLock {
            val sessData = BiliHttpApi.sessData
            if (sessData.isEmpty()) return DanmakuShieldRules.EMPTY
            val cacheValid =
                cache != null &&
                    cacheSessData == sessData &&
                    System.currentTimeMillis() - cacheFetchedAtMs < CACHE_TTL_MS
            if (!forceRefresh && cacheValid && cache != null) return cache ?: DanmakuShieldRules.EMPTY

            val rules = BiliHttpApi.getDanmakuFilterUser().getResponseData().rule
            val compiled = compileDanmakuShieldRules(rules)
            cache = compiled
            cacheSessData = sessData
            cacheFetchedAtMs = System.currentTimeMillis()
            compiled
        }

    private companion object {
        /** 缓存有效期（毫秒），规则低频变化，与 MyTVB 实现一致取 10 分钟。 */
        private const val CACHE_TTL_MS = 10L * 60 * 1000
    }
}
