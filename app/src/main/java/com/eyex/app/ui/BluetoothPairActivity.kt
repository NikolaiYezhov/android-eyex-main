package com.eyex.app.ui
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
class BluetoothPairActivity : AppCompatActivity() {
    companion object { const val EXTRA_DEVICE_NAME = "extra_device_name"; const val EXTRA_DEVICE_MAC = "extra_device_mac" }
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_bluetooth_pair)
        supportActionBar?.hide()
        val name = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "EyeX"; val mac = intent.getStringExtra(EXTRA_DEVICE_MAC) ?: ""
        findViewById<TextView>(R.id.tvDeviceName).text = name; findViewById<TextView>(R.id.tvDeviceMac).text = mac
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnCancel).setOnClickListener { AlertDialog.Builder(this).setTitle("Canceled").setMessage("Connection with $name canceled.").setPositiveButton("OK"){_,_->finish()}.show() }
        findViewById<Button>(R.id.btnPair).setOnClickListener { Toast.makeText(this,"Pairing...",Toast.LENGTH_SHORT).show(); startActivity(Intent(this,MainActivity::class.java)); finish() }
    }
}
