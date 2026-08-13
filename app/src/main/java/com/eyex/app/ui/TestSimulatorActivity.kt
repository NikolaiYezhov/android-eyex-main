package com.eyex.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
import com.eyex.app.ble.TestHelper

class TestSimulatorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_simulator)
        supportActionBar?.hide()
        val tvStatus = findViewById<TextView>(R.id.tvSimStatus)
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnSimEnableMock).setOnClickListener {
            TestHelper.enableMockMode()
            tvStatus.text = "Mock mode ON"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
            Toast.makeText(this, "Mock mode enabled", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnSimDisableMock).setOnClickListener {
            TestHelper.disableMockMode()
            tvStatus.text = "Mock mode OFF"
            tvStatus.setTextColor(0xFFFF5252.toInt())
        }
        findViewById<Button>(R.id.btnSimEnterApp).setOnClickListener {
            TestHelper.enableMockMode()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btnSimScan).setOnClickListener {
            startActivity(Intent(this, QCScanActivity::class.java))
        }
        if (TestHelper.isMockMode()) {
            tvStatus.text = "Mock mode active"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
        }
    }
}
