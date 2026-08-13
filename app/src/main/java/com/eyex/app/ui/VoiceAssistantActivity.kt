package com.eyex.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.eyex.app.R
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

class VoiceAssistantActivity : AppCompatActivity() {

    companion object {
        private const val CHAT_ENDPOINT = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val TTS_ENDPOINT = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        private const val API_KEY = "sk-1ecef1a03a0f4062a16b59a8e50b4399"
        private const val DEFAULT_MODEL = "qwen3-vl-flash"
        private const val TTS_MODEL = "qwen3-tts-flash"
        private const val TTS_VOICE = "Cherry"
        private const val PREFS_NAME = "voice_assistant_prefs"
        private const val KEY_SAVED_CHATS = "saved_chats"
        private const val KEY_LAST_SESSION = "last_session"
        private const val PERMISSION_RECORD_AUDIO_CODE = 201
    }

    data class ChatMessage(val role: String, val text: String)
    data class ChatSession(
        val chatId: String = UUID.randomUUID().toString(),
        var title: String = "新会话",
        var model: String = DEFAULT_MODEL,
        var saved: Boolean = false,
        var updatedAt: Long = System.currentTimeMillis(),
        val messages: MutableList<ChatMessage> = mutableListOf()
    )

    private data class ModelInfo(val id: String, val title: String)

    private val availableModels = listOf(
        ModelInfo("qwen3-vl-flash", "Qwen VL Flash"),
        ModelInfo("qwen-plus", "Qwen Plus"),
        ModelInfo("qwen-turbo", "Qwen Turbo")
    )

    // Views
    private lateinit var rvMessages: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var btnModelSwitch: TextView
    private lateinit var btnSaveChat: TextView
    private lateinit var tvPrompt: TextView

    // State
    private val chatSessions = mutableListOf<ChatSession>()
    private var currentChatIndex = -1
    private val messages get() = currentChat?.messages
    private val currentChat get() = chatSessions.getOrNull(currentChatIndex)
    private var currentRequestId = 0L
    private var mediaPlayer: MediaPlayer? = null

    // 录音 & ASR 状态
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var asrWebSocket: WebSocket? = null
    private var recordingThread: Thread? = null
    private var lastTranscript = ""
    private var audioTaskId = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val gson = Gson()
    private lateinit var adapter: MessageAdapter

    // ------ Activity Lifecycle ------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_assistant)

