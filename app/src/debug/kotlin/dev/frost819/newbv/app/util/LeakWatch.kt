package dev.frost819.newbv.app.util

import leakcanary.AppWatcher

/**
 * debug 变体：通过 LeakCanary 监控对象是否在释放后被 GC。
 */
object LeakWatch {

    /**
     * 期望 [target] 在 5 秒左右变为弱可达（即被 GC）。
     * 若仍被强引用持有，LeakCanary 会 dump heap 并输出泄漏链（logcat tag: LeakCanary）。
     */
    fun expectGc(target: Any, description: String) {
        AppWatcher.objectWatcher.expectWeaklyReachable(target, description)
    }
}
