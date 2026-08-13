package com.eyex.app.ui
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R

class FaqActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)
        supportActionBar?.hide()
        findViewById<ImageView>(R.id.btnBackFaq).setOnClickListener { finish() }
    }
}