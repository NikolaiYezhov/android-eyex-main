package com.eyex.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler // 注意检查导包
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import java.io.File

/**
 * 蓝牙管理中枢（单例）
 * 对应 iOS 的 QCCentralManager
 */
class QCBluetoothManager private constructor() {

    val connectionState = MutableLiveData<Int>(0)
    var activeDevice: BluetoothDevice? = null


    // 🚀 新增：用于让 UI 自动更新的 LiveData
    val batteryLiveData = MutableLiveData<Int>(0)
    val isChargingLiveData = MutableLiveData<Boolean>(false)

    // 🚀 新增：用于通知主页更新版本号
    val versionLiveData = MutableLiveData<String>("")

    // 在类顶部加一个用于存储最新照片的变量
    val latestBitmap = MutableLiveData<android.graphics.Bitmap?>()


    // Android 原生蓝牙适配器
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scannedDevices = mutableListOf<BluetoothDevice>()
    private var scanResultCallback: ((List<BluetoothDevice>) -> Unit)? = null

    companion object {
        val instance: QCBluetoothManager by lazy { QCBluetoothManager() }
    }

    // 1. 初始化，获取系统的蓝牙服务
    fun init(context: Context) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
    }

    // 2. 原生 BLE 扫描回调 (这里复刻了你 iOS 代码里的设备过滤规则)
    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            super.onScanResult(callbackType, result)
            val device = result?.device ?: return

            // 获取蓝牙名字
            val name = device.name ?: ""
            // 如果名字不为空，且列表里还没加过这个设备
            if (name.isNotEmpty() && !scannedDevices.any { it.address == device.address }) {
                val lowerName = name.lowercase()
                // 对应 iOS: "heycyan", "qc", "m02", "o_", "glass"
                if (lowerName.contains("heycyan") || lowerName.startsWith("qc") ||
                    lowerName.startsWith("m02") || lowerName.startsWith("o_") ||
                    lowerName.contains("glass")) {

                    scannedDevices.add(device)
                    // 把最新的设备列表传回给 UI
                    scanResultCallback?.invoke(scannedDevices.toList())
                }
            }
        }
    }

    // 3. 开始扫描
    @SuppressLint("MissingPermission")
    fun startScan(callback: (List<BluetoothDevice>) -> Unit) {
        scanResultCallback = callback
        scannedDevices.clear()
        callback(scannedDevices) // 先返回空列表清空 UI 旧数据

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.startScan(leScanCallback)
    }

    // 4. 停止扫描
    @SuppressLint("MissingPermission")
    fun stopScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        scanner?.stopScan(leScanCallback)
    }

    // 🚀 新增：原厂协议文档里的事件监听器
    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            try {
                // 根据文档，loadData 的第 7 个字节（索引 6）是指令类型
                val type = response.loadData[6].toInt()

                when (type) {
                    0x05 -> { // 🔋 收到眼镜电量上报
                        // 【关键修复】：使用 and 0xFF 将有符号 Byte 转换为无符号真实数值
                        val battery = response.loadData[7].toInt() and 0xFF
                        val charging = (response.loadData[8].toInt() and 0xFF) == 1

                        batteryLiveData.postValue(battery)
                        isChargingLiveData.postValue(charging)
                    }
                    0x02 -> {
                        Log.e("QCBluetooth", "📸 侦测到眼镜拍照信号，准备自动拉取缩略图...")

                        // 切到主线程再调用 getPictureThumbnails，避免 BLE 回调线程的上下文问题
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            fetchThumbnailWithRetry(5)
                        }
                    }
                    // TODO: 以后可以把 0x12 调节音量、0x04 OTA升级都加在这里
                }
            } catch (e: Exception) {
                e.printStackTrace() // 防止数组越界导致闪退
            }
        }
    }

    // 5. 连接与断开
    fun connect(device: BluetoothDevice) {
        activeDevice = device
        connectionState.postValue(1)
        stopScan()

        // 发起连接
        BleOperateManager.getInstance().connectDirectly(device.address)

        // 🚀 核心关键：注册眼镜事件上报监听！
        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)
    }

    // 括号里强行要一个 Context 过来
    fun disconnect(context: android.content.Context) {
        activeDevice = null
        connectionState.postValue(0)
        BleOperateManager.getInstance().disconnect()
        LargeDataHandler.getInstance().removeOutDeviceListener(100)

        try {
            val app = context.applicationContext as android.app.Application
            com.oudmon.wifi.GlassesControl.getInstance(app)?.releaseGlassesControl()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ------------------- 新增：主动控制与数据拉取 -------------------

    // 1. 主动拉取电量 (透视版)
    fun fetchBattery() {
        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().addBatteryCallBack("QC_Battery") { _, response ->
            if (response == null) return@addBatteryCallBack

            try {
                // 情况 1：如果 SDK 极其良心，直接给了一个单纯的数字
                if (response is Int) {
                    batteryLiveData.postValue(response)
                    return@addBatteryCallBack
                }

                // 情况 2：如果是原始的 ByteArray（就像日志里的 bc42020028206100）
                if (response is ByteArray && response.size >= 7) {
                    // 第 6 个索引位置就是 0x61 (97%)
                    val battery = response[6].toInt() and 0xFF
                    if (battery in 0..100) {
                        batteryLiveData.postValue(battery)
                        return@addBatteryCallBack
                    }
                }

                // 情况 3：如果它是一个复杂的对象，我们开启“反射”强行扫描！
                // 遍历这个对象肚子里的所有变量，只要遇到 0~100 之间的数值，直接抓走当电量！
                for (field in response.javaClass.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(response)

                    if (value is Int && value in 0..100) {
                        batteryLiveData.postValue(value)
                        return@addBatteryCallBack
                    } else if (value is Byte) {
                        val byteVal = value.toInt() and 0xFF
                        if (byteVal in 0..100) {
                            batteryLiveData.postValue(byteVal)
                            return@addBatteryCallBack
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 发送同步电量的指令，催促眼镜上报
        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().syncBattery()
    }

    fun takePhoto(onResult: (Boolean, String) -> Unit) {
        LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, it ->
            if (it == null) {
                onResult(false, "眼镜无响应")
                return@glassesControl
            }
            if (it.dataType == 1) {
                val errCode = it.errorCode.toInt()
                val workType = it.workTypeIng.toInt()

                // 只要不是明确的报错，我们就认为成功（因为眼镜已经响了）
                if (errCode == 0 || errCode == 255 || errCode == -1) {
                    when (workType) {
                        2, 4, 5 -> {
                            // 只有在这三种明确被占用的情况下，才提示失败
                            val msg = if(workType==2) "眼镜正在录像" else if(workType==4) "正在传文件" else "正在升级"
                            onResult(false, msg)
                        }
                        else -> {
                            // 其他所有状态码（包括 0, 1, 6, 255, -1），全算作成功！
                            onResult(true, "📸 拍照成功！(状态:$workType)")
                        }
                    }
                } else {
                    onResult(false, "控制失败(错误码:$errCode)")
                }
            } else {
                onResult(false, "收到异常数据(类型:${it.dataType})")
            }
        }
    }


    // 读取眼镜所有版本信息（美化版）
    fun fetchDeviceInfo() {
        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().syncDeviceInfo { _, response ->
            if (response != null) {
                // 1. 获取原始固件版本，例如 "A02S_1.00.15_250930"
                val rawVersion = response.firmwareVersion ?: ""

                // 2. 这里的逻辑：如果有下划线，我们只要中间那一段数字版本号
                val cleanVersion = if (rawVersion.contains("_")) {
                    val parts = rawVersion.split("_")
                    if (parts.size >= 2) {
                        "V${parts[1]}" // 变成 "V1.00.15"
                    } else {
                        rawVersion
                    }
                } else {
                    rawVersion
                }

                Log.e("QCBluetooth", "原始版本: $rawVersion -> 精简版本: $cleanVersion")

                // 3. 将美化后的版本号发给 UI
                versionLiveData.postValue(cleanVersion)
            }
        }
    }


    // 检查眼镜里有多少新媒体文件
    fun checkMediaCount(callback: (Int) -> Unit) {
        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x04)) { _, it ->
            if (it.dataType == 4) {
                val mediaCount = it.imageCount + it.videoCount + it.recordCount
                callback(mediaCount)
            }
        }
    }

    fun fetchImageThumbnail(onImageReceived: (android.graphics.Bitmap?) -> Unit) {
        // 设置一个 15 秒的保护，防止界面永远卡在“同步中”
        val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            onImageReceived(null)
            Log.e("QCBluetooth", "❌ 下载超时，眼镜没把照片吐出来")
        }
        timeoutHandler.postDelayed(timeoutRunnable, 15000)

        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().getPictureThumbnails { _, success, data ->
            // 一旦有回应，取消超时计时
            timeoutHandler.removeCallbacks(timeoutRunnable)

            if (success && data != null && data.isNotEmpty()) {
                try {
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (bitmap != null) {
                            Log.e("QCBluetooth", "✅ 图片解码成功！大小: ${data.size}")
                            onImageReceived(bitmap)
                        } else {
                            Log.e("QCBluetooth", "❌ 收到数据但无法解码为图片")
                            onImageReceived(null)
                        }
                    }
                } catch (e: Exception) {
                    onImageReceived(null)
                }
            } else {
                Log.e("QCBluetooth", "❌ 眼镜返回了空图片数据 (success=$success)")
                onImageReceived(null)
            }
        }
    }

    // 带重试的缩略图拉取：getPictureThumbnails 每个数据包回调一次，
    // 需要把所有分片累积起来，等 success=true 时再解码完整 JPEG
    private fun fetchThumbnailWithRetry(maxRetries: Int) {
        val retryHandler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var retryRunnable: Runnable
        retryRunnable = object : Runnable {
            var accumulatedData = ByteArray(0)

            override fun run() {
                LargeDataHandler.getInstance().getPictureThumbnails { _, success, data ->
                    if (data != null && data.isNotEmpty()) {
                        accumulatedData = accumulatedData + data
                        Log.e("QCBluetooth", "📦 收到分片: size=${data.size} 累积=${accumulatedData.size} success=$success")
                    }

                    if (success) {
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(accumulatedData, 0, accumulatedData.size)
                        if (bitmap != null) {
                            Log.e("QCBluetooth", "✅ 缩略图拉取成功！总大小: ${accumulatedData.size}")
                            latestBitmap.postValue(bitmap)
                            return@getPictureThumbnails
                        }
                    }
                }
            }
        }

        // 首次触发
        retryHandler.post(retryRunnable)

        // 重试定时器：只在第一次传输失败时才重试
        var retries = 0
        val timer = object : Runnable {
            override fun run() {
                if (latestBitmap.value != null) {
                    Log.e("QCBluetooth", "✅ 已在之前成功获取缩略图，跳过重试")
                    return
                }
                retries++
                if (retries > maxRetries) {
                    Log.e("QCBluetooth", "❌ 缩略图拉取失败，已重试 $maxRetries 次")
                    return
                }
                Log.e("QCBluetooth", "⏳ 重试 #$retries ...")
                retryRunnable.accumulatedData = ByteArray(0)
                retryRunnable.run()
                retryHandler.postDelayed(this, 1500)
            }
        }
        retryHandler.postDelayed(timer, 1500)
    }

    // 🚀 新增：初始化同步照片的引擎
    // 🚀 注意看第一行，onDownloaded 的参数已经改成了 String
    fun initMediaEngine(context: android.content.Context, onDownloaded: (String) -> Unit, onAllComplete: () -> Unit) {
        val albumPath = java.io.File(context.getExternalFilesDir(null), "DCIM_1")
        if (!albumPath.exists()) albumPath.mkdirs()

        val app = context.applicationContext as android.app.Application
        val control = com.oudmon.wifi.GlassesControl.getInstance(app)
        control?.initGlasses(albumPath.absolutePath)

        control?.setWifiDownloadListener(object : com.oudmon.wifi.GlassesControl.WifiFilesDownloadListener {

            // 🎬 1. 照片下载完成回调
            override fun fileWasDownloadSuccessfully(entity: com.oudmon.wifi.bean.GlassAlbumEntity) {
                val file = java.io.File(entity.filePath)
                if (file.exists()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        // 🚀 核心修复：这里只把路径(String)传给UI，绝对不解图！
                        onDownloaded(file.absolutePath)
                    }
                }
            }

            // 🎬 2. 防抖视频处理完成回调（视频真正的归宿）
            override fun eisEnd(fileName: String, filePath: String) {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onDownloaded(file.absolutePath) // 视频处理完了，通知UI刷新
                    }
                }
            }

            // 🎬 3. 全部文件下载完成回调
            override fun fileDownloadComplete() {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onAllComplete()
                }
            }

            // --------- 下面是其他打印日志的回调，不用管逻辑 ---------
            override fun fileCount(index: Int, total: Int) {
                android.util.Log.e("QCBluetooth", "📦 准备搬运！当前第 $index 个，总共 $total 个")
            }
            override fun fileProgress(fileName: String, progress: Int) {
android.util.Log.e("QCBluetooth", "🚚 正在路上：$fileName -> $progress%")
            }
            override fun fileDownloadError(fileType: Int, errorType: Int) {
                android.util.Log.e("QCBluetooth", "❌ 搬运失败！类型: $fileType, 错误码: $errorType")
            }
            override fun onGlassesFail(errorCode: Int) {
                android.util.Log.e("QCBluetooth", "⚠️ 通道异常！错误码: $errorCode")
            }
            override fun eisError(n: String, s: String, e: String) {
                android.util.Log.e("QCBluetooth", "❌ 视频防抖失败: $e")
            }

            // 其他空方法保持不变
            override fun onGlassesControlSuccess() {}

            // 🎙️ 录音文件同步成功回调
            override fun recordingToPcm(fileName: String, filePath: String, duration: Int) {
                val file = java.io.File(filePath)
                if (file.exists()) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onDownloaded(file.absolutePath) // 告诉 UI 有新录音啦！
                    }
                }
            }

            // 🎙️ 录音文件下载或解码失败
            override fun recordingToPcmError(fileName: String, errorInfo: String) {
                android.util.Log.e("QCBluetooth", "❌ 录音文件处理失败: $errorInfo")

                // 🛑 核心修改：删除了手动强制释放 releaseGlassesControl() 的逻辑
                // 解释：如果某个音频解码失败，只打印日志，千万不要杀掉整个 WiFi 连接，
                // 这样眼镜才会继续传输队列里剩下的照片和视频。
            }
            override fun wifiSpeed(s: String) {}
            override fun voiceFromGlasses(data: ByteArray) {}
            override fun voiceFromGlassesStatus(s: Int) {}
        })
    }


