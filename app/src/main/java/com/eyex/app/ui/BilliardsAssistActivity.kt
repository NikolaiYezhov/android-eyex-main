package com.eyex.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class BilliardsAssistActivity : AppCompatActivity() {

    companion object {
        private const val API_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val API_KEY = "sk-1ecef1a03a0f4062a16b59a8e50b4399"
        private const val MODEL = "qwen3-vl-flash"
        private const val MAX_IMAGE_DIMENSION = 1280
        private const val JPEG_QUALITY = 82
    }

    private lateinit var ivPreview: ImageView
    private lateinit var tvPlaceholder: TextView
    private lateinit var stateBadge: TextView
    private lateinit var tvResult: TextView
    private lateinit var btnPickImage: Button
    private lateinit var btnAnalyze: Button

    private var selectedImage: Bitmap? = null
    private var currentRequestId = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            loadImageFromUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_billiards_assist)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = 0xFF0F1014.toInt()
        }

        initViews()
        refreshUIState()
    }

    private fun initViews() {
        ivPreview = findViewById(R.id.ivPreview)
        tvPlaceholder = findViewById(R.id.tvPlaceholder)
        stateBadge = findViewById(R.id.stateBadge)
        tvResult = findViewById(R.id.tvResult)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnAnalyze = findViewById(R.id.btnAnalyze)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPickImage.setOnClickListener { pickImage() }
        btnAnalyze.setOnClickListener { analyzeImage() }
    }

    private fun refreshUIState() {
        val hasImage = selectedImage != null
        btnAnalyze.isEnabled = hasImage
        btnAnalyze.alpha = if (hasImage) 1.0f else 0.55f
        stateBadge.text = if (hasImage) "已就绪" else "待上传"
    }

    private fun pickImage() {
        imagePickerLauncher.launch("image/*")
    }

    private fun loadImageFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                Toast.makeText(this, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
                return
            }

            selectedImage = bitmap
            ivPreview.setImageBitmap(bitmap)
            tvPlaceholder.visibility = View.GONE
            stateBadge.text = "已就绪"
            tvResult.text = "图片已就绪，点击\"开始分析\"获取台球建议。"
            refreshUIState()
        } catch (e: Exception) {
            Toast.makeText(this, "图片读取失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyzeImage() {
        val bitmap = selectedImage ?: return
        val imageData = compressToJPEG(bitmap) ?: return

        if (imageData.isEmpty()) {
            Toast.makeText(this, "图片压缩失败，请重新选择", Toast.LENGTH_SHORT).show()
            return
        }

        currentRequestId++
        val requestId = currentRequestId
        stateBadge.text = "分析中"
        tvResult.text = "正在分析台球局面，请稍候..."

        val base64Image = Base64.encodeToString(imageData, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Image"

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是台球辅助教练。请基于用户上传的球桌图片，用简体中文给出实用建议。不要假装精确计算物理轨迹，要明确说明这是基于图片观察的策略判断。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "请分析这张台球照片，按以下结构输出：1.局面判断；2.推荐先打哪颗球；3.建议目标袋口；4.击球方向与力度建议；5.主要风险。要求简洁、可执行。")
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", dataUrl)
                            })
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(API_ENDPOINT)
            .post(RequestBody.create("application/json".toMediaType(), payload.toString()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $API_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (currentRequestId != requestId) return
                runOnUiThread {
                    stateBadge.text = "失败"
                    tvResult.text = "分析失败，请检查网络后重试。"
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (currentRequestId != requestId) return
                val body = response.body?.string()
                if (response.code != 200 || body.isNullOrEmpty()) {
                    runOnUiThread {
                        stateBadge.text = "失败"
                        tvResult.text = "服务异常（HTTP ${response.code}）"
                    }
                    return
                }
                try {
                    val json = JSONObject(body)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val content = choice?.optJSONObject("message")?.optString("content", "") ?: ""
                    val text = extractText(content)
                    runOnUiThread {
                        stateBadge.text = "完成"
                        tvResult.text = if (text.isNotEmpty()) text
                            else "模型未返回有效建议，请换一张更清晰的球桌照片。"
                    }
                } catch (_: Exception) {
                    runOnUiThread {
                        stateBadge.text = "失败"
                        tvResult.text = "解析响应失败，请稍后重试。"
                    }
                }
            }
        })
    }

    /** 压缩图片：最大 1280px，JPEG 质量 82% */
    private fun compressToJPEG(bitmap: Bitmap): ByteArray? {
        return try {
            val srcW = bitmap.width
            val srcH = bitmap.height
            val scale = minOf(1.0f, MAX_IMAGE_DIMENSION.toFloat() / maxOf(srcW, srcH))
            val dstW = maxOf(1, (srcW * scale).toInt())
            val dstH = maxOf(1, (srcH * scale).toInt())

            val resized = if (scale < 1.0f) {
                Bitmap.createScaledBitmap(bitmap, dstW, dstH, true)
            } else {
                bitmap
            }

            val output = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        } catch (_: Exception) { null }
    }

    private fun extractText(content: String?): String {
        return content ?: ""
    }
}
