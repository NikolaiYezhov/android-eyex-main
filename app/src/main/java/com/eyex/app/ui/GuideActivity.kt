package com.eyex.app.ui
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R

class GuideActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        supportActionBar?.hide()
        findViewById<ImageView>(R.id.btnCloseGuide).setOnClickListener { finish() }
    }
}