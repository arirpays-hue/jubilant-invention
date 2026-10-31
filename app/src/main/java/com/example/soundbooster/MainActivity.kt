package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
// 1. מנוע סאונד בטוח + רשימת כל 21 סגנונות המחוון
// =========================================================================
object AudioEngine {
    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var presetReverb: PresetReverb? = null

    var currentTotalVolume: Int = 100
    var isBoosterActive: Boolean = true
    var antiClippingEnabled: Boolean = true
    var hiResOptimizerEnabled: Boolean = true
    var clarityLevel: Int = 50
    var warmthLevel: Int = 40

    var sliderStyleIndex: Int = 1

    @Volatile var isInternalVolumeChange: Boolean = false
    var bootCooldownUntil: Long = 0L
    private var playbackCallbackRegistered = false

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
        "10. מטריצת נוריות אולפן (LED Matrix 200%)",
        "11. קפסולת גרדיאנט מפוצלת + תפריט (Split Gradient Pill)",
        "12. אנדרואיד 12 מנטה מודולרי (Material You Mint Split)",
        "13. פאנל 4 ערוצי ניאון גמיש (4-Channel Neon Matrix)",
        "14. כרטיסיית 4 עמודות RGB צבעוניות (Colorful Themes Card)",
        "15. קפסולה לבנה דקה + כפתור Play (Slim Red Accent Pill)",
        "16. פאנל מחוונים אופקיים מלא (Multi-Stream Volume Panel)",
        "17. אולפן זהב כהה אנכי (Golden Thin Track & Knob)",
        "18. מיקסר כחול עמוק 6 ערוצים (Deep Blue Line Mixer)",
        "19. בר עליון צמוד קצה עם תו מוזיקה (Top Status Bar Strip)",
        "20. קפסולת Windows / Emoji מדורגת (Segmented Cap Pill)"
    )

    fun init(context: Context? = null) {
        context?.let {
            val prefs = it.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            currentTotalVolume = prefs.getInt("saved_vol", currentTotalVolume)
            sliderStyleIndex = prefs.getInt("slider_style", sliderStyleIndex).coerceIn(0, styleNames.lastIndex)
            SliderThemeManager.loadAllStyleConfigs(it)
            registerAssistantSafeRecovery(it.applicationContext)
        }
        ensureAudioEffectsCreated()
        updateSoundQualityDSP()
    }

    private fun ensureAudioEffectsCreated() {
        if (loudnessEnhancer == null) {
            runCatching {
                loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = isBoosterActive }
            }
        }
        if (equalizer == null) {
            runCatching {
                equalizer = Equalizer(0, 0).apply { enabled = isBoosterActive }
            }
        }
        if (bassBoost == null) {
            runCatching {
                bassBoost = BassBoost(0, 0).apply { enabled = isBoosterActive }
            }
        }
        if (virtualizer == null) {
            runCatching {
                virtualizer = Virtualizer(0, 0).apply { enabled = isBoosterActive }
            }
        }
        if (presetReverb == null) {
            runCatching {
                presetReverb = PresetReverb(0, 0).apply { enabled = isBoosterActive }
            }
        }
    }

    private fun registerAssistantSafeRecovery(appContext: Context) {
        if (playbackCallbackRegistered) return
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        playbackCallbackRegistered = true

        runCatching {
            am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                    super.onPlaybackConfigChanged(configs)
                    if (!isBoosterActive) return
                    val mode = am.mode
                    if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                        runCatching { loudnessEnhancer?.setTargetGain(0) }
                    } else {
                        reapplyCurrentGainSafely()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun reapplyCurrentGainSafely() {
        if (!isBoosterActive) return
        val extra = (currentTotalVolume - 100).coerceAtLeast(0)
        val cleanGain = if (antiClippingEnabled) (extra * 18) else (extra * 26)
        runCatching {
            loudnessEnhancer?.enabled = true
            loudnessEnhancer?.setTargetGain(cleanGain)
        }.onFailure {
            runCatching { loudnessEnhancer?.release() }
            loudnessEnhancer = null
            ensureAudioEffectsCreated()
            runCatching { loudnessEnhancer?.setTargetGain(cleanGain) }
        }
    }

    fun saveSelectedStyleIndex(context: Context) {
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            .edit().putInt("slider_style", sliderStyleIndex).apply()
    }

    fun updateSoundQualityDSP() {
        val eq = equalizer ?: return
        runCatching {
            val bands = eq.numberOfBands.toInt()
            if (bands < 3) return
            val maxEQ = eq.bandLevelRange[1].toInt()

            if (hiResOptimizerEnabled) {
                val warmthBoost = ((warmthLevel / 100f) * (maxEQ * 0.35f)).toInt().toShort()
                val clarityBoost = ((clarityLevel / 100f) * (maxEQ * 0.45f)).toInt().toShort()
                eq.setBandLevel(0, warmthBoost)
                eq.setBandLevel((bands - 1).toShort(), clarityBoost)
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
    }

    fun applyVolume(context: Context, totalPercent: Int, showNativeSystemUI: Boolean = false) {
        ensureAudioEffectsCreated()
        currentTotalVolume = totalPercent.coerceIn(0, 200)
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            .edit().putInt("saved_vol", currentTotalVolume).apply()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        val allowUI = showNativeSystemUI && (SystemClock.elapsedRealtime() > bootCooldownUntil)
        val flags = if (allowUI) AudioManager.FLAG_SHOW_UI else 0

        isInternalVolumeChange = true
        try {
            if (currentTotalVolume <= 100) {
                val sysTarget = ((currentTotalVolume / 100f) * maxSysVol).roundToInt()
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysTarget, flags) }
                runCatching { loudnessEnhancer?.setTargetGain(0) }
            } else {
                val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (curSys < maxSysVol || allowUI) {
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSysVol, flags) }
                }
                reapplyCurrentGainSafely()
            }
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({
                isInternalVolumeChange = false
            }, 100)
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
    private lateinit var btnToggleInteractiveEdit: MaterialButton
    private val eqSliders = mutableListOf<SeekBar>()
    private val styleButtons = mutableListOf<MaterialButton>()
    private val colorSlotButtons = mutableListOf<MaterialButton>()

    private var currentColorSlotIndex = 0
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
        updateEditButtonState(VolumeOverlayService.instance?.isEditModeActive() == true)
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

    private fun updateEditButtonState(isEditing: Boolean) {
        if (!::btnToggleInteractiveEdit.isInitialized) return
        if (isEditing) {
            btnToggleInteractiveEdit.text = "✓ סיים עריכת מיקום ושמור (או לחץ על '✓ סיים' במחוון)"
            btnToggleInteractiveEdit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
        } else {
            btnToggleInteractiveEdit.text = "פתח מחוון להתאמת מיקום וגודל במסך"
            btnToggleInteractiveEdit.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
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
                tvStatus.text = "מנוע DSP HD פעיל ● מוגן מהתנגשויות שמע"
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

                if (fromUser) {
                    AudioEngine.applyVolume(this@MainActivity, progress, showNativeSystemUI = false)
                }

                if (progress <= 100) {
                    tvSubBoostInfo.text = "מערכת: $progress% | הגברת HD: מוכנה"
                } else {
                    val extraPercent = progress - 100
                    val db = String.format("%.1f", extraPercent * 0.18f)
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

        findViewById<MaterialButton>(R.id.btnVolMute).setOnClickListener {
            seekTotalVolume.progress = 0
            AudioEngine.applyVolume(this, 0, false)
        }
        findViewById<MaterialButton>(R.id.btnVol100).setOnClickListener {
            seekTotalVolume.progress = 100
            AudioEngine.applyVolume(this, 100, false)
        }
        findViewById<MaterialButton>(R.id.btnVol150).setOnClickListener {
            seekTotalVolume.progress = 150
            AudioEngine.applyVolume(this, 150, false)
        }
        findViewById<MaterialButton>(R.id.btnVol200).setOnClickListener {
            seekTotalVolume.progress = 200
            AudioEngine.applyVolume(this, 200, false)
        }

        switchAntiClip.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.antiClippingEnabled = isChecked
            AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, false)
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
    // קטגוריה 5: התאמה דינמית לכל 21 המחוונים
    // =========================================================================
    private fun setupSliderDesignCategory() {
        val stylesContainer = findViewById<LinearLayout>(R.id.stylesGridContainer)
        val tvSelectedStyleName = findViewById<TextView>(R.id.tvSelectedStyleName)
        val btnTestOverlay = findViewById<MaterialButton>(R.id.btnTestSliderOverlay)
        val btnResetStyle = findViewById<MaterialButton>(R.id.btnResetCurrentStyle)

        val colorSlotsContainer = findViewById<LinearLayout>(R.id.colorSlotsContainer)
        val wheelHolder = findViewById<FrameLayout>(R.id.colorWheelHolder)
        val tvHexColor = findViewById<TextView>(R.id.tvCurrentHexColor)
        val seekHue = findViewById<SeekBar>(R.id.seekColorHue)
        val seekSat = findViewById<SeekBar>(R.id.seekColorSat)
        val seekVal = findViewById<SeekBar>(R.id.seekColorVal)

        val layoutBorderControl = findViewById<View>(R.id.layoutBorderControl)
        val seekBorderWidth = findViewById<SeekBar>(R.id.seekBorderWidth)
        val tvBorderWidthVal = findViewById<TextView>(R.id.tvBorderWidthValue)

        val layoutCornerControl = findViewById<View>(R.id.layoutCornerControl)
        val seekRadius = findViewById<SeekBar>(R.id.seekCornerRadius)
        val tvRadiusVal = findViewById<TextView>(R.id.tvCornerRadiusValue)

        val layoutIconControl = findViewById<View>(R.id.layoutIconControl)
        val seekIconSize = findViewById<SeekBar>(R.id.seekIconSize)
        val tvIconSizeVal = findViewById<TextView>(R.id.tvIconSizeValue)

        val seekTextSize = findViewById<SeekBar>(R.id.seekTextSize)
        val tvTextSizeVal = findViewById<TextView>(R.id.tvTextSizeValue)

        val layoutExtra1 = findViewById<View>(R.id.layoutExtraParam1)
        val tvExtra1Label = findViewById<TextView>(R.id.tvExtraParam1Label)
        val tvExtra1Val = findViewById<TextView>(R.id.tvExtraParam1Value)
        val seekExtra1 = findViewById<SeekBar>(R.id.seekExtraParam1)

        val layoutExtra2 = findViewById<View>(R.id.layoutExtraParam2)
        val tvExtra2Label = findViewById<TextView>(R.id.tvExtraParam2Label)
        val tvExtra2Val = findViewById<TextView>(R.id.tvExtraParam2Value)
        val seekExtra2 = findViewById<SeekBar>(R.id.seekExtraParam2)

        val seekAlpha = findViewById<SeekBar>(R.id.seekSliderAlpha)
        val tvAlphaVal = findViewById<TextView>(R.id.tvSliderAlphaValue)

        val switchBlur = findViewById<SwitchCompat>(R.id.switchBlurScreen)
        val seekBlurStrength = findViewById<SeekBar>(R.id.seekBlurStrength)
        val tvBlurStrengthVal = findViewById<TextView>(R.id.tvBlurStrengthValue)

        val switchDim = findViewById<SwitchCompat>(R.id.switchDimScreen)
        val seekDimStrength = findViewById<SeekBar>(R.id.seekDimStrength)
        val tvDimStrengthVal = findViewById<TextView>(R.id.tvDimStrengthValue)

        btnToggleInteractiveEdit = findViewById(R.id.btnToggleInteractiveEdit)
        val seekWidth = findViewById<SeekBar>(R.id.seekSliderWidth)
        val tvWidthVal = findViewById<TextView>(R.id.tvSliderWidthValue)
        val seekHeight = findViewById<SeekBar>(R.id.seekSliderHeight)
        val tvHeightVal = findViewById<TextView>(R.id.tvSliderHeightValue)
        val seekPosX = findViewById<SeekBar>(R.id.seekPosX)
        val tvPosXVal = findViewById<TextView>(R.id.tvPosXValue)
        val seekPosY = findViewById<SeekBar>(R.id.seekPosY)
        val tvPosYVal = findViewById<TextView>(R.id.tvPosYValue)

        colorWheelView = TrueColorWheelView(this)
        wheelHolder.addView(colorWheelView)

        fun loadSlotColorIntoWheel(slotIdx: Int) {
            isUpdatingControlsProgrammatically = true
            val cfg = SliderThemeManager.getCurrentConfig()
            val caps = SliderThemeManager.getCurrentCapabilities()
            val safeSlot = slotIdx.coerceIn(0, caps.colorSlotNames.lastIndex)
            currentColorSlotIndex = safeSlot

            for (i in colorSlotButtons.indices) {
                val active = (i == safeSlot)
                colorSlotButtons[i].strokeColor = ColorStateList.valueOf(
                    if (active) Color.parseColor("#00E5FF") else Color.parseColor("#3A465E")
                )
                colorSlotButtons[i].setTextColor(
                    if (active) Color.parseColor("#00E5FF") else Color.parseColor("#A0AAB8")
                )
            }

            val color = cfg.colors[safeSlot]
            colorWheelView.setColorFromExternal(color)
            seekHue.progress = colorWheelView.getHueSliderValue()
            seekSat.progress = colorWheelView.getSatSliderValue()
            seekVal.progress = colorWheelView.getValSliderValue()

            val hex = String.format("#%06X", 0xFFFFFF and color)
            tvHexColor.text = "${caps.colorSlotNames[safeSlot]}: $hex"
            tvHexColor.setTextColor(color)
            isUpdatingControlsProgrammatically = false
        }

        fun syncUIFromCurrentStyleConfig() {
            isUpdatingControlsProgrammatically = true
            val cfg = SliderThemeManager.getCurrentConfig()
            val caps = SliderThemeManager.getCurrentCapabilities()

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

            colorSlotButtons.clear()
            colorSlotsContainer.removeAllViews()
            for (slotIdx in caps.colorSlotNames.indices) {
                val slotBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = caps.colorSlotNames[slotIdx]
                    textSize = 11f
                    cornerRadius = 22
                    strokeWidth = 3
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(42f)
                    ).apply { marginEnd = dpToPx(6f) }

                    setOnClickListener {
                        loadSlotColorIntoWheel(slotIdx)
                    }
                }
                colorSlotButtons.add(slotBtn)
                colorSlotsContainer.addView(slotBtn)
            }

            layoutBorderControl.visibility = if (caps.hasBorder) View.VISIBLE else View.GONE
            seekBorderWidth.progress = cfg.borderWidthDp
            tvBorderWidthVal.text = "${cfg.borderWidthDp} dp"

            layoutCornerControl.visibility = if (caps.hasCornerRadius) View.VISIBLE else View.GONE
            seekRadius.progress = cfg.cornerRadiusDp
            tvRadiusVal.text = "${cfg.cornerRadiusDp} dp"

            layoutIconControl.visibility = if (caps.hasIcon) View.VISIBLE else View.GONE
            seekIconSize.progress = cfg.iconSizePercent
            tvIconSizeVal.text = "${cfg.iconSizePercent}%"

            seekTextSize.progress = cfg.textSizeSp
            tvTextSizeVal.text = "${cfg.textSizeSp} sp"

            if (caps.extraElementLabel1 != null) {
                layoutExtra1.visibility = View.VISIBLE
                tvExtra1Label.text = caps.extraElementLabel1
                seekExtra1.progress = cfg.extraParam1
                tvExtra1Val.text = "${cfg.extraParam1}%"
            } else {
                layoutExtra1.visibility = View.GONE
            }

            if (caps.extraElementLabel2 != null) {
                layoutExtra2.visibility = View.VISIBLE
                tvExtra2Label.text = caps.extraElementLabel2
                seekExtra2.progress = cfg.extraParam2
                tvExtra2Val.text = "${cfg.extraParam2}%"
            } else {
                layoutExtra2.visibility = View.GONE
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

            loadSlotColorIntoWheel(0)
            isUpdatingControlsProgrammatically = false
        }

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

        fun saveAndApplySlotColor(newColor: Int) {
            val cfg = SliderThemeManager.getCurrentConfig()
            val caps = SliderThemeManager.getCurrentCapabilities()
            val slot = currentColorSlotIndex.coerceIn(0, 5)
            cfg.colors[slot] = newColor

            val label = caps.colorSlotNames.getOrElse(slot) { "צבע" }
            val hex = String.format("#%06X", 0xFFFFFF and newColor)
            tvHexColor.text = "$label: $hex"
            tvHexColor.setTextColor(newColor)

            SliderThemeManager.saveStyleConfig(this)
            VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
        }

        colorWheelView.onWheelColorChanged = { pickedColor ->
            isUpdatingControlsProgrammatically = true
            seekHue.progress = colorWheelView.getHueSliderValue()
            seekSat.progress = colorWheelView.getSatSliderValue()
            seekVal.progress = colorWheelView.getValSliderValue()
            isUpdatingControlsProgrammatically = false
            saveAndApplySlotColor(pickedColor)
        }

        seekVal.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingControlsProgrammatically) return
                val newColor = colorWheelView.updateWheelBrightness(p)
                saveAndApplySlotColor(newColor)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekSat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingControlsProgrammatically) return
                val newColor = colorWheelView.updateWheelSaturationScale(p)
                saveAndApplySlotColor(newColor)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekHue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || isUpdatingControlsProgrammatically) return
                val newColor = colorWheelView.updateWheelHueShift(p)
                saveAndApplySlotColor(newColor)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekBorderWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().borderWidthDp = p
                tvBorderWidthVal.text = "$p dp"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
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

        seekIconSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                val safeSize = p.coerceAtLeast(40)
                SliderThemeManager.getCurrentConfig().iconSizePercent = safeSize
                tvIconSizeVal.text = "$safeSize%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                val safeSp = p.coerceAtLeast(9)
                SliderThemeManager.getCurrentConfig().textSizeSp = safeSp
                tvTextSizeVal.text = "$safeSp sp"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekExtra1.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().extraParam1 = p
                tvExtra1Val.text = "$p%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekExtra2.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (isUpdatingControlsProgrammatically) return
                SliderThemeManager.getCurrentConfig().extraParam2 = p
                tvExtra2Val.text = "$p%"
                SliderThemeManager.saveStyleConfig(this@MainActivity)
                VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

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

        btnToggleInteractiveEdit.setOnClickListener {
            val svc = VolumeOverlayService.instance
            if (svc == null) {
                Toast.makeText(this, "הפעל תחילה את שירות המחוון בהגדרות הנגישות", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val nowEditing = svc.toggleInteractiveEditMode(
                onPosChanged = { newXPercent, newYPercent ->
                    runOnUiThread {
                        isUpdatingControlsProgrammatically = true
                        seekPosX.progress = newXPercent
                        tvPosXVal.text = "$newXPercent%"
                        seekPosY.progress = newYPercent
                        tvPosYVal.text = "$newYPercent%"
                        isUpdatingControlsProgrammatically = false
                    }
                },
                onEditClosedFromOverlay = {
                    runOnUiThread {
                        updateEditButtonState(false)
                    }
                }
            )
            updateEditButtonState(nowEditing)
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
                val h = p.coerceAtLeast(40)
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

        syncUIFromCurrentStyleConfig()
    }

    private fun dpToPx(dpVal: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dpVal, resources.displayMetrics).roundToInt()
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
// 3. שירות המחוון הצף: כולל לחיצה רציפה (Hold-to-Volume) והגנה מלאה על צילום מסך!
// =========================================================================
class VolumeOverlayService : AccessibilityService() {

    companion object {
        var instance: VolumeOverlayService? = null
    }

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var maxSysVol = 15

    private var backdropTouchThroughView: View? = null
    private var badgeView: TextView? = null
    private var customOverlayCanvasView: CustomThemedSliderOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var isInteractiveEditActive = false
    private var onLivePositionChangedCallback: ((Int, Int) -> Unit)? = null
    private var onEditClosedCallback: (() -> Unit)? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (!isInteractiveEditActive) {
            removeAllOverlays()
        }
    }

    // מנגנון לחיצה רציפה (Continuous Hold) + זיהוי קיצורי מערכת (כמו צילום מסך)
    private val volumeKeyHandler = Handler(Looper.getMainLooper())
    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var activeHoldDirection = 0 // +1 = הגברה, -1 = הנמכה, 0 = ללא
    private var pendingSingleStepApplied = false
    private var comboShortcutDetected = false

    private val delayedInitialStepRunnable = Runnable {
        if (comboShortcutDetected || activeHoldDirection == 0) return@Runnable
        pendingSingleStepApplied = true
        stepVolumeInDirection(activeHoldDirection, stepAmount = 6)
        // אם הכפתור עדיין מוחזק לחוץ, מתחילים ריצה רציפה עד עזיבת הכפתור!
        volumeKeyHandler.postDelayed(holdRepeatRunnable, 240L)
    }

    private val holdRepeatRunnable = object : Runnable {
        override fun run() {
            if (comboShortcutDetected || activeHoldDirection == 0) return
            stepVolumeInDirection(activeHoldDirection, stepAmount = 5)
            volumeKeyHandler.postDelayed(this, 95L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AudioEngine.bootCooldownUntil = SystemClock.elapsedRealtime() + 4000L

        runCatching {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxSysVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15
            AudioEngine.init(this)
        }
    }

    fun isEditModeActive(): Boolean = isInteractiveEditActive

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!AudioEngine.isBoosterActive) return super.onKeyEvent(event)
        if (AudioEngine.isInternalVolumeChange) return false

        val keyCode = event.keyCode

        // אם נלחץ כפתור אחר בזמן שלוחצים על ווליום (למשל כפתור Power לצילום מסך),
        // מבטלים מיד את התערבות האפליקציה ומאפשרים למערכת לבצע את קיצור הדרך!
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isVolUpPressed || isVolDownPressed) {
                cancelVolumeHoldForSystemShortcut()
            }
            return super.onKeyEvent(event)
        }

        if (isInteractiveEditActive) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                closeInteractiveEditMode()
            }
            return true
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed = true
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = true

                // אם שני כפתורי הווליום נלחצו יחד (קיצור דרך של נגישות/מערכת), לא מתערבים!
                if (isVolUpPressed && isVolDownPressed) {
                    cancelVolumeHoldForSystemShortcut()
                    return false
                }

                // תחילת הלחיצה הראשונה (repeatCount == 0)
                if (event.repeatCount == 0 && activeHoldDirection == 0) {
                    comboShortcutDetected = false
                    pendingSingleStepApplied = false
                    activeHoldDirection = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                    volumeKeyHandler.removeCallbacks(delayedInitialStepRunnable)
                    volumeKeyHandler.removeCallbacks(holdRepeatRunnable)
                    // המתנה קצרה של 140ms כדי לוודא שזה לא שילוב כפתורים לצילום מסך
                    volumeKeyHandler.postDelayed(delayedInitialStepRunnable, 140L)
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                val dirBeforeRelease = activeHoldDirection
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed = false
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = false

                volumeKeyHandler.removeCallbacks(delayedInitialStepRunnable)
                volumeKeyHandler.removeCallbacks(holdRepeatRunnable)
                activeHoldDirection = 0

                // אם זו הייתה לחיצה קצרה רגילה (פחות מ-140ms) ולא קיצור דרך, מבצעים צעד ווליום אחד
                if (!comboShortcutDetected && !pendingSingleStepApplied && dirBeforeRelease != 0) {
                    stepVolumeInDirection(dirBeforeRelease, stepAmount = 7)
                }
                comboShortcutDetected = false
                pendingSingleStepApplied = false
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun cancelVolumeHoldForSystemShortcut() {
        comboShortcutDetected = true
        activeHoldDirection = 0
        volumeKeyHandler.removeCallbacks(delayedInitialStepRunnable)
        volumeKeyHandler.removeCallbacks(holdRepeatRunnable)
    }

    private fun stepVolumeInDirection(direction: Int, stepAmount: Int) {
        val am = audioManager ?: return
        val curSys = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val useNativeSystemUI = (AudioEngine.sliderStyleIndex == 0)

        if (direction > 0) {
            if (curSys < maxSysVol && AudioEngine.currentTotalVolume < 100) {
                val nextVol = (AudioEngine.currentTotalVolume + stepAmount).coerceAtMost(100)
                AudioEngine.currentTotalVolume = nextVol
            } else {
                val baseVol = if (AudioEngine.currentTotalVolume < 100) 100 else AudioEngine.currentTotalVolume
                AudioEngine.currentTotalVolume = (baseVol + stepAmount).coerceAtMost(200)
            }
        } else {
            if (AudioEngine.currentTotalVolume > 100) {
                AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume - stepAmount).coerceAtLeast(100)
            } else {
                AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume - stepAmount).coerceAtLeast(0)
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

    fun toggleInteractiveEditMode(
        onPosChanged: (Int, Int) -> Unit,
        onEditClosedFromOverlay: () -> Unit
    ): Boolean {
        onLivePositionChangedCallback = onPosChanged
        onEditClosedCallback = onEditClosedFromOverlay

        if (isInteractiveEditActive) {
            closeInteractiveEditMode()
        } else {
            isInteractiveEditActive = true
            hideHandler.removeCallbacks(hideRunnable)
            if (AudioEngine.sliderStyleIndex == 0) {
                AudioEngine.sliderStyleIndex = 1
            }
            removeBadge()
            showCustomThemedOverlay()
            customOverlayCanvasView?.isEditPositionMode = true
            customOverlayCanvasView?.invalidate()
        }
        return isInteractiveEditActive
    }

    fun closeInteractiveEditMode() {
        isInteractiveEditActive = false
        customOverlayCanvasView?.isEditPositionMode = false
        customOverlayCanvasView?.invalidate()
        onEditClosedCallback?.invoke()
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, 1200)
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
            updateTouchThroughBackdrop()
            val params = buildSliderWindowLayoutParams()
            overlayParams = params
            runCatching { wm.updateViewLayout(customOverlayCanvasView, params) }
        } else {
            updateTouchThroughBackdrop()
        }
        customOverlayCanvasView?.invalidate()
        if (!isInteractiveEditActive) {
            hideHandler.removeCallbacks(hideRunnable)
            hideHandler.postDelayed(hideRunnable, 3400)
        }
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()
    }

    private fun updateTouchThroughBackdrop() {
        val wm = windowManager ?: return
        val cfg = SliderThemeManager.getCurrentConfig()
        val needsBackdrop = (cfg.dimBackgroundEnabled && cfg.dimStrengthPercent > 0) ||
                            (cfg.blurBackgroundEnabled && cfg.blurStrengthPercent > 0)

        if (!needsBackdrop) {
            removeBackdrop()
            return
        }

        val dimAlpha = if (cfg.dimBackgroundEnabled) {
            ((cfg.dimStrengthPercent / 100f) * 200).roundToInt().coerceIn(0, 210)
        } else {
            0
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        if (cfg.blurBackgroundEnabled && cfg.blurStrengthPercent > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }

        val backdropParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                blurBehindRadius = if (cfg.blurBackgroundEnabled) {
                    ((cfg.blurStrengthPercent / 100f) * 80).roundToInt()
                } else {
                    0
                }
            }
        }

        if (backdropTouchThroughView == null) {
            backdropTouchThroughView = View(this).apply {
                setBackgroundColor(Color.argb(dimAlpha, 0, 0, 0))
            }
            runCatching { wm.addView(backdropTouchThroughView, backdropParams) }
        } else {
            backdropTouchThroughView?.setBackgroundColor(Color.argb(dimAlpha, 0, 0, 0))
            runCatching { wm.updateViewLayout(backdropTouchThroughView, backdropParams) }
        }
    }

    private fun buildSliderWindowLayoutParams(): WindowManager.LayoutParams {
        val cfg = SliderThemeManager.getCurrentConfig()
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val viewW = dp(cfg.widthDp.toFloat()).coerceIn(dp(44f), screenW)
        val viewH = dp(cfg.heightDp.toFloat()).coerceIn(dp(40f), screenH)

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

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
        }
    }

    private fun showCustomThemedOverlay() {
        hideHandler.removeCallbacks(hideRunnable)
        val wm = windowManager ?: return

        updateTouchThroughBackdrop()

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
                },
                onFinishEditClicked = {
                    closeInteractiveEditMode()
                }
            ).apply {
                isEditPositionMode = isInteractiveEditActive
            }

            overlayParams = buildSliderWindowLayoutParams()
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
                gravity = Gravity.CENTER
                elevation = dp(6f).toFloat()
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                x = dp(12f)
                y = -dp(155f)
            }
            runCatching { wm.addView(badgeView, params) }
        }

        val padH = dp(10f + (cfg.extraParam1 / 100f) * 12f)
        val padV = dp(6f + (cfg.extraParam1 / 100f) * 6f)
        badgeView?.apply {
            setPadding(padH, padV, padH, padV)
            textSize = cfg.textSizeSp.toFloat()
            background = GradientDrawable().apply {
                setColor(cfg.colors[0])
                cornerRadius = dp(cfg.cornerRadiusDp.toFloat()).toFloat()
                setStroke(dp(cfg.borderWidthDp.toFloat()), cfg.colors[1])
            }
            setTextColor(cfg.colors[2])
            text = "${AudioEngine.currentTotalVolume}% HD"
        }
        hideHandler.postDelayed(hideRunnable, 2800)
    }

    private fun removeBackdrop() {
        backdropTouchThroughView?.let {
            runCatching { windowManager?.removeView(it) }
            backdropTouchThroughView = null
        }
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
        removeBackdrop()
    }

    private fun removeAllOverlays() {
        removeBadge()
        removeCustomOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // אם נלכדה התראת מערכת של צילום מסך או תפריט כיבוי, מפסיקים מיד כל פעולת ווליום
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.contains("systemui", ignoreCase = true) && (isVolUpPressed || isVolDownPressed)) {
            val cls = event?.className?.toString() ?: ""
            if (cls.contains("Screenshot", ignoreCase = true) || cls.contains("GlobalActions", ignoreCase = true)) {
                cancelVolumeHoldForSystemShortcut()
                removeAllOverlays()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        volumeKeyHandler.removeCallbacksAndMessages(null)
        removeAllOverlays()
        instance = null
    }
}
 
