package com.example.soundbooster

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
// 1. מפרט ייעודי לכל מחוון בנפרד (שמות הצבעים, האם יש מסגרת, ואלמנטים ייחודיים)
// =========================================================================
data class StyleCapabilities(
    val styleIndex: Int,
    val colorSlotNames: List<String>, // עד 6 צבעים מותאמים בדיוק לאלמנטים של אותו מחוון
    val hasBorder: Boolean,
    val hasCornerRadius: Boolean,
    val hasIcon: Boolean,
    val extraElementLabel1: String?,  // למשל: "מספר עמודות גל" או "עובי טבעת חוגה"
    val extraElementLabel2: String?   // למשל: "מרווח בין קוביות" או "עוצמת ברק זכוכית"
)

data class StyleConfig(
    var colors: IntArray,             // 6 חריצי צבעים ייחודיים למחוון
    var sliderAlphaPercent: Int,      // 15% - 100%
    var blurBackgroundEnabled: Boolean,
    var blurStrengthPercent: Int,     // 0% - 100%
    var dimBackgroundEnabled: Boolean,
    var dimStrengthPercent: Int,      // 0% - 100%
    var widthDp: Int,
    var heightDp: Int,
    var posXPercent: Int,
    var posYPercent: Int,
    var cornerRadiusDp: Int,
    var borderWidthDp: Int,           // עובי מסגרת (אם קיימת במחוון)
    var iconSizePercent: Int,         // גודל סמל וקטורי (50% - 160%)
    var textSizeSp: Int,              // גודל טקסט אחוזים (9 - 22 sp)
    var extraParam1: Int,             // 0 - 100 (פרמטר אלמנט ייחודי 1)
    var extraParam2: Int              // 0 - 100 (פרמטר אלמנט ייחודי 2)
) {
    fun copyConfig(): StyleConfig = copy(colors = colors.clone())
}

object SliderThemeManager {

    // הגדרת היכולות והצבעים המדויקים של כל אחד מ-11 המחוונים
    val styleCapabilities = mapOf(
        0 to StyleCapabilities(
            styleIndex = 0,
            colorSlotNames = listOf("רקע בועית HD", "מסגרת בועית", "טקסט אחוזים"),
            hasBorder = true, hasCornerRadius = true, hasIcon = false,
            extraElementLabel1 = "ריווח פנימי של הבועית",
            extraElementLabel2 = null
        ),
        1 to StyleCapabilities(
            styleIndex = 1,
            colorSlotNames = listOf("נוזל זכוכית עליון", "נוזל זכוכית תחתון", "רקע זכוכית חלבית", "מסגרת זכוכית", "טקסט וסמל", "ברק השתקפות עליון"),
            hasBorder = true, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "עוצמת ברק השתקפות הזכוכית",
            extraElementLabel2 = "עובי צינור המילוי הפנימי"
        ),
        2 to StyleCapabilities(
            styleIndex = 2,
            colorSlotNames = listOf("פס מילוי ראשי", "מסילה אחורית", "טקסט וסמל"),
            hasBorder = false, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "עובי פס המילוי ביחס לרוחב",
            extraElementLabel2 = null
        ),
        3 to StyleCapabilities(
            styleIndex = 3,
            colorSlotNames = listOf("פס התקדמות פעיל", "ידית גרירה עגולה", "מסילה אפורה", "רקע תיבת דיאלוג", "מסגרת תיבה", "טקסט וסמל רמקול"),
            hasBorder = true, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "גודל ידית הגרירה העגולה",
            extraElementLabel2 = "עובי פס ההתקדמות האופקי"
        ),
        4 to StyleCapabilities(
            styleIndex = 4,
            colorSlotNames = listOf("מילוי ניאון ראשי", "הילת ניאון משנית", "רקע סייבר כהה", "מסגרת ניאון חיצונית", "טקסט דיגיטלי", "סמל ברק ורמקול"),
            hasBorder = true, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "עוצמת זוהר פנימי (Cyber Glow)",
            extraElementLabel2 = "מרווח מסגרת כפולה"
        ),
        5 to StyleCapabilities(
            styleIndex = 5,
            colorSlotNames = listOf("קשת חוגה פעילה", "קשת שיא ( מעל 150% )", "מסילת חוגה כבויה", "רקע קופסת אולפן", "מסגרת אולפן", "טקסט וסמל"),
            hasBorder = true, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "עובי טבעת החוגה האנלוגית",
            extraElementLabel2 = "רדיוס קשת החוגה"
        ),
        6 to StyleCapabilities(
            styleIndex = 6,
            colorSlotNames = listOf("גלים תחתונים (0-100%)", "גלים עליונים (100-200%)", "גלים כבויים", "רקע מחוון", "מסגרת מחוון", "טקסט וסמל"),
            hasBorder = true, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "כמות עמודות גלי הקול",
            extraElementLabel2 = "מרווח אנכי בין הגלים"
        ),
        7 to StyleCapabilities(
            styleIndex = 7,
            colorSlotNames = listOf("מילוי קפסולה", "רקע קפסולה", "מסגרת עדינה", "טקסט וסמל פנימי"),
            hasBorder = true, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "רוחב קפסולה פנימית",
            extraElementLabel2 = null
        ),
        8 to StyleCapabilities(
            styleIndex = 8,
            colorSlotNames = listOf("קוביות 8-Bit דולקות", "קוביות שיא (200%)", "קוביות כבויות", "רקע ארקייד", "מסגרת פיקסל", "טקסט וסמל פיקסל"),
            hasBorder = true, hasCornerRadius = false, hasIcon = true,
            extraElementLabel1 = "מספר קוביות פיקסל",
            extraElementLabel2 = "מרווח בין קוביות"
        ),
        9 to StyleCapabilities(
            styleIndex = 9,
            colorSlotNames = listOf("פס תאורת קצה", "רקע מסילה דקה", "טקסט אחוזים צף"),
            hasBorder = false, hasCornerRadius = true, hasIcon = false,
            extraElementLabel1 = "עובי פס הקצה",
            extraElementLabel2 = "מרחק הטקסט מפס הקצה"
        ),
        10 to StyleCapabilities(
            styleIndex = 10,
            colorSlotNames = listOf("נוריות ירוקות (רגיל)", "נוריות צהובות (מוגבר)", "נוריות אדומות (שיא 200%)", "נוריות כבויות", "רקע לוח מטריצה", "מסגרת וטקסט"),
            hasBorder = true, hasCornerRadius = true, hasIcon = true,
            extraElementLabel1 = "מספר שורות נוריות LED",
            extraElementLabel2 = "מספר עמודות נוריות LED"
        )
    )

