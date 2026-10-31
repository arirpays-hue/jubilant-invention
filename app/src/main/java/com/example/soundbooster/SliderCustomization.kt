package com.example.soundbooster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =========================================================================
// 1. מודל הגדרות אישיות נפרד לכל אחד מ-11 סוגי המחוונים
// =========================================================================
data class StyleConfig(
    var colorAccent: Int,
    var colorBackground: Int,
    var colorBorder: Int,
    var colorText: Int,
    var sliderAlphaPercent: Int,      // 15% - 100%
    var blurBackgroundEnabled: Boolean,
    var blurStrengthPercent: Int,     // 0% - 100% (טשטוש אמיתי ללא החשכה)
    var dimBackgroundEnabled: Boolean,
    var dimStrengthPercent: Int,      // 0% - 100% (השחרת מסך נפרדת)
    var widthDp: Int,                 // רוחב המחוון ב-DP
    var heightDp: Int,                // אורך/גובה המחוון ב-DP
    var posXPercent: Int,             // מיקום אופקי במסך (0% שמאל עד 100% ימין)
    var posYPercent: Int,             // מיקום אנכי במסך (0% למעלה עד 100% למטה)
    var cornerRadiusDp: Int           // רדיוס פינות
)

object SliderThemeManager {

    private val defaultConfigs = mapOf(
        // 0: Native SystemUI Badge
        0 to StyleConfig(
            colorAccent = Color.parseColor("#3B607A"),
            colorBackground = Color.parseColor("#EEF2F6"),
            colorBorder = Color.parseColor("#3B607A"),
            colorText = Color.parseColor("#1B2C38"),
            sliderAlphaPercent = 95,
            blurBackgroundEnabled = false, blurStrengthPercent = 30,
            dimBackgroundEnabled = false, dimStrengthPercent = 0,
            widthDp = 120, heightDp = 50, posXPercent = 94, posYPercent = 25, cornerRadiusDp = 24
        ),
        // 1: Glassmorphism (עיצוב הזכוכית)
        1 to StyleConfig(
            colorAccent = Color.parseColor("#00E5FF"),
            colorBackground = Color.parseColor("#E8F4FF"), // רקע בהיר זכוכיתי כברירת מחדל
            colorBorder = Color.parseColor("#FFFFFF"),
            colorText = Color.parseColor("#0B192C"),
            sliderAlphaPercent = 65,
            blurBackgroundEnabled = true, blurStrengthPercent = 70,
            dimBackgroundEnabled = false, dimStrengthPercent = 15,
            widthDp = 84, heightDp = 280, posXPercent = 94, posYPercent = 50, cornerRadiusDp = 34
        ),
        // 2: Simple Minimal (עיצוב פשוט)
        2 to StyleConfig(
            colorAccent = Color.parseColor("#00E676"),
            colorBackground = Color.parseColor("#F5F7FA"),
            colorBorder = Color.parseColor("#D0D8E8"),
            colorText = Color.parseColor("#161B26"),
            sliderAlphaPercent = 92,
            blurBackgroundEnabled = false, blurStrengthPercent = 20,
            dimBackgroundEnabled = false, dimStrengthPercent = 0,
            widthDp = 64, heightDp = 260, posXPercent = 95, posYPercent = 50, cornerRadiusDp = 28
        ),
        // 3: Retro Android Holo (אנדרואיד ישן אופקי)
        3 to StyleConfig(
            colorAccent = Color.parseColor("#33B5E5"),
            colorBackground = Color.parseColor("#222222"),
            colorBorder = Color.parseColor("#33B5E5"),
            colorText = Color.parseColor("#FFFFFF"),
            sliderAlphaPercent = 95,
            blurBackgroundEnabled = false, blurStrengthPercent = 0,
            dimBackgroundEnabled = true, dimStrengthPercent = 25,
            widthDp = 310, heightDp = 88, posXPercent = 50, posYPercent = 12, cornerRadiusDp = 6
        ),
        // 4: Cyberpunk Neon (סייבר ניאון)
        4 to StyleConfig(
            colorAccent = Color.parseColor("#FF007F"),
            colorBackground = Color.parseColor("#0D0221"),
            colorBorder = Color.parseColor("#00F0FF"),
            colorText = Color.parseColor("#00F0FF"),
            sliderAlphaPercent = 90,
            blurBackgroundEnabled = true, blurStrengthPercent = 50,
            dimBackgroundEnabled = true, dimStrengthPercent = 35,
            widthDp = 88, heightDp = 290, posXPercent = 94, posYPercent = 50, cornerRadiusDp = 4
        ),
        // 5: Studio Rotary Knob (חוגת אולפן)
        5 to StyleConfig(
            colorAccent = Color.parseColor("#FFD740"),
            colorBackground = Color.parseColor("#1E232A"),
            colorBorder = Color.parseColor("#4E5869"),
            colorText = Color.parseColor("#FFD740"),
            sliderAlphaPercent = 94,
            blurBackgroundEnabled = true, blurStrengthPercent = 40,
            dimBackgroundEnabled = false, dimStrengthPercent = 20,
            widthDp = 125, heightDp = 240, posXPercent = 90, posYPercent = 50, cornerRadiusDp = 28
        ),
        // 6: Live Sound Wave (גלי קול)
        6 to StyleConfig(
            colorAccent = Color.parseColor("#BB86FC"),
            colorBackground = Color.parseColor("#141824"),
            colorBorder = Color.parseColor("#BB86FC"),
            colorText = Color.parseColor("#FFFFFF"),
            sliderAlphaPercent = 88,
            blurBackgroundEnabled = true, blurStrengthPercent = 55,
            dimBackgroundEnabled = false, dimStrengthPercent = 15,
            widthDp = 86, heightDp = 285, posXPercent = 94, posYPercent = 50, cornerRadiusDp = 30
        ),
        // 7: Cupertino iOS Pill (קפסולת iOS)
        7 to StyleConfig(
            colorAccent = Color.parseColor("#FFFFFF"),
            colorBackground = Color.parseColor("#D8E0E8"),
            colorBorder = Color.parseColor("#FFFFFF"),
            colorText = Color.parseColor("#1C2536"),
            sliderAlphaPercent = 78,
            blurBackgroundEnabled = true, blurStrengthPercent = 80,
            dimBackgroundEnabled = false, dimStrengthPercent = 10,
            widthDp = 76, heightDp = 270, posXPercent = 94, posYPercent = 50, cornerRadiusDp = 38
        ),
        // 8: 8-Bit Arcade (רטרו פיקסל)
        8 to StyleConfig(
            colorAccent = Color.parseColor("#39FF14"),
            colorBackground = Color.parseColor("#111111"),
            colorBorder = Color.parseColor("#39FF14"),
            colorText = Color.parseColor("#39FF14"),
            sliderAlphaPercent = 96,
            blurBackgroundEnabled = false, blurStrengthPercent = 0,
            dimBackgroundEnabled = true, dimStrengthPercent = 30,
            widthDp = 88, heightDp = 280, posXPercent = 94, posYPercent = 50, cornerRadiusDp = 0
        ),
        // 9: Edge Glow Bar (פס קצה מסך)
        9 to StyleConfig(
            colorAccent = Color.parseColor("#00E5FF"),
            colorBackground = Color.parseColor("#161B26"),
            colorBorder = Color.parseColor("#00E5FF"),
            colorText = Color.parseColor("#00E5FF"),
            sliderAlphaPercent = 90,
            blurBackgroundEnabled = false, blurStrengthPercent = 30,
            dimBackgroundEnabled = false, dimStrengthPercent = 0,
            widthDp = 58, heightDp = 320, posXPercent = 99, posYPercent = 50, cornerRadiusDp = 18
        ),
        // 10: LED Matrix 200% (מטריצת נוריות)
        10 to StyleConfig(
            colorAccent = Color.parseColor("#00E676"),
            colorBackground = Color.parseColor("#10141D"),
            colorBorder = Color.parseColor("#2A3447"),
            colorText = Color.parseColor("#FFFFFF"),
            sliderAlphaPercent = 92,
            blurBackgroundEnabled = true, blurStrengthPercent = 45,
            dimBackgroundEnabled = false, dimStrengthPercent = 20,
            widthDp = 90, heightDp = 295, posXPercent = 94, posYPercent = 50, cornerRadiusDp = 22
        )
    )

