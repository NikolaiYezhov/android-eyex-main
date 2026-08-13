package com.eyex.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.eyex.app.R
import com.eyex.app.ble.QCBluetoothManager
import kotlin.jvm.java
import android.content.Intent
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // 提前实例化好四个 Fragment（避免每次点击都重新创建）
    private val homeFragment = HomeFragment()
    private val aiFragment = AIFragment()
    private val albumFragment = AlbumFragment()
    private val mineFragment = MineFragment()

    // 记录当前正在显示的 Fragment
    private var activeFragment: Fragment = homeFragment
    private var launchedVoiceAssistant = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.eyex.app.ble.TestHelper.enableMockMode()
        if (!com.eyex.app.ble.TestHelper.isMockMode()) {
            com.eyex.app.ble.TestHelper.enableMockMode()
        }
        setContentView(R.layout.activity_main)


        // 🚀 终极杀手锏：App 刚启动时，先偷偷拆掉上次编译遗留的僵尸 WiFi 局域网。
        // 等用户操作完蓝牙、去点"相册"时，系统早就清理得干干净净了！
        try {
            com.oudmon.wifi.GlassesControl.getInstance(application)?.releaseGlassesControl()
        } catch (e: Exception) {}


        // 隐藏顶部的默认 ActionBar（对应 iOS 的 NavigationBarHidden = YES）
        supportActionBar?.hide()

        val bottomNavView: BottomNavigationView = findViewById(R.id.bottomNavView)

        // 把四个 Fragment 都加到容器里，但先把后三个隐藏起来
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragmentContainer, mineFragment, "4").hide(mineFragment)
            add(R.id.fragmentContainer, albumFragment, "3").hide(albumFragment)
            add(R.id.fragmentContainer, aiFragment, "2").hide(aiFragment)
            add(R.id.fragmentContainer, homeFragment, "1")
        }.commit()

        // 监听底部导航栏的点击事件
        bottomNavView.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> switchFragment(homeFragment)
                R.id.nav_ai -> {
                    launchedVoiceAssistant = true
                    startActivity(Intent(this, VoiceAssistantActivity::class.java))
                }
                R.id.nav_album -> switchFragment(albumFragment)
                R.id.nav_mine -> switchFragment(mineFragment)
            }
            true
        }


        // 🚀 新增：动态申请 Android 13+ 必须的"附近设备"权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ), 101)
        }

    }

    // 切换 Fragment 的核心逻辑
    private fun switchFragment(targetFragment: Fragment) {
        if (targetFragment != activeFragment) {
            supportFragmentManager.beginTransaction()
                .hide(activeFragment)
                .show(targetFragment)
                .commit()
            activeFragment = targetFragment
        }
    }


    override fun onResume() {
        super.onResume()
        // 从语音助手返回时，切回首页
        if (launchedVoiceAssistant) {
            launchedVoiceAssistant = false
            switchFragment(homeFragment)
            findViewById<BottomNavigationView>(R.id.bottomNavView).selectedItemId = R.id.nav_home
        }
    }

    // 🚀 新增强力除垢 3：当 App 被杀掉或退出时，强制清理系统 P2P 缓存
    override fun onDestroy() {
        super.onDestroy()

        try {
            // 告诉 SDK 彻底放手，它会去断开 socket 并移除 WiFi 直连群组
            com.oudmon.wifi.GlassesControl.getInstance(application)?.releaseGlassesControl()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



}

// ==========================================
// 下面是四个占位的 Fragment，对应你 iOS 里的各个 TabVC
// ==========================================

// ... (前面部分保持不变) ...

class HomeFragment : Fragment() {

    private var isRecording = false // 记录录像状态
    private var isAudioRecording = false // 录音状态

    // AI 识图状态
    private var currentAITab = "identify"
    private var aiRequestId = 0L
    private var aiImageBytes: ByteArray? = null
    private val aiHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 翻译状态
    private var translateTargetLang = "English"
    private val supportedLanguages = listOf(
        "English" to "英语", "German" to "德语", "Italian" to "意大利语",
        "Portuguese" to "葡萄牙语", "Spanish" to "西班牙语", "Japanese" to "日语",
        "Korean" to "韩语", "French" to "法语", "Russian" to "俄语", "Chinese" to "中文"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1. 找控件
        val tvDeviceName = view.findViewById<TextView>(R.id.tvDeviceName)
        val tvBattery = view.findViewById<TextView>(R.id.tvBattery)

        val btnCardPhoto = view.findViewById<androidx.cardview.widget.CardView>(R.id.btnCardPhoto)
        val tvPhotoText = view.findViewById<TextView>(R.id.tvPhotoText)

        val btnCardVideo = view.findViewById<androidx.cardview.widget.CardView>(R.id.btnCardVideo)
        val tvVideoText = view.findViewById<TextView>(R.id.tvVideoText)

        val btnCardAudio = view.findViewById<androidx.cardview.widget.CardView>(R.id.btnCardAudio)
        val tvAudioText = view.findViewById<TextView>(R.id.tvAudioText)


        // 动态设置设备名（如果有缓存的话）
        QCBluetoothManager.instance.activeDevice?.name?.let {
            tvDeviceName.text = it
        }

        // 2. 订阅电量变化
        QCBluetoothManager.instance.batteryLiveData.observe(viewLifecycleOwner) { battery ->
            tvBattery.text = "$battery%"
        }
        QCBluetoothManager.instance.isChargingLiveData.observe(viewLifecycleOwner) { isCharging ->
            if (isCharging) {
                tvBattery.text = tvBattery.text.toString() + " ⚡"
            }
        }
        // 静默请求一次电量
        QCBluetoothManager.instance.fetchBattery()

        QCBluetoothManager.instance.fetchDeviceInfo() // 顺便查一下版本

        // 点击设备卡片进入设置
        tvDeviceName.setOnClickListener {
            startActivity(Intent(requireContext(), DeviceSettingsActivity::class.java))
        }

        // 4. 拍照金刚键
        btnCardPhoto.setOnClickListener {
            tvPhotoText.text = "发送中..."
            btnCardPhoto.isEnabled = false

            QCBluetoothManager.instance.takePhoto { isSuccess, message ->
                activity?.runOnUiThread {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    tvPhotoText.text = "拍照"
                    btnCardPhoto.isEnabled = true
                }
            }
        }

        // 5. 录像金刚键
        btnCardVideo.setOnClickListener {
            // 先释放 WiFi 控制权
            com.oudmon.wifi.GlassesControl.getInstance(requireActivity().application)?.releaseGlassesControl()

            btnCardVideo.postDelayed({
                if (!isRecording) {
                    QCBluetoothManager.instance.controlVideo(true) { status ->
                        activity?.runOnUiThread {
                            tvVideoText.text = "停止录像"
                            tvVideoText.setTextColor(android.graphics.Color.RED) // 变红警示
                            isRecording = true
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    QCBluetoothManager.instance.controlVideo(false) { status ->
                        activity?.runOnUiThread {
                            tvVideoText.text = "录像"
                            tvVideoText.setTextColor(android.graphics.Color.WHITE) // 恢复白色
                            isRecording = false
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, 300)
        }

        // 6. 录音金刚键
        btnCardAudio.setOnClickListener {
            com.oudmon.wifi.GlassesControl.getInstance(requireActivity().application)?.releaseGlassesControl()

            btnCardAudio.postDelayed({
                if (!isAudioRecording) {
                    QCBluetoothManager.instance.controlAudio(true) { status ->
                        activity?.runOnUiThread {
                            tvAudioText.text = "停止录音"
                            tvAudioText.setTextColor(android.graphics.Color.RED)
                            isAudioRecording = true
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    QCBluetoothManager.instance.controlAudio(false) { status ->
                        activity?.runOnUiThread {
                            tvAudioText.text = "录音"
                            tvAudioText.setTextColor(android.graphics.Color.WHITE)
                            isAudioRecording = false
                            Toast.makeText(context, status, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }, 300)
        }


        // ========== 🚀 核心逻辑：AI 工作区顶部 4 个 Tab 的点击与高亮切换 ==========

        // 1. 把四个标签找出来
        val tabIdentify = view.findViewById<TextView>(R.id.tabIdentify)
        val tabTranslate = view.findViewById<TextView>(R.id.tabTranslate)
        val tabFavorite = view.findViewById<TextView>(R.id.tabFavorite)
        val tabShare = view.findViewById<TextView>(R.id.tabShare)
        // 2. 把它们放进一个列表里，方便后面批量操作
        val allTabs = listOf(tabIdentify, tabTranslate, tabFavorite, tabShare)

        // 3. 搞一个负责"一键换装"的神奇函数
        fun switchTab(selectedTab: TextView) {
            allTabs.forEach { tab ->
                if (tab == selectedTab) {
                    // 选中状态：纯白背景，黑色字，加粗
                    tab.setBackgroundColor(android.graphics.Color.WHITE)
                    tab.setTextColor(android.graphics.Color.BLACK)
                    tab.setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    // 未选中状态：透明/水波纹背景，深灰色字，取消加粗
                    val outValue = android.util.TypedValue()
                    context?.theme?.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    tab.setBackgroundResource(outValue.resourceId) // 巧妙恢复安卓自带的点击水波纹

                    tab.setTextColor(android.graphics.Color.parseColor("#666666"))
                    tab.setTypeface(null, android.graphics.Typeface.NORMAL)
                }
            }
        }

        // 4. 找到 AI 结果展示控件
        val ivAIShow = view.findViewById<ImageView>(R.id.ivAIShow)
        val tvAIResult = view.findViewById<TextView>(R.id.tvAIResult)

        // 5. 重新绑定点击事件
        tabIdentify.setOnClickListener {
            switchTab(tabIdentify)
            currentAITab = "identify"
            tvAIResult.visibility = View.GONE
            ivAIShow.visibility = View.GONE
            view.findViewById<Button>(R.id.btnStartAi).text = "开始识图"
            view.findViewById<Button>(R.id.btnAiCollection).text = "查看收藏"
        }

        tabTranslate.setOnClickListener {
            switchTab(tabTranslate)
            currentAITab = "translate"
            ivAIShow.visibility = View.GONE
            tvAIResult.visibility = View.VISIBLE
            tvAIResult.text = "按住下方按钮说话，松手翻译"
            val langName = supportedLanguages.find { it.first == translateTargetLang }?.second ?: "English"
            view.findViewById<Button>(R.id.btnStartAi).text = "按住 翻译为 $langName"
            view.findViewById<Button>(R.id.btnAiCollection).text = "$langName ▼"
        }

        tabFavorite.setOnClickListener {
            switchTab(tabFavorite)
            currentAITab = "favorite"
            ivAIShow.visibility = View.GONE
            // 如果有识图结果，保存到收藏
            val resultText = tvAIResult.text?.toString() ?: ""
            if (aiImageBytes != null && resultText.isNotEmpty()
                && !resultText.startsWith("正在") && !resultText.startsWith("请按")) {
                saveAIFavorite(aiImageBytes!!, resultText)
                tvAIResult.text = "✅ 已收藏"
            } else {
                tvAIResult.text = "暂无识图结果可收藏"
            }
            tvAIResult.visibility = View.VISIBLE
            view.findViewById<Button>(R.id.btnStartAi).text = "查看收藏"
            view.findViewById<Button>(R.id.btnAiCollection).text = "查看收藏"
        }

        tabShare.setOnClickListener {
            switchTab(tabShare)
            currentAITab = "share"
            ivAIShow.visibility = View.GONE
            tvAIResult.visibility = View.VISIBLE
            shareAIResult()
            view.findViewById<Button>(R.id.btnStartAi).text = "分享结果"
            view.findViewById<Button>(R.id.btnAiCollection).text = "查看收藏"
        }

        // ========== AI 工作区按钮 ==========
        val btnStartAi = view.findViewById<Button>(R.id.btnStartAi)
        val btnAiCollection = view.findViewById<Button>(R.id.btnAiCollection)

        // 翻译标签：长按录音、松手停止（类似微信语音）
        // 其他标签：正常点击
        btnStartAi.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (currentAITab == "translate") {
                        btnStartAi.isPressed = true
                        onTranslateStart(tvAIResult)
                        true
                    } else {
                        false // 非翻译模式交给 onClick
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (currentAITab == "translate" && translateIsRecording) {
                        btnStartAi.isPressed = false
                        onTranslateStop(btnStartAi, btnAiCollection)
                        true
                    } else if (currentAITab != "translate") {
                        // 非翻译模式：释放时触发点击动作
                        when (currentAITab) {
                            "identify" -> startAIPhoto(ivAIShow, tvAIResult)
                            "favorite" -> showAIFavorites(ivAIShow, tvAIResult)
                            "share" -> shareAIResult()
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        btnAiCollection.setOnClickListener {
            when (currentAITab) {
                "translate" -> showLanguagePicker(tvAIResult, btnStartAi, btnAiCollection)
                else -> showAIFavorites(ivAIShow, tvAIResult)
            }
        }

        // 🚀 语音助手按钮：跳转到语音助手页
        view.findViewById<androidx.cardview.widget.CardView>(R.id.btnAiVoice).setOnClickListener {
            startActivity(Intent(requireContext(), VoiceAssistantActivity::class.java))
        }

        // 🚀 语音导航按钮：跳转到语音导航页
        view.findViewById<androidx.cardview.widget.CardView>(R.id.btnAiNav).setOnClickListener {
            startActivity(Intent(requireContext(), NavigationActivity::class.java))
        }

        // 🚀 游戏辅助按钮：跳转到游戏辅助选择页
        view.findViewById<androidx.cardview.widget.CardView>(R.id.btnAiGame).setOnClickListener {
            startActivity(Intent(requireContext(), GameAssistSelectionActivity::class.java))
        }

    }

    // ========== AI 识图：请求缩略图 → 通过设备通知接收 → 大模型分析 ==========
    // SDK 标准流程：
    // 1. 发送 AI 拍照指令 0x02,0x01,0x06 → 响应只确认指令状态（不含图片数据）
    // 2. 眼镜拍完照后通过 GlassesDeviceNotifyListener 发通知 (loadData[6]==0x02)
    // 3. QCBluetoothManager 的通知监听器自动调 getPictureThumbnails 获取 JPEG → 投到 latestBitmap
    // 4. 这里只需观察 latestBitmap 即可拿到图片
    private fun startAIPhoto(ivShow: ImageView, tvResult: TextView) {
        val device = QCBluetoothManager.instance.activeDevice
        if (device == null) {
            Toast.makeText(context, "请先连接眼镜", Toast.LENGTH_SHORT).show()
            return
        }
        aiImageBytes = null // null = 正在等待缩略图
        ivShow.visibility = View.GONE
        tvResult.visibility = View.VISIBLE
        tvResult.text = "正在拍照..."
        view?.findViewById<Button>(R.id.btnStartAi)?.isEnabled = false

        // 清空上次的 bitmap，避免拿到旧图
        QCBluetoothManager.instance.latestBitmap.value = null

        // 观察 latestBitmap：设备通知回调取到图后会投递到这里
        lateinit var bitmapObserver: androidx.lifecycle.Observer<android.graphics.Bitmap?>
        bitmapObserver = androidx.lifecycle.Observer { bitmap ->
            if (bitmap != null && aiImageBytes == null) {
                QCBluetoothManager.instance.latestBitmap.removeObserver(bitmapObserver)
                onThumbnailReceived(bitmap, ivShow, tvResult)
            }
        }
        QCBluetoothManager.instance.latestBitmap.observeForever(bitmapObserver)

        // 发送 AI 拍照指令 — 响应仅含状态码，图片走设备通知通道
        com.oudmon.ble.base.communication.LargeDataHandler.getInstance().glassesControl(
            byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02, 0x02)
        ) { _, it ->
            val errCode = it.errorCode.toInt() and 0xFF
            if (it.dataType != 1 || (errCode != 0 && errCode != 255)) {
                // 指令发送失败，清理
                QCBluetoothManager.instance.latestBitmap.removeObserver(bitmapObserver)
                activity?.runOnUiThread {
                    aiImageBytes = null
                    tvResult.text = "指令发送失败，请重试"
                    view?.findViewById<Button>(R.id.btnStartAi)?.isEnabled = true
                }
            } else {
                android.util.Log.e("QCBluetooth", "AI 拍照指令已送达眼镜，等待缩略图通知...")
            }
        }

        // 15 秒超时保护：aiImageBytes 还是 null 说明一直没收到图
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            QCBluetoothManager.instance.latestBitmap.removeObserver(bitmapObserver)
            if (aiImageBytes == null) {
                aiImageBytes = byteArrayOf() // 标记超时，防二次进入
                activity?.runOnUiThread {
                    tvResult.text = "拍照超时，请重试"
                    view?.findViewById<Button>(R.id.btnStartAi)?.isEnabled = true
                }
            }
        }, 15000)
    }

    private fun onThumbnailReceived(bitmap: android.graphics.Bitmap, ivShow: ImageView, tvResult: TextView) {
        aiImageBytes = byteArrayOf() // 标记已收到
        ivShow.setImageBitmap(bitmap)
        ivShow.visibility = View.VISIBLE
        tvResult.text = "正在分析图片..."
        analyzeWithAI(bitmap, ivShow, tvResult)
    }

    private fun analyzeWithAI(bitmap: Bitmap, ivShow: ImageView, tvResult: TextView) {
        val maxDim = 768f
        val scale = minOf(1.0f, maxDim / maxOf(bitmap.width, bitmap.height).toFloat())
        val dstW = maxOf(1, (bitmap.width * scale).toInt())
        val dstH = maxOf(1, (bitmap.height * scale).toInt())
        val resized = if (scale < 1.0f) Bitmap.createScaledBitmap(bitmap, dstW, dstH, true) else bitmap
        val output = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 82, output)
        val imageData = output.toByteArray()
        aiImageBytes = imageData

        aiRequestId++
        val requestId = aiRequestId
        val b64 = Base64.encodeToString(imageData, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$b64"

        val payload = JSONObject().apply {
            put("model", "qwen3-vl-flash")
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "你是一个图像描述助手，请用简体中文简洁准确描述图片内容，返回30字以内，不要展开说明。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply { put("type", "text"); put("text", "请用30字以内描述这张图片，只返回简洁描述。") })
                        put(JSONObject().apply { put("type", "image_url"); put("image_url", JSONObject().apply { put("url", dataUrl) }) })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .post(RequestBody.create("application/json".toMediaType(), payload.toString()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer sk-1ecef1a03a0f4062a16b59a8e50b4399")
            .build()

        aiHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (aiRequestId != requestId) return
                activity?.runOnUiThread {
                    tvResult.text = "分析失败，请检查网络后重试。"
                    view?.findViewById<Button>(R.id.btnStartAi)?.isEnabled = true
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (aiRequestId != requestId) return
                val body = response.body?.string()
                if (response.code != 200 || body.isNullOrEmpty()) {
                    activity?.runOnUiThread {
                        tvResult.text = "服务异常（HTTP ${response.code}）"
                        view?.findViewById<Button>(R.id.btnStartAi)?.isEnabled = true
                    }
                    return
                }
                try {
                    val json = JSONObject(body)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val content = choice?.optJSONObject("message")?.optString("content", "") ?: ""
                    activity?.runOnUiThread {
                        tvResult.text = content
                        view?.findViewById<Button>(R.id.btnStartAi)?.isEnabled = true
                    }
                } catch (_: Exception) {
                    activity?.runOnUiThread {
                        tvResult.text = "解析响应失败"
                        view?.findViewById<Button>(R.id.btnStartAi)?.isEnabled = true
                    }
                }
            }
        })
    }

    private fun saveAIFavorite(imageBytes: ByteArray, text: String) {
        val prefs = requireContext().getSharedPreferences("ai_home_favorites", 0)
        val json = prefs.getString("items", null)
        val type = object : com.google.gson.reflect.TypeToken<MutableList<Map<String, String>>>() {}.type
        val items: MutableList<Map<String, String>> = if (json != null)
            com.google.gson.Gson().fromJson(json, type) ?: mutableListOf()
        else mutableListOf()
        items.add(0, mapOf(
            "image" to android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP),
            "text" to text,
            "time" to System.currentTimeMillis().toString()
        ))
        prefs.edit().putString("items", com.google.gson.Gson().toJson(items)).apply()
        Toast.makeText(context, "✅ 已收藏", Toast.LENGTH_SHORT).show()
    }

    private fun showAIFavorites(ivShow: ImageView, tvResult: TextView) {
        val prefs = requireContext().getSharedPreferences("ai_home_favorites", 0)
        val json = prefs.getString("items", null) ?: run {
            Toast.makeText(context, "暂无收藏", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, String>>>() {}.type
            val items: List<Map<String, String>> = com.google.gson.Gson().fromJson(json, type)
            if (items.isEmpty()) {
                Toast.makeText(context, "暂无收藏", Toast.LENGTH_SHORT).show()
                return
            }
            val names = items.map { it["text"]?.take(20) ?: "..." }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle("收藏列表")
                .setItems(names) { _, which ->
                    val item = items[which]
                    tvResult.text = item["text"] ?: ""
                    val b64 = item["image"] ?: ""
                    if (b64.isNotEmpty()) {
                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ivShow.setImageBitmap(bmp)
                        ivShow.visibility = View.VISIBLE
                    }
                }
                .setPositiveButton("关闭", null)
                .show()
        } catch (_: Exception) {
            Toast.makeText(context, "读取收藏失败", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== 翻译：语音输入 → ASR → 翻译 → 显示 ==========
    private var translateIsRecording = false
    private var translateRecordThread: Thread? = null
    private var translateAudioRecord: android.media.AudioRecord? = null
    private var translateWs: okhttp3.WebSocket? = null

    // 长按开始录音
    private fun onTranslateStart(tvResult: TextView) {
        if (translateIsRecording) return
        startTranslateRecording(tvResult)
    }

    // 松手停止录音
    private fun onTranslateStop(btnStart: Button, btnLang: Button) {
        if (!translateIsRecording) return
        stopTranslateRecording()
        val langName = supportedLanguages.find { it.first == translateTargetLang }?.second ?: "English"
        btnStart.text = "按住 翻译为 $langName"
        btnLang.text = "$langName ▼"
        btnLang.isEnabled = true
    }

    private fun showLanguagePicker(tvResult: TextView, btnStart: Button, btnLang: Button) {
        val langNames = supportedLanguages.map { it.second }.toTypedArray()
        val currentIdx = supportedLanguages.indexOfFirst { it.first == translateTargetLang }.coerceAtLeast(0)
        AlertDialog.Builder(requireContext())
            .setTitle("目标语言")
            .setSingleChoiceItems(langNames, currentIdx) { dialog, which ->
                translateTargetLang = supportedLanguages[which].first
                val name = supportedLanguages[which].second
                btnLang.text = "$name ▼"
                btnStart.text = "按住 翻译为 $name"
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startTranslateRecording(tvResult: TextView) {
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
            return
        }
        translateIsRecording = true
        val langName = supportedLanguages.find { it.first == translateTargetLang }?.second ?: "English"
        tvResult.text = "请说话，翻译为 $langName"
        view?.findViewById<Button>(R.id.btnStartAi)?.text = "🎤 录音中... 松手停止"
        view?.findViewById<Button>(R.id.btnAiCollection)?.isEnabled = false

        val sampleRate = 16000
        val bufferSize = maxOf(
            android.media.AudioRecord.getMinBufferSize(sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT) * 2, 6400)
        translateAudioRecord = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,
            sampleRate, android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT, bufferSize)

        val audioTaskId = UUID.randomUUID().toString()
        val wsRequest = okhttp3.Request.Builder()
            .url("wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime")
            .addHeader("Authorization", "bearer sk-1ecef1a03a0f4062a16b59a8e50b4399")
            .build()

        translateWs = okhttp3.OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
            .newWebSocket(wsRequest, object : okhttp3.WebSocketListener() {
                override fun onOpen(ws: okhttp3.WebSocket, response: okhttp3.Response) {
                    val sessionObj = org.json.JSONObject()
                    sessionObj.put("event_id", "sess_$audioTaskId")
                    sessionObj.put("type", "session.update")
                    val body = org.json.JSONObject()
                    body.put("modalities", org.json.JSONArray().put("text"))
                    body.put("input_audio_format", "pcm")
                    body.put("sample_rate", sampleRate)
                    body.put("input_audio_transcription", org.json.JSONObject().apply { put("language", "zh") })
                    body.put("turn_detection", org.json.JSONObject().apply {
                        put("type", "server_vad"); put("threshold", 0.0); put("silence_duration_ms", 400)
                    })
                    sessionObj.put("session", body)
                    ws.send(sessionObj.toString())

                    translateAudioRecord?.startRecording()
                    translateRecordThread = Thread {
                        val buf = ByteArray(3200)
                        try {
                            while (translateIsRecording) {
                                val read = translateAudioRecord?.read(buf, 0, buf.size) ?: -1
                                if (read > 0 && translateIsRecording) {
                                    val chunk = if (read < buf.size) buf.copyOf(read) else buf
                                    val b64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                                    val audioObj = org.json.JSONObject()
                                    audioObj.put("event_id", "aud_${System.currentTimeMillis()}")
                                    audioObj.put("type", "input_audio_buffer.append")
                                    audioObj.put("audio", b64)
                                    ws.send(audioObj.toString())
                                }
                            }
                        } catch (_: Exception) {}
                    }.apply { start() }
                }

                override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                    try {
                        val json = org.json.JSONObject(text)
                        if (json.optString("type") == "conversation.item.input_audio_transcription.completed") {
                            val transcript = json.optString("transcript", "")
                            if (transcript.isNotEmpty()) {
                                activity?.runOnUiThread {
                                    tvResult.text = "识别中：$transcript"
                                    // 自动发起翻译
                                    translateText(transcript, translateTargetLang, tvResult)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    activity?.runOnUiThread {
                        tvResult.text = "语音识别失败: ${t.localizedMessage}"
                        view?.findViewById<Button>(R.id.btnStartAi)?.text = "按住 翻译"
                    }
                    translateIsRecording = false
                }
            })
    }

    private fun stopTranslateRecording() {
        translateIsRecording = false
        translateRecordThread?.interrupt()
        val finishJson = org.json.JSONObject().apply {
            put("event_id", "fin_${System.currentTimeMillis()}")
            put("type", "session.finish")
        }.toString()
        translateWs?.send(finishJson)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            translateWs?.close(1000, "ok"); translateWs = null
            try { translateAudioRecord?.release() } catch (_: Exception) {}
            translateAudioRecord = null
        }, 2000)
        val langName = supportedLanguages.find { it.first == translateTargetLang }?.second ?: "English"
        view?.findViewById<Button>(R.id.btnStartAi)?.text = "按住 翻译为 $langName"
        view?.findViewById<Button>(R.id.btnAiCollection)?.text = "$langName ▼"
        view?.findViewById<Button>(R.id.btnAiCollection)?.isEnabled = true
    }

    private fun translateText(text: String, targetLang: String, tvResult: TextView) {
        aiRequestId++
        val requestId = aiRequestId
        val payload = org.json.JSONObject().apply {
            put("model", "qwen-mt-flash")
            put("messages", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("translation_options", org.json.JSONObject().apply {
                put("source_lang", "auto")
                put("target_lang", targetLang)
            })
        }

        aiHttpClient.newCall(okhttp3.Request.Builder()
            .url("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")
            .post(okhttp3.RequestBody.create("application/json".toMediaType(), payload.toString()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer sk-1ecef1a03a0f4062a16b59a8e50b4399")
            .build()).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (aiRequestId != requestId) return
                activity?.runOnUiThread {
                    tvResult.text = "翻译失败，请检查网络后重试。"
                }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (aiRequestId != requestId) return
                val body = response.body?.string()
                if (response.code != 200 || body.isNullOrEmpty()) {
                    activity?.runOnUiThread {
                        tvResult.text = "服务异常（HTTP ${response.code}）"
                    }
                    return
                }
                try {
                    val json = org.json.JSONObject(body)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val translated = choice?.optJSONObject("message")?.optString("content", "") ?: ""
                    val targetName = supportedLanguages.find { it.first == targetLang }?.second ?: targetLang
                    activity?.runOnUiThread {
                        tvResult.text = "🗣 $text\n\n🌐 $targetName：$translated"
                    }
                } catch (_: Exception) {
                    activity?.runOnUiThread {
                        tvResult.text = "解析翻译结果失败"
                    }
                }
            }
        })
    }

    private fun showCollectionShareMenu(ivShow: ImageView, tvResult: TextView) {
        val items = arrayOf("📂 查看收藏", "📤 分享结果")
        AlertDialog.Builder(requireContext())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showAIFavorites(ivShow, tvResult)
                    1 -> shareAIResult()
                }
            }
            .show()
    }

    private fun shareAIResult() {
        val tvResult = view?.findViewById<TextView>(R.id.tvAIResult)
        val text = tvResult?.text?.toString() ?: ""
        if (text.isEmpty() || text.startsWith("正在") || text.startsWith("服务异常")) {
            Toast.makeText(context, "没有可分享的识图结果", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "【EyeX 识图结果】$text")
        }
        startActivity(Intent.createChooser(intent, "分享识图结果"))
    }
}


// ... (后面的 AIFragment 等保持不变) ...

class AIFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return TextView(context).apply {
            text = "AI 助手 - 图像识别与聊天"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }
    }
}




// 确保这个类叫 AlbumFragment

class AlbumFragment : androidx.fragment.app.Fragment() {

    private val photoList = mutableListOf<String>()
    private lateinit var adapter: PhotoAdapter


    // 🚀 新增：记录当前的分类状态。默认显示"ALL"(全部)
    private var currentCategory = "ALL"


    // 🚀 新增：用来记住当前大图显示的是哪一张照片的路径
    private var currentPhotoPath: String? = null


    // 🚀 替换为 AudioTrack 相关的播放变量
    private var pcmAudioTrack: android.media.AudioTrack? = null
    @Volatile private var isPcmPlaying = false



    override fun onCreateView(inflater: android.view.LayoutInflater, container: android.view.ViewGroup?, savedInstanceState: android.os.Bundle?): android.view.View? {
        return inflater.inflate(R.layout.fragment_album, container, false)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val btnSync = view.findViewById<android.widget.Button>(R.id.btnSync)
        val rvAlbum = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAlbum)


        // 🚀 新增：找到大图遮罩层的控件
        val flFullScreen = view.findViewById<android.widget.FrameLayout>(R.id.flFullScreen)
        val ivBigPhoto = view.findViewById<android.widget.ImageView>(R.id.ivBigPhoto)
        val btnClose = view.findViewById<android.widget.ImageView>(R.id.btnClose)

        // 🚀 新增：找到我们刚加的保存和删除按钮
        val btnSavePhoto = view.findViewById<android.widget.Button>(R.id.btnSavePhoto)
        val btnDeletePhoto = view.findViewById<android.widget.Button>(R.id.btnDeletePhoto)


        // 🚀 新增：找到视频控件，并给它挂载系统的播放控制器（带进度条、播放/暂停键）
        val vvBigVideo = view.findViewById<android.widget.VideoView>(R.id.vvBigVideo)
        val mediaController = android.widget.MediaController(requireContext())
        mediaController.setAnchorView(vvBigVideo)
        vvBigVideo.setMediaController(mediaController)


        // 🚀 新增：找到录音界面的控件
        val llAudioPlayer = view.findViewById<android.widget.LinearLayout>(R.id.llAudioPlayer)
        val tvAudioName = view.findViewById<android.widget.TextView>(R.id.tvAudioName)






        rvAlbum.layoutManager = androidx.recyclerview.widget.GridLayoutManager(context, 3)


        // 🚀 修改：把 Adapter 换成带点击事件的版本
        adapter = PhotoAdapter(photoList) { clickedPath ->

            // 记住当前点击的路径，方便后续删除或保存
            currentPhotoPath = clickedPath

            if (clickedPath.endsWith(".jpg", true)) {

                // 📷 点击的是图片：显示 ImageView，隐藏 VideoView
                ivBigPhoto.visibility = android.view.View.VISIBLE
                vvBigVideo.visibility = android.view.View.GONE


                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(clickedPath, this)
                    inSampleSize = calculateInSampleSize(this, 2048, 2048) // 复用你写的那个计算函数
                    inJustDecodeBounds = false
                }
                val bigBitmap = android.graphics.BitmapFactory.decodeFile(clickedPath, options)


                ivBigPhoto.setImageBitmap(bigBitmap)
                flFullScreen.visibility = android.view.View.VISIBLE

                // 设置图片，并把黑布显示出来！
                ivBigPhoto.setImageBitmap(bigBitmap)
                flFullScreen.visibility = android.view.View.VISIBLE
            } else if(clickedPath.endsWith(".mp4", true) || clickedPath.endsWith(".mov", true)){
                // 🎬 点击的是视频：隐藏 ImageView，显示 VideoView 并播放
                ivBigPhoto.visibility = android.view.View.GONE
                vvBigVideo.visibility = android.view.View.VISIBLE

                // 🚀 修复 1：把普通的路径转换成标准的 "file://" URI 格式，彻底消除日志里的 FileNotFoundException
                val videoUri = android.net.Uri.fromFile(java.io.File(clickedPath))
                vvBigVideo.setVideoURI(videoUri)

                // 🚀 修复 2：监听视频准备完毕，解决"播放几秒就停"的异常，并开启自动循环播放
                vvBigVideo.setOnPreparedListener { mp ->
                    // 让视频循环播放，体验更好，也不会因为播完瞬间黑屏
                    mp.isLooping = true

                    // 确保进度条控制器在视频尺寸计算完毕后正确吸附
                    mediaController.setAnchorView(vvBigVideo)
                }

                // 🚀 修复 3：处理视频播放中的异常，防止 App 崩溃
                vvBigVideo.setOnErrorListener { _, what, extra ->
                    android.util.Log.e("QCVideo", "播放出错 what: $what extra: $extra")
                    true // 返回 true 表示我们自己处理了这个错误，不要弹系统的恶心报错框
                }

                vvBigVideo.start()
                flFullScreen.visibility = android.view.View.VISIBLE
            } else if (clickedPath.endsWith(".pcm", true) || clickedPath.endsWith(".wav", true) || clickedPath.endsWith(".mp3", true)) {
                // 🎵 点击的是录音：显示录音界面，隐藏图片和视频
                ivBigPhoto.visibility = android.view.View.GONE
                vvBigVideo.visibility = android.view.View.GONE
                llAudioPlayer.visibility = android.view.View.VISIBLE
                flFullScreen.visibility = android.view.View.VISIBLE

                val fileName = java.io.File(clickedPath).name
                tvAudioName.text = "正在准备: $fileName"

                // 🔴 先停止可能正在播放的旧音频
                isPcmPlaying = false
                pcmAudioTrack?.stop()
                pcmAudioTrack?.release()
                pcmAudioTrack = null

                // 🚀 核心：使用 AudioTrack 强行播放底层 PCM 裸流数据
                Thread {
                    try {
                        // ⚠️ 绝大多数智能眼镜录音芯片的默认配置：16kHz 采样率，单声道，16位位宽
                        val sampleRate = 16000
                        val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
                        val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT

                        val minBufferSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                        // 🚀 使用官方推荐的 Builder 模式，彻底消灭废弃警告
                        pcmAudioTrack = android.media.AudioTrack.Builder()
                            .setAudioAttributes(
                                android.media.AudioAttributes.Builder()
                                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA) // 对应以前的 STREAM_MUSIC
                                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .build()
                            )
                            .setAudioFormat(
                                android.media.AudioFormat.Builder()
                                    .setSampleRate(sampleRate)     // 16kHz
                                    .setChannelMask(channelConfig) // 单声道
                                    .setEncoding(audioFormat)      // 16bit PCM
                                    .build()
                            )
                            .setBufferSizeInBytes(minBufferSize)
                            .setTransferMode(android.media.AudioTrack.MODE_STREAM) // 流式播放
                            .build()

                        val file = java.io.File(clickedPath)
                        val inputStream = java.io.FileInputStream(file)
                        val buffer = ByteArray(minBufferSize)

                        isPcmPlaying = true
                        pcmAudioTrack?.play()

                        activity?.runOnUiThread {
                            tvAudioName.text = "🔊 正在播放: $fileName"
                        }

                        var bytesRead = inputStream.read(buffer)
                        while (isPcmPlaying && bytesRead != -1) {
                            // 1. 播放刚才读到的数据
                            pcmAudioTrack?.write(buffer, 0, bytesRead)

                            // 2. 继续抽下一口水
                            bytesRead = inputStream.read(buffer)
                        }

                        // 播放完毕后的收尾工作
                        inputStream.close()
                        if (isPcmPlaying) {
                            activity?.runOnUiThread {
                                tvAudioName.text = "✅ 播放完毕: $fileName"
                            }
                        }

                        pcmAudioTrack?.stop()
                        pcmAudioTrack?.release()
                        pcmAudioTrack = null
                        isPcmPlaying = false

                    } catch (e: Exception) {
                        e.printStackTrace()
                        activity?.runOnUiThread {
                            tvAudioName.text = "❌ 播放失败 (数据损坏)"
                        }
                    }
                }.start()

            } else {
                tvAudioName.text = "暂未开发"
            }
        }



        rvAlbum.adapter = adapter

        // 🚀 新增：点击右上角的 ❌ 关闭大图
        btnClose.setOnClickListener {
            flFullScreen.visibility = android.view.View.GONE
            ivBigPhoto.setImageBitmap(null) // 清空图片，释放宝贵的内存


            // 🚀 新增：如果视频正在播放，立马停止并重置
            if (vvBigVideo.isPlaying) {
                vvBigVideo.stopPlayback()
            }
            vvBigVideo.suspend() // 释放视频引擎资源


            // 🚀 新增：如果音频正在播放，立刻停止！
            isPcmPlaying = false
            llAudioPlayer.visibility = android.view.View.GONE // 隐藏录音界面

            currentPhotoPath = null
        }


        // 🚀 修改：保存文件按钮点击事件（加入 300ms 延迟，防文件死锁）
        btnSavePhoto.setOnClickListener {
            val path = currentPhotoPath ?: return@setOnClickListener
            val file = java.io.File(path)

            btnSavePhoto.text = "⏳ 保存中..."
            btnSavePhoto.isEnabled = false

            // 🔴 强制干掉底层播放器
            if (vvBigVideo.visibility == android.view.View.VISIBLE) {
                vvBigVideo.stopPlayback()
            }

            val isVideo = path.endsWith(".mp4", true) || path.endsWith(".mov", true)

            // 🚀 新增判断：是不是音频
            val isAudio = path.endsWith(".pcm", true) || path.endsWith(".wav", true) || path.endsWith(".mp3", true)

            val safeContext = requireContext().applicationContext

            // 开启子线程去干苦力活
            Thread {
                // 🚀 核心修复 1：让子线程先睡 300 毫秒！
                // 给底层 MediaPlayer 一点点时间，让它把该视频的文件锁彻底松开
                Thread.sleep(300)

                val success = if (isVideo) {
                    saveVideoToGallery(safeContext, file)
                } else if (isAudio) {
                    saveAudioToGallery(safeContext, file)
                }else {
                    saveImageToGallery(safeContext, file)
                }

                // 回到主线程更新 UI
                activity?.runOnUiThread {
                    btnSavePhoto.text = "⬇️ 保存本机"
                    btnSavePhoto.isEnabled = true
                    if (success) {
                        android.widget.Toast.makeText(context, "✅ 已成功保存到手机相册！", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(context, "❌ 保存失败，系统拒绝写入", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        // 🚀 新增：删除照片按钮点击事件
        btnDeletePhoto.setOnClickListener {
            val path = currentPhotoPath ?: return@setOnClickListener
            val file = java.io.File(path)

            android.app.AlertDialog.Builder(requireContext())
                .setTitle("⚠️ 确认删除")
                .setMessage("确定要彻底删除这张照片吗？")
                .setPositiveButton("删除") { _, _ ->
                    if (file.exists() && file.delete()) {
                        android.widget.Toast.makeText(context, "✅ 照片已删除", android.widget.Toast.LENGTH_SHORT).show()
                        // 关闭大图界面
                        flFullScreen.visibility = android.view.View.GONE
                        ivBigPhoto.setImageBitmap(null)
                        currentPhotoPath = null
                        // 重新刷新背后的相册列表
                        refreshPhotoList()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }




        // 🚀 新增：找到我们刚加的单选组，并监听它的点击事件
        val rgFilter = view.findViewById<android.widget.RadioGroup>(R.id.rgFilter)
        rgFilter.setOnCheckedChangeListener { _, checkedId ->
            currentCategory = when (checkedId) {
                R.id.rbPhotos -> "PHOTO"
                R.id.rbVideos -> "VIDEO"
                R.id.rbAudio -> "AUDIO" // 🚀 新增：接通录音的分类指令
                else -> "ALL"
            }
            // 每次切换分类后，立刻重新扫描文件夹并刷新列表！
            refreshPhotoList()
        }


        // 🚀 核心修改：优化刷新逻辑和断开时机
        com.eyex.app.ble.QCBluetoothManager.instance.initMediaEngine(
            requireActivity().application,
            { filePath ->
                // 🛑 核心修改 1：把这里的 refreshPhotoList() 删掉！
                // 解释：单个文件下载时，千万不要去扫描文件夹刷新界面，避免主线程卡死和 OOM。
                // 我们仅仅在后台打印一下日志即可。
                android.util.Log.i("QCBluetooth", "✅ 成功接收并处理文件: $filePath")
            },
            {
                activity?.runOnUiThread {
                    btnSync.text = "同步眼镜多媒体"
                    btnSync.isEnabled = true
                    android.widget.Toast.makeText(context, "✅ 同步完成！", android.widget.Toast.LENGTH_SHORT).show()

                    // 🛑 核心修改 2：给 SDK 留出 2 秒钟的"善后时间"，不要立刻过河拆桥
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            com.oudmon.wifi.GlassesControl.getInstance(requireActivity().application)?.releaseGlassesControl()
                        } catch (e: Exception) {}

                        // 🛑 核心修改 3：只有在所有文件都传输完毕，且连接断开后，才统一刷新一次界面！
                        refreshPhotoList()

                    }, 2000) // 2000 毫秒 = 2 秒
                }
            }
        )

        refreshPhotoList()

        btnSync.setOnClickListener {
            // 1. 先禁用按钮，防止用户狂点
            btnSync.text = "🔍 检查新文件中..."
            btnSync.isEnabled = false
            btnSync.setBackgroundColor(android.graphics.Color.GRAY)

            // 🚀 核心优化：先通过低功耗蓝牙（BLE）询问眼镜，还有没有未同步的文件？
            // 发送指令 0x02, 0x04 查询未同步的媒体数量
            com.oudmon.ble.base.communication.LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x04)
            ) { _, it ->
                // 切回主线程更新 UI
                activity?.runOnUiThread {
                    // dataType == 4 表示这是查询媒体数量的返回结果
                    if (it.dataType == 4) {
                        val mediaCount = it.imageCount + it.videoCount + it.recordCount

                        if (mediaCount == 0) {
                            // 🛑 智能拦截：如果没有新文件，直接结束！绝不启动笨重的 WiFi 引擎
                            btnSync.text = "同步眼镜多媒体"
                            btnSync.isEnabled = true
                            btnSync.setBackgroundColor(android.graphics.Color.parseColor("#FF6200EE"))
                            android.widget.Toast.makeText(context, "🎉 眼镜里没有新文件啦，不需要同步！", android.widget.Toast.LENGTH_SHORT).show()
                            return@runOnUiThread // 直接退出，后面的代码不执行了
                        }

                        // 👇 下面才是如果有文件，再去启动 WiFi 引擎的代码
                        btnSync.text = "⌛ 正在同步 $mediaCount 个文件..."
                        android.widget.Toast.makeText(context, "发现 $mediaCount 个新文件，准备传输...", android.widget.Toast.LENGTH_SHORT).show()

                        // 重新点火初始化媒体引擎
                        com.eyex.app.ble.QCBluetoothManager.instance.initMediaEngine(
                            requireActivity().application,
                            { filePath ->
                                android.util.Log.i("QCBluetooth", "✅ 成功接收文件: $filePath")
                            },
                            {
                                // 全部同步完成
                                activity?.runOnUiThread {
                                    btnSync.text = "同步眼镜多媒体"
                                    btnSync.isEnabled = true
                                    btnSync.setBackgroundColor(android.graphics.Color.parseColor("#FF6200EE"))
                                    android.widget.Toast.makeText(context, "✅ 所有文件同步完成！", android.widget.Toast.LENGTH_SHORT).show()

                                    // 给系统 4 秒钟的时间去安全拆除 WiFi
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try {
                                            com.oudmon.wifi.GlassesControl.getInstance(requireActivity().application)?.releaseGlassesControl()
                                        } catch (e: Exception) {}
                                        refreshPhotoList()
                                    }, 4000)
                                }
                            }
                        )

                        // 发起 WiFi 同步指令
                        com.oudmon.wifi.GlassesControl.getInstance(requireActivity().application)?.importAlbum()

                        // 20秒超时保护兜底
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (!btnSync.isEnabled) {
                                btnSync.text = "同步眼镜多媒体"
                                btnSync.isEnabled = true
                                btnSync.setBackgroundColor(android.graphics.Color.parseColor("#FF6200EE"))
                                try {
                                    com.oudmon.wifi.GlassesControl.getInstance(requireActivity().application)?.releaseGlassesControl()
                                } catch (e: Exception) {}
                            }
                        }, 30000)
                    }
                }
            }
        }



    }

    // 扫描文件夹，把 jpg 和 mp4 都找出来
    // 🚀 核心修改：让扫描文件夹的逻辑带上"过滤网"
    private fun refreshPhotoList() {
        val folder = java.io.File(requireContext().getExternalFilesDir(null), "DCIM_1")
        if (!folder.exists()) return

        val files = folder.listFiles { file ->
            val ext = file.extension.lowercase()
            val isImage = ext == "jpg"
            val isVideo = ext == "mp4" || ext == "mov"
            val isAudio = ext == "pcm" || ext == "wav" || ext == "mp3" // 🚀 加上音频
val isValidSize = file.length() > 1024

            // 🎯 过滤网：根据当前的分类，决定这个文件要不要显示
            val matchCategory = when (currentCategory) {
                "PHOTO" -> isImage                  // 如果选了照片，只认 jpg
                "VIDEO" -> isVideo                  // 如果选了视频，只认 mp4/mov
                "AUDIO" -> isAudio // 🚀 假设你以后想在顶部分类加个"录音"选项
                else -> isImage || isVideo || isAudio          // 如果选了全部，都要
            }

            // 必须同时满足：分类对得上，且文件大小正常
            matchCategory && file.length() > 1024
        }

        files?.let {
            val sortedPaths = it.sortedByDescending { f -> f.lastModified() }.map { f -> f.absolutePath }
            activity?.runOnUiThread {
                photoList.clear()
                photoList.addAll(sortedPaths)
                adapter.notifyDataSetChanged() // 通知列表数据变了，重新排版
            }
        }
    }


    // --- 适配器：专门处理视频抽帧和图片压缩 ---
    inner class PhotoAdapter(val list: List<String>, val onPhotoClick: (String) -> Unit) : androidx.recyclerview.widget.RecyclerView.Adapter<PhotoAdapter.VH>() {
        inner class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val iv = holder.itemView.findViewById<android.widget.ImageView>(R.id.ivPhoto)
            val path = list[position]


            // 🚀 修改 2：给整个格子加上点击事件
            holder.itemView.setOnClickListener {
                onPhotoClick(path) // 点击时，把路径传出去
            }

            if (path.endsWith(".pcm", true) || path.endsWith(".wav", true) || path.endsWith(".mp3", true)) {
                // 🎙️ 录音封面
                iv.clearColorFilter()

                // 🚀 核心修改：把 android.R.drawable... 换成我们自己的 R.drawable.ic_audio_record
                iv.setImageResource(R.drawable.ic_audio_record)

                // 给图片加个极浅的灰色背景，这样红色的录音图标会非常醒目好看
                iv.setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))

                // 保持图标居中且大小合适
                iv.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            }
            else if (path.endsWith(".mp4", true) || path.endsWith(".mov", true)) {
                // 🎬 处理视频：开启子线程抽第一帧画面
                Thread {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(path)
                        val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            iv.setImageBitmap(frame)
                            iv.setColorFilter(android.graphics.Color.argb(100, 0, 0, 0)) // 视频加黑色半透明遮罩
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        retriever.release()
                    }
                }.start()
            } else {
                // 📷 处理图片：瘦身压缩
                iv.clearColorFilter()
                val options = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(path, this)
                    inSampleSize = calculateInSampleSize(this, 300, 300)
                    inJustDecodeBounds = false
                }
                val bitmap = android.graphics.BitmapFactory.decodeFile(path, options)
                iv.setImageBitmap(bitmap)
            }
        }

        private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
            val (height: Int, width: Int) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }
        override fun getItemCount(): Int = list.size
    }


    // 💡 这是一个专门用来计算图片压缩比例的工具函数
    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // 获取图片的原始宽高
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // 计算最大的 inSampleSize 值，它是 2 的指数，
            // 并且保持宽高都大于等于请求的宽高。
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }



    // 🚀 新增：将私有目录的照片保存到系统公共相册的核心函数
    private fun saveImageToGallery(context: android.content.Context, sourceFile: java.io.File): Boolean {
        if (!sourceFile.exists()) return false

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "QC_${System.currentTimeMillis()}_${sourceFile.name}")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // ⚠️ 避开中文路径陷阱，统一使用纯英文 "QCGlasses"
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/QCGlasses")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        // 使用最稳定兼容的 EXTERNAL_CONTENT_URI
        val collection = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values)

        if (uri == null) {
            android.util.Log.e("QCSave", "❌ 系统 MediaStore 拒绝了图片的创建请求")
            return false
        }

        return try {
            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                sourceFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 🚀 终极稳定版：保存视频到相册 (改为 DCIM 目录)
    private fun saveVideoToGallery(context: android.content.Context, sourceFile: java.io.File): Boolean {
        if (!sourceFile.exists()) return false

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "QC_${System.currentTimeMillis()}_${sourceFile.name}")
            val mimeType = if (sourceFile.name.endsWith(".mov", true)) "video/quicktime" else "video/mp4"
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, mimeType)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // ⚠️ 核心修复 2：把 DIRECTORY_MOVIES 改成了 DIRECTORY_DCIM
                // 只有放在 DCIM 里，一加、OPPO 等国产手机的相册才会第一时间刷出来！
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_DCIM + "/QCGlasses")
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values)

        if (uri == null) {
            android.util.Log.e("QCSave", "❌ 系统 MediaStore 拒绝了视频的创建请求")
            return false
        }

        return try {
            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                sourceFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    // 🚀 终极稳定版：保存录音到系统音乐文件夹
    private fun saveAudioToGallery(context: android.content.Context, sourceFile: java.io.File): Boolean {
        if (!sourceFile.exists()) return false

        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "QC_${System.currentTimeMillis()}_${sourceFile.name}")
            // 简单判断后缀给出 MimeType
            val mimeType = if (sourceFile.name.endsWith(".wav", true)) "audio/x-wav" else "audio/mpeg"
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, mimeType)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                // ⚠️ 音频存放在系统的 Music 目录下，创建专属文件夹
                put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MUSIC + "/QCGlasses")
                put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }

        val collection = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val uri = context.contentResolver.insert(collection, values)

        if (uri == null) {
            android.util.Log.e("QCSave", "❌ 系统 MediaStore 拒绝了音频的创建请求")
            return false
        }

        return try {
            context.contentResolver.openOutputStream(uri)?.use { outStream ->
                sourceFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear()
                values.put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


}





class MineFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_mine, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.btnGuide).setOnClickListener {
            startActivity(Intent(requireContext(), GuideIntroActivity::class.java))
        }
        view.findViewById<TextView>(R.id.btnBgPermission).setOnClickListener {
            startActivity(Intent(requireContext(), BgPermissionActivity::class.java))
        }
        view.findViewById<TextView>(R.id.btnFaq).setOnClickListener {
            startActivity(Intent(requireContext(), FaqActivity::class.java))
        }
        view.findViewById<TextView>(R.id.btnAppAbout).setOnClickListener {
            startActivity(Intent(requireContext(), AppAboutActivity::class.java))
        }
    }
}

