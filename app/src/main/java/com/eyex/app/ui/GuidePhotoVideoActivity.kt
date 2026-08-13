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
    private val items = listOf(Item("Take Photo","Press front right once","Indicator flashes"),Item("Start Video","Press front right twice","Indicator stays on"),Item("Stop Video","Press front right while recording",""),Item("Start Recording","Long press back right","Indicator flashes"),Item("Stop Recording","Press back right while recording",""),Item("AI Quick Scan","Press back right twice",""))
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
    private fun update(step:TextView,title:TextView,sub:TextView,detail:TextView,next:Button){val i=items[index];step.text="${index+1}/${items.size}";title.text=i.title;sub.text=i.sub;detail.text=i.detail;detail.visibility=if(i.detail.isEmpty()) View.GONE else View.VISIBLE;next.text=if(index<items.size-1)"Next" else "Music Guide"}
}
