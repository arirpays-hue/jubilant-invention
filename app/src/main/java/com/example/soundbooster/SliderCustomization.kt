package com.example.soundbooster

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =========================================================================
// 1. מפרט ייעודי לכל אחד מ-18 סוגי המחוונים + הגדרת סגירה בנגיעה מחוץ למחוון
// =========================================================================
data class StyleCapabilities(
    val styleIndex: Int,
    val colorSlotNames: List<String>,
    val hasBorder: Boolean,
    val hasCornerRadius: Boolean,
    val hasIcon: Boolean,
    val extraElementLabel1: String?,
    val extraElementLabel2: String?
)

data class StyleConfig(
    var colors: IntArray,
    var sliderAlphaPercent: Int,
    var blurBackgroundEnabled: Boolean,
    var blurStrengthPercent: Int,
    var dimBackgroundEnabled: Boolean,
    var dimStrengthPercent: Int,
    var widthDp: Int,
    var heightDp: Int,
    var posXPercent: Int,
    var posYPercent: Int,
    var cornerRadiusDp: Int,
    var borderWidthDp: Int,
    var iconSizePercent: Int,
    var textSizeSp: Int,
    var extraParam1: Int,
    var extraParam2: Int,
    var dismissOnOutsideTouch: Boolean = true
) {
    fun copyConfig(): StyleConfig = copy(colors = colors.clone())
}

object SliderThemeManager {

    const val TOTAL_STYLES_COUNT = 18

    val styleCapabilities = mapOf(
        0 to StyleCapabilities(
            0, listOf("רקע בועית HD", "מסגרת בועית", "טקסט אחוזים"),
            true, true, false, "ריווח פנימי של הבועית", null
        ),
        1 to StyleCapabilities(
            1, listOf("נוזל זכוכית עליון", "נוזל זכוכית תחתון", "רקע זכוכית חלבית", "מסגרת זכוכית", "טקסט וסמל", "ברק השתקפות עליון"),
            true, true, true, "עוצמת ברק השתקפות הזכוכית", "עובי צינור המילוי הפנימי"
        ),
        2 to StyleCapabilities(
            2, listOf("פס מילוי ראשי", "מסילה אחורית", "טקסט וסמל"),
            false, true, true, "עובי פס המילוי ביחס לרוחב", null
        ),
        3 to StyleCapabilities(
            3, listOf("פס התקדמות פעיל", "ידית גרירה עגולה", "מסילה אפורה", "רקע תיבת דיאלוג", "מסגרת תיבה", "טקסט וסמל רמקול"),
            true, true, true, "גודל ידית הגרירה העגולה", "עובי פס ההתקדמות האופקי"
        ),
        4 to StyleCapabilities(
            4, listOf("מילוי ניאון ראשי", "הילת ניאון משנית", "רקע סייבר כהה", "מסגרת ניאון חיצונית", "טקסט דיגיטלי", "סמל ברק ורמקול"),
            true, true, true, "עוצמת זוהר פנימי (Cyber Glow)", "מרווח מסגרת כפולה"
        ),
        5 to StyleCapabilities(
            5, listOf("קשת חוגה פעילה", "קשת שיא (טורבו)", "מסילת חוגה כבויה", "רקע קופסת אולפן", "מסגרת אולפן", "טקסט וסמל"),
            true, true, true, "עובי טבעת החוגה האנלוגית", "רדיוס קשת החוגה"
        ),
        6 to StyleCapabilities(
            6, listOf("גלים תחתונים", "גלים עליונים (מוגבר)", "גלים כבויים", "רקע מחוון", "מסגרת מחוון", "טקסט וסמל"),
            true, true, true, "כמות עמודות גלי הקול", "מרווח אנכי בין הגלים"
        ),
        7 to StyleCapabilities(
            7, listOf("מילוי קפסולה", "רקע קפסולה", "מסגרת עדינה", "טקסט וסמל פנימי"),
            true, true, true, "רוחב קפסולה פנימית", null
        ),
        8 to StyleCapabilities(
            8, listOf("קוביות 8-Bit דולקות", "קוביות שיא (טורבו)", "קוביות כבויות", "רקע ארקייד", "מסגרת פיקסל", "טקסט וסמל פיקסל"),
            true, false, true, "מספר קוביות פיקסל", "מרווח בין קוביות"
        ),
        9 to StyleCapabilities(
            9, listOf("פס תאורת קצה", "רקע מסילה דקה", "טקסט אחוזים צף"),
            false, true, false, "עובי פס הקצה", "מרחק הטקסט מפס הקצה"
        ),
        10 to StyleCapabilities(
            10, listOf("נוריות ירוקות (רגיל)", "נוריות צהובות (מוגבר)", "נוריות אדומות (שיא)", "נוריות כבויות", "רקע לוח מטריצה", "מסגרת וטקסט"),
            true, true, true, "מספר שורות נוריות LED", "מספר עמודות נוריות LED"
        ),
        11 to StyleCapabilities(
            11, listOf("קו מילוי וכפתור Play", "מסילה דקה אחורית", "רקע קפסולה לבנה/בהירה", "מסגרת קפסולה", "טקסט וחצים", "משולש Play פנימי"),
            true, true, true, "עובי קו המילוי הדק", "רוחב הקפסולה"
        ),
        12 to StyleCapabilities(
            12, listOf("קו זהב פעיל", "עיגול אחיזה (Knob)", "רקע עמודה כהה", "מסגרת עמודה", "מסילה אחורית דקה", "סמל תו וטקסט זהב"),
            true, true, true, "גודל עיגול האחיזה", "עובי קו הזהב"
        ),
        13 to StyleCapabilities(
            13, listOf("גרדיאנט שמאל", "גרדיאנט ימין", "רקע בר עליון", "מסגרת בר", "תו מוזיקה וטקסט", "צבע משני"),
            true, true, true, "עובי בר עליון", null
        ),
        14 to StyleCapabilities(
            14, listOf("מילוי עליון", "מילוי תחתון", "רקע קפסולה", "מסגרת קפסולה", "עיגול ראש מודגש", "סמל וטקסט"),
            true, true, true, "רוחב קפסולה", null
        ),
        15 to StyleCapabilities(
            15, listOf("ניאון ציאן ראשי", "ניאון מג'נטה עליון", "רקע לוח HUD כהה", "מסגרת וסוגרי HUD", "טקסט VOLUME ואחוזים", "מסגרת ידית גרירה"),
            true, true, true, "עובי צינור הניאון", "צפיפות שנתות טלמטריה"
        ),
        16 to StyleCapabilities(
            16, listOf("הילת צינור ציאן", "ליבת קורל-כתום פנימית", "רקע זכוכית חלבית", "מסגרת זכוכית", "צבע סמלי רמקול/רטט/השתק", "בועית רמקול עליונה"),
            true, true, true, "עובי צינור הניאון", "גודל בועית הרמקול"
        ),
        17 to StyleCapabilities(
            17, listOf("צבע קשת 1 (צהוב)", "צבע קשת 2 (כתום)", "רקע זכוכית חלבית", "מסגרת זכוכית", "צבע קשת 3 (מג'נטה)", "צבע קשת 4 (כחול)"),
            true, true, true, "רוחב קפסולת הקשת", "מהירות תנועת הצבעים"
        )
    )

