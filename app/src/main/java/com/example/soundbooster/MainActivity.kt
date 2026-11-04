package com.example.soundbooster

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.PresetReverb
import android.media.audiofx.Virtualizer
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// =========================================================================
// 0. מנוע משוב רטט חכם (Tactile Haptic Feedback Engine)
// =========================================================================
object HapticEngine {
    private var lastTickTime = 0L

    private fun getVibrator(context: Context): Vibrator? {
        return context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun tick(context: Context) {
        if (!AudioEngine.hapticFeedbackEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastTickTime < 42L) return
        lastTickTime = now
        runCatching {
            val vib = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(10L, 65))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(10L)
            }
        }
    }

    fun click(context: Context) {
        if (!AudioEngine.hapticFeedbackEnabled) return
        runCatching {
            val vib = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(18L, 120))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(18L)
            }
        }
    }

    fun heavyMilestone(context: Context) {
        if (!AudioEngine.hapticFeedbackEnabled) return
        runCatching {
            val vib = getVibrator(context) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(35L, 220))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(35L)
            }
        }
    }
}

// =========================================================================
// 0.1 מחולל תדרי בדיקת שמיעה (Personalized Sound Calibration Generator)
// =========================================================================
object ToneCalibrationEngine {
    @Volatile private var isPlayingTone = false

    fun playCalibrationTone(freqHz: Int, durationMs: Int = 950, onComplete: () -> Unit) {
        if (isPlayingTone) return
        isPlayingTone = true
        thread(start = true) {
            runCatching {
                val sampleRate = 44100
                val numSamples = (durationMs * sampleRate) / 1000
                val samples = ShortArray(numSamples)
                for (i in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * i * freqHz / sampleRate
                    val env = when {
                        i < 1500 -> i / 1500.0
                        i > numSamples - 1500 -> (numSamples - i) / 1500.0
                        else -> 1.0
                    }
                    samples[i] = (sin(angle) * 14000 * env).toInt().toShort()
                }
                val bufferSize = samples.size * 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(durationMs.toLong() + 60L)
                track.stop()
                track.release()
            }
            isPlayingTone = false
            Handler(Looper.getMainLooper()).post { onComplete() }
        }
    }
}

