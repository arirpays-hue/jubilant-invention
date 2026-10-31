package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
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

// 1. מנוע סאונד מרכזי מוגן מקריסות עם שמירת נתונים
object AudioEngine {
    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null
    var presetReverb: PresetReverb? = null

    var currentTotalVolume: Int = 100
    var isBoosterActive: Boolean = true

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
        context?.let {
            val prefs = it.getSharedPreferences("sound_booster_prefs", Context.MODE_PRIVATE)
            currentTotalVolume = prefs.getInt("saved_vol", currentTotalVolume)
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

    fun applyVolume(context: Context, totalPercent: Int) {
        init()
        currentTotalVolume = totalPercent.coerceIn(0, 200)
        context.getSharedPreferences("sound_booster_prefs", Context.MODE_PRIVATE)
            .edit().putInt("saved_vol", currentTotalVolume).apply()

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        if (currentTotalVolume <= 100) {
            val sysTarget = ((currentTotalVolume / 100f) * maxSysVol).roundToInt()
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysTarget, 0) }
            runCatching { loudnessEnhancer?.setTargetGain(0) }
        } else {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSysVol, 0) }
            val extra = currentTotalVolume - 100
            runCatching {
                loudnessEnhancer?.enabled = true
                loudnessEnhancer?.setTargetGain(extra * 30)
            }
        }
    }

    fun isBluetoothOutputActive(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching {
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }
        }.getOrDefault(false)
    }
}

