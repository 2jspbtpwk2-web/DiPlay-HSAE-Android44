package local.airuize.receiver

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Small, dependency-free controls shared by the tools pages on Android 4.3+. */
internal class ReceiverUiKit(private val activity:Activity, private val managed:(AlertDialog)->AlertDialog) {
    val white=0xFFF4F4F7.toInt()
    val muted=0xFFB3B3C0.toInt()
    private val accent=0xFF6BCBEE.toInt()
    fun dp(value:Int)=(value*activity.resources.displayMetrics.density+0.5f).toInt()
    fun box():LinearLayout=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(16))
    }
    fun text(value:String,size:Float=15f,color:Int=muted):TextView=TextView(activity).apply {
        text=value;textSize=size;setTextColor(color);setLineSpacing(dp(3).toFloat(),1f)
        setPadding(0,dp(7),0,dp(9))
    }
    fun intro(box:LinearLayout,value:String) { box.addView(text(value)) }
    fun section(box:LinearLayout,value:String) { box.addView(text(value,13f,accent).apply {
        setTypeface(typeface,Typeface.BOLD);setPadding(0,dp(19),0,dp(7))
    }) }
    fun note(box:LinearLayout,value:String):TextView=text(value).apply {
        background=background(0xFF202A30.toInt());setPadding(dp(14),dp(12),dp(14),dp(12))
        box.addView(this,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8);bottomMargin=dp(8) })
    }
    fun action(box:LinearLayout,title:String,detail:String="",primary:Boolean=false,click:()->Unit):View {
        val row=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;setGravity(Gravity.CENTER_VERTICAL);minimumHeight=dp(58)
            setPadding(dp(15),dp(10),dp(15),dp(10));isClickable=true;isFocusable=true
            background=StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed),background(0xFF364653.toInt()))
                addState(intArrayOf(),background(if(primary) 0xFF213E4C.toInt() else 0xFF25252E.toInt()))
            }
            contentDescription=if(detail.isEmpty()) title else "$title。$detail"
        }
        row.addView(text(title,17f,if(primary) accent else white).apply {
            setTypeface(typeface,Typeface.BOLD);setPadding(0,0,0,if(detail.isEmpty()) 0 else dp(3))
        })
        if(detail.isNotEmpty()) row.addView(text(detail,14f).apply {setPadding(0,0,0,0)})
        row.setOnClickListener { safely(click) }
        box.addView(row,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(5);bottomMargin=dp(5)})
        return row
    }
    fun check(box:LinearLayout,title:String,detail:String,checked:Boolean):CheckBox {
        val check=CheckBox(activity).apply {
            text=title;textSize=17f;setTextColor(white);isChecked=checked;minimumHeight=dp(56)
            setPadding(dp(6),dp(5),dp(6),dp(5))
        }
        box.addView(check)
        if(detail.isNotEmpty()) box.addView(text(detail,14f).apply {setPadding(dp(9),0,dp(5),dp(8))})
        return check
    }
    fun page(title:String,box:LinearLayout,saveLabel:String?=null,save:(()->Unit)?=null):AlertDialog {
        val scroll=ScrollView(activity).apply {isFillViewport=false;addView(box)}
        val builder=AlertDialog.Builder(activity).setTitle(title).setView(scroll)
            .setNegativeButton(if(saveLabel==null) "关闭" else "取消",null)
        if(saveLabel!=null) builder.setPositiveButton(saveLabel) {_,_->if(save!=null) safely(save)}
        return managed(builder.create())
    }
    private fun safely(action:()->Unit) {
        try {action()}
        catch(_:Exception) {Toast.makeText(activity,"这次操作没有完成，请重试；持续异常请导出问题简报。",Toast.LENGTH_LONG).show()}
    }
    private fun background(fill:Int)=GradientDrawable().apply {
        setShape(GradientDrawable.RECTANGLE);setCornerRadius(dp(12).toFloat());setColor(fill)
        setStroke(dp(1),0xFF3A3A46.toInt())
    }
}
