package id.art.tracker.playground.labs

enum class BurstDecision { ALLOW, CONFIRM, BLOCK }

object BurstPolicy {
    const val CONFIRM_ABOVE = 1_000
    const val MAX = 10_000

    /** Any load against a REMOTE endpoint is blocked outright; a large local burst asks for confirmation. */
    fun decide(count: Int, remote: Boolean): BurstDecision = when {
        remote -> BurstDecision.BLOCK
        count > CONFIRM_ABOVE -> BurstDecision.CONFIRM
        else -> BurstDecision.ALLOW
    }

    fun clamp(count: Int): Int = count.coerceIn(0, MAX)
}
