package local.airuize.receiver

/** Retains metadata for up to eight binding attempts, including late cleanup callbacks. */
class CanUnbindDiagnostics {
    private val history = ArrayList<Attempt>()
    private var sequence = 0L
    @Synchronized fun begin(): Attempt = Attempt(++sequence).also {
        if(history.size == 8) history.removeAt(0)
        history.add(it)
    }
    fun snapshot(): String {
        val entries = synchronized(this) { history.toList() }
        return "CAN解绑记录=["+entries.joinToString(" | ") { it.snapshot() }+"]"
    }
    class Attempt internal constructor(private val id: Long) {
        enum class Api { NOT_REQUIRED, NOT_CALLED, PENDING, RETURNED, FAILED, NOT_RUN }
        private var obligation = false
        private var result = Api.NOT_REQUIRED
        private var errorType = "无"
        @Synchronized fun beforeBind() { obligation = true; result = Api.NOT_CALLED }
        @Synchronized fun bound(success: Boolean) {
            obligation = success
            result = if(success) Api.NOT_CALLED else Api.NOT_REQUIRED
        }
        @Synchronized fun needsUnbind(): Boolean = obligation
        @Synchronized fun queued() { if(obligation) result = Api.PENDING }
        @Synchronized fun returned() { obligation = false; result = Api.RETURNED }
        @Synchronized fun failed(error: Throwable) {
            obligation = true; result = Api.FAILED; errorType = LocationDiagnostics.exception(error)
        }
        @Synchronized fun queueRejected() { result = Api.NOT_RUN; errorType = "主线程未接受解绑任务" }
        @Synchronized fun bindingQueueRejected() {
            obligation = false; result = Api.NOT_REQUIRED; errorType = "主线程未接受绑定任务，未执行bind"
        }
        @Synchronized fun snapshot(): String = "binding#$id obligation=$obligation unbindApi=$result error=$errorType"
    }
}
