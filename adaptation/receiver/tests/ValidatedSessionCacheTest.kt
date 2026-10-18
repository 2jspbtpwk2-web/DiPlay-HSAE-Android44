package local.airuize.receiver

import org.junit.Assert.*
import org.junit.Test

class ValidatedSessionCacheTest {
    @Test fun successfulValidationRunsOncePerServiceCache() {
        val cache = ValidatedSessionCache<Any>(); val identity = Any(); var loads = 0
        repeat(20) { assertSame(identity, cache.getOrLoad { loads++; identity }) }
        assertEquals(1, loads)
        val second = ValidatedSessionCache<Any>()
        second.getOrLoad { loads++; Any() }; assertEquals(2, loads)
    }
    @Test fun failedValidationIsNotCached() {
        val cache = ValidatedSessionCache<String>(); var loads = 0
        repeat(2) { try { cache.getOrLoad { loads++; error("validation") }; fail() } catch (_: IllegalStateException) {} }
        assertEquals("valid", cache.getOrLoad { loads++; "valid" })
        assertEquals("valid", cache.getOrLoad { error("must reuse") })
        assertEquals(3, loads)
    }
}