    private val defaultConfigs = mapOf(
        0 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#EEF2F6"), Color.parseColor("#3B607A"), Color.parseColor("#1B2C38"),
                Color.WHITE, Color.WHITE, Color.WHITE
            ),
            sliderAlphaPercent = 95, blurBackgroundEnabled = false, blurStrengthPercent = 30,
            dimBackgroundEnabled = false, dimStrengthPercent = 0,
            widthDp = 125, heightDp = 52, posXPercent = 94, posYPercent = 25,
            cornerRadiusDp = 24, borderWidthDp = 2, iconSizePercent = 100, textSizeSp = 13,
            extraParam1 = 50, extraParam2 = 50
        ),
        1 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#00E5FF"), Color.parseColor("#0077FF"), Color.parseColor("#E8F4FF"),
                Color.parseColor("#FFFFFF"), Color.parseColor("#0B192C"), Color.parseColor("#FFFFFF")
            ),
            sliderAlphaPercent = 68, blurBackgroundEnabled = true, blurStrengthPercent = 65,
            dimBackgroundEnabled = false, dimStrengthPercent = 15,
            widthDp = 86, heightDp = 285, posXPercent = 94, posYPercent = 50,
            cornerRadiusDp = 34, borderWidthDp = 2, iconSizePercent = 100, textSizeSp = 13,
            extraParam1 = 70, extraParam2 = 65
        ),
        2 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#00E676"), Color.parseColor("#222B3A"), Color.parseColor("#FFFFFF"),
                Color.WHITE, Color.WHITE, Color.WHITE
            ),
            sliderAlphaPercent = 92, blurBackgroundEnabled = false, blurStrengthPercent = 20,
            dimBackgroundEnabled = false, dimStrengthPercent = 0,
            widthDp = 64, heightDp = 260, posXPercent = 95, posYPercent = 50,
            cornerRadiusDp = 28, borderWidthDp = 0, iconSizePercent = 100, textSizeSp = 12,
            extraParam1 = 60, extraParam2 = 50
        ),
        3 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#33B5E5"), Color.parseColor("#00E5FF"), Color.parseColor("#444E5E"),
                Color.parseColor("#1B2028"), Color.parseColor("#33B5E5"), Color.parseColor("#FFFFFF")
            ),
            sliderAlphaPercent = 95, blurBackgroundEnabled = false, blurStrengthPercent = 0,
            dimBackgroundEnabled = false, dimStrengthPercent = 20,
            widthDp = 310, heightDp = 90, posXPercent = 50, posYPercent = 12,
            cornerRadiusDp = 8, borderWidthDp = 2, iconSizePercent = 100, textSizeSp = 13,
            extraParam1 = 55, extraParam2 = 50
        ),
        4 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#FF007F"), Color.parseColor("#00F0FF"), Color.parseColor("#0D0221"),
                Color.parseColor("#00F0FF"), Color.parseColor("#00F0FF"), Color.parseColor("#FF007F")
            ),
            sliderAlphaPercent = 92, blurBackgroundEnabled = true, blurStrengthPercent = 50,
            dimBackgroundEnabled = false, dimStrengthPercent = 30,
            widthDp = 88, heightDp = 290, posXPercent = 94, posYPercent = 50,
            cornerRadiusDp = 6, borderWidthDp = 3, iconSizePercent = 105, textSizeSp = 13,
            extraParam1 = 75, extraParam2 = 50
        ),
        5 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#00E676"), Color.parseColor("#FF5252"), Color.parseColor("#323B4D"),
                Color.parseColor("#181D26"), Color.parseColor("#4E5869"), Color.parseColor("#FFD740")
            ),
            sliderAlphaPercent = 94, blurBackgroundEnabled = true, blurStrengthPercent = 40,
            dimBackgroundEnabled = false, dimStrengthPercent = 20,
            widthDp = 128, heightDp = 240, posXPercent = 90, posYPercent = 50,
            cornerRadiusDp = 28, borderWidthDp = 2, iconSizePercent = 100, textSizeSp = 14,
            extraParam1 = 60, extraParam2 = 70
        ),
        6 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#00E5FF"), Color.parseColor("#BB86FC"), Color.parseColor("#2A3346"),
                Color.parseColor("#141824"), Color.parseColor("#BB86FC"), Color.parseColor("#FFFFFF")
            ),
            sliderAlphaPercent = 90, blurBackgroundEnabled = true, blurStrengthPercent = 50,
            dimBackgroundEnabled = false, dimStrengthPercent = 15,
            widthDp = 88, heightDp = 285, posXPercent = 94, posYPercent = 50,
            cornerRadiusDp = 30, borderWidthDp = 2, iconSizePercent = 100, textSizeSp = 12,
            extraParam1 = 60, extraParam2 = 50
        ),
        7 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#FFFFFF"), Color.parseColor("#2B3548"), Color.parseColor("#80FFFFFF"),
                Color.parseColor("#00E5FF"), Color.WHITE, Color.WHITE
            ),
            sliderAlphaPercent = 82, blurBackgroundEnabled = true, blurStrengthPercent = 75,
            dimBackgroundEnabled = false, dimStrengthPercent = 10,
            widthDp = 78, heightDp = 270, posXPercent = 94, posYPercent = 50,
            cornerRadiusDp = 38, borderWidthDp = 1, iconSizePercent = 100, textSizeSp = 12,
            extraParam1 = 75, extraParam2 = 50
        ),
        8 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#39FF14"), Color.parseColor("#FF2A55"), Color.parseColor("#222831"),
                Color.parseColor("#0F1217"), Color.parseColor("#39FF14"), Color.parseColor("#39FF14")
            ),
            sliderAlphaPercent = 96, blurBackgroundEnabled = false, blurStrengthPercent = 0,
            dimBackgroundEnabled = false, dimStrengthPercent = 25,
            widthDp = 88, heightDp = 280, posXPercent = 94, posYPercent = 50,
            cornerRadiusDp = 0, borderWidthDp = 3, iconSizePercent = 100, textSizeSp = 12,
            extraParam1 = 55, extraParam2 = 45
        ),
        9 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#00E5FF"), Color.parseColor("#1B2434"), Color.parseColor("#00E5FF"),
                Color.WHITE, Color.WHITE, Color.WHITE
            ),
            sliderAlphaPercent = 92, blurBackgroundEnabled = false, blurStrengthPercent = 30,
            dimBackgroundEnabled = false, dimStrengthPercent = 0,
            widthDp = 64, heightDp = 310, posXPercent = 99, posYPercent = 50,
            cornerRadiusDp = 18, borderWidthDp = 0, iconSizePercent = 100, textSizeSp = 12,
            extraParam1 = 55, extraParam2 = 60
        ),
        10 to StyleConfig(
            colors = intArrayOf(
                Color.parseColor("#00E676"), Color.parseColor("#FFD740"), Color.parseColor("#FF5252"),
                Color.parseColor("#222B38"), Color.parseColor("#10141D"), Color.parseColor("#FFFFFF")
            ),
            sliderAlphaPercent = 94, blurBackgroundEnabled = true, blurStrengthPercent = 45,
            dimBackgroundEnabled = false, dimStrengthPercent = 20,
            widthDp = 92, heightDp = 295, posXPercent = 94, posYPercent = 50,
            cornerRadiusDp = 22, borderWidthDp = 2, iconSizePercent = 100, textSizeSp = 12,
            extraParam1 = 65, extraParam2 = 50
        )
    )

    val configs = mutableMapOf<Int, StyleConfig>()

    init {
        for ((k, v) in defaultConfigs) {
            configs[k] = v.copyConfig()
        }
    }

    fun getCurrentCapabilities(): StyleCapabilities {
        return styleCapabilities[AudioEngine.sliderStyleIndex] ?: styleCapabilities[1]!!
    }

    fun getCurrentConfig(): StyleConfig {
        return configs.getOrPut(AudioEngine.sliderStyleIndex) {
            defaultConfigs[1]!!.copyConfig()
        }
    }

    fun loadAllStyleConfigs(context: Context) {
        val prefs = context.getSharedPreferences("sound_booster_per_style_v3", Context.MODE_PRIVATE)
        for (idx in 0..10) {
            val def = defaultConfigs[idx] ?: defaultConfigs[1]!!
            val loadedColors = IntArray(6) { cIdx ->
                prefs.getInt("s_${idx}_c_$cIdx", def.colors.getOrElse(cIdx) { Color.WHITE })
            }
            configs[idx] = StyleConfig(
                colors = loadedColors,
                sliderAlphaPercent = prefs.getInt("s_${idx}_alpha", def.sliderAlphaPercent),
                blurBackgroundEnabled = prefs.getBoolean("s_${idx}_blur_en", def.blurBackgroundEnabled),
                blurStrengthPercent = prefs.getInt("s_${idx}_blur_str", def.blurStrengthPercent),
                dimBackgroundEnabled = prefs.getBoolean("s_${idx}_dim_en", def.dimBackgroundEnabled),
                dimStrengthPercent = prefs.getInt("s_${idx}_dim_str", def.dimStrengthPercent),
                widthDp = prefs.getInt("s_${idx}_w", def.widthDp),
                heightDp = prefs.getInt("s_${idx}_h", def.heightDp),
                posXPercent = prefs.getInt("s_${idx}_x", def.posXPercent),
                posYPercent = prefs.getInt("s_${idx}_y", def.posYPercent),
                cornerRadiusDp = prefs.getInt("s_${idx}_rad", def.cornerRadiusDp),
                borderWidthDp = prefs.getInt("s_${idx}_brd_w", def.borderWidthDp),
                iconSizePercent = prefs.getInt("s_${idx}_ico_s", def.iconSizePercent),
                textSizeSp = prefs.getInt("s_${idx}_txt_s", def.textSizeSp),
                extraParam1 = prefs.getInt("s_${idx}_ex1", def.extraParam1),
                extraParam2 = prefs.getInt("s_${idx}_ex2", def.extraParam2)
            )
        }
    }

    fun saveStyleConfig(context: Context, idx: Int = AudioEngine.sliderStyleIndex) {
        val cfg = configs[idx] ?: return
        val editor = context.getSharedPreferences("sound_booster_per_style_v3", Context.MODE_PRIVATE).edit()
        for (cIdx in 0..5) {
            editor.putInt("s_${idx}_c_$cIdx", cfg.colors[cIdx])
        }
        editor.putInt("s_${idx}_alpha", cfg.sliderAlphaPercent)
            .putBoolean("s_${idx}_blur_en", cfg.blurBackgroundEnabled)
            .putInt("s_${idx}_blur_str", cfg.blurStrengthPercent)
            .putBoolean("s_${idx}_dim_en", cfg.dimBackgroundEnabled)
            .putInt("s_${idx}_dim_str", cfg.dimStrengthPercent)
            .putInt("s_${idx}_w", cfg.widthDp)
            .putInt("s_${idx}_h", cfg.heightDp)
            .putInt("s_${idx}_x", cfg.posXPercent)
            .putInt("s_${idx}_y", cfg.posYPercent)
            .putInt("s_${idx}_rad", cfg.cornerRadiusDp)
            .putInt("s_${idx}_brd_w", cfg.borderWidthDp)
            .putInt("s_${idx}_ico_s", cfg.iconSizePercent)
            .putInt("s_${idx}_txt_s", cfg.textSizeSp)
            .putInt("s_${idx}_ex1", cfg.extraParam1)
            .putInt("s_${idx}_ex2", cfg.extraParam2)
            .apply()
    }

    fun resetStyleToDefault(context: Context, idx: Int = AudioEngine.sliderStyleIndex) {
        val def = defaultConfigs[idx] ?: return
        configs[idx] = def.copyConfig()
        saveStyleConfig(context, idx)
    }
}

