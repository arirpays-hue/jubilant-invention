package com.example.soundbooster

import android.graphics.Color
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    private val eqSliders = mutableListOf<SeekBar>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initAudioEffects()
        setupUI()
    }

    private fun initAudioEffects() {
        try {
            // סשן 0 מחיל את האפקטים על המיקס הגלובלי של המכשיר
            loudnessEnhancer = LoudnessEnhancer(0)
            equalizer = Equalizer(0, 0)
            bassBoost = BassBoost(0, 0)
            virtualizer = Virtualizer(0, 0)
        } catch (e: Exception) {
            Toast.makeText(this, "שגיאה בגישה למנוע הסאונד של המכשיר", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupUI() {
        val switchEnable = findViewById<Switch>(R.id.switchEnable)
        val seekBoost = findViewById<SeekBar>(R.id.seekBoost)
        val tvBoostLabel = findViewById<TextView>(R.id.tvBoostLabel)

        val seekBass = findViewById<SeekBar>(R.id.seekBass)
        val tvBassLabel = findViewById<TextView>(R.id.tvBassLabel)

        val seekVirtualizer = findViewById<SeekBar>(R.id.seekVirtualizer)
        val tvVirtualizerLabel = findViewById<TextView>(R.id.tvVirtualizerLabel)

        val eqContainer = findViewById<LinearLayout>(R.id.eqContainer)

        // הפעלה וכיבוי של כל המנועים
        switchEnable.setOnCheckedChangeListener { _, isChecked ->
            loudnessEnhancer?.enabled = isChecked
            equalizer?.enabled = isChecked
            bassBoost?.enabled = isChecked
            virtualizer?.enabled = isChecked
        }

        // 1. Loudness Enhancer (עד 3000mB שהם 30dB תוספת)
        seekBoost.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvBoostLabel.text = "הגברת ווליום אקסטרה (Boost): $progress%"
                val gainMillibels = progress * 30 // מקסימום 3000mB
                loudnessEnhancer?.setTargetGain(gainMillibels)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 2. Bass Boost (0 עד 1000)
        seekBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = progress / 10
                tvBassLabel.text = "הגברת באס (Bass Boost): $percent%"
                bassBoost?.setStrength(progress.toShort())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3. Virtualizer (0 עד 1000)
        seekVirtualizer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = progress / 10
                tvVirtualizerLabel.text = "אפקט מרחבי (3D Virtualizer): $percent%"
                virtualizer?.setStrength(progress.toShort())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 4. בניית סליידרים לאקולייזר לפי תדרי המכשיר
        equalizer?.let { eq ->
            val bands = eq.numberOfBands
            val minEQ = eq.bandLevelRange[0]
            val maxEQ = eq.bandLevelRange[1]

            for (i in 0 until bands) {
                val band = i.toShort()
                val freqHz = eq.getCenterFreq(band) / 1000

                val label = TextView(this).apply {
                    text = "תדר ${freqHz}Hz"
                    setTextColor(Color.WHITE)
                }

                val seekBar = SeekBar(this).apply {
                    max = maxEQ - minEQ
                    progress = eq.getBandLevel(band) - minEQ
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                            val newLevel = (p + minEQ).toShort()
                            eq.setBandLevel(band, newLevel)
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }

                eqSliders.add(seekBar)
                eqContainer.addView(label)
                eqContainer.addView(seekBar)
            }
        }

        // 5. כפתורי פרופילים (Presets)
        findViewById<Button>(R.id.btnPresetNormal).setOnClickListener {
            applyPreset(listOf(0, 0, 0, 0, 0))
        }
        findViewById<Button>(R.id.btnPresetBass).setOnClickListener {
            applyPreset(listOf(80, 50, 0, -10, 20))
        }
        findViewById<Button>(R.id.btnPresetVocal).setOnClickListener {
            applyPreset(listOf(-30, 10, 70, 60, 20))
        }
    }

    private fun applyPreset(offsetsPercent: List<Int>) {
        val eq = equalizer ?: return
        val minEQ = eq.bandLevelRange[0]
        val maxEQ = eq.bandLevelRange[1]
        val range = maxEQ - minEQ
        val mid = range / 2

        for (i in 0 until minOf(eqSliders.size, offsetsPercent.size)) {
            val offset = (range * offsetsPercent[i]) / 200
            eqSliders[i].progress = (mid + offset).coerceIn(0, range)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // שחרור משאבים בסגירה מלאה
        loudnessEnhancer?.release()
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
    }
}
