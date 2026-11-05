package id.art.tracker.lifecycle

/**
 * Overrides the screen name that ARTracker reports for an Activity (or Fragment) class. Without it the class's
 * simple name is used.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
public annotation class ARTrackerScreenName(val value: String)

/** `@ARTrackerScreenName` value when present and not blank, else the simple (or, for anonymous classes, full) name. */
internal fun screenNameOf(clazz: Class<*>): String {
    val annotated = clazz.getAnnotation(ARTrackerScreenName::class.java)?.value
    if (!annotated.isNullOrBlank()) return annotated
    return clazz.simpleName.ifEmpty { clazz.name }
}
