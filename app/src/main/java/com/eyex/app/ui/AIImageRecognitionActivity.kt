package com.eyex.app.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.eyex.app.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AIImageRecognitionActivity : AppCompatActivity() {

    companion object {
        private const val API_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val TTS_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val API_KEY = "sk-1ecef1a03a0f4062a16b59a8e50b4399"
        private const val MODEL = "qwen3-vl-flash"
        private const val TTS_MODEL = "qwen3-tts-flash"
        private const val TTS_VOICE = "Cherry"
        private const val PREFS_NAME = "ai_image_prefs"
        private const val KEY_FAVORITES = "favorites"
        private const val MAX_IMAGE_DIM = 768
    }

    private data class FavoriteItem(
        val id: String = UUID.randomUUID().toString(),
        val imageBase64: String = "",
        val text: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    private lateinit var tvTime: TextView
    private lateinit var contentLayout: LinearLayout
    private lateinit var btnPickImage: Button
    private lateinit var btnAnalyze: Button
    private lateinit var btnShare: ImageButton

    private var selectedBitmap: Bitmap? = null
    private var currentRequestId = 0L
    private var latestResultImage: Bitmap? = null
    private var latestResultText: String? = null
    private var latestResultId: String? = null
    private var mediaPlayer: MediaPlayer? = null
    private var favoriteItems = mutableListOf<FavoriteItem>()
    private var isShowingFavorites = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) loadImageFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_image_recognition)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = 0xFF0F1014.toInt()
        }

        initViews()
        loadFavorites()
        updateTime()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun initViews() {
        tvTime = findViewById(R.id.tvTime)
        contentLayout = findViewById(R.id.contentLayout)
        btnPickImage = findViewById(R.id.btnPickImage)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        btnShare = findViewById(R.id.btnShare)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnPickImage.setOnClickListener { showSourceChooser() }
        btnAnalyze.setOnClickListener { analyzeImage() }
        btnShare.setOnClickListener { shareResult() }
    }

    private fun updateTime() {
        tvTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
    }

    // ------ 图片来源选择 ------
    private fun showSourceChooser() {
        val items = arrayOf("从相册选择", "查看收藏")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> imagePickerLauncher.launch("image/*")
                    1 -> showFavorites()
                }
            }
            .show()
    }

    // ------ 图片加载 ------
    private fun loadImageFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (bitmap == null) {
                Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show()
                return
            }
            selectedBitmap = bitmap
            isShowingFavorites = false
            renderPreview(bitmap, null)
            btnAnalyze.isEnabled = true
            btnAnalyze.alpha = 1.0f
        } catch (e: Exception) {
            Toast.makeText(this, "图片读取失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------ 渲染界面 ------
    private fun renderPreview(bitmap: Bitmap?, extraText: String?) {
        contentLayout.removeAllViews()
        if (bitmap == null && extraText == null) return

        if (bitmap != null) {
            val density = resources.displayMetrics.density
            val iv = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = null
                setBackgroundColor(0x0FFFFFFF.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (220 * density).toInt()
                )
                lp.bottomMargin = (12 * density).toInt()
                layoutParams = lp
            }
            contentLayout.addView(iv)
        }

        if (extraText != null && extraText.isNotEmpty()) {
            val density = resources.displayMetrics.density
            val tv = TextView(this).apply {
                text = extraText
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                setLineSpacing(0f, 1.3f)
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                setOnLongClickListener {
                    saveToFavorites()
                    true
                }
            }
            contentLayout.addView(tv)
        }
    }

    private fun renderFavorites() {
        isShowingFavorites = true
        contentLayout.removeAllViews()

        if (favoriteItems.isEmpty()) {
            val tv = TextView(this).apply {
                text = "长按识图结果卡片即可收藏，收藏内容会保留到下次启动。"
                setTextColor(0xA6FFFFFF.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(40, 40, 40, 40)
            }
            contentLayout.addView(tv)
            return
        }

        for ((index, item) in favoriteItems.withIndex()) {
            val card = createFavoriteCard(item, index)
            contentLayout.addView(card)
        }
    }

    private fun createFavoriteCard(item: FavoriteItem, index: Int): View {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFF2.toInt())
            val radius = 18 * density
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(0xFFFFFFF2.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 16
            layoutParams = lp
            setPadding(0, 0, 0, 0)
            tag = index
        }

        // Image
        val imageData = Base64.decode(item.imageBase64, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
        if (bitmap != null) {
            val iv = ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(0x0FFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (200 * density).toInt()
                )
            }
            card.addView(iv)
        }

        // Text
        val tv = TextView(this).apply {
            text = item.text
            setTextColor(0xFF1C1C1E.toInt())
            textSize = 16f
            setPadding((16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt())
        }
        card.addView(tv)

        // Long press for delete
        card.setOnLongClickListener {
            showDeleteFavoriteDialog(item)
            true
        }

        return card
    }

    // ------ AI 分析 ------
    private fun analyzeImage() {
        val bitmap = selectedBitmap ?: return
        val imageData = compressImage(bitmap) ?: return

        currentRequestId++
        val requestId = currentRequestId
        btnAnalyze.isEnabled = false
        btnAnalyze.text = "分析中..."
        latestResultId = null
        latestResultText = null
        latestResultImage = null

        val base64Img = Base64.encodeToString(imageData, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Img"

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个图像描述助手，请用简体中文简洁准确描述图片内容，返回30字以内，不要展开说明。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "请用30字以内描述这张图片，只返回简洁描述。")
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply { put("url", dataUrl) })
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
                    btnAnalyze.isEnabled = true
                    btnAnalyze.text = "开始识图"
                    Toast.makeText(this@AIImageRecognitionActivity,
                        "分析失败，请检查网络后重试。", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (currentRequestId != requestId) return
                val body = response.body?.string()
                if (response.code != 200 || body.isNullOrEmpty()) {
                    runOnUiThread { onAnalysisError(response.code, requestId) }
                    return
                }
                try {
                    val json = JSONObject(body)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val content = choice?.optJSONObject("message")?.optString("content", "") ?: ""
                    if (content.isEmpty()) throw Exception("empty content")

                    runOnUiThread {
                        latestResultImage = selectedBitmap
                        latestResultText = content
                        latestResultId = UUID.randomUUID().toString()
                        btnAnalyze.isEnabled = true
                        btnAnalyze.text = "开始识图"
                        renderPreview(selectedBitmap, content)
                        btnShare.isEnabled = true
                        btnShare.alpha = 1.0f
                    }
                    // TTS
                    synthesizeSpeech(content, requestId)
                } catch (_: Exception) {
                    runOnUiThread { onAnalysisError(0, requestId) }
                }
            }
        })
    }

    private fun onAnalysisError(httpCode: Int, requestId: Long) {
        if (currentRequestId != requestId) return
        btnAnalyze.isEnabled = true
        btnAnalyze.text = "开始识图"
        val msg = if (httpCode > 0) "服务异常（HTTP $httpCode）" else "模型返回解析失败，请稍后重试。"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ------ 图片压缩 ------
    private fun compressImage(bitmap: Bitmap): ByteArray? {
        return try {
            val srcW = bitmap.width
            val srcH = bitmap.height
            val srcMax = maxOf(srcW, srcH)
            val scale = if (srcMax > MAX_IMAGE_DIM) MAX_IMAGE_DIM.toFloat() / srcMax else 1.0f
            val dstW = maxOf(1, (srcW * scale).toInt())
            val dstH = maxOf(1, (srcH * scale).toInt())
            val resized = if (scale < 1.0f) Bitmap.createScaledBitmap(bitmap, dstW, dstH, true) else bitmap

            var quality = 0.6f
            var data = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), data)
            var bytes = data.toByteArray()

            // 确保不超过 220KB
            while (bytes.size > 220 * 1024 && quality > 0.12f) {
                quality -= 0.1f
                data = ByteArrayOutputStream()
                resized.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt(), data)
                bytes = data.toByteArray()
            }
            bytes
        } catch (_: Exception) { null }
    }

    // ------ TTS ------
    private fun synthesizeSpeech(text: String, requestId: Long) {
        if (text.isEmpty() || currentRequestId != requestId) return
        val payload = JSONObject().apply {
            put("model", TTS_MODEL)
            put("input", JSONObject().apply {
                put("text", text)
                put("voice", TTS_VOICE)
                put("language_type", "Chinese")
            })
        }
        client.newCall(Request.Builder()
            .url(TTS_ENDPOINT)
            .post(RequestBody.create("application/json".toMediaType(), payload.toString()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $API_KEY")
            .build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                if (currentRequestId != requestId) return
                try {
                    val json = JSONObject(response.body?.string() ?: return)
                    val url = json.optJSONObject("output")?.optJSONObject("audio")?.optString("url", "") ?: ""
                    if (url.isNotEmpty()) playAudio(url, requestId)
                } catch (_: Exception) {}
            }
        })
    }

    private fun playAudio(url: String, requestId: Long) {
        val httpsUrl = if (url.startsWith("http://")) "https://${url.removePrefix("http://")}" else url
        runOnUiThread {
            if (currentRequestId != requestId) return@runOnUiThread
            stopPlayback()
            mediaPlayer = MediaPlayer().apply {
                setOnPreparedListener { mp -> if (currentRequestId == requestId) mp.start() }
                setOnErrorListener { _, _, _ -> true }
                setOnCompletionListener { stopPlayback() }
                try { setDataSource(httpsUrl); prepareAsync() } catch (_: Exception) {}
            }
        }
    }

    private fun stopPlayback() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() }; mediaPlayer = null } catch (_: Exception) {}
    }

    // ------ 收藏 ------
    private fun loadFavorites() {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_FAVORITES, null) ?: return
        try {
            val type = object : TypeToken<List<FavoriteItem>>() {}.type
            val list: List<FavoriteItem> = gson.fromJson(json, type)
            favoriteItems = list.toMutableList()
        } catch (_: Exception) {}
    }

    private fun saveFavorites() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_FAVORITES, gson.toJson(favoriteItems)).apply()
    }

    private fun saveToFavorites() {
        val image = latestResultImage ?: return
        val text = latestResultText ?: return
        val id = latestResultId ?: return
        if (id.isEmpty() || text.isEmpty()) return

        // 检查是否已收藏
        if (favoriteItems.any { it.id == id }) {
            Toast.makeText(this, "已收藏过了", Toast.LENGTH_SHORT).show()
            return
        }

        val imageData = compressImage(image) ?: return
        val base64 = Base64.encodeToString(imageData, Base64.NO_WRAP)

        favoriteItems.add(0, FavoriteItem(id = id, imageBase64 = base64, text = text))
        saveFavorites()
        Toast.makeText(this, "已收藏", Toast.LENGTH_SHORT).show()
    }

    private fun showDeleteFavoriteDialog(item: FavoriteItem) {
        AlertDialog.Builder(this)
            .setTitle("管理收藏")
            .setMessage("删除这条已收藏内容。")
            .setPositiveButton("删除收藏") { _, _ ->
                favoriteItems.removeAll { it.id == item.id }
                saveFavorites()
                renderFavorites()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFavorites() {
        btnAnalyze.isEnabled = false
        btnAnalyze.alpha = 0.55f
        renderFavorites()
    }

    // ------ 分享 ------
    private fun shareResult() {
        val text = latestResultText ?: return
        val image = latestResultImage ?: return

        // 保存图片到缓存目录，用于分享
        try {
            val cacheDir = File(cacheDir, "share")
            cacheDir.mkdirs()
            val file = File(cacheDir, "ai_result_${System.currentTimeMillis()}.jpg")
            val output = java.io.FileOutputStream(file)
            image.compress(Bitmap.CompressFormat.JPEG, 90, output)
            output.close()

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "分享识图结果"))
        } catch (e: Exception) {
            // 兜底：只分享文字
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(shareIntent, "分享识图结果"))
        }
    }
}
