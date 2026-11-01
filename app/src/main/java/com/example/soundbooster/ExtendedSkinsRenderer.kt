package com.example.soundbooster

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

// =========================================================================
// מנוע ציור וקטורי ל-4 הסגנונות המיוחדים הנוספים (מסודרים מחדש כאינדקסים 11 עד 14)
// =========================================================================
object ExtendedSkinsRenderer {

    private val cardRect = RectF()
    private val fillRect = RectF()
    private val clipPath = Path()
    private val iconPath = Path()

    fun drawMusicNoteIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        val r = size * 0.16f

        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - size * 0.22f, cy + size * 0.24f, r, paint)
        canvas.drawCircle(cx + size * 0.18f, cy + size * 0.16f, r, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.11f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val leftStemX = cx - size * 0.22f + r * 0.7f
        val rightStemX = cx + size * 0.18f + r * 0.7f
        canvas.drawLine(leftStemX, cy + size * 0.24f, leftStemX, cy - size * 0.32f, paint)
        canvas.drawLine(rightStemX, cy + size * 0.16f, rightStemX, cy - size * 0.38f, paint)
        canvas.drawLine(leftStemX, cy - size * 0.32f, rightStemX, cy - size * 0.38f, paint)

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawPlayCircleIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, circleColor: Int, triangleColor: Int, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = circleColor
        val r = size * 0.45f
        canvas.drawCircle(cx, cy, r, paint)

        paint.color = triangleColor
        iconPath.reset()
        iconPath.moveTo(cx - r * 0.28f, cy - r * 0.38f)
        iconPath.lineTo(cx + r * 0.45f, cy)
        iconPath.lineTo(cx - r * 0.28f, cy + r * 0.38f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)
    }

    fun drawExtendedStyle(
        styleIndex: Int,
        canvas: Canvas,
        w: Float,
        h: Float,
        topEditOffset: Float,
        vol: Int,
        ratio: Float,
        cfg: StyleConfig,
        c0: Int,
        c1: Int,
        c2: Int,
        c3: Int,
        c4: Int,
        c5: Int,
        cornerPx: Float,
        brdW: Float,
        icoSize: Float,
        txtPx: Float,
        dpFn: (Float) -> Float,
        paint: Paint
    ) {
        val pad = dpFn(5f)
        val availTop = pad + topEditOffset
        val availBot = h - pad

        when (styleIndex) {
            // 11. קפסולה לבנה דקה עם קו אדום/מותאם דקיק וכפתור Play (לשעבר 15)
            11 -> {
                val pillW = (w - pad * 2f) * (0.58f + (cfg.extraParam2 / 100f) * 0.35f)
                val left = (w - pillW) / 2f
                cardRect.set(left, availTop, left + pillW, availBot)
                val rad = min(cornerPx, pillW / 2f)

                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, rad, rad, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, rad, rad, paint)
                }

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(cardRect, rad, rad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                val safeTop = cardRect.top + max(rad * 0.7f, dpFn(28f))
                val safeBot = cardRect.bottom - max(rad * 0.95f, dpFn(58f))
                val lineThick = dpFn(2.5f + (cfg.extraParam1 / 100f) * 4.5f)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lineThick
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = c1
                canvas.drawLine(w / 2f, safeTop, w / 2f, safeBot, paint)

                val fillTop = safeBot - (safeBot - safeTop) * ratio
                paint.color = c0
                canvas.drawLine(w / 2f, fillTop, w / 2f, safeBot, paint)

                drawPlayCircleIcon(canvas, w / 2f, safeBot + dpFn(18f), icoSize * 1.05f, c0, c5, paint)

                paint.style = Paint.Style.FILL
                paint.color = c4
                paint.textSize = txtPx * 0.85f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("⇄", w / 2f, cardRect.bottom - max(rad * 0.35f, dpFn(12f)), paint)
                canvas.drawText("$vol%", w / 2f, cardRect.top + max(rad * 0.45f, dpFn(18f)), paint)
                canvas.restore()
            }

            // 12. אולפן זהב כהה אנכי (Golden Thin Track & Knob - לשעבר 17)
            12 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                val rad = min(cornerPx, cardRect.width() / 2f)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, rad, rad, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, rad, rad, paint)
                }

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(cardRect, rad, rad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                val safeInset = max(rad * 0.68f, dpFn(26f))
                val safeTop = cardRect.top + safeInset + dpFn(10f)
                val safeBot = cardRect.bottom - safeInset - dpFn(14f)
                val lineW = dpFn(2f + (cfg.extraParam2 / 100f) * 4f)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lineW
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = c4
                canvas.drawLine(w / 2f, safeTop, w / 2f, safeBot, paint)

                val thumbY = safeBot - (safeBot - safeTop) * ratio
                paint.color = c0
                canvas.drawLine(w / 2f, thumbY, w / 2f, safeBot, paint)

                val knobR = dpFn(6f + (cfg.extraParam1 / 100f) * 6f)
                paint.style = Paint.Style.FILL
                paint.color = c1
                canvas.drawCircle(w / 2f, thumbY, knobR, paint)

                drawMusicNoteIcon(canvas, w / 2f, cardRect.bottom - safeInset * 0.78f, icoSize * 0.9f, c5, paint)

                paint.color = c5
                paint.textSize = txtPx * 0.88f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + safeInset * 0.85f, paint)
                canvas.restore()
            }

            // 13. בר עליון צמוד קצה עם גרדיאנט ותו מוזיקה (Top Status Bar Strip - לשעבר 19)
            13 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                val rad = min(cornerPx, cardRect.height() / 2f)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, rad, rad, paint)

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(cardRect, rad, rad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                val fillRight = cardRect.left + cardRect.width() * ratio
                fillRect.set(cardRect.left, cardRect.top, fillRight, cardRect.bottom)
                paint.shader = LinearGradient(
                    cardRect.left, 0f, fillRight.coerceAtLeast(cardRect.left + 1f), 0f,
                    c0, c1, Shader.TileMode.CLAMP
                )
                canvas.drawRect(fillRect, paint)
                paint.shader = null

                val sideInset = max(rad * 0.8f, dpFn(20f))
                drawMusicNoteIcon(canvas, cardRect.left + sideInset, cardRect.centerY(), icoSize * 0.85f, c4, paint)

                paint.color = c4
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$vol%", cardRect.right - sideInset * 0.8f, cardRect.centerY() + dpFn(4f), paint)
                canvas.restore()

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, rad, rad, paint)
                }
            }

            // 14. קפסולת Windows / Emoji אנכית עם ראש מודגש (לשעבר 20)
            else -> {
                val pillW = (w - pad * 2f) * (0.65f + (cfg.extraParam1 / 100f) * 0.28f)
                val left = (w - pillW) / 2f
                cardRect.set(left, availTop, left + pillW, availBot)
                val rad = min(cornerPx, pillW / 2f)

                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, rad, rad, paint)

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(cardRect, rad, rad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                val fillTop = cardRect.bottom - cardRect.height() * ratio
                fillRect.set(cardRect.left, fillTop, cardRect.right, cardRect.bottom)
                paint.shader = LinearGradient(0f, fillTop, 0f, cardRect.bottom, c0, c1, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(fillRect, rad, rad, paint)
                paint.shader = null

                val capR = pillW * 0.34f
                paint.color = c4
                canvas.drawCircle(w / 2f, fillTop.coerceIn(cardRect.top + capR, cardRect.bottom - capR), capR, paint)

                val safeInset = max(rad * 0.68f, dpFn(22f))
                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, cardRect.bottom - safeInset * 0.82f, icoSize * 0.9f, c5, 1, vol, paint
                )

                paint.color = c5
                paint.textSize = txtPx * 0.9f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + safeInset * 0.88f, paint)
                canvas.restore()

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, rad, rad, paint)
                }
            }
        }
    }
}
 
