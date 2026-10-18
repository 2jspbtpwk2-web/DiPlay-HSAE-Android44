package local.airuize.receiver

/** Local receive-queue PTS only. No phone clock, media payload, display timing or touch is inferred. */
internal class VideoOutputAgeStats {
    data class Distribution(val samples:Long,val invalid:Long,val totalUs:Long,val maxUs:Long,val buckets:List<Long>) {
        val meanUs:Long get()=if(samples==0L) 0L else totalUs/samples
    }
    data class Snapshot(val decoded:Distribution,val submitted:Distribution)
    private class Buckets {
        var samples=0L;var invalid=0L;var total=0L;var max=0L
        private val counts=LongArray(6)
        fun record(ageUs:Long?) {
            if(ageUs==null) {invalid++;return}
            samples++;total=if(ageUs>Long.MAX_VALUE-total) Long.MAX_VALUE else total+ageUs;max=maxOf(max,ageUs)
            val bucket=when {
                ageUs<=16_000L->0;ageUs<=33_000L->1;ageUs<=66_000L->2
                ageUs<=100_000L->3;ageUs<=250_000L->4;else->5
            }
            counts[bucket]++
        }
        fun snapshot()=Distribution(samples,invalid,total,max,counts.toList())
    }
    private val decoded=Buckets()
    private val submitted=Buckets()
    @Synchronized fun decoded(epochNs:Long,presentationTimeUs:Long,nowNs:Long) {
        decoded.record(ageUs(epochNs,presentationTimeUs,nowNs))
    }
    @Synchronized fun submitted(epochNs:Long,presentationTimeUs:Long,nowNs:Long) {
        submitted.record(ageUs(epochNs,presentationTimeUs,nowNs))
    }
    @Synchronized fun snapshot()=Snapshot(decoded.snapshot(),submitted.snapshot())
    fun summary():String {
        val value=snapshot()
        fun describe(d:Distribution)="样本=${d.samples} 均值Us=${d.meanUs} 最大Us=${d.maxUs} 无效PTS=${d.invalid} 分桶=${d.buckets.joinToString(",")}"
        return "本地视频年龄（接收入队→解码输出/Surface提交返回；分桶≤16/33/66/100/250/>250ms）："+
            "解码[${describe(value.decoded)}]；提交[${describe(value.submitted)}]；"+
            "不含手机处理、网络或入队前解密/复制；提交不等于屏幕实显，也不是端到端触摸延迟"
    }
    companion object {
        /** Subtraction uses the same monotonic clock and tolerates its signed-long wrap. */
        fun ageUs(epochNs:Long,presentationTimeUs:Long,nowNs:Long):Long? {
            val elapsedNs=nowNs-epochNs
            if(elapsedNs<0L || presentationTimeUs<0L) return null
            val elapsedUs=elapsedNs/1000L
            if(presentationTimeUs>elapsedUs) return null
            return elapsedUs-presentationTimeUs
        }
    }
}