// 2. המסך הראשי של האפליקציה
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

        setupModernUI()
    }

    override fun onResume() {
        super.onResume()
        updateServiceButtonState()
        seekTotalVolume.progress = AudioEngine.currentTotalVolume
    }

    private fun updateServiceButtonState() {
        if (VolumeOverlayService.instance != null) {
            btnEnableSystemSlider.text = "✅ מחוון הווליום המערכתי 1:1 פעיל!"
            btnEnableSystemSlider.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#00E676"))
        } else {
            btnEnableSystemSlider.text = "הפעל מחוון ווליום מערכתי בהגדרות"
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
                tvStatus.text = "מנוע סאונד פעיל ●"
                tvStatus.setTextColor(Color.parseColor("#00E676"))
                cardHeader.strokeColor = Color.parseColor("#00E676")
            } else {
                tvStatus.text = "מנוע סאונד כבוי ●"
                tvStatus.setTextColor(Color.parseColor("#FF5252"))
                cardHeader.strokeColor = Color.parseColor("#263042")
                if (seekTotalVolume.progress > 100) {
                    seekTotalVolume.progress = 100
                }
            }
        }

        seekTotalVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvTotalVolume.text = "$progress%"
                progressTotalVolume.progress = progress

                AudioEngine.applyVolume(this@MainActivity, progress)

                if (progress <= 100) {
                    tvSubBoostInfo.text = "ווליום מערכת: $progress% | הגברת אקסטרה: כבויה"
                } else {
                    val extraPercent = progress - 100
                    val db = String.format("%.1f", extraPercent * 0.3f)
                    tvSubBoostInfo.text = "מערכת: 100% | תוספת אקסטרה: +$db dB (+$extraPercent%)"
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
                        setPadding(0, 16, 0, 4)
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
            applyPreset(listOf(85, 55, 0, -10, 25), bass = 850, surround = 300, reverb = 1)
        }
        findViewById<MaterialButton>(R.id.btnPresetVocal).setOnClickListener {
            applyPreset(listOf(-30, 15, 75, 65, 30), bass = 100, surround = 200, reverb = 2)
        }
        findViewById<MaterialButton>(R.id.btnPresetGaming).setOnClickListener {
            applyPreset(listOf(50, 20, -15, 60, 80), bass = 600, surround = 950, reverb = 4)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (VolumeOverlayService.instance == null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    seekTotalVolume.progress = (seekTotalVolume.progress + 8).coerceAtMost(200)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    seekTotalVolume.progress = (seekTotalVolume.progress - 8).coerceAtLeast(0)
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

// 3. שירות המחוון המערכתי הצף - העתק 100% מדויק של SystemUI של המכשיר
class VolumeOverlayService : AccessibilityService() {

    companion object {
        var instance: VolumeOverlayService? = null
    }

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var maxSysVol = 15

    private var overlayView: View? = null
    private var nativeSliderView: NativeSystemSliderView? = null
    private var ringerIconView: RingerIconView? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { removeOverlay() }

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
                val step = 8
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    val sysVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxSysVol
                    if (AudioEngine.currentTotalVolume < 100) {
                        AudioEngine.currentTotalVolume = ((sysVol.toFloat() / maxSysVol) * 100).roundToInt()
                    }
                    AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume + step).coerceAtMost(200)
                } else {
                    AudioEngine.currentTotalVolume = (AudioEngine.currentTotalVolume - step).coerceAtLeast(0)
                }

                AudioEngine.applyVolume(this, AudioEngine.currentTotalVolume)
                showSystemStyleHUD()
            }
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun showSystemStyleHUD() {
        hideHandler.removeCallbacks(hideRunnable)

        if (overlayView == null) {
            createSystemEqualOverlay()
        }

        updateSystemHUD()
        hideHandler.postDelayed(hideRunnable, 3200)
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).roundToInt()
    }

    // גווני ה-SystemUI המדויקים מצילום המסך של המכשיר
    private fun getExactSystemColors(): Triple<Int, Int, Int> {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (isNight) {
            Triple(
                Color.parseColor("#252930"), // רקע קפסולה כהה
                Color.parseColor("#4B728F"), // כחול-פלדה מערכתי
                Color.parseColor("#383E47")  // מסילה פנימית
            )
        } else {
            Triple(
                Color.parseColor("#EEF2F6"), // רקע קפסולה לבן-כחלחל זהה למקור
                Color.parseColor("#3B607A"), // כחול-פלדה מדויק מצילום המסך המקורי
                Color.parseColor("#DCE2E8")  // מסילה פנימית בהירה
            )
        }
    }

    private fun createSystemEqualOverlay() {
        val wm = windowManager ?: return
        val (pillBgColor, accentColor, trackBgColor) = getExactSystemColors()

        // עוטף חיצוני שמכיל את הקפסולה הראשית + הכפתור העגול הנפרד מתחתיה (כמו במקור!)
        val outerWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // 1. הקפסולה הראשית בצד ימין
        val mainPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(8f))
            background = GradientDrawable().apply {
                setColor(pillBgColor)
                cornerRadius = dp(32f).toFloat()
            }
            elevation = dp(6f).toFloat()
        }

        // 1A. הכפתור העגול העליון (מחליף מצבי צלצול בלחיצה)
        ringerIconView = RingerIconView(this, accentColor).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42f), dp(42f)).apply {
                bottomMargin = dp(10f)
            }
            setOnClickListener {
                cycleRingerMode()
                hideHandler.removeCallbacks(hideRunnable)
                hideHandler.postDelayed(hideRunnable, 3200)
            }
        }

        // 1B. מחוון הווליום האנכי העבה - ללא קו חוצה באמצע ועם סמל תו + בלוטות'
        nativeSliderView = NativeSystemSliderView(
            context = this,
            activeColor = accentColor,
            trackColor = trackBgColor
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42f), dp(176f))
            onProgressChanged = { newVol ->
                AudioEngine.applyVolume(this@VolumeOverlayService, newVol)
                updateSystemHUD()
                hideHandler.removeCallbacks(hideRunnable)
                hideHandler.postDelayed(hideRunnable, 3200)
            }
        }

        // 1C. שלוש הנקודות בתחתית הקפסולה
        val bottomDotsView = DotsMenuView(this, accentColor).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42f), dp(34f)).apply {
                topMargin = dp(6f)
            }
            setOnClickListener {
                removeOverlay()
                runCatching {
                    // פותח את פאנל הווליום המורחב של אנדרואיד
                    val panelIntent = Intent("android.settings.panel.action.VOLUME").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(panelIntent)
                }.onFailure {
                    runCatching {
                        val appIntent = Intent(this@VolumeOverlayService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(appIntent)
                    }
                }
            }
        }

        mainPill.addView(ringerIconView)
        mainPill.addView(nativeSliderView)
        mainPill.addView(bottomDotsView)

        // 2. הכפתור העגול הנפרד מתחת לקפסולה (בדיוק כמו בצילום המסך המקורי!)
        // לחיצה עליו מפעילה/מכבה Turbo 200% מיידי
        val bottomCaptionCircle = CaptionButtonView(this, pillBgColor, accentColor).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54f), dp(54f)).apply {
                topMargin = dp(10f)
            }
            elevation = dp(6f).toFloat()
            setOnClickListener {
                val nextTarget = if (AudioEngine.currentTotalVolume < 200) 200 else 100
                AudioEngine.applyVolume(this@VolumeOverlayService, nextTarget)
                updateSystemHUD()
                hideHandler.removeCallbacks(hideRunnable)
                hideHandler.postDelayed(hideRunnable, 3200)
            }
        }

        outerWrapper.addView(mainPill)
        outerWrapper.addView(bottomCaptionCircle)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(8f)
        }

        overlayView = outerWrapper
        runCatching { wm.addView(overlayView, params) }
    }

    private fun cycleRingerMode() {
        val am = audioManager ?: return
        runCatching {
            val nextMode = when (am.ringerMode) {
                AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
                else -> AudioManager.RINGER_MODE_NORMAL
            }
            am.ringerMode = nextMode
        }
        ringerIconView?.invalidate()
    }

    private fun updateSystemHUD() {
        val vol = AudioEngine.currentTotalVolume
        val btConnected = AudioEngine.isBluetoothOutputActive(this)
        nativeSliderView?.updateState(vol, btConnected)
        ringerIconView?.invalidate()
    }

    private fun removeOverlay() {
        overlayView?.let {
            runCatching { windowManager?.removeView(it) }
            overlayView = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        instance = null
    }

    // עיגול עליון עם ציור וקטורי נקי של רמקול (כמו ב-SystemUI המקורי)
    @SuppressLint("ViewConstructor")
    inner class RingerIconView(context: Context, private val circleColor: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = width / 2f

            // עיגול רקע כחול-פלדה
            paint.style = Paint.Style.FILL
            paint.color = circleColor
            canvas.drawCircle(cx, cy, r, paint)

            // אייקון רמקול בקו נקי לבן
            paint.style = Paint.Style.STROKE
            paint.color = Color.WHITE
            paint.strokeWidth = dp(1.8f).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            val s = width * 0.22f
            path.reset()
            path.moveTo(cx - s * 0.9f, cy - s * 0.35f)
            path.lineTo(cx - s * 0.4f, cy - s * 0.35f)
            path.lineTo(cx + s * 0.2f, cy - s * 0.85f)
            path.lineTo(cx + s * 0.2f, cy + s * 0.85f)
            path.lineTo(cx - s * 0.4f, cy + s * 0.35f)
            path.lineTo(cx - s * 0.9f, cy + s * 0.35f)
            path.close()
            canvas.drawPath(path, paint)

            val mode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            if (mode == AudioManager.RINGER_MODE_SILENT) {
                canvas.drawLine(cx - s, cy - s, cx + s, cy + s, paint)
            } else {
                val arcRect = RectF(cx + s * 0.1f, cy - s * 0.6f, cx + s * 0.85f, cy + s * 0.6f)
                canvas.drawArc(arcRect, -45f, 90f, false, paint)
            }
        }
    }

    // מחוון הווליום האנכי של המערכת (ללא קו חוצה, עם תו מוזיקלי + סמל בלוטות')
    @SuppressLint("ViewConstructor")
    inner class NativeSystemSliderView(
        context: Context,
        private val activeColor: Int,
        private val trackColor: Int
    ) : View(context) {

        private var volumeProgress = 100
        private var isBluetooth = false
        var onProgressChanged: ((Int) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val btPath = Path()

        fun updateState(value: Int, btConnected: Boolean) {
            volumeProgress = value.coerceIn(0, 200)
            isBluetooth = btConnected
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val radius = w / 2f

            // 1. מסילה מלאה מעוגלת
            paint.style = Paint.Style.FILL
            paint.color = trackColor
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // 2. מילוי אנכי חלק וללא שום קו חוצה באמצע
            val fillRatio = (volumeProgress / 200f).coerceIn(0.22f, 1f)
            val fillTop = h * (1f - fillRatio)

            paint.color = activeColor
            rect.set(0f, fillTop, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // 3. ציור תו מוזיקה + סמל בלוטות' בחלק העליון של המילוי (כמו במקור)
            val iconCenterY = (fillTop + w * 0.52f).coerceAtMost(h - w * 0.48f)
            drawMusicAndBluetoothIcon(canvas, w / 2f, iconCenterY, w)
        }

        private fun drawMusicAndBluetoothIcon(canvas: Canvas, cx: Float, cy: Float, w: Float) {
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL

            val offsetX = if (isBluetooth) -w * 0.08f else 0f
            val noteX = cx + offsetX

            // עיגול תחתון של התו
            canvas.drawCircle(noteX - dp(2f), cy + dp(4f), dp(3.5f).toFloat(), paint)

            // רגל התו ודגלון עליון
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(noteX + dp(1f), cy + dp(4f), noteX + dp(1f), cy - dp(6f), paint)
            canvas.drawLine(noteX + dp(1f), cy - dp(6f), noteX + dp(5.5f), cy - dp(3.5f), paint)

            // סמל בלוטות' קטן מימין לתו (כמו בצילום המסך המקורי של המכשיר!)
            if (isBluetooth) {
                paint.strokeWidth = dp(1.3f).toFloat()
                val bx = cx + w * 0.16f
                val by = cy
                val bs = dp(4.5f).toFloat()

                btPath.reset()
                btPath.moveTo(bx, by - bs)
                btPath.lineTo(bx, by + bs)
                btPath.lineTo(bx + bs * 0.65f, by + bs * 0.5f)
                btPath.lineTo(bx - bs * 0.55f, by - bs * 0.45f)
                btPath.moveTo(bx - bs * 0.55f, by + bs * 0.45f)
                btPath.lineTo(bx + bs * 0.65f, by - bs * 0.5f)
                btPath.lineTo(bx, by - bs)
                canvas.drawPath(btPath, paint)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (height > 0) {
                        val touchRatio = 1f - (event.y / height.toFloat()).coerceIn(0f, 1f)
                        val newVol = (touchRatio * 200).roundToInt()
                        volumeProgress = newVol.coerceIn(0, 200)
                        invalidate()
                        onProgressChanged?.invoke(volumeProgress)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    // שלוש הנקודות האופקיות בתחתית הקפסולה
    @SuppressLint("ViewConstructor")
    inner class DotsMenuView(context: Context, private val dotColor: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = dotColor
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val r = dp(2.2f).toFloat()
            val gap = dp(6.5f).toFloat()

            canvas.drawCircle(cx - gap, cy, r, paint)
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawCircle(cx + gap, cy, r, paint)
        }
    }

    // הכפתור העגול הנפרד מתחת לקפסולה (אייקון Live Caption כמו בצילום המסך המקורי)
    @SuppressLint("ViewConstructor")
    inner class CaptionButtonView(
        context: Context,
        private val bgColor: Int,
        private val iconColor: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val boxRect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f

            // עיגול לבן-אפרפר זהה לקפסולה
            paint.style = Paint.Style.FILL
            paint.color = bgColor
            canvas.drawCircle(cx, cy, width / 2f, paint)

            // אייקון כתוביות עם קו אלכסוני זהה לזה שבצילום המסך המקורי
            paint.style = Paint.Style.STROKE
            paint.color = iconColor
            paint.strokeWidth = dp(1.8f).toFloat()
            paint.strokeCap = Paint.Cap.ROUND

            val bw = dp(16f).toFloat()
            val bh = dp(12f).toFloat()
            boxRect.set(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh / 2f)
            canvas.drawRoundRect(boxRect, dp(2.5f).toFloat(), dp(2.5f).toFloat(), paint)

            // קווים פנימיים וקו אלכסוני
            canvas.drawLine(cx - bw * 0.28f, cy + bh * 0.15f, cx + bw * 0.25f, cy + bh * 0.15f, paint)
            canvas.drawLine(cx - bw * 0.6f, cy - bh * 0.65f, cx + bw * 0.6f, cy + bh * 0.65f, paint)
        }
    }
}
 
