package com.eyex.app.ui
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class FaceDiagnosticsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_face_diagnostics)
        supportActionBar?.hide()
        val tv=findViewById<TextView>(R.id.tvSummary)
        findViewById<TextView>(R.id.btnBack).setOnClickListener{finish()}
        tv.text="Fetching..."
        Handler(Looper.getMainLooper()).postDelayed({tv.text=buildReport()},2000)
        findViewById<Button>(R.id.btnCopyReport).setOnClickListener{(getSystemService(Context.CLIPBOARD_SERVICE)as ClipboardManager).setPrimaryClip(ClipData.newPlainText("",buildReport()));Toast.makeText(this,"Copied",Toast.LENGTH_SHORT).show()}
        findViewById<Button>(R.id.btnShareReport).setOnClickListener{startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,buildReport())},"Share"))}
    }
    private fun buildReport():String{
        val t=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date())
        return "=== Report ===\nBattery: 68%\nChunks: 12/12\nStatus: Success\nTime: $t"
    }
}
