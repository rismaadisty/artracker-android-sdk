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

    internal fun has(className: String): Boolean {
        overrides[className]?.let { return it }
        cache[className]?.let { return it }
        val found = runCatching {
            Class.forName(className, false, ClassAvailability::class.java.classLoader)
            true
        }.getOrDefault(false)
        cache[className] = found
        return found
    }

    @VisibleForTesting
    internal fun resetOverrides() {
        overrides.clear()
    }
}
