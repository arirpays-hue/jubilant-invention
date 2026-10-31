package com.example.soundbooster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =========================================================================
// מנוע ציור וקטורי מתקדם ל-10 הסגנונות החדשים מהתמונות + סמלים מותאמים אישית
// =========================================================================
object ExtendedSkinsRenderer {

    private val cardRect = RectF()
    private val subRect = RectF()
    private val trackRect = RectF()
    private val fillRect = RectF()
    private val clipPath = Path()
    private val iconPath = Path()

    // =====================================================================
    // ספריית סמלים וקטוריים מותאמים אישית (תו מוזיקה, פעמון, שעון, טלפון, הגדרות, תפריט)
    // =====================================================================
    fun drawMusicNoteIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        val r = size * 0.16f

        // שני ראשי תווים עגולים
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - size * 0.22f, cy + size * 0.24f, r, paint)
        canvas.drawCircle(cx + size * 0.18f, cy + size * 0.16f, r, paint)

        // קווי התו והגשר העליון
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

    fun drawBellIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        paint.color = color
        paint.style = Paint.Style.FILL

        val w = size * 0.36f
        val topY = cy - size * 0.34f
        val botY = cy + size * 0.20f

        iconPath.reset()
        iconPath.moveTo(cx - w, botY)
        iconPath.lineTo(cx - w * 0.75f, cy - size * 0.08f)
        iconPath.quadTo(cx, topY - size * 0.12f, cx + w * 0.75f, cy - size * 0.08f)
        iconPath.lineTo(cx + w, botY)
        iconPath.close()
        canvas.drawPath(iconPath, paint)

        // ענבל תחתון וכתר עליון
        canvas.drawCircle(cx, botY + size * 0.12f, size * 0.10f, paint)
        canvas.drawCircle(cx, topY, size * 0.07f, paint)
        paint.style = prevStyle
    }

    fun drawAlarmClockIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.11f
        paint.strokeCap = Paint.Cap.ROUND

        val r = size * 0.36f
        canvas.drawCircle(cx, cy + size * 0.04f, r, paint)
        // מחוגי השעון
        canvas.drawLine(cx, cy + size * 0.04f, cx, cy - size * 0.14f, paint)
        canvas.drawLine(cx, cy + size * 0.04f, cx + size * 0.14f, cy + size * 0.04f, paint)
        // אוזני פעמון למעלה
        canvas.drawLine(cx - r * 0.85f, cy - r * 0.65f, cx - r * 0.4f, cy - r * 0.95f, paint)
        canvas.drawLine(cx + r * 0.85f, cy - r * 0.65f, cx + r * 0.4f, cy - r * 0.95f, paint)

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawPhoneCallIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.14f
        paint.strokeCap = Paint.Cap.ROUND

        val r = size * 0.34f
        subRect.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(subRect, 125f, 100f, false, paint)

        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx - r * 0.55f, cy - r * 0.65f, size * 0.11f, paint)
        canvas.drawCircle(cx - r * 0.65f, cy + r * 0.55f, size * 0.11f, paint)

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawGearIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.12f
        paint.strokeCap = Paint.Cap.ROUND

        val r = size * 0.24f
        canvas.drawCircle(cx, cy, r, paint)
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val x1 = (cx + cos(angle) * (r * 1.15f)).toFloat()
            val y1 = (cy + sin(angle) * (r * 1.15f)).toFloat()
            val x2 = (cx + cos(angle) * (r * 1.65f)).toFloat()
            val y2 = (cy + sin(angle) * (r * 1.65f)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawThreeDotsMenu(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        val dotR = (size * 0.09f).coerceAtLeast(2.5f)
        val gap = size * 0.26f
        canvas.drawCircle(cx - gap, cy, dotR, paint)
        canvas.drawCircle(cx, cy, dotR, paint)
        canvas.drawCircle(cx + gap, cy, dotR, paint)
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

    fun drawVibratePhoneIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.10f
        paint.strokeCap = Paint.Cap.ROUND

        val pw = size * 0.22f
        val ph = size * 0.34f
        subRect.set(cx - pw, cy - ph, cx + pw, cy + ph)
        canvas.drawRoundRect(subRect, size * 0.06f, size * 0.06f, paint)

        // קווי רטט בצדדים
        canvas.drawLine(cx - pw * 1.65f, cy - ph * 0.55f, cx - pw * 1.65f, cy + ph * 0.55f, paint)
        canvas.drawLine(cx + pw * 1.65f, cy - ph * 0.55f, cx + pw * 1.65f, cy + ph * 0.55f, paint)

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    // =====================================================================
    // ציור 10 המחוונים החדשים (סגנונות 11 עד 20) עם חיתוך קצוות בטוח (Zero Overflow)
    // =====================================================================
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
        val availH = (availBot - availTop).coerceAtLeast(dpFn(60f))

        when (styleIndex) {
            // 11. קפסולת גרדיאנט מפוצלת עם כרטיסיית תפריט תחתונה (Beautiful Gradients בתמונה)
            11 -> {
                val splitRatio = (0.64f + (cfg.extraParam1 / 100f) * 0.14f).coerceIn(0.60f, 0.80f)
                val gap = dpFn(4f + (cfg.extraParam2 / 100f) * 8f)
                val mainBot = availTop + availH * splitRatio - gap / 2f
                val subTop = mainBot + gap

                cardRect.set(pad, availTop, w - pad, mainBot)
                val pillRad = min(cornerPx, cardRect.width() / 2f)

                // קפסולה עליונה עם חיתוך מדויק שלא מאפשר לגלוש מהעיגול
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, pillRad, pillRad, paint)

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(cardRect, pillRad, pillRad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                val fillTop = cardRect.bottom - cardRect.height() * ratio
                fillRect.set(cardRect.left, fillTop, cardRect.right, cardRect.bottom)
                paint.shader = LinearGradient(0f, fillTop, 0f, cardRect.bottom, c0, c1, Shader.TileMode.CLAMP)
                canvas.drawRect(fillRect, paint)
                paint.shader = null

                // סמל תו מוזיקה בתוך הקפסולה במרחק בטוח מהקצה העגול התחתון
                val safeInset = max(pillRad * 0.65f, dpFn(20f))
                drawMusicNoteIcon(canvas, w / 2f, cardRect.bottom - safeInset, icoSize, c4, paint)

                paint.color = c4
                paint.textSize = txtPx * 0.9f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + max(pillRad * 0.65f, dpFn(20f)), paint)
                canvas.restore()

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, pillRad, pillRad, paint)
                }

                // קפסולה תחתונה נפרדת (פעמון + 3 נקודות מיושרים במרכז בלי לגלוש!)
                subRect.set(pad, subTop, w - pad, availBot)
                val subRad = min(cornerPx, subRect.width() / 2f)
                paint.style = Paint.Style.FILL
                paint.color = c5
                canvas.drawRoundRect(subRect, subRad, subRad, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(subRect, subRad, subRad, paint)
                }

                val subH = subRect.height()
                drawBellIcon(canvas, w / 2f, subRect.top + subH * 0.34f, icoSize * 0.85f, c4, paint)
                drawThreeDotsMenu(canvas, w / 2f, subRect.top + subH * 0.72f, icoSize * 0.85f, c4, paint)
            }

            // 12. אנדרואיד 12 מנטה מודולרי (Unique Sliders בתמונה)
            12 -> {
                val gap = dpFn(4f + (cfg.extraParam2 / 100f) * 6f)
                val btnSize = min(w - pad * 2f, availH * 0.17f)
                val mainBot = availBot - (btnSize * 2f + gap * 2f)

                cardRect.set(pad, availTop, w - pad, mainBot)
                val rad = cornerPx.coerceAtMost(cardRect.width() * 0.45f)

                paint.style = Paint.Style.FILL
                paint.color = c1
                canvas.drawRoundRect(cardRect, rad, rad, paint)

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(cardRect, rad, rad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                val fillTop = cardRect.bottom - cardRect.height() * ratio
                fillRect.set(cardRect.left, fillTop, cardRect.right, cardRect.bottom)
                paint.color = c0
                canvas.drawRoundRect(fillRect, rad * 0.7f, rad * 0.7f, paint)

                val safeBottom = cardRect.bottom - max(rad * 0.65f, dpFn(20f))
                drawMusicNoteIcon(canvas, w / 2f, safeBottom, icoSize, c4, paint)

                paint.color = c5
                paint.textSize = txtPx * 0.9f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + max(rad * 0.65f, dpFn(20f)), paint)
                canvas.restore()

                // כפתור מודולרי 1: פעמון/רטט
                val b1Top = mainBot + gap
                subRect.set(pad, b1Top, w - pad, b1Top + btnSize)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(subRect, rad * 0.75f, rad * 0.75f, paint)
                drawBellIcon(canvas, w / 2f, subRect.centerY(), icoSize * 0.85f, c4, paint)

                // כפתור מודולרי 2: תפריט 3 נקודות (שמור היטב בתוך הריבוע שלו!)
                val b2Top = b1Top + btnSize + gap
                subRect.set(pad, b2Top, w - pad, availBot)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(subRect, rad * 0.75f, rad * 0.75f, paint)
                drawThreeDotsMenu(canvas, w / 2f, subRect.centerY(), icoSize * 0.9f, c4, paint)
            }

            // 13. פאנל 4 ערוצי ניאון זוהר (Neon Sliders בתמונה)
            13 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)
                }

                val cols = 4
                val colW = cardRect.width() / cols
                val trackTop = cardRect.top + dpFn(36f)
                val trackBot = cardRect.bottom - dpFn(40f)
                val slotRatios = floatArrayOf(ratio, (ratio * 0.78f).coerceIn(0.15f, 1f), (ratio * 0.85f).coerceIn(0.2f, 1f), (ratio * 0.92f).coerceIn(0.25f, 1f))
                val knobRad = dpFn(4.5f + (cfg.extraParam1 / 100f) * 4.5f)
                val barThick = dpFn(2.5f + (cfg.extraParam2 / 100f) * 3.5f)

                for (i in 0 until cols) {
                    val cx = cardRect.left + colW * (i + 0.5f)
                    val slotW = colW * 0.52f
                    subRect.set(cx - slotW / 2f, trackTop - dpFn(8f), cx + slotW / 2f, trackBot + dpFn(28f))

                    // מסגרת תא דקה לכל ערוץ
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dpFn(1f)
                    paint.color = Color.argb(70, Color.red(c3), Color.green(c3), Color.blue(c3))
                    canvas.drawRoundRect(subRect, slotW / 2f, slotW / 2f, paint)

                    // פס מסילה כהה
                    paint.strokeWidth = barThick
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = Color.argb(90, Color.red(c4), Color.green(c4), Color.blue(c4))
                    canvas.drawLine(cx, trackTop, cx, trackBot, paint)

                    // פס ניאון פעיל + נקודה זוהרת
                    val curR = slotRatios[i]
                    val fillY = trackBot - (trackBot - trackTop) * curR
                    paint.color = if (i == 0) c0 else c1
                    canvas.drawLine(cx, fillY, cx, trackBot, paint)

                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(cx, fillY, knobRad, paint)

                    // סמל מותאם בתחתית כל ערוץ
                    val iconY = trackBot + dpFn(15f)
                    when (i) {
                        0 -> VectorIconRenderer.drawStyledSpeakerIcon(canvas, cx, iconY, icoSize * 0.78f, c4, 1, vol, paint)
                        1 -> drawAlarmClockIcon(canvas, cx, iconY, icoSize * 0.78f, c4, paint)
                        2 -> drawBellIcon(canvas, cx, iconY, icoSize * 0.78f, c4, paint)
                        else -> drawPhoneCallIcon(canvas, cx, iconY, icoSize * 0.78f, c4, paint)
                    }
                }

                // כותרת אחוזים עליונה
                paint.style = Paint.Style.FILL
                paint.color = c5
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("NEON $vol%", w / 2f, cardRect.top + dpFn(22f), paint)
            }

            // 14. כרטיסיית 4 עמודות RGB צבעוניות (Colorful Themes בתמונה)
            14 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)
                }

                val cols = 4
                val innerPad = dpFn(10f)
                val availW = cardRect.width() - innerPad * 2f
                val colStep = availW / cols
                val pillW = colStep * (0.55f + (cfg.extraParam1 / 100f) * 0.25f)
                val pillTop = cardRect.top + dpFn(34f)
                val pillBot = cardRect.bottom - dpFn(36f)
                val pillRad = pillW / 2f
                val slotRatios = floatArrayOf(ratio, (ratio * 0.72f).coerceIn(0.18f, 1f), (ratio * 0.86f).coerceIn(0.22f, 1f), (ratio * 0.64f).coerceIn(0.15f, 1f))

                for (i in 0 until cols) {
                    val cx = cardRect.left + innerPad + colStep * (i + 0.5f)
                    subRect.set(cx - pillW / 2f, pillTop, cx + pillW / 2f, pillBot)

                    paint.style = Paint.Style.FILL
                    paint.color = c4
                    canvas.drawRoundRect(subRect, pillRad, pillRad, paint)

                    canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(subRect, pillRad, pillRad, Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val fillTop = pillBot - (pillBot - pillTop) * slotRatios[i]
                    fillRect.set(subRect.left, fillTop, subRect.right, pillBot)
                    paint.shader = LinearGradient(0f, fillTop, 0f, pillBot, c0, c1, Shader.TileMode.CLAMP)
                    canvas.drawRect(fillRect, paint)
                    paint.shader = null
                    canvas.restore()

                    // נקודה זוהרת בתחתית כל קפסולה
                    paint.color = c0
                    canvas.drawCircle(cx, pillBot - pillRad * 0.85f, pillW * 0.22f, paint)

                    // סמל מתחת לקפסולה
                    val icoY = pillBot + dpFn(18f)
                    when (i) {
                        0 -> VectorIconRenderer.drawStyledSpeakerIcon(canvas, cx, icoY, icoSize * 0.75f, c5, 1, vol, paint)
                        1 -> drawVibratePhoneIcon(canvas, cx, icoY, icoSize * 0.75f, c5, paint)
                        2 -> drawBellIcon(canvas, cx, icoY, icoSize * 0.75f, c5, paint)
                        else -> drawPhoneCallIcon(canvas, cx, icoY, icoSize * 0.75f, c5, paint)
                    }
                }

                paint.style = Paint.Style.FILL
                paint.color = c5
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + dpFn(22f), paint)
            }

            // 15. קפסולה לבנה דקה עם קו אדום/מותאם דקיק וכפתור Play (Custom Skins בתמונה)
            15 -> {
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

                val safeTop = cardRect.top + max(rad * 0.7f, dpFn(26f))
                val safeBot = cardRect.bottom - max(rad * 0.9f, dpFn(54f))
                val lineThick = dpFn(2.5f + (cfg.extraParam1 / 100f) * 4.5f)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lineThick
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = c1
                canvas.drawLine(w / 2f, safeTop, w / 2f, safeBot, paint)

                val fillTop = safeBot - (safeBot - safeTop) * ratio
                paint.color = c0
                canvas.drawLine(w / 2f, fillTop, w / 2f, safeBot, paint)

                // אייקון Play עגול + חצים בתחתית הקפסולה (ללא גלישה!)
                drawPlayCircleIcon(canvas, w / 2f, safeBot + dpFn(18f), icoSize * 1.05f, c0, c2, paint)

                paint.style = Paint.Style.FILL
                paint.color = c4
                paint.textSize = txtPx * 0.85f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("⇄", w / 2f, cardRect.bottom - dpFn(10f), paint)
                canvas.drawText("$vol%", w / 2f, cardRect.top + dpFn(18f), paint)
            }

            // 16. פאנל מחוונים אופקיים עבים מלא (Volume Control הירוק בתמונה)
            16 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)
                }

                val rows = 4
                val labels = arrayOf("Media  $vol%", "Ring", "Notifications", "Alarm")
                val rowRatios = floatArrayOf(ratio, (ratio * 0.75f).coerceIn(0.2f, 1f), (ratio * 0.55f).coerceIn(0.15f, 1f), (ratio * 0.85f).coerceIn(0.25f, 1f))
                val innerPad = dpFn(12f)
                val rowStep = (cardRect.height() - innerPad * 2f) / rows
                val barRad = dpFn(8f + (cfg.extraParam1 / 100f) * 10f)

                for (r in 0 until rows) {
                    val rowTop = cardRect.top + innerPad + r * rowStep
                    paint.style = Paint.Style.FILL
                    paint.color = c4
                    paint.textSize = txtPx * 0.85f
                    paint.textAlign = Paint.Align.LEFT
                    canvas.drawText(labels[r], cardRect.left + innerPad, rowTop + dpFn(13f), paint)

                    val barTop = rowTop + dpFn(18f)
                    val barBot = (rowTop + rowStep - dpFn(4f)).coerceAtLeast(barTop + dpFn(18f))
                    trackRect.set(cardRect.left + innerPad, barTop, cardRect.right - innerPad, barBot)

                    paint.color = c1
                    canvas.drawRoundRect(trackRect, barRad, barRad, paint)

                    canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(trackRect, barRad, barRad, Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val fillRight = trackRect.left + trackRect.width() * rowRatios[r]
                    fillRect.set(trackRect.left, trackRect.top, fillRight, trackRect.bottom)
                    paint.color = c0
                    canvas.drawRect(fillRect, paint)

                    val icoX = trackRect.left + dpFn(18f)
                    val icoY = trackRect.centerY()
                    when (r) {
                        0 -> drawMusicNoteIcon(canvas, icoX, icoY, icoSize * 0.8f, c5, paint)
                        1 -> drawVibratePhoneIcon(canvas, icoX, icoY, icoSize * 0.8f, c5, paint)
                        2 -> drawBellIcon(canvas, icoX, icoY, icoSize * 0.8f, c5, paint)
                        else -> drawAlarmClockIcon(canvas, icoX, icoY, icoSize * 0.8f, c5, paint)
                    }
                    canvas.restore()
                }
            }

            // 17. אולפן זהב כהה (Golden Thin Track & Knob בתמונה)
            17 -> {
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

                val safeTop = cardRect.top + max(rad * 0.65f, dpFn(28f))
                val safeBot = cardRect.bottom - max(rad * 0.75f, dpFn(38f))
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

                drawMusicNoteIcon(canvas, w / 2f, cardRect.bottom - max(rad * 0.5f, dpFn(18f)), icoSize * 0.9f, c5, paint)

                paint.color = c5
                paint.textSize = txtPx * 0.88f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + dpFn(20f), paint)
            }

            // 18. מיקסר כחול עמוק עם 6 קווים אנכיים (Deep Blue Line Mixer בתמונה)
            18 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)
                }

                // תגית עליונה (Pill Badge) עם פעמון ואחוזים
                subRect.set(cardRect.left + dpFn(12f), cardRect.top + dpFn(10f), cardRect.left + dpFn(125f), cardRect.top + dpFn(36f))
                paint.style = Paint.Style.FILL
                paint.color = c4
                canvas.drawRoundRect(subRect, dpFn(13f), dpFn(13f), paint)
                drawBellIcon(canvas, subRect.left + dpFn(16f), subRect.centerY(), icoSize * 0.7f, c5, paint)
                paint.color = c5
                paint.textSize = txtPx * 0.85f
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText("Media $vol%", subRect.left + dpFn(30f), subRect.centerY() + dpFn(4f), paint)

                val cols = 6
                val colStep = (cardRect.width() - dpFn(16f)) / cols
                val trackTop = cardRect.top + dpFn(48f)
                val trackBot = cardRect.bottom - dpFn(38f)
                val ratios = floatArrayOf(ratio, (ratio * 0.8f).coerceIn(0.2f, 1f), (ratio * 0.6f).coerceIn(0.15f, 1f), (ratio * 0.45f).coerceIn(0.15f, 1f), (ratio * 0.75f).coerceIn(0.2f, 1f), (ratio * 0.9f).coerceIn(0.25f, 1f))
                val knobR = dpFn(3.5f + (cfg.extraParam1 / 100f) * 3.5f)

                for (i in 0 until cols) {
                    val cx = cardRect.left + dpFn(8f) + colStep * (i + 0.5f)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dpFn(1.8f)
                    paint.color = Color.argb(80, Color.red(c0), Color.green(c0), Color.blue(c0))
                    canvas.drawLine(cx, trackTop, cx, trackBot, paint)

                    val ty = trackBot - (trackBot - trackTop) * ratios[i]
                    paint.color = c0
                    canvas.drawLine(cx, ty, cx, trackBot, paint)

                    paint.style = Paint.Style.FILL
                    paint.color = c1
                    canvas.drawCircle(cx, ty, knobR, paint)

                    val icoY = trackBot + dpFn(18f)
                    when (i) {
                        0 -> drawMusicNoteIcon(canvas, cx, icoY, icoSize * 0.7f, c5, paint)
                        1 -> drawBellIcon(canvas, cx, icoY, icoSize * 0.7f, c5, paint)
                        2 -> VectorIconRenderer.drawStyledSpeakerIcon(canvas, cx, icoY, icoSize * 0.7f, c5, 1, vol, paint)
                        3 -> drawAlarmClockIcon(canvas, cx, icoY, icoSize * 0.7f, c5, paint)
                        4 -> drawPhoneCallIcon(canvas, cx, icoY, icoSize * 0.7f, c5, paint)
                        else -> drawGearIcon(canvas, cx, icoY, icoSize * 0.7f, c5, paint)
                    }
                }
            }

            // 19. בר עליון צמוד קצה עם גרדיאנט ותו מוזיקה (Top Status Bar Strip בתמונה)
            19 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)

                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(cardRect, cornerPx, cornerPx, Path.Direction.CW)
                canvas.clipPath(clipPath)

                val fillRight = cardRect.left + cardRect.width() * ratio
                fillRect.set(cardRect.left, cardRect.top, fillRight, cardRect.bottom)
                paint.shader = LinearGradient(cardRect.left, 0f, fillRight.coerceAtLeast(cardRect.left + 1f), 0f, c0, c1, Shader.TileMode.CLAMP)
                canvas.drawRect(fillRect, paint)
                paint.shader = null

                drawMusicNoteIcon(canvas, cardRect.left + dpFn(20f), cardRect.centerY(), icoSize * 0.85f, c4, paint)

                paint.color = c4
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.RIGHT
                canvas.drawText("$vol%", cardRect.right - dpFn(16f), cardRect.centerY() + dpFn(4f), paint)
                canvas.restore()

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)
                }
            }

            // 20. קפסולת Windows / Emoji אנכית עם ראש מודגש וחיתוך קצוות מושלם
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

                // עיגול ראש מודגש בראש המילוי
                val capR = pillW * 0.34f
                paint.color = c4
                canvas.drawCircle(w / 2f, fillTop.coerceIn(cardRect.top + capR, cardRect.bottom - capR), capR, paint)

                val safeInset = max(rad * 0.65f, dpFn(20f))
                VectorIconRenderer.drawStyledSpeakerIcon(canvas, w / 2f, cardRect.bottom - safeInset, icoSize * 0.9f, c5, 1, vol, paint)

                paint.color = c5
                paint.textSize = txtPx * 0.9f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + safeInset, paint)
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
 
