package com.eyex.app.app

import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Build
// 下面这两个类如果爆红，千万记得按 Alt+Enter 导包！
import com.oudmon.ble.base.bluetooth.BleAction
import com.eyex.app.ble.QCBluetoothReceiver

import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.eyex.app.ble.QCBluetoothManager
import java.io.File

class QCApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. 初始化核心蓝牙操作类
        BleOperateManager.getInstance(this)
        BleOperateManager.getInstance().setApplication(this)
        BleOperateManager.getInstance().init()

        // 2. 初始化底层基础控制类
        BleBaseControl.getInstance(this).setmContext(this)

        // ========================================================
        // 3. 注册 SDK 的全局系统广播接收器 (之前加的，保持不动)
        // ========================================================
        try {
            val deviceFilter: android.content.IntentFilter = BleAction.getDeviceIntentFilter()
            val deviceReceiver = QCBluetoothReceiver()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                registerReceiver(deviceReceiver, deviceFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(deviceReceiver, deviceFilter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ========================================================
        // 🚀 新增第4步：注册本地服务广播 (用来接收 onServiceDiscovered 并打开阀门)
        // ========================================================
        try {
            val intentFilter = BleAction.getIntentFilter()
            val myBleReceiver = com.eyex.app.ble.QCBluetoothReceiver() // 我们刚才建的类
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(this)
                .registerReceiver(myBleReceiver, intentFilter)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. 初始化咱们自己封装的管理类
        QCBluetoothManager.instance.init(this)


        // 🚀 新增：在手机里创建一个专门放眼镜照片的文件夹
        val folder = File(getExternalFilesDir(null), "GlassesPhotos")
        if (!folder.exists()) {
            folder.mkdirs()
        }

    }
}