    val configs = mutableMapOf<Int, StyleConfig>()

    init {
        resetAllToDefaults()
    }

    private fun resetAllToDefaults() {
        for ((k, v) in defaultConfigs) {
            configs[k] = v.copy()
        }
    }

    fun getCurrentConfig(): StyleConfig {
        return configs.getOrPut(AudioEngine.sliderStyleIndex) {
            defaultConfigs[1]!!.copy()
        }
    }

    fun loadAllStyleConfigs(context: Context) {
        val prefs = context.getSharedPreferences("sound_booster_per_style_v2", Context.MODE_PRIVATE)
        for (idx in 0..10) {
            val def = defaultConfigs[idx] ?: defaultConfigs[1]!!
            configs[idx] = StyleConfig(
                colorAccent = prefs.getInt("s_${idx}_accent", def.colorAccent),
                colorBackground = prefs.getInt("s_${idx}_bg", def.colorBackground),
                colorBorder = prefs.getInt("s_${idx}_border", def.colorBorder),
                colorText = prefs.getInt("s_${idx}_text", def.colorText),
                sliderAlphaPercent = prefs.getInt("s_${idx}_alpha", def.sliderAlphaPercent),
                blurBackgroundEnabled = prefs.getBoolean("s_${idx}_blur_en", def.blurBackgroundEnabled),
                blurStrengthPercent = prefs.getInt("s_${idx}_blur_str", def.blurStrengthPercent),
                dimBackgroundEnabled = prefs.getBoolean("s_${idx}_dim_en", def.dimBackgroundEnabled),
                dimStrengthPercent = prefs.getInt("s_${idx}_dim_str", def.dimStrengthPercent),
                widthDp = prefs.getInt("s_${idx}_w", def.widthDp),
                heightDp = prefs.getInt("s_${idx}_h", def.heightDp),
                posXPercent = prefs.getInt("s_${idx}_x", def.posXPercent),
                posYPercent = prefs.getInt("s_${idx}_y", def.posYPercent),
                cornerRadiusDp = prefs.getInt("s_${idx}_rad", def.cornerRadiusDp)
            )
        }
    }

