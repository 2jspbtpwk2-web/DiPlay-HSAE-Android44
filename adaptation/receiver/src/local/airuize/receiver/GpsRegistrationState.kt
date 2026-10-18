package local.airuize.receiver

/** Registration ownership survives local running=false and ambiguous vendor API failures. */
class GpsRegistrationState {
    enum class Api { NOT_REQUIRED, NOT_CALLED, RETURNED, FAILED }
    private var obligation = false
    private var lastApi = Api.NOT_REQUIRED
    private var lastError = "无"
    @Synchronized fun beforeRequest() { obligation = true; lastApi = Api.NOT_CALLED }
    @Synchronized fun needsRemoval(): Boolean = obligation
    @Synchronized fun removalReturned() { obligation = false; lastApi = Api.RETURNED }
    @Synchronized fun removalFailed(error: Throwable) {
        obligation = true; lastApi = Api.FAILED; lastError = LocationDiagnostics.exception(error)
    }
    @Synchronized fun snapshot(): String = "registrationObligation=$obligation unregisterApi=$lastApi unregisterError=$lastError"
}