// =========================================================================
// 2. גלגל צבעים שבו כל 3 המחוונים משנים את הגלגל עצמו בלי להזיז את הבורר!
// =========================================================================
class TrueColorWheelView(context: Context) : View(context) {
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    // מיקום הבורר הפיזי על הגלגל (נעול וזז רק כשנוגעים בגלגל עצמו!)
    private var pointerAngleDeg = 190f
    private var pointerRadiusRatio = 0.85f

    // 3 פרמטרים מהמחוונים שמשנים את מראה הגלגל עצמו בזמן אמת
    private var wheelRotationOffsetDeg = 0f // 0..360 (מחוון גוון מסובב את צבעי הגלגל עצמו)
    private var wheelMaxSaturation = 1.0f   // 0..1 (מחוון רוויה משנה את רוויית כל הגלגל)
    private var wheelBrightnessVal = 1.0f   // 0.05..1 (מחוון בהירות משנה את בהירות כל הגלגל)

    var onWheelColorChanged: ((Int) -> Unit)? = null

    fun setColorFromExternal(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        wheelRotationOffsetDeg = 0f
        pointerAngleDeg = hsv[0]
        pointerRadiusRatio = 1f
        wheelMaxSaturation = hsv[1]
        wheelBrightnessVal = hsv[2].coerceAtLeast(0.05f)
        invalidate()
    }