// 🚀 在 QCBluetoothManager 类里增加这个方法
fun resetWifiSystem(context: android.content.Context) {
val app = context.applicationContext as android.app.Application
// 1. 停止同步
com.oudmon.wifi.GlassesControl.getInstance(app)?.releaseGlassesControl()
// 2. 告诉眼镜停止 WiFi（根据 SDK 逻辑，通常发 0x01 指令可以重置模式）
com.oudmon.ble.base.communication.LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, _ -> }
}


    fun controlVideo(start: Boolean, onStatusUpdate: (String) -> Unit) {
        val value = if (start) 0x02 else 0x03
        val command = byteArrayOf(0x02, 0x01, value.toByte())

        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().glassesControl(command) { _, it ->
            // 🚀 核心修改：放宽判断条件
            // 只要 errorCode 是 0 或 255 (ff)，或者 workTypeIng 是 2 (录像中) 或 0 (空闲)，都算成功
            if (it.errorCode == 0 || it.errorCode == 0xff || it.errorCode == -1) {
                if (start) {
                    onStatusUpdate("🔴 正在录制中...")
                } else {
                    onStatusUpdate("✅ 录制已停止")
                }
            } else {
                // 只有真的出现其他错误码（比如 -2, 115等）才报冲突
                onStatusUpdate("状态切换中...")
            }
        }
    }


    // 🚀 新增：控制录音功能
    fun controlAudio(start: Boolean, onStatusUpdate: (String) -> Unit) {
        // 根据文档：0x08 开始，0x0c 停止
        val value = if (start) 0x08 else 0x0c
        val command = byteArrayOf(0x02, 0x01, value.toByte())

        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().glassesControl(command) { _, it ->
            // 只要没报错，或者明确进入录音状态 (8)，就算成功
            if (it.errorCode == 0 || it.errorCode == 0xff || it.errorCode == -1 || it.workTypeIng == 8) {
                if (start) {
                    onStatusUpdate("🎙️ 正在录音中...")
                } else {
                    onStatusUpdate("✅ 录音已停止")
                }
            } else {
                onStatusUpdate("状态切换中...")
            }
        }
    }

}
