package local.airuize.receiver

/**
 * Retain the newest accepted monotonic fix until its existing freshness check expires it.
 * A rejected or out-of-order callback must not erase a newer, still valid fix. The owning
 * provider validates coordinates/provider/mock/age before offering a value and clears this
 * store on provider disable, STOP or expiry; this class never extends the freshness budget.
 */
internal class LatestGpsFix<T : Any> {
    enum class Result { ACCEPTED, REJECTED, OUT_OF_ORDER }
    private var value: T? = null
    private var elapsedNanos = -1L

    fun offer(candidate: T, candidateElapsedNanos: Long, valid: Boolean): Result {
        if (!valid || candidateElapsedNanos <= 0) return Result.REJECTED
        if (value != null && candidateElapsedNanos < elapsedNanos) return Result.OUT_OF_ORDER
        value = candidate
        elapsedNanos = candidateElapsedNanos
        return Result.ACCEPTED
    }

    fun current(): T? = value
    fun clear() { value = null; elapsedNanos = -1L }
}
