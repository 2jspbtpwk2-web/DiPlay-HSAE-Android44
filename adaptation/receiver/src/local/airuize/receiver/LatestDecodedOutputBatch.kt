package local.airuize.receiver

/**
 * Worker-owned selection of already decoded pictures from one bounded drain pass. Compression
 * inputs and their reference chain are unchanged: an older picture is omitted from the Surface
 * only after a newer eligible decoded picture has become available.
 *
 * Ownership is removed before calling release, even if that callback throws. Retrying an ambiguous
 * MediaCodec release could release a reused index; the caller must tear down the codec after an
 * error. Registering a new candidate before releasing the old one lets finally/abort return the
 * newer index when the old release fails. The callback must not reenter this object.
 */
internal class LatestDecodedOutputBatch(private val release: (Int, ReleaseKind) -> Unit) {
    enum class ReleaseKind { RENDER, SUPERSEDED, RECOVERY, METADATA, CANCELLED }

    private var pendingIndex = -1

    fun keep(index: Int) {
        require(index >= 0)
        check(index != pendingIndex) { "Output index is already owned" }
        val previous = pendingIndex
        pendingIndex = index
        if (previous >= 0) release(previous, ReleaseKind.SUPERSEDED)
    }

    /** Non-picture/recovery outputs do not replace the last eligible picture. */
    fun discard(index: Int, reason: ReleaseKind) {
        require(index >= 0)
        require(reason == ReleaseKind.RECOVERY || reason == ReleaseKind.METADATA || reason == ReleaseKind.CANCELLED)
        check(index != pendingIndex) { "Output index is already owned" }
        release(index, reason)
    }

    fun finish(render: Boolean) {
        val index = pendingIndex
        pendingIndex = -1
        if (index >= 0) release(index, if (render) ReleaseKind.RENDER else ReleaseKind.CANCELLED)
    }

    fun abort() = finish(false)

    companion object {
        /** Surface decoders may report size=0 for a picture. Empty EOS and codec config are not pictures. */
        fun isPicture(size: Int, codecConfig: Boolean, endOfStream: Boolean): Boolean =
            size >= 0 && !codecConfig && (size > 0 || !endOfStream)
    }
}
