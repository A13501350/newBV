package dev.frost819.newbv.core.log

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 崩溃日志上传器。
 *
 * 将崩溃日志转为结构化 JSON 并上传到 Cloudflare Worker（R2 Storage）。
 * 采用双重保障策略：
 * 1. 崩溃时 best-effort 同步上传（应用即将退出，可能失败）
 * 2. 下次启动时检查未上传的 JSON 文件并异步上传
 *
 * 上传失败静默忽略，不影响用户体验。每个 JSON 文件最多尝试 3 次，
 * 超过后删除，避免长期占用存储或反复请求。
 *
 * JSON 文件存储在 `filesDir/crash_logs/` 下，命名为 `logs_crash_*.json`。
 * 上传成功后删除 JSON 文件；`.log` 文本日志保留供本地查看。
 * 上传时 logcat 只保留尾部 1 MB，本地 `.log` 仍为完整内容。
 *
 * @param context 应用 Context（用于获取文件目录和包信息）
 * @param authToken Worker 认证 token（为空则不上传）
 * @param isEnabled 上传开关提供者，每次上传前读取最新值（绑定用户设置），切换后无需重启即可生效
 */
class CrashUploader(
    private val context: Context,
    private val authToken: String,
    private val isEnabled: () -> Boolean,
) {
    private val logger = Loggers.get("CrashUploader")

    /**
     * 是否启用上传。
     *
     * 每次调用时读取最新用户设置，设置页切换开关后立即生效。
     */
    private fun uploadEnabled(): Boolean = isEnabled()

    companion object {
        private const val WORKER_URL = "https://crash-log.frost819.site"
        private const val UPLOAD_PATH = "/api/crash/upload"
        private const val UPLOAD_TIMEOUT_MS = 5_000
        private const val CRASH_JSON_PREFIX = "logs_crash"
        private const val CRASH_JSON_SUFFIX = ".json"
        private const val UPLOAD_ATTEMPTS_KEY = "_uploadAttempts"

        /** 单个 JSON 文件最多上传尝试次数，超过后删除，避免无限重试。 */
        private const val MAX_UPLOAD_ATTEMPTS = 3

        /** 上传时 logcat 最多保留 UTF-8 字节数（完整日志仍保存在本地 `.log`）。 */
        internal const val MAX_LOG_BYTES = 1024 * 1024

        /**
         * 截取上传用的 logcat 尾部。
         *
         * 按 [MAX_LOG_BYTES] 截断字节，只保留崩溃现场附近的尾部内容。
         *
         * @param text 完整 logcat 文本
         * @return 适合上传的尾部片段
         */
        internal fun truncateLogcatForUpload(text: String): String {
            if (text.isEmpty()) return text

            val raw = text.toByteArray(Charsets.UTF_8)
            if (raw.size <= MAX_LOG_BYTES) return text

            // 从尾部截取字节时跳过 UTF-8 续字节，避免把多字节字符切成乱码
            var start = raw.size - MAX_LOG_BYTES
            while (start < raw.size && (raw[start].toInt() and 0xC0) == 0x80) {
                start++
            }
            return String(raw, start, raw.size - start, Charsets.UTF_8)
        }
    }

    /** 单次上传尝试的结果。 */
    private enum class UploadAttemptResult {
        /** 上传成功。 */
        Success,

        /** 上传失败但未达上限，保留文件等待下次重试。 */
        Retry,

        /** 达到上限或文件损坏，已删除。 */
        Dropped,
    }

    /**
     * 崩溃时同步上传（best-effort）。
     *
     * 将崩溃数据转为 JSON 保存到文件，然后尝试同步上传。
     * 如果上传失败，JSON 文件保留在磁盘上，下次启动时重试（最多 3 次）。
     *
     * @param deviceInfo 设备信息
     * @param thread 崩溃线程
     * @param throwable 异常
     * @param logcatText 崩溃前的 logcat 输出
     */
    fun uploadCrash(
        deviceInfo: DeviceInfo,
        thread: Thread,
        throwable: Throwable,
        logcatText: String,
    ) {
        if (!uploadEnabled() || authToken.isBlank()) return

        val json = buildCrashJson(deviceInfo, thread, throwable, logcatText)
        val jsonFile = saveJsonFile(json)

        when (attemptUpload(jsonFile)) {
            UploadAttemptResult.Success -> logger.info { "Crash log uploaded successfully" }
            UploadAttemptResult.Retry -> logger.warn { "Crash log upload failed, will retry on next launch" }
            UploadAttemptResult.Dropped -> logger.warn { "Crash log dropped after $MAX_UPLOAD_ATTEMPTS attempts" }
        }
    }

    /**
     * 上传所有未发送的崩溃日志。
     *
     * 在应用启动时调用，扫描 `crash_logs/` 目录下的 `.json` 文件，
     * 逐个上传，成功后删除；失败累计 3 次后删除。
     */
    fun uploadPendingCrashLogs() {
        if (!uploadEnabled() || authToken.isBlank()) return

        val logDir = File(context.filesDir, CrashHandler.LOG_DIR)
        val jsonFiles =
            logDir
                .listFiles { file ->
                    file.name.startsWith(CRASH_JSON_PREFIX) &&
                        file.name.endsWith(CRASH_JSON_SUFFIX)
                }?.sortedBy { it.lastModified() } ?: return

        if (jsonFiles.isEmpty()) return

        logger.info { "Found ${jsonFiles.size} unsent crash log(s)" }

        for (file in jsonFiles) {
            when (attemptUpload(file)) {
                UploadAttemptResult.Success -> logger.info { "Uploaded pending crash log: ${file.name}" }
                UploadAttemptResult.Retry -> {
                    logger.warn { "Failed to upload: ${file.name}, will retry later" }
                    break
                }
                UploadAttemptResult.Dropped -> Unit
            }
        }
    }

    /**
     * 尝试上传单个 JSON 文件，并维护重试次数。
     *
     * 次数记录在 JSON 的 `_uploadAttempts` 字段中（只在本地保存，发送前移除）。
     * 达到 [MAX_UPLOAD_ATTEMPTS] 后删除文件；文件损坏也直接删除。
     *
     * @param file 待上传的崩溃 JSON 文件
     * @return 上传结果
     */
    private fun attemptUpload(file: File): UploadAttemptResult {
        val raw = runCatching { file.readText() }.getOrNull()
        val json = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null) {
            file.delete()
            logger.warn { "Dropped invalid crash log: ${file.name}" }
            return UploadAttemptResult.Dropped
        }

        val attempts = json.optInt(UPLOAD_ATTEMPTS_KEY, 0)
        if (attempts >= MAX_UPLOAD_ATTEMPTS) {
            file.delete()
            logger.warn { "Dropped crash log after $attempts failed attempts: ${file.name}" }
            return UploadAttemptResult.Dropped
        }

        val nextAttempts = attempts + 1
        json.put(UPLOAD_ATTEMPTS_KEY, nextAttempts)
        runCatching { file.writeText(json.toString()) }
            .onFailure { logger.warn { "Failed to persist retry count for ${file.name}" } }

        // 重试计数仅用于本地，不上传到服务端
        val payload = JSONObject(json.toString()).apply { remove(UPLOAD_ATTEMPTS_KEY) }
        if (uploadJson(payload.toString())) {
            file.delete()
            return UploadAttemptResult.Success
        }

        if (nextAttempts >= MAX_UPLOAD_ATTEMPTS) {
            file.delete()
            logger.warn { "Dropped crash log after $nextAttempts failed attempts: ${file.name}" }
            return UploadAttemptResult.Dropped
        }
        return UploadAttemptResult.Retry
    }

    /**
     * 构建 CrashUploader 是否可用（用户开关开启 + token 非空）。
     */
    fun canUpload(): Boolean = uploadEnabled() && authToken.isNotBlank()

    // -----------------------------------------------------------------------
    // JSON 构建
    // -----------------------------------------------------------------------

    /**
     * 将崩溃数据构建为结构化 JSON 字符串。
     */
    private fun buildCrashJson(
        deviceInfo: DeviceInfo,
        thread: Thread,
        throwable: Throwable,
        logcatText: String,
    ): String {
        val json = JSONObject()

        json.put("id", UUID.randomUUID().toString())
        json.put("versionCode", deviceInfo.appVersionCode)
        json.put("versionName", deviceInfo.appVersion)
        // X 模式需要 API 24+；用 Z（+0800）再插入冒号，所有版本都能得到 +08:00
        val rawCrashTime = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault()).format(Date())
        json.put("crashTime", rawCrashTime.dropLast(2) + ":" + rawCrashTime.takeLast(2))

        val device = JSONObject()
        device.put("manufacturer", deviceInfo.manufacturer)
        device.put("model", deviceInfo.model)
        device.put("androidVersion", deviceInfo.androidVersion)
        device.put("sdk", deviceInfo.androidSdk)
        device.put("device", deviceInfo.device)
        json.put("deviceInfo", device)

        val exception = JSONObject()
        exception.put("thread", thread.name)
        exception.put("type", throwable.javaClass.name)
        exception.put("message", throwable.message ?: "")

        val stackTrace = StringBuilder()
        stackTrace.append("${throwable.javaClass.name}: ${throwable.message}")
        stackTrace.append("\n")
        throwable.stackTrace.forEach {
            stackTrace.append("    at $it\n")
        }
        throwable.cause?.let { cause ->
            stackTrace.append("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
            cause.stackTrace.forEach {
                stackTrace.append("    at $it\n")
            }
        }
        exception.put("stackTrace", stackTrace.toString().trimEnd())
        json.put("exception", exception)

        json.put("logcat", truncateLogcatForUpload(logcatText))

        return json.toString()
    }

    /**
     * 将 JSON 保存到 `crash_logs/` 目录下的文件。
     *
     * @return 保存的文件
     */
    private fun saveJsonFile(json: String): File {
        val logDir = File(context.filesDir, CrashHandler.LOG_DIR)
        if (!logDir.exists()) logDir.mkdirs()

        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.getDefault()).format(Date())
        val jsonFile = File(logDir, "${CrashHandler.CRASH_LOG_PREFIX}_$timestamp$CRASH_JSON_SUFFIX")
        jsonFile.writeText(json)
        return jsonFile
    }

    // -----------------------------------------------------------------------
    // HTTP 上传
    // -----------------------------------------------------------------------

    /**
     * 同步上传 JSON 到 Worker。
     *
     * @param json JSON 字符串
     * @return 是否上传成功
     */
    private fun uploadJson(json: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$WORKER_URL$UPLOAD_PATH")
            connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = UPLOAD_TIMEOUT_MS
                    readTimeout = UPLOAD_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $authToken")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }

            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                true
            } else {
                logger.warn { "Upload failed: HTTP $responseCode" }
                false
            }
        } catch (e: Exception) {
            logger.warn { "Upload exception: ${e.message}" }
            false
        } finally {
            connection?.disconnect()
        }
    }
}
