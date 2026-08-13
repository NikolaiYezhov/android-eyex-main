package com.eyex.app.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
import com.eyex.app.ble.QCBluetoothManager
import kotlin.jvm.java

class DeviceSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_settings)
        supportActionBar?.hide()

        // 1. 返回按钮
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // 2. 跳转录制设置
        findViewById<TextView>(R.id.btnRecordSettings).setOnClickListener {
            startActivity(Intent(this, RecordingSettingsActivity::class.java))
        }

        // 3. 🚀 点击【重启】按钮，弹出确认框 (图2)
        findViewById<TextView>(R.id.btnRestart).setOnClickListener {
            showCustomConfirmDialog(
                title = "重启后将会自动重连眼镜，\n确定继续吗？",
                subtitle = null // 重启没有小字提示
            ) {
                // 点击确认后的操作
                Toast.makeText(this, "正在发送重启指令...", Toast.LENGTH_SHORT).show()
                // TODO: 在这里调用 QCBluetoothManager 发送重启指令给眼镜
            }
        }

        // 4. 🚀 点击【恢复出厂设置】按钮，弹出确认框 (图3)
        findViewById<TextView>(R.id.btnFactoryReset).setOnClickListener {
            showCustomConfirmDialog(
                title = "确定要恢复出厂设置吗？",
                subtitle = "恢复出厂设置将清除眼镜上的所有数据。" // 恢复出厂设置有小字提示
            ) {
                // 点击确认后的操作
                Toast.makeText(this, "正在恢复出厂设置...", Toast.LENGTH_SHORT).show()
                // TODO: 在这里调用 QCBluetoothManager 发送清空指令
            }
        }

        // 5. 解除配对
        findViewById<Button>(R.id.btnUnpair).setOnClickListener {
            QCBluetoothManager.instance.disconnect(this)
            startActivity(Intent(this, QCScanActivity::class.java))
            finishAffinity()
        }
    }

    // ==========================================
    // 🎨 万能的自定义弹窗生成器
    // ==========================================
    private fun showCustomConfirmDialog(title: String, subtitle: String?, onConfirm: () -> Unit) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_custom_confirm)

        // 把弹窗的背景设为透明，这样才能露出我们在 XML 里画的白色圆角
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 找到弹窗里的控件
        val tvTitle = dialog.findViewById<TextView>(R.id.tvDialogTitle)
        val tvSubtitle = dialog.findViewById<TextView>(R.id.tvDialogSubtitle)
        val btnConfirm = dialog.findViewById<Button>(R.id.btnDialogConfirm)
        val btnCancel = dialog.findViewById<Button>(R.id.btnDialogCancel)

        // 设置文本
        tvTitle.text = title
        if (subtitle != null) {
            tvSubtitle.visibility = View.VISIBLE
            tvSubtitle.text = subtitle
        } else {
            tvSubtitle.visibility = View.GONE
        }

        // 按钮点击事件
        btnCancel.setOnClickListener {
            dialog.dismiss() // 点击取消直接关掉
        }

        btnConfirm.setOnClickListener {
            dialog.dismiss() // 先关掉弹窗
            onConfirm.invoke() // 执行外部传进来的逻辑 (重启或恢复出厂)
        }


        // 点击【关于】跳转
        findViewById<TextView>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }



        dialog.show()
    }
}