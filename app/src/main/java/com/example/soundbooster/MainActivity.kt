package com.example.soundbooster

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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

class MainActivity : AppCompatActivity() {

    private lateinit var audioManager: AudioManager
    private var maxSystemVolume: Int = 15

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private lateinit var seekTotalVolume: SeekBar
    private lateinit var switchEnable: SwitchCompat
    private lateinit var btnEnableSystemSlider: MaterialButton
    private val eqSliders = mutableListOf<SeekBar>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#0B0E14")
        window.navigationBarColor = Color.parseColor("#0B0E14")
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        initAudioEffects()
        setupModernUI()
    }

    override fun onResume() {
        super.onResume()
        updateServiceButtonState()
        seekTotalVolume.progress = VolumeOverlayService.currentTotalVolume
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

    private fun initAudioEffects() {
        try {
            loudnessEnhancer = LoudnessEnhancer(0).apply { enabled = true }
            equalizer = Equalizer(0, 0).apply { enabled = true }
            bassBoost = BassBoost(0, 0).apply { enabled = true }
            virtualizer = Virtualizer(0, 0).apply { enabled = true }
        } catch (e: Exception) {
            Toast.makeText(this, "שגיאה בחיבור למנוע האודיו של המכשיר", Toast.LENGTH_LONG).show()
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
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            VolumeOverlayService.isBoosterActive = isChecked
            loudnessEnhancer?.enabled = isChecked
            equalizer?.enabled = isChecked
            bassBoost?.enabled = isChecked
            virtualizer?.enabled = isChecked

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
                VolumeOverlayService.currentTotalVolume = progress

                if (VolumeOverlayService.instance != null) {
                    VolumeOverlayService.instance?.applyVolume(progress)
                } else {
                    if (progress <= 100) {
                        val targetSysVol = ((progress / 100f) * maxSystemVolume).roundToInt()
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetSysVol, 0)
                        loudnessEnhancer?.setTargetGain(0)
                    } else {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSystemVolume, 0)
                        loudnessEnhancer?.setTargetGain((progress - 100) * 30)
                    }
                }

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
                bassBoost?.setStrength(progress.toShort())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVirtualizerValue.text = "${progress / 10}%"
                virtualizer?.setStrength(progress.toShort())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        equalizer?.let { eq ->
            val bands = eq.numberOfBands
            val minEQ = eq.bandLevelRange[0]
            val maxEQ = eq.bandLevelRange[1]

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
                    max = maxEQ - minEQ
                    progress = eq.getBandLevel(band) - minEQ
                    progressTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))
                    thumbTintList = ColorStateList.valueOf(Color.parseColor("#00E5FF"))

                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            val newLevel = (p + minEQ).toShort()
                            eq.setBandLevel(band, newLevel)
                            val dbVal = newLevel / 100
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

    private fun applyPreset(offsetsPercent: List<Int>, bass: Int, surround: Int) {
        val eq = equalizer ?: return
        val minEQ = eq.bandLevelRange[0]
        val maxEQ = eq.bandLevelRange[1]
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
