package local.airuize.receiver

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Only the exact HmiManager events observed on this HSAE firmware are recognized. */
object HsaeMediaKeyParser {
    enum class Kind { NEXT_DOWN, NEXT_LONG, NEXT_UP, PREVIOUS_DOWN, PREVIOUS_LONG, PREVIOUS_UP, LONG_PRESS }
    data class Event(val kind: Kind, val wallMillis: Long, val token: String)
    private val line=Regex("^([0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}) +([0-9]{1,8}) +([0-9]{1,8}) [VDIWEF] +HmiManager *: (.*)$")
    private val seek=Regex("^seek(up|down)KeyEvent: ([0-5]) *$")

    fun parse(text: String, nowWallMillis: Long): Event? {
        if(text.length>512) return null
        val match=line.matchEntire(text) ?: return null
        val message=match.groupValues[4]
        val kind=if(message=="Set UP state isLongPress_UP = true") Kind.LONG_PRESS else {
            val key=seek.matchEntire(message) ?: return null
            when(key.groupValues[1]+key.groupValues[2]) {
                "up0" -> Kind.NEXT_DOWN; "up1" -> Kind.NEXT_LONG; "up2" -> Kind.NEXT_UP
                "down3" -> Kind.PREVIOUS_DOWN; "down4" -> Kind.PREVIOUS_LONG; "down5" -> Kind.PREVIOUS_UP
                else -> return null
            }
        }
        val year=Calendar.getInstance().apply { timeInMillis=nowWallMillis }.get(Calendar.YEAR)
        val format=SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).apply { isLenient=false }
        val time=listOf(year-1,year,year+1).mapNotNull { candidate ->
            try { format.parse("$candidate-${match.groupValues[1]}")?.time } catch(_:Exception) { null }
        }.minByOrNull { kotlin.math.abs(it-nowWallMillis) } ?: return null
        return Event(kind,time,"${match.groupValues[1]}:${match.groupValues[2]}:${match.groupValues[3]}:${kind.name}")
    }
}

/** Cheap, fixed two-second prefilter before calendar/date parsing. It does not impose
 * an arbitrary line tail, so a busy ring cannot discard fresh keys ahead of filtering. */
internal class HsaeKeyFreshWindow(nowWallMillis:Long) {
    private val format=SimpleDateFormat("MM-dd HH:mm:ss.SSS",Locale.US)
    private val first=format.format(java.util.Date(nowWallMillis-2000L))
    private val last=format.format(java.util.Date(nowWallMillis))
    fun includes(text:String):Boolean {
        if(text.length<18) return false
        val stamp=text.substring(0,18)
        return if(first<=last) stamp>=first && stamp<=last else stamp>=first || stamp<=last
    }
}

/** Short-press pairs only; no long-press repeat, queued action or old-session replay. */
class HsaeMediaKeyPolicy {
    private var session:Any?=null
    private var beganWall=Long.MAX_VALUE
    private val seen=LinkedHashSet<String>()
    private val down=LongArray(2) { -1L }
    private val held=BooleanArray(2)
    private var lastSentMono=Long.MIN_VALUE
    private var lastSentWall=Long.MIN_VALUE
    fun reset(value:Any?,wallMillis:Long) {
        session=value;beganWall=wallMillis;seen.clear();down.fill(-1L);held.fill(false);lastSentMono=Long.MIN_VALUE;lastSentWall=Long.MIN_VALUE
    }
    fun accept(event:HsaeMediaKeyParser.Event,value:Any?,nowWall:Long,nowMono:Long,
        enabled:Boolean,mediaActive:Boolean,diagnostic:Boolean):Int? {
        if(value!==session) { reset(value,nowWall);return null }
        if(event.wallMillis<beganWall || nowWall-event.wallMillis !in 0L..1800L) return null
        if(!seen.add(event.token)) return null
        while(seen.size>128) seen.remove(seen.first())
        if(!enabled || value==null || !mediaActive || diagnostic) { down.fill(-1L);held.fill(false);return null }
        val kind=event.kind
        if(kind==HsaeMediaKeyParser.Kind.LONG_PRESS) { for(i in 0..1) if(down[i]>=0) held[i]=true;return null }
        val slot=if(kind in listOf(HsaeMediaKeyParser.Kind.NEXT_DOWN,HsaeMediaKeyParser.Kind.NEXT_LONG,HsaeMediaKeyParser.Kind.NEXT_UP)) 0 else 1
        when(kind) {
            HsaeMediaKeyParser.Kind.NEXT_DOWN,HsaeMediaKeyParser.Kind.PREVIOUS_DOWN -> {
                if(down[slot]>=0 && event.wallMillis-down[slot]<=1200) held[slot]=true
                else { down[slot]=event.wallMillis;held[slot]=false }
            }
            HsaeMediaKeyParser.Kind.NEXT_LONG,HsaeMediaKeyParser.Kind.PREVIOUS_LONG -> held[slot]=true
            else -> {
                val started=down[slot];val wasHeld=held[slot];down[slot]=-1;held[slot]=false
                if(started<0 || wasHeld || event.wallMillis-started !in 1L..1200L) return null
                // A finite logcat dump can contain multiple real presses. Their delivery
                // is a batch, so parser arrival time is not the physical key interval.
                // Freshness, ownership and exact-token dedup above still apply first.
                if(lastSentMono!=Long.MIN_VALUE && (nowMono<lastSentMono || event.wallMillis-lastSentWall<350L)) return null
                lastSentMono=nowMono;lastSentWall=event.wallMillis
                return if(slot==0) 4 else 5 // AirPlayHid media descriptor: next-track / previous-track.
            }
        }
        return null
    }
}

/** Bounded counters only. No OEM log payload, track title, ROOT output or phone identity is retained. */
object SteeringMediaDiagnostics {
    private val events=java.util.ArrayDeque<String>()
    private var polls=0L;private var parsed=0L;private var sendAttempts=0L;private var sent=0L;private var writeFailures=0L;private var failures=0L
    private var source="source=not-started"
    @Synchronized fun event(value:String) {
        events.addLast(value.take(180));while(events.size>32) events.removeFirst()
    }
    @Synchronized fun poll() { polls++ }
    @Synchronized fun source(value:String) { source=value.take(240) }
    @Synchronized fun parsed() { parsed++ }
    @Synchronized fun attempted(index:Int,success:Boolean) {
        sendAttempts++;if(success) sent++ else writeFailures++
        event("HID momentary index=$index downAndUpWritten=$success；"+
            if(success) "写入不证明手机已切曲" else "未就绪或写入失败；本键不重放")
    }
    @Synchronized fun failure(type:String) { failures++;event("reader failure="+type.filter { it.isLetterOrDigit() || it in "._-" }.take(80)) }
    @Synchronized fun snapshot():String="HSAE方向盘切歌：默认关闭，原厂无效时才启用；若一次切两首请关闭。"+
        "只读有限logcat快照；polls=$polls parsed=$parsed sendAttempts=$sendAttempts sent=$sent writeFailures=$writeFailures failures=$failures；$source；"+
        "仅活动CarPlay媒体播放且非诊断按键采集中转发；未验证实车切歌。\n"+events.joinToString("\n")
}
