package com.eyex.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R

class GameAssistSelectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game_assist_selection)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = 0xFF0F1014.toInt()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnBilliards).setOnClickListener {
            startActivity(Intent(this, BilliardsAssistActivity::class.java))
        }
        findViewById<View>(R.id.btnFaceRecognition).setOnClickListener {
            startActivity(Intent(this, FaceRecognitionActivity::class.java))
        }
    }
}
