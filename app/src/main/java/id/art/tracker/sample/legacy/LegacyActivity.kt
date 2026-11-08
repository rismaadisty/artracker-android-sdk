package id.art.tracker.sample.legacy

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import id.art.tracker.R
import id.art.tracker.lifecycle.ARTrackerScreenName

/** XML-layout screen hosting [LegacyFragment]: exercises the View walker, tag masking and fragment tracking. */
@ARTrackerScreenName("LegacyScreen")
class LegacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legacy)
    }
}
