/*
 * The MIT License (MIT)
 *
 * Copyright 2021 Kwai, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.kuaishou.akdanmaku.ecs.component.filter

import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.utils.DanmakuTimer

/**
 * 屏蔽关键字过滤
 *
 * 命中任一条件即屏蔽（filter 返回 true）：
 * - [selfPredicate] 返回 true：弹幕作者命中用户屏蔽规则，入参为弹幕作者的
 *   `userId`（云屏蔽场景下为 midHash 的 8 位 hex 解析后的 Long，null 表示未知）；
 * - [filterSet] 关键字：弹幕内容包含任一关键字。
 *
 * 与上游 1.0.4 的差异：上游 `selfPredicate` 语义为「自己的弹幕免屏蔽」
 * （`!selfPredicate && 关键字命中`），本项目改为「命中用户规则即屏蔽」
 * （`selfPredicate || 关键字命中`），用于承载云屏蔽用户规则，见 danmaku-engine/README.md。
 *
 * @author Xana
 * @since 2021-06-30
 */
class BlockedTextFilter(
    val selfPredicate: (Long?) -> Boolean,
) : SimpleDanmakuFilter<String>(DanmakuFilters.FILTER_TYPE_BLOCKED_TEXT) {
    override fun filterField(data: DanmakuItemData): String = data.content

    override fun filter(
        item: DanmakuItem,
        timer: DanmakuTimer,
        config: DanmakuConfig,
    ): Boolean {
        val data = item.data
        if (selfPredicate(data.userId)) return true
        return filterSet.any { data.content.contains(it) }
    }
}
