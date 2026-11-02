package dev.frost819.newbv.biliapi.entity.danmaku

import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.zip.CRC32

/**
 * 弹幕云屏蔽规则列表（x/dm/filter/user 响应的 data 字段）。
 *
 * 对应端点：GET /x/dm/filter/user（需登录），返回当前 B 站账号同步的弹幕屏蔽规则。
 *
 * @property rule 规则列表
 */
@Serializable
data class DanmakuFilterListData(
    val rule: List<DanmakuFilterRule> = emptyList(),
)

/**
 * 弹幕云屏蔽规则（B 站账号同步的单条屏蔽规则）。
 *
 * 字段全部带默认值：服务端可能省略部分字段，配合全局
 * `ignoreUnknownKeys` + `coerceInputValues` 实现容错解析。
 * `act` 字段仅保留不参与判定：该端点返回的规则均按屏蔽处理（与 MyTVB 已验证行为一致）。
 *
 * @property id 规则 id
 * @property type 规则类型：[TYPE_TEXT] 文字 / [TYPE_REGEX] 正则 / [TYPE_USER] 用户
 * @property filter 规则内容：关键字 / 正则表达式 / 用户 mid 或 midHash
 * @property comment 规则备注（可空）
 * @property act 规则动作（保留字段）
 * @property uid 规则所属用户 mid
 * @property created 创建时间戳（秒）
 * @property modified 修改时间戳（秒）
 */
@Serializable
data class DanmakuFilterRule(
    val id: Long = 0L,
    val type: Int = TYPE_TEXT,
    val filter: String = "",
    val comment: String? = null,
    val act: Int = 0,
    val uid: Long = 0L,
    val created: Long = 0L,
    val modified: Long = 0L,
) {
    companion object {
        /** 规则类型：文字（关键字）。 */
        const val TYPE_TEXT = 0

        /** 规则类型：正则表达式。 */
        const val TYPE_REGEX = 1

        /** 规则类型：用户。 */
        const val TYPE_USER = 2
    }
}

private val USER_RULE_MID_REGEX = Regex("^[0-9]{1,20}$")
private val USER_RULE_MID_HASH_REGEX = Regex("^[0-9a-fA-F]{1,8}$")

/**
 * 将用户 mid 转换为与弹幕 proto `midHash` 同口径的 CRC32 hex（8 位小写，补前导零）。
 *
 * 算法对照 MyTVB `DanmakuUserFilter.midHashOfMid`：
 * 对 mid 的十进制字符串（UTF-8 字节）计算标准 CRC32，再以 8 位小写 hex 表示。
 *
 * @param mid 用户 mid
 * @return 8 位小写 hex 字符串，如 `midHashOfMid(123456789L)` == `"cbf43926"`
 */
fun midHashOfMid(mid: Long): String {
    val crc = CRC32()
    crc.update(mid.toString().toByteArray(Charsets.UTF_8))
    return crc.value.toString(16).padStart(8, '0')
}

/**
 * 归一化云屏蔽用户规则（type=2）的 filter 内容为 8 位小写 midHash。
 *
 * 对照 MyTVB `DanmakuUserFilter.normalizeMidHashRule`，兼容两种历史格式：
 * - 纯数字且长度 > 8：视为 mid，转 CRC32 hash；
 * - 1-8 位数字或 hex（可能丢失前导零）：按 hex 小写补零。
 *
 * @param raw 规则原始内容
 * @return 归一化后的 8 位小写 midHash；空白或格式不符返回 null
 */
fun normalizeUserRuleToMidHash(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return when {
        USER_RULE_MID_REGEX.matches(trimmed) ->
            if (trimmed.length > 8) {
                val mid = trimmed.toLongOrNull()?.takeIf { it > 0L } ?: return null
                midHashOfMid(mid)
            } else {
                trimmed.lowercase(Locale.US).padStart(8, '0')
            }

        USER_RULE_MID_HASH_REGEX.matches(trimmed) -> trimmed.lowercase(Locale.US).padStart(8, '0')

        else -> null
    }
}
