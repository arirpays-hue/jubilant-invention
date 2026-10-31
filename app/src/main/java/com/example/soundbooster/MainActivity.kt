package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =========================================================================
// 1. מנוע DSP HD + הגדרות עיצוב מחוון עוצמת הקול (11 סגנונות + צבעים + טשטוש)
// =========================================================================
object AudioEngine {
    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var presetReverb: PresetReverb? = null
    var dynamicsProcessing: DynamicsProcessing? = null

    var currentTotalVolume: Int = 100
    var isBoosterActive: Boolean = true
    var antiClippingEnabled: Boolean = true
    var hiResOptimizerEnabled: Boolean = true
    var clarityLevel: Int = 50
    var warmthLevel: Int = 40

    // הגדרות עיצוב מחוון הווליום:
    // 0 = מערכת מקורי (Native SystemUI)
    // 1 = עיצוב הזכוכית (Glassmorphism)
    // 2 = עיצוב פשוט ומינימלי (Simple Minimal)
    // 3 = עיצוב אנדרואיד ישן (Holo / Lollipop Retro)
    // 4 = ניאון סייבר (Cyberpunk Neon)
    // 5 = חוגת סטודיו אנלוגית (Rotary Knob & VU)
    // 6 = גלי קול חיים (Audio Wave Spectrum)
    // 7 = קפסולת iOS (Cupertino Pill)
    // 8 = רטרו פיקסל (8-Bit Arcade)
    // 9 = פס קצה נסתר (Edge Glow Line)
    // 10 = מטריצת לד (LED Matrix Bar)
    var sliderStyleIndex: Int = 1
    var colorAccent: Int = Color.parseColor("#00E5FF")
    var colorBackground: Int = Color.parseColor("#1C2536")
    var colorBorder: Int = Color.parseColor("#80FFFFFF")
    var colorText: Int = Color.parseColor("#FFFFFF")

    var sliderAlphaPercent: Int = 85     // 15% - 100%
    var blurBackgroundEnabled: Boolean = true
    var blurStrengthPercent: Int = 45    // 0% - 100%
    var sliderScalePercent: Int = 100    // 60% - 150%
    var cornerRadiusDp: Int = 32         // 0 - 50 dp
    var isRightSide: Boolean = true

    val styleNames = listOf(
        "0. מחוון המערכת המקורי של המכשיר (Native SystemUI)",
        "1. עיצוב הזכוכית היוקרתי (Glassmorphism)",
        "2. עיצוב פשוט ונקי (Simple Minimal)",
        "3. עיצוב אנדרואיד ישן קלאסי (Retro Android Holo)",
        "4. סייבר ניאון זוהר (Cyberpunk Neon)",
        "5. חוגת אולפן אנלוגית (Studio Rotary Knob)",
        "6. גלי קול דינמיים (Live Sound Wave)",
        "7. קפסולת iOS מודרנית (Cupertino Pill)",
        "8. רטרו ארקייד 8-Bit (Pixel Blocks)",
        "9. תאורת קצה מסך (Edge Glow Bar)",
        "10. מטריצת נוריות אולפן (LED Matrix 200%)"
    )

    fun init(context: Context? = null) {
        if (loudnessEnhancer == null) {
            runCatching { loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = isBoosterActive } }
        }
        if (equalizer == null) {
            runCatching { equalizer = Equalizer(0, 0).apply { enabled = isBoosterActive } }
        }
        if (bassBoost == null) {
            runCatching { bassBoost = BassBoost(0, 0).apply { enabled = isBoosterActive } }
        }
        if (virtualizer == null) {
            runCatching { virtualizer = Virtualizer(0, 0).apply { enabled = isBoosterActive } }
        }
        if (presetReverb == null) {
            runCatching { presetReverb = PresetReverb(0, 0).apply { enabled = isBoosterActive } }
        }
        if (dynamicsProcessing == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            initDynamicsEngine()
        }

        context?.let { loadPreferences(it) }
        updateSoundQualityDSP()
    }

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
        currentTotalVolume = prefs.getInt("saved_vol", currentTotalVolume)
        sliderStyleIndex = prefs.getInt("slider_style", sliderStyleIndex)
        colorAccent = prefs.getInt("color_accent", colorAccent)
        colorBackground = prefs.getInt("color_bg", colorBackground)
        colorBorder = prefs.getInt("color_border", colorBorder)
        colorText = prefs.getInt("color_text", colorText)
        sliderAlphaPercent = prefs.getInt("slider_alpha", sliderAlphaPercent)
        blurBackgroundEnabled = prefs.getBoolean("blur_enabled", blurBackgroundEnabled)
        blurStrengthPercent = prefs.getInt("blur_strength", blurStrengthPercent)
        sliderScalePercent = prefs.getInt("slider_scale", sliderScalePercent)
        cornerRadiusDp = prefs.getInt("corner_radius", cornerRadiusDp)
        isRightSide = prefs.getBoolean("is_right_side", isRightSide)
    }

    fun saveDesignPreferences(context: Context) {
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE).edit()
            .putInt("slider_style", sliderStyleIndex)
            .putInt("color_accent", colorAccent)
            .putInt("color_bg", colorBackground)
            .putInt("color_border", colorBorder)
            .putInt("color_text", colorText)
            .putInt("slider_alpha", sliderAlphaPercent)
            .putBoolean("blur_enabled", blurBackgroundEnabled)
            .putInt("blur_strength", blurStrengthPercent)
            .putInt("slider_scale", sliderScalePercent)
            .putInt("corner_radius", cornerRadiusDp)
            .putBoolean("is_right_side", isRightSide)
            .apply()
    }

    private fun initDynamicsEngine() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,
                true, 5,
                true, 5,
                true, 5,
                true
            ).build()

            dynamicsProcessing = DynamicsProcessing(0, 0, config).apply {
                enabled = isBoosterActive
            }
        }
    }

    fun updateSoundQualityDSP() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val dp = dynamicsProcessing ?: return

        runCatching {
            val limiter = DynamicsProcessing.Limiter(
                true,
                antiClippingEnabled,
                0,
                1.5f,
                45.0f,
                4.0f,
                -0.8f,
                0.0f
            )
            dp.setLimiterAllChannelsTo(limiter)

            val clarityGainDb = if (hiResOptimizerEnabled) (clarityLevel / 100f) * 4.5f else 0f
            val warmthGainDb = if (hiResOptimizerEnabled) (warmthLevel / 100f) * 3.2f else 0f

            val postEq = dp.getPostEqByChannelIndex(0)
            if (postEq != null && postEq.bandCount >= 5) {
                postEq.getBand(0)?.apply { isEnabled = true; gain = warmthGainDb * 0.7f; dp.setPostEqBandAllChannelsTo(0, this) }
                postEq.getBand(1)?.apply { isEnabled = true; gain = warmthGainDb; dp.setPostEqBandAllChannelsTo(1, this) }
                postEq.getBand(2)?.apply { isEnabled = true; gain = if (hiResOptimizerEnabled) -0.8f else 0f; dp.setPostEqBandAllChannelsTo(2, this) }
                postEq.getBand(3)?.apply { isEnabled = true; gain = clarityGainDb * 0.8f; dp.setPostEqBandAllChannelsTo(3, this) }
                postEq.getBand(4)?.apply { isEnabled = true; gain = clarityGainDb; dp.setPostEqBandAllChannelsTo(4, this) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        isBoosterActive = enabled
        runCatching { loudnessEnhancer?.enabled = enabled }
        runCatching { equalizer?.enabled = enabled }
        runCatching { bassBoost?.enabled = enabled }
        runCatching { virtualizer?.enabled = enabled }
        runCatching { presetReverb?.enabled = enabled }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dynamicsProcessing?.enabled = enabled }
        }
    }

    fun applyVolume(context: Context, totalPercent: Int, showNativeSystemUI: Boolean = false) {
        init()
        currentTotalVolume = totalPercent.coerceIn(0, 200)
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            .edit().putInt("saved_vol", currentTotalVolume).apply()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val flags = if (showNativeSystemUI) AudioManager.FLAG_SHOW_UI else 0

        if (currentTotalVolume <= 100) {
            val sysTarget = ((currentTotalVolume / 100f) * maxSysVol).roundToInt()
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysTarget, flags) }
            runCatching { loudnessEnhancer?.setTargetGain(0) }
        } else {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSysVol, flags) }
            val extra = currentTotalVolume - 100
            val cleanGainMillibels = if (antiClippingEnabled) (extra * 22) else (extra * 30)
            runCatching {
                loudnessEnhancer?.enabled = true
                loudnessEnhancer?.setTargetGain(cleanGainMillibels)
            }
            updateSoundQualityDSP()
        }
    }
}