    private val defaultConfigs = mapOf(
        0 to StyleConfig(
            intArrayOf(Color.parseColor("#EEF2F6"), Color.parseColor("#3B607A"), Color.parseColor("#1B2C38"), Color.WHITE, Color.WHITE, Color.WHITE),
            95, false, 30, false, 0, 125, 52, 94, 25, 24, 2, 100, 13, 50, 50, true
        ),
        1 to StyleConfig(
            intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#0077FF"), Color.parseColor("#E8F4FF"), Color.parseColor("#FFFFFF"), Color.parseColor("#0B192C"), Color.parseColor("#FFFFFF")),
            72, true, 65, false, 15, 76, 275, 94, 50, 36, 2, 95, 13, 70, 65, true
        ),
        2 to StyleConfig(
            intArrayOf(Color.parseColor("#00E676"), Color.parseColor("#222B3A"), Color.parseColor("#FFFFFF"), Color.WHITE, Color.WHITE, Color.WHITE),
            92, false, 20, false, 0, 64, 260, 95, 50, 30, 0, 95, 12, 60, 50, true
        ),
        3 to StyleConfig(
            intArrayOf(Color.parseColor("#33B5E5"), Color.parseColor("#00E5FF"), Color.parseColor("#444E5E"), Color.parseColor("#1B2028"), Color.parseColor("#33B5E5"), Color.parseColor("#FFFFFF")),
            95, false, 0, false, 20, 310, 90, 50, 12, 8, 2, 100, 13, 55, 50, true
        ),
        4 to StyleConfig(
            intArrayOf(Color.parseColor("#FF007F"), Color.parseColor("#00F0FF"), Color.parseColor("#0D0221"), Color.parseColor("#00F0FF"), Color.parseColor("#00F0FF"), Color.parseColor("#FF007F")),
            92, true, 50, false, 30, 84, 285, 94, 50, 8, 3, 100, 13, 75, 50, true
        ),
        5 to StyleConfig(
            intArrayOf(Color.parseColor("#00E676"), Color.parseColor("#FF5252"), Color.parseColor("#323B4D"), Color.parseColor("#181D26"), Color.parseColor("#4E5869"), Color.parseColor("#FFD740")),
            94, true, 40, false, 20, 126, 240, 90, 50, 28, 2, 100, 14, 60, 70, true
        ),
        6 to StyleConfig(
            intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#BB86FC"), Color.parseColor("#2A3346"), Color.parseColor("#141824"), Color.parseColor("#BB86FC"), Color.parseColor("#FFFFFF")),
            90, true, 50, false, 15, 84, 280, 94, 50, 30, 2, 95, 12, 60, 50, true
        ),
        7 to StyleConfig(
            intArrayOf(Color.parseColor("#FFFFFF"), Color.parseColor("#2B3548"), Color.parseColor("#80FFFFFF"), Color.parseColor("#101824"), Color.WHITE, Color.WHITE),
            85, true, 75, false, 10, 74, 265, 94, 50, 38, 1, 95, 12, 75, 50, true
        ),
        8 to StyleConfig(
            intArrayOf(Color.parseColor("#39FF14"), Color.parseColor("#FF2A55"), Color.parseColor("#222831"), Color.parseColor("#0F1217"), Color.parseColor("#39FF14"), Color.parseColor("#39FF14")),
            96, false, 0, false, 25, 86, 280, 94, 50, 0, 3, 100, 12, 55, 45, true
        ),
        9 to StyleConfig(
            intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#1B2434"), Color.parseColor("#00E5FF"), Color.WHITE, Color.WHITE, Color.WHITE),
            92, false, 30, false, 0, 64, 310, 99, 50, 18, 0, 100, 12, 55, 60, true
        ),
        10 to StyleConfig(
            intArrayOf(Color.parseColor("#00E676"), Color.parseColor("#FFD740"), Color.parseColor("#FF5252"), Color.parseColor("#222B38"), Color.parseColor("#10141D"), Color.parseColor("#FFFFFF")),
            94, true, 45, false, 20, 88, 290, 94, 50, 24, 2, 95, 12, 65, 50, true
        ),
        11 to StyleConfig(
            intArrayOf(Color.parseColor("#A91D22"), Color.parseColor("#F2C6C8"), Color.parseColor("#FFFFFF"), Color.parseColor("#E2E8F0"), Color.parseColor("#1E293B"), Color.parseColor("#FFFFFF")),
            96, true, 40, false, 10, 62, 285, 94, 48, 30, 1, 95, 11, 45, 75, true
        ),
        12 to StyleConfig(
            intArrayOf(Color.parseColor("#EAB308"), Color.parseColor("#78716C"), Color.parseColor("#262626"), Color.parseColor("#404040"), Color.parseColor("#525252"), Color.parseColor("#FACC15")),
            92, true, 50, false, 15, 68, 280, 94, 50, 22, 1, 100, 12, 55, 45, true
        ),
        13 to StyleConfig(
            intArrayOf(Color.parseColor("#8B5CF6"), Color.parseColor("#3B82F6"), Color.parseColor("#1E1B4B"), Color.parseColor("#6366F1"), Color.parseColor("#FFFFFF"), Color.parseColor("#00E676")),
            95, false, 0, false, 0, 340, 44, 50, 2, 14, 1, 95, 12, 50, 50, true
        ),
        14 to StyleConfig(
            intArrayOf(Color.parseColor("#F59E0B"), Color.parseColor("#D97706"), Color.parseColor("#27272A"), Color.parseColor("#52525B"), Color.parseColor("#FDE047"), Color.parseColor("#FFFFFF")),
            94, true, 45, false, 10, 70, 270, 94, 50, 34, 2, 95, 12, 65, 50, true
        ),
        15 to StyleConfig(
            intArrayOf(Color.parseColor("#00F0FF"), Color.parseColor("#FF00A0"), Color.parseColor("#080D1A"), Color.parseColor("#00E5FF"), Color.parseColor("#00F0FF"), Color.parseColor("#00FFFF")),
            90, true, 60, false, 20, 148, 340, 12, 48, 16, 1, 100, 12, 50, 55, true
        ),
        16 to StyleConfig(
            intArrayOf(Color.parseColor("#2AF5D2"), Color.parseColor("#FF7B54"), Color.parseColor("#F2ECF4"), Color.parseColor("#FFFFFF"), Color.parseColor("#1E1B18"), Color.parseColor("#4CE0D2")),
            78, true, 80, false, 10, 108, 305, 92, 50, 28, 1, 100, 12, 45, 50, true
        ),
        17 to StyleConfig(
            intArrayOf(Color.parseColor("#FFE600"), Color.parseColor("#FF5E00"), Color.parseColor("#F0F4F8"), Color.parseColor("#FFFFFF"), Color.parseColor("#FF007F"), Color.parseColor("#0051FF")),
            80, true, 85, false, 10, 114, 310, 92, 50, 28, 1, 100, 12, 60, 55, true
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
            (defaultConfigs[AudioEngine.sliderStyleIndex] ?: defaultConfigs[1]!!).copyConfig()
        }
    }

