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
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
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
//    + Audio Output Routing Profiles + Multi-Band Dynamic Limiter
//    + 10-Band Parametric Equalizer + Hearing Safe Guard + Sleep Timer Fade-Out
// =========================================================================
object AudioEngine {
    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var presetReverb: PresetReverb? = null
    var dynamicsProcessing: DynamicsProcessing? = null

    var currentTotalVolume: Int = 100
    var savedMediaBoostVolume: Int = 100
    var isBoosterActive: Boolean = true
    var allow300PercentBoost: Boolean = false

    // מתג הגנת שמיעה חכמה באוזניות (Hearing Safe Guard)
    var hearingSafeGuardEnabled: Boolean = false
    const val HEARING_SAFE_MAX_VOLUME = 120

    // פרופילי ניתוב שמע (Audio Output Routing Profiles):
    // 0 = Auto Detect, 1 = Phone Speaker, 2 = Wired Headphones, 3 = Bluetooth Audio, 4 = Car Audio
    var selectedRoutingProfileMode: Int = 0
    var detectedHardwareRoute: Int = 1 // 1=Speaker, 2=Wired, 3=Bluetooth
    var isBluetoothConnected: Boolean = false
    var isWiredHeadsetConnected: Boolean = false

    // 0 = מדיה, 1 = עוצמת צלצול, 2 = התראות, 3 = שעון מעורר, 4 = שיחת טלפון
    var activeStreamType: Int = 0
    private var manualStreamOverrideUntil: Long = 0L

    var antiClippingEnabled: Boolean = true
    var hiResOptimizerEnabled: Boolean = true
    var clarityLevel: Int = 50
    var warmthLevel: Int = 40

    // Multi-Band Dynamic Limiter (שליטה נפרדת ב-3 תחומי תדרים: באס, ווקאל, גבוהים)
    var multiBandLimiterEnabled: Boolean = true
    var mbLowControl: Int = 75
    var mbMidControl: Int = 65
    var mbHighControl: Int = 70

    // אקולייזר פרמטרי מורחב 10 ערוצים (31Hz..16kHz) + Q-Factor
    val parametricFreqsHz = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val parametricBandGainsDb = FloatArray(10) { 0f } // -15dB עד +15dB
    var parametricQFactorProgress: Int = 12 // מייצג Q = 1.2

    // מנוע ה-AI המוכן לשיפור שירים לאיכות גבוהה במיוחד
    var aiSongEngineEnabled: Boolean = true
    var aiPresetMode: Int = 0
    var aiNeuralIntensity: Int = 85

    // כיוון ידני משלים למנוע השירים
    var songRemasterEnabled: Boolean = true
    var songUpscaleLevel: Int = 75
    var songSeparationLevel: Int = 65
    var songPunchLevel: Int = 60

    // טיימר שינה חכם עם Fade-Out הדרגתי
    private var sleepCountDownTimer: CountDownTimer? = null
    var sleepTimerMinutesSelected: Int = 0
    var sleepRemainingSeconds: Long = 0L
    private var fadeOutStartVolume: Int = 100
    var onSleepTimerTickCallback: ((Long, Int) -> Unit)? = null

    @Volatile var isInternalVolumeChange: Boolean = false
    var bootCooldownUntil: Long = 0L
    private var playbackCallbackRegistered = false
    private var isInitializedOnce = false

    fun isHeadphonesActive(): Boolean {
        val effectiveProfile = getEffectiveOutputProfile()
        return isBluetoothConnected || isWiredHeadsetConnected || effectiveProfile == 2 || effectiveProfile == 3
    }

    fun getEffectiveOutputProfile(): Int {
        return if (selectedRoutingProfileMode == 0) detectedHardwareRoute else selectedRoutingProfileMode
    }

    fun getActiveOutputProfileHebrewName(): String {
        val eff = getEffectiveOutputProfile()
        val prefix = if (selectedRoutingProfileMode == 0) "אוטומטי" else "ידני"
        val name = when (eff) {
            2 -> "אוזניות חוטיות (Hi-Fi Headphone Profile)"
            3 -> "אוזניות Bluetooth (BT Dynamic Profile)"
            4 -> "מערכת שמע ברכב (Car Sub & Stage Profile)"
            else -> "רמקול מובנה (Speaker Anti-Distortion Profile)"
        }
        return "$prefix: $name"
    }

