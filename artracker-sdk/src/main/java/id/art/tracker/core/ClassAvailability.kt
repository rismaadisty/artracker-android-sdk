package id.art.tracker.core

import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime detection of optional dependencies (OkHttp, Fragment, Compose, Navigation), which are
 * `compileOnly` in the SDK. Classes are located with `Class.forName(name, false, loader)` so they
 * are never initialized. Thread-safe; never throws.
 */
internal object ClassAvailability {
    private const val OKHTTP = "okhttp3.OkHttpClient"
    private const val FRAGMENT = "androidx.fragment.app.FragmentActivity"
    private const val COMPOSE = "androidx.compose.ui.platform.AndroidComposeView"
    private const val NAVIGATION = "androidx.navigation.NavController"

    private val cache = ConcurrentHashMap<String, Boolean>()

    /**
     * Test hook: className -> forced result. An entry takes precedence over real class loading
     * (true = pretend present, false = pretend absent). Use [resetOverrides] to clear it.
     */
    @VisibleForTesting
    internal val overrides: MutableMap<String, Boolean> = ConcurrentHashMap()

    val okHttp: Boolean get() = has(OKHTTP)
    val fragment: Boolean get() = has(FRAGMENT)
    val compose: Boolean get() = has(COMPOSE)
    val navigation: Boolean get() = has(NAVIGATION)

    internal fun has(className: String): Boolean = probe(className) {
        Class.forName(className, false, ClassAvailability::class.java.classLoader)
        true
    }

    /**
     * Whether [check] succeeds, answered once per [key] (then cached) and overridable like [has]. For features whose
     * presence is a *member* of an optional library, not a class: the check calls it directly, and anything it throws,
     * `NoSuchMethodError` included, means "not there". Never looked up by name: a host's R8 renames library members.
     */
    internal fun probe(key: String, check: () -> Boolean): Boolean {
        overrides[key]?.let { return it }
        cache[key]?.let { return it }
        val result = try {
            check()
        } catch (_: Throwable) {
            false
        }
        cache[key] = result
        return result
    }

    @VisibleForTesting
    internal fun resetOverrides() {
        overrides.clear()
    }
}
