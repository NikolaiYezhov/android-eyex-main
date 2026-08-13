package com.eyex.app.ble

import android.bluetooth.BluetoothDevice
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler

class QCBluetoothReceiver : QCBluetoothCallbackCloneReceiver() {

    override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
        // 蓝牙连接状态改变（这里可以留空，因为我们有别的监听了）
    }

    override fun onServiceDiscovered() {
        Log.e("QCBluetooth", "🎉 服务发现完成，正在初始化...")

        // 1. 打开阀门
        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().initEnable()
        com.oudmon.ble.base.bluetooth.BleOperateManager.getInstance().isReady = true

        // 2. 稍微延迟 500ms 再读（给硬件一点喘息时间，提高成功率）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // 催促上报电量
            QCBluetoothManager.instance.fetchBattery()
            // 催促上报版本信息
            QCBluetoothManager.instance.fetchDeviceInfo()
        }, 500)
    }

    override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {}
    override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
        if (uuid != null && data != null) {
            // 把字节数组直接转换成人类能看懂的字符串（比如 "V1.0.3"）
            val versionStr = String(data, Charsets.UTF_8)

            // 常用的版本号特征值 UUID 结尾通常是 2a26 或者 2a27
            // 不管是硬件版本还是固件版本，只要读到了，我们就发给 UI
            if (uuid.contains("2a26", ignoreCase = true) ||
                uuid.contains("2a27", ignoreCase = true) ||
                uuid.contains("CHAR_FIRMWARE_REVISION", ignoreCase = true)) {

                Log.e("QCBluetooth", "读到版本号了: $versionStr")
                QCBluetoothManager.instance.versionLiveData.postValue(versionStr)
            }
        }
    }
}