    fun saveStyleConfig(context: Context, idx: Int = AudioEngine.sliderStyleIndex) {
        val cfg = configs[idx] ?: return
        context.getSharedPreferences("sound_booster_per_style_v2", Context.MODE_PRIVATE).edit()
            .putInt("s_${idx}_accent", cfg.colorAccent)
            .putInt("s_${idx}_bg", cfg.colorBackground)
            .putInt("s_${idx}_border", cfg.colorBorder)
            .putInt("s_${idx}_text", cfg.colorText)
            .putInt("s_${idx}_alpha", cfg.sliderAlphaPercent)
            .putBoolean("s_${idx}_blur_en", cfg.blurBackgroundEnabled)
            .putInt("s_${idx}_blur_str", cfg.blurStrengthPercent)
            .putBoolean("s_${idx}_dim_en", cfg.dimBackgroundEnabled)
            .putInt("s_${idx}_dim_str", cfg.dimStrengthPercent)
            .putInt("s_${idx}_w", cfg.widthDp)
            .putInt("s_${idx}_h", cfg.heightDp)
            .putInt("s_${idx}_x", cfg.posXPercent)
            .putInt("s_${idx}_y", cfg.posYPercent)
            .putInt("s_${idx}_rad", cfg.cornerRadiusDp)
            .apply()
    }

