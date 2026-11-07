package id.art.tracker

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Replaces the real SampleApplication with [TestPlaygroundApplication], which can never reach a remote endpoint. */
class PlaygroundTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, TestPlaygroundApplication::class.java.name, context)
}