    fun getHueSliderValue(): Int = ((pointerAngleDeg + wheelRotationOffsetDeg) % 360f).roundToInt()
    fun getSatSliderValue(): Int = ((pointerRadiusRatio * wheelMaxSaturation) * 100f).roundToInt().coerceIn(0, 100)
    fun getValSliderValue(): Int = (wheelBrightnessVal * 100f).roundToInt().coerceIn(5, 100)

    // שלוש הפונקציות הללו משנות את הצבעים המצוירים בתוך הגלגל עצמו מבלי להזיז את הבורר!
    fun updateWheelBrightness(valPercent: Int): Int {
        wheelBrightnessVal = (valPercent / 100f).coerceIn(0.05f, 1f)
        invalidate()
        return computeCurrentSelectedColor()
    }

    fun updateWheelSaturationScale(satPercent: Int): Int {
        val ratio = pointerRadiusRatio.coerceAtLeast(0.05f)
        wheelMaxSaturation = ((satPercent / 100f) / ratio).coerceIn(0f, 1f)
        invalidate()
        return computeCurrentSelectedColor()
    }

    fun updateWheelHueShift(targetHueDeg: Int): Int {
        var shift = targetHueDeg.toFloat() - pointerAngleDeg
        while (shift < 0f) shift += 360f
        wheelRotationOffsetDeg = shift % 360f
        invalidate()
        return computeCurrentSelectedColor()
    }