// =========================================================================
// 2. גלגל צבעים מלא 360 מעלות (Interactive HSV Color Wheel View)
// =========================================================================
class ColorWheelView(context: Context) : View(context) {
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
    }
    private val previewFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    var currentHue = 190f
    var currentSat = 0.85f
    var currentVal = 0.95f
    var onColorSelected: ((Int, Float, Float) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * 0.88f
        if (radius <= 0f) return

        val hueColors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        )
        wheelPaint.shader = SweepGradient(cx, cy, hueColors, null)
        canvas.drawCircle(cx, cy, radius, wheelPaint)

        satPaint.shader = RadialGradient(
            cx, cy, radius,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, satPaint)

        val angleRad = Math.toRadians(currentHue.toDouble())
        val dist = currentSat * radius
        val sx = (cx + cos(angleRad) * dist).toFloat()
        val sy = (cy + sin(angleRad) * dist).toFloat()

        val selectedColor = Color.HSVToColor(floatArrayOf(currentHue, currentSat, currentVal))
        previewFillPaint.color = selectedColor
        canvas.drawCircle(sx, sy, 18f, previewFillPaint)
        canvas.drawCircle(sx, sy, 18f, selectorPaint)
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
            onColorSelected?.invoke(color, currentHue, currentSat)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onTouchEvent(event)
    }
}

