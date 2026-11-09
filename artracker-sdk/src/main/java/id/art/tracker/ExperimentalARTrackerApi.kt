package id.art.tracker

/**
 * Marks an ARTracker API whose contract is not frozen yet: it may change or disappear in a minor release (versions
 * below 1.0 may break anything anyway, but marked APIs are the ones expected to move).
 *
 * Using it produces a compiler warning. Acknowledge it with `@OptIn(ExperimentalARTrackerApi::class)`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This ARTracker API is experimental and may change in a minor release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class ExperimentalARTrackerApi
