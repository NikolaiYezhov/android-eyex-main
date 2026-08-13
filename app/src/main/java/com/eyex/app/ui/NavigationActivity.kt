package com.eyex.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.eyex.app.R
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.TimeUnit

class NavigationActivity : AppCompatActivity() {

    companion object {
        private const val API_KEY = "sk-1ecef1a03a0f4062a16b59a8e50b4399"
        private const val PREFS_NAME = "navigation_prefs"
        private const val KEY_RECENT = "recent_destinations"
        private const val KEY_PREFERRED_MAP = "preferred_map"
        private const val KEY_ALWAYS_ASK = "always_ask"
        private const val KEY_HAS_SELECTED = "has_selected"
        private const val PERMISSION_RECORD_AUDIO_CODE = 301
        private const val MAX_RECENT = 6
    }

    // Views
    private lateinit var tvStatus: TextView
    private lateinit var etDestination: EditText
    private lateinit var btnMic: ImageButton
    private lateinit var btnNavigate: Button
    private lateinit var switchAlwaysAsk: Switch
    private lateinit var layoutHistory: LinearLayout

    // State
    private var recentDestinations = mutableListOf<String>()
    private var preferredMapId = ""
    private var alwaysAsk = true
    private var hasSelectedMap = false

    // ASR state
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var asrWebSocket: WebSocket? = null
    private var recordingThread: Thread? = null
    private var audioTaskId = ""

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // 地图信息
    private data class MapApp(val id: String, val name: String, val packageName: String?, val uriScheme: String)

    private val mapApps = listOf(
        MapApp("amap", "高德地图", "com.autonavi.minimap", "androidamap://route?sourceApplication=QCSDKDemo&dname=%s&dev=0&t=0"),
        MapApp("baidumap", "百度地图", "com.baidu.BaiduMap", "baidumap://map/direction?destination=%s&mode=driving"),
        MapApp("qqmap", "腾讯地图", "com.tencent.map", "qqmap://map/routeplan?type=drive&to=%s")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = 0xFF0F1014.toInt()
        }

