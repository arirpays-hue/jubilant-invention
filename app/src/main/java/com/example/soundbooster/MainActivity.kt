package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
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
// 1. מנוע סאונד והגברה דרך מחוון המערכת המקורי של המכשיר (Native SystemUI)
// =========================================================================
object AudioEngine {
    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var presetReverb: PresetReverb? = null

    var currentTotalVolume: Int = 100
    var savedMediaBoostVolume: Int = 100
    var isBoosterActive: Boolean = true
    var allow300PercentBoost: Boolean = false

    // 0 = מדיה, 1 = עוצמת צלצול, 2 = התראות, 3 = שעון מעורר, 4 = שיחת טלפון
    var activeStreamType: Int = 0
    var isBluetoothConnected: Boolean = false
    private var manualStreamOverrideUntil: Long = 0L

    var antiClippingEnabled: Boolean = true
    var hiResOptimizerEnabled: Boolean = true
    var clarityLevel: Int = 50
    var warmthLevel: Int = 40

    // מנוע ה-AI המוכן לשיפור שירים לאיכות גבוהה במיוחד
    var aiSongEngineEnabled: Boolean = true
    var aiPresetMode: Int = 0
    var aiNeuralIntensity: Int = 85

    // כיוון ידני משלים למנוע השירים
    var songRemasterEnabled: Boolean = true
    var songUpscaleLevel: Int = 75
    var songSeparationLevel: Int = 65
    var songPunchLevel: Int = 60

    @Volatile var isInternalVolumeChange: Boolean = false
    var bootCooldownUntil: Long = 0L
    private var playbackCallbackRegistered = false
    private var isInitializedOnce = false

    fun getEffectiveMaxVolume(): Int {
        if (activeStreamType != 0) return 100
        return when {
            !isBoosterActive -> 100
            allow300PercentBoost -> 300
            else -> 200
        }
    }

