package local.airuize.receiver

/** Repeated avcC packets are not decoder configuration changes. */
class VideoConfigState {
    @Volatile var snapshot: ByteArray? = null
        private set
    @Synchronized fun update(bytes: ByteArray): Boolean {
        if (snapshot?.contentEquals(bytes) == true) return false
        snapshot = bytes.copyOf()
        return true
    }
}
