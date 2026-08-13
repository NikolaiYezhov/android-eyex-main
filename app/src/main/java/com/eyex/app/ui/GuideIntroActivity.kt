package com.eyex.app.ui
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
class GuideIntroActivity : AppCompatActivity() {
    private var didNavigate = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_guide_intro)
        supportActionBar?.hide()
        findViewById<Button>(R.id.btnStartGuide).setOnClickListener { if(!didNavigate){ didNavigate=true; startActivity(Intent(this, GuidePhotoVideoActivity::class.java)) } }
        findViewById<TextView>(R.id.btnSkipIntro).setOnClickListener { finish() }
    }
    override fun onResume() { super.onResume(); didNavigate = false }
}
