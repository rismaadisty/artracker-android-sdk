package id.art.tracker.gradle

import org.gradle.api.provider.Property

/**
 * The `artracker { }` block.
 *
 * `apiUrl` falls back to the `ARTRACKER_API_URL` environment variable, then the `artracker.apiUrl` Gradle property.
 * `secretKey` falls back to `ARTRACKER_SECRET_KEY`, then `artracker.secretKey`. Keep the secret key out of source control:
 * set it in CI, not in the build script.
 */
public abstract class ArtrackerExtension {
    public abstract val apiUrl: Property<String>
    public abstract val secretKey: Property<String>

    /** `false` registers no upload task at all. Default `true`. */
    public abstract val uploadMapping: Property<Boolean>

    /** `true` fails the build when the upload fails. Default `false`: a failed upload only warns. */
    public abstract val failOnUploadError: Property<Boolean>
}