    fun computeCurrentSelectedColor(): Int {
        val effectiveHue = (pointerAngleDeg + wheelRotationOffsetDeg) % 360f
        val effectiveSat = (pointerRadiusRatio * wheelMaxSaturation).coerceIn(0f, 1f)
        return Color.HSVToColor(floatArrayOf(effectiveHue, effectiveSat, wheelBrightnessVal))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.88f
        if (radius <= 0f) return

        // ציור היקף הגלגל לפי היסט הגוון, הרוויה המקסימלית והבהירות שנבחרו במחוונים
        val hueColors = IntArray(13) { i ->
            val h = (i * 30f + wheelRotationOffsetDeg) % 360f
            Color.HSVToColor(floatArrayOf(h, wheelMaxSaturation, wheelBrightnessVal))
        }
        wheelPaint.shader = SweepGradient(cx, cy, hueColors, null)
        canvas.drawCircle(cx, cy, radius, wheelPaint)

        // מרכז הגלגל מאפשר צבעים בהירים/לבנים (Saturation = 0) בבהירות הנוכחית
        val centerColor = Color.HSVToColor(floatArrayOf(0f, 0f, wheelBrightnessVal))
        centerOverlayPaint.shader = RadialGradient(
            cx, cy, radius,
            centerColor, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, centerOverlayPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)

        // הבורר נשאר בדיוק בזווית ובמרחק שלו בזמן שהגלגל מתחתיו משתנה!
        val angleRad = Math.toRadians(pointerAngleDeg.toDouble())
        val dist = pointerRadiusRatio * radius
        val sx = (cx + cos(angleRad) * dist).toFloat()
        val sy = (cy + sin(angleRad) * dist).toFloat()

        previewFillPaint.color = computeCurrentSelectedColor()
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

            pointerRadiusRatio = (dist / radius).coerceIn(0f, 1f)
            var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            if (angle < 0) angle += 360f
            pointerAngleDeg = angle

            invalidate()
            onWheelColorChanged?.invoke(computeCurrentSelectedColor())
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onTouchEvent(event)
    }
}

// =========================================================================
// 3. ציור סמלים וקטוריים מותאמים לכל מחוון
// =========================================================================
object VectorIconRenderer {
    private val iconPath = Path()
    private val arcRect = RectF()

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
        if (size <= 2f) return
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        val prevCap = paint.strokeCap

        when (styleIndex) {
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

                iconPath.reset()
                iconPath.moveTo(cx + size * 0.35f, cy - size * 0.4f)
                iconPath.lineTo(cx + size * 0.2f, cy)
                iconPath.lineTo(cx + size * 0.42f, cy)
                iconPath.lineTo(cx + size * 0.25f, cy + size * 0.4f)
                canvas.drawPath(iconPath, paint)
            }
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
// 4. תצוגת המחוון המותאם אישית לכל סגנון (6 צבעים + אלמנטים + כפתור סיום עריכה)
// =========================================================================
class CustomThemedSliderOverlayView(
    context: Context,
    private val onVolumeDragged: (Int) -> Unit,
    private val onPositionDragged: (Int, Int) -> Unit,
    private val onFinishEditClicked: () -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val innerRect = RectF()
    private val doneBtnRect = RectF()
    private val clipPath = Path()

    var isEditPositionMode: Boolean = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }

