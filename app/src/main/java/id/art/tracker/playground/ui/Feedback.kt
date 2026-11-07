package id.art.tracker.playground.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Short confirmation shown after an action ("track `checkout_clicked` → antre"). Never put values in the message. */
class Feedback(private val sink: (String) -> Unit) {
    fun show(message: String) = sink(message)
}

val LocalFeedback = staticCompositionLocalOf { Feedback { } }
