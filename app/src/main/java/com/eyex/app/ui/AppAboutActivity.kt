package com.eyex.app.ui
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R

class AppAboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_about)
        supportActionBar?.hide()
        findViewById<ImageView>(R.id.btnBackAppAbout).setOnClickListener { finish() }

        // 动态读取 `build.gradle` 里的真实版本号
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.tvAppRealVersion).text = "版本号 ${packageInfo.versionName}"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}