    private fun sp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
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

        val c0 = applyAlpha(cfg.colors[0], (alphaPct + 10).coerceAtMost(100))
        val c1 = applyAlpha(cfg.colors[1], (alphaPct + 10).coerceAtMost(100))
        val c2 = applyAlpha(cfg.colors[2], alphaPct)
        val c3 = applyAlpha(cfg.colors[3], alphaPct)
        val c4 = applyAlpha(cfg.colors[4], (alphaPct + 10).coerceAtMost(100))
        val c5 = cfg.colors[5]

        val cornerPx = dp(cfg.cornerRadiusDp.toFloat())
        val brdW = dp(cfg.borderWidthDp.toFloat())
        val icoSize = dp(18f * (cfg.iconSizePercent / 100f))
        val txtPx = sp(cfg.textSizeSp.toFloat())

        // אם במצב עריכה, שומרים מקום בראש המחוון לכפתור "✓ סיים ושמור"
        val topEditOffset = if (isEditPositionMode) dp(36f) else 0f

        when (AudioEngine.sliderStyleIndex) {
            // 1. עיצוב הזכוכית (Glassmorphism - 6 צבעים ואלמנטים מותאמים)
            1 -> {
                val pad = dp(6f)
                rect.set(pad, pad + topEditOffset, w - pad, h - pad)

                val highlightStrength = (cfg.extraParam1 / 100f).coerceIn(0.1f, 1f)
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(
                    0f, rect.top, w, rect.bottom,
                    c2,
                    applyAlpha(cfg.colors[5], (alphaPct * highlightStrength).roundToInt()),
                    Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                paint.shader = null

                val tubeRatio = 0.35f + (cfg.extraParam2 / 100f) * 0.45f
                val tubeW = (rect.width() * tubeRatio).coerceAtLeast(dp(16f))
                val trackLeft = w / 2f - tubeW / 2f
                val trackRight = w / 2f + tubeW / 2f
                val trackTop = rect.top + dp(32f)
                val trackBot = rect.bottom - dp(34f)
                val fillTop = trackBot - (trackBot - trackTop) * ratio

                innerRect.set(trackLeft, trackTop, trackRight, trackBot)
                paint.color = applyAlpha(cfg.colors[3], 25)
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                innerRect.set(trackLeft, fillTop, trackRight, trackBot)
                paint.shader = LinearGradient(
                    0f, fillTop, 0f, trackBot,
                    c0, c1, Shader.TileMode.CLAMP
                )
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)
                paint.shader = null

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                }

                paint.style = Paint.Style.FILL
                paint.color = cfg.colors[4]
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(22f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(17f), icoSize, cfg.colors[4], 1, vol, paint
                )
            }

            // 2. עיצוב פשוט ונקי (Simple Minimal)
            2 -> {
                val widthFactor = 0.25f + (cfg.extraParam1 / 100f) * 0.6f
                val barW = w * widthFactor
                val left = (w - barW) / 2f
                rect.set(left, dp(12f) + topEditOffset, left + barW, h - dp(12f))
                paint.style = Paint.Style.FILL
                paint.color = c1
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val fillTop = rect.bottom - rect.height() * ratio
                innerRect.set(left, fillTop, left + barW, rect.bottom)
                paint.color = c0
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                paint.color = cfg.colors[2]
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(20f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(18f), icoSize, cfg.colors[2], 2, vol, paint
                )
            }

            // 3. עיצוב אנדרואיד ישן (Retro Android Holo Horizontal - 6 צבעים)
            3 -> {
                rect.set(dp(6f), dp(6f) + topEditOffset, w - dp(6f), h - dp(6f))
                paint.style = Paint.Style.FILL
                paint.color = c3
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c4
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                }