        initViews()
        loadPreferences()
        refreshHistory()
        refreshMapButtonTitle()
    }

    override fun onDestroy() {
        isRecording = false
        try { asrWebSocket?.close(1000, "destroy") } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        etDestination = findViewById(R.id.etDestination)
        btnMic = findViewById(R.id.btnMic)
        btnNavigate = findViewById(R.id.btnNavigate)
        switchAlwaysAsk = findViewById(R.id.switchAlwaysAsk)
        layoutHistory = findViewById(R.id.layoutHistory)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnMic.setOnClickListener { onMicTapped() }
        btnNavigate.setOnClickListener { onNavigateTapped() }

        etDestination.setOnEditorActionListener { _, _, _ ->
            val text = etDestination.text.toString().trim()
            if (text.isNotEmpty()) tvStatus.text = "地点已填写，请选择地图开始导航。"
            false
        }

        switchAlwaysAsk.setOnCheckedChangeListener { _, isChecked ->
            alwaysAsk = isChecked
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putBoolean(KEY_ALWAYS_ASK, alwaysAsk).apply()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        try {
            val json = prefs.getString(KEY_RECENT, null) ?: ""
            val type = object : TypeToken<List<String>>() {}.type
            val list: List<String> = gson.fromJson(json, type) ?: emptyList()
            recentDestinations = list.toMutableList()
        } catch (_: Exception) {}
        preferredMapId = prefs.getString(KEY_PREFERRED_MAP, "") ?: ""
        alwaysAsk = prefs.getBoolean(KEY_ALWAYS_ASK, true)
        hasSelectedMap = prefs.getBoolean(KEY_HAS_SELECTED, false)
        switchAlwaysAsk.isChecked = alwaysAsk
    }

    private fun persistRecent() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_RECENT, gson.toJson(recentDestinations)).apply()
    }

    // ------ 麦克风 / ASR ------
    private fun onMicTapped() {
        if (isRecording) {
            stopVoiceRecording()
        } else {
            startVoiceRecording()
        }
    }

    private fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_RECORD_AUDIO_CODE)
            return
        }

        isRecording = true
        audioTaskId = UUID.randomUUID().toString()
        btnMic.setBackgroundColor(0xFFEE5F37.toInt())
        tvStatus.text = "正在聆听，再次点击停止"

        val sampleRate = 16000
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
            6400
        )
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

        val wsRequest = Request.Builder()
            .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime")
            .addHeader("Authorization", "bearer $API_KEY")
            .build()

        asrWebSocket = client.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val sessionObj = JSONObject()
                sessionObj.put("event_id", "sess_${audioTaskId}")
                sessionObj.put("type", "session.update")
                val sessionBody = JSONObject()
                sessionBody.put("modalities", org.json.JSONArray().put("text"))
                sessionBody.put("input_audio_format", "pcm")
                sessionBody.put("sample_rate", sampleRate)
                sessionBody.put("input_audio_transcription", JSONObject().apply {
                    put("language", "zh")
                })
                sessionBody.put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.0)
                    put("silence_duration_ms", 400)
                })
                sessionObj.put("session", sessionBody)
                ws.send(sessionObj.toString())

                audioRecord?.startRecording()
                recordingThread = Thread {
                    val buffer = ByteArray(3200)
                    try {
                        while (isRecording) {
                            val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                            if (bytesRead > 0 && isRecording) {
                                val chunk = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
                                val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                                val audioObj = JSONObject()
                                audioObj.put("event_id", "aud_${System.currentTimeMillis()}")
                                audioObj.put("type", "input_audio_buffer.append")
                                audioObj.put("audio", b64)
                                ws.send(audioObj.toString())
                            }
                        }
                    } catch (_: Exception) {}
                }.apply { start() }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (json.optString("type") == "conversation.item.input_audio_transcription.completed") {
                        val transcript = json.optString("transcript", "")
                        if (transcript.isNotEmpty()) {
                            runOnUiThread {
                                etDestination.setText(transcript)
                                etDestination.setSelection(transcript.length)
                                tvStatus.text = "地点已识别，请选择地图开始导航。"
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread { Toast.makeText(this@NavigationActivity,
                    "语音识别失败: ${t.localizedMessage}", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun stopVoiceRecording() {
        isRecording = false
        recordingThread?.interrupt()
        val finishJson = JSONObject().apply {
            put("event_id", "fin_${System.currentTimeMillis()}")
            put("type", "session.finish")
        }.toString()
        asrWebSocket?.send(finishJson)
        Handler(Looper.getMainLooper()).postDelayed({
            asrWebSocket?.close(1000, "ok"); asrWebSocket = null
            btnMic.setBackgroundColor(0x1AFFFFFF.toInt())
            try { audioRecord?.release(); audioRecord = null } catch (_: Exception) {}
            val text = etDestination.text.toString().trim()
            if (text.isNotEmpty()) {
                tvStatus.text = "地点已识别，请选择地图开始导航。"
            } else {
                tvStatus.text = "说出目的地，或手动输入地点后开始导航。"
            }
        }, 2000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>,
                                            grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_RECORD_AUDIO_CODE &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording()
        } else if (requestCode == PERMISSION_RECORD_AUDIO_CODE) {
            Toast.makeText(this, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    // ------ 导航 ------
    private fun onNavigateTapped() {
        val destination = etDestination.text.toString().trim()
        if (destination.isEmpty()) {
            Toast.makeText(this, "请输入或说出目的地", Toast.LENGTH_SHORT).show()
            return
        }
        saveRecentDestination(destination)

        if (!alwaysAsk && hasSelectedMap && preferredMapId.isNotEmpty()) {
            openMap(preferredMapId, destination)
            return
        }
        showMapSelection(destination)
    }

    private fun showMapSelection(destination: String) {
        val available = mapApps.filter { isAppInstalled(it.packageName) }

        if (available.isEmpty()) {
            // 没有地图 App 时使用系统 geo URI
            openSystemMap(destination)
            return
        }

        val items = available.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择地图")
            .setMessage(destination)
            .setItems(items) { _, which ->
                val map = available[which]
                openMap(map.id, destination)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openMap(mapId: String, destination: String) {
        preferredMapId = mapId
        hasSelectedMap = true
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_PREFERRED_MAP, mapId)
            .putBoolean(KEY_HAS_SELECTED, true).apply()
        refreshMapButtonTitle()

        val map = mapApps.find { it.id == mapId }
        if (map != null && map.packageName != null && isAppInstalled(map.packageName)) {
            val uriStr = String.format(map.uriScheme, URLEncoder.encode(destination, "UTF-8"))
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)))
                return
            } catch (_: Exception) {}
        }
        // 降级：用系统地图
        openSystemMap(destination)
    }

    private fun openSystemMap(destination: String) {
        try {
            val geoUri = "geo:0,0?q=${URLEncoder.encode(destination, "UTF-8")}"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(geoUri)))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的地图应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isAppInstalled(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun refreshMapButtonTitle() {
        if (!hasSelectedMap) {
            btnNavigate.text = "选择地图"
            return
        }
        btnNavigate.text = mapApps.find { it.id == preferredMapId }?.name ?: "选择地图"
    }

    // ------ 常用目的地 ------
    private fun saveRecentDestination(destination: String) {
        recentDestinations.remove(destination)
        recentDestinations.add(0, destination)
        if (recentDestinations.size > MAX_RECENT) {
            recentDestinations = recentDestinations.take(MAX_RECENT).toMutableList()
        }
        persistRecent()
        refreshHistory()
    }

    private fun refreshHistory() {
        layoutHistory.removeAllViews()

        if (recentDestinations.isEmpty()) {
            val emptyHint = TextView(this).apply {
                text = "导航过的地点会显示在这里，之后可一键复用。"
                setTextColor(0x73FFFFFF.toInt())
                textSize = 14f
            }
            layoutHistory.addView(emptyHint)
            return
        }

        for ((index, destination) in recentDestinations.withIndex()) {
            val btn = Button(this).apply {
                text = destination
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 15f
                background = null
                setBackgroundColor(0x14FFFFFF.toInt())
                val density = resources.displayMetrics.density
                val radius = 14 * density
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = radius
                    setColor(0x14FFFFFF.toInt())
                }
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                setPadding((18 * density).toInt(), 0, (18 * density).toInt(), 0)
                setOnClickListener {
                    etDestination.setText(destination)
                    etDestination.setSelection(destination.length)
                    tvStatus.text = "已填入常用地点，请选择地图开始导航。"
                }
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (48 * density).toInt())
                params.bottomMargin = (8 * density).toInt()
                layoutParams = params
            }
            layoutHistory.addView(btn)
        }
    }
}
