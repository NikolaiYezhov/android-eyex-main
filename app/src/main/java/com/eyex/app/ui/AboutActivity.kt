package com.eyex.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
import com.eyex.app.ble.QCBluetoothManager

class AboutActivity : AppCompatActivity() {

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        supportActionBar?.hide()

        // 返回按钮
        findViewById<ImageView>(R.id.btnBackAbout).setOnClickListener { finish() }

        // 找到需要动态赋值的文本框
        val tvAboutName = findViewById<TextView>(R.id.tvAboutName)
        val tvAboutAppVersion = findViewById<TextView>(R.id.tvAboutAppVersion)
        val tvAboutMac = findViewById<TextView>(R.id.tvAboutMac)

        // 1. 真实获取：当前连接的眼镜信息
        QCBluetoothManager.instance.activeDevice?.let { device ->
            tvAboutName.text = device.name ?: "未知设备"
            tvAboutMac.text = device.address
        } ?: run {
            tvAboutName.text = "未连接设备"
            tvAboutMac.text = "--:--:--:--:--:--"
        }

        // 2. 真实获取：当前手机 App 的版本号
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            tvAboutAppVersion.text = packageInfo.versionName
        } catch (e: Exception) {
            tvAboutAppVersion.text = "获取失败"
        }

        // （注：至于眼镜的固件版本、WIFI版本等，由于目前没有硬件通信协议，暂时用 XML 里的假数据占位显示。等拿到协议后调用蓝牙特征值读取替换即可）
    }
}