    fun getEffectiveMaxVolume(): Int {
        if (activeStreamType != 0) return 100
        if (!isBoosterActive) return 100
        val baseMax = if (allow300PercentBoost) 300 else 200
        return if (hearingSafeGuardEnabled && isHeadphonesActive()) {
            minOf(baseMax, HEARING_SAFE_MAX_VOLUME)
        } else {
            baseMax
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
            else -> when {
                isBluetoothConnected -> "מדיה (Bluetooth)"
                isWiredHeadsetConnected -> "מדיה (אוזניות)"
                else -> "מדיה ומוזיקה"
            }
        }
    }

    fun updateBluetoothStatusOnly(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        var btFound = false
        var wiredFound = false
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
                }
                if (t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    t == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    t == AudioDeviceInfo.TYPE_USB_DEVICE
                ) {
                    wiredFound = true
                }
            }
        }
        isBluetoothConnected = btFound
        isWiredHeadsetConnected = wiredFound
        detectedHardwareRoute = when {
            btFound -> 3
            wiredFound -> 2
            else -> 1
        }
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

    // הפעלת טיימר שינה חכם עם הנמכה הדרגתית (Fade-Out) בדקה האחרונה
    fun startSleepTimer(context: Context, minutes: Int) {
        sleepCountDownTimer?.cancel()
        sleepTimerMinutesSelected = minutes
        if (minutes <= 0) {
            sleepRemainingSeconds = 0L
            onSleepTimerTickCallback?.invoke(0L, currentTotalVolume)
            return
        }

        val appCtx = context.applicationContext
        val totalMillis = minutes * 60 * 1000L
        fadeOutStartVolume = currentTotalVolume.coerceAtLeast(20)

        sleepCountDownTimer = object : CountDownTimer(totalMillis, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                sleepRemainingSeconds = millisUntilFinished / 1000L
                // בדקה האחרונה (60 שניות אחרונות) מבצע Fade-Out חלק ורציף עד 0%
                if (sleepRemainingSeconds in 1..60) {
                    if (sleepRemainingSeconds == 60L) {
                        fadeOutStartVolume = currentTotalVolume.coerceAtLeast(10)
                    }
                    val fadeVol = ((sleepRemainingSeconds / 60f) * fadeOutStartVolume).roundToInt().coerceAtLeast(1)
                    if (fadeVol < currentTotalVolume) {
                        applyVolume(appCtx, fadeVol, showNativeSystemUI = false)
                    }
                }
                onSleepTimerTickCallback?.invoke(sleepRemainingSeconds, currentTotalVolume)
            }

            override fun onFinish() {
                sleepRemainingSeconds = 0L
                sleepTimerMinutesSelected = 0
                applyVolume(appCtx, 0, showNativeSystemUI = false)
                onSleepTimerTickCallback?.invoke(0L, 0)
            }
        }.start()
    }

    fun init(context: Context? = null) {
        context?.let {
            val prefs = it.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            allow300PercentBoost = prefs.getBoolean("allow_300_boost", allow300PercentBoost)
            hearingSafeGuardEnabled = prefs.getBoolean("hearing_safe_guard", hearingSafeGuardEnabled)
            selectedRoutingProfileMode = prefs.getInt("routing_profile_mode", selectedRoutingProfileMode)

            multiBandLimiterEnabled = prefs.getBoolean("mb_limiter_en", multiBandLimiterEnabled)
            mbLowControl = prefs.getInt("mb_low", mbLowControl)
            mbMidControl = prefs.getInt("mb_mid", mbMidControl)
            mbHighControl = prefs.getInt("mb_high", mbHighControl)

            parametricQFactorProgress = prefs.getInt("param_q_prog", parametricQFactorProgress)
            for (b in 0 until 10) {
                parametricBandGainsDb[b] = prefs.getFloat("param_eq_b_$b", 0f)
            }

            aiSongEngineEnabled = prefs.getBoolean("ai_song_en", aiSongEngineEnabled)
            aiPresetMode = prefs.getInt("ai_preset_mode", aiPresetMode)
            aiNeuralIntensity = prefs.getInt("ai_intensity", aiNeuralIntensity)

            songRemasterEnabled = prefs.getBoolean("song_remaster_en", songRemasterEnabled)
            songUpscaleLevel = prefs.getInt("song_upscale", songUpscaleLevel)
            songSeparationLevel = prefs.getInt("song_sep", songSeparationLevel)
            songPunchLevel = prefs.getInt("song_punch", songPunchLevel)

            updateBluetoothStatusOnly(it)

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

            registerAssistantSafeRecovery(it.applicationContext)
        }
        ensureAudioEffectsCreated()
        updateSoundQualityDSP()
    }

    fun saveEngineModes(context: Context) {
        val editor = context.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE).edit()
            .putBoolean("allow_300_boost", allow300PercentBoost)
            .putBoolean("hearing_safe_guard", hearingSafeGuardEnabled)
            .putInt("routing_profile_mode", selectedRoutingProfileMode)
            .putBoolean("mb_limiter_en", multiBandLimiterEnabled)
            .putInt("mb_low", mbLowControl)
            .putInt("mb_mid", mbMidControl)
            .putInt("mb_high", mbHighControl)
            .putInt("param_q_prog", parametricQFactorProgress)
            .putBoolean("ai_song_en", aiSongEngineEnabled)
            .putInt("ai_preset_mode", aiPresetMode)
            .putInt("ai_intensity", aiNeuralIntensity)
            .putBoolean("song_remaster_en", songRemasterEnabled)
            .putInt("song_upscale", songUpscaleLevel)
            .putInt("song_sep", songSeparationLevel)
            .putInt("song_punch", songPunchLevel)

        for (b in 0 until 10) {
            editor.putFloat("param_eq_b_$b", parametricBandGainsDb[b])
        }
        editor.apply()
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
        // יצירת מנוע DynamicsProcessing ל-10 ערוצי EQ פרמטריים + Multi-Band Dynamic Limiter (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing == null) {
            runCatching {
                val cfgBuilder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,
                    true, 10,   // 10-Band Pre-EQ
                    true, 3,    // 3-Band Multi-Band Compressor (Low / Mid / High)
                    true, 10,   // 10-Band Post-EQ
                    true        // Master Limiter
                )
                dynamicsProcessing = DynamicsProcessing(0, 0, cfgBuilder.build()).apply {
                    enabled = effectsActive
                }
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
                        updateSoundQualityDSP()
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
        val maxAllowed = getEffectiveMaxVolume()
        if (currentTotalVolume > maxAllowed) {
            currentTotalVolume = maxAllowed
        }
        val mediaVol = if (activeStreamType == 0) currentTotalVolume else savedMediaBoostVolume.coerceAtMost(maxAllowed)
        if (mediaVol <= 100) {
            runCatching { loudnessEnhancer?.setTargetGain(0) }
            return
        }
        val extra = (mediaVol - 100).coerceAtLeast(0)

        // התאמת דרגת ההגברה לפי פרופיל הניתוב (רמקול מובנה מוגן יותר מעיוות, רכב/אוזניות פתוחים יותר)
        val profileMultiplier = when (getEffectiveOutputProfile()) {
            1 -> 0.88f // Speaker safe headroom
            4 -> 1.08f // Car audio clean line boost
            else -> 1.0f
        }
        val factor = if (antiClippingEnabled) 17 else 24
        val cleanGain = (extra * factor * profileMultiplier).roundToInt()

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
        val eq = equalizer

        val qFactor = (0.4f + (parametricQFactorProgress / 10f)).coerceIn(0.4f, 4.4f)
        val routeProfile = getEffectiveOutputProfile()

        // חישוב תוספות תדרים לפי פרופיל הניתוב (Audio Output Routing Profile)
        var routeSubBassDb = 0f
        var routeLowMidCutDb = 0f
        var routeVocalPresenceDb = 0f
        var routeAirTrebleDb = 0f

        when (routeProfile) {
            1 -> { // רמקול מובנה: חיתוך תדרי צרידות והגנה על הממברנה הקטנה
                routeSubBassDb = -1.5f
                routeLowMidCutDb = -1.2f
                routeVocalPresenceDb = 2.2f
                routeAirTrebleDb = 1.8f
            }
            2 -> { // אוזניות חוטיות: באס עמוק ומדויק + הפרדה גבוהה
                routeSubBassDb = 2.8f
                routeLowMidCutDb = -0.8f
                routeVocalPresenceDb = 1.5f
                routeAirTrebleDb = 2.5f
            }
            3 -> { // אוזניות בלוטות': פיצוי דחיסת A2DP בתדרים הגבוהים והנמוכים
                routeSubBassDb = 2.4f
                routeLowMidCutDb = -1.0f
                routeVocalPresenceDb = 1.8f
                routeAirTrebleDb = 3.0f
            }
            4 -> { // מערכת שמע ברכב: הדגשת סאב-וופר ובמה רחבה
                routeSubBassDb = 4.0f
                routeLowMidCutDb = -1.5f
                routeVocalPresenceDb = 2.0f
                routeAirTrebleDb = 2.2f
            }
        }

        // עדכון Multi-Band Dynamic Limiter + 10-Band Parametric EQ ב-DynamicsProcessing (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val dp = dynamicsProcessing
                if (dp != null) {
                    val cutoffFreqs = floatArrayOf(45f, 90f, 180f, 355f, 710f, 1400f, 2800f, 5600f, 11200f, 20000f)
                    for (b in 0 until 10) {
                        val userGainDb = parametricBandGainsDb[b]
                        val profileOffset = when (b) {
                            0, 1 -> routeSubBassDb
                            2, 3 -> routeLowMidCutDb
                            5, 6, 7 -> routeVocalPresenceDb
                            8, 9 -> routeAirTrebleDb
                            else -> 0f
                        }
                        // חידוד ה-Gain בהתאם ל-Q-Factor הפרמטרי שנבחר
                        val qScaledGain = (userGainDb * (0.85f + (qFactor - 1.0f) * 0.12f)) + profileOffset
                        val eqBand = DynamicsProcessing.EqBand(true, cutoffFreqs[b], qScaledGain.coerceIn(-15f, 15f))
                        dp.setPreEqBandAllChannelsTo(b, eqBand)
                    }

                    // כיוון Multi-Band Dynamic Limiter (3 ערוצי דחיסה: נמוכים, אמצע, גבוהים)
                    val mbEnabled = multiBandLimiterEnabled
                    val lowThresh = -6f - (mbLowControl / 100f) * 18f
                    val midThresh = -5f - (mbMidControl / 100f) * 14f
                    val highThresh = -5f - (mbHighControl / 100f) * 16f

                    val lowRatio = 2.0f + (mbLowControl / 100f) * 6.0f
                    val midRatio = 1.8f + (mbMidControl / 100f) * 4.2f
                    val highRatio = 2.0f + (mbHighControl / 100f) * 5.0f

                    val mbcLow = DynamicsProcessing.MbcBand(
                        mbEnabled, 220f, 5f, 80f, lowRatio, lowThresh, 3f, -45f, 1.5f, 0f, 1.2f
                    )
                    val mbcMid = DynamicsProcessing.MbcBand(
                        mbEnabled, 3200f, 8f, 90f, midRatio, midThresh, 4f, -45f, 1.5f, 0f, 1.5f
                    )
                    val mbcHigh = DynamicsProcessing.MbcBand(
                        mbEnabled, 20000f, 3f, 60f, highRatio, highThresh, 2.5f, -50f, 1.5f, 0f, 1.0f
                    )
                    dp.setMbcBandAllChannelsTo(0, mbcLow)
                    dp.setMbcBandAllChannelsTo(1, mbcMid)
                    dp.setMbcBandAllChannelsTo(2, mbcHigh)

                    val limiter = DynamicsProcessing.Limiter(
                        antiClippingEnabled || multiBandLimiterEnabled,
                        true, 0, 2f, 45f, 10f, -1.2f, 0f
                    )
                    dp.setLimiterAllChannelsTo(limiter)
                }
            }
        }

        // סנכרון מלא גם מול ה-Equalizer החומרתי של המכשיר
        if (eq != null) {
            runCatching {
                val bands = eq.numberOfBands.toInt()
                if (bands < 3) return@runCatching
                val maxEQ = eq.bandLevelRange[1].toInt()
                val minEQ = eq.bandLevelRange[0].toInt()

                var lowBandGain = ((parametricBandGainsDb[0] + parametricBandGainsDb[1]) * 50f) + (routeSubBassDb * 90f)
                var lowMidGain = ((parametricBandGainsDb[2] + parametricBandGainsDb[3]) * 50f) + (routeLowMidCutDb * 90f)
                var centerMidGain = ((parametricBandGainsDb[4] + parametricBandGainsDb[5]) * 50f)
                var presenceGain = ((parametricBandGainsDb[6] + parametricBandGainsDb[7]) * 50f) + (routeVocalPresenceDb * 90f)
                var airHighGain = ((parametricBandGainsDb[8] + parametricBandGainsDb[9]) * 50f) + (routeAirTrebleDb * 90f)

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

                // התאמת ריסון רב-תדרי (Multi-Band Limiter) בעוצמות גבוהות
                if (multiBandLimiterEnabled && currentTotalVolume > 120) {
                    val overRatio = ((currentTotalVolume - 120) / 180f).coerceIn(0f, 1f)
                    lowBandGain *= (1f - (mbLowControl / 100f) * 0.35f * overRatio)
                    centerMidGain *= (1f + (mbMidControl / 100f) * 0.15f * overRatio)
                    airHighGain *= (1f - (mbHighControl / 100f) * 0.25f * overRatio)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { dynamicsProcessing?.enabled = enabled }
        }
    }

    fun applyVolume(context: Context, totalPercent: Int, showNativeSystemUI: Boolean = false) {
        ensureAudioEffectsCreated()
        updateBluetoothStatusOnly(context)
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
                updateSoundQualityDSP()
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
    private lateinit var tvActiveOutputProfileInfo: TextView
    private lateinit var tvHearingGuardSub: TextView
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
        AudioEngine.updateBluetoothStatusOnly(this)
        val maxAllowed = AudioEngine.getEffectiveMaxVolume()
        seekTotalVolume.max = maxAllowed
        progressTotalVolume.max = maxAllowed

        val streamLabel = AudioEngine.getActiveStreamHebrewLabel()
        val guardActiveNow = AudioEngine.hearingSafeGuardEnabled && AudioEngine.isHeadphonesActive()
        tvVolumeCardTitle.text = when {
            AudioEngine.activeStreamType != 0 -> "ערוץ פעיל: $streamLabel (0% - 100%)"
            guardActiveNow -> "עוצמת $streamLabel (מוגן אוזניות: 0% - $maxAllowed%)"
            AudioEngine.allow300PercentBoost -> "עוצמת $streamLabel (0% - 300%)"
            else -> "עוצמת $streamLabel (0% - 200%)"
        }

        if (::tvHearingGuardSub.isInitialized) {
            tvHearingGuardSub.text = if (guardActiveNow) {
                "פעיל כעת ● אוזניות זוהו - העוצמה מוגבלת ל-120% להגנה על השמיעה"
            } else {
                "מגביל עוצמת יתר מסוכנת (עד 120%) בעת חיבור אוזניות חוטיות או בלוטות'"
            }
        }

        if (::tvActiveOutputProfileInfo.isInitialized) {
            tvActiveOutputProfileInfo.text = "פרופיל פעיל ● ${AudioEngine.getActiveOutputProfileHebrewName()}"
        }

        btnVol300.visibility = if (AudioEngine.allow300PercentBoost && !guardActiveNow && AudioEngine.activeStreamType == 0) {
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
        val switchHearingSafeGuard = findViewById<SwitchCompat>(R.id.switchHearingSafeGuard)
        tvHearingGuardSub = findViewById(R.id.tvHearingGuardSub)

        // כפתורי Audio Output Routing Profiles
        tvActiveOutputProfileInfo = findViewById(R.id.tvActiveOutputProfileInfo)
        val btnRouteAuto = findViewById<MaterialButton>(R.id.btnRouteAuto)
        val btnRouteSpeaker = findViewById<MaterialButton>(R.id.btnRouteSpeaker)
        val btnRouteWired = findViewById<MaterialButton>(R.id.btnRouteWired)
        val btnRouteBluetooth = findViewById<MaterialButton>(R.id.btnRouteBluetooth)
        val btnRouteCar = findViewById<MaterialButton>(R.id.btnRouteCar)
        val routeBtns = listOf(btnRouteAuto, btnRouteSpeaker, btnRouteWired, btnRouteBluetooth, btnRouteCar)

        // כפתורי Sleep Timer with Fade-Out
        val tvSleepTimerCountdown = findViewById<TextView>(R.id.tvSleepTimerCountdown)
        val btnSleepOff = findViewById<MaterialButton>(R.id.btnSleepOff)
        val btnSleep15 = findViewById<MaterialButton>(R.id.btnSleep15)
        val btnSleep30 = findViewById<MaterialButton>(R.id.btnSleep30)
        val btnSleep60 = findViewById<MaterialButton>(R.id.btnSleep60)
        val sleepBtns = listOf(btnSleepOff to 0, btnSleep15 to 15, btnSleep30 to 30, btnSleep60 to 60)

        val switchAiEngine = findViewById<SwitchCompat>(R.id.switchAiSongEngine)
        val tvAiSubStatus = findViewById<TextView>(R.id.tvAiEngineSubStatus)
        val btnAiAuto = findViewById<MaterialButton>(R.id.btnAiModeAuto)
        val btnAiBass = findViewById<MaterialButton>(R.id.btnAiModeBass)
        val btnAiVocal = findViewById<MaterialButton>(R.id.btnAiModeVocal)
        val btnAiConcert = findViewById<MaterialButton>(R.id.btnAiModeConcert)
        val aiModeBtns = listOf(btnAiAuto, btnAiBass, btnAiVocal, btnAiConcert)
        val seekAiIntensity = findViewById<SeekBar>(R.id.seekAiIntensity)
        val tvAiIntensityVal = findViewById<TextView>(R.id.tvAiIntensityValue)

        // בקרי Multi-Band Dynamic Limiter
        val switchMultiBandLimiter = findViewById<SwitchCompat>(R.id.switchMultiBandLimiter)
        val seekMbLow = findViewById<SeekBar>(R.id.seekMbLow)
        val tvMbLowVal = findViewById<TextView>(R.id.tvMbLowVal)
        val seekMbMid = findViewById<SeekBar>(R.id.seekMbMid)
        val tvMbMidVal = findViewById<TextView>(R.id.tvMbMidVal)
        val seekMbHigh = findViewById<SeekBar>(R.id.seekMbHigh)
        val tvMbHighVal = findViewById<TextView>(R.id.tvMbHighVal)

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

        // אקולייזר פרמטרי מורחב 10 ערוצים + Q-Factor
        val seekParametricQ = findViewById<SeekBar>(R.id.seekParametricQ)
        val tvParametricQValue = findViewById<TextView>(R.id.tvParametricQValue)
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

        // סנכרון כפתורי Audio Output Routing Profiles
        fun updateRoutingProfileButtonsUI() {
            for (i in routeBtns.indices) {
                val active = (i == AudioEngine.selectedRoutingProfileMode)
                routeBtns[i].strokeColor = ColorStateList.valueOf(
                    if (active) Color.parseColor("#BB86FC") else Color.parseColor("#3A465E")
                )
                routeBtns[i].setTextColor(
                    if (active) Color.parseColor("#BB86FC") else Color.parseColor("#A0AAB8")
                )
            }
            tvActiveOutputProfileInfo.text = "פרופיל פעיל ● ${AudioEngine.getActiveOutputProfileHebrewName()}"
        }

        updateRoutingProfileButtonsUI()
        for (rIdx in routeBtns.indices) {
            routeBtns[rIdx].setOnClickListener {
                AudioEngine.selectedRoutingProfileMode = rIdx
                AudioEngine.saveEngineModes(this)
                AudioEngine.updateSoundQualityDSP()
                AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, false)
                updateRoutingProfileButtonsUI()
                syncVolumeRangeUI()
            }
        }

        // סנכרון כפתורי Sleep Timer with Fade-Out
        fun updateSleepTimerButtonsUI(selectedMin: Int) {
            for ((btn, mins) in sleepBtns) {
                val active = (mins == selectedMin)
                btn.strokeColor = ColorStateList.valueOf(
                    if (active) Color.parseColor("#FFD740") else Color.parseColor("#3A465E")
                )
                btn.setTextColor(
                    if (active) Color.parseColor("#FFD740") else Color.parseColor("#A0AAB8")
                )
            }
        }

        for ((btn, mins) in sleepBtns) {
            btn.setOnClickListener {
                AudioEngine.startSleepTimer(this, mins)
                updateSleepTimerButtonsUI(mins)
                if (mins == 0) {
                    tvSleepTimerCountdown.text = "כבוי"
                } else {
                    Toast.makeText(this, "טיימר שינה הופעל ל-$mins דקות עם Fade-Out", Toast.LENGTH_SHORT).show()
                }
            }
        }

        AudioEngine.onSleepTimerTickCallback = { remSec, newVol ->
            runOnUiThread {
                if (remSec <= 0L) {
                    tvSleepTimerCountdown.text = "כבוי"
                    updateSleepTimerButtonsUI(0)
                } else {
                    val m = remSec / 60
                    val s = remSec % 60
                    val fadeTag = if (remSec <= 60) " (Fade-Out...)" else ""
                    tvSleepTimerCountdown.text = String.format("%02d:%02d%s", m, s, fadeTag)
                }
                if (seekTotalVolume.progress != newVol) {
                    isUpdatingControlsProgrammatically = true
                    seekTotalVolume.progress = newVol
                    progressTotalVolume.progress = newVol
                    isUpdatingControlsProgrammatically = false
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

        // חיבור Multi-Band Dynamic Limiter
        switchMultiBandLimiter.isChecked = AudioEngine.multiBandLimiterEnabled
        seekMbLow.progress = AudioEngine.mbLowControl
        tvMbLowVal.text = "${AudioEngine.mbLowControl}%"
        seekMbMid.progress = AudioEngine.mbMidControl
        tvMbMidVal.text = "${AudioEngine.mbMidControl}%"
        seekMbHigh.progress = AudioEngine.mbHighControl
        tvMbHighVal.text = "${AudioEngine.mbHighControl}%"

        switchMultiBandLimiter.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.multiBandLimiterEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }
        seekMbLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvMbLowVal.text = "$p%"
                if (fromUser) {
                    AudioEngine.mbLowControl = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                    AudioEngine.updateSoundQualityDSP()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekMbMid.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvMbMidVal.text = "$p%"
                if (fromUser) {
                    AudioEngine.mbMidControl = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                    AudioEngine.updateSoundQualityDSP()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        seekMbHigh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvMbHighVal.text = "$p%"
                if (fromUser) {
                    AudioEngine.mbHighControl = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                    AudioEngine.updateSoundQualityDSP()
                }
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

        // חיבור מתג Hearing Safe Guard
        switchHearingSafeGuard.isChecked = AudioEngine.hearingSafeGuardEnabled
        switchHearingSafeGuard.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.hearingSafeGuardEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            syncVolumeRangeUI()
            if (isChecked && AudioEngine.isHeadphonesActive()) {
                Toast.makeText(this, "הגנת שמיעה באוזניות הופעלה (מקסימום 120%)", Toast.LENGTH_SHORT).show()
            }
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
            val target = 150.coerceAtMost(AudioEngine.getEffectiveMaxVolume())
            seekTotalVolume.progress = target
            AudioEngine.applyVolume(this, target, false)
        }
        findViewById<MaterialButton>(R.id.btnVol200).setOnClickListener {
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            val target = 200.coerceAtMost(AudioEngine.getEffectiveMaxVolume())
            seekTotalVolume.progress = target
            AudioEngine.applyVolume(this, target, false)
        }
        btnVol300.setOnClickListener {
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            if (AudioEngine.allow300PercentBoost) {
                val target = 300.coerceAtMost(AudioEngine.getEffectiveMaxVolume())
                seekTotalVolume.progress = target
                AudioEngine.applyVolume(this, target, false)
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

        // בניית אקולייזר פרמטרי מורחב 10 ערוצים (31Hz עד 16kHz) + Q-Factor
        seekParametricQ.progress = AudioEngine.parametricQFactorProgress
        val initQ = String.format("%.1f", 0.4f + (AudioEngine.parametricQFactorProgress / 10f))
        tvParametricQValue.text = "Q: $initQ"

        seekParametricQ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val qVal = String.format("%.1f", 0.4f + (p / 10f))
                tvParametricQValue.text = "Q: $qVal"
                if (fromUser) {
                    AudioEngine.parametricQFactorProgress = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                    AudioEngine.updateSoundQualityDSP()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        eqSliders.clear()
        eqContainer.removeAllViews()

        for (bandIdx in 0 until 10) {
            val freqHz = AudioEngine.parametricFreqsHz[bandIdx]
            val freqText = if (freqHz >= 1000) "${freqHz / 1000} kHz" else "$freqHz Hz"

            val rowHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 4)
            }

            val tvFreq = TextView(this).apply {
                text = "ערוץ ${bandIdx + 1}: $freqText"
                setTextColor(Color.parseColor("#D0D8E8"))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val savedGainDb = AudioEngine.parametricBandGainsDb[bandIdx].roundToInt()
            val tvDb = TextView(this).apply {
                text = if (savedGainDb > 0) "+$savedGainDb dB" else "$savedGainDb dB"
                setTextColor(Color.parseColor("#00E5FF"))
                textSize = 13f
                gravity = Gravity.END
            }

            rowHeader.addView(tvFreq)
            rowHeader.addView(tvDb)

            // טווח 0..30 המייצג -15dB עד +15dB (אמצע = 15)
            val seekBar = SeekBar(this).apply {
                max = 30
                progress = (savedGainDb + 15).coerceIn(0, 30)
                progressTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                thumbTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val dbVal = p - 15
                        tvDb.text = if (dbVal > 0) "+$dbVal dB" else "$dbVal dB"
                        if (fromUser) {
                            AudioEngine.parametricBandGainsDb[bandIdx] = dbVal.toFloat()
                            AudioEngine.saveEngineModes(this@MainActivity)
                            AudioEngine.updateSoundQualityDSP()
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

        findViewById<MaterialButton>(R.id.btnPresetNormal).setOnClickListener {
            applyPreset(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), bass = 0, surround = 0, reverb = 0)
        }
        findViewById<MaterialButton>(R.id.btnPresetBass).setOnClickListener {
            applyPreset(listOf(9, 8, 6, 3, 0, 0, 1, 2, 3, 4), bass = 700, surround = 250, reverb = 1)
        }
        findViewById<MaterialButton>(R.id.btnPresetVocal).setOnClickListener {
            applyPreset(listOf(-3, -2, 0, 2, 5, 7, 7, 5, 3, 2), bass = 100, surround = 200, reverb = 1)
        }
        findViewById<MaterialButton>(R.id.btnPresetGaming).setOnClickListener {
            applyPreset(listOf(6, 5, 3, 0, -1, 2, 6, 8, 9, 8), bass = 500, surround = 900, reverb = 3)
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

    private fun applyPreset(gainsDbList: List<Int>, bass: Int, surround: Int, reverb: Int) {
        runCatching {
            for (i in 0 until minOf(eqSliders.size, gainsDbList.size)) {
                val db = gainsDbList[i].coerceIn(-15, 15)
                AudioEngine.parametricBandGainsDb[i] = db.toFloat()
                eqSliders[i].progress = db + 15
            }
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()

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
        val safeGuardMark = if (AudioEngine.hearingSafeGuardEnabled && AudioEngine.isHeadphonesActive()) " 🛡️" else ""
        badgeView?.apply {
            setPadding(padH, padV, padH, padV)
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEF2F6"))
                cornerRadius = dp(24f).toFloat()
                setStroke(dp(2f), Color.parseColor("#3B607A"))
            }
            setTextColor(Color.parseColor("#1B2C38"))
            text = "${AudioEngine.currentTotalVolume}% HD$btMark$safeGuardMark"
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
 
