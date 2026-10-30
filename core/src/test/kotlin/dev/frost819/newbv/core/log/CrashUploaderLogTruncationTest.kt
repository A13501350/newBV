package dev.frost819.newbv.core.log

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * [CrashUploader.truncateLogcatForUpload] 的单元测试。
 *
 * 验证上传日志的行数/字节上限，以及截断后仍保留崩溃现场附近的尾部内容。
 */
class CrashUploaderLogTruncationTest {
    @Test
    fun `short logcat is returned unchanged`() {
        val text = "line 1\nline 2\nline 3"

        val result = CrashUploader.truncateLogcatForUpload(text)

        assertThat(result).isEqualTo(text)
    }

    @Test
    fun `limits utf8 bytes and keeps the tail`() {
        val line = "崩溃日志".repeat(128)
        val text = (1..1_000).joinToString("\n") { line }

        val result = CrashUploader.truncateLogcatForUpload(text)

        assertThat(result.toByteArray(Charsets.UTF_8).size).isAtMost(CrashUploader.MAX_LOG_BYTES)
        assertThat(result.endsWith("崩溃日志")).isTrue()
    }
}