// =========================================================================
// 1. מנוע סאונד והגברה דרך מחוון המערכת המקורי של המכשיר (Native SystemUI)
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

    // הגנת שמיעה, רטט, מונו חכם ובועית צפה נגררת
    var hearingSafeGuardEnabled: Boolean = false
    const val HEARING_SAFE_MAX_VOLUME = 120
    var hapticFeedbackEnabled: Boolean = true
    var monoDownmixEnabled: Boolean = false
    var alwaysShowDraggableMiniPill: Boolean = false
    var pillPosX: Int = 16
    var pillPosY: Int = -155

    // איזון ערוצים שמאל / ימין (L/R Stereo Balance: 0=Full Left, 100=Center, 200=Full Right)
    var lrBalanceProgress: Int = 100

    // פסיכו-אקוסטיקה: פיצוי תדרים בווליום נמוך + מניעת עייפות שמיעה
    var loudnessContourEnabled: Boolean = true
    var antiFatigueModeEnabled: Boolean = false

    // הגנות ניתוק אוזניות והתרחקות בלוטות'
    var autoRestoreSpeakerOnUnplug: Boolean = true
    var btRangeDropGuardEnabled: Boolean = true
    var savedSpeakerOnlyVolume: Int = 85
    var savedHeadphonesOnlyVolume: Int = 120
    private var wasHeadphonesConnectedLastCheck: Boolean = false
    private var wasBluetoothConnectedLastCheck: Boolean = false

    // חיסכון סוללה חכם, AMOLED Black וערכת צבעי הדגש
    var autoBatterySaverDspEnabled: Boolean = true
    var isLowBatteryDetected: Boolean = false
    var amoledBlackThemeEnabled: Boolean = false
    var accentThemeIndex: Int = 0 // 0=Neon Green, 1=Cyber Cyan, 2=Studio Gold, 3=Ultra Purple

    // פרופילי ניתוב שמע (Audio Output Routing Profiles):
    // 0 = Auto Detect, 1 = Phone Speaker, 2 = Wired Headphones, 3 = Bluetooth Audio, 4 = Car Audio
    var selectedRoutingProfileMode: Int = 0
    var detectedHardwareRoute: Int = 1
    var isBluetoothConnected: Boolean = false
    var isWiredHeadsetConnected: Boolean = false

    // עוצמה עצמאית לכל אפליקציה (Per-App Volume Control):
    // 0 = YouTube, 1 = Spotify/Music, 2 = TikTok/Social, 3 = Games, 4 = WhatsApp/Voice
    var perAppVolumeEnabled: Boolean = true
    var selectedAppSlotIndex: Int = 0
    val perAppVolumes = intArrayOf(140, 155, 125, 165, 135)
    var currentDetectedAppCategory: Int = -1
    var lastDetectedPackageName: String = ""

    // זיהוי סביבה רועשת (Ambient Noise Auto-Boost)
    var ambientNoiseBoostEnabled: Boolean = false
    var ambientSensitivityPercent: Int = 60
    var currentAmbientAutoBonusGainMb: Int = 0
    private var ambientMonitorRunning = false

    // מצב דיבור חכם (Dialogue Clarity) + כיול שמיעה אישי (Personal Ear Calibration)
    var vocalDialogueModeEnabled: Boolean = false
    var vocalDialogueStrength: Int = 70
    var personalCalibrationEnabled: Boolean = true
    val personalEarOffsetsDb = floatArrayOf(1.5f, 1.0f, 2.2f)

    // 0 = מדיה, 1 = עוצמת צלצול, 2 = התראות, 3 = שעון מעורר, 4 = שיחת טלפון
    var activeStreamType: Int = 0
    private var manualStreamOverrideUntil: Long = 0L

    var antiClippingEnabled: Boolean = true
    var hiResOptimizerEnabled: Boolean = true
    var clarityLevel: Int = 50
    var warmthLevel: Int = 40

    // Multi-Band Dynamic Limiter
    var multiBandLimiterEnabled: Boolean = true
    var mbLowControl: Int = 75
    var mbMidControl: Int = 65
    var mbHighControl: Int = 70

    // אקולייזר פרמטרי מורחב 10 ערוצים (31Hz..16kHz) + Q-Factor
    val parametricFreqsHz = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val parametricBandGainsDb = FloatArray(10) { 0f }
    var parametricQFactorProgress: Int = 12

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
    var onAmbientNoiseUpdateCallback: ((Int, Int) -> Unit)? = null

    @Volatile var isInternalVolumeChange: Boolean = false
    var bootCooldownUntil: Long = 0L
    private var playbackCallbackRegistered = false
    private var isInitializedOnce = false

    val appCategoryNames = listOf(
        "YouTube",
        "Spotify / Music",
        "TikTok / Reels",
        "משחקים (Games)",
        "WhatsApp / פודקאסט"
    )

    fun getPrimaryAccentColor(): Int {
        return when (accentThemeIndex) {
            1 -> Color.parseColor("#00E5FF")
            2 -> Color.parseColor("#FFD740")
            3 -> Color.parseColor("#BB86FC")
            else -> Color.parseColor("#00E676")
        }
    }

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

    fun checkBatteryLevelState(context: Context): Boolean {
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            isLowBatteryDetected = (pct in 1..15)
        }
        return isLowBatteryDetected
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

        val nowHeadphones = btFound || wiredFound
        if (isInitializedOnce && activeStreamType == 0) {
            // זיהוי ניתוק אוזניות או נפילת טווח בלוטות' ושחזור אוטומטי של ווליום רמקול בטוח
            if (wasHeadphonesConnectedLastCheck && !nowHeadphones) {
                savedHeadphonesOnlyVolume = currentTotalVolume
                if (autoRestoreSpeakerOnUnplug || (wasBluetoothConnectedLastCheck && btRangeDropGuardEnabled)) {
                    val safeSpeakerVol = savedSpeakerOnlyVolume.coerceIn(20, 100)
                    currentTotalVolume = safeSpeakerVol
                    savedMediaBoostVolume = safeSpeakerVol
                    runCatching { loudnessEnhancer?.setTargetGain(0) }
                }
            } else if (!wasHeadphonesConnectedLastCheck && nowHeadphones) {
                savedSpeakerOnlyVolume = currentTotalVolume.coerceIn(0, 110)
                if (autoRestoreSpeakerOnUnplug) {
                    currentTotalVolume = savedHeadphonesOnlyVolume.coerceIn(0, getEffectiveMaxVolume())
                    savedMediaBoostVolume = currentTotalVolume
                }
            }
        }

        wasHeadphonesConnectedLastCheck = nowHeadphones
        wasBluetoothConnectedLastCheck = btFound
        isBluetoothConnected = btFound
        isWiredHeadsetConnected = wiredFound
        detectedHardwareRoute = when {
            btFound -> 3
            wiredFound -> 2
            else -> 1
        }
        checkBatteryLevelState(context)
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

    fun onForegroundAppDetected(context: Context, packageName: String) {
        if (!perAppVolumeEnabled || !isBoosterActive || activeStreamType != 0) return
        val pkg = packageName.lowercase()
        if (pkg == lastDetectedPackageName ||
            pkg.contains("soundbooster") ||
            pkg.contains("systemui") ||
            pkg.contains("launcher") ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard")
        ) {
            return
        }
        lastDetectedPackageName = pkg

        val matchedCategory = when {
            pkg.contains("youtube") || pkg.contains("vanced") || pkg.contains("newpipe") -> 0
            pkg.contains("spotify") || pkg.contains("music") || pkg.contains("deezer") || pkg.contains("soundcloud") -> 1
            pkg.contains("tiktok") || pkg.contains("musically") || pkg.contains("instagram") || pkg.contains("facebook") -> 2
            pkg.contains("game") || pkg.contains("pubg") || pkg.contains("brawl") || pkg.contains("roblox") || pkg.contains("cod") -> 3
            pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("podcast") || pkg.contains("audible") -> 4
            else -> -1
        }

        if (matchedCategory != -1 && matchedCategory != currentDetectedAppCategory) {
            currentDetectedAppCategory = matchedCategory
            val targetVol = perAppVolumes[matchedCategory].coerceIn(0, getEffectiveMaxVolume())
            if (targetVol != currentTotalVolume) {
                applyVolume(context, targetVol, showNativeSystemUI = false)
                VolumeOverlayService.instance?.showNativeBadgeIfNeeded()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun setAmbientNoiseMonitor(context: Context, enable: Boolean) {
        ambientNoiseBoostEnabled = enable
        if (!enable || (autoBatterySaverDspEnabled && isLowBatteryDetected)) {
            ambientMonitorRunning = false
            currentAmbientAutoBonusGainMb = 0
            reapplyCurrentGainSafely()
            onAmbientNoiseUpdateCallback?.invoke(0, 0)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ambientNoiseBoostEnabled = false
            return
        }
        if (ambientMonitorRunning) return
        ambientMonitorRunning = true

        thread(start = true) {
            val sampleRate = 8000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(1024)

            val recorder = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull()

            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                ambientMonitorRunning = false
                return@thread
            }

            runCatching { recorder.startRecording() }
            val buf = ShortArray(minBuf / 2)

            while (ambientMonitorRunning && ambientNoiseBoostEnabled) {
                val read = recorder.read(buf, 0, buf.size)
                if (read > 0) {
                    var sumSq = 0.0
                    for (i in 0 until read) {
                        val s = buf[i].toDouble()
                        sumSq += s * s
                    }
                    val rms = sqrt(sumSq / read).coerceAtLeast(1.0)
                    val approxDb = (20.0 * log10(rms)).toInt().coerceIn(25, 95)

                    val noiseExcess = (approxDb - 52).coerceAtLeast(0)
                    val sensFactor = ambientSensitivityPercent / 100f
                    currentAmbientAutoBonusGainMb = (noiseExcess * 18f * sensFactor).roundToInt().coerceIn(0, 750)
                    val bonusPct = (currentAmbientAutoBonusGainMb / 17f).roundToInt()

                    reapplyCurrentGainSafely()
                    Handler(Looper.getMainLooper()).post {
                        onAmbientNoiseUpdateCallback?.invoke(approxDb, bonusPct)
                    }
                }
                Thread.sleep(900L)
            }

            runCatching {
                recorder.stop()
                recorder.release()
            }
            currentAmbientAutoBonusGainMb = 0
        }
    }

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

    // ייצוא ושחזור מלא של כל הגדרות הסאונד לפורמט JSON
    fun exportConfigurationToJson(): String {
        val root = JSONObject()
        root.put("allow_300_boost", allow300PercentBoost)
        root.put("hearing_safe_guard", hearingSafeGuardEnabled)
        root.put("haptic_en", hapticFeedbackEnabled)
        root.put("mono_downmix_en", monoDownmixEnabled)
        root.put("lr_balance", lrBalanceProgress)
        root.put("loudness_contour", loudnessContourEnabled)
        root.put("anti_fatigue", antiFatigueModeEnabled)
        root.put("auto_restore_unplug", autoRestoreSpeakerOnUnplug)
        root.put("bt_range_guard", btRangeDropGuardEnabled)
        root.put("battery_saver_dsp", autoBatterySaverDspEnabled)
        root.put("amoled_black", amoledBlackThemeEnabled)
        root.put("accent_theme", accentThemeIndex)
        root.put("routing_profile_mode", selectedRoutingProfileMode)
        root.put("per_app_vol_en", perAppVolumeEnabled)
        root.put("vocal_dialogue_en", vocalDialogueModeEnabled)
        root.put("vocal_dialogue_str", vocalDialogueStrength)
        root.put("personal_cal_en", personalCalibrationEnabled)
        root.put("mb_limiter_en", multiBandLimiterEnabled)
        root.put("mb_low", mbLowControl)
        root.put("mb_mid", mbMidControl)
        root.put("mb_high", mbHighControl)
        root.put("param_q_prog", parametricQFactorProgress)
        root.put("ai_song_en", aiSongEngineEnabled)
        root.put("ai_preset_mode", aiPresetMode)
        root.put("ai_intensity", aiNeuralIntensity)
        root.put("song_remaster_en", songRemasterEnabled)
        root.put("song_upscale", songUpscaleLevel)
        root.put("song_sep", songSeparationLevel)
        root.put("song_punch", songPunchLevel)
        root.put("clarity", clarityLevel)
        root.put("warmth", warmthLevel)

        val eqArr = JSONArray()
        for (b in 0 until 10) eqArr.put(parametricBandGainsDb[b].toDouble())
        root.put("eq_10_bands", eqArr)

        val appArr = JSONArray()
        for (i in 0..4) appArr.put(perAppVolumes[i])
        root.put("per_app_volumes", appArr)

        return root.toString(2)
    }

    fun importConfigurationFromJson(context: Context, jsonStr: String): Boolean {
        return runCatching {
            val root = JSONObject(jsonStr)
            allow300PercentBoost = root.optBoolean("allow_300_boost", allow300PercentBoost)
            hearingSafeGuardEnabled = root.optBoolean("hearing_safe_guard", hearingSafeGuardEnabled)
            hapticFeedbackEnabled = root.optBoolean("haptic_en", hapticFeedbackEnabled)
            monoDownmixEnabled = root.optBoolean("mono_downmix_en", monoDownmixEnabled)
            lrBalanceProgress = root.optInt("lr_balance", lrBalanceProgress).coerceIn(0, 200)
            loudnessContourEnabled = root.optBoolean("loudness_contour", loudnessContourEnabled)
            antiFatigueModeEnabled = root.optBoolean("anti_fatigue", antiFatigueModeEnabled)
            autoRestoreSpeakerOnUnplug = root.optBoolean("auto_restore_unplug", autoRestoreSpeakerOnUnplug)
            btRangeDropGuardEnabled = root.optBoolean("bt_range_guard", btRangeDropGuardEnabled)
            autoBatterySaverDspEnabled = root.optBoolean("battery_saver_dsp", autoBatterySaverDspEnabled)
            amoledBlackThemeEnabled = root.optBoolean("amoled_black", amoledBlackThemeEnabled)
            accentThemeIndex = root.optInt("accent_theme", accentThemeIndex).coerceIn(0, 3)
            selectedRoutingProfileMode = root.optInt("routing_profile_mode", selectedRoutingProfileMode).coerceIn(0, 4)
            perAppVolumeEnabled = root.optBoolean("per_app_vol_en", perAppVolumeEnabled)
            vocalDialogueModeEnabled = root.optBoolean("vocal_dialogue_en", vocalDialogueModeEnabled)
            vocalDialogueStrength = root.optInt("vocal_dialogue_str", vocalDialogueStrength).coerceIn(0, 100)
            personalCalibrationEnabled = root.optBoolean("personal_cal_en", personalCalibrationEnabled)
            multiBandLimiterEnabled = root.optBoolean("mb_limiter_en", multiBandLimiterEnabled)
            mbLowControl = root.optInt("mb_low", mbLowControl).coerceIn(0, 100)
            mbMidControl = root.optInt("mb_mid", mbMidControl).coerceIn(0, 100)
            mbHighControl = root.optInt("mb_high", mbHighControl).coerceIn(0, 100)
            parametricQFactorProgress = root.optInt("param_q_prog", parametricQFactorProgress).coerceIn(0, 40)
            aiSongEngineEnabled = root.optBoolean("ai_song_en", aiSongEngineEnabled)
            aiPresetMode = root.optInt("ai_preset_mode", aiPresetMode).coerceIn(0, 3)
            aiNeuralIntensity = root.optInt("ai_intensity", aiNeuralIntensity).coerceIn(0, 100)
            songRemasterEnabled = root.optBoolean("song_remaster_en", songRemasterEnabled)
            songUpscaleLevel = root.optInt("song_upscale", songUpscaleLevel).coerceIn(0, 100)
            songSeparationLevel = root.optInt("song_sep", songSeparationLevel).coerceIn(0, 100)
            songPunchLevel = root.optInt("song_punch", songPunchLevel).coerceIn(0, 100)
            clarityLevel = root.optInt("clarity", clarityLevel).coerceIn(0, 100)
            warmthLevel = root.optInt("warmth", warmthLevel).coerceIn(0, 100)

            root.optJSONArray("eq_10_bands")?.let { arr ->
                for (b in 0 until minOf(10, arr.length())) {
                    parametricBandGainsDb[b] = arr.optDouble(b, 0.0).toFloat().coerceIn(-15f, 15f)
                }
            }
            root.optJSONArray("per_app_volumes")?.let { arr ->
                for (i in 0 until minOf(5, arr.length())) {
                    perAppVolumes[i] = arr.optInt(i, perAppVolumes[i]).coerceIn(0, 300)
                }
            }

            saveEngineModes(context)
            updateSoundQualityDSP()
            reapplyCurrentGainSafely()
            true
        }.getOrDefault(false)
    }

    fun init(context: Context? = null) {
        context?.let {
            val prefs = it.getSharedPreferences("sound_booster_hd", Context.MODE_PRIVATE)
            allow300PercentBoost = prefs.getBoolean("allow_300_boost", allow300PercentBoost)
            hearingSafeGuardEnabled = prefs.getBoolean("hearing_safe_guard", hearingSafeGuardEnabled)
            hapticFeedbackEnabled = prefs.getBoolean("haptic_en", hapticFeedbackEnabled)
            monoDownmixEnabled = prefs.getBoolean("mono_downmix_en", monoDownmixEnabled)
            alwaysShowDraggableMiniPill = prefs.getBoolean("always_mini_pill", alwaysShowDraggableMiniPill)
            pillPosX = prefs.getInt("pill_pos_x", pillPosX)
            pillPosY = prefs.getInt("pill_pos_y", pillPosY)

            lrBalanceProgress = prefs.getInt("lr_balance", lrBalanceProgress)
            loudnessContourEnabled = prefs.getBoolean("loudness_contour", loudnessContourEnabled)
            antiFatigueModeEnabled = prefs.getBoolean("anti_fatigue", antiFatigueModeEnabled)
            autoRestoreSpeakerOnUnplug = prefs.getBoolean("auto_restore_unplug", autoRestoreSpeakerOnUnplug)
            btRangeDropGuardEnabled = prefs.getBoolean("bt_range_guard", btRangeDropGuardEnabled)
            savedSpeakerOnlyVolume = prefs.getInt("saved_spk_only_vol", savedSpeakerOnlyVolume)
            savedHeadphonesOnlyVolume = prefs.getInt("saved_hp_only_vol", savedHeadphonesOnlyVolume)

            autoBatterySaverDspEnabled = prefs.getBoolean("battery_saver_dsp", autoBatterySaverDspEnabled)
            amoledBlackThemeEnabled = prefs.getBoolean("amoled_black", amoledBlackThemeEnabled)
            accentThemeIndex = prefs.getInt("accent_theme", accentThemeIndex)

            selectedRoutingProfileMode = prefs.getInt("routing_profile_mode", selectedRoutingProfileMode)
            perAppVolumeEnabled = prefs.getBoolean("per_app_vol_en", perAppVolumeEnabled)
            for (i in 0..4) {
                perAppVolumes[i] = prefs.getInt("per_app_v_$i", perAppVolumes[i])
            }

            ambientSensitivityPercent = prefs.getInt("ambient_sens", ambientSensitivityPercent)
            vocalDialogueModeEnabled = prefs.getBoolean("vocal_dialogue_en", vocalDialogueModeEnabled)
            vocalDialogueStrength = prefs.getInt("vocal_dialogue_str", vocalDialogueStrength)
            personalCalibrationEnabled = prefs.getBoolean("personal_cal_en", personalCalibrationEnabled)
            for (i in 0..2) {
                personalEarOffsetsDb[i] = prefs.getFloat("ear_cal_$i", personalEarOffsetsDb[i])
            }

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
            .putBoolean("haptic_en", hapticFeedbackEnabled)
            .putBoolean("mono_downmix_en", monoDownmixEnabled)
            .putBoolean("always_mini_pill", alwaysShowDraggableMiniPill)
            .putInt("pill_pos_x", pillPosX)
            .putInt("pill_pos_y", pillPosY)
            .putInt("lr_balance", lrBalanceProgress)
            .putBoolean("loudness_contour", loudnessContourEnabled)
            .putBoolean("anti_fatigue", antiFatigueModeEnabled)
            .putBoolean("auto_restore_unplug", autoRestoreSpeakerOnUnplug)
            .putBoolean("bt_range_guard", btRangeDropGuardEnabled)
            .putInt("saved_spk_only_vol", savedSpeakerOnlyVolume)
            .putInt("saved_hp_only_vol", savedHeadphonesOnlyVolume)
            .putBoolean("battery_saver_dsp", autoBatterySaverDspEnabled)
            .putBoolean("amoled_black", amoledBlackThemeEnabled)
            .putInt("accent_theme", accentThemeIndex)
            .putInt("routing_profile_mode", selectedRoutingProfileMode)
            .putBoolean("per_app_vol_en", perAppVolumeEnabled)
            .putInt("ambient_sens", ambientSensitivityPercent)
            .putBoolean("vocal_dialogue_en", vocalDialogueModeEnabled)
            .putInt("vocal_dialogue_str", vocalDialogueStrength)
            .putBoolean("personal_cal_en", personalCalibrationEnabled)
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

        for (i in 0..4) {
            editor.putInt("per_app_v_$i", perAppVolumes[i])
        }
        for (i in 0..2) {
            editor.putFloat("ear_cal_$i", personalEarOffsetsDb[i])
        }
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
        // שימוש ב-2 ערוצי עיבוד (Left=0, Right=1) לטובת שליטה מלאה באיזון L/R Stereo Balance
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dynamicsProcessing == null) {
            runCatching {
                val cfgBuilder = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,          // 2 Channels: Left (0) & Right (1)
                    true, 10,   // 10-Band Pre-EQ
                    true, 3,    // 3-Band Multi-Band Compressor
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
        if (mediaVol <= 100 && currentAmbientAutoBonusGainMb <= 0) {
            runCatching { loudnessEnhancer?.setTargetGain(0) }
            return
        }
        val extra = (mediaVol - 100).coerceAtLeast(0)

        val profileMultiplier = when (getEffectiveOutputProfile()) {
            1 -> 0.88f
            4 -> 1.08f
            else -> 1.0f
        }
        val factor = if (antiClippingEnabled) 17 else 24
        val cleanGain = (extra * factor * profileMultiplier).roundToInt() + currentAmbientAutoBonusGainMb

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

        val batterySaverActive = autoBatterySaverDspEnabled && isLowBatteryDetected
        val qFactor = (0.4f + (parametricQFactorProgress / 10f)).coerceIn(0.4f, 4.4f)
        val routeProfile = getEffectiveOutputProfile()

        var routeSubBassDb = 0f
        var routeLowMidCutDb = 0f
        var routeVocalPresenceDb = 0f
        var routeAirTrebleDb = 0f

        when (routeProfile) {
            1 -> {
                routeSubBassDb = -1.5f
                routeLowMidCutDb = -1.2f
                routeVocalPresenceDb = 2.2f
                routeAirTrebleDb = 1.8f
            }
            2 -> {
                routeSubBassDb = 2.8f
                routeLowMidCutDb = -0.8f
                routeVocalPresenceDb = 1.5f
                routeAirTrebleDb = 2.5f
            }
            3 -> {
                routeSubBassDb = 2.4f
                routeLowMidCutDb = -1.0f
                routeVocalPresenceDb = 1.8f
                routeAirTrebleDb = 3.0f
            }
            4 -> {
                routeSubBassDb = 4.0f
                routeLowMidCutDb = -1.5f
                routeVocalPresenceDb = 2.0f
                routeAirTrebleDb = 2.2f
            }
        }

        // 1. פיצוי איבוד תדרים בווליום נמוך (Fletcher-Munson Loudness Contour)
        if (loudnessContourEnabled && currentTotalVolume < 65) {
            val lowVolRatio = ((65 - currentTotalVolume) / 65f).coerceIn(0f, 1f)
            routeSubBassDb += 3.8f * lowVolRatio
            routeAirTrebleDb += 2.6f * lowVolRatio
        }

        // 2. מצב מניעת עייפות שמיעה (Anti-Fatigue / Dark Warm Mode)
        if (antiFatigueModeEnabled) {
            routeAirTrebleDb -= 3.2f
            routeVocalPresenceDb -= 1.0f
            routeSubBassDb += 1.2f
        }

        if (vocalDialogueModeEnabled) {
            val vRatio = vocalDialogueStrength / 100f
            routeSubBassDb -= 3.5f * vRatio
            routeLowMidCutDb -= 2.5f * vRatio
            routeVocalPresenceDb += 5.2f * vRatio
            routeAirTrebleDb += 1.5f * vRatio
        }

        if (personalCalibrationEnabled) {
            routeSubBassDb += personalEarOffsetsDb[0]
            routeVocalPresenceDb += personalEarOffsetsDb[1]
            routeAirTrebleDb += personalEarOffsetsDb[2]
        }

        if (monoDownmixEnabled) {
            runCatching { virtualizer?.setStrength(0.toShort()) }
            routeVocalPresenceDb += 1.2f
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val dp = dynamicsProcessing
                if (dp != null) {
                    if (batterySaverActive) {
                        dp.enabled = false
                    } else {
                        dp.enabled = true
                        // שליטה באיזון ערוצים שמאל/ימין (L/R Stereo Balance) דרך InputGain של כל ערוץ
                        val leftAttenuationDb = if (lrBalanceProgress > 100) {
                            -((lrBalanceProgress - 100) / 100f) * 24f
                        } else 0f
                        val rightAttenuationDb = if (lrBalanceProgress < 100) {
                            -((100 - lrBalanceProgress) / 100f) * 24f
                        } else 0f

                        runCatching {
                            dp.setInputGainbyChannel(0, leftAttenuationDb)
                            dp.setInputGainbyChannel(1, rightAttenuationDb)
                        }

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
                            val qScaledGain = (userGainDb * (0.85f + (qFactor - 1.0f) * 0.12f)) + profileOffset
                            val eqBand = DynamicsProcessing.EqBand(true, cutoffFreqs[b], qScaledGain.coerceIn(-15f, 15f))
                            dp.setPreEqBandAllChannelsTo(b, eqBand)
                        }

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
        }

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
                            if (!monoDownmixEnabled) {
                                runCatching { virtualizer?.setStrength((aiPower * 720).toInt().toShort()) }
                            }
                            runCatching { presetReverb?.preset = 1.toShort() }
                        }
                        1 -> {
                            lowBandGain += aiPower * (maxEQ * 0.62f) * highVolProtection
                            lowMidGain += aiPower * (maxEQ * 0.15f)
                            presenceGain += aiPower * (maxEQ * 0.25f)
                            airHighGain += aiPower * (maxEQ * 0.32f)

                            runCatching { bassBoost?.setStrength((aiPower * 920 * highVolProtection).toInt().toShort()) }
                            if (!monoDownmixEnabled) {
                                runCatching { virtualizer?.setStrength((aiPower * 550).toInt().toShort()) }
                            }
                            runCatching { presetReverb?.preset = 0.toShort() }
                        }
                        2 -> {
                            lowBandGain += aiPower * (maxEQ * 0.20f)
                            lowMidGain -= aiPower * (maxEQ * 0.26f)
                            centerMidGain += aiPower * (maxEQ * 0.38f)
                            presenceGain += aiPower * (maxEQ * 0.52f)
                            airHighGain += aiPower * (maxEQ * 0.55f)

                            runCatching { bassBoost?.setStrength((aiPower * 350).toInt().toShort()) }
                            if (!monoDownmixEnabled) {
                                runCatching { virtualizer?.setStrength((aiPower * 680).toInt().toShort()) }
                            }
                            runCatching { presetReverb?.preset = 1.toShort() }
                        }
                        else -> {
                            lowBandGain += aiPower * (maxEQ * 0.45f) * highVolProtection
                            centerMidGain += aiPower * (maxEQ * 0.20f)
                            presenceGain += aiPower * (maxEQ * 0.42f)
                            airHighGain += aiPower * (maxEQ * 0.50f)

                            runCatching { bassBoost?.setStrength((aiPower * 740 * highVolProtection).toInt().toShort()) }
                            if (!monoDownmixEnabled) {
                                runCatching { virtualizer?.setStrength((aiPower * 980).toInt().toShort()) }
                            }
                            runCatching { presetReverb?.preset = 4.toShort() }
                        }
                    }
                }

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
            runCatching { dynamicsProcessing?.enabled = enabled && !(autoBatterySaverDspEnabled && isLowBatteryDetected) }
        }
    }

    fun applyVolume(context: Context, totalPercent: Int, showNativeSystemUI: Boolean = false) {
        ensureAudioEffectsCreated()
        updateBluetoothStatusOnly(context)
        val prevVol = currentTotalVolume
        val maxAllowed = getEffectiveMaxVolume()
        currentTotalVolume = totalPercent.coerceIn(0, maxAllowed)

        if (activeStreamType == 0) {
            if (isHeadphonesActive()) {
                savedHeadphonesOnlyVolume = currentTotalVolume
            } else {
                savedSpeakerOnlyVolume = currentTotalVolume.coerceAtMost(110)
            }
        }

        if (currentTotalVolume != prevVol) {
            if ((prevVol < 100 && currentTotalVolume >= 100) ||
                (prevVol > 100 && currentTotalVolume <= 100) ||
                currentTotalVolume == 200 ||
                currentTotalVolume == 300
            ) {
                HapticEngine.heavyMilestone(context)
            }
        }

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
                .edit()
                .putInt("saved_vol", currentTotalVolume)
                .putInt("saved_spk_only_vol", savedSpeakerOnlyVolume)
                .putInt("saved_hp_only_vol", savedHeadphonesOnlyVolume)
                .apply()

            val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            if (currentTotalVolume <= 100 || !isBoosterActive) {
                val safePct = currentTotalVolume.coerceIn(0, 100)
                val sysTarget = ((safePct / 100f) * maxSysVol).roundToInt()
                if (curSys != sysTarget || allowNativeUI) {
                    runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysTarget, flags) }
                }
                if (currentAmbientAutoBonusGainMb <= 0) {
                    runCatching { loudnessEnhancer?.setTargetGain(0) }
                } else {
                    reapplyCurrentGainSafely()
                }
                updateSoundQualityDSP()
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
// 2. המסך הראשי של האפליקציה (7 קטגוריות מלאות)
// =========================================================================
class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var maxSystemVolume: Int = 15

    private lateinit var rootMainLayout: LinearLayout
    private lateinit var cardHeader: MaterialCardView
    private lateinit var seekTotalVolume: SeekBar
    private lateinit var progressTotalVolume: ProgressBar
    private lateinit var tvVolumeCardTitle: TextView
    private lateinit var tvActiveOutputProfileInfo: TextView
    private lateinit var tvHearingGuardSub: TextView
    private lateinit var btnVol300: MaterialButton
    private lateinit var switchEnable: SwitchCompat
    private lateinit var btnEnableSystemSlider: MaterialButton
    private val eqSliders = mutableListOf<SeekBar>()
    private val categoryTabs = mutableListOf<MaterialButton>()
    private var selectedCategoryTabIdx = 0

    private var isUpdatingControlsProgrammatically = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootMainLayout = findViewById(R.id.rootMainLayout)
        cardHeader = findViewById(R.id.cardHeader)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        AudioEngine.init(this)

        setupCategoryTabs()
        setupModernUI()
        applyVisualThemeColors()
    }

    override fun onResume() {
        super.onResume()
        AudioEngine.updateBluetoothStatusOnly(this)
        updateServiceButtonState()
        syncVolumeRangeUI()
        applyVisualThemeColors()
    }

    private fun applyVisualThemeColors() {
        val bgColor = if (AudioEngine.amoledBlackThemeEnabled) Color.BLACK else Color.parseColor("#0B0E14")
        val accent = AudioEngine.getPrimaryAccentColor()
        rootMainLayout.setBackgroundColor(bgColor)
        runCatching {
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
        }
        cardHeader.strokeColor = if (AudioEngine.isBoosterActive) accent else Color.parseColor("#263042")
        for (i in categoryTabs.indices) {
            if (i == selectedCategoryTabIdx) {
                categoryTabs[i].backgroundTintList = ColorStateList.valueOf(accent)
                categoryTabs[i].setTextColor(Color.parseColor("#0B0E14"))
            } else {
                categoryTabs[i].backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                categoryTabs[i].setTextColor(Color.parseColor("#A0AAB8"))
            }
        }
        VolumeOverlayService.instance?.showNativeBadgeIfNeeded()
    }

    private fun setupCategoryTabs() {
        val tabVolume = findViewById<MaterialButton>(R.id.tabBtnVolume)
        val tabQuality = findViewById<MaterialButton>(R.id.tabBtnQuality)
        val tabHearing = findViewById<MaterialButton>(R.id.tabBtnHearing)
        val tabEffects = findViewById<MaterialButton>(R.id.tabBtnEffects)
        val tabEq = findViewById<MaterialButton>(R.id.tabBtnEq)
        val tabAutomation = findViewById<MaterialButton>(R.id.tabBtnAutomation)
        val tabSystem = findViewById<MaterialButton>(R.id.tabBtnSystem)

        val secVolume = findViewById<View>(R.id.sectionVolume)
        val secQuality = findViewById<View>(R.id.sectionQuality)
        val secHearing = findViewById<View>(R.id.sectionHearing)
        val secEffects = findViewById<View>(R.id.sectionEffects)
        val secEq = findViewById<View>(R.id.sectionEq)
        val secAutomation = findViewById<View>(R.id.sectionAutomation)
        val secSystem = findViewById<View>(R.id.sectionSystem)

        categoryTabs.clear()
        categoryTabs.addAll(listOf(tabVolume, tabQuality, tabHearing, tabEffects, tabEq, tabAutomation, tabSystem))
        val sections = listOf(secVolume, secQuality, secHearing, secEffects, secEq, secAutomation, secSystem)

        fun selectTab(index: Int) {
            selectedCategoryTabIdx = index
            HapticEngine.click(this)
            val accent = AudioEngine.getPrimaryAccentColor()
            for (i in categoryTabs.indices) {
                if (i == index) {
                    categoryTabs[i].backgroundTintList = ColorStateList.valueOf(accent)
                    categoryTabs[i].setTextColor(Color.parseColor("#0B0E14"))
                    sections[i].visibility = View.VISIBLE
                } else {
                    categoryTabs[i].backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    categoryTabs[i].setTextColor(Color.parseColor("#A0AAB8"))
                    sections[i].visibility = View.GONE
                }
            }
        }

        for (i in categoryTabs.indices) {
            categoryTabs[i].setOnClickListener { selectTab(i) }
        }
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
        val switchHapticFeedback = findViewById<SwitchCompat>(R.id.switchHapticFeedback)
        val switchMonoDownmix = findViewById<SwitchCompat>(R.id.switchMonoDownmix)
        val switchAlwaysShowMiniPill = findViewById<SwitchCompat>(R.id.switchAlwaysShowMiniPill)

        // Audio Output Routing Profiles
        tvActiveOutputProfileInfo = findViewById(R.id.tvActiveOutputProfileInfo)
        val btnRouteAuto = findViewById<MaterialButton>(R.id.btnRouteAuto)
        val btnRouteSpeaker = findViewById<MaterialButton>(R.id.btnRouteSpeaker)
        val btnRouteWired = findViewById<MaterialButton>(R.id.btnRouteWired)
        val btnRouteBluetooth = findViewById<MaterialButton>(R.id.btnRouteBluetooth)
        val btnRouteCar = findViewById<MaterialButton>(R.id.btnRouteCar)
        val routeBtns = listOf(btnRouteAuto, btnRouteSpeaker, btnRouteWired, btnRouteBluetooth, btnRouteCar)

        // קטגוריה 3: איזון ערוצים L/R, פיצוי ווליום נמוך, מניעת עייפות וכיול שמיעה
        val tvLRBalanceValue = findViewById<TextView>(R.id.tvLRBalanceValue)
        val seekLRBalance = findViewById<SeekBar>(R.id.seekLRBalance)
        val btnResetLRBalance = findViewById<MaterialButton>(R.id.btnResetLRBalance)
        val switchLoudnessContour = findViewById<SwitchCompat>(R.id.switchLoudnessContour)
        val switchAntiFatigue = findViewById<SwitchCompat>(R.id.switchAntiFatigue)
        val switchPersonalCalibration = findViewById<SwitchCompat>(R.id.switchPersonalCalibration)
        val tvCalibrationStatus = findViewById<TextView>(R.id.tvCalibrationStatus)
        val btnToneTestLow = findViewById<MaterialButton>(R.id.btnToneTestLow)
        val btnToneTestMid = findViewById<MaterialButton>(R.id.btnToneTestMid)
        val btnToneTestHigh = findViewById<MaterialButton>(R.id.btnToneTestHigh)

        // קטגוריה 6: אוטומציה, Per-App, רעש סביבתי, הגנות ניתוק וטיימר שינה
        val switchPerAppVolume = findViewById<SwitchCompat>(R.id.switchPerAppVolume)
        val btnAppSlotYoutube = findViewById<MaterialButton>(R.id.btnAppSlotYoutube)
        val btnAppSlotSpotify = findViewById<MaterialButton>(R.id.btnAppSlotSpotify)
        val btnAppSlotSocial = findViewById<MaterialButton>(R.id.btnAppSlotSocial)
        val btnAppSlotGaming = findViewById<MaterialButton>(R.id.btnAppSlotGaming)
        val btnAppSlotVoice = findViewById<MaterialButton>(R.id.btnAppSlotVoice)
        val appSlotBtns = listOf(btnAppSlotYoutube, btnAppSlotSpotify, btnAppSlotSocial, btnAppSlotGaming, btnAppSlotVoice)
        val tvPerAppTargetLabel = findViewById<TextView>(R.id.tvPerAppTargetLabel)
        val tvPerAppTargetValue = findViewById<TextView>(R.id.tvPerAppTargetValue)
        val seekPerAppTargetVol = findViewById<SeekBar>(R.id.seekPerAppTargetVol)

        val switchAmbientNoiseBoost = findViewById<SwitchCompat>(R.id.switchAmbientNoiseBoost)
        val tvAmbientNoiseInfo = findViewById<TextView>(R.id.tvAmbientNoiseInfo)
        val seekAmbientSensitivity = findViewById<SeekBar>(R.id.seekAmbientSensitivity)
        val tvAmbientSensitivityVal = findViewById<TextView>(R.id.tvAmbientSensitivityVal)

        val switchAutoRestoreUnplug = findViewById<SwitchCompat>(R.id.switchAutoRestoreUnplug)
        val switchBtRangeGuard = findViewById<SwitchCompat>(R.id.switchBtRangeGuard)

        val tvSleepTimerCountdown = findViewById<TextView>(R.id.tvSleepTimerCountdown)
        val btnSleepOff = findViewById<MaterialButton>(R.id.btnSleepOff)
        val btnSleep15 = findViewById<MaterialButton>(R.id.btnSleep15)
        val btnSleep30 = findViewById<MaterialButton>(R.id.btnSleep30)
        val btnSleep60 = findViewById<MaterialButton>(R.id.btnSleep60)
        val sleepBtns = listOf(btnSleepOff to 0, btnSleep15 to 15, btnSleep30 to 30, btnSleep60 to 60)

        // קטגוריה 7: מערכת, סוללה, AMOLED, מיני-EQ צף וגיבוי JSON
        val switchBatterySaverDsp = findViewById<SwitchCompat>(R.id.switchBatterySaverDsp)
        val tvBatterySaverStatus = findViewById<TextView>(R.id.tvBatterySaverStatus)
        val switchAmoledBlack = findViewById<SwitchCompat>(R.id.switchAmoledBlack)
        val btnThemeNeonGreen = findViewById<MaterialButton>(R.id.btnThemeNeonGreen)
        val btnThemeCyberCyan = findViewById<MaterialButton>(R.id.btnThemeCyberCyan)
        val btnThemeStudioGold = findViewById<MaterialButton>(R.id.btnThemeStudioGold)
        val btnThemeUltraPurple = findViewById<MaterialButton>(R.id.btnThemeUltraPurple)
        val themeBtns = listOf(btnThemeNeonGreen, btnThemeCyberCyan, btnThemeStudioGold, btnThemeUltraPurple)
        val btnOpenFloatingMiniEq = findViewById<MaterialButton>(R.id.btnOpenFloatingMiniEq)
        val btnExportJsonConfig = findViewById<MaterialButton>(R.id.btnExportJsonConfig)
        val btnImportJsonConfig = findViewById<MaterialButton>(R.id.btnImportJsonConfig)

        // קטגוריה 2: איכות ו-AI
        val switchAiEngine = findViewById<SwitchCompat>(R.id.switchAiSongEngine)
        val tvAiSubStatus = findViewById<TextView>(R.id.tvAiEngineSubStatus)
        val btnAiAuto = findViewById<MaterialButton>(R.id.btnAiModeAuto)
        val btnAiBass = findViewById<MaterialButton>(R.id.btnAiModeBass)
        val btnAiVocal = findViewById<MaterialButton>(R.id.btnAiModeVocal)
        val btnAiConcert = findViewById<MaterialButton>(R.id.btnAiModeConcert)
        val aiModeBtns = listOf(btnAiAuto, btnAiBass, btnAiVocal, btnAiConcert)
        val seekAiIntensity = findViewById<SeekBar>(R.id.seekAiIntensity)
        val tvAiIntensityVal = findViewById<TextView>(R.id.tvAiIntensityValue)

        val switchVocalDialogueMode = findViewById<SwitchCompat>(R.id.switchVocalDialogueMode)
        val seekVocalDialogueStrength = findViewById<SeekBar>(R.id.seekVocalDialogueStrength)
        val tvVocalDialogueVal = findViewById<TextView>(R.id.tvVocalDialogueVal)

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

        // קטגוריה 4 ו-5: אפקטים ואקולייזר
        val seekBass = findViewById<SeekBar>(R.id.seekBass)
        val tvBassValue = findViewById<TextView>(R.id.tvBassValue)
        val seekVirtualizer = findViewById<SeekBar>(R.id.seekVirtualizer)
        val tvVirtualizerValue = findViewById<TextView>(R.id.tvVirtualizerValue)
        val seekReverb = findViewById<SeekBar>(R.id.seekReverb)
        val tvReverbValue = findViewById<TextView>(R.id.tvReverbValue)

        val seekParametricQ = findViewById<SeekBar>(R.id.seekParametricQ)
        val tvParametricQValue = findViewById<TextView>(R.id.tvParametricQValue)
        val eqContainer = findViewById<LinearLayout>(R.id.eqContainer)

        tvVolumeCardTitle.setOnClickListener {
            HapticEngine.click(this)
            AudioEngine.cycleNextStream(this)
            syncVolumeRangeUI()
            Toast.makeText(this, "ערוץ נבחר: ${AudioEngine.getActiveStreamHebrewLabel()} (לחץ שוב להחלפה)", Toast.LENGTH_SHORT).show()
        }

        btnEnableSystemSlider.setOnClickListener {
            HapticEngine.click(this)
            Toast.makeText(this, "הפעל את 'Sound Booster מחוון ווליום 200%'", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        fun refreshHeaderStatusText() {
            val btStatus = if (AudioEngine.isBluetoothConnected) " | BT מחובר" else ""
            val batTag = if (AudioEngine.autoBatterySaverDspEnabled && AudioEngine.isLowBatteryDetected) " | חיסכון סוללה" else ""
            when {
                !AudioEngine.isBoosterActive -> {
                    tvStatus.text = "מנוע סאונד כבוי ●$btStatus"
                    tvStatus.setTextColor(Color.parseColor("#FF5252"))
                    cardHeader.strokeColor = Color.parseColor("#263042")
                }
                AudioEngine.aiSongEngineEnabled -> {
                    tvStatus.text = "מנוע AI Neural Mastering פעיל ● שדרוג שירים חי$btStatus$batTag"
                    tvStatus.setTextColor(AudioEngine.getPrimaryAccentColor())
                    cardHeader.strokeColor = AudioEngine.getPrimaryAccentColor()
                }
                else -> {
                    tvStatus.text = "מנוע DSP HD פעיל ● מוגן מהתנגשויות שמע$btStatus$batTag"
                    tvStatus.setTextColor(Color.parseColor("#00E5FF"))
                    cardHeader.strokeColor = Color.parseColor("#00E5FF")
                }
            }
        }

        // חיבור בקרי קטגוריה 3: איזון ערוצים L/R, פיצוי ווליום נמוך ומניעת עייפות
        fun updateLRBalanceLabel(prog: Int) {
            val leftPct = if (prog <= 100) 100 else (200 - prog).coerceIn(0, 100)
            val rightPct = if (prog >= 100) 100 else prog.coerceIn(0, 100)
            tvLRBalanceValue.text = when {
                prog == 100 -> "מאוזן במרכז (L: 100% | R: 100%)"
                prog < 100 -> "נטייה לשמאל (L: $leftPct% | R: $rightPct%)"
                else -> "נטייה לימין (L: $leftPct% | R: $rightPct%)"
            }
        }

        seekLRBalance.progress = AudioEngine.lrBalanceProgress
        updateLRBalanceLabel(AudioEngine.lrBalanceProgress)

        seekLRBalance.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                updateLRBalanceLabel(p)
                if (fromUser) {
                    HapticEngine.tick(this@MainActivity)
                    AudioEngine.lrBalanceProgress = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                    AudioEngine.updateSoundQualityDSP()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        btnResetLRBalance.setOnClickListener {
            HapticEngine.click(this)
            AudioEngine.lrBalanceProgress = 100
            seekLRBalance.progress = 100
            updateLRBalanceLabel(100)
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }

        switchLoudnessContour.isChecked = AudioEngine.loudnessContourEnabled
        switchLoudnessContour.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.loudnessContourEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }

        switchAntiFatigue.isChecked = AudioEngine.antiFatigueModeEnabled
        switchAntiFatigue.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.antiFatigueModeEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }

        // חיבור בקרי קטגוריה 6: הגנות ניתוק אוזניות ובלוטות'
        switchAutoRestoreUnplug.isChecked = AudioEngine.autoRestoreSpeakerOnUnplug
        switchAutoRestoreUnplug.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.autoRestoreSpeakerOnUnplug = isChecked
            AudioEngine.saveEngineModes(this)
        }

        switchBtRangeGuard.isChecked = AudioEngine.btRangeDropGuardEnabled
        switchBtRangeGuard.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.btRangeDropGuardEnabled = isChecked
            AudioEngine.saveEngineModes(this)
        }

        // חיבור בקרי קטגוריה 7: סוללה, AMOLED, צבעי הדגש, Mini-EQ צף וגיבוי JSON
        switchBatterySaverDsp.isChecked = AudioEngine.autoBatterySaverDspEnabled
        switchBatterySaverDsp.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.autoBatterySaverDspEnabled = isChecked
            AudioEngine.checkBatteryLevelState(this)
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
            refreshHeaderStatusText()
        }

        switchAmoledBlack.isChecked = AudioEngine.amoledBlackThemeEnabled
        switchAmoledBlack.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.amoledBlackThemeEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            applyVisualThemeColors()
        }

        for (tIdx in themeBtns.indices) {
            themeBtns[tIdx].setOnClickListener {
                HapticEngine.click(this)
                AudioEngine.accentThemeIndex = tIdx
                AudioEngine.saveEngineModes(this)
                applyVisualThemeColors()
                refreshHeaderStatusText()
            }
        }

        btnOpenFloatingMiniEq.setOnClickListener {
            HapticEngine.click(this)
            val svc = VolumeOverlayService.instance
            if (svc != null) {
                svc.toggleFloatingMiniEqPopup()
            } else {
                Toast.makeText(this, "הפעל תחילה את שירות המחוון בהגדרות הנגישות", Toast.LENGTH_SHORT).show()
            }
        }

        btnExportJsonConfig.setOnClickListener {
            HapticEngine.click(this)
            val json = AudioEngine.exportConfigurationToJson()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("SoundBooster_Config", json))
            AlertDialog.Builder(this)
                .setTitle("גיבוי JSON הועתק ללוח!")
                .setMessage(json)
                .setPositiveButton("שתף גיבוי") { _, _ ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, json)
                    }
                    startActivity(Intent.createChooser(shareIntent, "שתף גיבוי הגדרות Sound Booster"))
                }
                .setNegativeButton("סגור", null)
                .show()
        }

        btnImportJsonConfig.setOnClickListener {
            HapticEngine.click(this)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipTxt = cm?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            val inputEdit = EditText(this).apply {
                setText(clipTxt)
                hint = "הדבק כאן טקסט גיבוי JSON..."
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                setPadding(36, 28, 36, 28)
            }
            AlertDialog.Builder(this)
                .setTitle("שחזור הגדרות מגיבוי JSON")
                .setView(inputEdit)
                .setPositiveButton("שחזר והחל כעת") { _, _ ->
                    val ok = AudioEngine.importConfigurationFromJson(this, inputEdit.text.toString())
                    if (ok) {
                        HapticEngine.heavyMilestone(this)
                        recreate()
                        Toast.makeText(this, "כל ההגדרות שוחזרו בהצלחה!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "קובץ ה-JSON אינו תקין", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("ביטול", null)
                .show()
        }

        // חיבור מתגי רטט, מונו חכם ובועית צפה נגררת
        switchHapticFeedback.isChecked = AudioEngine.hapticFeedbackEnabled
        switchHapticFeedback.setOnCheckedChangeListener { _, isChecked ->
            AudioEngine.hapticFeedbackEnabled = isChecked
            if (isChecked) HapticEngine.click(this)
            AudioEngine.saveEngineModes(this)
        }

        switchMonoDownmix.isChecked = AudioEngine.monoDownmixEnabled
        switchMonoDownmix.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.monoDownmixEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }

        switchAlwaysShowMiniPill.isChecked = AudioEngine.alwaysShowDraggableMiniPill
        switchAlwaysShowMiniPill.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.alwaysShowDraggableMiniPill = isChecked
            AudioEngine.saveEngineModes(this)
            VolumeOverlayService.instance?.showNativeBadgeIfNeeded()
        }

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
                HapticEngine.click(this)
                AudioEngine.selectedRoutingProfileMode = rIdx
                AudioEngine.saveEngineModes(this)
                AudioEngine.updateSoundQualityDSP()
                AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, false)
                updateRoutingProfileButtonsUI()
                syncVolumeRangeUI()
            }
        }

        fun updatePerAppSlotUI() {
            val slot = AudioEngine.selectedAppSlotIndex.coerceIn(0, 4)
            for (i in appSlotBtns.indices) {
                val active = (i == slot)
                appSlotBtns[i].strokeColor = ColorStateList.valueOf(
                    if (active) Color.parseColor("#00E676") else Color.parseColor("#3A465E")
                )
                appSlotBtns[i].setTextColor(
                    if (active) Color.parseColor("#00E676") else Color.parseColor("#A0AAB8")
                )
            }
            val appName = AudioEngine.appCategoryNames[slot]
            val savedVol = AudioEngine.perAppVolumes[slot]
            tvPerAppTargetLabel.text = "עוצמה ייעודית עבור $appName:"
            tvPerAppTargetValue.text = "$savedVol%"
            seekPerAppTargetVol.progress = savedVol
        }

        switchPerAppVolume.isChecked = AudioEngine.perAppVolumeEnabled
        updatePerAppSlotUI()

        switchPerAppVolume.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.perAppVolumeEnabled = isChecked
            AudioEngine.saveEngineModes(this)
        }

        for (slotIdx in appSlotBtns.indices) {
            appSlotBtns[slotIdx].setOnClickListener {
                HapticEngine.click(this)
                AudioEngine.selectedAppSlotIndex = slotIdx
                updatePerAppSlotUI()
            }
        }

        seekPerAppTargetVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvPerAppTargetValue.text = "$p%"
                if (fromUser) {
                    HapticEngine.tick(this@MainActivity)
                    val slot = AudioEngine.selectedAppSlotIndex.coerceIn(0, 4)
                    AudioEngine.perAppVolumes[slot] = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekAmbientSensitivity.progress = AudioEngine.ambientSensitivityPercent
        tvAmbientSensitivityVal.text = "${AudioEngine.ambientSensitivityPercent}%"
        switchAmbientNoiseBoost.isChecked = AudioEngine.ambientNoiseBoostEnabled

        switchAmbientNoiseBoost.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            if (isChecked && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 202)
                switchAmbientNoiseBoost.isChecked = false
                return@setOnCheckedChangeListener
            }
            AudioEngine.setAmbientNoiseMonitor(this, isChecked)
            if (!isChecked) {
                tvAmbientNoiseInfo.text = "דוגם את עוצמת הרעש בסביבה ומגביר דינמית את השמע ברחוב או באוטובוס"
            }
        }

        seekAmbientSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvAmbientSensitivityVal.text = "$p%"
                if (fromUser) {
                    HapticEngine.tick(this@MainActivity)
                    AudioEngine.ambientSensitivityPercent = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        AudioEngine.onAmbientNoiseUpdateCallback = { dbLevel, bonusPct ->
            if (AudioEngine.ambientNoiseBoostEnabled) {
                tvAmbientNoiseInfo.text = "רעש סביבה חי: ~${dbLevel} dB | תוספת הגברה אוטומטית: +${bonusPct}%"
            }
        }

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
                HapticEngine.click(this)
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
            HapticEngine.click(this)
            AudioEngine.aiSongEngineEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
            updateAiButtonsUI()
            refreshHeaderStatusText()
        }

        for (modeIdx in aiModeBtns.indices) {
            aiModeBtns[modeIdx].setOnClickListener {
                HapticEngine.click(this)
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
                HapticEngine.tick(this@MainActivity)
                tvAiIntensityVal.text = "$p%"
                AudioEngine.aiNeuralIntensity = p
                AudioEngine.saveEngineModes(this@MainActivity)
                AudioEngine.updateSoundQualityDSP()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchVocalDialogueMode.isChecked = AudioEngine.vocalDialogueModeEnabled
        seekVocalDialogueStrength.progress = AudioEngine.vocalDialogueStrength
        tvVocalDialogueVal.text = "${AudioEngine.vocalDialogueStrength}%"

        switchVocalDialogueMode.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.vocalDialogueModeEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }
        seekVocalDialogueStrength.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvVocalDialogueVal.text = "$p%"
                if (fromUser) {
                    HapticEngine.tick(this@MainActivity)
                    AudioEngine.vocalDialogueStrength = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                    AudioEngine.updateSoundQualityDSP()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchPersonalCalibration.isChecked = AudioEngine.personalCalibrationEnabled
        switchPersonalCalibration.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.personalCalibrationEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }

        fun runToneCalibrationStep(bandIndex: Int, freqHz: Int, label: String) {
            HapticEngine.click(this)
            tvCalibrationStatus.text = "משמיע צליל בדיקה ב-$label ($freqHz Hz)..."
            ToneCalibrationEngine.playCalibrationTone(freqHz) {
                val currentBoost = AudioEngine.personalEarOffsetsDb[bandIndex]
                val nextBoost = if (currentBoost >= 4.0f) 0.5f else currentBoost + 1.0f
                AudioEngine.personalEarOffsetsDb[bandIndex] = nextBoost
                AudioEngine.personalCalibrationEnabled = true
                switchPersonalCalibration.isChecked = true
                AudioEngine.saveEngineModes(this)
                AudioEngine.updateSoundQualityDSP()
                tvCalibrationStatus.text = "כויל $label: פיצוי שמיעה +${String.format("%.1f", nextBoost)} dB"
                HapticEngine.heavyMilestone(this)
            }
        }

        btnToneTestLow.setOnClickListener { runToneCalibrationStep(0, 250, "נמוכים 250Hz") }
        btnToneTestMid.setOnClickListener { runToneCalibrationStep(1, 1000, "אמצע 1kHz") }
        btnToneTestHigh.setOnClickListener { runToneCalibrationStep(2, 4000, "גבוהים 4kHz") }

        switchMultiBandLimiter.isChecked = AudioEngine.multiBandLimiterEnabled
        seekMbLow.progress = AudioEngine.mbLowControl
        tvMbLowVal.text = "${AudioEngine.mbLowControl}%"
        seekMbMid.progress = AudioEngine.mbMidControl
        tvMbMidVal.text = "${AudioEngine.mbMidControl}%"
        seekMbHigh.progress = AudioEngine.mbHighControl
        tvMbHighVal.text = "${AudioEngine.mbHighControl}%"

        switchMultiBandLimiter.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.multiBandLimiterEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }
        seekMbLow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvMbLowVal.text = "$p%"
                if (fromUser) {
                    HapticEngine.tick(this@MainActivity)
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
                    HapticEngine.tick(this@MainActivity)
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
                    HapticEngine.tick(this@MainActivity)
                    AudioEngine.mbHighControl = p
                    AudioEngine.saveEngineModes(this@MainActivity)
                    AudioEngine.updateSoundQualityDSP()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.setEnabled(isChecked)
            syncVolumeRangeUI()
            refreshHeaderStatusText()
        }

        switchAllow300.isChecked = AudioEngine.allow300PercentBoost
        switchAllow300.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.allow300PercentBoost = isChecked
            AudioEngine.saveEngineModes(this)
            syncVolumeRangeUI()
            refreshHeaderStatusText()
        }

        switchHearingSafeGuard.isChecked = AudioEngine.hearingSafeGuardEnabled
        switchHearingSafeGuard.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
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
                    HapticEngine.tick(this@MainActivity)
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
                    else -> AudioEngine.getPrimaryAccentColor()
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
            HapticEngine.click(this)
            seekTotalVolume.progress = 0
            AudioEngine.applyVolume(this, 0, false)
        }
        findViewById<MaterialButton>(R.id.btnVol100).setOnClickListener {
            HapticEngine.click(this)
            seekTotalVolume.progress = 100
            AudioEngine.applyVolume(this, 100, false)
        }
        findViewById<MaterialButton>(R.id.btnVol150).setOnClickListener {
            HapticEngine.click(this)
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            val target = 150.coerceAtMost(AudioEngine.getEffectiveMaxVolume())
            seekTotalVolume.progress = target
            AudioEngine.applyVolume(this, target, false)
        }
        findViewById<MaterialButton>(R.id.btnVol200).setOnClickListener {
            HapticEngine.click(this)
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            val target = 200.coerceAtMost(AudioEngine.getEffectiveMaxVolume())
            seekTotalVolume.progress = target
            AudioEngine.applyVolume(this, target, false)
        }
        btnVol300.setOnClickListener {
            HapticEngine.heavyMilestone(this)
            AudioEngine.activeStreamType = 0
            syncVolumeRangeUI()
            if (AudioEngine.allow300PercentBoost) {
                val target = 300.coerceAtMost(AudioEngine.getEffectiveMaxVolume())
                seekTotalVolume.progress = target
                AudioEngine.applyVolume(this, target, false)
            }
        }

        switchAntiClip.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.antiClippingEnabled = isChecked
            AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume, false)
        }
        switchHiRes.setOnCheckedChangeListener { _, isChecked ->
            HapticEngine.click(this)
            AudioEngine.hiResOptimizerEnabled = isChecked
            AudioEngine.updateSoundQualityDSP()
        }
        seekClarity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                HapticEngine.tick(this@MainActivity)
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
                HapticEngine.tick(this@MainActivity)
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
            HapticEngine.click(this)
            AudioEngine.songRemasterEnabled = isChecked
            AudioEngine.saveEngineModes(this)
            AudioEngine.updateSoundQualityDSP()
        }
        seekSongUpscale.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                HapticEngine.tick(this@MainActivity)
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
                HapticEngine.tick(this@MainActivity)
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
                HapticEngine.tick(this@MainActivity)
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
                    HapticEngine.tick(this@MainActivity)
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
                    HapticEngine.tick(this@MainActivity)
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
                    HapticEngine.click(this@MainActivity)
                    runCatching { AudioEngine.presetReverb?.preset = progress.toShort() }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        seekParametricQ.progress = AudioEngine.parametricQFactorProgress
        val initQ = String.format("%.1f", 0.4f + (AudioEngine.parametricQFactorProgress / 10f))
        tvParametricQValue.text = "Q: $initQ"

        seekParametricQ.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val qVal = String.format("%.1f", 0.4f + (p / 10f))
                tvParametricQValue.text = "Q: $qVal"
                if (fromUser) {
                    HapticEngine.tick(this@MainActivity)
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
                            HapticEngine.tick(this@MainActivity)
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
            HapticEngine.click(this)
            applyPreset(listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), bass = 0, surround = 0, reverb = 0)
        }
        findViewById<MaterialButton>(R.id.btnPresetBass).setOnClickListener {
            HapticEngine.click(this)
            applyPreset(listOf(9, 8, 6, 3, 0, 0, 1, 2, 3, 4), bass = 700, surround = 250, reverb = 1)
        }
        findViewById<MaterialButton>(R.id.btnPresetVocal).setOnClickListener {
            HapticEngine.click(this)
            applyPreset(listOf(-3, -2, 0, 2, 5, 7, 7, 5, 3, 2), bass = 100, surround = 200, reverb = 1)
        }
        findViewById<MaterialButton>(R.id.btnPresetGaming).setOnClickListener {
            HapticEngine.click(this)
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
                    HapticEngine.tick(this)
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
// 3. שירות הגברת עוצמת הקול דרך מחוון המערכת + בועית HD נגררת + חלון Mini-EQ צף
// =========================================================================
class VolumeOverlayService : AccessibilityService() {

    companion object {
        var instance: VolumeOverlayService? = null
    }

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var maxSysVol = 15

    private var badgeView: TextView? = null
    private var badgeParams: WindowManager.LayoutParams? = null
    private var miniEqPopupView: View? = null

    private var dragInitialX = 0
    private var dragInitialY = 0
    private var dragTouchStartX = 0f
    private var dragTouchStartY = 0f
    private var lastBadgeTapTime = 0L

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable {
        if (!AudioEngine.alwaysShowDraggableMiniPill) {
            removeBadge()
        }
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
            if (AudioEngine.alwaysShowDraggableMiniPill) {
                showNativeBadgeIfNeeded()
            }
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
                HapticEngine.click(this)
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

                if (isVolUpPressed && isVolDownPressed && abs(volUpDownTimestamp - volDownDownTimestamp) < 250L) {
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
        HapticEngine.tick(this)
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

    // חלון מיני-אקולייזר צף מהיר (Bubble Mini-EQ Overlay) מעל אפליקציות
    fun toggleFloatingMiniEqPopup() {
        val wm = windowManager ?: return
        if (miniEqPopupView != null) {
            removeFloatingMiniEqPopup()
            return
        }

        val accent = AudioEngine.getPrimaryAccentColor()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2121722"))
                cornerRadius = dp(22f).toFloat()
                setStroke(dp(2f), accent)
            }
        }

        val title = TextView(this).apply {
            text = "⚡ MINI-EQ & BOOST (${AudioEngine.currentTotalVolume}%)"
            setTextColor(accent)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10f))
        }
        container.addView(title)

        // מחוון ווליום מהיר בחלון הצף
        val seekQuickVol = SeekBar(this).apply {
            max = AudioEngine.getEffectiveMaxVolume()
            progress = AudioEngine.currentTotalVolume
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        HapticEngine.tick(this@VolumeOverlayService)
                        AudioEngine.applyVolume(this@VolumeOverlayService, p, showNativeSystemUI = false)
                        title.text = "⚡ MINI-EQ & BOOST ($p%)"
                        showNativeBadgeIfNeeded()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        container.addView(seekQuickVol)

        val modesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, dp(6f))
        }

        val quickProfiles = listOf("AI Auto" to 0, "Bass+" to 1, "Vocal" to 2, "3D Live" to 3)
        for ((lbl, modeIdx) in quickProfiles) {
            val btn = TextView(this).apply {
                text = lbl
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
                setTextColor(if (AudioEngine.aiPresetMode == modeIdx) Color.parseColor("#0B0E14") else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(if (AudioEngine.aiPresetMode == modeIdx) accent else Color.parseColor("#263042"))
                    cornerRadius = dp(12f).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(4f)
                }
                setOnClickListener {
                    HapticEngine.click(this@VolumeOverlayService)
                    AudioEngine.aiPresetMode = modeIdx
                    AudioEngine.aiSongEngineEnabled = true
                    AudioEngine.saveEngineModes(this@VolumeOverlayService)
                    AudioEngine.updateSoundQualityDSP()
                    removeFloatingMiniEqPopup()
                    toggleFloatingMiniEqPopup()
                }
            }
            modesRow.addView(btn)
        }
        container.addView(modesRow)

        val closeBtn = TextView(this).apply {
            text = "✕ סגור חלון צף"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FF5252"))
            setPadding(0, dp(8f), 0, 0)
            setOnClickListener {
                HapticEngine.click(this@VolumeOverlayService)
                removeFloatingMiniEqPopup()
            }
        }
        container.addView(closeBtn)

        val params = WindowManager.LayoutParams(
            dp(290f),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        miniEqPopupView = container
        runCatching { wm.addView(miniEqPopupView, params) }
    }

    private fun removeFloatingMiniEqPopup() {
        miniEqPopupView?.let {
            runCatching { windowManager?.removeView(it) }
            miniEqPopupView = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showNativeBadgeIfNeeded() {
        hideHandler.removeCallbacks(hideRunnable)
        if (AudioEngine.currentTotalVolume <= 100 && !AudioEngine.alwaysShowDraggableMiniPill) {
            removeBadge()
            return
        }
        val wm = windowManager ?: return
        val accent = AudioEngine.getPrimaryAccentColor()

        if (badgeView == null) {
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
                x = AudioEngine.pillPosX
                y = AudioEngine.pillPosY
            }
            badgeParams = params

            badgeView = TextView(this).apply {
                gravity = Gravity.CENTER
                elevation = dp(8f).toFloat()
                // גרירה חופשית + לחיצה להחלפת ערוץ + לחיצה כפולה לפתיחת Mini-EQ צף!
                setOnTouchListener { v, ev ->
                    val lp = badgeParams ?: return@setOnTouchListener false
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            hideHandler.removeCallbacks(hideRunnable)
                            dragInitialX = lp.x
                            dragInitialY = lp.y
                            dragTouchStartX = ev.rawX
                            dragTouchStartY = ev.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (dragTouchStartX - ev.rawX).roundToInt()
                            val dy = (ev.rawY - dragTouchStartY).roundToInt()
                            lp.x = dragInitialX + dx
                            lp.y = dragInitialY + dy
                            AudioEngine.pillPosX = lp.x
                            AudioEngine.pillPosY = lp.y
                            runCatching { wm.updateViewLayout(v, lp) }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val movedDist = abs(ev.rawX - dragTouchStartX) + abs(ev.rawY - dragTouchStartY)
                            if (movedDist < dp(10f)) {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastBadgeTapTime < 360L) {
                                    HapticEngine.heavyMilestone(this@VolumeOverlayService)
                                    toggleFloatingMiniEqPopup()
                                } else {
                                    HapticEngine.click(this@VolumeOverlayService)
                                    AudioEngine.cycleNextStream(this@VolumeOverlayService)
                                    showNativeBadgeIfNeeded()
                                    Toast.makeText(
                                        this@VolumeOverlayService,
                                        "ערוץ: ${AudioEngine.getActiveStreamHebrewLabel()} (לחיצה כפולה = Mini-EQ)",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                lastBadgeTapTime = now
                            } else {
                                HapticEngine.tick(this@VolumeOverlayService)
                                AudioEngine.saveEngineModes(this@VolumeOverlayService)
                            }
                            if (!AudioEngine.alwaysShowDraggableMiniPill) {
                                hideHandler.postDelayed(hideRunnable, 2800)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            runCatching { wm.addView(badgeView, params) }
        }

        val padH = dp(16f)
        val padV = dp(9f)
        val btMark = if (AudioEngine.isBluetoothConnected) " ᛒ" else ""
        val safeGuardMark = if (AudioEngine.hearingSafeGuardEnabled && AudioEngine.isHeadphonesActive()) " 🛡️" else ""
        val dlgMark = if (AudioEngine.vocalDialogueModeEnabled) " 🎙️" else ""
        badgeView?.apply {
            setPadding(padH, padV, padH, padV)
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(if (AudioEngine.amoledBlackThemeEnabled) Color.BLACK else Color.parseColor("#161B26"))
                cornerRadius = dp(24f).toFloat()
                setStroke(dp(2f), accent)
            }
            setTextColor(accent)
            text = "${AudioEngine.currentTotalVolume}% HD$btMark$safeGuardMark$dlgMark"
        }
        if (!AudioEngine.alwaysShowDraggableMiniPill) {
            hideHandler.postDelayed(hideRunnable, 2800)
        }
    }

    private fun removeBadge() {
        badgeView?.let {
            runCatching { windowManager?.removeView(it) }
            badgeView = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: ""
        if (pkg.isNotEmpty()) {
            if (pkg.contains("systemui", ignoreCase = true)) {
                val cls = event?.className?.toString() ?: ""
                if (cls.contains("Screenshot", ignoreCase = true) || cls.contains("GlobalActions", ignoreCase = true)) {
                    cancelVolumeHold()
                    removeBadge()
                    removeFloatingMiniEqPopup()
                }
            } else if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                AudioEngine.onForegroundAppDetected(this, pkg)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        volumeKeyHandler.removeCallbacksAndMessages(null)
        hideHandler.removeCallbacksAndMessages(null)
        removeBadge()
        removeFloatingMiniEqPopup()
        instance = null
    }
}

// =========================================================================
// 4. אריח הגדרות מהירות בווילון ההתראות (Quick Settings Tile)
// =========================================================================
class BoosterTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        AudioEngine.init(this)
        syncTileVisual()
    }

    override fun onClick() {
        super.onClick()
        HapticEngine.click(this)
        val nextVolume = when {
            !AudioEngine.isBoosterActive -> {
                AudioEngine.setEnabled(true)
                150
            }
            AudioEngine.currentTotalVolume < 150 -> 150
            AudioEngine.currentTotalVolume < 200 -> 200
            else -> 100
        }
        AudioEngine.applyVolume(this, nextVolume, showNativeSystemUI = true)
        VolumeOverlayService.instance?.showNativeBadgeIfNeeded()
        syncTileVisual()
    }

    private fun syncTileVisual() {
        val tile = qsTile ?: return
        tile.state = if (AudioEngine.isBoosterActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Sound Booster (${AudioEngine.currentTotalVolume}%)"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (AudioEngine.currentTotalVolume > 100) "Turbo HD Boost" else "Normal 100%"
        }
        tile.updateTile()
    }
}

// =========================================================================
// 5. מקלט אוטומציה חיצונית (Intent / Tasker / Routines API)
// =========================================================================
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action ?: return
        AudioEngine.init(context)
        when (act) {
            "com.example.soundbooster.ACTION_SET_BOOST" -> {
                val targetVol = intent.getIntExtra("volume", 150)
                val routeMode = intent.getIntExtra("route_profile", -1)
                if (routeMode in 0..4) {
                    AudioEngine.selectedRoutingProfileMode = routeMode
                }
                AudioEngine.setEnabled(true)
                AudioEngine.applyVolume(context, targetVol, showNativeSystemUI = true)
                AudioEngine.saveEngineModes(context)
            }
            "com.example.soundbooster.ACTION_TOGGLE_BOOSTER" -> {
                AudioEngine.setEnabled(!AudioEngine.isBoosterActive)
                AudioEngine.applyVolume(context, AudioEngine.currentTotalVolume, showNativeSystemUI = true)
            }
        }
    }
}
 
