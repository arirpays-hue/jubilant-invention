package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

// מנוע אודיו מרכזי יחיד שמונע התנגשויות וקריסות בין המסך לשירות הרקע
object AudioEngine {
    var loudnessEnhancer: LoudnessEnhancer? = null
    var equalizer: Equalizer? = null
    var bassBoost: BassBoost? = null
    var virtualizer: Virtualizer? = null

    var currentTotalVolume = 100
    var isBoosterActive = true

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
            // עטיפה מוגנת למניעת קריסת SecurityException של מצב "נא לא להפריע"
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