    fun getAndroidStreamConstant(streamIndex: Int = activeStreamType): Int {
        return when (streamIndex) {
            1 -> AudioManager.STREAM_RING
            2 -> AudioManager.STREAM_NOTIFICATION
            3 -> AudioManager.STREAM_ALARM
            4 -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
    }

    fun getActiveStreamHebrewLabel(): String {
        return when (activeStreamType) {
            1 -> "עוצמת צלצול"
            2 -> "התראות"
            3 -> "שעון מעורר"
            4 -> "שיחת טלפון"
            else -> if (isBluetoothConnected) "מדיה (Bluetooth)" else "מדיה ומוזיקה"
        }
    }

    fun updateBluetoothStatusOnly(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        var btFound = false
        runCatching {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in devices) {
                val t = dev.type
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                        t == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        t == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        t == AudioDeviceInfo.TYPE_BLE_BROADCAST
                    ))
                ) {
                    btFound = true
                    break
                }
            }
        }
        isBluetoothConnected = btFound
    }

    fun refreshBluetoothAndStreamState(context: Context) {
        updateBluetoothStatusOnly(context)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        val now = SystemClock.elapsedRealtime()
        if (now > manualStreamOverrideUntil) {
            val autoStream = when (am.mode) {
                AudioManager.MODE_RINGTONE -> 1
                AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> 4
                else -> 0
            }
            if (autoStream != activeStreamType) {
                if (activeStreamType == 0) {
                    savedMediaBoostVolume = currentTotalVolume
                }
                activeStreamType = autoStream
                if (activeStreamType == 0) {
                    currentTotalVolume = savedMediaBoostVolume.coerceIn(0, getEffectiveMaxVolume())
                } else {
                    syncCurrentVolumeFromActiveStream(am)
                }
            }
        }
    }

    fun cycleNextStream(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (activeStreamType == 0) {
            savedMediaBoostVolume = currentTotalVolume
        }
        activeStreamType = (activeStreamType + 1) % 5
        manualStreamOverrideUntil = SystemClock.elapsedRealtime() + 12000L
        if (activeStreamType == 0) {
            currentTotalVolume = savedMediaBoostVolume.coerceIn(0, getEffectiveMaxVolume())
        } else {
            syncCurrentVolumeFromActiveStream(am)
        }
    }

    private fun syncCurrentVolumeFromActiveStream(am: AudioManager) {
        val streamConst = getAndroidStreamConstant(activeStreamType)
        val maxSys = am.getStreamMaxVolume(streamConst).coerceAtLeast(1)
        val curSys = am.getStreamVolume(streamConst)
        currentTotalVolume = ((curSys.toFloat() / maxSys) * 100f).roundToInt().coerceIn(0, 100)
    }

    fun init(context: Context? = null) {
        context?.let {
            val prefs = it.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            allow300PercentBoost = prefs.getBoolean("allow_300_boost", allow300PercentBoost)

            aiSongEngineEnabled = prefs.getBoolean("ai_song_en", aiSongEngineEnabled)
            aiPresetMode = prefs.getInt("ai_preset_mode", aiPresetMode)
            aiNeuralIntensity = prefs.getInt("ai_intensity", aiNeuralIntensity)

            songRemasterEnabled = prefs.getBoolean("song_remaster_en", songRemasterEnabled)
            songUpscaleLevel = prefs.getInt("song_upscale", songUpscaleLevel)
            songSeparationLevel = prefs.getInt("song_sep", songSeparationLevel)
            songPunchLevel = prefs.getInt("song_punch", songPunchLevel)

            if (!isInitializedOnce) {
                if (prefs.contains("saved_vol")) {
                    currentTotalVolume = prefs.getInt("saved_vol", 100).coerceIn(0, getEffectiveMaxVolume())
                } else {
                    val am = it.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (am != null) {
                        val maxS = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val curS = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                        currentTotalVolume = ((curS.toFloat() / maxS) * 100f).roundToInt().coerceIn(0, 100)
                    }
                }
                savedMediaBoostVolume = currentTotalVolume
                isInitializedOnce = true
            }

            updateBluetoothStatusOnly(it)
            registerAssistantSafeRecovery(it.applicationContext)
        }
        ensureAudioEffectsCreated()
        updateSoundQualityDSP()
    }

    fun saveEngineModes(context: Context) {
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE).edit()
            .putBoolean("allow_300_boost", allow300PercentBoost)
            .putBoolean("ai_song_en", aiSongEngineEnabled)
            .putInt("ai_preset_mode", aiPresetMode)
            .putInt("ai_intensity", aiNeuralIntensity)
            .putBoolean("song_remaster_en", songRemasterEnabled)
            .putInt("song_upscale", songUpscaleLevel)
            .putInt("song_sep", songSeparationLevel)
            .putInt("song_punch", songPunchLevel)
            .apply()
    }

    private fun ensureAudioEffectsCreated() {
        val effectsActive = isBoosterActive
        if (loudnessEnhancer == null) {
            runCatching { loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = effectsActive } }
        }
        if (equalizer == null) {
            runCatching { equalizer = Equalizer(0, 0).apply { enabled = effectsActive } }
        }
        if (bassBoost == null) {
            runCatching { bassBoost = BassBoost(0, 0).apply { enabled = effectsActive } }
        }
        if (virtualizer == null) {
            runCatching { virtualizer = Virtualizer(0, 0).apply { enabled = effectsActive } }
        }
        if (presetReverb == null) {
            runCatching { presetReverb = PresetReverb(0, 0).apply { enabled = effectsActive } }
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
                    updateBluetoothStatusOnly(appContext)
                    if (!isBoosterActive) {
                        runCatching { loudnessEnhancer?.setTargetGain(0) }
                        return
                    }
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
        if (!isBoosterActive) {
            runCatching { loudnessEnhancer?.setTargetGain(0) }
            return
        }
        val mediaVol = if (activeStreamType == 0) currentTotalVolume else savedMediaBoostVolume
        if (mediaVol <= 100) {
            runCatching { loudnessEnhancer?.setTargetGain(0) }
            return
        }
        val extra = (mediaVol - 100).coerceAtLeast(0)
        val factor = if (antiClippingEnabled) 17 else 24
        val cleanGain = extra * factor

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

    fun updateSoundQualityDSP() {
        if (!isBoosterActive) return
        val eq = equalizer ?: return

        runCatching {
            val bands = eq.numberOfBands.toInt()
            if (bands < 3) return
            val maxEQ = eq.bandLevelRange[1].toInt()
            val minEQ = eq.bandLevelRange[0].toInt()

            var lowBandGain = 0f
            var lowMidGain = 0f
            var centerMidGain = 0f
            var presenceGain = 0f
            var airHighGain = 0f

            if (hiResOptimizerEnabled) {
                lowBandGain += (warmthLevel / 100f) * (maxEQ * 0.22f)
                airHighGain += (clarityLevel / 100f) * (maxEQ * 0.28f)
            }

            if (songRemasterEnabled) {
                airHighGain += (songUpscaleLevel / 100f) * (maxEQ * 0.30f)
                lowMidGain -= (songSeparationLevel / 100f) * (maxEQ * 0.14f)
                presenceGain += (songSeparationLevel / 100f) * (maxEQ * 0.24f)
                lowBandGain += (songPunchLevel / 100f) * (maxEQ * 0.25f)
            }

            if (aiSongEngineEnabled) {
                val aiPower = (aiNeuralIntensity / 100f).coerceIn(0f, 1f)
                val highVolProtection = if (currentTotalVolume > 140) 0.72f else 1.0f

                when (aiPresetMode) {
                    0 -> {
                        lowBandGain += aiPower * (maxEQ * 0.42f) * highVolProtection
                        lowMidGain -= aiPower * (maxEQ * 0.18f)
                        centerMidGain += aiPower * (maxEQ * 0.12f)
                        presenceGain += aiPower * (maxEQ * 0.36f)
                        airHighGain += aiPower * (maxEQ * 0.45f)

                        runCatching { bassBoost?.setStrength((aiPower * 680 * highVolProtection).toInt().toShort()) }
                        runCatching { virtualizer?.setStrength((aiPower * 720).toInt().toShort()) }
                        runCatching { presetReverb?.preset = 1.toShort() }
                    }
                    1 -> {
                        lowBandGain += aiPower * (maxEQ * 0.62f) * highVolProtection
                        lowMidGain += aiPower * (maxEQ * 0.15f)
                        presenceGain += aiPower * (maxEQ * 0.25f)
                        airHighGain += aiPower * (maxEQ * 0.32f)

                        runCatching { bassBoost?.setStrength((aiPower * 920 * highVolProtection).toInt().toShort()) }
                        runCatching { virtualizer?.setStrength((aiPower * 550).toInt().toShort()) }
                        runCatching { presetReverb?.preset = 0.toShort() }
                    }
                    2 -> {
                        lowBandGain += aiPower * (maxEQ * 0.20f)
                        lowMidGain -= aiPower * (maxEQ * 0.26f)
                        centerMidGain += aiPower * (maxEQ * 0.38f)
                        presenceGain += aiPower * (maxEQ * 0.52f)
                        airHighGain += aiPower * (maxEQ * 0.55f)

                        runCatching { bassBoost?.setStrength((aiPower * 350).toInt().toShort()) }
                        runCatching { virtualizer?.setStrength((aiPower * 680).toInt().toShort()) }
                        runCatching { presetReverb?.preset = 1.toShort() }
                    }
                    else -> {
                        lowBandGain += aiPower * (maxEQ * 0.45f) * highVolProtection
                        centerMidGain += aiPower * (maxEQ * 0.20f)
                        presenceGain += aiPower * (maxEQ * 0.42f)
                        airHighGain += aiPower * (maxEQ * 0.50f)

                        runCatching { bassBoost?.setStrength((aiPower * 740 * highVolProtection).toInt().toShort()) }
                        runCatching { virtualizer?.setStrength((aiPower * 980).toInt().toShort()) }
                        runCatching { presetReverb?.preset = 4.toShort() }
                    }
                }
            }

            eq.setBandLevel(0, lowBandGain.toInt().coerceIn(minEQ, maxEQ).toShort())
            if (bands >= 5) {
                eq.setBandLevel(1, lowMidGain.toInt().coerceIn(minEQ, maxEQ).toShort())
                eq.setBandLevel(2, centerMidGain.toInt().coerceIn(minEQ, maxEQ).toShort())
                eq.setBandLevel(3, presenceGain.toInt().coerceIn(minEQ, maxEQ).toShort())
                eq.setBandLevel(4, airHighGain.toInt().coerceIn(minEQ, maxEQ).toShort())
            } else if (bands == 4) {
                eq.setBandLevel(1, lowMidGain.toInt().coerceIn(minEQ, maxEQ).toShort())
                eq.setBandLevel(2, presenceGain.toInt().coerceIn(minEQ, maxEQ).toShort())
                eq.setBandLevel(3, airHighGain.toInt().coerceIn(minEQ, maxEQ).toShort())
            } else {
                eq.setBandLevel(1, centerMidGain.toInt().coerceIn(minEQ, maxEQ).toShort())
                eq.setBandLevel(2, airHighGain.toInt().coerceIn(minEQ, maxEQ).toShort())
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        isBoosterActive = enabled
        runCatching {
            if (!enabled) loudnessEnhancer?.setTargetGain(0)
            loudnessEnhancer?.enabled = enabled
        }
        runCatching { equalizer?.enabled = enabled }
        runCatching { bassBoost?.enabled = enabled }
        runCatching { virtualizer?.enabled = enabled }
        runCatching { presetReverb?.enabled = enabled }
    }

    fun applyVolume(context: Context, totalPercent: Int, showNativeSystemUI: Boolean = false) {
        ensureAudioEffectsCreated()
        val maxAllowed = getEffectiveMaxVolume()
        currentTotalVolume = totalPercent.coerceIn(0, maxAllowed)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val now = SystemClock.elapsedRealtime()

        val allowNativeUI = showNativeSystemUI && (now > bootCooldownUntil)
        val flags = if (allowNativeUI) AudioManager.FLAG_SHOW_UI else 0

        isInternalVolumeChange = true
        try {
            if (activeStreamType != 0) {
                val targetStream = getAndroidStreamConstant(activeStreamType)
                val maxStreamVol = audioManager.getStreamMaxVolume(targetStream).coerceAtLeast(1)
                val sysTarget = ((currentTotalVolume.coerceIn(0, 100) / 100f) * maxStreamVol).roundToInt()
                val curStreamVol = audioManager.getStreamVolume(targetStream)
                if (curStreamVol != sysTarget || allowNativeUI) {
                    runCatching { audioManager.setStreamVolume(targetStream, sysTarget, flags) }
                }
                return
            }

            savedMediaBoostVolume = currentTotalVolume
            context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
                .edit().putInt("saved_vol", currentTotalVolume).apply()

            val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (currentTotalVolume <= 100 || !isBoosterActive) {
                val safePct = currentTotalVolume.coerceIn(0, 100)
                val sysTarget = ((safePct / 100f) * maxSysVol).roundToInt()
                if (curSys != sysTarget || allowNativeUI) {
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysTarget, flags) }
                }
                runCatching { loudnessEnhancer?.setTargetGain(0) }
            } else {
                if (curSys < maxSysVol || allowNativeUI) {
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSysVol, flags) }
                }
                reapplyCurrentGainSafely()
            }
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({
                isInternalVolumeChange = false
            }, 140)
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
    private lateinit var progressTotalVolume: ProgressBar
    private lateinit var tvVolumeCardTitle: TextView
    private lateinit var btnVol300: MaterialButton
    private lateinit var switchEnable: SwitchCompat
    private lateinit var btnEnableSystemSlider: MaterialButton
    private val eqSliders = mutableListOf<SeekBar>()

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

        setupCategoryTabs()
        setupModernUI()
    }

    override fun onResume() {
        super.onResume()
        AudioEngine.updateBluetoothStatusOnly(this)
        updateServiceButtonState()
        syncVolumeRangeUI()
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
            val maxTxt = AudioEngine.getEffectiveMaxVolume()
            btnEnableSystemSlider.text = "הגברת מחוון המערכת ($maxTxt%) פעילה ומחוברת"
            btnEnableSystemSlider.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
        } else {
            btnEnableSystemSlider.text = "הפעל הרחבת מחוון מערכת בהגדרות"
            btnEnableSystemSlider.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
        }
    }

    private fun syncVolumeRangeUI() {
        isUpdatingControlsProgrammatically = true
        val maxAllowed = AudioEngine.getEffectiveMaxVolume()
        seekTotalVolume.max = maxAllowed
        progressTotalVolume.max = maxAllowed

        val streamLabel = AudioEngine.getActiveStreamHebrewLabel()
        tvVolumeCardTitle.text = when {
            AudioEngine.activeStreamType != 0 -> "ערוץ פעיל: $streamLabel (0% - 100%)"
            AudioEngine.allow300PercentBoost -> "עוצמת $streamLabel (0% - 300%)"
            else -> "עוצמת $streamLabel (0% - 200%)"
        }

        btnVol300.visibility = if (AudioEngine.allow300PercentBoost && AudioEngine.activeStreamType == 0) {
            View.VISIBLE
        } else {
            View.GONE
        }

        if (AudioEngine.currentTotalVolume > maxAllowed) {
            AudioEngine.currentTotalVolume = maxAllowed
            AudioEngine.applyVolume(this, maxAllowed, false)
        }
        seekTotalVolume.progress = AudioEngine.currentTotalVolume
        progressTotalVolume.progress = AudioEngine.currentTotalVolume
        isUpdatingControlsProgrammatically = false
        updateServiceButtonState()
    }

    private fun setupModernUI() {
        switchEnable = findViewById(R.id.switchEnable)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val cardHeader = findViewById<MaterialCardView>(R.id.cardHeader)
        btnEnableSystemSlider = findViewById(R.id.btnEnableSystemSlider)

        tvVolumeCardTitle = findViewById(R.id.tvVolumeCardTitle)
        seekTotalVolume = findViewById(R.id.seekTotalVolume)
        val tvTotalVolume = findViewById<TextView>(R.id.tvTotalVolume)
        val tvSubBoostInfo = findViewById<TextView>(R.id.tvSubBoostInfo)
        progressTotalVolume = findViewById(R.id.progressTotalVolume)
        btnVol300 = findViewById(R.id.btnVol300)

        val switchAllow300 = findViewById<SwitchCompat>(R.id.switchAllow300Boost)

        val switchAiEngine = findViewById<SwitchCompat>(R.id.switchAiSongEngine)
        val tvAiSubStatus = findViewById<TextView>(R.id.tvAiEngineSubStatus)
        val btnAiAuto = findViewById<MaterialButton>(R.id.btnAiModeAuto)
        val btnAiBass = findViewById<MaterialButton>(R.id.btnAiModeBass)
        val btnAiVocal = findViewById<MaterialButton>(R.id.btnAiModeVocal)
        val btnAiConcert = findViewById<MaterialButton>(R.id.btnAiModeConcert)
        val aiModeBtns = listOf(btnAiAuto, btnAiBass, btnAiVocal, btnAiConcert)
        val seekAiIntensity = findViewById<SeekBar>(R.id.seekAiIntensity)
        val tvAiIntensityVal = findViewById<TextView>(R.id.tvAiIntensityValue)

        val switchAntiClip = findViewById<SwitchCompat>(R.id.switchAntiClip)
        val switchHiRes = findViewById<SwitchCompat>(R.id.switchHiRes)
        val seekClarity = findViewById<SeekBar>(R.id.seekClarity)
        val tvClarityValue = findViewById<TextView>(R.id.tvClarityValue)
        val seekWarmth = findViewById<SeekBar>(R.id.seekWarmth)
        val tvWarmthValue = findViewById<TextView>(R.id.tvWarmthValue)

        val switchSongRemaster = findViewById<SwitchCompat>(R.id.switchSongRemaster)
        val seekSongUpscale = findViewById<SeekBar>(R.id.seekSongUpscale)
        val tvSongUpscaleVal = findViewById<TextView>(R.id.tvSongUpscaleValue)
        val seekSongSep = findViewById<SeekBar>(R.id.seekSongSeparation)
        val tvSongSepVal = findViewById<TextView>(R.id.tvSongSeparationValue)
        val seekSongPunch = findViewById<SeekBar>(R.id.seekSongPunch)
        val tvSongPunchVal = findViewById<TextView>(R.id.tvSongPunchValue)

        val seekBass = findViewById<SeekBar>(R.id.seekBass)
        val tvBassValue = findViewById<TextView>(R.id.tvBassValue)
        val seekVirtualizer = findViewById<SeekBar>(R.id.seekVirtualizer)
        val tvVirtualizerValue = findViewById<TextView>(R.id.tvVirtualizerValue)
        val seekReverb = findViewById<SeekBar>(R.id.seekReverb)
        val tvReverbValue = findViewById<TextView>(R.id.tvReverbValue)

        val eqContainer = findViewById<LinearLayout>(R.id.eqContainer)

        tvVolumeCardTitle.setOnClickListener {
            AudioEngine.cycleNextStream(this)
            syncVolumeRangeUI()
            Toast.makeText(this, "ערוץ נבחר: ${AudioEngine.getActiveStreamHebrewLabel()} (לחץ שוב להחלפה)", Toast.LENGTH_SHORT).show()
        }

        btnEnableSystemSlider.setOnClickListener {
            Toast.makeText(this, "הפעל את 'Sound Booster מחוון ווליום 200%'", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        fun refreshHeaderStatusText() {
            val btStatus = if (AudioEngine.isBluetoothConnected) " | BT מחובר" else ""
            when {
                !AudioEngine.isBoosterActive -> {
                    tvStatus.text = "מנוע סאונד כבוי ●$btStatus"
                    tvStatus.setTextColor(Color.parseColor("#FF5252"))
                    cardHeader.strokeColor = Color.parseColor("#263042")
                }
                AudioEngine.aiSongEngineEnabled -> {
                    tvStatus.text = "מנוע AI Neural Mastering פעיל ● שדרוג שירים חי$btStatus"
                    tvStatus.setTextColor(Color.parseColor("#00E676"))
                    cardHeader.strokeColor = Color.parseColor("#00E676")
                }
                else -> {
                    tvStatus.text = "מנוע DSP HD פעיל ● מוגן מהתנגשויות שמע$btStatus"
                    tvStatus.setTextColor(Color.parseColor("#00E5FF"))
                    cardHeader.strokeColor = Color.parseColor("#00E5FF")
                }
            }
        }

        fun updateAiButtonsUI() {
            val modeNames = listOf(
                "AI Auto-Master: איזון אולפני אוטומטי לכל שיר",
                "AI Deep Bass: הדגשת תדרי סאב ופאנץ' נקי",
                "AI Crystal Vocal: הבלטת שירה והפרדת כלים",
                "AI 3D Concert: במת הופעה חיה וסראונד היקפי"
            )
            tvAiSubStatus.text = if (AudioEngine.aiSongEngineEnabled) {
                "פעיל ● ${modeNames.getOrElse(AudioEngine.aiPresetMode) { modeNames[0] }}"
            } else {
                "מנוע ה-AI כבוי כעת (ניתן להפעיל בלחיצה על המתג)"
            }
            for (i in aiModeBtns.indices) {
                val active = (i == AudioEngine.aiPresetMode && AudioEngine.aiSongEngineEnabled)
                aiModeBtns[i].strokeColor = ColorStateList.valueOf(
                    if (active) Color.parseColor("#00E676") else Color.parseColor("#3A465E")
                )
                aiModeBtns[i].setTextColor(
                    if (active) Color.parseColor("#00E676") else Color.parseColor("#A0AAB8")
                )
            }
        }

        switchAiEngine.isChecked = AudioEngine.aiSongEngineEnabled
        seekAiIntensity.progress = AudioEngine.aiNeuralIntensity
        tvAiIntensityVal.text = "${AudioEngine.aiNeuralIntensity}%"
        updateAiButtonsUI()

        switchAiEngine.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.aiSongEngineEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
            updateAiButtonsUI()
            refreshHeaderStatusText()
        }

        for (modeIdx in aiModeBtns.indices) {
            aiModeBtns[modeIdx].setOnClickListener {
                AudioEngine.aiPresetMode = modeIdx
                if (!AudioEngine.aiSongEngineEnabled) {
                    AudioEngine.aiSongEngineEnabled = true
                    switchAiEngine.isChecked = true
                }
                AudioEngine.saveEngineModes(this)
                AudioEngine.updateSoundQualityDSP()
                updateAiButtonsUI()
                refreshHeaderStatusText()
            }
        }

        seekAiIntensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvAiIntensityVal.text = "$p%"
                AudioEngine.aiNeuralIntensity = p
                AudioEngine.saveEngineModes(this@MainActivity)
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.setEnabled(isChecked)
            syncVolumeRangeUI()
            refreshHeaderStatusText()
        }

        switchAllow300.isChecked = AudioEngine.allow300PercentBoost
        switchAllow300.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.allow300PercentBoost = isChecked
            AudioEngine.saveEngineModes(this)
            syncVolumeRangeUI()
            refreshHeaderStatusText()
        }

        seekTotalVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTotalVolume.text = "$progress%"
                progressTotalVolume.progress = progress

                if (fromUser && !isUpdatingControlsProgrammatically) {
                    AudioEngine.applyVolume(this@MainActivity, progress, showNativeSystemUI = false)
                }

                val curStreamName = AudioEngine.getActiveStreamHebrewLabel()
                if (AudioEngine.activeStreamType != 0) {
                    tvSubBoostInfo.text = "ערוץ: $curStreamName ($progress%)"
                } else if (progress <= 100) {
                    tvSubBoostInfo.text = "ערוץ: $curStreamName | מנוע AI + HD: מוכנים"
                } else {
                    val extraPercent = progress - 100
                    val db = String.format("%.1f", extraPercent * 0.17f)
                    tvSubBoostInfo.text = "ערוץ: $curStreamName | הגברת AI HD נקייה: +$db dB"
                }

                val color = when {
                    progress > 220 -> Color.parseColor("#FF1744")
                    progress > 160 -> Color.parseColor("#FF9100")
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
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            seekTotalVolume.progress = 150
            AudioEngine.applyVolume(this, 150, false)
        }
        findViewById<MaterialButton>(R.id.btnVol200).setOnClickListener {
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            seekTotalVolume.progress = 200
            AudioEngine.applyVolume(this, 200, false)
        }
        btnVol300.setOnClickListener {
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            if (AudioEngine.allow300PercentBoost) {
                seekTotalVolume.progress = 300
                AudioEngine.applyVolume(this, 300, false)
            }
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
                if (!fromUser) return
                tvClarityValue.text = "$p%"
                AudioEngine.clarityLevel = p
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekWarmth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvWarmthValue.text = "$p%"
                AudioEngine.warmthLevel = p
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchSongRemaster.isChecked = AudioEngine.songRemasterEnabled
        seekSongUpscale.progress = AudioEngine.songUpscaleLevel
        tvSongUpscaleVal.text = "${AudioEngine.songUpscaleLevel}%"
        seekSongSep.progress = AudioEngine.songSeparationLevel
        tvSongSepVal.text = "${AudioEngine.songSeparationLevel}%"
        seekSongPunch.progress = AudioEngine.songPunchLevel
        tvSongPunchVal.text = "${AudioEngine.songPunchLevel}%"

        switchSongRemaster.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.songRemasterEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }
        seekSongUpscale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvSongUpscaleVal.text = "$p%"
                AudioEngine.songUpscaleLevel = p
                AudioEngine.saveEngineModes(this@MainActivity)
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekSongSep.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvSongSepVal.text = "$p%"
                AudioEngine.songSeparationLevel = p
                AudioEngine.saveEngineModes(this@MainActivity)
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekSongPunch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                tvSongPunchVal.text = "$p%"
                AudioEngine.songPunchLevel = p
                AudioEngine.saveEngineModes(this@MainActivity)
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBassValue.text = "${progress / 10}%"
                if (fromUser) {
                    runCatching { AudioEngine.bassBoost?.setStrength(progress.toShort()) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVirtualizerValue.text = "${progress / 10}%"
                if (fromUser) {
                    runCatching { AudioEngine.virtualizer?.setStrength(progress.toShort()) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val reverbNames = listOf("ללא", "חדר קטן", "חדר בינוני", "חדר גדול", "אולם בינוני", "אולם גדול", "פלטה אקוסטית")
        seekReverb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvReverbValue.text = reverbNames.getOrElse(progress) { "ללא" }
                if (fromUser) {
                    runCatching { AudioEngine.presetReverb?.preset = progress.toShort() }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
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

                    val curLevel = eq.getBandLevel(band).toInt()
                    val initDb = curLevel / 100
                    val tvDb = TextView(this).apply {
                        text = if (initDb > 0) "+$initDb dB" else "$initDb dB"
                        setTextColor(Color.parseColor("#00E5FF"))
                        textSize = 13f
                        gravity = Gravity.END
                    }

                    rowHeader.addView(tvFreq)
                    rowHeader.addView(tvDb)

                    val seekBar = SeekBar(this).apply {
                        max = (maxEQ - minEQ).coerceAtLeast(1)
                        progress = (curLevel - minEQ).coerceAtLeast(0)
                        progressTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                        thumbTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))

                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                                val newLevel = (p + minEQ).toShort()
                                val dbVal = newLevel.toInt() / 100
                                tvDb.text = if (dbVal > 0) "+$dbVal dB" else "$dbVal dB"
                                if (fromUser) {
                                    runCatching { eq.setBandLevel(band, newLevel) }
                                }
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

        syncVolumeRangeUI()
        refreshHeaderStatusText()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val svc = VolumeOverlayService.instance
            if (svc != null) {
                val handled = svc.handleVolumeKeyEventDirect(event)
                seekTotalVolume.progress = AudioEngine.currentTotalVolume
                return handled
            } else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val maxAllowed = AudioEngine.getEffectiveMaxVolume()
                    val delta = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 6 else -6
                    val next = (AudioEngine.currentTotalVolume + delta).coerceIn(0, maxAllowed)
                    seekTotalVolume.progress = next
                    AudioEngine.applyVolume(this, next, showNativeSystemUI = true)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
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
                val prog = (mid + offset).coerceIn(0, range)
                eqSliders[i].progress = prog
                val newLevel = (prog + minEQ).toShort()
                runCatching { eq.setBandLevel(i.toShort(), newLevel) }
            }

            findViewById<SeekBar>(R.id.seekBass).progress = bass
            runCatching { AudioEngine.bassBoost?.setStrength(bass.toShort()) }

            findViewById<SeekBar>(R.id.seekVirtualizer).progress = surround
            runCatching { AudioEngine.virtualizer?.setStrength(surround.toShort()) }

            findViewById<SeekBar>(R.id.seekReverb).progress = reverb
            runCatching { AudioEngine.presetReverb?.preset = reverb.toShort() }
        }
    }
}

