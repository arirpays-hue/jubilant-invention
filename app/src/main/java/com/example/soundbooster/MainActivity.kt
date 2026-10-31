package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

// =========================================================================
// 1. מנוע DSP HD (שומר על כל תכונות השמע והאיכות + חיבור ל-SliderThemeManager)
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

    var sliderStyleIndex: Int = 1

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

        context?.let {
            val prefs = it.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            currentTotalVolume = prefs.getInt("saved_vol", currentTotalVolume)
            sliderStyleIndex = prefs.getInt("slider_style", sliderStyleIndex)
            SliderThemeManager.loadAllStyleConfigs(it)
        }
        updateSoundQualityDSP()
    }

    fun saveSelectedStyleIndex(context: Context) {
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            .edit().putInt("slider_style", sliderStyleIndex).apply()
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
// 2. המסך הראשי של האפליקציה
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
    private lateinit var colorWheelView: TrueColorWheelView
    private var isUpdatingControlsProgrammatically = false

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
            btnEnableSystemSlider.text = "שירות מחוון הווליום (200%) פעיל ומחובר"
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
    // קטגוריה 5: התאמה אישית נפרדת לכל מחוון, גלגל צבעים אמיתי, טשטוש/השחרה ועריכת מיקום
    // =========================================================================
    private fun setupSliderDesignCategory() {
        val stylesContainer = findViewById<LinearLayout>(R.id.stylesGridContainer)
        val tvSelectedStyleName = findViewById<TextView>(R.id.tvSelectedStyleName)
        val btnTestOverlay = findViewById<MaterialButton>(R.id.btnTestSliderOverlay)
        val btnResetStyle = findViewById<MaterialButton>(R.id.btnResetCurrentStyle)

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

        val switchDim = findViewById<SwitchCompat>(R.id.switchDimScreen)
        val seekDimStrength = findViewById<SeekBar>(R.id.seekDimStrength)
        val tvDimStrengthVal = findViewById<TextView>(R.id.tvDimStrengthValue)

        val btnToggleInteractiveEdit = findViewById<MaterialButton>(R.id.btnToggleInteractiveEdit)
        val seekWidth = findViewById<SeekBar>(R.id.seekSliderWidth)
        val tvWidthVal = findViewById<TextView>(R.id.tvSliderWidthValue)
        val seekHeight = findViewById<SeekBar>(R.id.seekSliderHeight)
        val tvHeightVal = findViewById<TextView>(R.id.tvSliderHeightValue)
        val seekPosX = findViewById<SeekBar>(R.id.seekPosX)
        val tvPosXVal = findViewById<TextView>(R.id.tvPosXValue)
        val seekPosY = findViewById<SeekBar>(R.id.seekPosY)
        val tvPosYVal = findViewById<TextView>(R.id.tvPosYValue)
        val seekRadius = findViewById<SeekBar>(R.id.seekCornerRadius)
        val tvRadiusVal = findViewById<TextView>(R.id.tvCornerRadiusValue)

        colorWheelView = TrueColorWheelView(this)
        wheelHolder.addView(colorWheelView)

        fun getTargetColorFromConfig(cfg: StyleConfig, targetIdx: Int): Int {
            return when (targetIdx) {
                0 -> cfg.colorAccent
                1 -> cfg.colorBackground
                2 -> cfg.colorBorder
                else -> cfg.colorText
            }
        }

        fun loadColorIntoWheelAndSliders(color: Int) {
            isUpdatingControlsProgrammatically = true
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
            isUpdatingControlsProgrammatically = false
        }

        // טוען את כל ההגדרות הייחודיות של המחוון שנבחר כרגע לממשק
        fun syncUIFromCurrentStyleConfig() {
            isUpdatingControlsProgrammatically = true
            val cfg = SliderThemeManager.getCurrentConfig()

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

            seekAlpha.progress = cfg.sliderAlphaPercent
            tvAlphaVal.text = "${cfg.sliderAlphaPercent}%"

            switchBlur.isChecked = cfg.blurBackgroundEnabled
            seekBlurStrength.progress = cfg.blurStrengthPercent
            tvBlurStrengthVal.text = "${cfg.blurStrengthPercent}%"

            switchDim.isChecked = cfg.dimBackgroundEnabled
            seekDimStrength.progress = cfg.dimStrengthPercent
            tvDimStrengthVal.text = "${cfg.dimStrengthPercent}%"

            seekWidth.progress = cfg.widthDp
            tvWidthVal.text = "${cfg.widthDp} dp"
            seekHeight.progress = cfg.heightDp
            tvHeightVal.text = "${cfg.heightDp} dp"
            seekPosX.progress = cfg.posXPercent
            tvPosXVal.text = "${cfg.posXPercent}%"
            seekPosY.progress = cfg.posYPercent
            tvPosYVal.text = "${cfg.posYPercent}%"
            seekRadius.progress = cfg.cornerRadiusDp
            tvRadiusVal.text = "${cfg.cornerRadiusDp} dp"

            loadColorIntoWheelAndSliders(getTargetColorFromConfig(cfg, currentColorTarget))
            isUpdatingControlsProgrammatically = false
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
                    AudioEngine.saveSelectedStyleIndex(this@MainActivity)
                    syncUIFromCurrentStyleConfig()
                    VolumeOverlayService.instance?.triggerPreviewOverlay()
                }
            }
            styleButtons.add(btn)
            stylesContainer.addView(btn)
        }

        btnResetStyle.setOnClickListener {
            SliderThemeManager.resetStyleToDefault(this)
            syncUIFromCurrentStyleConfig()
            VolumeOverlayService.instance?.triggerPreviewOverlay()
            Toast.makeText(this, "הגדרות המחוון הנוכחי אופסו לברירת המחדל", Toast.LENGTH_SHORT).show()
        }

        btnTestOverlay.setOnClickListener {
            if (VolumeOverlayService.instance != null) {
                VolumeOverlayService.instance?.triggerPreviewOverlay()
            } else {
                Toast.makeText(this, "הפעל תחילה את שירות המחוון בהגדרות הנגישות", Toast.LENGTH_SHORT).show()
            }
        }

        fun applyPickedColorToCurrentStyle(color: Int) {
            val cfg = SliderThemeManager.getCurrentConfig()
            when (currentColorTarget) {
                0 -> cfg.colorAccent = color
                1 -> cfg.colorBackground = color
                2 -> cfg.colorBorder = color
                3 -> cfg.colorText = color
            }
            val hex = String.format("#%06X", 0xFFFFFF and color)
            tvHexColor.text = "צבע נבחר: $hex"
            tvHexColor.setTextColor(color)
            SliderThemeManager.saveStyleConfig(this)
            VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
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
            val cfg = SliderThemeManager.getCurrentConfig()
            loadColorIntoWheelAndSliders(getTargetColorFromConfig(cfg, targetIndex))
        }

        btnTargetAccent.setOnClickListener { selectColorTarget(0) }
        btnTargetBg.setOnClickListener { selectColorTarget(1) }
        btnTargetBorder.setOnClickListener { selectColorTarget(2) }
        btnTargetText.setOnClickListener { selectColorTarget(3) }

        // נגיעה בגלגל הצבעים מעדכנת את הגוון והרוויה
        colorWheelView.onWheelColorChanged = { color, hue, sat ->
            isUpdatingControlsProgrammatically = true
            seekHue.progress = hue.roundToInt()
            seekSat.progress = (sat * 100).roundToInt()
            isUpdatingControlsProgrammatically = false
            applyPickedColorToCurrentStyle(color)
        }

        // מחוון הבהירות משנה את צבעי הגלגל עצמו בלי להזיז את מיקום הבורר על הגלגל!
        seekVal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingControlsProgrammatically) return
                val newVal = (p / 100f).coerceIn(0.05f, 1f)
                colorWheelView.updateValueFromSlider(newVal)
                val updatedColor = Color.HSVToColor(
                    floatArrayOf(colorWheelView.currentHue, colorWheelView.currentSat, colorWheelView.currentVal)
                )
                applyPickedColorToCurrentStyle(updatedColor)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekSat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingControlsProgrammatically) return
                colorWheelView.currentSat = (p / 100f).coerceIn(0f, 1f)
                colorWheelView.invalidate()
                val updatedColor = Color.HSVToColor(
                    floatArrayOf(colorWheelView.currentHue, colorWheelView.currentSat, colorWheelView.currentVal)
                )
                applyPickedColorToCurrentStyle(updatedColor)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingControlsProgrammatically) return
                colorWheelView.currentHue = p.toFloat()
                colorWheelView.invalidate()
                val updatedColor = Color.HSVToColor(
                    floatArrayOf(colorWheelView.currentHue, colorWheelView.currentSat, colorWheelView.currentVal)
                )
                applyPickedColorToCurrentStyle(updatedColor)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // שקיפות, טשטוש מסך אמיתי והשחרת מסך נפרדת
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                val safeAlpha = p.coerceAtLeast(15)
                SliderThemeManager.getCurrentConfig().sliderAlphaPercent = safeAlpha
                tvAlphaVal.text = "$safeAlpha%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchBlur.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingControlsProgrammatically) return@setOnCheckedChangeListener
            SliderThemeManager.getCurrentConfig().blurBackgroundEnabled = isChecked
            SliderThemeManager.saveStyleConfig(this)
            VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
        }

        seekBlurStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().blurStrengthPercent = p
                tvBlurStrengthVal.text = "$p%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchDim.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingControlsProgrammatically) return@setOnCheckedChangeListener
            SliderThemeManager.getCurrentConfig().dimBackgroundEnabled = isChecked
            SliderThemeManager.saveStyleConfig(this)
            VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
        }

        seekDimStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().dimStrengthPercent = p
                tvDimStrengthVal.text = "$p%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        // מצב עריכת מיקום וגודל חיה על המסך
        btnToggleInteractiveEdit.setOnClickListener {
            val svc = VolumeOverlayService.instance
            if (svc == null) {
                Toast.makeText(this, "הפעל תחילה את שירות המחוון בהגדרות הנגישות", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nowEditing = svc.toggleInteractiveEditMode { newXPercent, newYPercent ->
                runOnUiThread {
                    isUpdatingControlsProgrammatically = true
                    seekPosX.progress = newXPercent
                    tvPosXVal.text = "$newXPercent%"
                    seekPosY.progress = newYPercent
                    tvPosYVal.text = "$newYPercent%"
                    isUpdatingControlsProgrammatically = false
                }
            }
            if (nowEditing) {
                btnToggleInteractiveEdit.text = "סיים עריכת מיקום ושמור (או גרור את המחוון במסך)"
                btnToggleInteractiveEdit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
            } else {
                btnToggleInteractiveEdit.text = "פתח מחוון להתאמת מיקום וגודל במסך"
                btnToggleInteractiveEdit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
            }
        }

        seekWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                val w = p.coerceAtLeast(44)
                SliderThemeManager.getCurrentConfig().widthDp = w
                tvWidthVal.text = "$w dp"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                val h = p.coerceAtLeast(65)
                SliderThemeManager.getCurrentConfig().heightDp = h
                tvHeightVal.text = "$h dp"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekPosX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().posXPercent = p
                tvPosXVal.text = "$p%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekPosY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().posYPercent = p
                tvPosYVal.text = "$p%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().cornerRadiusDp = p
                tvRadiusVal.text = "$p dp"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        selectColorTarget(0)
        syncUIFromCurrentStyleConfig()
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
// 3. שירות המחוון הצף (תומך בטשטוש מסך אמיתי, השחרה נפרדת, וגרירת מיקום חיה)
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
    private var isInteractiveEditActive = false
    private var onLivePositionChangedCallback: ((Int, Int) -> Unit)? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (!isInteractiveEditActive) {
            removeAllOverlays()
        }
    }

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

    fun toggleInteractiveEditMode(onPosChanged: (Int, Int) -> Unit): Boolean {
        isInteractiveEditActive = !isInteractiveEditActive
        onLivePositionChangedCallback = onPosChanged
        hideHandler.removeCallbacks(hideRunnable)

        if (isInteractiveEditActive) {
            if (AudioEngine.sliderStyleIndex == 0) {
                AudioEngine.sliderStyleIndex = 1
            }
            removeBadge()
            showCustomThemedOverlay()
            customOverlayCanvasView?.isEditPositionMode = true
            customOverlayCanvasView?.invalidate()
        } else {
            customOverlayCanvasView?.isEditPositionMode = false
            customOverlayCanvasView?.invalidate()
            hideHandler.postDelayed(hideRunnable, 1500)
        }
        return isInteractiveEditActive
    }

    fun triggerPreviewOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        if (AudioEngine.sliderStyleIndex == 0) {
            removeCustomOverlay()
            AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
            showNativeBadgeIfNeeded()
        } else {
            removeBadge()
            showCustomThemedOverlay()
        }
    }

    fun refreshOverlayLive(updateLayout: Boolean = false) {
        if (customOverlayCanvasView == null) {
            if (updateLayout) triggerPreviewOverlay()
            return
        }
        if (updateLayout) {
            val wm = windowManager ?: return
            val params = buildOverlayLayoutParams()
            overlayParams = params
            runCatching { wm.updateViewLayout(customOverlayCanvasView, params) }
        }
        customOverlayCanvasView?.invalidate()
        if (!isInteractiveEditActive) {
            hideHandler.removeCallbacks(hideRunnable)
            hideHandler.postDelayed(hideRunnable, 3400)
        }
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()
    }

    // בונה את פרמטרי החלון כולל הפרדה אמיתית בין טשטוש אופטי (Blur) לבין השחרה (Dim)
    private fun buildOverlayLayoutParams(): WindowManager.LayoutParams {
        val cfg = SliderThemeManager.getCurrentConfig()
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val viewW = dp(cfg.widthDp.toFloat()).coerceIn(dp(44f), screenW)
        val viewH = dp(cfg.heightDp.toFloat()).coerceIn(dp(60f), screenH)

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // 1. טשטוש מסך אמיתי (True Optical Blur) ללא השחרה
        if (cfg.blurBackgroundEnabled && cfg.blurStrengthPercent > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }

        // 2. השחרת מסך ברקע (Background Dim) נפרדת לחלוטין
        if (cfg.dimBackgroundEnabled && cfg.dimStrengthPercent > 0) {
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
        }

        val maxOffsetX = (screenW - viewW).coerceAtLeast(1)
        val maxOffsetY = (screenH - viewH).coerceAtLeast(1)

        return WindowManager.LayoutParams(
            viewW,
            viewH,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((cfg.posXPercent / 100f) * maxOffsetX).roundToInt()
            y = ((cfg.posYPercent / 100f) * maxOffsetY).roundToInt()

            if (cfg.dimBackgroundEnabled && cfg.dimStrengthPercent > 0) {
                dimAmount = (cfg.dimStrengthPercent / 100f) * 0.85f
            } else {
                dimAmount = 0f
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = if (cfg.blurBackgroundEnabled) {
                    ((cfg.blurStrengthPercent / 100f) * 85).roundToInt()
                } else {
                    0
                }
            }
        }
    }

    private fun showCustomThemedOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        val wm = windowManager ?: return

        if (customOverlayCanvasView == null) {
            customOverlayCanvasView = CustomThemedSliderOverlayView(
                context = this,
                onVolumeDragged = { newVol ->
                    AudioEngine.applyVolume(this@VolumeOverlayService, newVol, showNativeSystemUI = false)
                    if (!isInteractiveEditActive) {
                        hideHandler.removeCallbacks(hideRunnable)
                        hideHandler.postDelayed(hideRunnable, 3400)
                    }
                },
                onPositionDragged = { dx, dy ->
                    val cfg = SliderThemeManager.getCurrentConfig()
                    val metrics = resources.displayMetrics
                    val viewW = dp(cfg.widthDp.toFloat())
                    val viewH = dp(cfg.heightDp.toFloat())
                    val maxX = (metrics.widthPixels - viewW).coerceAtLeast(1)
                    val maxY = (metrics.heightPixels - viewH).coerceAtLeast(1)

                    val curPxX = ((cfg.posXPercent / 100f) * maxX) + dx
                    val curPxY = ((cfg.posYPercent / 100f) * maxY) + dy

                    cfg.posXPercent = ((curPxX / maxX) * 100f).roundToInt().coerceIn(0, 100)
                    cfg.posYPercent = ((curPxY / maxY) * 100f).roundToInt().coerceIn(0, 100)

                    SliderThemeManager.saveStyleConfig(this@VolumeOverlayService)
                    refreshOverlayLive(updateLayout = true)
                    onLivePositionChangedCallback?.invoke(cfg.posXPercent, cfg.posYPercent)
                }
            ).apply {
                isEditPositionMode = isInteractiveEditActive
            }

            overlayParams = buildOverlayLayoutParams()
            runCatching { wm.addView(customOverlayCanvasView, overlayParams) }
        } else {
            refreshOverlayLive(updateLayout = true)
        }

        if (!isInteractiveEditActive) {
            hideHandler.postDelayed(hideRunnable, 3400)
        }
    }

    private fun showNativeBadgeIfNeeded() {
        hideHandler.removeCallbacks(hideRunnable)
        if (AudioEngine.currentTotalVolume <= 100) {
            removeBadge()
            return
        }
        val wm = windowManager ?: return
        val cfg = SliderThemeManager.configs[0] ?: SliderThemeManager.getCurrentConfig()

        if (badgeView == null) {
            badgeView = TextView(this).apply {
                textSize = 13f
                setPadding(dp(14f), dp(8f), dp(14f), dp(8f))
                gravity = Gravity.CENTER
                elevation = dp(6f).toFloat()
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = dp(12f)
                y = -dp(155f)
            }
            runCatching { wm.addView(badgeView, params) }
        }

        badgeView?.apply {
            background = GradientDrawable().apply {
                setColor(cfg.colorBackground)
                cornerRadius = dp(cfg.cornerRadiusDp.toFloat()).toFloat()
                setStroke(dp(1.5f), cfg.colorBorder)
            }
            setTextColor(cfg.colorText)
            text = "${AudioEngine.currentTotalVolume}% HD"
        }
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
}
 
