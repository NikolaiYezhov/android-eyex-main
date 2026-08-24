package com.eyex.app.ui
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
class GuidePhotoVideoActivity : AppCompatActivity() {
    private data class Item(val title: String, val sub: String, val detail: String)
    private val items = listOf(Item("拍照","单击右前方按键","指示灯闪烁"),Item("开始录像","双击右前方按键","指示灯常亮"),Item("停止录像","录像时按下右前方按键",""),Item("开始录音","长按右后方按键","指示灯闪烁"),Item("停止录音","录音时按下右后方按键",""),Item("AI 快速扫描","双击右后方按键",""))
    private var index = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_guide_photo_video)
        supportActionBar?.hide()
        val step=findViewById<TextView>(R.id.tvStepNumber); val title=findViewById<TextView>(R.id.tvGuideTitle); val sub=findViewById<TextView>(R.id.tvGuideSubtitle); val detail=findViewById<TextView>(R.id.tvGuideDetail); val next=findViewById<Button>(R.id.btnNext); val skip=findViewById<TextView>(R.id.btnSkip)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener{finish()}
        skip.setOnClickListener{startActivity(Intent(this,GuideMusicActivity::class.java));finish()}
        next.setOnClickListener{if(index<items.size-1){index++;update(step,title,sub,detail,next)}else{startActivity(Intent(this,GuideMusicActivity::class.java));finish()}}
        update(step,title,sub,detail,next)
    }
    private fun update(step:TextView,title:TextView,sub:TextView,detail:TextView,next:Button){val i=items[index];step.text="${index+1}/${items.size}";title.text=i.title;sub.text=i.sub;detail.text=i.detail;detail.visibility=if(i.detail.isEmpty()) View.GONE else View.VISIBLE;next.text=if(index<items.size-1)"下一步" else "音乐指南"}
}
