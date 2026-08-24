package com.eyex.app.ui
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.eyex.app.R
class GuideMusicActivity : AppCompatActivity() {
    private data class Item(val title: String, val sub: String)
    private val items = listOf(Item("播放/暂停","双击右侧触控板"),Item("音量","向前/向后滑动"),Item("上一项","三击"),Item("下一项","长按右触摸板"))
    private var index = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_guide_music)
        supportActionBar?.hide()
        val step=findViewById<TextView>(R.id.tvStepNumber); val title=findViewById<TextView>(R.id.tvMusicTitle); val sub=findViewById<TextView>(R.id.tvMusicSubtitle); val next=findViewById<Button>(R.id.btnMusicNext); val skip=findViewById<TextView>(R.id.btnMusicSkip)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener{finish()}
        skip.setOnClickListener{finish()}
        next.setOnClickListener{if(index<items.size-1){index++;update(step,title,sub,next)}else{finish()}}
        update(step,title,sub,next)
    }
    private fun update(step:TextView,title:TextView,sub:TextView,next:Button){val i=items[index];step.text="${index+1}/${items.size}";title.text=i.title;sub.text=i.sub;next.text=if(index<items.size-1)"下一步" else "完成"}
}
