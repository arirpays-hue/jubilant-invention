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
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
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
import android.widget.ImageView
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

// 1. מנוע סאונד מרכזי מוגן מקריסות
object AudioEngine {
    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null

    var currentTotalVolume: Int = 100
    var isBoosterActive: Boolean = true

    fun init() {
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
    }

    fun setEnabled(enabled: Boolean) {
        isBoosterActive = enabled
        runCatching { loudnessEnhancer?.enabled = enabled }
        runCatching { equalizer?.enabled = enabled }
        runCatching { bassBoost?.enabled = enabled }
        runCatching { virtualizer?.enabled = enabled }
    }

    fun applyVolume(context: Context, totalPercent: Int) {
        init()
        currentTotalVolume = totalPercent.coerceIn(0, 200)
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

        AudioEngine.init()

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
            btnEnableSystemSlider.text = "✅ מחוון הווליום המערכתי (200%) פעיל!"
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

        val eqContainer = findViewById<LinearLayout>(R.id.eqContainer)

        btnEnableSystemSlider.setOnClickListener {
            Toast.makeText(this, "חפש ברשימה 'Sound Booster מחוון ווליום 200%' והפעל אותו", Toast.LENGTH_LONG).show()
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
            applyPreset(listOf(0, 0, 0, 0, 0), bass = 0, surround = 0)
        }
        findViewById<MaterialButton>(R.id.btnPresetBass).setOnClickListener {
            applyPreset(listOf(85, 55, 0, -10, 25), bass = 850, surround = 300)
        }
        findViewById<MaterialButton>(R.id.btnPresetVocal).setOnClickListener {
            applyPreset(listOf(-30, 15, 75, 65, 30), bass = 100, surround = 200)
        }
        findViewById<MaterialButton>(R.id.btnPresetGaming).setOnClickListener {
            applyPreset(listOf(50, 20, -15, 60, 80), bass = 600, surround = 950)
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

    private fun applyPreset(offsetsPercent: List<Int>, bass: Int, surround: Int) {
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
        }
    }
}

// 3. שירות המחוון המערכתי הצף (באותה גרפיקה של המכשיר עד 200%)
class VolumeOverlayService : AccessibilityService() {

    companion object {
        var instance: VolumeOverlayService? = null
    }

    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null
    private var maxSysVol = 15

    private var overlayView: View? = null
    private var nativeSliderView: NativeSystemSliderView? = null
    private var tvBoostBadge: TextView? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { removeOverlay() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        runCatching {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            maxSysVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

            AudioEngine.init()

            val curSys = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: maxSysVol
            AudioEngine.currentTotalVolume = ((curSys.toFloat() / maxSysVol) * 100).roundToInt()
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
        hideHandler.postDelayed(hideRunnable, 3000)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).roundToInt()
    }

    private fun getSystemColors(): Triple<Int, Int, Int> {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val result = runCatching {
                val pillBg = ContextCompat.getColor(
                    this,
                    if (isNight) android.R.color.system_neutral1_800 else android.R.color.system_neutral1_50
                )
                val accentColor = ContextCompat.getColor(
                    this,
                    if (isNight) android.R.color.system_accent2_300 else android.R.color.system_accent2_600
                )
                val trackBg = ContextCompat.getColor(
                    this,
                    if (isNight) android.R.color.system_neutral2_700 else android.R.color.system_neutral2_200
                )
                Triple(pillBg, accentColor, trackBg)
            }
            if (result.isSuccess) return result.getOrThrow()
        }

        val pillBg = if (isNight) Color.parseColor("#2A2D32") else Color.parseColor("#F2F4F7")
        val accentColor = Color.parseColor("#456173")
        val trackBg = if (isNight) Color.parseColor("#3E434C") else Color.parseColor("#DCE2E8")
        return Triple(pillBg, accentColor, trackBg)
    }

    private fun createSystemEqualOverlay() {
        val wm = windowManager ?: return
        val (pillBgColor, accentColor, trackBgColor) = getSystemColors()

        val rootPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                setColor(pillBgColor)
                cornerRadius = dp(36).toFloat()
            }
            elevation = dp(8).toFloat()
        }

        val topRingerCircle = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                bottomMargin = dp(8)
            }
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
        }

        val ringerIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        topRingerCircle.addView(ringerIcon)

        tvBoostBadge = TextView(this).apply {
            text = "${AudioEngine.currentTotalVolume}%"
            textSize = 11f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }

        nativeSliderView = NativeSystemSliderView(
            context = this,
            activeColor = accentColor,
            trackColor = trackBgColor
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(170))
            onProgressChanged = { newVol ->
                AudioEngine.applyVolume(this@VolumeOverlayService, newVol)
                updateSystemHUD()
                hideHandler.removeCallbacks(hideRunnable)
                hideHandler.postDelayed(hideRunnable, 3000)
            }
        }

        val bottomDotsBtn = TextView(this).apply {
            text = "•••"
            textSize = 16f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(38)).apply {
                topMargin = dp(6)
            }
            setOnClickListener {
                removeOverlay()
                runCatching {
                    val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                }
            }
        }

        rootPill.addView(topRingerCircle)
        rootPill.addView(tvBoostBadge)
        rootPill.addView(nativeSliderView)
        rootPill.addView(bottomDotsBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = dp(10)
        }

        overlayView = rootPill
        runCatching { wm.addView(overlayView, params) }
    }

    private fun updateSystemHUD() {
        val vol = AudioEngine.currentTotalVolume
        nativeSliderView?.setVolumeProgress(vol)
        tvBoostBadge?.text = if (vol > 100) "⚡$vol%" else "$vol%"
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

    @SuppressLint("ViewConstructor")
    inner class NativeSystemSliderView(
        context: Context,
        private val activeColor: Int,
        private val trackColor: Int
    ) : View(context) {

        private var volumeProgress = 100
        var onProgressChanged: ((Int) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun setVolumeProgress(value: Int) {
            volumeProgress = value.coerceIn(0, 200)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val radius = w / 2f

            paint.color = trackColor
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)

            val fillRatio = (volumeProgress / 200f).coerceIn(0.12f, 1f)
            val fillTop = h * (1f - fillRatio)

            paint.color = activeColor
            rect.set(0f, fillTop, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)

            paint.color = Color.argb(70, 255, 255, 255)
            paint.strokeWidth = 3f
            canvas.drawLine(w * 0.25f, h / 2f, w * 0.75f, h / 2f, paint)

            paint.color = Color.WHITE
            paint.textSize = w * 0.42f
            paint.textAlign = Paint.Align.CENTER
            val noteY = (fillTop + w * 0.65f).coerceAtMost(h - w * 0.35f)
            canvas.drawText("♪", w / 2f, noteY, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    if (height > 0) {
                        val touchRatio = 1f - (event.y / height.toFloat()).coerceIn(0f, 1f)
                        val newVol = (touchRatio * 200).roundToInt()
                        setVolumeProgress(newVol)
                        onProgressChanged?.invoke(volumeProgress)
                    }
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
 