    fun resetStyleToDefault(context: Context, idx: Int = AudioEngine.sliderStyleIndex) {
        val def = defaultConfigs[idx] ?: return
        configs[idx] = def.copy()
        saveStyleConfig(context, idx)
    }
}

// =========================================================================
// 2. גלגל צבעים אמיתי שמגיב לבהירות ורוויה בתוך הגלגל עצמו בלי להזיז את הבורר!
// =========================================================================
class TrueColorWheelView(context: Context) : View(context) {
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#3A465E")
    }
    private val selectorOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val selectorInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.BLACK
    }
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    var currentHue = 190f        // 0..360
    var currentSat = 0.85f       // 0..1 (מרחק מהמרכז: 0 = לבן/בהיר לגמרי, 1 = צבע מלא)
    var currentVal = 1.0f        // 0..1 (בהירות הגלגל כולו)
    var onWheelColorChanged: ((Int, Float, Float) -> Unit)? = null

    // שינוי בהירות או רוויה מהמחוונים מעדכן את צבעי הגלגל עצמו בלי להזיז את הבורר!
    fun updateValueFromSlider(newVal: Float) {
        currentVal = newVal.coerceIn(0.05f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.88f
        if (radius <= 0f) return

        // יצירת 13 גווני בסיס לפי רמת הבהירות הנוכחית (currentVal) כך שהגלגל עצמו מתבהר/מתכהה!
        val hueColors = IntArray(13) { i ->
            val h = (i * 30f) % 360f
            Color.HSVToColor(floatArrayOf(h, 1f, currentVal))
        }
        val sweepShader = SweepGradient(cx, cy, hueColors, null)

        // מרכז הגלגל הוא צבע בהיר/לבן (Saturation = 0) בבהירות הנוכחית, כדי לאפשר בחירת רקעים בהירים!
        val centerLightColor = Color.HSVToColor(floatArrayOf(0f, 0f, currentVal))
        val radialShader = RadialGradient(
            cx, cy, radius,
            centerLightColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )

        wheelPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, radius, wheelPaint)

        valOverlayPaint.shader = radialShader
        canvas.drawCircle(cx, cy, radius, valOverlayPaint)

        canvas.drawCircle(cx, cy, radius, borderPaint)

        // מיקום הבורר נשאר קבוע לפי הזווית (Hue) והמרחק (Saturation)
        val angleRad = Math.toRadians(currentHue.toDouble())
        val dist = currentSat * radius
        val sx = (cx + cos(angleRad) * dist).toFloat()
        val sy = (cy + sin(angleRad) * dist).toFloat()

        val activeColor = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
        previewFillPaint.color = activeColor
        canvas.drawCircle(sx, sy, 20f, previewFillPaint)
        canvas.drawCircle(sx, sy, 20f, selectorOuterPaint)
        canvas.drawCircle(sx, sy, 22f, selectorInnerPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(cx, cy) * 0.88f
            val dx = event.x - cx
            val dy = event.y - cy
            val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

            currentSat = (dist / radius).coerceIn(0f, 1f)
            var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angle < 0) angle += 360f
            currentHue = angle

            val color = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
            invalidate()
            onWheelColorChanged?.invoke(color, currentHue, currentSat)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onTouchEvent(event)
    }
}

// =========================================================================
// 3. מנוע ציור סמלים וקטוריים מותאמים לכל מחוון (מחליף את כל האימוג'ים!)
// =========================================================================
object VectorIconRenderer {
    private val iconPath = Path()
    private val arcRect = RectF()

