package com.eyex.app.ui
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R

class BgPermissionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bg_permission)
        supportActionBar?.hide()
        findViewById<ImageView>(R.id.btnBackBg).setOnClickListener { finish() }
    }
}