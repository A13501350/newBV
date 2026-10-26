package dev.frost819.newbv.biliapi.util

import okhttp3.Interceptor

/**
 * 调试网络拦截器注入点。
 *
 * bili-api 为纯 JVM 模块，不直接依赖 Android 调试库（如 Chucker）。
 * app 进程启动时（Application.onCreate，早于任何 API 调用与 client 创建）向
 * [interceptors] 装配调试拦截器，各 `HttpClient(OkHttp)` 创建时挂载到
 * OkHttp application interceptor 链。
 *
 * 默认为空列表，release 包不装配时零开销。
 */
object NetworkDebugHooks {
    @Volatile
    var interceptors: List<Interceptor> = emptyList()
}