// =========================================================================
// 3. המסך הראשי עם 5 קטגוריות מלאות
// =========================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var maxSystemVolume: Int = 15

    private lateinit var seekTotalVolume: SeekBar
    private lateinit var switchEnable: SwitchCompat
    private lateinit var btnEnableSystemSlider: MaterialButton
    private val eqSliders = mutableListOf<SeekBar>()
    private val styleButtons = mutableListOf<MaterialButton>()

    // 0 = Accent, 1 = Background, 2 = Border, 3 = Text
    private var currentColorTarget = 0
    private lateinit var colorWheelView: ColorWheelView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            window.statusBarColor = Color.parseColor("#0B0E14")
            window.navigationBarColor = Color.parseColor("#0B0E14")
        }
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        AudioEngine.init(this)

        val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (AudioEngine.currentTotalVolume <= 100) {
            AudioEngine.currentTotalVolume = ((curSys.toFloat() / maxSystemVolume) * 100).roundToInt()
        }

        setupCategoryTabs()
        setupModernUI()
        setupSliderDesignCategory()
    }

    override fun onResume() {
        super.onResume()
        updateServiceButtonState()
        seekTotalVolume.progress = AudioEngine.currentTotalVolume
    }

    private fun setupCategoryTabs() {
        val tabVolume = findViewById<MaterialButton>(R.id.tabBtnVolume)
        val tabQuality = findViewById<MaterialButton>(R.id.tabBtnQuality)
        val tabEffects = findViewById<MaterialButton>(R.id.tabBtnEffects)
        val tabEq = findViewById<MaterialButton>(R.id.tabBtnEq)
        val tabSliderDesign = findViewById<MaterialButton>(R.id.tabBtnSliderDesign)

        val secVolume = findViewById<View>(R.id.sectionVolume)
        val secQuality = findViewById<View>(R.id.sectionQuality)
        val secEffects = findViewById<View>(R.id.sectionEffects)
        val secEq = findViewById<View>(R.id.sectionEq)
        val secSliderDesign = findViewById<View>(R.id.sectionSliderDesign)

        val tabs = listOf(tabVolume, tabQuality, tabEffects, tabEq, tabSliderDesign)
        val sections = listOf(secVolume, secQuality, secEffects, secEq, secSliderDesign)

        fun selectTab(index: Int) {
            for (i in tabs.indices) {
                if (i == index) {
                    tabs[i].backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
                    tabs[i].setTextColor(Color.parseColor("#0B0E14"))
                    sections[i].visibility = View.VISIBLE
                } else {
                    tabs[i].backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    tabs[i].setTextColor(Color.parseColor("#A0AAB8"))
                    sections[i].visibility = View.GONE
                }
            }
        }

        tabVolume.setOnClickListener { selectTab(0) }
        tabQuality.setOnClickListener { selectTab(1) }
        tabEffects.setOnClickListener { selectTab(2) }
        tabEq.setOnClickListener { selectTab(3) }
        tabSliderDesign.setOnClickListener { selectTab(4) }
    }

    private fun updateServiceButtonState() {
        if (VolumeOverlayService.instance != null) {
            btnEnableSystemSlider.text = "✅ שירות מחוון הווליום (200%) פעיל!"
            btnEnableSystemSlider.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
        } else {
            btnEnableSystemSlider.text = "הפעל הרחבת מחוון מערכת בהגדרות"
            btnEnableSystemSlider.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
        }
    }

    private fun setupModernUI() {
        switchEnable = findViewById(R.id.switchEnable)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val cardHeader = findViewById<MaterialCardView>(R.id.cardHeader)
        btnEnableSystemSlider = findViewById(R.id.btnEnableSystemSlider)

        seekTotalVolume = findViewById(R.id.seekTotalVolume)
        val tvTotalVolume = findViewById<TextView>(R.id.tvTotalVolume)
        val tvSubBoostInfo = findViewById<TextView>(R.id.tvSubBoostInfo)
        val progressTotalVolume = findViewById<ProgressBar>(R.id.progressTotalVolume)

        val switchAntiClip = findViewById<SwitchCompat>(R.id.switchAntiClip)
        val switchHiRes = findViewById<SwitchCompat>(R.id.switchHiRes)
        val seekClarity = findViewById<SeekBar>(R.id.seekClarity)
        val tvClarityValue = findViewById<TextView>(R.id.tvClarityValue)
        val seekWarmth = findViewById<SeekBar>(R.id.seekWarmth)
        val tvWarmthValue = findViewById<TextView>(R.id.tvWarmthValue)

        val seekBass = findViewById<SeekBar>(R.id.seekBass)
        val tvBassValue = findViewById<TextView>(R.id.tvBassValue)
        val seekVirtualizer = findViewById<SeekBar>(R.id.seekVirtualizer)
        val tvVirtualizerValue = findViewById<TextView>(R.id.tvVirtualizerValue)
        val seekReverb = findViewById<SeekBar>(R.id.seekReverb)
        val tvReverbValue = findViewById<TextView>(R.id.tvReverbValue)

        val eqContainer = findViewById<LinearLayout>(R.id.eqContainer)

        btnEnableSystemSlider.setOnClickListener {
            Toast.makeText(this, "הפעל את 'Sound Booster מחוון ווליום 200%'", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.setEnabled(isChecked)
            if (isChecked) {
                tvStatus.text = "מנוע DSP HD פעיל ● ללא איבוד איכות"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
                cardHeader.strokeColor = Color.parseColor("#00E676")
            } else {
                tvStatus.text = "מנוע סאונד כבוי ●"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
                cardHeader.strokeColor = Color.parseColor("#263042")
                if (seekTotalVolume.progress > 100) seekTotalVolume.progress = 100
            }
        }

        seekTotalVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTotalVolume.text = "$progress%"
                progressTotalVolume.progress = progress

                AudioEngine.applyVolume(this@MainActivity, progress, showNativeSystemUI = false)

                if (progress <= 100) {
                    tvSubBoostInfo.text = "מערכת: $progress% | הגברת HD: מוכנה"
                } else {
                    val extraPercent = progress - 100
                    val db = String.format("%.1f", extraPercent * 0.22f)
                    tvSubBoostInfo.text = "מערכת: 100% | הגברת HD נקייה: +$db dB"
                }

                val color = when {
                    progress > 160 -> Color.parseColor("#FF5252")
                    progress > 100 -> Color.parseColor("#FFD740")
                    else -> Color.parseColor("#00E676")
                }
                tvTotalVolume.setTextColor(color)
                progressTotalVolume.progressTintList = ColorStateList.valueOf(color)
                seekTotalVolume.progressTintList = ColorStateList.valueOf(color)
                seekTotalVolume.thumbTintList = ColorStateList.valueOf(color)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<MaterialButton>(R.id.btnVolMute).setOnClickListener { seekTotalVolume.progress = 0 }
        findViewById<MaterialButton>(R.id.btnVol100).setOnClickListener { seekTotalVolume.progress = 100 }
        findViewById<MaterialButton>(R.id.btnVol150).setOnClickListener { seekTotalVolume.progress = 150 }
        findViewById<MaterialButton>(R.id.btnVol200).setOnClickListener { seekTotalVolume.progress = 200 }

        switchAntiClip.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.antiClippingEnabled = isChecked
            AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume)
        }
        switchHiRes.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.hiResOptimizerEnabled = isChecked
            AudioEngine.updateSoundQualityDSP()
        }
        seekClarity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvClarityValue.text = "$p%"
                AudioEngine.clarityLevel = p
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekWarmth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvWarmthValue.text = "$p%"
                AudioEngine.warmthLevel = p
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBassValue.text = "${progress / 10}%"
                runCatching { AudioEngine.bassBoost?.setStrength(progress.toShort()) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVirtualizerValue.text = "${progress / 10}%"
                runCatching { AudioEngine.virtualizer?.setStrength(progress.toShort()) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val reverbNames = listOf("ללא", "חדר קטן", "חדר בינוני", "חדר גדול", "אולם בינוני", "אולם גדול", "פלטה אקוסטית")
        seekReverb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvReverbValue.text = reverbNames.getOrElse(progress) { "ללא" }
                runCatching { AudioEngine.presetReverb?.preset = progress.toShort() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        runCatching {
            AudioEngine.equalizer?.let { eq ->
                val bands = eq.numberOfBands.toInt()
                val minEQ = eq.bandLevelRange[0].toInt()
                val maxEQ = eq.bandLevelRange[1].toInt()

                for (i in 0 until bands) {
                    val band = i.toShort()
                    val freqHz = eq.getCenterFreq(band) / 1000
                    val freqText = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"

                    val rowHeader = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 14, 0, 4)
                    }

                    val tvFreq = TextView(this).apply {
                        text = "תדר $freqText"
                        setTextColor(Color.parseColor("#D0D8E8"))
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val tvDb = TextView(this).apply {
                        text = "0 dB"
                        setTextColor(Color.parseColor("#00E5FF"))
                        textSize = 13f
                        gravity = Gravity.END
                    }

                    rowHeader.addView(tvFreq)
                    rowHeader.addView(tvDb)

                    val seekBar = SeekBar(this).apply {
                        max = (maxEQ - minEQ).coerceAtLeast(1)
                        val curLevel = eq.getBandLevel(band).toInt()
                        progress = (curLevel - minEQ).coerceAtLeast(0)
                        progressTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                        thumbTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))

                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                                val newLevel = (p + minEQ).toShort()
                                runCatching { eq.setBandLevel(band, newLevel) }
                                val dbVal = newLevel.toInt() / 100
                                tvDb.text = if (dbVal > 0) "+$dbVal dB" else "$dbVal dB"
                            }
                            override fun onStartTrackingTouch(s: SeekBar?) {}
                            override fun onStopTrackingTouch(s: SeekBar?) {}
                        })
                    }

                    eqSliders.add(seekBar)
                    eqContainer.addView(rowHeader)
                    eqContainer.addView(seekBar)
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnPresetNormal).setOnClickListener {
            applyPreset(listOf(0, 0, 0, 0, 0), bass = 0, surround = 0, reverb = 0)
        }
        findViewById<MaterialButton>(R.id.btnPresetBass).setOnClickListener {
            applyPreset(listOf(65, 45, 0, 10, 25), bass = 700, surround = 250, reverb = 1)
        }
        findViewById<MaterialButton>(R.id.btnPresetVocal).setOnClickListener {
            applyPreset(listOf(-15, 15, 55, 50, 35), bass = 100, surround = 200, reverb = 1)
        }
        findViewById<MaterialButton>(R.id.btnPresetGaming).setOnClickListener {
            applyPreset(listOf(45, 20, 0, 50, 65), bass = 500, surround = 900, reverb = 3)
        }
    }

    // =========================================================================
    // בניית קטגוריה 5: עיצוב מחוון עוצמת הקול (11 עיצובים + גלגל צבעים + טשטוש)
    // =========================================================================
    private fun setupSliderDesignCategory() {
        val stylesContainer = findViewById<LinearLayout>(R.id.stylesGridContainer)
        val tvSelectedStyleName = findViewById<TextView>(R.id.tvSelectedStyleName)
        val btnTestOverlay = findViewById<MaterialButton>(R.id.btnTestSliderOverlay)

        val wheelHolder = findViewById<FrameLayout>(R.id.colorWheelHolder)
        val tvHexColor = findViewById<TextView>(R.id.tvCurrentHexColor)
        val seekHue = findViewById<SeekBar>(R.id.seekColorHue)
        val seekSat = findViewById<SeekBar>(R.id.seekColorSat)
        val seekVal = findViewById<SeekBar>(R.id.seekColorVal)

        val btnTargetAccent = findViewById<MaterialButton>(R.id.btnTargetAccent)
        val btnTargetBg = findViewById<MaterialButton>(R.id.btnTargetBg)
        val btnTargetBorder = findViewById<MaterialButton>(R.id.btnTargetBorder)
        val btnTargetText = findViewById<MaterialButton>(R.id.btnTargetText)
        val targetBtns = listOf(btnTargetAccent, btnTargetBg, btnTargetBorder, btnTargetText)

        val seekAlpha = findViewById<SeekBar>(R.id.seekSliderAlpha)
        val tvAlphaVal = findViewById<TextView>(R.id.tvSliderAlphaValue)
        val switchBlur = findViewById<SwitchCompat>(R.id.switchBlurScreen)
        val seekBlurStrength = findViewById<SeekBar>(R.id.seekBlurStrength)
        val tvBlurStrengthVal = findViewById<TextView>(R.id.tvBlurStrengthValue)
        val seekScale = findViewById<SeekBar>(R.id.seekSliderScale)
        val tvScaleVal = findViewById<TextView>(R.id.tvSliderScaleValue)
        val seekRadius = findViewById<SeekBar>(R.id.seekCornerRadius)
        val tvRadiusVal = findViewById<TextView>(R.id.tvCornerRadiusValue)
        val btnSideRight = findViewById<MaterialButton>(R.id.btnSideRight)
        val btnSideLeft = findViewById<MaterialButton>(R.id.btnSideLeft)

        fun updateStyleButtonsUI() {
            tvSelectedStyleName.text = "פעיל: ${AudioEngine.styleNames[AudioEngine.sliderStyleIndex]}"
            for (i in styleButtons.indices) {
                val active = (i == AudioEngine.sliderStyleIndex)
                styleButtons[i].strokeColor = ColorStateList.valueOf(
                    if (active) Color.parseColor("#00E676") else Color.parseColor("#2E3B4E")
                )
                styleButtons[i].setTextColor(
                    if (active) Color.parseColor("#00E676") else Color.parseColor("#D0D8E8")
                )
            }
        }

        // יצירת כפתורי 11 העיצובים
        styleButtons.clear()
        stylesContainer.removeAllViews()
        for (i in AudioEngine.styleNames.indices) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = AudioEngine.styleNames[i]
                textSize = 12f
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                cornerRadius = 28
                strokeWidth = 3
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 }

                setOnClickListener {
                    AudioEngine.sliderStyleIndex = i
                    AudioEngine.saveDesignPreferences(this@MainActivity)
                    updateStyleButtonsUI()
                    VolumeOverlayService.instance?.triggerPreviewOverlay()
                }
            }
            styleButtons.add(btn)
            stylesContainer.addView(btn)
        }
        updateStyleButtonsUI()

        btnTestOverlay.setOnClickListener {
            if (VolumeOverlayService.instance != null) {
                VolumeOverlayService.instance?.triggerPreviewOverlay()
            } else {
                Toast.makeText(this, "הפעל תחילה את שירות המחוון בהגדרות הנגישות", Toast.LENGTH_SHORT).show()
            }
        }

        // הוספת גלגל הצבעים
        colorWheelView = ColorWheelView(this)
        wheelHolder.addView(colorWheelView)

        fun applyPickedColor(color: Int) {
            when (currentColorTarget) {
                0 -> AudioEngine.colorAccent = color
                1 -> AudioEngine.colorBackground = color
                2 -> AudioEngine.colorBorder = color
                3 -> AudioEngine.colorText = color
            }
            val hex = String.format("#%06X", 0xFFFFFF and color)
            tvHexColor.text = "צבע נבחר: $hex"
            tvHexColor.setTextColor(color)
            AudioEngine.saveDesignPreferences(this)
            VolumeOverlayService.instance?.refreshOverlayLive()
        }

        fun loadColorIntoWheel(color: Int) {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            colorWheelView.currentHue = hsv[0]
            colorWheelView.currentSat = hsv[1]
            colorWheelView.currentVal = hsv[2]
            seekHue.progress = hsv[0].roundToInt()
            seekSat.progress = (hsv[1] * 100).roundToInt()
            seekVal.progress = (hsv[2] * 100).roundToInt()
            colorWheelView.invalidate()
            val hex = String.format("#%06X", 0xFFFFFF and color)
            tvHexColor.text = "צבע נבחר: $hex"
            tvHexColor.setTextColor(color)
        }

        fun selectColorTarget(targetIndex: Int) {
            currentColorTarget = targetIndex
            for (i in targetBtns.indices) {
                val active = (i == targetIndex)
                targetBtns[i].strokeColor = ColorStateList.valueOf(
                    if (active) Color.parseColor("#00E5FF") else Color.parseColor("#3A465E")
                )
                targetBtns[i].setTextColor(
                    if (active) Color.parseColor("#00E5FF") else Color.parseColor("#A0AAB8")
                )
            }
            val targetColor = when (targetIndex) {
                0 -> AudioEngine.colorAccent
                1 -> AudioEngine.colorBackground
                2 -> AudioEngine.colorBorder
                else -> AudioEngine.colorText
            }
            loadColorIntoWheel(targetColor)
        }

        btnTargetAccent.setOnClickListener { selectColorTarget(0) }
        btnTargetBg.setOnClickListener { selectColorTarget(1) }
        btnTargetBorder.setOnClickListener { selectColorTarget(2) }
        btnTargetText.setOnClickListener { selectColorTarget(3) }

        colorWheelView.onColorSelected = { color, hue, sat ->
            seekHue.progress = hue.roundToInt()
            seekSat.progress = (sat * 100).roundToInt()
            applyPickedColor(color)
        }

        val hsvSliderListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                colorWheelView.currentHue = seekHue.progress.toFloat()
                colorWheelView.currentSat = seekSat.progress / 100f
                colorWheelView.currentVal = seekVal.progress / 100f
                colorWheelView.invalidate()
                val c = Color.HSVToColor(
                    floatArrayOf(colorWheelView.currentHue, colorWheelView.currentSat, colorWheelView.currentVal)
                )
                applyPickedColor(c)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        seekHue.setOnSeekBarChangeListener(hsvSliderListener)
        seekSat.setOnSeekBarChangeListener(hsvSliderListener)
        seekVal.setOnSeekBarChangeListener(hsvSliderListener)

        // טעינת ערכי שקיפות וטשטוש
        seekAlpha.progress = AudioEngine.sliderAlphaPercent
        tvAlphaVal.text = "${AudioEngine.sliderAlphaPercent}%"
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val safeAlpha = p.coerceAtLeast(15)
                AudioEngine.sliderAlphaPercent = safeAlpha
                tvAlphaVal.text = "$safeAlpha%"
                AudioEngine.saveDesignPreferences(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchBlur.isChecked = AudioEngine.blurBackgroundEnabled
        switchBlur.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.blurBackgroundEnabled = isChecked
            AudioEngine.saveDesignPreferences(this)
            VolumeOverlayService.instance?.triggerPreviewOverlay()
        }

        seekBlurStrength.progress = AudioEngine.blurStrengthPercent
        tvBlurStrengthVal.text = "${AudioEngine.blurStrengthPercent}%"
        seekBlurStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                AudioEngine.blurStrengthPercent = p
                tvBlurStrengthVal.text = "$p%"
                AudioEngine.saveDesignPreferences(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekScale.progress = AudioEngine.sliderScalePercent
        tvScaleVal.text = "${AudioEngine.sliderScalePercent}%"
        seekScale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val scale = p.coerceAtLeast(60)
                AudioEngine.sliderScalePercent = scale
                tvScaleVal.text = "$scale%"
                AudioEngine.saveDesignPreferences(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekRadius.progress = AudioEngine.cornerRadiusDp
        tvRadiusVal.text = "${AudioEngine.cornerRadiusDp} dp"
        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                AudioEngine.cornerRadiusDp = p
                tvRadiusVal.text = "$p dp"
                AudioEngine.saveDesignPreferences(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        fun updateSideButtons() {
            btnSideRight.strokeColor = ColorStateList.valueOf(
                if (AudioEngine.isRightSide) Color.parseColor("#00E676") else Color.parseColor("#3A465E")
            )
            btnSideRight.setTextColor(
                if (AudioEngine.isRightSide) Color.parseColor("#00E676") else Color.parseColor("#A0AAB8")
            )
            btnSideLeft.strokeColor = ColorStateList.valueOf(
                if (!AudioEngine.isRightSide) Color.parseColor("#00E676") else Color.parseColor("#3A465E")
            )
            btnSideLeft.setTextColor(
                if (!AudioEngine.isRightSide) Color.parseColor("#00E676") else Color.parseColor("#A0AAB8")
            )
        }
        updateSideButtons()

        btnSideRight.setOnClickListener {
            AudioEngine.isRightSide = true
            AudioEngine.saveDesignPreferences(this)
            updateSideButtons()
            VolumeOverlayService.instance?.triggerPreviewOverlay()
        }
        btnSideLeft.setOnClickListener {
            AudioEngine.isRightSide = false
            AudioEngine.saveDesignPreferences(this)
            updateSideButtons()
            VolumeOverlayService.instance?.triggerPreviewOverlay()
        }

        selectColorTarget(0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (VolumeOverlayService.instance == null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    seekTotalVolume.progress = (seekTotalVolume.progress + 8).coerceAtMost(200)
                    AudioEngine.applyVolume(this, seekTotalVolume.progress, showNativeSystemUI = true)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    seekTotalVolume.progress = (seekTotalVolume.progress - 8).coerceAtLeast(0)
                    AudioEngine.applyVolume(this, seekTotalVolume.progress, showNativeSystemUI = true)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyPreset(offsetsPercent: List<Int>, bass: Int, surround: Int, reverb: Int) {
        runCatching {
            val eq = AudioEngine.equalizer ?: return
            val minEQ = eq.bandLevelRange[0].toInt()
            val maxEQ = eq.bandLevelRange[1].toInt()
            val range = maxEQ - minEQ
            val mid = range / 2

            for (i in 0 until minOf(eqSliders.size, offsetsPercent.size)) {
                val offset = (range * offsetsPercent[i]) / 200
                eqSliders[i].progress = (mid + offset).coerceIn(0, range)
            }

            findViewById<SeekBar>(R.id.seekBass).progress = bass
            findViewById<SeekBar>(R.id.seekVirtualizer).progress = surround
            findViewById<SeekBar>(R.id.seekReverb).progress = reverb
        }
    }
}

// =========================================================================
// 4. שירות המחוון הצף התומך ב-11 עיצובים, גלגל צבעים, שקיפות וטשטוש מסך
// =========================================================================
class VolumeOverlayService : AccessibilityService() {

    companion object {
        var instance: VolumeOverlayService? = null
    }

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var maxSysVol = 15

    private var badgeView: TextView? = null
    private var customOverlayCanvasView: CustomThemedSliderOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { removeAllOverlays() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxSysVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
            AudioEngine.init(this)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!AudioEngine.isBoosterActive) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val am = audioManager ?: return super.onKeyEvent(event)
                val curSys = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val step = 8
                val useNativeSystemUI = (AudioEngine.sliderStyleIndex == 0)

                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    if (curSys < maxSysVol && AudioEngine.currentTotalVolume < 100) {
                        val nextSys = (curSys + 1).coerceAtMost(maxSysVol)
                        AudioEngine.currentTotalVolume = ((nextSys.toFloat() / maxSysVol) * 100).roundToInt()
                    } else {
                        val baseVol = if (AudioEngine.currentTotalVolume < 100) 100 else AudioEngine.currentTotalVolume
                        AudioEngine.currentTotalVolume = (baseVol + step).coerceAtMost(200)
                    }
                } else {
                    if (AudioEngine.currentTotalVolume > 100) {
                        AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume - step).coerceAtLeast(100)
                    } else {
                        val prevSys = (curSys - 1).coerceAtLeast(0)
                        AudioEngine.currentTotalVolume = ((prevSys.toFloat() / maxSysVol) * 100).roundToInt()
                    }
                }

                AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = useNativeSystemUI)
                if (useNativeSystemUI) {
                    removeCustomOverlay()
                    showNativeBadgeIfNeeded()
                } else {
                    removeBadge()
                    showCustomThemedOverlay()
                }
            }
            return true
        }
        return super.onKeyEvent(event)
    }

    fun triggerPreviewOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        if (AudioEngine.sliderStyleIndex == 0) {
            removeCustomOverlay()
            AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
            showNativeBadgeIfNeeded()
        } else {
            removeBadge()
            removeCustomOverlay()
            showCustomThemedOverlay()
        }
    }

    fun refreshOverlayLive() {
        customOverlayCanvasView?.invalidate()
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()
    }

    // הצגת אחד מ-10 העיצובים המותאמים עם טשטוש שאר המסך (Screen Blur)
    private fun showCustomThemedOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        val wm = windowManager ?: return

        if (customOverlayCanvasView == null) {
            customOverlayCanvasView = CustomThemedSliderOverlayView(this).apply {
                onVolumeDragged = { newVol ->
                    AudioEngine.applyVolume(this@VolumeOverlayService, newVol, showNativeSystemUI = false)
                    hideHandler.removeCallbacks(hideRunnable)
                    hideHandler.postDelayed(hideRunnable, 3400)
                }
            }

            val isHorizontalRetro = (AudioEngine.sliderStyleIndex == 3)
            val scale = AudioEngine.sliderScalePercent / 100f
            val viewW = if (isHorizontalRetro) dp(300f * scale) else dp(86f * scale)
            val viewH = if (isHorizontalRetro) dp(84f * scale) else dp(285f * scale)

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

            if (AudioEngine.blurBackgroundEnabled && AudioEngine.blurStrengthPercent > 0) {
                flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                }
            }

            overlayParams = WindowManager.LayoutParams(
                viewW,
                viewH,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (isHorizontalRetro) {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(48f)
                } else {
                    gravity = (if (AudioEngine.isRightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                    x = dp(12f)
                }

                if (AudioEngine.blurBackgroundEnabled) {
                    dimAmount = (AudioEngine.blurStrengthPercent / 100f) * 0.55f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        blurBehindRadius = ((AudioEngine.blurStrengthPercent / 100f) * 45).roundToInt()
                    }
                }
            }

            runCatching { wm.addView(customOverlayCanvasView, overlayParams) }
        } else {
            customOverlayCanvasView?.invalidate()
        }

        hideHandler.postDelayed(hideRunnable, 3400)
    }

    private fun getDeviceNativeColors(): Pair<Int, Int> {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val res = runCatching {
                val bg = ContextCompat.getColor(
                    this,
                    if (isNight) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50
                )
                val fg = ContextCompat.getColor(
                    this,
                    if (isNight) android.R.color.system_accent1_200 else android.R.color.system_accent1_600
                )
                Pair(bg, fg)
            }
            if (res.isSuccess) return res.getOrThrow()
        }
        return if (isNight) {
            Pair(Color.parseColor("#252930"), Color.parseColor("#8AB4F8"))
        } else {
            Pair(Color.parseColor("#EEF2F6"), Color.parseColor("#3B607A"))
        }
    }

    private fun showNativeBadgeIfNeeded() {
        hideHandler.removeCallbacks(hideRunnable)
        if (AudioEngine.currentTotalVolume <= 100) {
            removeBadge()
            return
        }
        val wm = windowManager ?: return
        val (bgColor, textColor) = getDeviceNativeColors()

        if (badgeView == null) {
            badgeView = TextView(this).apply {
                textSize = 13f
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                gravity = Gravity.CENTER
                elevation = dp(6f).toFloat()
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = dp(24f).toFloat()
                    setStroke(dp(1.5f), textColor)
                }
                setTextColor(textColor)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = (if (AudioEngine.isRightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                x = dp(12f)
                y = -dp(155f)
            }
            runCatching { wm.addView(badgeView, params) }
        }
        badgeView?.text = "🔊 ${AudioEngine.currentTotalVolume}% HD"
        hideHandler.postDelayed(hideRunnable, 2800)
    }

    private fun removeBadge() {
        badgeView?.let {
            runCatching { windowManager?.removeView(it) }
            badgeView = null
        }
    }

    private fun removeCustomOverlay() {
        customOverlayCanvasView?.let {
            runCatching { windowManager?.removeView(it) }
            customOverlayCanvasView = null
        }
    }

    private fun removeAllOverlays() {
        removeBadge()
        removeCustomOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeAllOverlays()
        instance = null
    }

    // =========================================================================
    // מחלקת הציור של 10 העיצובים המיוחדים (זכוכית, פשוט, אנדרואיד ישן, סייבר ועוד)
    // =========================================================================
    inner class CustomThemedSliderOverlayView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val innerRect = RectF()
        private val path = Path()
        var onVolumeDragged: ((Int) -> Unit)? = null

        private fun applyAlpha(color: Int, alphaPercent: Int): Int {
            val a = ((alphaPercent / 100f) * 255).roundToInt().coerceIn(0, 255)
            return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            val vol = AudioEngine.currentTotalVolume.coerceIn(0, 200)
            val ratio = vol / 200f
            val alphaPct = AudioEngine.sliderAlphaPercent
            val bgCol = applyAlpha(AudioEngine.colorBackground, alphaPct)
            val accCol = applyAlpha(AudioEngine.colorAccent, (alphaPct + 12).coerceAtMost(100))
            val brdCol = applyAlpha(AudioEngine.colorBorder, (alphaPct + 10).coerceAtMost(100))
            val txtCol = AudioEngine.colorText
            val cornerPx = dp(AudioEngine.cornerRadiusDp.toFloat()).toFloat()

            when (AudioEngine.sliderStyleIndex) {
                // 1. עיצוב הזכוכית (Glassmorphism - עם השתקפות זכוכית חלבית ומסגרת מוארת)
                1 -> {
                    val pad = dp(8f).toFloat()
                    rect.set(pad, pad, w - pad, h - pad)

                    // גוף הזכוכית החלבית
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(
                        0f, 0f, w, h,
                        applyAlpha(AudioEngine.colorBackground, (alphaPct * 0.78f).roundToInt()),
                        applyAlpha(Color.WHITE, (alphaPct * 0.22f).roundToInt()),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
                    paint.shader = null

                    // מילוי נוזל הזכוכית הזוהר
                    val trackPad = pad + dp(10f)
                    val trackTop = trackPad + dp(30f)
                    val trackBot = h - trackPad - dp(22f)
                    val fillTop = trackBot - (trackBot - trackTop) * ratio

                    innerRect.set(trackPad, trackTop, w - trackPad, trackBot)
                    paint.color = applyAlpha(Color.WHITE, 28)
                    canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                    innerRect.set(trackPad, fillTop, w - trackPad, trackBot)
                    paint.shader = LinearGradient(
                        0f, fillTop, 0f, trackBot,
                        accCol, applyAlpha(accCol, 55), Shader.TileMode.CLAMP
                    )
                    canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)
                    paint.shader = null

                    // ברק השתקפות זכוכית אלכסוני בצד שמאל למעלה
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2f).toFloat()
                    paint.color = brdCol
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                    // טקסט אחוזים זכוכיתי
                    paint.style = Paint.Style.FILL
                    paint.color = txtCol
                    paint.textSize = dp(13f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, pad + dp(24f), paint)
                    canvas.drawText("🔊", w / 2f, h - pad - dp(6f), paint)
                }

                // 2. עיצוב פשוט ונקי (Simple Minimal)
                2 -> {
                    val barW = w * 0.36f
                    val left = (w - barW) / 2f
                    rect.set(left, dp(16f).toFloat(), left + barW, h - dp(16f))
                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                    val fillTop = rect.bottom - rect.height() * ratio
                    innerRect.set(left, fillTop, left + barW, rect.bottom)
                    paint.color = accCol
                    canvas.drawRoundRect(innerRect, cornerPx, cornerPx, paint)

                    paint.color = txtCol
                    paint.textSize = dp(12f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, fillTop.coerceAtLeast(dp(30f).toFloat()) - dp(6f), paint)
                }

                // 3. עיצוב אנדרואיד ישן (Holo / Lollipop Retro Horizontal)
                3 -> {
                    rect.set(dp(6f).toFloat(), dp(6f).toFloat(), w - dp(6f), h - dp(6f))
                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRoundRect(rect, dp(6f).toFloat(), dp(6f).toFloat(), paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1.5f).toFloat()
                    paint.color = brdCol
                    canvas.drawRoundRect(rect, dp(6f).toFloat(), dp(6f).toFloat(), paint)

                    // קו אופקי דק עם נקודת גרירה עגולה כמו באנדרואיד 4.4 / 5.0
                    val lineY = h * 0.62f
                    val startX = dp(48f).toFloat()
                    val endX = w - dp(24f).toFloat()
                    val thumbX = startX + (endX - startX) * ratio

                    paint.strokeWidth = dp(3f).toFloat()
                    paint.color = applyAlpha(txtCol, 50)
                    canvas.drawLine(startX, lineY, endX, lineY, paint)

                    paint.strokeWidth = dp(4f).toFloat()
                    paint.color = accCol
                    canvas.drawLine(startX, lineY, thumbX, lineY, paint)

                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(thumbX, lineY, dp(8f).toFloat(), paint)

                    paint.color = txtCol
                    paint.textSize = dp(13f).toFloat()
                    paint.textAlign = Paint.Align.LEFT
                    canvas.drawText("🔊 עוצמת מדיה: $vol%", startX, h * 0.36f, paint)
                }

                // 4. ניאון סייבר (Cyberpunk Neon)
                4 -> {
                    val pad = dp(10f).toFloat()
                    rect.set(pad, pad, w - pad, h - pad)
                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRect(rect, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(3f).toFloat()
                    paint.color = accCol
                    canvas.drawRect(rect, paint)

                    val fillH = (rect.height() - dp(40f)) * ratio
                    innerRect.set(
                        rect.left + dp(8f),
                        rect.bottom - dp(8f) - fillH,
                        rect.right - dp(8f),
                        rect.bottom - dp(8f)
                    )
                    paint.style = Paint.Style.FILL
                    paint.color = accCol
                    canvas.drawRect(innerRect, paint)

                    paint.color = txtCol
                    paint.textSize = dp(13f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("⚡$vol%", w / 2f, rect.top + dp(22f), paint)
                }

                // 5. חוגת סטודיו אנלוגית (Rotary Knob & VU Meter)
                5 -> {
                    val pad = dp(8f).toFloat()
                    rect.set(pad, pad, w - pad, h - pad)
                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                    val cx = w / 2f
                    val cy = h / 2f
                    val r = (w * 0.34f)
                    innerRect.set(cx - r, cy - r, cx + r, cy + r)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(7f).toFloat()
                    paint.color = applyAlpha(txtCol, 40)
                    canvas.drawArc(innerRect, 135f, 270f, false, paint)

                    paint.color = accCol
                    paint.strokeCap = Paint.Cap.ROUND
                    canvas.drawArc(innerRect, 135f, 270f * ratio, false, paint)

                    paint.style = Paint.Style.FILL
                    paint.color = txtCol
                    paint.textSize = dp(13f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", cx, cy + dp(5f), paint)
                    canvas.drawText("STUDIO", cx, h - dp(20f), paint)
                }

                // 6. גלי קול חיים (Audio Wave Spectrum Bars)
                6 -> {
                    val pad = dp(8f).toFloat()
                    rect.set(pad, pad, w - pad, h - pad)
                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                    val bars = 16
                    val topSpace = rect.top + dp(32f)
                    val botSpace = rect.bottom - dp(16f)
                    val stepY = (botSpace - topSpace) / bars
                    val activeBars = (bars * ratio).roundToInt()

                    for (i in 0 until bars) {
                        val barIndexFromBottom = bars - 1 - i
                        val y = topSpace + i * stepY
                        val waveWidthFactor = 0.35f + 0.5f * kotlin.math.abs(sin(i * 0.7f))
                        val bw = rect.width() * waveWidthFactor
                        val left = (w - bw) / 2f

                        paint.color = if (barIndexFromBottom < activeBars) accCol else applyAlpha(txtCol, 30)
                        innerRect.set(left, y, left + bw, y + stepY * 0.65f)
                        canvas.drawRoundRect(innerRect, dp(4f).toFloat(), dp(4f).toFloat(), paint)
                    }

                    paint.color = txtCol
                    paint.textSize = dp(12f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, rect.top + dp(22f), paint)
                }

                // 7. קפסולת iOS (Cupertino Pill)
                7 -> {
                    val pillW = w * 0.62f
                    val left = (w - pillW) / 2f
                    rect.set(left, dp(12f).toFloat(), left + pillW, h - dp(12f))
                    val rad = pillW / 2f

                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRoundRect(rect, rad, rad, paint)

                    val fillTop = rect.bottom - rect.height() * ratio
                    canvas.save()
                    path.reset()
                    path.addRoundRect(rect, rad, rad, Path.Direction.CW)
                    canvas.clipPath(path)

                    innerRect.set(left, fillTop, left + pillW, rect.bottom)
                    paint.color = accCol
                    canvas.drawRect(innerRect, paint)
                    canvas.restore()

                    paint.color = txtCol
                    paint.textSize = dp(12f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("🔊", w / 2f, rect.bottom - dp(18f), paint)
                    canvas.drawText("$vol%", w / 2f, rect.top + dp(24f), paint)
                }

                // 8. רטרו פיקסל (8-Bit Arcade Blocks)
                8 -> {
                    val pad = dp(10f).toFloat()
                    rect.set(pad, pad, w - pad, h - pad)
                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRect(rect, paint)

                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(3f).toFloat()
                    paint.color = brdCol
                    canvas.drawRect(rect, paint)

                    val blocks = 12
                    val startY = rect.top + dp(34f)
                    val endY = rect.bottom - dp(12f)
                    val blockH = (endY - startY) / blocks
                    val lit = (blocks * ratio).roundToInt()

                    paint.style = Paint.Style.FILL
                    for (i in 0 until blocks) {
                        val idxFromBottom = blocks - 1 - i
                        paint.color = if (idxFromBottom < lit) accCol else applyAlpha(txtCol, 25)
                        val by = startY + i * blockH
                        canvas.drawRect(rect.left + dp(8f), by + 2f, rect.right - dp(8f), by + blockH - 4f, paint)
                    }

                    paint.color = txtCol
                    paint.textSize = dp(11f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("8BIT $vol%", w / 2f, rect.top + dp(22f), paint)
                }

                // 9. פס תאורת קצה מסך (Edge Glow Line)
                9 -> {
                    val barW = dp(12f).toFloat()
                    val xPos = if (AudioEngine.isRightSide) w - barW - dp(4f) else dp(4f).toFloat()
                    rect.set(xPos, dp(10f).toFloat(), xPos + barW, h - dp(10f))

                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint)

                    val fillTop = rect.bottom - rect.height() * ratio
                    innerRect.set(xPos, fillTop, xPos + barW, rect.bottom)
                    paint.color = accCol
                    canvas.drawRoundRect(innerRect, barW / 2f, barW / 2f, paint)

                    paint.color = txtCol
                    paint.textSize = dp(12f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    val textX = if (AudioEngine.isRightSide) xPos - dp(24f) else xPos + barW + dp(24f)
                    canvas.drawText("$vol%", textX, fillTop.coerceIn(dp(24f).toFloat(), h - dp(24f)), paint)
                }

                // 10. מטריצת נוריות LED (LED Matrix 200%)
                else -> {
                    val pad = dp(8f).toFloat()
                    rect.set(pad, pad, w - pad, h - pad)
                    paint.style = Paint.Style.FILL
                    paint.color = bgCol
                    canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)

                    val rows = 20
                    val cols = 3
                    val topY = rect.top + dp(32f)
                    val botY = rect.bottom - dp(14f)
                    val cellH = (botY - topY) / rows
                    val cellW = (rect.width() - dp(20f)) / cols
                    val activeRows = (rows * ratio).roundToInt()

                    for (r in 0 until rows) {
                        val fromBottom = rows - 1 - r
                        val isLit = fromBottom < activeRows
                        val rowColor = when {
                            !isLit -> applyAlpha(txtCol, 22)
                            fromBottom >= 15 -> Color.parseColor("#FF5252")
                            fromBottom >= 10 -> Color.parseColor("#FFD740")
                            else -> accCol
                        }
                        paint.color = rowColor
                        val cy = topY + r * cellH
                        for (c in 0 until cols) {
                            val cx = rect.left + dp(10f) + c * cellW
                            innerRect.set(cx + 2f, cy + 2f, cx + cellW - 2f, cy + cellH - 2f)
                            canvas.drawRoundRect(innerRect, dp(2f).toFloat(), dp(2f).toFloat(), paint)
                        }
                    }

                    paint.color = txtCol
                    paint.textSize = dp(12f).toFloat()
                    paint.textAlign = Paint.Align.CENTER
                    canvas.drawText("$vol%", w / 2f, rect.top + dp(22f), paint)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val ratio = if (AudioEngine.sliderStyleIndex == 3) {
                    (event.x / width.toFloat()).coerceIn(0f, 1f)
                } else {
                    1f - (event.y / height.toFloat()).coerceIn(0f, 1f)
                }
                val newVol = (ratio * 200).roundToInt().coerceIn(0, 200)
                AudioEngine.currentTotalVolume = newVol
                invalidate()
                onVolumeDragged?.invoke(newVol)
                return true
            }
            return super.onTouchEvent(event)
        }
    }
}
 
