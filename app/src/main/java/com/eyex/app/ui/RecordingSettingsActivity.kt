package com.eyex.app.ui

import android.os.Bundle
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.eyex.app.R

class RecordingSettingsActivity : AppCompatActivity() {

    private lateinit var tvVideoValue: TextView
    private lateinit var tvAudioValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_settings)
        supportActionBar?.hide()

        findViewById<ImageView>(R.id.btnBackRecord).setOnClickListener { finish() }

        tvVideoValue = findViewById(R.id.tvVideoValue)
        tvAudioValue = findViewById(R.id.tvAudioValue)

        findViewById<RelativeLayout>(R.id.btnVideoDuration).setOnClickListener {
            showBottomSheet("录像时长", arrayOf("15s", "30s", "1min", "3min", "9min\n(录制中会有发热)", "12min\n(录制中会有发热)"), tvVideoValue)
        }

        findViewById<RelativeLayout>(R.id.btnAudioDuration).setOnClickListener {
            showBottomSheet("录音时长", arrayOf("30min", "60min", "120min"), tvAudioValue)
        }
    }

    // 动态生成底部弹窗的魔法方法
    private fun showBottomSheet(title: String, options: Array<String>, targetTextView: TextView) {
        val dialog = BottomSheetDialog(this)

        // 动态创建一个线性布局作为弹窗内容
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#282828"))
            setPadding(0, 40, 0, 40)
        }

        // 弹窗标题
        val titleView = TextView(this).apply {
            text = title
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }
        layout.addView(titleView)

        // 渲染选项列表
        options.forEach { optionText ->
            val isSelected = targetTextView.text.toString().contains(optionText.split("\n")[0])

            val itemTextView = TextView(this).apply {
                text = optionText
                setTextColor(if (isSelected) android.graphics.Color.parseColor("#4A90E2") else android.graphics.Color.WHITE)
                textSize = 16f
                setPadding(60, 40, 60, 40)

                // 画一条分割线
                val border = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#333333"))
                setCompoundDrawablesWithIntrinsicBounds(null, null, if(isSelected) resources.getDrawable(android.R.drawable.checkbox_on_background, null) else null, border)
            }

            itemTextView.setOnClickListener {
                targetTextView.text = "${optionText.split("\n")[0]} >"
                dialog.dismiss()
                // TODO: 在这里把新的时长发给蓝牙 SDK
            }
            layout.addView(itemTextView)
        }

        dialog.setContentView(layout)
        dialog.show()
    }
}