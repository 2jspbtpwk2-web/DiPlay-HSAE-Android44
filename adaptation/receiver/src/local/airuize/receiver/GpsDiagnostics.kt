package local.airuize.receiver

object GpsDiagnostics {
    fun satelliteSummary(samples: List<Pair<Float, Boolean>>, timeToFirstFixMillis: Int): String {
        val bounded = samples.take(256)
        val snr = bounded.map { it.first }.filter { !it.isNaN() && !it.isInfinite() && it in 0f..100f }
        val average = if(snr.isEmpty()) "未知" else String.format(java.util.Locale.US, "%.1f", snr.map { it.toDouble() }.average())
        val max = snr.maxOrNull()?.toInt()?.toString() ?: "未知"
        return "可见=${bounded.size} 使用=${bounded.count { it.second }} SNR有效=${snr.size} SNR平均=$average SNR最大=$max " +
            "缓存TTFFms=${timeToFirstFixMillis.takeIf { it > 0 } ?: -1}（缓存可能属于较早定位；不记录卫星ID/角度）"
    }
    enum class Rejection { PROVIDER, COORDINATES, MOCK, AGE, TIMESTAMP, ORDER }
    fun rejection(provider: String?, latitude: Double, longitude: Double, mock: Boolean, ageMillis: Long,
        timestampMillis: Long): Rejection? = when {
        provider != "gps" -> Rejection.PROVIDER
        latitude !in -90.0..90.0 || longitude !in -180.0..180.0 -> Rejection.COORDINATES
        mock -> Rejection.MOCK
        ageMillis !in 0..10000 -> Rejection.AGE
        timestampMillis <= 0 -> Rejection.TIMESTAMP
        else -> null
    }
    fun freshGps(provider: String?, latitude: Double, longitude: Double, mock: Boolean, ageMillis: Long): Boolean =
        provider == "gps" && latitude in -90.0..90.0 && longitude in -180.0..180.0 && !mock && ageMillis in 0..10000
    fun describe(provider: String?, ageMillis: Long, accuracy: Float?, mock: Boolean, speed: Boolean, bearing: Boolean): String {
        val source = when (provider) { "gps", "network" -> provider; else -> "其他" }
        val precision = accuracy?.takeIf { !it.isNaN() && !it.isInfinite() && it >= 0 }?.toInt()?.toString() ?: "未知"
        return "source=$source；ageMs=$ageMillis；精度米=$precision；模拟来源=$mock；有速度=$speed；有方向=$bearing"
    }
}
