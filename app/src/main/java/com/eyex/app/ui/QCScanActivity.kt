package com.eyex.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eyex.app.R
import com.eyex.app.ble.QCBluetoothManager

class QCScanActivity : AppCompatActivity() {

    // 记录发现的设备
    private var targetDevice: BluetoothDevice? = null

    // 缓存视图，方便切换
    private lateinit var layoutStep1: View
    private lateinit var layoutStep2: View
    private lateinit var layoutStep3: View
    private lateinit var layoutStep4: View

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceMac: TextView
    private lateinit var btnConnectFinal: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        supportActionBar?.hide() // 隐藏顶部默认导航栏

        // 1. 找齐 4 个步骤的容器
        layoutStep1 = findViewById(R.id.layoutStep1)
        layoutStep2 = findViewById(R.id.layoutStep2)
        layoutStep3 = findViewById(R.id.layoutStep3)
        layoutStep4 = findViewById(R.id.layoutStep4)

        // 2. 找到所有按钮和文本
        val btnGoStep2 = findViewById<Button>(R.id.btnGoStep2)
        val btnGoStep3 = findViewById<Button>(R.id.btnGoStep3)
        btnConnectFinal = findViewById(R.id.btnConnectFinal)
        tvDeviceName = findViewById(R.id.tvDeviceName)
        tvDeviceMac = findViewById(R.id.tvDeviceMac)

        val btnBackFromStep2 = findViewById<ImageView>(R.id.btnBackFromStep2)
        val btnBackFromStep3 = findViewById<ImageView>(R.id.btnBackFromStep3)
        val btnBackFromStep4 = findViewById<ImageView>(R.id.btnBackFromStep4)

        // 默认显示第 1 步
        showStep(1)

        // 3. 各种按钮的点击事件（步骤切换）
        btnGoStep2.setOnClickListener { showStep(2) }

        // 点击第2步的“下一步”，开始申请权限并搜索
        btnGoStep3.setOnClickListener {
            checkPermissionsAndScan()
        }

        btnBackFromStep2.setOnClickListener { showStep(1) }

        btnBackFromStep3.setOnClickListener {
            QCBluetoothManager.instance.stopScan() // 退出搜索页时停止搜索省电
            showStep(2)
        }

        btnBackFromStep4.setOnClickListener {
            // 如果从第4步返回，重新进入第3步开始搜索
            showStep(3)
            startBleScan()
        }

        // 4. 点击最终的【连接】按钮
        @SuppressLint("MissingPermission")
        btnConnectFinal.setOnClickListener {
            targetDevice?.let { device ->
                btnConnectFinal.text = "正在连接..."
                btnConnectFinal.isEnabled = false

                // 告诉底层开始连接
                QCBluetoothManager.instance.connect(device)
                Toast.makeText(this, "准备连接: ${device.name ?: "智能眼镜"}", Toast.LENGTH_SHORT).show()

                // 延迟 500ms 跳转到主页，让按钮状态变化能被用户看到，体验更好
                it.postDelayed({
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }, 500)
            }
        }
    }

    // 控制页面显示隐藏的核心方法
    private fun showStep(step: Int) {
        layoutStep1.visibility = if (step == 1) View.VISIBLE else View.GONE
        layoutStep2.visibility = if (step == 2) View.VISIBLE else View.GONE
        layoutStep3.visibility = if (step == 3) View.VISIBLE else View.GONE
        layoutStep4.visibility = if (step == 4) View.VISIBLE else View.GONE
    }

    // 复用你之前写好的权限检查逻辑
    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        } else {
            // 权限都有了，跳转第 3 步，开启雷达扫描
            showStep(3)
            startBleScan()
        }
    }

    // 权限弹窗回调
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            showStep(3)
            startBleScan()
        } else {
            Toast.makeText(this, "需要允许蓝牙和定位权限才能扫描设备哦", Toast.LENGTH_SHORT).show()
        }
    }

    // 开始雷达扫描并处理结果
    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        // 先确保初始化了蓝牙单例
        QCBluetoothManager.instance.init(this)

        QCBluetoothManager.instance.startScan { devices ->
            // 我们的目标是：只要搜到列表里有设备（因为你在底层已经过滤了 HeyCyan 名字），就直接抓取第一个！
            if (devices.isNotEmpty()) {
                val foundDevice = devices[0]
                this.targetDevice = foundDevice

                // 搜到了！立马停止扫描
                QCBluetoothManager.instance.stopScan()

                // 切回主线程更新 UI：跳转到第 4 步，并显示真实设备信息
                runOnUiThread {
                    tvDeviceName.text = foundDevice.name ?: "EyeX Glass"
                    tvDeviceMac.text = foundDevice.address
                    showStep(4)
                }
            }
        }
    }
}
