package dev.frost819.newbv.biliapi.http

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [buildFeedDislikeQuery] 的单元测试。
 *
 * 验证负反馈上报 Query 参数的组装：参数名与取值的映射、
 * reason 取值（1 = 不感兴趣，4 = 不想看此UP主）以及 aid/feedback_id 的字符串化。
 */
class FeedDislikeQueryTest {
    @Test
    fun `buildFeedDislikeQuery maps aid reason feedback_id in request order`() {
        val params = buildFeedDislikeQuery(aid = 993403941L, reason = 1, feedbackId = 42)
        val expected =
            listOf(
                "aid" to "993403941",
                "reason" to "1",
                "feedback_id" to "42",
            )

        assertThat(params).containsExactlyElementsIn(expected).inOrder()
    }

    @Test
    fun `buildFeedDislikeQuery keeps not-interested reason 1`() {
        val params = buildFeedDislikeQuery(aid = 1L, reason = 1, feedbackId = 7)

        assertThat(params.first { it.first == "reason" }.second).isEqualTo("1")
    }

    @Test
    fun `buildFeedDislikeQuery keeps dislike-up reason 4`() {
        val params = buildFeedDislikeQuery(aid = 1L, reason = 4, feedbackId = 7)

        assertThat(params.first { it.first == "reason" }.second).isEqualTo("4")
    }
}
