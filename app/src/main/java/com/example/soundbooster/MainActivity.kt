package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import kotlin.math.roundToInt

// =========================================================================
// 1. מנוע DSP HD מתקדם: מונע ירידת איכות (Anti-Clipping Limiter) ומשפר צלילות
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
        }
        updateSoundQualityDSP()
    }

    // מאתחל את ה-Limiter וה-Multi-band EQ למניעת צרידות ואיבוד איכות בווליום גבוה
    private fun initDynamicsEngine() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                1,
                true, 5,   // Pre-EQ של 5 ערוצים לעיצוב צליל נקי
                true, 5,   // Multi-band Compressor לייצוב התדרים
                true, 5,   // Post-EQ להדגשת Clarity
                true       // Limiter אקטיבי שמונע Clipping ב-200% ווליום!
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
            // 1. Limiter אקטיבי: נועל את תקרת הגל ב--0.8dB כדי שלעולם לא יהיה צליל שבור או צורם
            val limiter = DynamicsProcessing.Limiter(
                true,
                antiClippingEnabled,
                0,
                1.5f,    // Attack מהיר מאוד (1.5ms) לתפיסת פיקים
                45.0f,   // Release חלק
                4.0f,    // Ratio
                -0.8f,   // Threshold ששומר על צליל קריסטלי
                0.0f
            )
            dp.setLimiterAllChannelsTo(limiter)

            // 2. חידוד צלילות (Clarity) וחמימות (Warmth) בלי להעמיס על הרמקול
            val clarityGainDb = if (hiResOptimizerEnabled) (clarityLevel / 100f) * 4.5f else 0f
            val warmthGainDb = if (hiResOptimizerEnabled) (warmthLevel / 100f) * 3.2f else 0f

            val postEq = dp.getPostEqByChannelIndex(0)
            if (postEq != null && postEq.bandCount >= 5) {
                // תדרים נמוכים-חמים (Warmth)
                postEq.getBand(0)?.apply { isEnabled = true; gain = warmthGainDb * 0.7f; dp.setPostEqBandAllChannelsTo(0, this) }
                postEq.getBand(1)?.apply { isEnabled = true; gain = warmthGainDb; dp.setPostEqBandAllChannelsTo(1, this) }
                // הפחתת עכירות במיד-נמוך (Mud Removal)
                postEq.getBand(2)?.apply { isEnabled = true; gain = if (hiResOptimizerEnabled) -0.8f else 0f; dp.setPostEqBandAllChannelsTo(2, this) }
                // הדגשת פרטים, קול וטרבל קריסטלי (Clarity)
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

    // מעדכן את העוצמה; כש-showNativeSystemUI=true המחוון המקורי של הטלפון קופץ!
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
            // מציג את המחוון המקורי של המכשיר ב-100% ומוסיף הגברה נקייה מבוססת DSP
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSysVol, flags) }
            val extra = currentTotalVolume - 100
            // כשה-Anti-Clipping פעיל, ההגברה מכוילת לעקומה אקוסטית נקייה ללא דיסטורשן
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
// 2. המסך הראשי של האפליקציה עם 4 קטגוריות
// =========================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var maxSystemVolume: Int = 15

    private lateinit var seekTotalVolume: SeekBar
    private lateinit var switchEnable: SwitchCompat
    private lateinit var btnEnableSystemSlider: MaterialButton
    private val eqSliders = mutableListOf<SeekBar>()

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

        val secVolume = findViewById<View>(R.id.sectionVolume)
        val secQuality = findViewById<View>(R.id.sectionQuality)
        val secEffects = findViewById<View>(R.id.sectionEffects)
        val secEq = findViewById<View>(R.id.sectionEq)

        val tabs = listOf(tabVolume, tabQuality, tabEffects, tabEq)
        val sections = listOf(secVolume, secQuality, secEffects, secEq)

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
    }

    private fun updateServiceButtonState() {
        if (VolumeOverlayService.instance != null) {
            btnEnableSystemSlider.text = "✅ מחוון המערכת המקורי של המכשיר מורחב ל-200%!"
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

        // רכיבי קטגוריית איכות סאונד HD
        val switchAntiClip = findViewById<SwitchCompat>(R.id.switchAntiClip)
        val switchHiRes = findViewById<SwitchCompat>(R.id.switchHiRes)
        val seekClarity = findViewById<SeekBar>(R.id.seekClarity)
        val tvClarityValue = findViewById<TextView>(R.id.tvClarityValue)
        val seekWarmth = findViewById<SeekBar>(R.id.seekWarmth)
        val tvWarmthValue = findViewById<TextView>(R.id.tvWarmthValue)

        // רכיבי קטגוריית אפקטים
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

        // מאזינים לקטגוריית איכות סאונד HD
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

        // מאזינים לקטגוריית אפקטים
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

        // בניית האקולייזר
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
// 3. שירות היברידי חכם: מציג את המחוון המקורי של המכשיר (100% Native SystemUI)
//    ומצרף אליו בועית אחוזים זעירה תואמת למערכת כשעוברים את ה-100%!
// =========================================================================
class VolumeOverlayService : AccessibilityService() {

    companion object {
        var instance: VolumeOverlayService? = null
    }

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var maxSysVol = 15

    private var badgeView: TextView? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { removeBadge() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxSysVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

            AudioEngine.init(this)

            val curSys = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxSysVol
            if (AudioEngine.currentTotalVolume <= 100) {
                AudioEngine.currentTotalVolume = ((curSys.toFloat() / maxSysVol) * 100).roundToInt()
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!AudioEngine.isBoosterActive) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val am = audioManager ?: return super.onKeyEvent(event)
                val curSys = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val step = 10

                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    if (curSys < maxSysVol && AudioEngine.currentTotalVolume < 100) {
                        // מתחת ל-100%: נותן למערכת ההפעלה המקורית להעלות ווליום ולהציג את המחוון המקורי שלה
                        val nextSys = (curSys + 1).coerceAtMost(maxSysVol)
                        AudioEngine.currentTotalVolume = ((nextSys.toFloat() / maxSysVol) * 100).roundToInt()
                        AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
                        showNativeBadgeIfNeeded()
                        return true
                    } else {
                        // הגענו ל-100% ומעלה: מקפיץ את המחוון המקורי של הטלפון ומוסיף את ההגברה עד 200%!
                        val baseVol = if (AudioEngine.currentTotalVolume < 100) 100 else AudioEngine.currentTotalVolume
                        AudioEngine.currentTotalVolume = (baseVol + step).coerceAtMost(200)
                        AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
                        showNativeBadgeIfNeeded()
                        return true
                    }
                } else {
                    // לחיצה על ווליום למטה
                    if (AudioEngine.currentTotalVolume > 100) {
                        // יורדים מ-200% חזרה לכיוון 100% תוך הצגת המחוון המקורי של הטלפון
                        AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume - step).coerceAtLeast(100)
                        AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
                        showNativeBadgeIfNeeded()
                        return true
                    } else {
                        // מ-100% ומטה: מוריד ווליום רגיל עם המחוון המקורי של המכשיר
                        val prevSys = (curSys - 1).coerceAtLeast(0)
                        AudioEngine.currentTotalVolume = ((prevSys.toFloat() / maxSysVol) * 100).roundToInt()
                        AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
                        removeBadge()
                        return true
                    }
                }
            }
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()
    }

    // שואב את צבעי המערכת המקוריים של המכשיר שעליו האפליקציה רצה
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

    // מציג קפסולת אחוזים מינימלית בצבעי המערכת רק כשעוברים את ה-100%, לצד המחוון המקורי של הטלפון
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
                // ממוקם מעל המחוון המקורי של הטלפון מבלי להסתיר אותו
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeBadge()
        instance = null
    }
}
 