                val lineY = rect.top + rect.height() * 0.65f
                val startX = dp(52f)
                val endX = w - dp(22f)
                val thumbX = startX + (endX - startX) * ratio

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, dp(28f), lineY - dp(2f), icoSize, c5, 3, vol, paint
                )

                val lineThick = dp(2f + (cfg.extraParam2 / 100f) * 6f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = lineThick
                paint.color = c2
                canvas.drawLine(startX, lineY, endX, lineY, paint)

                paint.color = c0
                canvas.drawLine(startX, lineY, thumbX, lineY, paint)

                val thumbRad = dp(5f + (cfg.extraParam1 / 100f) * 8f)
                paint.style = Paint.Style.FILL
                paint.color = c1
                canvas.drawCircle(thumbX, lineY, thumbRad, paint)

                paint.color = c5
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText("עוצמת שמע: $vol%", startX, rect.top + rect.height() * 0.36f, paint)
            }

            // 4. סייבר ניאון זוהר (Cyberpunk Neon - 6 צבעים)
            4 -> {
                val pad = dp(8f)
                rect.set(pad, pad + topEditOffset, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = c2
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c3
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                }

                val innerGap = dp(4f + (cfg.extraParam2 / 100f) * 10f)
                val trackTop = rect.top + dp(34f)
                val trackBot = rect.bottom - dp(34f)
                val fillTop = trackBot - (trackBot - trackTop) * ratio

                innerRect.set(rect.left + innerGap, fillTop, rect.right - innerGap, trackBot)
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(0f, fillTop, 0f, trackBot, c1, c0, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(innerRect, cornerPx * 0.5f, cornerPx * 0.5f, paint)
                paint.shader = null

                paint.color = cfg.colors[4]
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(23f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(18f), icoSize, c5, 4, vol, paint
                )
            }

            // 5. חוגת אולפן אנלוגית (Studio Rotary Knob - 6 צבעים)
            5 -> {
                val pad = dp(6f)
                rect.set(pad, pad + topEditOffset, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = c3
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c4
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                }

                val cx = w / 2f
                val cy = rect.top + rect.height() * 0.46f
                val radScale = 0.24f + (cfg.extraParam2 / 100f) * 0.16f
                val r = min(w, rect.height()) * radScale
                innerRect.set(cx - r, cy - r, cx + r, cy + r)

                val ringStroke = dp(4f + (cfg.extraParam1 / 100f) * 8f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ringStroke
                paint.color = c2
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawArc(innerRect, 135f, 270f, false, paint)

                paint.color = if (vol > 150) c1 else c0
                canvas.drawArc(innerRect, 135f, 270f * ratio, false, paint)

                paint.style = Paint.Style.FILL
                paint.color = c5
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", cx, cy + dp(5f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, cx, rect.bottom - dp(22f), icoSize, c5, 5, vol, paint
                )
            }

            // 6. גלי קול דינמיים (Live Sound Wave - 6 צבעים)
            6 -> {
                val pad = dp(6f)
                rect.set(pad, pad + topEditOffset, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = c3
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c4
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                }

                val bars = (8 + (cfg.extraParam1 / 100f) * 16).roundToInt().coerceIn(6, 26)
                val topSpace = rect.top + dp(32f)
                val botSpace = rect.bottom - dp(32f)
                val stepY = (botSpace - topSpace) / bars
                val activeBars = (bars * ratio).roundToInt()
                val thicknessRatio = (0.85f - (cfg.extraParam2 / 100f) * 0.45f).coerceIn(0.3f, 0.85f)

                paint.style = Paint.Style.FILL
                for (i in 0 until bars) {
                    val idxFromBottom = bars - 1 - i
                    val y = topSpace + i * stepY
                    val waveFactor = 0.32f + 0.52f * abs(sin(i * 0.65f))
                    val bw = rect.width() * waveFactor
                    val left = (w - bw) / 2f

                    paint.color = when {
                        idxFromBottom >= activeBars -> c2
                        idxFromBottom >= bars / 2 -> c1
                        else -> c0
                    }
                    innerRect.set(left, y, left + bw, y + stepY * thicknessRatio)
                    canvas.drawRoundRect(innerRect, dp(4f), dp(4f), paint)
                }

                paint.color = c5
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(22f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(16f), icoSize, c5, 6, vol, paint
                )
            }

            // 7. קפסולת iOS מודרנית (Cupertino Pill)
            7 -> {
                val widthFactor = 0.45f + (cfg.extraParam1 / 100f) * 0.42f
                val pillW = w * widthFactor
                val left = (w - pillW) / 2f
                rect.set(left, dp(8f) + topEditOffset, left + pillW, h - dp(8f))
                val rad = cornerPx.coerceAtLeast(pillW / 2f)

                paint.style = Paint.Style.FILL
                paint.color = c1
                canvas.drawRoundRect(rect, rad, rad, paint)

                val fillTop = rect.bottom - rect.height() * ratio
                canvas.save()
                clipPath.reset()
                clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
                canvas.clipPath(clipPath)

                innerRect.set(left, fillTop, left + pillW, rect.bottom)
                paint.color = c0
                canvas.drawRect(innerRect, paint)
                canvas.restore()

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c2
                    canvas.drawRoundRect(rect, rad, rad, paint)
                }

                paint.style = Paint.Style.FILL
                paint.color = cfg.colors[3]
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(24f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(22f), icoSize, cfg.colors[3], 7, vol, paint
                )
            }

            // 8. רטרו ארקייד 8-Bit (Pixel Blocks - 6 צבעים)
            8 -> {
                val pad = dp(8f)
                rect.set(pad, pad + topEditOffset, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = c3
                canvas.drawRect(rect, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c4
                    canvas.drawRect(rect, paint)
                }

                val blocks = (6 + (cfg.extraParam1 / 100f) * 14).roundToInt().coerceIn(6, 20)
                val gapPx = dp(1f + (cfg.extraParam2 / 100f) * 5f)
                val startY = rect.top + dp(32f)
                val endY = rect.bottom - dp(32f)
                val blockH = (endY - startY) / blocks
                val lit = (blocks * ratio).roundToInt()

                paint.style = Paint.Style.FILL
                for (i in 0 until blocks) {
                    val idxFromBottom = blocks - 1 - i
                    paint.color = when {
                        idxFromBottom >= lit -> c2
                        idxFromBottom >= (blocks * 0.75f).roundToInt() -> c1
                        else -> c0
                    }
                    val by = startY + i * blockH
                    canvas.drawRect(rect.left + dp(8f), by + gapPx, rect.right - dp(8f), by + blockH - gapPx, paint)
                }

                paint.color = c5
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(21f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(15f), icoSize, c5, 8, vol, paint
                )
            }

            // 9. תאורת קצה מסך (Edge Glow Bar)
            9 -> {
                val barW = dp(6f + (cfg.extraParam1 / 100f) * 18f)
                val isRight = cfg.posXPercent >= 50
                val xPos = if (isRight) w - barW - dp(4f) else dp(4f)
                rect.set(xPos, dp(10f) + topEditOffset, xPos + barW, h - dp(10f))

                paint.style = Paint.Style.FILL
                paint.color = c1
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                val fillTop = rect.bottom - rect.height() * ratio
                innerRect.set(xPos, fillTop, xPos + barW, rect.bottom)
                paint.color = c0
                canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                paint.color = cfg.colors[2]
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                val distPx = dp(16f + (cfg.extraParam2 / 100f) * 20f)
                val textX = if (isRight) xPos - distPx else xPos + barW + distPx
                val safeY = fillTop.coerceIn(rect.top + dp(18f), h - dp(18f))
                canvas.drawText("$vol%", textX, safeY, paint)
            }

            // 10. מטריצת נוריות אולפן (LED Matrix 200% - 6 צבעים)
            else -> {
                val pad = dp(6f)
                rect.set(pad, pad + topEditOffset, w - pad, h - pad)
                paint.style = Paint.Style.FILL
                paint.color = c4
                canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                if (brdW > 0f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = brdW
                    paint.color = c5
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                }

                val rows = (10 + (cfg.extraParam1 / 100f) * 16).roundToInt().coerceIn(8, 26)
                val cols = (1 + (cfg.extraParam2 / 100f) * 4).roundToInt().coerceIn(1, 5)
                val topY = rect.top + dp(30f)
                val botY = rect.bottom - dp(30f)
                val cellH = (botY - topY) / rows
                val cellW = (rect.width() - dp(18f)) / cols
                val activeRows = (rows * ratio).roundToInt()

                paint.style = Paint.Style.FILL
                for (r in 0 until rows) {
                    val fromBottom = rows - 1 - r
                    val isLit = fromBottom < activeRows
                    paint.color = when {
                        !isLit -> c3
                        fromBottom >= (rows * 0.75f).roundToInt() -> c2
                        fromBottom >= (rows * 0.5f).roundToInt() -> c1
                        else -> c0
                    }
                    val cy = topY + r * cellH
                    for (c in 0 until cols) {
                        val cx = rect.left + dp(9f) + c * cellW
                        innerRect.set(cx + 2f, cy + 2f, cx + cellW - 2f, cy + cellH - 2f)
                        canvas.drawRoundRect(innerRect, dp(2f), dp(2f), paint)
                    }
                }

                paint.color = c5
                paint.textSize = txtPx
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("$vol%", w / 2f, rect.top + dp(21f), paint)

                VectorIconRenderer.drawStyledSpeakerIcon(
                    canvas, w / 2f, rect.bottom - dp(15f), icoSize, c5, 10, vol, paint
                )
            }
        }

        // במצב עריכת מיקום: מצייר כפתור "✓ סיים" בולט בראש המחוון כדי שלעולם לא ייתקע!
        if (isEditPositionMode) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = Color.parseColor("#00E676")
            rect.set(dp(2f), dp(2f), w - dp(2f), h - dp(2f))
            canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

            doneBtnRect.set(dp(4f), dp(4f), w - dp(4f), dp(34f))
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#00E676")
            canvas.drawRoundRect(doneBtnRect, dp(10f), dp(10f), paint)

            paint.color = Color.parseColor("#0B0E14")
            paint.textSize = dp(11.5f)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("✓ סיים", w / 2f, dp(23f), paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isEditPositionMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // לחיצה על כפתור "✓ סיים" הירוק סוגרת מיד את מצב העריכה ושומרת!
                    if (doneBtnRect.contains(event.x, event.y)) {
                        onFinishEditClicked()
                        return true
                    }
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
 
