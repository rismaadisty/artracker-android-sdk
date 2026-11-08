package id.art.tracker

/** Severity of a log item. */
public enum class LogLevel(internal val wire: String) {
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    FATAL("fatal"),
}
