package dev.frost819.newbv.biliapi.entity.danmaku

import com.google.common.truth.Truth.assertThat
import dev.frost819.newbv.biliapi.http.entity.BiliResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

/**
 * [DanmakuFilterRule] 与 midHash 编码函数的单元测试。
 *
 * 验证 x/dm/filter/user 响应的容错解析（缺字段/未知字段/未知 type）、
 * CRC32 midHash 编码（对照 MyTVB `DanmakuUserFilter.midHashOfMid` 算法）
 * 与用户规则归一化。
 */
class DanmakuFilterRuleTest {
    private val json =
        Json {
            coerceInputValues = true
            ignoreUnknownKeys = true
        }

    /** 按实际调用路径反序列化 x/dm/filter/user 响应并取出规则列表。 */
    private fun decodeRules(body: String): List<DanmakuFilterRule> =
        json.decodeFromString<BiliResponse<DanmakuFilterListData>>(body).getResponseData().rule

    // === 响应解析 ===

    @Test
    fun `parses full rule with all fields`() {
        // Given
        val body =
            """
            {
              "code": 0,
              "message": "0",
              "ttl": 1,
              "data": {
                "rule": [
                  {
                    "id": 8344,
                    "type": 0,
                    "filter": "2333",
                    "comment": "关键字",
                    "act": 1,
                    "uid": 2,
                    "created": 1585108795,
                    "modified": 1585108795
                  }
                ]
              }
            }
            """.trimIndent()

        // When
        val rules = decodeRules(body)

        // Then
        val rule = rules.single()
        assertThat(rule.id).isEqualTo(8344L)
        assertThat(rule.type).isEqualTo(DanmakuFilterRule.TYPE_TEXT)
        assertThat(rule.filter).isEqualTo("2333")
        assertThat(rule.comment).isEqualTo("关键字")
        assertThat(rule.act).isEqualTo(1)
        assertThat(rule.uid).isEqualTo(2L)
        assertThat(rule.created).isEqualTo(1585108795L)
        assertThat(rule.modified).isEqualTo(1585108795L)
    }

    @Test
    fun `parses rule with missing fields via defaults`() {
        // Given：服务端可能省略部分字段
        val body = """{"code":0,"message":"0","data":{"rule":[{"type":1,"filter":"^spam"}]}}"""

        // When
        val rules = decodeRules(body)

        // Then
        val rule = rules.single()
        assertThat(rule.id).isEqualTo(0L)
        assertThat(rule.type).isEqualTo(DanmakuFilterRule.TYPE_REGEX)
        assertThat(rule.filter).isEqualTo("^spam")
        assertThat(rule.comment).isNull()
        assertThat(rule.act).isEqualTo(0)
    }

    @Test
    fun `parses rule tolerating unknown fields and unknown type`() {
        // Given：未知字段忽略；未知 type 不匹配任何编译分支但解析不失败
        val body =
            """
            {
              "code": 0,
              "message": "0",
              "data": {
                "rule": [
                  {"type": 2, "filter": "12345678901", "extra_field": "ignored"},
                  {"type": 99, "filter": "unknown", "reason": "future type"}
                ]
              }
            }
            """.trimIndent()

        // When
        val rules = decodeRules(body)

        // Then
        assertThat(rules).hasSize(2)
        assertThat(rules[0].type).isEqualTo(DanmakuFilterRule.TYPE_USER)
        assertThat(rules[1].type).isEqualTo(99)
    }

    @Test
    fun `parses empty rule list`() {
        // Given：未配置任何屏蔽规则时 data.rule 可能为空数组或缺省
        assertThat(decodeRules("""{"code":0,"message":"0","data":{}}""")).isEmpty()
        assertThat(decodeRules("""{"code":0,"message":"0"}""")).isEmpty()
    }

    // === CRC32 midHash 编码 ===

    @Test
    fun `midHashOfMid matches CRC32 check value`() {
        // "123456789" 是 CRC-32 的标准校验值 CBF43926
        assertThat(midHashOfMid(123456789L)).isEqualTo("cbf43926")
    }

    @Test
    fun `midHashOfMid pads leading zeros and lowercases`() {
        // CRC32("2") = 0x1AD5BE0D；CRC32("10000") = 0x77BC7D45
        assertThat(midHashOfMid(2L)).isEqualTo("1ad5be0d")
        assertThat(midHashOfMid(10000L)).isEqualTo("77bc7d45")
    }

    // === 用户规则归一化 ===

    @Test
    fun `normalizeUserRuleToMidHash converts long numeric mid via CRC32`() {
        // 纯数字且长度 > 8 视为 mid，转 CRC32 hash
        assertThat(normalizeUserRuleToMidHash("12345678901")).isEqualTo(midHashOfMid(12345678901L))
        assertThat(normalizeUserRuleToMidHash(" 12345678901 ")).isEqualTo(midHashOfMid(12345678901L))
    }

    @Test
    fun `normalizeUserRuleToMidHash pads short numeric and hex hash`() {
        // 1-8 位按 hex 补前导零（可能丢失前导零的历史数据）
        assertThat(normalizeUserRuleToMidHash("10000")).isEqualTo("00010000")
        assertThat(normalizeUserRuleToMidHash("ABC123")).isEqualTo("00abc123")
        assertThat(normalizeUserRuleToMidHash(" 23333333 ")).isEqualTo("23333333")
    }

    @Test
    fun `normalizeUserRuleToMidHash returns null for invalid rule`() {
        assertThat(normalizeUserRuleToMidHash("")).isNull()
        assertThat(normalizeUserRuleToMidHash("   ")).isNull()
        assertThat(normalizeUserRuleToMidHash("xyz!")).isNull()
        // 20 位纯数字超出 Long 范围，无法转 mid
        assertThat(normalizeUserRuleToMidHash("99999999999999999999")).isNull()
        // 超出 20 位的纯数字串不匹配任何格式
        assertThat(normalizeUserRuleToMidHash("99999999999999999999999")).isNull()
    }
}
