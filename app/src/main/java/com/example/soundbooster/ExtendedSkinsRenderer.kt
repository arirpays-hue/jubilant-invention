package com.example.soundbooster

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// =========================================================================
// מנוע ציור וקטורי לסגנונות 11..17 עם תמיכה מלאה בסמלי ערוצים (מדיה, צלצול,
// התראות, שעון, טלפון) + סמל בלוטות' חכם ליד הרמקול כשמחובר מכשיר!
// =========================================================================
object ExtendedSkinsRenderer {

    private val cardRect = RectF()
    private val pillRect = RectF()
    private val fillRect = RectF()
    private val thumbRect = RectF()
    private val badgeRect = RectF()
    private val clipPath = Path()
    private val innerClipPath = Path()
    private val iconPath = Path()

    // אזורי לחיצה אינטראקטיביים על המחוון
    val hitSpeakerButton = RectF()
    val hitVibrateButton = RectF()
    val hitMuteButton = RectF()
    val hitPlayPauseButton = RectF()
    val hitSwapSideButton = RectF()
    val hitStreamCycleButton = RectF() // לחיצה להחלפת הערוץ המותאם (מדיה/צלצול/התראות/שעון/טלפון)

    fun clearInteractiveHitZones() {
        hitSpeakerButton.setEmpty()
        hitVibrateButton.setEmpty()
        hitMuteButton.setEmpty()
        hitPlayPauseButton.setEmpty()
        hitSwapSideButton.setEmpty()
        hitStreamCycleButton.setEmpty()
    }

