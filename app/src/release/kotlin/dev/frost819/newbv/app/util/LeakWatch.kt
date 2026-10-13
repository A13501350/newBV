package dev.frost819.newbv.app.util

/**
 * release 变体：空实现，不引入 LeakCanary 依赖。
 */
object LeakWatch {

    @Suppress("UNUSED_PARAMETER")
    fun expectGc(target: Any, description: String) = Unit
}
