package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

// =========================================================================
// 1. מנוע סאונד + מנוע AI לשיפור שירים + חסימת לופ מחוון המערכת + זיהוי בלוטות'
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
    var sliderOnlyNoBoostMode: Boolean = false
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

    var sliderStyleIndex: Int = 1

    // חותמות זמן למניעת קפיצת מחוון המערכת ולופים בזמן שינוי עוצמה או החלפת מחוון
    @Volatile var isInternalVolumeChange: Boolean = false
    @Volatile var suppressSystemUILoopUntil: Long = 0L
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
        "10. מטריצת נוריות אולפן (LED Matrix)",
        "11. קפסולה לבנה דקה + כפתור Play (Slim Red Accent Pill)",
        "12. אולפן זהב כהה אנכי (Golden Thin Track & Knob)",
        "13. בר עליון צמוד קצה עם תו מוזיקה (Top Status Bar Strip)",
        "14. קפסולת Windows / Emoji מדורגת (Segmented Cap Pill)",
        "15. סייבר-טק HUD טלמטריה (Cyber-Tech Telemetry HUD)",
        "16. זכוכית חלבית + פס ניאון וכפתורים (Frosted Neon Tube Card)",
        "17. זכוכית חלבית + קפסולת קשת בצבעים משתנים (Animated Rainbow Pill)"
    )

    fun getEffectiveMaxVolume(): Int {
        if (activeStreamType != 0) return 100
        return when {
            sliderOnlyNoBoostMode || !isBoosterActive -> 100
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

    fun getActiveStreamShortTitle(): String {
        return when (activeStreamType) {
            1 -> "RING VOL"
            2 -> "NOTIFY"
            3 -> "ALARM"
            4 -> if (isBluetoothConnected) "BT CALL" else "CALL VOL"
            else -> if (isBluetoothConnected) "BT AUDIO" else "VOLUME"
        }
    }

    fun refreshBluetoothAndStreamState(context: Context) {
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

        val now = SystemClock.elapsedRealtime()
        if (now > manualStreamOverrideUntil) {
            val autoStream = when (am.mode) {
                AudioManager.MODE_RINGTONE -> 1
                AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> 4
                else -> 0
            }
            if (autoStream != activeStreamType) {
                activeStreamType = autoStream
                syncCurrentVolumeFromActiveStream(am)
            }
        }
    }

    fun cycleNextStream(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (activeStreamType == 0) {
            savedMediaBoostVolume = currentTotalVolume
        }
        activeStreamType = (activeStreamType + 1) % 5
        manualStreamOverrideUntil = SystemClock.elapsedRealtime() + 8000L
        syncCurrentVolumeFromActiveStream(am)
    }

    fun syncCurrentVolumeFromActiveStream(am: AudioManager) {
        if (activeStreamType == 0) {
            val maxSys = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curSys = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val sysPct = ((curSys.toFloat() / maxSys) * 100f).roundToInt()
            currentTotalVolume = if (savedMediaBoostVolume > 100 && curSys >= maxSys && !sliderOnlyNoBoostMode) {
                savedMediaBoostVolume.coerceAtMost(getEffectiveMaxVolume())
            } else {
                sysPct
            }
        } else {
            val streamConst = getAndroidStreamConstant(activeStreamType)
            val maxSys = am.getStreamMaxVolume(streamConst).coerceAtLeast(1)
            val curSys = am.getStreamVolume(streamConst)
            currentTotalVolume = ((curSys.toFloat() / maxSys) * 100f).roundToInt().coerceIn(0, 100)
        }
    }

    fun init(context: Context? = null) {
        context?.let {
            val prefs = it.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            sliderOnlyNoBoostMode = prefs.getBoolean("slider_only_mode", sliderOnlyNoBoostMode)
            allow300PercentBoost = prefs.getBoolean("allow_300_boost", allow300PercentBoost)

            aiSongEngineEnabled = prefs.getBoolean("ai_song_en", aiSongEngineEnabled)
            aiPresetMode = prefs.getInt("ai_preset_mode", aiPresetMode)
            aiNeuralIntensity = prefs.getInt("ai_intensity", aiNeuralIntensity)

            songRemasterEnabled = prefs.getBoolean("song_remaster_en", songRemasterEnabled)
            songUpscaleLevel = prefs.getInt("song_upscale", songUpscaleLevel)
            songSeparationLevel = prefs.getInt("song_sep", songSeparationLevel)
            songPunchLevel = prefs.getInt("song_punch", songPunchLevel)

            currentTotalVolume = prefs.getInt("saved_vol", currentTotalVolume).coerceIn(0, getEffectiveMaxVolume())
            savedMediaBoostVolume = currentTotalVolume
            sliderStyleIndex = prefs.getInt("slider_style", sliderStyleIndex).coerceIn(0, styleNames.lastIndex)
            SliderThemeManager.loadAllStyleConfigs(it)
            refreshBluetoothAndStreamState(it)
            registerAssistantSafeRecovery(it.applicationContext)
        }
        ensureAudioEffectsCreated()
        updateSoundQualityDSP()
    }

    fun saveEngineModes(context: Context) {
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE).edit()
            .putBoolean("slider_only_mode", sliderOnlyNoBoostMode)
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
        val effectsActive = isBoosterActive && !sliderOnlyNoBoostMode
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
                    refreshBluetoothAndStreamState(appContext)
                    if (!isBoosterActive || sliderOnlyNoBoostMode) {
                        runCatching { loudnessEnhancer?.setTargetGain(0) }
                        return
                    }
                    val mode = am.mode
                    if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                        runCatching { loudnessEnhancer?.setTargetGain(0) }
                    } else {
                        reapplyCurrentGainSafely()
                        updateSoundQualityDSP()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun reapplyCurrentGainSafely() {
        if (!isBoosterActive || sliderOnlyNoBoostMode) {
            runCatching { loudnessEnhancer?.setTargetGain(0) }
            return
        }
        val mediaVol = if (activeStreamType == 0) currentTotalVolume else savedMediaBoostVolume
        val extra = (mediaVol - 100).coerceAtLeast(0)
        val aiSmartWarmthGain = if (aiSongEngineEnabled) ((aiNeuralIntensity / 100f) * 180).roundToInt() else 0
        val factor = if (antiClippingEnabled) 17 else 24
        val cleanGain = (extra * factor) + aiSmartWarmthGain

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
        suppressSystemUILoopUntil = SystemClock.elapsedRealtime() + 800L
        context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            .edit().putInt("slider_style", sliderStyleIndex).apply()
    }

    fun updateSoundQualityDSP() {
        if (!isBoosterActive || sliderOnlyNoBoostMode) return
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
        val effectsShouldRun = enabled && !sliderOnlyNoBoostMode
        runCatching {
            if (!effectsShouldRun) loudnessEnhancer?.setTargetGain(0)
            loudnessEnhancer?.enabled = effectsShouldRun
        }
        runCatching { equalizer?.enabled = effectsShouldRun }
        runCatching { bassBoost?.enabled = effectsShouldRun }
        runCatching { virtualizer?.enabled = effectsShouldRun }
        runCatching { presetReverb?.enabled = effectsShouldRun }
    }

    // חוסם הרמטית כל קפיצה של מחוון המערכת כשנבחר סגנון מותאם אישית (1..17) ומונע לופ!
    fun applyVolume(context: Context, totalPercent: Int, showNativeSystemUI: Boolean = false) {
        ensureAudioEffectsCreated()
        val maxAllowed = getEffectiveMaxVolume()
        currentTotalVolume = totalPercent.coerceIn(0, maxAllowed)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

        // מחוון המערכת המקורי מורשה לקפוץ *אך ורק* אם המשתמש בחר בסגנון 0 (מחוון המערכת המקורי)
        val now = SystemClock.elapsedRealtime()
        val isCustomOverlaySelected = (sliderStyleIndex != 0 && VolumeOverlayService.instance != null)
        val allowUI = showNativeSystemUI && !isCustomOverlaySelected && (now > bootCooldownUntil) && (now > suppressSystemUILoopUntil)
        val flags = if (allowUI) AudioManager.FLAG_SHOW_UI else 0

        isInternalVolumeChange = true
        suppressSystemUILoopUntil = now + 350L
        try {
            if (activeStreamType != 0) {
                val targetStream = getAndroidStreamConstant(activeStreamType)
                val maxStreamVol = audioManager.getStreamMaxVolume(targetStream).coerceAtLeast(1)
                val sysTarget = ((currentTotalVolume.coerceIn(0, 100) / 100f) * maxStreamVol).roundToInt()
                val curStreamVol = audioManager.getStreamVolume(targetStream)
                if (curStreamVol != sysTarget || allowUI) {
                    runCatching { audioManager.setStreamVolume(targetStream, sysTarget, flags) }
                }
                return
            }

            savedMediaBoostVolume = currentTotalVolume
            context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
                .edit().putInt("saved_vol", currentTotalVolume).apply()

            val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (currentTotalVolume <= 100 || sliderOnlyNoBoostMode || !isBoosterActive) {
                val safePct = currentTotalVolume.coerceIn(0, 100)
                val sysTarget = ((safePct / 100f) * maxSysVol).roundToInt()
                if (curSys != sysTarget || allowUI) {
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysTarget, flags) }
                }
                if (sliderOnlyNoBoostMode || !isBoosterActive) {
                    runCatching { loudnessEnhancer?.setTargetGain(0) }
                } else {
                    reapplyCurrentGainSafely()
                    updateSoundQualityDSP()
                }
            } else {
                if (curSys < maxSysVol || allowUI) {
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSysVol, flags) }
                }
                reapplyCurrentGainSafely()
                updateSoundQualityDSP()
            }
        } finally {
            Handler(Looper.getMainLooper()).postDelayed({
                isInternalVolumeChange = false
            }, 120)
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
        if (AudioEngine.currentTotalVolume <= 100 && AudioEngine.activeStreamType == 0) {
            AudioEngine.currentTotalVolume = ((curSys.toFloat() / maxSystemVolume) * 100).roundToInt()
        }

        setupCategoryTabs()
        setupModernUI()
        setupSliderDesignCategory()
    }

    override fun onResume() {
        super.onResume()
        AudioEngine.refreshBluetoothAndStreamState(this)
        updateServiceButtonState()
        updateEditButtonState(VolumeOverlayService.instance?.isEditModeActive() == true)
        syncVolumeRangeUI()
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
            val maxTxt = AudioEngine.getEffectiveMaxVolume()
            btnEnableSystemSlider.text = "שירות מחוון הווליום ($maxTxt%) פעיל ומחובר"
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

    private fun syncVolumeRangeUI() {
        val maxAllowed = AudioEngine.getEffectiveMaxVolume()
        seekTotalVolume.max = maxAllowed
        progressTotalVolume.max = maxAllowed

        val streamLabel = AudioEngine.getActiveStreamHebrewLabel()
        tvVolumeCardTitle.text = when {
            AudioEngine.activeStreamType != 0 -> "ערוץ פעיל: $streamLabel (0% - 100%)"
            AudioEngine.sliderOnlyNoBoostMode -> "עוצמת שמע (0% - 100% | מצב מחוון בלבד)"
            AudioEngine.allow300PercentBoost -> "עוצמת $streamLabel (0% - 300%)"
            else -> "עוצמת $streamLabel (0% - 200%)"
        }

        btnVol300.visibility = if (AudioEngine.allow300PercentBoost && !AudioEngine.sliderOnlyNoBoostMode && AudioEngine.activeStreamType == 0) {
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
        val switchSliderOnly = findViewById<SwitchCompat>(R.id.switchSliderOnlyMode)

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
            VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = false)
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
                    tvStatus.text = "מנוע סאונד ומחוון כבויים ●$btStatus"
                    tvStatus.setTextColor(Color.parseColor("#FF5252"))
                    cardHeader.strokeColor = Color.parseColor("#263042")
                }
                AudioEngine.sliderOnlyNoBoostMode -> {
                    tvStatus.text = "מצב עיצוב מחוון בלבד פעיל ● ללא הגברת סאונד$btStatus"
                    tvStatus.setTextColor(Color.parseColor("#00E5FF"))
                    cardHeader.strokeColor = Color.parseColor("#00E5FF")
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

        switchSliderOnly.isChecked = AudioEngine.sliderOnlyNoBoostMode
        switchSliderOnly.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.sliderOnlyNoBoostMode = isChecked
            AudioEngine.setEnabled(AudioEngine.isBoosterActive)
            AudioEngine.saveEngineModes(this)
            syncVolumeRangeUI()
            refreshHeaderStatusText()
        }

        seekTotalVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTotalVolume.text = "$progress%"
                progressTotalVolume.progress = progress

                if (fromUser) {
                    AudioEngine.applyVolume(this@MainActivity, progress, showNativeSystemUI = false)
                }

                val curStreamName = AudioEngine.getActiveStreamHebrewLabel()
                if (AudioEngine.activeStreamType != 0) {
                    tvSubBoostInfo.text = "ערוץ: $curStreamName ($progress%) | לחץ על סמל המחוון להחלפת ערוץ"
                } else if (AudioEngine.sliderOnlyNoBoostMode) {
                    tvSubBoostInfo.text = "מערכת: $progress% | מצב עיצוב מחוון בלבד (ללא הגברה)"
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
            if (AudioEngine.sliderOnlyNoBoostMode) {
                Toast.makeText(this, "כבה את 'מצב עיצוב מחוון בלבד' כדי להגביר מעל 100%", Toast.LENGTH_SHORT).show()
            } else {
                seekTotalVolume.progress = 150
                AudioEngine.applyVolume(this, 150, false)
            }
        }
        findViewById<MaterialButton>(R.id.btnVol200).setOnClickListener {
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            if (AudioEngine.sliderOnlyNoBoostMode) {
                Toast.makeText(this, "כבה את 'מצב עיצוב מחוון בלבד' כדי להגביר מעל 100%", Toast.LENGTH_SHORT).show()
            } else {
                seekTotalVolume.progress = 200
                AudioEngine.applyVolume(this, 200, false)
            }
        }
        btnVol300.setOnClickListener {
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            if (!AudioEngine.sliderOnlyNoBoostMode && AudioEngine.allow300PercentBoost) {
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

        syncVolumeRangeUI()
        refreshHeaderStatusText()
    }

    // =========================================================================
    // קטגוריה 5: התאמה דינמית ל-18 המחוונים + מתג סגירה בנגיעה מחוץ למחוון
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

        val switchDismissOutside = findViewById<SwitchCompat>(R.id.switchDismissOutside)

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

            switchDismissOutside.isChecked = cfg.dismissOnOutsideTouch

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
                    // החלפת מחוון חלקה ללא הפעלת מחוון המערכת באמצע (מונע תקיעות ולופים!)
                    AudioEngine.sliderStyleIndex = i
                    AudioEngine.saveSelectedStyleIndex(this@MainActivity)
                    syncUIFromCurrentStyleConfig()
                    VolumeOverlayService.instance?.triggerPreviewOverlay(playEnterAnim = true)
                }
            }
            styleButtons.add(btn)
            stylesContainer.addView(btn)
        }

        btnResetStyle.setOnClickListener {
            SliderThemeManager.resetStyleToDefault(this)
            syncUIFromCurrentStyleConfig()
            VolumeOverlayService.instance?.triggerPreviewOverlay(playEnterAnim = true)
            Toast.makeText(this, "הגדרות המחוון הנוכחי אופסו לברירת המחדל", Toast.LENGTH_SHORT).show()
        }

        btnTestOverlay.setOnClickListener {
            if (VolumeOverlayService.instance != null) {
                VolumeOverlayService.instance?.triggerPreviewOverlay(playEnterAnim = true)
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

        switchDismissOutside.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingControlsProgrammatically) return@setOnCheckedChangeListener
            SliderThemeManager.getCurrentConfig().dismissOnOutsideTouch = isChecked
            SliderThemeManager.saveStyleConfig(this)
            VolumeOverlayService.instance?.refreshOverlayLive(updateLayout = true)
        }

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
            AudioEngine.refreshBluetoothAndStreamState(this)
            val maxAllowed = AudioEngine.getEffectiveMaxVolume()
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    seekTotalVolume.progress = (seekTotalVolume.progress + 8).coerceAtMost(maxAllowed)
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
// 3. שירות המחוון הצף: טשטוש מסך בסגנון זכוכית כהה חלבית + סגירה בנגיעה בחוץ + אנימציות
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
    private var lastAnimatedStyleIndex: Int = -1

    private var isInteractiveEditActive = false
    private var onLivePositionChangedCallback: ((Int, Int) -> Unit)? = null
    private var onEditClosedCallback: (() -> Unit)? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (!isInteractiveEditActive) {
            dismissOverlaysAnimated()
        }
    }

    private val volumeKeyHandler = Handler(Looper.getMainLooper())
    private var isVolUpPressed = false
    private var isVolDownPressed = false
    private var activeHoldDirection = 0
    private var pendingSingleStepApplied = false
    private var comboShortcutDetected = false

    private val delayedInitialStepRunnable = Runnable {
        if (comboShortcutDetected || activeHoldDirection == 0) return@Runnable
        pendingSingleStepApplied = true
        stepVolumeInDirection(activeHoldDirection, stepAmount = 6)
        volumeKeyHandler.postDelayed(holdRepeatRunnable, 220L)
    }

    private val holdRepeatRunnable = object : Runnable {
        override fun run() {
            if (comboShortcutDetected || activeHoldDirection == 0) return
            stepVolumeInDirection(activeHoldDirection, stepAmount = 5)
            volumeKeyHandler.postDelayed(this, 90L)
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
        if (!AudioEngine.isBoosterActive && !AudioEngine.sliderOnlyNoBoostMode) return super.onKeyEvent(event)
        // בולע מיד את אירועי כפתורי הווליום כדי שמחוון המערכת של הטלפון לעולם לא יקפוץ לפנינו!
        val keyCode = event.keyCode

        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isVolUpPressed || isVolDownPressed) {
                cancelVolumeHoldForSystemShortcut()
            }
            return super.onKeyEvent(event)
        }

        if (AudioEngine.isInternalVolumeChange) return true

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

                if (isVolUpPressed && isVolDownPressed) {
                    cancelVolumeHoldForSystemShortcut()
                    return false
                }

                if (event.repeatCount == 0 && activeHoldDirection == 0) {
                    comboShortcutDetected = false
                    pendingSingleStepApplied = false
                    activeHoldDirection = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                    volumeKeyHandler.removeCallbacks(delayedInitialStepRunnable)
                    volumeKeyHandler.removeCallbacks(holdRepeatRunnable)
                    volumeKeyHandler.postDelayed(delayedInitialStepRunnable, 120L)
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

                if (!comboShortcutDetected && !pendingSingleStepApplied && dirBeforeRelease != 0) {
                    stepVolumeInDirection(dirBeforeRelease, stepAmount = 7)
                }
                comboShortcutDetected = false
                pendingSingleStepApplied = false
                return true
            }
        }
        return true
    }

    private fun cancelVolumeHoldForSystemShortcut() {
        comboShortcutDetected = true
        activeHoldDirection = 0
        volumeKeyHandler.removeCallbacks(delayedInitialStepRunnable)
        volumeKeyHandler.removeCallbacks(holdRepeatRunnable)
    }

    private fun stepVolumeInDirection(direction: Int, stepAmount: Int) {
        val am = audioManager ?: return
        AudioEngine.refreshBluetoothAndStreamState(this)

        val useNativeSystemUI = (AudioEngine.sliderStyleIndex == 0)
        val maxAllowed = AudioEngine.getEffectiveMaxVolume()

        if (AudioEngine.activeStreamType != 0) {
            AudioEngine.currentTotalVolume = if (direction > 0) {
                (AudioEngine.currentTotalVolume + stepAmount).coerceAtMost(100)
            } else {
                (AudioEngine.currentTotalVolume - stepAmount).coerceAtLeast(0)
            }
        } else {
            val curSys = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (direction > 0) {
                if (curSys < maxSysVol && AudioEngine.currentTotalVolume < 100) {
                    val nextVol = (AudioEngine.currentTotalVolume + stepAmount).coerceAtMost(100)
                    AudioEngine.currentTotalVolume = nextVol
                } else {
                    val baseVol = if (AudioEngine.currentTotalVolume < 100) 100 else AudioEngine.currentTotalVolume
                    AudioEngine.currentTotalVolume = (baseVol + stepAmount).coerceAtMost(maxAllowed)
                }
            } else {
                if (AudioEngine.currentTotalVolume > 100) {
                    AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume - stepAmount).coerceAtLeast(100)
                } else {
                    AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume - stepAmount).coerceAtLeast(0)
                }
            }
        }

        AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = useNativeSystemUI)
        if (useNativeSystemUI) {
            removeCustomOverlayImmediate()
            showNativeBadgeIfNeeded()
        } else {
            removeBadge()
            showCustomThemedOverlay(playEnterAnim = false)
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
            showCustomThemedOverlay(playEnterAnim = true)
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

    fun triggerPreviewOverlay(playEnterAnim: Boolean = true) {
        hideHandler.removeCallbacks(hideRunnable)
        AudioEngine.suppressSystemUILoopUntil = SystemClock.elapsedRealtime() + 800L
        AudioEngine.refreshBluetoothAndStreamState(this)
        if (AudioEngine.sliderStyleIndex == 0) {
            removeCustomOverlayImmediate()
            AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
            showNativeBadgeIfNeeded()
        } else {
            removeBadge()
            showCustomThemedOverlay(playEnterAnim = playEnterAnim)
        }
    }

    fun refreshOverlayLive(updateLayout: Boolean = false) {
        if (customOverlayCanvasView == null) {
            if (updateLayout) triggerPreviewOverlay(playEnterAnim = true)
            return
        }
        if (updateLayout) {
            val wm = windowManager ?: return
            updateFrostedGlassBackdrop(animateIn = false)
            val params = buildSliderWindowLayoutParams()
            overlayParams = params
            runCatching { wm.updateViewLayout(customOverlayCanvasView, params) }
        } else {
            updateFrostedGlassBackdrop(animateIn = false)
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

    // =====================================================================
    // שכבת טשטוש זכוכית חלבית כהה ברקע (כמו בתמונה של האולר השווייצרי Pro fx-991)
    // =====================================================================
    private fun updateFrostedGlassBackdrop(animateIn: Boolean) {
        val wm = windowManager ?: return
        val cfg = SliderThemeManager.getCurrentConfig()
        val needsBackdrop = (cfg.dimBackgroundEnabled && cfg.dimStrengthPercent > 0) ||
                            (cfg.blurBackgroundEnabled && cfg.blurStrengthPercent > 0)

        if (!needsBackdrop) {
            removeBackdropImmediate()
            return
        }

        // חישוב גוון הזכוכית הכהה העמוקה ברקע (כמו בתמונה) לפי עוצמת הטשטוש וההשחרה
        val blurTintAlpha = if (cfg.blurBackgroundEnabled) {
            ((cfg.blurStrengthPercent / 100f) * 145).roundToInt()
        } else 0
        val dimTintAlpha = if (cfg.dimBackgroundEnabled) {
            ((cfg.dimStrengthPercent / 100f) * 195).roundToInt()
        } else 0
        val combinedAlpha = maxOf(blurTintAlpha, dimTintAlpha).coerceIn(0, 225)
        val frostedDarkColor = Color.argb(combinedAlpha, 14, 18, 28)

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
                    // רדיוס טשטוש עמוק וחלבי (עד 110px) שנראה בדיוק כמו בתמונה!
                    ((cfg.blurStrengthPercent / 100f) * 110).roundToInt().coerceAtLeast(1)
                } else {
                    0
                }
            }
        }

        if (backdropTouchThroughView == null) {
            backdropTouchThroughView = View(this).apply {
                setBackgroundColor(frostedDarkColor)
                if (animateIn) {
                    alpha = 0f
                    animate().alpha(1f).setDuration(240L).setListener(null).start()
                } else {
                    alpha = 1f
                }
            }
            runCatching { wm.addView(backdropTouchThroughView, backdropParams) }
        } else {
            backdropTouchThroughView?.animate()?.cancel()
            backdropTouchThroughView?.alpha = 1f
            backdropTouchThroughView?.setBackgroundColor(frostedDarkColor)
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

        // הוספת FLAG_WATCH_OUTSIDE_TOUCH כדי שנגיעה בכל מקום במסך מחוץ למחוון תזהה מיד ותעלים אותו באנימציה!
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
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

    private fun showCustomThemedOverlay(playEnterAnim: Boolean = false) {
        hideHandler.removeCallbacks(hideRunnable)
        val wm = windowManager ?: return

        val isNewWindow = (customOverlayCanvasView == null)
        val styleChanged = (lastAnimatedStyleIndex != AudioEngine.sliderStyleIndex)
        lastAnimatedStyleIndex = AudioEngine.sliderStyleIndex

        updateFrostedGlassBackdrop(animateIn = isNewWindow || playEnterAnim)

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
                },
                onOutsideTouchDismiss = {
                    hideHandler.removeCallbacks(hideRunnable)
                    dismissOverlaysAnimated()
                }
            ).apply {
                isEditPositionMode = isInteractiveEditActive
            }

            overlayParams = buildSliderWindowLayoutParams()
            runCatching { wm.addView(customOverlayCanvasView, overlayParams) }
            customOverlayCanvasView?.playCustomEnterAnimation(AudioEngine.sliderStyleIndex)
        } else {
            customOverlayCanvasView?.isAnimatingExit = false
            refreshOverlayLive(updateLayout = true)
            if (playEnterAnim || styleChanged) {
                customOverlayCanvasView?.playCustomEnterAnimation(AudioEngine.sliderStyleIndex)
            } else {
                customOverlayCanvasView?.animate()?.cancel()
                customOverlayCanvasView?.alpha = 1f
                customOverlayCanvasView?.translationX = 0f
                customOverlayCanvasView?.translationY = 0f
                customOverlayCanvasView?.scaleX = 1f
                customOverlayCanvasView?.scaleY = 1f
            }
        }

        if (!isInteractiveEditActive) {
            hideHandler.postDelayed(hideRunnable, 3400)
        }
    }

    // =====================================================================
    // מעלים באנימציה חלקה ומותאמת גם את המחוון וגם את טשטוש הרקע יחד!
    // =====================================================================
    fun dismissOverlaysAnimated() {
        removeBadge()
        val sliderView = customOverlayCanvasView
        if (sliderView == null) {
            removeBackdropImmediate()
            return
        }

        backdropTouchThroughView?.let { bd ->
            bd.animate()
                .alpha(0f)
                .setDuration(200L)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        removeBackdropImmediate()
                    }
                })
                .start()
        }

        sliderView.playCustomExitAnimation(AudioEngine.sliderStyleIndex) {
            removeCustomOverlayImmediate()
        }
    }

    private fun showNativeBadgeIfNeeded() {
        hideHandler.removeCallbacks(hideRunnable)
        if (AudioEngine.currentTotalVolume <= 100 || AudioEngine.sliderOnlyNoBoostMode) {
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
        val btMark = if (AudioEngine.isBluetoothConnected) " ᛒ" else ""
        badgeView?.apply {
            setPadding(padH, padV, padH, padV)
            textSize = cfg.textSizeSp.toFloat()
            background = GradientDrawable().apply {
                setColor(cfg.colors[0])
                cornerRadius = dp(cfg.cornerRadiusDp.toFloat()).toFloat()
                setStroke(dp(cfg.borderWidthDp.toFloat()), cfg.colors[1])
            }
            setTextColor(cfg.colors[2])
            text = "${AudioEngine.currentTotalVolume}% HD$btMark"
        }
        hideHandler.postDelayed(hideRunnable, 2800)
    }

    private fun removeBackdropImmediate() {
        backdropTouchThroughView?.let {
            it.animate().cancel()
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

    private fun removeCustomOverlayImmediate() {
        customOverlayCanvasView?.let {
            it.animate().cancel()
            runCatching { windowManager?.removeView(it) }
            customOverlayCanvasView = null
        }
        removeBackdropImmediate()
    }

    private fun removeAllOverlaysImmediate() {
        removeBadge()
        removeCustomOverlayImmediate()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // מונע לחלוטין לופ מול SystemUI בזמן שהמחוון המותאם אישית פועל!
        val now = SystemClock.elapsedRealtime()
        if (now < AudioEngine.suppressSystemUILoopUntil || AudioEngine.isInternalVolumeChange) return

        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.contains("systemui", ignoreCase = true) && (isVolUpPressed || isVolDownPressed)) {
            val cls = event?.className?.toString() ?: ""
            if (cls.contains("Screenshot", ignoreCase = true) || cls.contains("GlobalActions", ignoreCase = true)) {
                cancelVolumeHoldForSystemShortcut()
                removeAllOverlaysImmediate()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        volumeKeyHandler.removeCallbacksAndMessages(null)
        hideHandler.removeCallbacksAndMessages(null)
        removeAllOverlaysImmediate()
        instance = null
    }
}