    fun loadAllStyleConfigs(context: Context) {
        val prefs = context.getSharedPreferences("sound_booster_per_style_v7", Context.MODE_PRIVATE)
        for (idx in 0 until TOTAL_STYLES_COUNT) {
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
                extraParam2 = prefs.getInt("s_${idx}_ex2", def.extraParam2),
                dismissOnOutsideTouch = prefs.getBoolean("s_${idx}_out_dismiss", def.dismissOnOutsideTouch)
            )
        }
    }

    fun saveStyleConfig(context: Context, idx: Int = AudioEngine.sliderStyleIndex) {
        val cfg = configs[idx] ?: return
        val editor = context.getSharedPreferences("sound_booster_per_style_v7", Context.MODE_PRIVATE).edit()
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
            .putBoolean("s_${idx}_out_dismiss", cfg.dismissOnOutsideTouch)
            .apply()
    }

    fun resetStyleToDefault(context: Context, idx: Int = AudioEngine.sliderStyleIndex) {
        val def = defaultConfigs[idx] ?: return
        configs[idx] = def.copyConfig()
        saveStyleConfig(context, idx)
    }
}

// =========================================================================
// 2. גלגל צבעים מקצועי (Studio HSV Ring + 2D Saturation/Value Box + Quick Swatches)
//    מכיל את כל 16,777,216 הצבעים בעולם:
//    - טבעת חיצונית: בוחרת גוון (Hue 0°..360°)
//    - ריבוע פנימי: משמאל לימין = רוויה (לבן -> צבע חי), מלמעלה למטה = בהירות (מואר -> שחור מוחלט)
//    - שורה תחתונה: 12 צבעים מהירים בנגיעה אחת
// =========================================================================
class TrueColorWheelView(context: Context) : View(context) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val svBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#3A465E")
    }
    private val pointerOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f
        color = Color.WHITE
    }
    private val pointerInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.BLACK
    }
    private val pointerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val swatchPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val svBoxRect = RectF()

    private var currentHueDeg = 190f
    private var currentSatRatio = 0.85f
    private var currentValRatio = 1.0f

    // מצב גרירה פעיל כך שהאצבע לעולם לא "בורחת" מהטבעת או מהריבוע הפנימי בזמן תנועה!
    private var activeTouchZone = 0 // 0 = None, 1 = Hue Ring, 2 = SV Box, 3 = Swatches

    private val quickSwatches = intArrayOf(
        Color.parseColor("#FFFFFF"),
        Color.parseColor("#000000"),
        Color.parseColor("#00E5FF"),
        Color.parseColor("#00E676"),
        Color.parseColor("#FFD740"),
        Color.parseColor("#FF6D00"),
        Color.parseColor("#FF1744"),
        Color.parseColor("#FF007F"),
        Color.parseColor("#AA00FF"),
        Color.parseColor("#2979FF"),
        Color.parseColor("#B5DDD1"),
        Color.parseColor("#94A3B8")
    )
    private val swatchCentersX = FloatArray(quickSwatches.size)
    private var swatchCenterY = 0f
    private var swatchRadius = 0f

    var onWheelColorChanged: ((Int) -> Unit)? = null

    init {
        // נדרש עבור ComposeShader של ריבוע הרוויה/בהירות
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setColorFromExternal(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        currentHueDeg = hsv[0].coerceIn(0f, 360f)
        currentSatRatio = hsv[1].coerceIn(0f, 1f)
        currentValRatio = hsv[2].coerceIn(0f, 1f)
        invalidate()
    }

    fun getHueSliderValue(): Int = currentHueDeg.roundToInt().coerceIn(0, 360)
    fun getSatSliderValue(): Int = (currentSatRatio * 100f).roundToInt().coerceIn(0, 100)
    fun getValSliderValue(): Int = (currentValRatio * 100f).roundToInt().coerceIn(0, 100)

    fun updateWheelBrightness(valPercent: Int): Int {
        currentValRatio = (valPercent / 100f).coerceIn(0f, 1f)
        invalidate()
        return computeCurrentSelectedColor()
    }

    fun updateWheelSaturationScale(satPercent: Int): Int {
        currentSatRatio = (satPercent / 100f).coerceIn(0f, 1f)
        invalidate()
        return computeCurrentSelectedColor()
    }

    fun updateWheelHueShift(targetHueDeg: Int): Int {
        currentHueDeg = targetHueDeg.toFloat().coerceIn(0f, 360f)
        invalidate()
        return computeCurrentSelectedColor()
    }

    fun computeCurrentSelectedColor(): Int {
        return Color.HSVToColor(floatArrayOf(currentHueDeg, currentSatRatio, currentValRatio))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val swatchAreaH = h * 0.17f
        val wheelAreaH = h - swatchAreaH
        val cx = w / 2f
        val cy = wheelAreaH / 2f

        val outerRadius = min(cx, cy) * 0.90f
        val ringThickness = outerRadius * 0.22f
        val ringMidRadius = outerRadius - ringThickness / 2f
        val innerRadius = outerRadius - ringThickness - 6f
        if (innerRadius <= 10f) return

        // 1. ציור טבעת ה-Hue החיצונית (360 מעלות של כל צבעי הקשת)
        val hueColors = IntArray(13) { i ->
            Color.HSVToColor(floatArrayOf((i * 30f) % 360f, 1f, 1f))
        }
        ringPaint.strokeWidth = ringThickness
        ringPaint.shader = SweepGradient(cx, cy, hueColors, null)
        canvas.drawCircle(cx, cy, ringMidRadius, ringPaint)

        // סמן הבחירה על טבעת ה-Hue
        val hueRad = Math.toRadians(currentHueDeg.toDouble())
        val hx = (cx + cos(hueRad) * ringMidRadius).toFloat()
        val hy = (cy + sin(hueRad) * ringMidRadius).toFloat()
        val pureHueColor = Color.HSVToColor(floatArrayOf(currentHueDeg, 1f, 1f))
        pointerFillPaint.color = pureHueColor
        val ringThumbR = (ringThickness * 0.48f).coerceAtLeast(12f)
        canvas.drawCircle(hx, hy, ringThumbR, pointerFillPaint)
        canvas.drawCircle(hx, hy, ringThumbR, pointerOuterPaint)
        canvas.drawCircle(hx, hy, ringThumbR + 2f, pointerInnerPaint)

        // 2. ציור ריבוע ה-Saturation & Brightness הפנימי (מאפשר לבחור כל צבע מלבן ועד שחור מוחלט!)
        val halfSide = innerRadius * 0.68f
        svBoxRect.set(cx - halfSide, cy - halfSide, cx + halfSide, cy + halfSide)

        val satShader = LinearGradient(
            svBoxRect.left, svBoxRect.top, svBoxRect.right, svBoxRect.top,
            Color.WHITE, pureHueColor, Shader.TileMode.CLAMP
        )
        val valShader = LinearGradient(
            svBoxRect.left, svBoxRect.top, svBoxRect.left, svBoxRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
        svBoxPaint.shader = ComposeShader(satShader, valShader, PorterDuff.Mode.MULTIPLY)
        canvas.drawRoundRect(svBoxRect, 12f, 12f, svBoxPaint)
        canvas.drawRoundRect(svBoxRect, 12f, 12f, frameBorderPaint)

        // סמן הבחירה בתוך ריבוע ה-SV
        val svX = svBoxRect.left + currentSatRatio * svBoxRect.width()
        val svY = svBoxRect.top + (1f - currentValRatio) * svBoxRect.height()
        val selectedColor = computeCurrentSelectedColor()
        pointerFillPaint.color = selectedColor
        canvas.drawCircle(svX, svY, 16f, pointerFillPaint)
        canvas.drawCircle(svX, svY, 16f, pointerOuterPaint)
        canvas.drawCircle(svX, svY, 18f, pointerInnerPaint)

        // 3. שורת 12 צבעים מהירים בתחתית
        val count = quickSwatches.size
        val stepX = w / count
        swatchRadius = min(stepX * 0.38f, swatchAreaH * 0.38f)
        swatchCenterY = wheelAreaH + swatchAreaH * 0.54f

        for (i in 0 until count) {
            val scx = stepX * (i + 0.5f)
            swatchCentersX[i] = scx
            swatchPaint.style = Paint.Style.FILL
            swatchPaint.color = quickSwatches[i]
            canvas.drawCircle(scx, swatchCenterY, swatchRadius, swatchPaint)

            swatchPaint.style = Paint.Style.STROKE
            swatchPaint.strokeWidth = 2.2f
            swatchPaint.color = Color.parseColor("#4E5D78")
            canvas.drawCircle(scx, swatchCenterY, swatchRadius, swatchPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return super.onTouchEvent(event)

        val swatchAreaH = h * 0.17f
        val wheelAreaH = h - swatchAreaH
        val cx = w / 2f
        val cy = wheelAreaH / 2f

        val outerRadius = min(cx, cy) * 0.90f
        val ringThickness = outerRadius * 0.22f
        val innerRadius = outerRadius - ringThickness - 6f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)

                // בדיקה אם נלחץ צבע מהיר בשורה התחתונה
                if (event.y >= wheelAreaH - 4f) {
                    activeTouchZone = 3
                    for (i in quickSwatches.indices) {
                        if (hypot((event.x - swatchCentersX[i]).toDouble(), (event.y - swatchCenterY).toDouble()) <= swatchRadius * 1.4f) {
                            setColorFromExternal(quickSwatches[i])
                            onWheelColorChanged?.invoke(computeCurrentSelectedColor())
                            return true
                        }
                    }
                    return true
                }

                val dx = event.x - cx
                val dy = event.y - cy
                val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

                // בדיקה אם הלחיצה התחילה בריבוע ה-SV הפנימי או בטבעת ה-Hue החיצונית
                if (svBoxRect.contains(event.x, event.y) || dist < innerRadius * 0.85f) {
                    activeTouchZone = 2
                    updateSVFromTouch(event.x, event.y)
                } else {
                    activeTouchZone = 1
                    updateHueFromTouch(dx, dy)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                when (activeTouchZone) {
                    1 -> updateHueFromTouch(event.x - cx, event.y - cy)
                    2 -> updateSVFromTouch(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeTouchZone = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateHueFromTouch(dx: Float, dy: Float) {
        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (angle < 0f) angle += 360f
        currentHueDeg = angle.coerceIn(0f, 360f)
        invalidate()
        onWheelColorChanged?.invoke(computeCurrentSelectedColor())
    }

    private fun updateSVFromTouch(tx: Float, ty: Float) {
        if (svBoxRect.width() <= 0f || svBoxRect.height() <= 0f) return
        currentSatRatio = ((tx - svBoxRect.left) / svBoxRect.width()).coerceIn(0f, 1f)
        currentValRatio = (1f - ((ty - svBoxRect.top) / svBoxRect.height())).coerceIn(0f, 1f)
        invalidate()
        onWheelColorChanged?.invoke(computeCurrentSelectedColor())
    }
}

// =========================================================================
// 3. מנוע ציור סמלים תואמי ערכת נושא לכל הערוצים + סמל בלוטות' קטן צמוד לרמקול
// =========================================================================
object VectorIconRenderer {
    private val iconPath = Path()
    private val arcRect = RectF()

    fun drawActiveStreamIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        styleIndex: Int,
        volumePercent: Int,
        streamType: Int = AudioEngine.activeStreamType,
        isBluetoothConnected: Boolean = AudioEngine.isBluetoothConnected,
        paint: Paint
    ) {
        when (streamType) {
            0 -> {
                val shiftX = if (isBluetoothConnected) -size * 0.14f else 0f
                drawStyledSpeakerIcon(canvas, cx + shiftX, cy, size, color, styleIndex, volumePercent, paint)
                if (isBluetoothConnected) {
                    drawMiniBluetoothBadge(
                        canvas,
                        cx + size * 0.46f,
                        cy - size * 0.16f,
                        size * 0.48f,
                        color,
                        styleIndex,
                        paint
                    )
                }
            }
            1 -> drawThemedRingVolumeIcon(canvas, cx, cy, size, color, styleIndex, paint)
            2 -> drawThemedNotificationIcon(canvas, cx, cy, size, color, styleIndex, paint)
            3 -> drawThemedAlarmIcon(canvas, cx, cy, size, color, styleIndex, paint)
            else -> {
                drawThemedPhoneCallIcon(canvas, cx, cy, size, color, styleIndex, paint)
                if (isBluetoothConnected) {
                    drawMiniBluetoothBadge(
                        canvas,
                        cx + size * 0.44f,
                        cy - size * 0.18f,
                        size * 0.45f,
                        color,
                        styleIndex,
                        paint
                    )
                }
            }
        }
    }

    fun drawMiniBluetoothBadge(
        canvas: Canvas,
        bx: Float,
        by: Float,
        bSize: Float,
        color: Int,
        styleIndex: Int,
        paint: Paint
    ) {
        if (bSize <= 2f) return
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        val prevCap = paint.strokeCap
        paint.color = color

        if (styleIndex == 8) {
            paint.style = Paint.Style.FILL
            val u = bSize * 0.16f
            canvas.drawRect(bx - u * 0.5f, by - 3 * u, bx + u * 0.5f, by + 3 * u, paint)
            canvas.drawRect(bx + u * 0.5f, by - 2 * u, bx + 2 * u, by - u, paint)
            canvas.drawRect(bx + u * 0.5f, by + u, bx + 2 * u, by + 2 * u, paint)
            canvas.drawRect(bx - 2 * u, by - 1.5f * u, bx - u * 0.5f, by - 0.5f * u, paint)
            canvas.drawRect(bx - 2 * u, by + 0.5f * u, bx - u * 0.5f, by + 1.5f * u, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (bSize * 0.14f).coerceAtLeast(1.8f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            val h = bSize * 0.48f
            val w = bSize * 0.34f
            iconPath.reset()
            iconPath.moveTo(bx, by - h)
            iconPath.lineTo(bx + w, by - h * 0.48f)
            iconPath.lineTo(bx - w * 0.85f, by + h * 0.48f)
            iconPath.moveTo(bx - w * 0.85f, by - h * 0.48f)
            iconPath.lineTo(bx + w, by + h * 0.48f)
            iconPath.lineTo(bx, by + h)
            iconPath.lineTo(bx, by - h)
            canvas.drawPath(iconPath, paint)
        }

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
        paint.strokeCap = prevCap
    }

    fun drawThemedRingVolumeIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        styleIndex: Int,
        paint: Paint
    ) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color

        if (styleIndex == 8) {
            paint.style = Paint.Style.FILL
            val u = size * 0.14f
            canvas.drawRect(cx - u, cy - 3 * u, cx + u, cy - 2 * u, paint)
            canvas.drawRect(cx - 2 * u, cy - 2 * u, cx + 2 * u, cy + u, paint)
            canvas.drawRect(cx - 3 * u, cy + u, cx + 3 * u, cy + 2 * u, paint)
            canvas.drawRect(cx - u * 0.7f, cy + 2.3f * u, cx + u * 0.7f, cy + 3.3f * u, paint)
            canvas.drawRect(cx - 4.2f * u, cy - 2 * u, cx - 3.3f * u, cy, paint)
            canvas.drawRect(cx + 3.3f * u, cy - 2 * u, cx + 4.2f * u, cy, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (size * 0.115f).coerceAtLeast(2f)
            paint.strokeCap = if (styleIndex == 4 || styleIndex == 15) Paint.Cap.SQUARE else Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            val w = size * 0.30f
            val topY = cy - size * 0.28f
            val botY = cy + size * 0.18f

            iconPath.reset()
            iconPath.moveTo(cx - w, botY)
            iconPath.lineTo(cx - w * 0.75f, cy - size * 0.05f)
            iconPath.quadTo(cx, topY - size * 0.10f, cx + w * 0.75f, cy - size * 0.05f)
            iconPath.lineTo(cx + w, botY)
            iconPath.close()
            canvas.drawPath(iconPath, paint)

            arcRect.set(cx - w * 1.65f, cy - size * 0.24f, cx - w * 0.65f, cy + size * 0.12f)
            canvas.drawArc(arcRect, 135f, 90f, false, paint)
            arcRect.set(cx + w * 0.65f, cy - size * 0.24f, cx + w * 1.65f, cy + size * 0.12f)
            canvas.drawArc(arcRect, -45f, 90f, false, paint)

            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, botY + size * 0.11f, size * 0.08f, paint)
        }

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawThemedNotificationIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        styleIndex: Int,
        paint: Paint
    ) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color

        if (styleIndex == 8) {
            paint.style = Paint.Style.FILL
            val u = size * 0.14f
            canvas.drawRect(cx - 2.5f * u, cy - 2 * u, cx + 2.5f * u, cy + u, paint)
            canvas.drawRect(cx - 1.5f * u, cy + u, cx, cy + 2.5f * u, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (size * 0.115f).coerceAtLeast(2f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            val w = size * 0.32f
            val topY = cy - size * 0.30f
            val botY = cy + size * 0.18f

            iconPath.reset()
            iconPath.moveTo(cx - w, botY)
            iconPath.lineTo(cx - w * 0.75f, cy - size * 0.06f)
            iconPath.quadTo(cx, topY - size * 0.10f, cx + w * 0.75f, cy - size * 0.06f)
            iconPath.lineTo(cx + w, botY)
            iconPath.close()
            canvas.drawPath(iconPath, paint)

            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, botY + size * 0.11f, size * 0.085f, paint)
            canvas.drawCircle(cx + w * 0.78f, topY + size * 0.08f, size * 0.10f, paint)
        }

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawThemedAlarmIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        styleIndex: Int,
        paint: Paint
    ) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color

        if (styleIndex == 8) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.12f
            val r = size * 0.32f
            canvas.drawRect(cx - r, cy - r, cx + r, cy + r, paint)
            canvas.drawLine(cx, cy, cx, cy - r * 0.6f, paint)
            canvas.drawLine(cx, cy, cx + r * 0.5f, cy, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (size * 0.115f).coerceAtLeast(2f)
            paint.strokeCap = Paint.Cap.ROUND

            val r = size * 0.34f
            canvas.drawCircle(cx, cy + size * 0.04f, r, paint)
            canvas.drawLine(cx, cy + size * 0.04f, cx, cy - size * 0.14f, paint)
            canvas.drawLine(cx, cy + size * 0.04f, cx + size * 0.14f, cy + size * 0.04f, paint)
            canvas.drawLine(cx - r * 0.88f, cy - r * 0.62f, cx - r * 0.38f, cy - r * 0.96f, paint)
            canvas.drawLine(cx + r * 0.88f, cy - r * 0.62f, cx + r * 0.38f, cy - r * 0.96f, paint)
        }

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

    fun drawThemedPhoneCallIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        color: Int,
        styleIndex: Int,
        paint: Paint
    ) {
        paint.shader = null
        val prevStyle = paint.style
        val prevStroke = paint.strokeWidth
        paint.color = color

        if (styleIndex == 8) {
            paint.style = Paint.Style.FILL
            val u = size * 0.15f
            canvas.drawRect(cx - 2.5f * u, cy - 2.5f * u, cx - 0.5f * u, cy - 0.5f * u, paint)
            canvas.drawRect(cx - 1.5f * u, cy - 1.5f * u, cx + 1.5f * u, cy + 1.5f * u, paint)
            canvas.drawRect(cx + 0.5f * u, cy + 0.5f * u, cx + 2.5f * u, cy + 2.5f * u, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (size * 0.13f).coerceAtLeast(2.2f)
            paint.strokeCap = Paint.Cap.ROUND

            val r = size * 0.33f
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arcRect, 125f, 105f, false, paint)

            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx - r * 0.52f, cy - r * 0.65f, size * 0.105f, paint)
            canvas.drawCircle(cx - r * 0.65f, cy + r * 0.52f, size * 0.105f, paint)
        }

        paint.style = prevStyle
        paint.strokeWidth = prevStroke
    }

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
            4, 15 -> {
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
// 4. תצוגת המחוון הצף (ללא שום שינוי ווליום אוטומטי ב-onDraw!)
// =========================================================================
class CustomThemedSliderOverlayView(
    context: Context,
    private val onVolumeDragged: (Int) -> Unit,
    private val onPositionDragged: (Int, Int) -> Unit,
    private val onFinishEditClicked: () -> Unit,
    private val onOutsideTouchDismiss: () -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val innerRect = RectF()
    private val fillRect = RectF()
    private val doneBtnRect = RectF()
    private val clipPath = Path()
    private val innerTrackClipPath = Path()

    var isEditPositionMode: Boolean = false
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastNonZeroVolume = 100
    var isAnimatingExit = false
    private var isDraggingVolumeBar = false

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

    fun playCustomEnterAnimation(styleIdx: Int = AudioEngine.sliderStyleIndex) {
        animate().cancel()
        isAnimatingExit = false
        val cfg = SliderThemeManager.getCurrentConfig()
        val fromRight = cfg.posXPercent >= 50
        val sideSlidePx = dp(if (fromRight) 75f else -75f)

        alpha = 0f
        when (styleIdx) {
            3, 13 -> {
                translationX = 0f
                translationY = -dp(65f)
                scaleX = 0.92f
                scaleY = 0.92f
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(290L)
                    .setInterpolator(OvershootInterpolator(1.1f))
                    .setListener(null)
                    .start()
            }
            5, 8 -> {
                translationX = 0f
                translationY = 0f
                scaleX = 0.55f
                scaleY = 0.55f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(270L)
                    .setInterpolator(OvershootInterpolator(1.35f))
                    .setListener(null)
                    .start()
            }
            4, 15 -> {
                translationX = sideSlidePx * 0.45f
                translationY = 0f
                scaleX = 0.88f
                scaleY = 0.25f
                animate()
                    .alpha(1f)
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .setListener(null)
                    .start()
            }
            else -> {
                translationX = sideSlidePx
                translationY = 0f
                scaleX = 0.85f
                scaleY = 0.90f
                animate()
                    .alpha(1f)
                    .translationX(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(280L)
                    .setInterpolator(OvershootInterpolator(0.95f))
                    .setListener(null)
                    .start()
            }
        }
    }

    fun playCustomExitAnimation(styleIdx: Int = AudioEngine.sliderStyleIndex, onComplete: () -> Unit) {
        if (isAnimatingExit) return
        isAnimatingExit = true
        animate().cancel()

        val cfg = SliderThemeManager.getCurrentConfig()
        val toRight = cfg.posXPercent >= 50
        val sideSlidePx = dp(if (toRight) 70f else -70f)

        val anim = animate().alpha(0f).setDuration(210L).setInterpolator(AccelerateInterpolator(1.3f))
        when (styleIdx) {
            3, 13 -> anim.translationY(-dp(55f)).scaleX(0.92f).scaleY(0.92f)
            5, 8 -> anim.scaleX(0.60f).scaleY(0.60f)
            4, 15 -> anim.translationX(sideSlidePx * 0.4f).scaleY(0.25f).scaleX(0.85f)
            else -> anim.translationX(sideSlidePx).scaleX(0.86f).scaleY(0.90f)
        }

        anim.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                isAnimatingExit = false
                onComplete()
            }

            override fun onAnimationCancel(animation: Animator) {
                isAnimatingExit = false
            }
        }).start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        AudioEngine.updateBluetoothStatusOnly(context)

        val cfg = SliderThemeManager.getCurrentConfig()
        val maxScale = AudioEngine.getEffectiveMaxVolume().toFloat()
        val vol = AudioEngine.currentTotalVolume.coerceIn(0, maxScale.toInt())
        if (vol > 0) lastNonZeroVolume = vol
        val ratio = (vol / maxScale).coerceIn(0f, 1f)
        val alphaPct = cfg.sliderAlphaPercent

        val c0 = applyAlpha(cfg.colors[0], (alphaPct + 10).coerceAtMost(100))
        val c1 = applyAlpha(cfg.colors[1], (alphaPct + 10).coerceAtMost(100))
        val c2 = applyAlpha(cfg.colors[2], alphaPct)
        val c3 = applyAlpha(cfg.colors[3], alphaPct)
        val c4 = applyAlpha(cfg.colors[4], (alphaPct + 10).coerceAtMost(100))
        val c5 = cfg.colors[5]

        val cornerPx = dp(cfg.cornerRadiusDp.toFloat())
        val brdW = dp(cfg.borderWidthDp.toFloat())
        val icoSize = dp(17f * (cfg.iconSizePercent / 100f))
        val txtPx = sp(cfg.textSizeSp.toFloat())
        val topEditOffset = if (isEditPositionMode) dp(36f) else 0f
        val streamType = AudioEngine.activeStreamType
        val btConnected = AudioEngine.isBluetoothConnected

        if (AudioEngine.sliderStyleIndex >= 11) {
            ExtendedSkinsRenderer.drawExtendedStyle(
                styleIndex = AudioEngine.sliderStyleIndex,
                canvas = canvas, w = w, h = h, topEditOffset = topEditOffset,
                vol = vol, ratio = ratio, cfg = cfg,
                c0 = c0, c1 = c1, c2 = c2, c3 = c3, c4 = c4, c5 = c5,
                cornerPx = cornerPx, brdW = brdW, icoSize = icoSize, txtPx = txtPx,
                dpFn = ::dp, paint = paint
            )
            if (AudioEngine.sliderStyleIndex == 17 && isAttachedToWindow && !isAnimatingExit) {
                postInvalidateOnAnimation()
            }
        } else {
            ExtendedSkinsRenderer.clearInteractiveHitZones()
            val pad = dp(6f)
            rect.set(pad, pad + topEditOffset, w - pad, h - pad)
            val safePillRad = min(cornerPx, rect.width() / 2f)
            val safeVerticalInset = max(safePillRad * 0.65f, dp(22f))

            when (AudioEngine.sliderStyleIndex) {
                1 -> {
                    val highlightStrength = (cfg.extraParam1 / 100f).coerceIn(0.1f, 1f)
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(
                        0f, rect.top, w, rect.bottom,
                        c2, applyAlpha(cfg.colors[5], (alphaPct * highlightStrength).roundToInt()),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)
                    paint.shader = null

                    canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(rect, safePillRad, safePillRad, Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val tubeRatio = 0.35f + (cfg.extraParam2 / 100f) * 0.45f
                    val tubeW = (rect.width() * tubeRatio).coerceAtLeast(dp(14f))
                    val trackLeft = w / 2f - tubeW / 2f
                    val trackRight = w / 2f + tubeW / 2f
                    val trackTop = rect.top + safeVerticalInset + dp(12f)
                    val trackBot = rect.bottom - safeVerticalInset - dp(14f)
                    val tubeCornerRad = min(cornerPx, tubeW / 2f)

                    innerRect.set(trackLeft, trackTop, trackRight, trackBot)
                    paint.color = applyAlpha(cfg.colors[3], 28)
                    canvas.drawRoundRect(innerRect, tubeCornerRad, tubeCornerRad, paint)

                    canvas.save()
                    innerTrackClipPath.reset()
                    innerTrackClipPath.addRoundRect(innerRect, tubeCornerRad, tubeCornerRad, Path.Direction.CW)
                    canvas.clipPath(innerTrackClipPath)

                    val fillTop = trackBot - (trackBot - trackTop) * ratio
                    fillRect.set(trackLeft, fillTop, trackRight, trackBot)
                    paint.shader = LinearGradient(0f, fillTop, 0f, trackBot, c0, c1, Shader.TileMode.CLAMP)
                    canvas.drawRect(fillRect, paint)
                    paint.shader = null
                    canvas.restore()

                    paint.style = Paint.Style.FILL
                    paint.color = cfg.colors[4]
                    paint.textSize = txtPx
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, rect.top + safeVerticalInset, paint)

                    val spkCy = rect.bottom - safeVerticalInset * 0.78f
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, spkCy, icoSize, cfg.colors[4], 1, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(rect.left, spkCy - dp(20f), rect.right, rect.bottom)
                    canvas.restore()

                    if (brdW > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = brdW
                        paint.color = c3
                        canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)
                    }
                }

                2 -> {
                    val widthFactor = 0.38f + (cfg.extraParam1 / 100f) * 0.52f
                    val barW = w * widthFactor
                    val left = (w - barW) / 2f
                    rect.set(left, dp(10f) + topEditOffset, left + barW, h - dp(10f))
                    val rad = min(cornerPx, barW / 2f)

                    paint.style = Paint.Style.FILL
                    paint.color = c1
                    canvas.drawRoundRect(rect, rad, rad, paint)

                    canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val fillTop = rect.bottom - rect.height() * ratio
                    innerRect.set(left, fillTop, left + barW, rect.bottom)
                    paint.color = c0
                    canvas.drawRect(innerRect, paint)

                    val inset = max(rad * 0.68f, dp(20f))
                    paint.color = cfg.colors[2]
                    paint.textSize = txtPx * 0.9f
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, rect.top + inset, paint)

                    val spkCy = rect.bottom - inset * 0.85f
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, spkCy, icoSize * 0.9f, cfg.colors[2], 2, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(left, spkCy - dp(18f), left + barW, rect.bottom)
                    canvas.restore()
                }

                3 -> {
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
                    val startX = dp(56f)
                    val endX = w - dp(22f)
                    val thumbX = startX + (endX - startX) * ratio

                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, dp(28f), lineY - dp(2f), icoSize, c5, 3, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(0f, 0f, dp(52f), h)

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
                    val streamName = AudioEngine.getActiveStreamHebrewLabel()
                    canvas.drawText("$streamName: $vol%", startX, rect.top + rect.height() * 0.36f, paint)
                }

                4 -> {
                    paint.style = Paint.Style.FILL
                    paint.color = c2
                    canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)

                    val innerGap = dp(5f + (cfg.extraParam2 / 100f) * 8f)
                    val trackTop = rect.top + safeVerticalInset + dp(12f)
                    val trackBot = rect.bottom - safeVerticalInset - dp(12f)
                    val fillTop = trackBot - (trackBot - trackTop) * ratio

                    innerRect.set(rect.left + innerGap, trackTop, rect.right - innerGap, trackBot)
                    val innerRad = min(safePillRad * 0.5f, innerRect.width() / 2f)

                    canvas.save()
                    innerTrackClipPath.reset()
                    innerTrackClipPath.addRoundRect(innerRect, innerRad, innerRad, Path.Direction.CW)
                    canvas.clipPath(innerTrackClipPath)

                    fillRect.set(innerRect.left, fillTop, innerRect.right, trackBot)
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(0f, fillTop, 0f, trackBot, c1, c0, Shader.TileMode.CLAMP)
                    canvas.drawRect(fillRect, paint)
                    paint.shader = null
                    canvas.restore()

                    if (brdW > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = brdW
                        paint.color = c3
                        canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)
                    }

                    paint.style = Paint.Style.FILL
                    paint.color = cfg.colors[4]
                    paint.textSize = txtPx
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, rect.top + safeVerticalInset, paint)

                    val spkCy = rect.bottom - safeVerticalInset * 0.8f
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, spkCy, icoSize, c5, 4, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(rect.left, spkCy - dp(18f), rect.right, rect.bottom)
                }

                5 -> {
                    paint.style = Paint.Style.FILL
                    paint.color = c3
                    canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)

                    if (brdW > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = brdW
                        paint.color = c4
                        canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)
                    }

                    val cx = w / 2f
                    val cy = rect.top + rect.height() * 0.45f
                    val radScale = 0.24f + (cfg.extraParam2 / 100f) * 0.15f
                    val r = min(w, rect.height()) * radScale
                    innerRect.set(cx - r, cy - r, cx + r, cy + r)

                    val ringStroke = dp(4f + (cfg.extraParam1 / 100f) * 8f)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = ringStroke
                    paint.color = c2
                    paint.strokeCap = Paint.Cap.ROUND
                    canvas.drawArc(innerRect, 135f, 270f, false, paint)

                    paint.color = if (ratio > 0.75f) c1 else c0
                    canvas.drawArc(innerRect, 135f, 270f * ratio, false, paint)

                    paint.style = Paint.Style.FILL
                    paint.color = c5
                    paint.textSize = txtPx
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", cx, cy + dp(5f), paint)

                    val spkCy = rect.bottom - safeVerticalInset * 0.85f
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, cx, spkCy, icoSize, c5, 5, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(rect.left, spkCy - dp(18f), rect.right, rect.bottom)
                }

                6 -> {
                    paint.style = Paint.Style.FILL
                    paint.color = c3
                    canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)

                    if (brdW > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = brdW
                        paint.color = c4
                        canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)
                    }

                    val bars = (8 + (cfg.extraParam1 / 100f) * 14).roundToInt().coerceIn(6, 24)
                    val topSpace = rect.top + safeVerticalInset + dp(12f)
                    val botSpace = rect.bottom - safeVerticalInset - dp(12f)
                    val stepY = (botSpace - topSpace) / bars
                    val activeBars = (bars * ratio).roundToInt()
                    val thicknessRatio = (0.85f - (cfg.extraParam2 / 100f) * 0.45f).coerceIn(0.3f, 0.85f)

                    paint.style = Paint.Style.FILL
                    for (i in 0 until bars) {
                        val idxFromBottom = bars - 1 - i
                        val y = topSpace + i * stepY
                        val waveFactor = 0.30f + 0.48f * abs(sin(i * 0.65f))
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
                    canvas.drawText("$vol%", w / 2f, rect.top + safeVerticalInset, paint)

                    val spkCy = rect.bottom - safeVerticalInset * 0.8f
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, spkCy, icoSize, c5, 6, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(rect.left, spkCy - dp(18f), rect.right, rect.bottom)
                }

                7 -> {
                    val widthFactor = 0.48f + (cfg.extraParam1 / 100f) * 0.42f
                    val pillW = w * widthFactor
                    val left = (w - pillW) / 2f
                    rect.set(left, dp(8f) + topEditOffset, left + pillW, h - dp(8f))
                    val rad = min(cornerPx.coerceAtLeast(pillW / 2f), pillW / 2f)

                    paint.style = Paint.Style.FILL
                    paint.color = c1
                    canvas.drawRoundRect(rect, rad, rad, paint)

                    canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
                    canvas.clipPath(clipPath)

                    val fillTop = rect.bottom - rect.height() * ratio
                    innerRect.set(left, fillTop, left + pillW, rect.bottom)
                    paint.color = c0
                    canvas.drawRect(innerRect, paint)

                    val inset = max(rad * 0.68f, dp(22f))
                    paint.style = Paint.Style.FILL
                    paint.color = cfg.colors[3]
                    paint.textSize = txtPx
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, rect.top + inset, paint)

                    val spkCy = rect.bottom - inset * 0.85f
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, spkCy, icoSize, cfg.colors[3], 7, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(left, spkCy - dp(18f), left + pillW, rect.bottom)
                    canvas.restore()

                    if (brdW > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = brdW
                        paint.color = c2
                        canvas.drawRoundRect(rect, rad, rad, paint)
                    }
                }

                8 -> {
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

                    val spkCy = rect.bottom - dp(16f)
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, spkCy, icoSize, c5, 8, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(rect.left, spkCy - dp(18f), rect.right, rect.bottom)
                }

                9 -> {
                    val barW = dp(6f + (cfg.extraParam1 / 100f) * 18f)
                    val isRight = cfg.posXPercent >= 50
                    val xPos = if (isRight) w - barW - dp(4f) else dp(4f)
                    rect.set(xPos, dp(10f) + topEditOffset, xPos + barW, h - dp(10f))
                    val rad = min(cornerPx, barW / 2f)

                    paint.style = Paint.Style.FILL
                    paint.color = c1
                    canvas.drawRoundRect(rect, rad, rad, paint)

                    canvas.save()
                    innerTrackClipPath.reset()
                    innerTrackClipPath.addRoundRect(rect, rad, rad, Path.Direction.CW)
                    canvas.clipPath(innerTrackClipPath)

                    val fillTop = rect.bottom - rect.height() * ratio
                    innerRect.set(xPos, fillTop, xPos + barW, rect.bottom)
                    paint.color = c0
                    canvas.drawRect(innerRect, paint)
                    canvas.restore()

                    paint.color = cfg.colors[2]
                    paint.textSize = txtPx
                    paint.textAlign = Paint.Align.CENTER
                    val distPx = dp(16f + (cfg.extraParam2 / 100f) * 20f)
                    val textX = if (isRight) xPos - distPx else xPos + barW + distPx
                    val safeY = fillTop.coerceIn(rect.top + dp(18f), h - dp(36f))
                    canvas.drawText("$vol%", textX, safeY, paint)

                    val icoY = h - dp(18f)
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, textX, icoY, icoSize * 0.78f, cfg.colors[2], 9, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(0f, h - dp(36f), w, h)
                }

                else -> {
                    paint.style = Paint.Style.FILL
                    paint.color = c4
                    canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)

                    if (brdW > 0f) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = brdW
                        paint.color = c5
                        canvas.drawRoundRect(rect, safePillRad, safePillRad, paint)
                    }

                    val rows = (10 + (cfg.extraParam1 / 100f) * 14).roundToInt().coerceIn(8, 24)
                    val cols = (1 + (cfg.extraParam2 / 100f) * 4).roundToInt().coerceIn(1, 5)
                    val topY = rect.top + safeVerticalInset + dp(10f)
                    val botY = rect.bottom - safeVerticalInset - dp(12f)
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
                    canvas.drawText("$vol%", w / 2f, rect.top + safeVerticalInset, paint)

                    val spkCy = rect.bottom - safeVerticalInset * 0.8f
                    VectorIconRenderer.drawActiveStreamIcon(
                        canvas, w / 2f, spkCy, icoSize, c5, 10, vol, streamType, btConnected, paint
                    )
                    ExtendedSkinsRenderer.hitStreamCycleButton.set(rect.left, spkCy - dp(18f), rect.right, rect.bottom)
                }
            }
        }

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

    private fun handleInteractiveOverlayButtonClick(x: Float, y: Float): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

        if (ExtendedSkinsRenderer.hitStreamCycleButton.contains(x, y)) {
            AudioEngine.cycleNextStream(context)
            Toast.makeText(
                context,
                "ערוץ פעיל: ${AudioEngine.getActiveStreamHebrewLabel()} (${AudioEngine.currentTotalVolume}%)",
                Toast.LENGTH_SHORT
            ).show()
            invalidate()
            return true
        }

        if (ExtendedSkinsRenderer.hitSpeakerButton.contains(x, y)) {
            AudioEngine.cycleNextStream(context)
            Toast.makeText(
                context,
                "ערוץ פעיל: ${AudioEngine.getActiveStreamHebrewLabel()}",
                Toast.LENGTH_SHORT
            ).show()
            invalidate()
            return true
        }

        if (ExtendedSkinsRenderer.hitVibrateButton.contains(x, y)) {
            runCatching { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            runCatching {
                val vib = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib?.vibrate(60)
                }
            }
            Toast.makeText(context, "מצב רטט הופעל", Toast.LENGTH_SHORT).show()
            invalidate()
            return true
        }

        if (ExtendedSkinsRenderer.hitMuteButton.contains(x, y)) {
            if (AudioEngine.currentTotalVolume > 0) {
                lastNonZeroVolume = AudioEngine.currentTotalVolume
                AudioEngine.currentTotalVolume = 0
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
                onVolumeDragged(0)
                Toast.makeText(context, "הושתק", Toast.LENGTH_SHORT).show()
            } else {
                val restored = lastNonZeroVolume.coerceAtLeast(50)
                AudioEngine.currentTotalVolume = restored
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                onVolumeDragged(restored)
                Toast.makeText(context, "ביטול השתקה ($restored%)", Toast.LENGTH_SHORT).show()
            }
            invalidate()
            return true
        }

        if (ExtendedSkinsRenderer.hitPlayPauseButton.contains(x, y)) {
            runCatching {
                val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                am.dispatchMediaKeyEvent(downEvent)
                am.dispatchMediaKeyEvent(upEvent)
            }
            Toast.makeText(context, "ניגון / השהיית מדיה", Toast.LENGTH_SHORT).show()
            return true
        }

        if (ExtendedSkinsRenderer.hitSwapSideButton.contains(x, y)) {
            val cfg = SliderThemeManager.getCurrentConfig()
            cfg.posXPercent = if (cfg.posXPercent >= 50) 6 else 94
            SliderThemeManager.saveStyleConfig(context)
            VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
            return true
        }

        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            if (!isEditPositionMode && SliderThemeManager.getCurrentConfig().dismissOnOutsideTouch) {
                onOutsideTouchDismiss()
            }
            return false
        }

        if (isEditPositionMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDraggingVolumeBar = false
                if (handleInteractiveOverlayButtonClick(event.x, event.y)) {
                    return true
                }
                if ((AudioEngine.sliderStyleIndex == 16 || AudioEngine.sliderStyleIndex == 17) &&
                    event.x > width * 0.58f
                ) {
                    return true
                }
                isDraggingVolumeBar = true
                updateVolumeFromTouchPoint(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingVolumeBar) {
                    updateVolumeFromTouchPoint(event.x, event.y)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDraggingVolumeBar = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateVolumeFromTouchPoint(touchX: Float, touchY: Float) {
        val isHorizontalStyle = (AudioEngine.sliderStyleIndex == 3 || AudioEngine.sliderStyleIndex == 13)
        val ratio = if (isHorizontalStyle) {
            (touchX / width.toFloat()).coerceIn(0f, 1f)
        } else {
            val padPx = dp(18f)
            val usableH = (height.toFloat() - padPx * 2f).coerceAtLeast(1f)
            1f - ((touchY - padPx) / usableH).coerceIn(0f, 1f)
        }
        val maxAllowed = AudioEngine.getEffectiveMaxVolume()
        val newVol = (ratio * maxAllowed).roundToInt().coerceIn(0, maxAllowed)
        if (newVol != AudioEngine.currentTotalVolume) {
            AudioEngine.currentTotalVolume = newVol
            invalidate()
            onVolumeDragged(newVol)
        }
    }
}
