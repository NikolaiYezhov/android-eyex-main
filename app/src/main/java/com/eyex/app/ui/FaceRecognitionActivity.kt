package com.eyex.app.ui
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
class FaceRecognitionActivity : AppCompatActivity() {
    private lateinit var tvResult: TextView
    private lateinit var btnStart: Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_recognition)
        supportActionBar?.hide()
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        tvResult = findViewById(R.id.tvResult)
        btnStart = findViewById(R.id.btnStart)
        btnStart.setOnClickListener {
            btnStart.isEnabled = false; tvResult.text = "正在拍摄…"
            Handler(Looper.getMainLooper()).postDelayed({
                tvResult.text = "人脸已捕获！"
                btnStart.isEnabled = true
            }, 2000)
        }
    }
}