    // מצייר סמל רמקול וקטורי מותאם לסגנון המחוון (זכוכית, ניאון, רטרו 8-ביט, אולפן וכו')
    fun drawStyledSpeakerIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        styleIndex: Int,
        volumePercent: Int,
        paint: Paint
    ) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        val prevCap = paint.strokeCap

        when (styleIndex) {
            // סגנון 8-Bit מפוקסל
            8 -> {
                paint.style = Paint.Style.FILL
                paint.color = color
                val u = size * 0.16f
                canvas.drawRect(cx - 3 * u, cy - u, cx - u, cy + u, paint)
                canvas.drawRect(cx - u, cy - 2 * u, cx, cy + 2 * u, paint)
                canvas.drawRect(cx, cy - 3 * u, cx + u, cy + 3 * u, paint)
                if (volumePercent > 0) {
                    canvas.drawRect(cx + 2 * u, cy - u, cx + 3 * u, cy + u, paint)
                }
                if (volumePercent > 100) {
                    canvas.drawRect(cx + 3.5f * u, cy - 2.5f * u, cx + 4.5f * u, cy + 2.5f * u, paint)
                }
            }
            // סגנון סייבר ניאון (ברק + רמקול זוויתי)
            4 -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.13f
                paint.color = color
                paint.strokeCap = Paint.Cap.SQUARE

                iconPath.reset()
                iconPath.moveTo(cx - size * 0.42f, cy - size * 0.18f)
                iconPath.lineTo(cx - size * 0.15f, cy - size * 0.18f)
                iconPath.lineTo(cx + size * 0.12f, cy - size * 0.45f)
                iconPath.lineTo(cx + size * 0.12f, cy + size * 0.45f)
                iconPath.lineTo(cx - size * 0.15f, cy + size * 0.18f)
                iconPath.lineTo(cx - size * 0.42f, cy + size * 0.18f)
                iconPath.close()
                canvas.drawPath(iconPath, paint)

                // ברק ניאון מימין לרמקול
                iconPath.reset()
                iconPath.moveTo(cx + size * 0.35f, cy - size * 0.4f)
                iconPath.lineTo(cx + size * 0.2f, cy)
                iconPath.lineTo(cx + size * 0.42f, cy)
                iconPath.lineTo(cx + size * 0.25f, cy + size * 0.4f)
                canvas.drawPath(iconPath, paint)
            }
            // כל שאר העיצובים: סמל רמקול וקטורי אלגנטי עם גלי קול מדורגים
            else -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.12f
                paint.color = color
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND

                val s = size * 0.48f
                iconPath.reset()
                iconPath.moveTo(cx - s * 0.85f, cy - s * 0.32f)
                iconPath.lineTo(cx - s * 0.35f, cy - s * 0.32f)
                iconPath.lineTo(cx + s * 0.15f, cy - s * 0.82f)
                iconPath.lineTo(cx + s * 0.15f, cy + s * 0.82f)
                iconPath.lineTo(cx - s * 0.35f, cy + s * 0.32f)
                iconPath.lineTo(cx - s * 0.85f, cy + s * 0.32f)
                iconPath.close()
                canvas.drawPath(iconPath, paint)

                if (volumePercent == 0) {
                    canvas.drawLine(cx + s * 0.35f, cy - s * 0.35f, cx + s * 0.85f, cy + s * 0.35f, paint)
                    canvas.drawLine(cx + s * 0.85f, cy - s * 0.35f, cx + s * 0.35f, cy + s * 0.35f, paint)
                } else {
                    arcRect.set(cx - s * 0.1f, cy - s * 0.5f, cx + s * 0.55f, cy + s * 0.5f)
                    canvas.drawArc(arcRect, -45f, 90f, false, paint)
                    if (volumePercent >= 80) {
                        arcRect.set(cx - s * 0.25f, cy - s * 0.85f, cx + s * 0.9f, cy + s * 0.85f)
                        canvas.drawArc(arcRect, -45f, 90f, false, paint)
                    }
                }
            }
        }

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
        paint.strokeCap = prevCap
    }
}

