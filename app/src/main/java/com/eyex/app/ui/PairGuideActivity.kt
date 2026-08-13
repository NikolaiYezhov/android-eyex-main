package com.eyex.app.ui
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
class PairGuideActivity : AppCompatActivity() {
    private var didNavigate = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_pair_guide)
        supportActionBar?.hide()
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnStartScan).setOnClickListener {
            if (didNavigate) return@setOnClickListener; didNavigate = true
            startActivity(Intent(this, QCScanActivity::class.java))
        }
    }
    override fun onResume() { super.onResume(); didNavigate = false }
}
