package com.eyex.app.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.eyex.app.R
import com.eyex.app.ble.QCBluetoothManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class FaceRecognitionActivity : AppCompatActivity() {

    companion object {
        private const val API_ENDPOINT = "https://api.eyex-ai.com/v1/face/recognition"
        private const val API_TOKEN = "face_api_2026"
        private const val CONFIDENCE_THRESHOLD = 0.50
        private const val IMAGE_TIMEOUT_MS = 15_000L
    }

    // 视图
    private lateinit var ivFaceImage: ImageView
    private lateinit var tvResult: TextView
    private lateinit var btnStart: Button

    // 状态
    private var selectedBitmap: Bitmap? = null
    private var isRequesting = false

    // 观察者和超时
    private var latestBitmapObserver: Observer<Bitmap?>? = null
    private var imageTimeoutRunnable: Runnable? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_face_recognition)

        initViews()
        setupListeners()
    }

    private fun initViews() {
        ivFaceImage = findViewById(R.id.ivFaceImage)
        tvResult = findViewById(R.id.tvResult)
        btnStart = findViewById(R.id.btnStart)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            if (isRequesting) return@setOnClickListener
            prepareForNewRequest()
            triggerAIPhotoFromGlasses()
        }
    }

    private fun prepareForNewRequest() {
        isRequesting = true
        btnStart.isEnabled = false
        btnStart.text = "拍照中..."
        tvResult.text = "正在获取眼镜图片..."
        ivFaceImage.setImageBitmap(null)
        ivFaceImage.visibility = View.GONE
        selectedBitmap = null

        // 提前注册观察者，避免错过回调
        observeLatestBitmap()
    }

    private fun observeLatestBitmap() {
        // 移除旧观察者
        latestBitmapObserver?.let { QCBluetoothManager.instance.latestBitmap.removeObserver(it) }

        // 清空旧值
        QCBluetoothManager.instance.latestBitmap.value = null

        val observer = Observer<Bitmap?> { bitmap ->
            if (bitmap != null && selectedBitmap == null) {
                // 收到图片，取消超时
                cancelImageTimeout()
                // 使用 latestBitmapObserver 移除（它此时已指向当前 observer）
                latestBitmapObserver?.let { QCBluetoothManager.instance.latestBitmap.removeObserver(it) }
                latestBitmapObserver = null

                selectedBitmap = bitmap
                ivFaceImage.setImageBitmap(bitmap)
                ivFaceImage.visibility = View.VISIBLE
                tvResult.text = "已获取眼镜图片，正在上传识别..."
                startRecognition()
            }
        }
        latestBitmapObserver = observer
        QCBluetoothManager.instance.latestBitmap.observeForever(observer)
    }

    private fun triggerAIPhotoFromGlasses() {
        // 检查设备是否空闲（此处简化，实际应调用 SDK 方法）
        val isReady = true
        if (!isReady) {
            tvResult.text = "设备正忙，请稍后再试"
            finishRequest()
            return
        }

        tvResult.text = "拍照命令发送中..."
        val command = byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02, 0x02)
        com.oudmon.ble.base.communication.LargeDataHandler.getInstance()
            .glassesControl(command) { _, response ->
                runOnUiThread {
                    val errCode = response.errorCode.toInt() and 0xFF
                    if (response.dataType != 1 || (errCode != 0 && errCode != 255)) {
                        tvResult.text = "AI 拍照触发失败（错误码：$errCode）"
                        finishRequest()
                    } else {
                        // 命令发送成功，启动超时计时
                        scheduleImageTimeout()
                    }
                }
            }
    }

    private fun scheduleImageTimeout() {
        cancelImageTimeout()
        imageTimeoutRunnable = Runnable {
            if (isRequesting && selectedBitmap == null) {
                tvResult.text = "图片接收超时，请重试"
                finishRequest()
            }
        }
        Handler(Looper.getMainLooper()).postDelayed(imageTimeoutRunnable!!, IMAGE_TIMEOUT_MS)
    }

    private fun cancelImageTimeout() {
        imageTimeoutRunnable?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
        imageTimeoutRunnable = null
    }

    private fun startRecognition() {
        val bitmap = selectedBitmap ?: run {
            tvResult.text = "未获取到图片"
            finishRequest()
            return
        }

        val jpegData = compressImage(bitmap) ?: run {
            tvResult.text = "图片压缩失败"
            finishRequest()
            return
        }

        uploadImageForRecognition(jpegData)
    }

    private fun compressImage(bitmap: Bitmap): ByteArray? {
        return try {
            val maxDim = 768f
            val scale = minOf(1f, maxDim / maxOf(bitmap.width, bitmap.height).toFloat())
            val resized = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else {
                bitmap
            }

            var quality = 0.78f
            var out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), out)
            var bytes = out.toByteArray()

            while (bytes.size > 1024 * 1024 && quality > 0.2f) {
                quality -= 0.12f
                out = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), out)
                bytes = out.toByteArray()
            }
            bytes
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun uploadImageForRecognition(jpegData: ByteArray) {
        val requestId = UUID.randomUUID().toString()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("request_id", requestId)
            .addFormDataPart("file", "face.jpg",
                RequestBody.create("image/jpeg".toMediaType(), jpegData))
            .build()

        val request = Request.Builder()
            .url(API_ENDPOINT)
            .post(body)
            .addHeader("Authorization", "Bearer $API_TOKEN")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvResult.text = "网络请求失败：${e.localizedMessage}"
                    finishRequest()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.code !in 200..299) {
                    runOnUiThread {
                        tvResult.text = "HTTP ${response.code}\n$responseBody"
                        finishRequest()
                    }
                    return
                }
                try {
                    val json = JSONObject(responseBody ?: "")
                    runOnUiThread {
                        handleRecognitionResponse(json)
                        finishRequest()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        tvResult.text = "解析响应失败：${e.localizedMessage}"
                        finishRequest()
                    }
                }
            }
        })
    }

    private fun handleRecognitionResponse(json: JSONObject) {
        val success = json.optBoolean("success", false)
        if (!success) {
            val error = json.optJSONObject("error")
            val message = error?.optString("message", "未知错误") ?: "识别失败"
            tvResult.text = "识别失败：$message"
            return
        }

        val facesArray = json.optJSONArray("faces") ?: JSONArray()
        if (facesArray.length() == 0) {
            tvResult.text = "未检测到人脸"
            return
        }

        val resultBuilder = StringBuilder()
        resultBuilder.append("检测到 ${facesArray.length()} 张人脸\n\n")

        for (i in 0 until facesArray.length()) {
            val face = facesArray.getJSONObject(i)
            val topKMatches = face.optJSONArray("top_k_matches")
            val topMatch = face.optJSONObject("top_match")

            val matches = when {
                topKMatches != null && topKMatches.length() > 0 -> topKMatches
                topMatch != null -> JSONArray().put(topMatch)
                else -> null
            }

            if (matches == null || matches.length() == 0) {
                resultBuilder.append("人物 ${i + 1}: 未找到匹配\n")
                continue
            }

            resultBuilder.append("人物 ${i + 1}:\n")
            val maxCandidates = minOf(3, matches.length())
            for (j in 0 until maxCandidates) {
                val match = matches.getJSONObject(j)
                val name = match.optString("name", match.optString("teacher_id", "--"))
                val similarity = match.optDouble("similarity", 0.0)
                val similarityPercent = "%.2f".format(similarity * 100)
                val college = match.optString("college", "")
                val school = match.optString("school", "")
                val unit = if (college.isNotEmpty() && school.isNotEmpty() && college != school) {
                    "$college / $school"
                } else {
                    college.ifEmpty { school.ifEmpty { "--" } }
                }

                resultBuilder.append("  ${j + 1}. $name ($similarityPercent%) - $unit\n")
            }
            resultBuilder.append("\n")
        }

        tvResult.text = resultBuilder.toString().trimEnd()
    }

    private fun finishRequest() {
        isRequesting = false
        btnStart.isEnabled = true
        btnStart.text = "开始识别"
        // 清除超时和观察者
        cancelImageTimeout()
        latestBitmapObserver?.let { QCBluetoothManager.instance.latestBitmap.removeObserver(it) }
        latestBitmapObserver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelImageTimeout()
        latestBitmapObserver?.let { QCBluetoothManager.instance.latestBitmap.removeObserver(it) }
        latestBitmapObserver = null
    }


}
