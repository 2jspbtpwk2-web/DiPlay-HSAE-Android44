package local.airuize.receiver

/** Separates local callback shutdown from the result of each platform unregister API. */
class GpsCleanupState {
    enum class Result { NOT_REQUIRED, PENDING, RETURNED, FAILED, NOT_RUN }
    private var pending = false
    private var location = Result.NOT_REQUIRED
    private var satellite = Result.NOT_REQUIRED
    private var locationError = "无"
    private var satelliteError = "无"
    private var queueError = "无"
    @Synchronized fun queued(locationRegistered: Boolean, satelliteRegistered: Boolean) {
        pending = true
        if(locationRegistered) location = Result.PENDING
        if(satelliteRegistered) satellite = Result.PENDING
    }
    @Synchronized fun locationReturned() { location = Result.RETURNED }
    @Synchronized fun satelliteReturned() { satellite = Result.RETURNED }
    @Synchronized fun locationFailed(error: Throwable) {
        location = Result.FAILED; locationError = LocationDiagnostics.exception(error)
    }
    @Synchronized fun satelliteFailed(error: Throwable) {
        satellite = Result.FAILED; satelliteError = LocationDiagnostics.exception(error)
    }
    @Synchronized fun queueRejected() {
        queueError = "主线程未接受注销任务"; pending = false
        if(location == Result.PENDING) location = Result.NOT_RUN
        if(satellite == Result.PENDING) satellite = Result.NOT_RUN
    }
    @Synchronized fun finished() { pending = false }
    @Synchronized fun snapshot(): String = "cleanupPending=$pending locationUnregisterApi=$location " +
        "satelliteUnregisterApi=$satellite locationCleanupError=$locationError satelliteCleanupError=$satelliteError " +
        "cleanupQueueError=$queueError（RETURNED表示注销API正常返回；FAILED表示未确认系统注销）"
}
