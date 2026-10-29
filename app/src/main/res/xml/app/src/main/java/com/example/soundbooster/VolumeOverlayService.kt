package com.example.soundbooster

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
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
    private var tvOverlayPercent: TextView? = null
    private var seekOverlay: SeekBar? = null
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { removeOverlay() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxSysVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        try {
            loudnessEnhancer = LoudnessEnhancer(0).apply {
                enabled = true
            }
        } catch (_: Exception) {}

        val curSys = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        currentTotalVolume = ((curSys.toFloat() / maxSysVol) * 100).roundToInt()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isBoosterActive) return super.onKeyEvent(event)

        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val step = 10
                if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    // אם אנחנו עדיין מתחת ל-100%, נסתנכרן עם ווליום המערכת הנוכחי
                    val sysVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentTotalVolume <= 100) {
                        currentTotalVolume = ((sysVol.toFloat() / maxSysVol) * 100).roundToInt()
                    }
                    currentTotalVolume = (currentTotalVolume + step).coerceAtMost(200)
                } else {
                    currentTotalVolume = (currentTotalVolume - step).coerceAtLeast(0)
                }

                applyVolume(currentTotalVolume)
                showVolumeHUD()
            }
            // חוסם את המחוון הרגיל המוגבל ל-100% ומחליף אותו במחוון ה-200% שלנו
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
            loudnessEnhancer?.setTargetGain(extra * 30) // עד +3000mB (+30dB)
        }
    }

    private fun showVolumeHUD() {
        hideHandler.removeCallbacks(hideRunnable)

        if (overlayView == null) {
            createOverlayView()
        }

        updateOverlayUI()
        hideHandler.postDelayed(hideRunnable, 3000) // נעלם אוטומטית אחרי 3 שניות
    }

    private fun createOverlayView() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 36, 28, 36)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6161B26"))
                cornerRadius = 64f
                setStroke(3, Color.parseColor("#00E676"))
            }
        }

        val iconLabel = TextView(this).apply {
            text = "🔊"
            textSize = 20f
            gravity = Gravity.CENTER
        }

        tvOverlayPercent = TextView(this).apply {
            text = "${currentTotalVolume}%"
            setTextColor(Color.parseColor("#00E676"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 16)
        }

        // סליידר שניתן גם לגרור עם האצבע על המסך
        seekOverlay = SeekBar(this).apply {
            max = 200
            progress = currentTotalVolume
            layoutParams = LinearLayout.LayoutParams(260, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        applyVolume(p)
                        updateOverlayUI()
                        hideHandler.removeCallbacks(hideRunnable)
                        hideHandler.postDelayed(hideRunnable, 3000)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val subLabel = TextView(this).apply {
            text = "MAX 200%"
            setTextColor(Color.parseColor("#A0AAB8"))
            textSize = 10f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        container.addView(iconLabel)
        container.addView(tvOverlayPercent)
        container.addView(seekOverlay)
        container.addView(subLabel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24 // צמוד לצד ימין כמו בצילום המסך
        }

        overlayView = container
        try {
            windowManager.addView(overlayView, params)
        } catch (_: Exception) {}
    }

    private fun updateOverlayUI() {
        tvOverlayPercent?.text = "${currentTotalVolume}%"
        seekOverlay?.progress = currentTotalVolume

        val color = when {
            currentTotalVolume > 150 -> Color.parseColor("#FF5252")
            currentTotalVolume > 100 -> Color.parseColor("#FFD740")
            else -> Color.parseColor("#00E676")
        }

        tvOverlayPercent?.setTextColor(color)
        seekOverlay?.progressTintList = ColorStateList.valueOf(color)
        seekOverlay?.thumbTintList = ColorStateList.valueOf(color)
        (overlayView?.background as? GradientDrawable)?.setStroke(3, color)
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
}
