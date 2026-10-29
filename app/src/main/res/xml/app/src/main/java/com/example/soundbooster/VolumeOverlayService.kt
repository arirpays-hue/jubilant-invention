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
import android.media.audiofx.LoudnessEnhancer
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

class VolumeOverlayService : AccessibilityService() {

    companion object {
        var instance: VolumeOverlayService? = null
        var currentTotalVolume = 100 // 0 עד 200
        var isBoosterActive = true
    }

    private lateinit var windowManager: WindowManager
    private lateinit var audioManager: AudioManager
    private var maxSysVol = 15
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private var overlayView: View? = null
    private var nativeSliderView: NativeSystemSliderView? = null
    private var tvBoostBadge: TextView? = null

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { removeOverlay() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as WindowManager
        maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        try {
            loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
        } catch (_: Exception) {}

        val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentTotalVolume = ((curSys.toFloat() / maxSysVol) * 100).roundToInt()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isBoosterActive) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val step = 8
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentTotalVolume < 100) {
                        currentTotalVolume = ((sysVol.toFloat() / maxSysVol) * 100).roundToInt()
                    }
                    currentTotalVolume = (currentTotalVolume + step).coerceAtMost(200)
                } else {
                    currentTotalVolume = (currentTotalVolume - step).coerceAtLeast(0)
                }

                applyVolume(currentTotalVolume)
                showSystemStyleHUD()
            }
            return true
        }
        return super.onKeyEvent(event)
    }

    fun applyVolume(totalPercent: Int) {
        currentTotalVolume = totalPercent.coerceIn(0, 200)

        if (currentTotalVolume <= 100) {
            val sysTarget = ((currentTotalVolume / 100f) * maxSysVol).roundToInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, sysTarget, 0)
            loudnessEnhancer?.setTargetGain(0)
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSysVol, 0)
            val extra = currentTotalVolume - 100
            loudnessEnhancer?.enabled = true
            loudnessEnhancer?.setTargetGain(extra * 30)
        }
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

    // שליפת הצבעים הדינמיים של המכשיר (Material You / System Accent)
    private fun getSystemColors(): Triple<Int, Int, Int> {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // שואב את צבעי המערכת המדויקים של המכשיר (כמו הכחול-אפור שבצילום המסך)
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
        } else {
            val pillBg = if (isNight) Color.parseColor("#2A2D32") else Color.parseColor("#F2F4F7")
            val accentColor = Color.parseColor("#456173") // הגוון המדויק מצילום המסך
            val trackBg = if (isNight) Color.parseColor("#3E434C") else Color.parseColor("#DCE2E8")
            Triple(pillBg, accentColor, trackBg)
        }
    }

    private fun createSystemEqualOverlay() {
        val (pillBgColor, accentColor, trackBgColor) = getSystemColors()

        // הקפסולה הראשית בצד המסך (זהה לעיצוב המערכת)
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

        // 1. הכפתור העגול העליון (מצב שמע / רמקול)
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

        // תווית אחוזים עדינה שמופיעה בתוך הקפסולה (עד 200%)
        tvBoostBadge = TextView(this).apply {
            text = "${currentTotalVolume}%"
            textSize = 11f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }

        // 2. מחוון הווליום האנכי העבה של המערכת
        nativeSliderView = NativeSystemSliderView(
            context = this,
            activeColor = accentColor,
            trackColor = trackBgColor
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(170))
            onProgressChanged = { newVol ->
                applyVolume(newVol)
                updateSystemHUD()
                hideHandler.removeCallbacks(hideRunnable)
                hideHandler.postDelayed(hideRunnable, 3000)
            }
        }

        // 3. כפתור שלוש הנקודות בתחתית (פותח את הגדרות הסאונד או את האפליקציה)
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
                val intent = Intent(Settings.ACTION_SOUND_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
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
            x = dp(10) // מרחק זהה מהקצה הימני כמו במערכת המקורית
        }

        overlayView = rootPill
        try {
            windowManager.addView(overlayView, params)
        } catch (_: Exception) {}
    }

    private fun updateSystemHUD() {
        nativeSliderView?.setVolumeProgress(currentTotalVolume)
        tvBoostBadge?.text = if (currentTotalVolume > 100) "⚡${currentTotalVolume}%" else "${currentTotalVolume}%"
    }

    private fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        loudnessEnhancer?.release()
        instance = null
    }

    // מחלקה פנימית שמציירת את המחוון האנכי העבה של אנדרואיד עם אייקון התו המוזיקלי
    @SuppressLint("ViewConstructor")
    inner class NativeSystemSliderView(
        context: Context,
        private val activeColor: Int,
        private val trackColor: Int
    ) : View(context) {

        private var volumeProgress = 100 // 0 עד 200
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
            val radius = w / 2f

            // 1. מסילת רקע מעוגלת
            paint.color = trackColor
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // 2. מילוי אנכי לפי גובה העוצמה (0 עד 200)
            val fillRatio = (volumeProgress / 200f).coerceIn(0.12f, 1f)
            val fillTop = h * (1f - fillRatio)

            paint.color = activeColor
            rect.set(0f, fillTop, w, h)
            canvas.drawRoundRect(rect, radius, radius, paint)

            // 3. קו סימון עדין באמצע שמייצג את ה-100% המקורי של המערכת
            paint.color = Color.argb(70, 255, 255, 255)
            paint.strokeWidth = 3f
            canvas.drawLine(w * 0.25f, h / 2f, w * 0.75f, h / 2f, paint)

            // 4. אייקון תו מוזיקה (♪) בחלק העליון של המחוון כמו בצילום המסך
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
                    val touchRatio = 1f - (event.y / height.toFloat()).coerceIn(0f, 1f)
                    val newVol = (touchRatio * 200).roundToInt()
                    setVolumeProgress(newVol)
                    onProgressChanged?.invoke(volumeProgress)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