// =========================================================================
// 3. שירות הגברת עוצמת הקול דרך מחוון המערכת המקורי בלבד (Native SystemUI)
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
    private val hideRunnable = Runnable {
        removeBadge()
    }

    private val volumeKeyHandler = Handler(Looper.getMainLooper())
    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var volUpDownTimestamp = 0L
    private var volDownDownTimestamp = 0L
    private var activeHoldDirection = 0

    private val holdRepeatRunnable = object : Runnable {
        override fun run() {
            if (activeHoldDirection == 0) return
            stepVolumeInDirection(activeHoldDirection, stepAmount = 5)
            volumeKeyHandler.postDelayed(this, 85L)
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

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return handleVolumeKeyEventDirect(event)
    }

    fun handleVolumeKeyEventDirect(event: KeyEvent): Boolean {
        if (!AudioEngine.isBoosterActive) return false

        val keyCode = event.keyCode
        val isVolumeKey = (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                           keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                           keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)

        if (!isVolumeKey) {
            if (keyCode == KeyEvent.KEYCODE_POWER && (isVolUpPressed || isVolDownPressed)) {
                cancelVolumeHold()
            }
            return false
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val next = if (AudioEngine.currentTotalVolume > 0) 0 else 60
                AudioEngine.applyVolume(this, next, showNativeSystemUI = true)
                showNativeBadgeIfNeeded()
            }
            return true
        }

        val now = SystemClock.elapsedRealtime()

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    isVolUpPressed = true
                    volUpDownTimestamp = now
                } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    isVolDownPressed = true
                    volDownDownTimestamp = now
                }

                if (isVolUpPressed && isVolDownPressed && kotlin.math.abs(volUpDownTimestamp - volDownDownTimestamp) < 250L) {
                    cancelVolumeHold()
                    return false
                }

                if (event.repeatCount == 0) {
                    val dir = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                    activeHoldDirection = dir
                    stepVolumeInDirection(dir, stepAmount = 6)
                    volumeKeyHandler.removeCallbacks(holdRepeatRunnable)
                    volumeKeyHandler.postDelayed(holdRepeatRunnable, 280L)
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) isVolUpPressed = false
                if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) isVolDownPressed = false

                if (!isVolUpPressed && !isVolDownPressed) {
                    cancelVolumeHold()
                }
                return true
            }
        }
        return true
    }

    private fun cancelVolumeHold() {
        isVolUpPressed = false
        isVolDownPressed = false
        activeHoldDirection = 0
        volumeKeyHandler.removeCallbacks(holdRepeatRunnable)
    }

    private fun stepVolumeInDirection(direction: Int, stepAmount: Int) {
        AudioEngine.refreshBluetoothAndStreamState(this)
        val maxAllowed = AudioEngine.getEffectiveMaxVolume()

        val nextVol = if (direction > 0) {
            (AudioEngine.currentTotalVolume + stepAmount).coerceAtMost(maxAllowed)
        } else {
            (AudioEngine.currentTotalVolume - stepAmount).coerceAtLeast(0)
        }

        AudioEngine.applyVolume(this, nextVol, showNativeSystemUI = true)
        showNativeBadgeIfNeeded()
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).roundToInt()
    }

    private fun showNativeBadgeIfNeeded() {
        hideHandler.removeCallbacks(hideRunnable)
        if (AudioEngine.currentTotalVolume <= 100) {
            removeBadge()
            return
        }
        val wm = windowManager ?: return

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

        val padH = dp(16f)
        val padV = dp(9f)
        val btMark = if (AudioEngine.isBluetoothConnected) " ᛒ" else ""
        badgeView?.apply {
            setPadding(padH, padV, padH, padV)
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF2F6"))
                cornerRadius = dp(24f).toFloat()
                setStroke(dp(2f), Color.parseColor("#3B607A"))
            }
            setTextColor(Color.parseColor("#1B2C38"))
            text = "${AudioEngine.currentTotalVolume}% HD$btMark"
        }
        hideHandler.postDelayed(hideRunnable, 2800)
    }

    private fun removeBadge() {
        badgeView?.let {
            runCatching { windowManager?.removeView(it) }
            badgeView = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.contains("systemui", ignoreCase = true)) {
            val cls = event?.className?.toString() ?: ""
            if (cls.contains("Screenshot", ignoreCase = true) || cls.contains("GlobalActions", ignoreCase = true)) {
                cancelVolumeHold()
                removeBadge()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        volumeKeyHandler.removeCallbacksAndMessages(null)
        hideHandler.removeCallbacksAndMessages(null)
        removeBadge()
        instance = null
    }
}
 