// =========================================================================
// 4. תצוגת המחוון הצף המותאם אישית (כולל מצב עריכת מיקום וגודל על המסך!)
// =========================================================================
class CustomThemedSliderOverlayView(
    context: Context,
    private val onVolumeDragged: (Int) -> Unit,
    private val onPositionDragged: (Int, Int) -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val innerRect = RectF()
    private val clipPath = Path()

    var isEditPositionMode: Boolean = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        )
    }

    private fun applyAlpha(color: Int, alphaPercent: Int): Int {
        val a = ((alphaPercent / 100f) * 255).roundToInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cfg = SliderThemeManager.getCurrentConfig()
        val vol = AudioEngine.currentTotalVolume.coerceIn(0, 200)
        val ratio = vol / 200f
        val alphaPct = cfg.sliderAlphaPercent

        val bgCol = applyAlpha(cfg.colorBackground, alphaPct)
        val accCol = applyAlpha(cfg.colorAccent, (alphaPct + 12).coerceAtMost(100))
        val brdCol = applyAlpha(cfg.colorBorder, (alphaPct + 10).coerceAtMost(100))
        val txtCol = cfg.colorText
        val cornerPx = dp(cfg.cornerRadiusDp.toFloat())

        when (AudioEngine.sliderStyleIndex) {
            // 1. עיצוב הזכוכית היוקרתי (Glassmorphism)
            1 -> {
                val pad = dp(6f)
                rect.set(pad, pad, w - pad, h - pad)

                // שכבת זכוכית חלבית עם השתקפות עדינה (תומך גם ברקעים בהירים!)
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    0f, 0f, w, h,
                    applyAlpha(cfg.colorBackground, alphaPct),
                    applyAlpha(Color.WHITE, (alphaPct * 0.55f).roundToInt()),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                paint.shader = null

                val trackPad = pad + dp(10f)
                val trackTop = trackPad + dp(30f)
                val trackBot = h - trackPad - dp(32f)
                val fillTop = trackBot - (trackBot - trackTop) * ratio

                innerRect.set(trackPad, trackTop, w - trackPad, trackBot)
                paint.color = applyAlpha(cfg.colorBorder, 28)
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                innerRect.set(trackPad, fillTop, w - trackPad, trackBot)
                paint.shader = LinearGradient(
                    0f, fillTop, 0f, trackBot,
                    accCol, applyAlpha(cfg.colorAccent, 60), Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)
                paint.shader = null

                // מסגרת זכוכית מוארת
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.8f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                // אחוזים + סמל רמקול וקטורי מותאם לזכוכית
                paint.style = Paint.Style.FILL
                paint.color = txtCol
                paint.textSize = dp(12.5f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, pad + dp(23f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, h - pad - dp(16f), dp(18f), txtCol, 1, vol, paint
                )
            }

            // 2. עיצוב פשוט ונקי (Simple Minimal)
            2 -> {
                val barW = w * 0.44f
                val left = (w - barW) / 2f
                rect.set(left, dp(14f), left + barW, h - dp(14f))
                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val fillTop = rect.bottom - rect.height() * ratio
                innerRect.set(left, fillTop, left + barW, rect.bottom)
                paint.color = accCol
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.2f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                paint.style = Paint.Style.FILL
                paint.color = txtCol
                paint.textSize = dp(11.5f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(18f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(16f), dp(15f), txtCol, 2, vol, paint
                )
            }

            // 3. עיצוב אנדרואיד ישן קלאסי (Retro Android Holo Horizontal)
            3 -> {
                rect.set(dp(6f), dp(6f), w - dp(6f), h - dp(6f))
                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val lineY = h * 0.64f
                val startX = dp(52f)
                val endX = w - dp(22f)
                val thumbX = startX + (endX - startX) * ratio

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, dp(28f), lineY - dp(4f), dp(20f), accCol, 3, vol, paint
                )

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(3f)
                paint.color = applyAlpha(txtCol, 45)
                canvas.drawLine(startX, lineY, endX, lineY, paint)

                paint.strokeWidth = dp(4.5f)
                paint.color = accCol
                canvas.drawLine(startX, lineY, thumbX, lineY, paint)

                paint.style = Paint.Style.FILL
                canvas.drawCircle(thumbX, lineY, dp(8f), paint)

                paint.color = txtCol
                paint.textSize = dp(12.5f)
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText("עוצמת שמע: $vol%", startX, h * 0.35f, paint)
            }

            // 4. סייבר ניאון זוהר (Cyberpunk Neon)
            4 -> {
                val pad = dp(8f)
                rect.set(pad, pad, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val trackTop = rect.top + dp(34f)
                val trackBot = rect.bottom - dp(34f)
                val fillTop = trackBot - (trackBot - trackTop) * ratio

                innerRect.set(rect.left + dp(10f), fillTop, rect.right - dp(10f), trackBot)
                paint.style = Paint.Style.FILL
                paint.color = accCol
                canvas.drawRoundRect(innerRect, cornerPx * 0.5f, cornerPx * 0.5f, paint)

                paint.color = txtCol
                paint.textSize = dp(12.5f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(22f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(18f), dp(18f), txtCol, 4, vol, paint
                )
            }

            // 5. חוגת אולפן אנלוגית (Studio Rotary Knob & VU)
            5 -> {
                val pad = dp(6f)
                rect.set(pad, pad, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.8f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val cx = w / 2f
                val cy = h * 0.46f
                val r = min(w, h) * 0.34f
                innerRect.set(cx - r, cy - r, cx + r, cy + r)

                paint.strokeWidth = dp(7f)
                paint.color = applyAlpha(txtCol, 35)
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(innerRect, 135f, 270f, false, paint)

                paint.color = accCol
                canvas.drawArc(innerRect, 135f, 270f * ratio, false, paint)

                paint.style = Paint.Style.FILL
                paint.color = txtCol
                paint.textSize = dp(13f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", cx, cy + dp(5f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, cx, h - dp(24f), dp(18f), txtCol, 5, vol, paint
                )
            }

            // 6. גלי קול דינמיים (Live Sound Wave)
            6 -> {
                val pad = dp(6f)
                rect.set(pad, pad, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val bars = 15
                val topSpace = rect.top + dp(32f)
                val botSpace = rect.bottom - dp(32f)
                val stepY = (botSpace - topSpace) / bars
                val activeBars = (bars * ratio).roundToInt()

                paint.style = Paint.Style.FILL
                for (i in 0 until bars) {
                    val idxFromBottom = bars - 1 - i
                    val y = topSpace + i * stepY
                    val waveFactor = 0.32f + 0.52f * abs(sin(i * 0.65f))
                    val bw = rect.width() * waveFactor
                    val left = (w - bw) / 2f

                    paint.color = if (idxFromBottom < activeBars) accCol else applyAlpha(txtCol, 28)
                    innerRect.set(left, y, left + bw, y + stepY * 0.65f)
                    canvas.drawRoundRect(innerRect, dp(4f), dp(4f), paint)
                }

                paint.color = txtCol
                paint.textSize = dp(12f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(22f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(16f), dp(16f), txtCol, 6, vol, paint
                )
            }

            // 7. קפסולת iOS מודרנית (Cupertino Pill)
            7 -> {
                val pillW = w * 0.68f
                val left = (w - pillW) / 2f
                rect.set(left, dp(8f), left + pillW, h - dp(8f))
                val rad = cornerPx.coerceAtLeast(pillW / 2f)

                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, rad, rad, paint)

                val fillTop = rect.bottom - rect.height() * ratio
                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                innerRect.set(left, fillTop, left + pillW, rect.bottom)
                paint.color = accCol
                canvas.drawRect(innerRect, paint)
                canvas.restore()

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.2f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, rad, rad, paint)

                paint.style = Paint.Style.FILL
                paint.color = txtCol
                paint.textSize = dp(12f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(24f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(22f), dp(18f), txtCol, 7, vol, paint
                )
            }

            // 8. רטרו ארקייד 8-Bit (Pixel Blocks)
            8 -> {
                val pad = dp(8f)
                rect.set(pad, pad, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRect(rect, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2.5f)
                paint.color = brdCol
                canvas.drawRect(rect, paint)

                val blocks = 12
                val startY = rect.top + dp(30f)
                val endY = rect.bottom - dp(30f)
                val blockH = (endY - startY) / blocks
                val lit = (blocks * ratio).roundToInt()

                paint.style = Paint.Style.FILL
                for (i in 0 until blocks) {
                    val idxFromBottom = blocks - 1 - i
                    paint.color = if (idxFromBottom < lit) accCol else applyAlpha(txtCol, 24)
                    val by = startY + i * blockH
                    canvas.drawRect(rect.left + dp(8f), by + 2f, rect.right - dp(8f), by + blockH - 3f, paint)
                }

                paint.color = txtCol
                paint.textSize = dp(11f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(20f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(15f), dp(16f), txtCol, 8, vol, paint
                )
            }

            // 9. תאורת קצה מסך (Edge Glow Bar)
            9 -> {
                val barW = dp(14f)
                val isRight = cfg.posXPercent >= 50
                val xPos = if (isRight) w - barW - dp(4f) else dp(4f)
                rect.set(xPos, dp(10f), xPos + barW, h - dp(10f))

                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val fillTop = rect.bottom - rect.height() * ratio
                innerRect.set(xPos, fillTop, xPos + barW, rect.bottom)
                paint.color = accCol
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                paint.color = txtCol
                paint.textSize = dp(11.5f)
                paint.textAlign = Paint.Align.CENTER
                val textX = if (isRight) xPos - dp(22f) else xPos + barW + dp(22f)
                val safeY = fillTop.coerceIn(dp(26f), h - dp(26f))
                canvas.drawText("$vol%", textX, safeY, paint)
            }

            // 10. מטריצת נוריות אולפן (LED Matrix 200%)
            else -> {
                val pad = dp(6f)
                rect.set(pad, pad, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = bgCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = brdCol
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val rows = 18
                val cols = 3
                val topY = rect.top + dp(30f)
                val botY = rect.bottom - dp(30f)
                val cellH = (botY - topY) / rows
                val cellW = (rect.width() - dp(18f)) / cols
                val activeRows = (rows * ratio).roundToInt()

                paint.style = Paint.Style.FILL
                for (r in 0 until rows) {
                    val fromBottom = rows - 1 - r
                    val isLit = fromBottom < activeRows
                    paint.color = if (isLit) accCol else applyAlpha(txtCol, 22)
                    val cy = topY + r * cellH
                    for (c in 0 until cols) {
                        val cx = rect.left + dp(9f) + c * cellW
                        innerRect.set(cx + 2f, cy + 2f, cx + cellW - 2f, cy + cellH - 2f)
                        canvas.drawRoundRect(innerRect, dp(2f), dp(2f), paint)
                    }
                }

                paint.color = txtCol
                paint.textSize = dp(12f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(21f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(15f), dp(16f), txtCol, 10, vol, paint
                )
            }
        }

        // אם מצב עריכת מיקום וגודל פעיל, מצייר מסגרת הכוונה לידיעת המשתמש
        if (isEditPositionMode) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.parseColor("#00E676")
            rect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
            canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEditPositionMode) {
            // במצב עריכה: גרירת המחוון עם האצבע משנה את המיקום שלו על המסך בזמן אמת!
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - lastRawX).roundToInt()
                    val dy = (event.rawY - lastRawY).roundToInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    onPositionDragged(dx, dy)
                    return true
                }
            }
            return true
        }

        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val ratio = if (AudioEngine.sliderStyleIndex == 3) {
                (event.x / width.toFloat()).coerceIn(0f, 1f)
            } else {
                1f - (event.y / height.toFloat()).coerceIn(0f, 1f)
            }
            val newVol = (ratio * 200).roundToInt().coerceIn(0, 200)
            AudioEngine.currentTotalVolume = newVol
            invalidate()
            onVolumeDragged(newVol)
            return true
        }
        return super.onTouchEvent(event)
    }
}
 