    // ציור תו מוזיקה + סמל בלוטות' קטן צמוד אם מחובר התקן בלוטות'
    fun drawMusicNoteIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        paint: Paint,
        showBluetoothBadge: Boolean = AudioEngine.isBluetoothConnected,
        styleIndex: Int = AudioEngine.sliderStyleIndex
    ) {
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

        if (showBluetoothBadge) {
            VectorIconRenderer.drawMiniBluetoothBadge(
                canvas, cx + size * 0.52f, cy - size * 0.12f, size * 0.48f, color, styleIndex, paint
            )
        }

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

    fun drawVibrateModeIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (size * 0.105f).coerceAtLeast(2.2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val pw = size * 0.20f
        val ph = size * 0.34f
        fillRect.set(cx - pw, cy - ph, cx + pw, cy + ph)
        canvas.drawRoundRect(fillRect, size * 0.06f, size * 0.06f, paint)

        val waveX1 = cx - pw * 1.55f
        val waveX2 = cx - pw * 2.15f
        canvas.drawLine(waveX1, cy - ph * 0.55f, waveX1, cy + ph * 0.55f, paint)
        canvas.drawLine(waveX2, cy - ph * 0.28f, waveX2, cy + ph * 0.28f, paint)

        val rWaveX1 = cx + pw * 1.55f
        val rWaveX2 = cx + pw * 2.15f
        canvas.drawLine(rWaveX1, cy - ph * 0.55f, rWaveX1, cy + ph * 0.55f, paint)
        canvas.drawLine(rWaveX2, cy - ph * 0.28f, rWaveX2, cy + ph * 0.28f, paint)

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawMuteSlashIcon(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, paint: Paint) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (size * 0.11f).coerceAtLeast(2.2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val s = size * 0.44f
        iconPath.reset()
        iconPath.moveTo(cx - s * 0.75f, cy - s * 0.30f)
        iconPath.lineTo(cx - s * 0.25f, cy - s * 0.30f)
        iconPath.lineTo(cx + s * 0.25f, cy - s * 0.78f)
        iconPath.lineTo(cx + s * 0.25f, cy + s * 0.78f)
        iconPath.lineTo(cx - s * 0.25f, cy + s * 0.30f)
        iconPath.lineTo(cx - s * 0.75f, cy + s * 0.30f)
        iconPath.close()
        canvas.drawPath(iconPath, paint)

        canvas.drawLine(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.75f, cy + s * 0.85f, paint)

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
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
        clearInteractiveHitZones()
        val pad = dpFn(5f)
        val availTop = pad + topEditOffset
        val availBot = h - pad
        val streamType = AudioEngine.activeStreamType
        val btConnected = AudioEngine.isBluetoothConnected

        when (styleIndex) {
            // 11. קפסולה לבנה דקה עם קו מילוי + סמל ערוץ/Play + חצים
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

                val safeTop = cardRect.top + max(rad * 0.85f, dpFn(38f))
                val safeBot = cardRect.bottom - max(rad * 0.95f, dpFn(58f))
                val lineThick = dpFn(2.5f + (cfg.extraParam1 / 100f) * 4.5f)

                // סמל הערוץ הפעיל בראש הקפסולה (רמקול+בלוטות' / צלצול / התראות / שעון / טלפון)
                val topIconY = cardRect.top + max(rad * 0.50f, dpFn(20f))
                VectorIconRenderer.drawActiveStreamIcon(
                    canvas, w / 2f, topIconY, icoSize * 0.82f, c4, 11, vol, streamType, btConnected, paint
                )
                hitStreamCycleButton.set(left, cardRect.top, left + pillW, topIconY + dpFn(16f))

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lineThick
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = c1
                canvas.drawLine(w / 2f, safeTop, w / 2f, safeBot, paint)

                val fillTop = safeBot - (safeBot - safeTop) * ratio
                paint.color = c0
                canvas.drawLine(w / 2f, fillTop, w / 2f, safeBot, paint)

                val playY = safeBot + dpFn(18f)
                val playR = icoSize * 0.75f
                drawPlayCircleIcon(canvas, w / 2f, playY, icoSize * 1.05f, c0, c5, paint)
                hitPlayPauseButton.set(w / 2f - playR, playY - playR, w / 2f + playR, playY + playR)

                val swapY = cardRect.bottom - max(rad * 0.35f, dpFn(12f))
                paint.style = Paint.Style.FILL
                paint.color = c4
                paint.textSize = txtPx * 0.85f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("⇄", w / 2f, swapY, paint)
                hitSwapSideButton.set(left, swapY - dpFn(16f), left + pillW, cardRect.bottom)
                canvas.restore()
            }

            // 12. אולפן זהב כהה אנכי (סמל ערוץ וקטורי מותאם בזהב + בלוטות')
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

                val iconY = cardRect.bottom - safeInset * 0.78f
                if (streamType == 0) {
                    drawMusicNoteIcon(canvas, w / 2f, iconY, icoSize * 0.9f, c5, paint, btConnected, 12)
                } else {
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, iconY, icoSize * 0.9f, c5, 12, vol, streamType, btConnected, paint
                    )
                }
                hitStreamCycleButton.set(cardRect.left, iconY - dpFn(20f), cardRect.right, cardRect.bottom)

                paint.color = c5
                paint.textSize = txtPx * 0.88f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, cardRect.top + safeInset * 0.85f, paint)
                canvas.restore()
            }

            // 13. בר עליון צמוד קצה עם סמל ערוץ דינמי + בלוטות'
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

                val sideInset = max(rad * 0.85f, dpFn(22f))
                if (streamType == 0) {
                    drawMusicNoteIcon(canvas, cardRect.left + sideInset, cardRect.centerY(), icoSize * 0.85f, c4, paint, btConnected, 13)
                } else {
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, cardRect.left + sideInset, cardRect.centerY(), icoSize * 0.85f, c4, 13, vol, streamType, btConnected, paint
                    )
                }
                hitStreamCycleButton.set(cardRect.left, cardRect.top, cardRect.left + sideInset * 2.2f, cardRect.bottom)

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

            // 14. קפסולת Windows / Emoji אנכית עם סמל ערוץ תואם + בלוטות'
            14 -> {
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
                val spkY = cardRect.bottom - safeInset * 0.82f
                VectorIconRenderer.drawActiveStreamIcon(
                    canvas, w / 2f, spkY, icoSize * 0.9f, c5, 14, vol, streamType, btConnected, paint
                )
                hitStreamCycleButton.set(left, spkY - dpFn(18f), left + pillW, cardRect.bottom)

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

            // 15. סייבר-טק HUD טלמטריה (עם סמל ערוץ סייבר + בלוטות' בראש הלוח!)
            15 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, cornerPx, cornerPx, paint)

                // כותרת הערוץ הפעיל + סמל סייבר מותאם אישית בראש ה-HUD
                val headerTitle = AudioEngine.getActiveStreamShortTitle()
                paint.color = c4
                paint.textSize = txtPx * 0.90f
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(headerTitle, cardRect.left + dpFn(10f), cardRect.top + dpFn(20f), paint)

                val hudIconX = cardRect.right - dpFn(22f)
                val hudIconY = cardRect.top + dpFn(16f)
                VectorIconRenderer.drawActiveStreamIcon(
                    canvas, hudIconX, hudIconY, icoSize * 0.80f, c0, 15, vol, streamType, btConnected, paint
                )
                hitStreamCycleButton.set(cardRect.left, cardRect.top, cardRect.right, cardRect.top + dpFn(30f))

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpFn(1.6f)
                paint.color = c3
                val brkLen = dpFn(12f)
                val hudTop = cardRect.top + dpFn(28f)
                val hudBot = cardRect.bottom - dpFn(10f)
                val hudLeft = cardRect.left + dpFn(8f)
                val hudRight = cardRect.right - dpFn(8f)

                canvas.drawLine(hudLeft, hudTop + brkLen, hudLeft, hudTop, paint)
                canvas.drawLine(hudLeft, hudTop, hudLeft + brkLen, hudTop, paint)
                canvas.drawLine(hudRight - brkLen, hudTop, hudRight, hudTop, paint)
                canvas.drawLine(hudRight, hudTop, hudRight, hudTop + brkLen, paint)
                canvas.drawLine(hudLeft, hudBot - brkLen, hudLeft, hudBot, paint)
                canvas.drawLine(hudLeft, hudBot, hudLeft + brkLen, hudBot, paint)
                canvas.drawLine(hudRight - brkLen, hudBot, hudRight, hudBot, paint)
                canvas.drawLine(hudRight, hudBot, hudRight, hudBot - brkLen, paint)

                val tubeW = dpFn(14f + (cfg.extraParam1 / 100f) * 12f)
                val tubeCx = cardRect.left + cardRect.width() * 0.36f
                val tubeTop = hudTop + dpFn(12f)
                val tubeBot = hudBot - dpFn(12f)
                val tubeRad = tubeW / 2f

                pillRect.set(tubeCx - tubeW / 2f, tubeTop, tubeCx + tubeW / 2f, tubeBot)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpFn(2.2f)
                paint.shader = LinearGradient(0f, tubeTop, 0f, tubeBot, c1, c0, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(pillRect, tubeRad, tubeRad, paint)
                paint.shader = null

                val innerInset = dpFn(2.5f)
                fillRect.set(pillRect.left + innerInset, pillRect.top + innerInset, pillRect.right - innerInset, pillRect.bottom - innerInset)
                val innerRad = (tubeW - innerInset * 2f) / 2f

                canvas.save()
                innerClipPath.reset()
                innerClipPath.addRoundRect(fillRect, innerRad, innerRad, Path.Direction.CW)
                canvas.clipPath(innerClipPath)

                val fillY = fillRect.bottom - fillRect.height() * ratio
                thumbRect.set(fillRect.left, fillY, fillRect.right, fillRect.bottom)
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(0f, fillY, 0f, fillRect.bottom, Color.WHITE, c0, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(thumbRect, innerRad, innerRad, paint)
                paint.shader = null
                canvas.restore()

                val ticksCount = (16 + (cfg.extraParam2 / 100f) * 16).toInt()
                val tickStartX = pillRect.right + dpFn(6f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpFn(1.2f)
                for (t in 0..ticksCount) {
                    val ty = tubeTop + (tubeBot - tubeTop) * (t.toFloat() / ticksCount)
                    val isMajor = (t % 4 == 0)
                    val tickLen = if (isMajor) dpFn(9f) else dpFn(4.5f)
                    val isBelowFill = ty >= fillY
                    paint.color = if (isBelowFill) c0 else c1
                    canvas.drawLine(tickStartX, ty, tickStartX + tickLen, ty, paint)
                }

                val dotX = pillRect.left - dpFn(8f)
                for (d in 0 until 6) {
                    val dy = tubeTop + (tubeBot - tubeTop) * (0.35f + d * 0.06f)
                    paint.style = if (d < 3) Paint.Style.FILL else Paint.Style.STROKE
                    paint.strokeWidth = dpFn(1f)
                    paint.color = c0
                    canvas.drawCircle(dotX, dy, dpFn(2f), paint)
                }

                val clampedThumbY = fillY.coerceIn(tubeTop + dpFn(12f), tubeBot - dpFn(12f))
                val handleW = tubeW * 1.85f
                val handleH = dpFn(18f)
                thumbRect.set(tubeCx - handleW / 2f, clampedThumbY - handleH / 2f, tubeCx + handleW / 2f, clampedThumbY + handleH / 2f)

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(65, Color.red(c0), Color.green(c0), Color.blue(c0))
                canvas.drawRoundRect(thumbRect, dpFn(4f), dpFn(4f), paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpFn(1.8f)
                paint.color = c5
                canvas.drawRoundRect(thumbRect, dpFn(4f), dpFn(4f), paint)

                paint.color = Color.WHITE
                canvas.drawLine(thumbRect.left + dpFn(4f), clampedThumbY, thumbRect.right - dpFn(4f), clampedThumbY, paint)

                val badgeLeft = thumbRect.right + dpFn(4f)
                val badgeRight = (cardRect.right - dpFn(4f)).coerceAtLeast(badgeLeft + dpFn(32f))
                badgeRect.set(badgeLeft, clampedThumbY - dpFn(11f), badgeRight, clampedThumbY + dpFn(11f))

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(140, Color.red(c2), Color.green(c2), Color.blue(c2))
                canvas.drawRoundRect(badgeRect, dpFn(3f), dpFn(3f), paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpFn(1.2f)
                paint.color = c3
                canvas.drawRoundRect(badgeRect, dpFn(3f), dpFn(3f), paint)

                paint.style = Paint.Style.FILL
                paint.color = c4
                paint.textSize = txtPx * 0.82f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", badgeRect.centerX(), clampedThumbY + dpFn(4f), paint)
            }

            // 16. כרטיסיית זכוכית חלבית עם פס ניאון דק + סמל ערוץ מתחלף/בלוטות'/רטט/השתק
            16 -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                val rad = cornerPx.coerceAtMost(cardRect.width() * 0.45f)

                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    0f, cardRect.top, w, cardRect.bottom,
                    c2,
                    Color.argb(Color.alpha(c2), 255, 235, 240),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(cardRect, rad, rad, paint)
                paint.shader = null

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, rad, rad, paint)
                }

                val leftColX = cardRect.left + cardRect.width() * 0.33f
                val rightColX = cardRect.left + cardRect.width() * 0.72f

                // בועית עליונה המציגה את סמל הערוץ הפעיל (רמקול+בלוטות' / צלצול / התראות / שעון / טלפון)
                val spkCircleR = dpFn(17f + (cfg.extraParam2 / 100f) * 6f)
                val spkCy = cardRect.top + dpFn(32f)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(95, Color.red(c5), Color.green(c5), Color.blue(c5))
                canvas.drawCircle(leftColX, spkCy, spkCircleR, paint)

                VectorIconRenderer.drawActiveStreamIcon(
                    canvas, leftColX, spkCy, icoSize * 0.95f, c4, 16, vol, streamType, btConnected, paint
                )
                // לחיצה על הבועית העליונה מחליפה בין ערוצי השמע (מדיה/צלצול/התראות/שעון/טלפון)!
                hitStreamCycleButton.set(leftColX - spkCircleR, spkCy - spkCircleR, leftColX + spkCircleR, spkCy + spkCircleR)

                val tubeW = dpFn(7f + (cfg.extraParam1 / 100f) * 7f)
                val tubeTop = spkCy + spkCircleR + dpFn(14f)
                val tubeBot = cardRect.bottom - dpFn(22f)
                val tubeRad = tubeW / 2f
                pillRect.set(leftColX - tubeW / 2f, tubeTop, leftColX + tubeW / 2f, tubeBot)

                canvas.save()
                innerClipPath.reset()
                innerClipPath.addRoundRect(pillRect, tubeRad, tubeRad, Path.Direction.CW)
                canvas.clipPath(innerClipPath)

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(45, Color.red(c0), Color.green(c0), Color.blue(c0))
                canvas.drawRect(pillRect, paint)

                val fillTop = tubeBot - (tubeBot - tubeTop) * ratio
                fillRect.set(pillRect.left, fillTop, pillRect.right, tubeBot)
                paint.shader = LinearGradient(0f, fillTop, 0f, tubeBot, c0, c1, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(fillRect, tubeRad, tubeRad, paint)
                paint.shader = null
                canvas.restore()

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpFn(2f)
                paint.color = c0
                canvas.drawRoundRect(pillRect, tubeRad, tubeRad, paint)

                // בעמודה הימנית: סמל הערוץ/התראות למעלה, רטט באמצע, השתק למטה
                val chSwitchCy = cardRect.top + dpFn(32f)
                val vibCy = tubeTop + (tubeBot - tubeTop) * 0.45f
                val muteCy = tubeTop + (tubeBot - tubeTop) * 0.82f
                val btnHitR = dpFn(22f)

                VectorIconRenderer.drawActiveStreamIcon(
                    canvas, rightColX, chSwitchCy, icoSize * 0.90f, c4, 16, vol, (streamType + 1) % 5, false, paint
                )
                hitSpeakerButton.set(rightColX - btnHitR, chSwitchCy - btnHitR, rightColX + btnHitR, chSwitchCy + btnHitR)

                drawVibrateModeIcon(canvas, rightColX, vibCy, icoSize * 1.05f, c4, paint)
                hitVibrateButton.set(rightColX - btnHitR, vibCy - btnHitR, rightColX + btnHitR, vibCy + btnHitR)

                drawMuteSlashIcon(canvas, rightColX, muteCy, icoSize * 1.05f, c4, paint)
                hitMuteButton.set(rightColX - btnHitR, muteCy - btnHitR, rightColX + btnHitR, muteCy + btnHitR)
            }

            // 17. כרטיסיית זכוכית חלבית עם קפסולת קשת בצבעים משתנים בהדרגה + סמל ערוץ ובלוטות'
            else -> {
                cardRect.set(pad, availTop, w - pad, availBot)
                val rad = cornerPx.coerceAtMost(cardRect.width() * 0.42f)

                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(cardRect, rad, rad, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(cardRect, rad, rad, paint)
                }

                val speedFactor = (0.3f + (cfg.extraParam2 / 100f) * 1.7f)
                val timeSec = (SystemClock.uptimeMillis() / 1000f) * speedFactor
                val hueShift = (timeSec * 65f) % 360f

                val baseColors = intArrayOf(
                    c0, c1, c4, Color.parseColor("#8B00FF"), c5
                )
                val animatedColors = IntArray(baseColors.size) { idx ->
                    val hsv = FloatArray(3)
                    Color.colorToHSV(baseColors[idx], hsv)
                    hsv[0] = (hsv[0] + hueShift) % 360f
                    Color.HSVToColor(hsv)
                }

                val pillW = cardRect.width() * (0.34f + (cfg.extraParam1 / 100f) * 0.16f)
                val pillLeft = cardRect.left + dpFn(12f)
                val pillTop = cardRect.top + dpFn(14f)
                val pillBot = cardRect.bottom - dpFn(14f)
                val pillRad = pillW / 2f
                pillRect.set(pillLeft, pillTop, pillLeft + pillW, pillBot)

                canvas.save()
                innerClipPath.reset()
                innerClipPath.addRoundRect(pillRect, pillRad, pillRad, Path.Direction.CW)
                canvas.clipPath(innerClipPath)

                paint.style = Paint.Style.FILL
                paint.color = Color.argb(38, 120, 130, 150)
                canvas.drawRect(pillRect, paint)

                val fillTop = pillBot - (pillBot - pillTop) * ratio
                fillRect.set(pillRect.left, fillTop, pillRect.right, pillBot)
                paint.shader = LinearGradient(
                    0f, pillTop, 0f, pillBot,
                    animatedColors, null, Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(fillRect, pillRad, pillRad, paint)
                paint.shader = null
                canvas.restore()

                // 3 סמלים בצד ימין בצבעים מתואמים: העליון מציג את הערוץ הפעיל + בלוטות' (וניתן ללחיצה להחלפת ערוץ)
                val rightIconX = pillRect.right + (cardRect.right - pillRect.right) * 0.52f
                val spkY = pillTop + (pillBot - pillTop) * 0.14f
                val vibY = pillTop + (pillBot - pillTop) * 0.50f
                val muteY = pillTop + (pillBot - pillTop) * 0.86f
                val hitR = dpFn(22f)

                VectorIconRenderer.drawActiveStreamIcon(
                    canvas, rightIconX, spkY, icoSize * 1.08f, animatedColors[1], 17, vol, streamType, btConnected, paint
                )
                hitStreamCycleButton.set(rightIconX - hitR, spkY - hitR, rightIconX + hitR, spkY + hitR)

                drawVibrateModeIcon(canvas, rightIconX, vibY, icoSize * 1.08f, animatedColors[2], paint)
                hitVibrateButton.set(rightIconX - hitR, vibY - hitR, rightIconX + hitR, vibY + hitR)

                drawMuteSlashIcon(canvas, rightIconX, muteY, icoSize * 1.08f, animatedColors[3], paint)
                hitMuteButton.set(rightIconX - hitR, muteY - hitR, rightIconX + hitR, muteY + hitR)
            }
        }
    }
}
 