        // 设置状态栏与工具栏颜色一致
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = 0xFF0F1014.toInt()
        }

        initViews()
        loadLastSession()
        loadSavedChats()
        if (chatSessions.isEmpty()) {
            createNewChatSessionAndSwitch(true)
        } else {
            switchToChat(0)
        }
    }

    override fun onPause() {
        super.onPause()
        saveLastSession()
    }

    override fun onDestroy() {
        isRecording = false
        try { asrWebSocket?.close(1000, "destroy"); asrWebSocket = null } catch (_: Exception) {}
        try { audioRecord?.release(); audioRecord = null } catch (_: Exception) {}
        stopPlayback()
        super.onDestroy()
    }

    // ------ Init ------
    private fun initViews() {
        rvMessages = findViewById(R.id.rvMessages)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        btnModelSwitch = findViewById(R.id.btnModelSwitch)
        btnSaveChat = findViewById(R.id.btnSaveChat)
        tvPrompt = findViewById(R.id.tvPrompt)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnChatList).setOnClickListener { showChatListDialog() }
        btnSend.setOnClickListener { sendTextMessage() }
        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    btnMic.isPressed = true
                    startVoiceRecording()
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    btnMic.isPressed = false
                    if (isRecording) stopVoiceRecording()
                    true
                }
                else -> false
            }
        }
        btnModelSwitch.setOnClickListener { showModelSelector() }
        btnSaveChat.setOnClickListener { onSaveChatTapped() }

        adapter = MessageAdapter()
        rvMessages.layoutManager = LinearLayoutManager(this)
        rvMessages.adapter = adapter
    }

    // ------ Chat Session Management ------
    private fun loadSavedChats() {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_SAVED_CHATS, null) ?: return
        try {
            val type = object : TypeToken<List<ChatSession>>() {}.type
            val saved: List<ChatSession> = gson.fromJson(json, type)
            chatSessions.addAll(saved.map { it.copy(messages = it.messages.toMutableList()) })
        } catch (_: Exception) {}
    }

    private fun persistSavedChats() {
        val saved = chatSessions.filter { it.saved }.map {
            it.copy(messages = ArrayList(it.messages.filter { m -> m.role != "status" }))
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_SAVED_CHATS, gson.toJson(saved)).apply()
    }

    /** 保存当前会话（无论是否已保存），下次打开自动恢复 */
    private fun saveLastSession() {
        val chat = currentChat ?: return
        val clean = chat.copy(messages = ArrayList(chat.messages.filter { it.role != "status" }))
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_LAST_SESSION, gson.toJson(clean)).apply()
    }

    /** 加载上次的会话，插入到列表最前面 */
    private fun loadLastSession() {
        val json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_LAST_SESSION, null) ?: return
        try {
            val session: ChatSession = gson.fromJson(json, ChatSession::class.java)
            if (chatSessions.none { it.chatId == session.chatId }) {
                chatSessions.add(0, session.copy(messages = session.messages.toMutableList()))
            }
        } catch (_: Exception) {}
    }

    private fun newChatSession() = ChatSession(
        messages = mutableListOf(ChatMessage("assistant",
            "可以直接输入问题，或按住右侧话筒说话，松手发送。"))
    )

    private fun createNewChatSessionAndSwitch(switchImmediately: Boolean) {
        val chat = newChatSession()
        chatSessions.add(0, chat)
        if (switchImmediately) switchToChat(0)
    }

    private fun switchToChat(index: Int) {
        if (index !in chatSessions.indices) return
        stopPlayback()
        currentChatIndex = index
        etInput.setText("")
        adapter.notifyDataSetChanged()
        updateUI()
        scrollToBottom()
    }

    private fun derivedTitle(): String {
        messages?.forEach { msg ->
            if (msg.role == "user") {
                val trimmed = msg.text.trim()
                if (trimmed.isNotEmpty()) return if (trimmed.length > 12) "${trimmed.take(12)}…" else trimmed
            }
        }
        return "新会话"
    }

    private fun updateUI() {
        val chat = currentChat ?: return
        tvPrompt.text = if (chat.saved) "已保存 · ${chat.title}" else "按住话筒说话"
        btnSaveChat.text = if (chat.saved) "删除" else "保存"
        val modelId = chat.model
        btnModelSwitch.text = availableModels.find { it.id == modelId }?.title ?: "Qwen VL Flash"
    }

    private fun currentChatModelId() = currentChat?.model ?: DEFAULT_MODEL

    private fun showModelSelector() {
        val currentModel = currentChatModelId()
        val items = availableModels.map { m ->
            val prefix = if (m.id == currentModel) "✓ " else ""
            "$prefix${m.title}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择模型")
            .setItems(items) { _, which ->
                val chat = currentChat ?: return@setItems
                chat.model = availableModels[which].id
                chat.updatedAt = System.currentTimeMillis()
                if (chat.saved) persistSavedChats()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showChatListDialog() {
        val items = mutableListOf("新建会话")
        items.addAll(chatSessions.map { c -> if (c.saved) c.title else "${c.title}（未保存）" })
        AlertDialog.Builder(this)
            .setTitle("会话")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) createNewChatSessionAndSwitch(true)
                else switchToChat(which - 1)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun onSaveChatTapped() {
        val chat = currentChat ?: return
        if (chat.saved) confirmDeleteCurrentChat() else showSaveChatDialog()
    }

    private fun showSaveChatDialog() {
        val chat = currentChat ?: return
        val defaultTitle = derivedTitle()
        val editText = EditText(this).apply {
            setText(if (chat.title != "新会话") chat.title else defaultTitle)
            hint = "例如：出行问答"
        }
        AlertDialog.Builder(this)
            .setTitle("保存会话")
            .setMessage("给这段对话起个名字")
            .setView(editText)
            .setPositiveButton("保存") { _, _ ->
                chat.title = editText.text.toString().trim().ifEmpty { defaultTitle }
                chat.saved = true
                chat.updatedAt = System.currentTimeMillis()
                persistSavedChats()
                updateUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteCurrentChat() {
        AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("删除后，这条已保存对话将从本地移除。")
            .setPositiveButton("删除") { _, _ -> deleteCurrentChatSession() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteCurrentChatSession() {
        if (currentChatIndex !in chatSessions.indices) return
        chatSessions.removeAt(currentChatIndex)
        persistSavedChats()
        if (chatSessions.isEmpty()) createNewChatSessionAndSwitch(true)
        else switchToChat(currentChatIndex.coerceAtMost(chatSessions.size - 1))
    }

    // ------ Send Text ------
    private fun sendTextMessage() {
        val text = etInput.text.toString().trim()
        if (text.isEmpty()) return
        etInput.setText("")
        appendMessage(ChatMessage("user", text))
        requestAssistantReply(text)
    }

    // ------ Mic / ASR (DashScope WebSocket 实时语音识别) ------

    private fun startVoiceRecording() {
        // 检查麦克风权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO),
                PERMISSION_RECORD_AUDIO_CODE)
            return
        }

        isRecording = true
        lastTranscript = ""
        audioTaskId = UUID.randomUUID().toString()
        btnMic.setBackgroundColor(0xFFEE5F37.toInt())
        btnMic.setImageResource(android.R.drawable.ic_media_pause)
        tvPrompt.text = "正在聆听，松手发送"

        // 1. 启动 AudioRecord
        val sampleRate = 16000
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
            6400
        )
        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)

        // 2. 连接 WebSocket ASR（model 作为 URL query 参数）
        val wsRequest = Request.Builder()
            .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime")
            .addHeader("Authorization", "bearer $API_KEY")
            .build()

        asrWebSocket = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
            .newWebSocket(wsRequest, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    android.util.Log.i("ASR", "WebSocket 已连接")

                    // 发送 session.update
                    val sessionObj = JSONObject()
                    sessionObj.put("event_id", "sess_${audioTaskId}")
                    sessionObj.put("type", "session.update")
                    val sessionBody = JSONObject()
                    sessionBody.put("modalities", JSONArray().put("text"))
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
                    android.util.Log.i("ASR", "session.update 已发送")

                    // 开始录制并发送音频
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
                        val type = json.optString("type", "")
                        android.util.Log.i("ASR", "收到消息 type=$type  data=$text")
                        when (type) {
                            "conversation.item.input_audio_transcription.completed" -> {
                                val transcript = json.optString("transcript", "")
                                if (transcript.isNotEmpty()) {
                                    lastTranscript = transcript
                                    runOnUiThread {
                                        etInput.setText(transcript)
                                        etInput.setSelection(transcript.length)
                                    }
                                }
                            }
                            "error" -> {
                                val errMsg = json.optJSONObject("error")?.optString("message", "") ?: ""
                                runOnUiThread {
                                    Toast.makeText(this@VoiceAssistantActivity,
                                        "语音识别错误: $errMsg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    android.util.Log.i("ASR", "连接关闭 code=$code reason=$reason")
                    runOnUiThread { cleanupRecording() }
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val err = t.localizedMessage ?: "未知错误"
                    android.util.Log.e("ASR", "连接失败: $err")
                    runOnUiThread {
                        Toast.makeText(this@VoiceAssistantActivity,
                            "语音识别连接失败: $err", Toast.LENGTH_LONG).show()
                        cleanupRecording()
                    }
                }
            })
    }

    private fun stopVoiceRecording() {
        isRecording = false
        recordingThread?.interrupt()

        // 发送 session.finish 正常结束会话
        val finishJson = JSONObject().apply {
            put("event_id", "fin_${System.currentTimeMillis()}")
            put("type", "session.finish")
        }.toString()
        asrWebSocket?.send(finishJson)

        // 等服务端返回最终结果后关闭
        Handler(Looper.getMainLooper()).postDelayed({
            asrWebSocket?.close(1000, "user_stop")
            asrWebSocket = null
            cleanupRecording()

            // 自动发送识别的文字
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                sendTextMessage()
            } else {
                tvPrompt.text = "按住话筒说话"
            }
        }, 2000)
    }

    private fun cleanupRecording() {
        try {
            audioRecord?.apply { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop(); release() }
        } catch (_: Exception) {}
        audioRecord = null
        recordingThread = null
        btnMic.setBackgroundColor(0x1AFFFFFF.toInt())
        btnMic.setImageResource(android.R.drawable.ic_btn_speak_now)
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

    // ------ AI Chat Request ------
    private fun requestAssistantReply(text: String) {
        if (text.isEmpty()) return
        currentRequestId++
        val requestId = currentRequestId
        appendMessage(ChatMessage("status", "正在思考..."))

        val requestMessages = JSONArray()
        requestMessages.put(JSONObject().apply {
            put("role", "system")
            put("content", "你是智能眼镜首页的语音助手。请用简体中文直接回答用户问题，简洁、自然、口语化。")
        })

        messages?.forEach { msg ->
            if (msg.role == "status" || msg.text.isEmpty()) return@forEach
            if (msg.role != "user" && msg.role != "assistant") return@forEach
            requestMessages.put(JSONObject().apply {
                put("role", msg.role)
                put("content", msg.text)
            })
        }

        val payload = JSONObject().apply {
            put("model", currentChatModelId())
            put("messages", requestMessages)
        }

        val request = Request.Builder()
            .url(CHAT_ENDPOINT)
            .post(RequestBody.create(JSON_MEDIA_TYPE, payload.toString()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $API_KEY")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (currentRequestId != requestId) return
                runOnUiThread { replaceLatestStatus(ChatMessage("assistant", "请求失败，请确认网络可用后重试。")) }
            }
            override fun onResponse(call: Call, response: Response) {
                if (currentRequestId != requestId) return
                val body = response.body?.string()
                if (response.code != 200 || body.isNullOrEmpty()) {
                    runOnUiThread { replaceLatestStatus(ChatMessage("assistant", "服务异常（HTTP ${response.code}）")) }
                    return
                }
                try {
                    val json = JSONObject(body)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val content = choice?.optJSONObject("message")?.optString("content", "") ?: ""
                    if (content.isEmpty()) {
                        runOnUiThread { replaceLatestStatus(ChatMessage("assistant", "模型未返回有效内容，请稍后重试。")) }
                        return
                    }
                    runOnUiThread {
                        removeLatestStatus()
                        appendMessage(ChatMessage("assistant", content))
                    }
                    synthesizeSpeech(content, requestId)
                } catch (_: Exception) {
                    runOnUiThread { replaceLatestStatus(ChatMessage("assistant", "解析响应失败，请稍后重试。")) }
                }
            }
        })
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
            .post(RequestBody.create(JSON_MEDIA_TYPE, payload.toString()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $API_KEY")
            .build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {
                if (currentRequestId != requestId) return
                try {
                    val json = JSONObject(response.body?.string() ?: return)
                    val url = json.optJSONObject("output")
                        ?.optJSONObject("audio")
                        ?.optString("url", "") ?: ""
                    if (url.isNotEmpty()) playAudioFromUrl(url, requestId)
                } catch (_: Exception) {}
            }
        })
    }

    private fun playAudioFromUrl(url: String, requestId: Long) {
        val httpsUrl = if (url.startsWith("http://")) "https://${url.removePrefix("http://")}" else url
        runOnUiThread {
            if (currentRequestId != requestId) return@runOnUiThread
            stopPlayback()
            mediaPlayer = MediaPlayer().apply {
                setOnPreparedListener { mp -> if (currentRequestId == requestId) mp.start() }
                setOnErrorListener { _, _, _ -> true }
                setOnCompletionListener { stopPlayback() }
                try { setDataSource(httpsUrl); prepareAsync() } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun stopPlayback() {
        try {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
            mediaPlayer = null
        } catch (_: Exception) {}
    }

    // ------ Message Management ------
    private fun appendMessage(msg: ChatMessage) {
        messages?.add(msg)
        val chat = currentChat ?: return
        chat.updatedAt = System.currentTimeMillis()
        if (msg.role != "status" && chat.title == "新会话") chat.title = derivedTitle()
        if (chat.saved) persistSavedChats()
        adapter.notifyItemInserted((messages?.size ?: 1) - 1)
        scrollToBottom()
        updateUI()
    }

    private fun replaceLatestStatus(msg: ChatMessage) {
        if (messages?.lastOrNull()?.role == "status") messages?.removeAt(messages!!.size - 1)
        appendMessage(msg)
    }

    private fun removeLatestStatus() {
        if (messages?.lastOrNull()?.role == "status") {
            messages?.removeAt(messages!!.size - 1)
            adapter.notifyDataSetChanged()
        }
    }

    private fun scrollToBottom() {
        val count = adapter.itemCount
        if (count > 0) rvMessages.post { rvMessages.smoothScrollToPosition(count - 1) }
    }

    // ------ Message Adapter ------
    inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {
        private val density = resources.displayMetrics.density

        inner class VH(itemView: View, val bubbleLayout: LinearLayout, val tvMessage: TextView) :
            RecyclerView.ViewHolder(itemView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val bubble = LinearLayout(container.context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    (14 * density).toInt(), (14 * density).toInt(),
                    (14 * density).toInt(), (14 * density).toInt()
                )
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(bubble)
            val tv = TextView(container.context).apply {
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            bubble.addView(tv)
            return VH(container, bubble, tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg = messages?.getOrNull(position) ?: return
            holder.tvMessage.text = msg.text

            val maxBubbleWidth = resources.displayMetrics.widthPixels - (92 * density).toInt()
            holder.tvMessage.maxWidth = maxBubbleWidth

            val bubbleLp = holder.bubbleLayout.layoutParams as FrameLayout.LayoutParams
            bubbleLp.width = FrameLayout.LayoutParams.WRAP_CONTENT
            bubbleLp.height = FrameLayout.LayoutParams.WRAP_CONTENT

            val drawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (18 * density)
            }

            when (msg.role) {
                "user" -> {
                    bubbleLp.gravity = Gravity.END
                    drawable.setColor(0xFFEE5F37.toInt())
                    holder.tvMessage.setTextColor(0xFFFFFFFF.toInt())
                }
                "status" -> {
                    bubbleLp.gravity = Gravity.START
                    drawable.setColor(0x14FFFFFF.toInt())
                    holder.tvMessage.setTextColor(0xB8FFFFFF.toInt())
                }
                else -> {
                    bubbleLp.gravity = Gravity.START
                    drawable.setColor(0xFFEBEBEB.toInt())
                    holder.tvMessage.setTextColor(0xFF1A1C1E.toInt())
                }
            }
            holder.bubbleLayout.layoutParams = bubbleLp
            holder.bubbleLayout.background = drawable
        }

        override fun getItemCount() = messages?.size ?: 0
    }
}
