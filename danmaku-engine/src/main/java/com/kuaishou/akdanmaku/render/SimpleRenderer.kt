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

package com.kuaishou.akdanmaku.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.math.MathUtils.clamp
import com.kuaishou.akdanmaku.DanmakuConfig
import com.kuaishou.akdanmaku.data.DanmakuItem
import com.kuaishou.akdanmaku.data.DanmakuItemData
import com.kuaishou.akdanmaku.ui.DanmakuDisplayer
import com.kuaishou.akdanmaku.utils.Size
import java.util.HashMap
import kotlin.math.roundToInt

/**
 * 一个默认的，实现了简单只绘制文字和描边的弹幕渲染器
 *
 * @author Xana
 */
open class SimpleRenderer : DanmakuRenderer {
    private val textPaint =
        TextPaint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    private val strokePaint =
        TextPaint().apply {
            textSize = textPaint.textSize
            color = Color.BLACK
            strokeWidth = 3f
            style = Paint.Style.FILL_AND_STROKE
            isAntiAlias = true
        }
    private val debugPaint by lazy {
        Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeWidth = 6f
        }
    }
    private val borderPaint =
        Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeWidth = 6f
        }

    // 高赞弹幕点赞图标的画笔与路径：渲染器预置字段，绘制时复用，避免缓存构建热路径分配对象
    private val highLikePaint =
        Paint().apply {
            color = HIGH_LIKE_ICON_COLOR
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    private val highLikeIconPath = Path()

    override fun updatePaint(
        item: DanmakuItem,
        displayer: DanmakuDisplayer,
        config: DanmakuConfig,
    ) {
        val danmakuItemData = item.data
        // update textPaint
        val textSize = clamp(danmakuItemData.textSize.toFloat(), 12f, 25f) * (displayer.density - 0.6f)
        textPaint.color = danmakuItemData.textColor or Color.argb(255, 0, 0, 0)
        textPaint.textSize = textSize * config.textSizeScale
        textPaint.typeface = if (config.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        // update strokePaint
        strokePaint.textSize = textPaint.textSize
        strokePaint.typeface = textPaint.typeface
        strokePaint.color = if (textPaint.color == DEFAULT_DARK_COLOR) Color.WHITE else Color.BLACK
    }

    override fun measure(
        item: DanmakuItem,
        displayer: DanmakuDisplayer,
        config: DanmakuConfig,
    ): Size {
        updatePaint(item, displayer, config)
        val danmakuItemData = item.data
        val textWidth = textPaint.measureText(danmakuItemData.content)
        val textHeight = getCacheHeight(textPaint)
        // 高赞弹幕在文本右侧顶部附加点赞图标，测量宽度需为图标预留空间；
        // 非高赞弹幕宽度不变，不影响既有缓存位图的尺寸命中
        val iconWidth = if (danmakuItemData.highLike) highLikeIconWidth() else 0f
        return Size((textWidth + iconWidth).roundToInt() + CANVAS_PADDING, textHeight.roundToInt() + CANVAS_PADDING)
    }

    override fun draw(
        item: DanmakuItem,
        canvas: Canvas,
        displayer: DanmakuDisplayer,
        config: DanmakuConfig,
    ) {
        updatePaint(item, displayer, config)
        val danmakuItemData = item.data
        val x = CANVAS_PADDING * 0.5f
        val y = CANVAS_PADDING * 0.5f - textPaint.ascent()
        canvas.drawText(danmakuItemData.content, x, y, strokePaint)
        canvas.drawText(danmakuItemData.content, x, y, textPaint)
        if (danmakuItemData.highLike) {
            // 图标画在缓存位图内：合成到屏幕时随弹幕一起被 drawPaint.alpha 淡化、
            // 随 transform 矩阵缩放，无需单独处理透明度与缩放
            drawHighLikeIcon(canvas, x + textPaint.measureText(danmakuItemData.content))
        }
        if (danmakuItemData.danmakuStyle == DanmakuItemData.DANMAKU_STYLE_SELF_SEND) {
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), borderPaint)
        }
    }

    /** 高赞图标总占宽（图标边长 + 与文本间距），测量时为右上角图标预留空间。 */
    private fun highLikeIconWidth(): Float = highLikeIconSize() * (1f + HIGH_LIKE_ICON_GAP)

    private fun highLikeIconSize(): Float = textPaint.textSize * HIGH_LIKE_ICON_SIZE_SCALE

    /**
     * 在文本右侧顶部绘制高赞点赞小图标（心形：左右两瓣圆 + 底部尖角三角）。
     *
     * [textRight] 为文本右边缘，图标绘制在其右侧、垂直方向与文本顶部对齐。
     * Paint 与 Path 均为渲染器预置字段，此处无新对象分配；每次绘制前 reset 复用 Path。
     */
    private fun drawHighLikeIcon(
        canvas: Canvas,
        textRight: Float,
    ) {
        val size = highLikeIconSize()
        val left = textRight + HIGH_LIKE_ICON_GAP * size
        val top = CANVAS_PADDING * 0.5f
        val lobeRadius = size * HIGH_LIKE_LOBE_RADIUS
        // 心形上半部分：左右两瓣圆，外缘与下方三角顶边对齐
        canvas.drawCircle(left + size * 0.3f, top + size * 0.35f, lobeRadius, highLikePaint)
        canvas.drawCircle(left + size * 0.7f, top + size * 0.35f, lobeRadius, highLikePaint)
        // 心形下半部分：底部尖角三角
        highLikeIconPath.reset()
        highLikeIconPath.moveTo(left + size * 0.08f, top + size * 0.45f)
        highLikeIconPath.lineTo(left + size * 0.92f, top + size * 0.45f)
        highLikeIconPath.lineTo(left + size * 0.5f, top + size)
        highLikeIconPath.close()
        canvas.drawPath(highLikeIconPath, highLikePaint)
    }

    companion object {
        private val DEFAULT_DARK_COLOR: Int = Color.argb(255, 0x22, 0x22, 0x22)

        private const val CANVAS_PADDING: Int = 6

        /** 高赞点赞图标颜色（B 站粉）。 */
        private val HIGH_LIKE_ICON_COLOR: Int = Color.argb(255, 0xFB, 0x72, 0x9F)

        /** 高赞图标边长，相对弹幕字号缩放，保证与文字比例协调。 */
        private const val HIGH_LIKE_ICON_SIZE_SCALE = 0.45f

        /** 高赞图标与文本的间距，相对图标边长。 */
        private const val HIGH_LIKE_ICON_GAP = 0.18f

        /** 心形两瓣圆的半径比例。 */
        private const val HIGH_LIKE_LOBE_RADIUS = 0.22f

        private val sTextHeightCache: MutableMap<Float, Float> = HashMap()

        private fun getCacheHeight(paint: Paint): Float {
            val textSize = paint.textSize
            return sTextHeightCache[textSize] ?: let {
                val fontMetrics = paint.fontMetrics
                val textHeight = fontMetrics.descent - fontMetrics.ascent + fontMetrics.leading
                sTextHeightCache[textSize] = textHeight
                textHeight
            }
        }
    }
}
