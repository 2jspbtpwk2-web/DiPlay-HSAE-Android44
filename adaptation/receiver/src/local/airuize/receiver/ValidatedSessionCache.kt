package local.airuize.receiver

/** Lifecycle worker only. Publish after the loader's validation succeeds; failures remain retryable. */
internal class ValidatedSessionCache<T : Any> {
    private var value: T? = null
    fun getOrLoad(loader: () -> T): T = value ?: loader().also { value = it }
}
