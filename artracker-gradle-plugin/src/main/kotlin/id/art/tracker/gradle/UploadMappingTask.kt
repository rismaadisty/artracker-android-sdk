package id.art.tracker.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

/** Uploads one variant's `mapping.txt`. It has no outputs, so it runs on every invocation; the server de-duplicates. */
public abstract class UploadMappingTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    public abstract val mappingFile: RegularFileProperty

    @get:Input
    public abstract val versionName: Property<String>

    @get:Input
    public abstract val versionCode: Property<Int>

    @get:Input
    @get:Optional
    public abstract val apiUrl: Property<String>

    /** @Internal: kept out of build-cache keys and never logged. */
    @get:Internal
    public abstract val secretKey: Property<String>

    @get:Input
    public abstract val failOnUploadError: Property<Boolean>

    @TaskAction
    public fun upload() {
        val url = apiUrl.orNull?.trim().orEmpty()
        val key = secretKey.orNull?.trim().orEmpty()
        val version = versionName.orNull.orEmpty()
        val mapping = mappingFile.orNull?.asFile

        if (url.isEmpty() || key.isEmpty()) {
            logger.warn("Artracker: apiUrl or secretKey is not set, skipping mapping upload")
            return
        }
        if (!key.startsWith(SECRET_PREFIX)) {
            logger.warn("Artracker: the key is not a secret key (it must start with $SECRET_PREFIX), skipping mapping upload")
            return
        }
        if (version.isEmpty()) {
            logger.warn("Artracker: the variant has no versionName, skipping mapping upload")
            return
        }
        if (mapping == null || !mapping.isFile) {
            logger.warn("Artracker: no mapping file for $version, skipping mapping upload")
            return
        }

        when (val result = MappingUploader().upload(url, key, version, versionCode.get().toLong(), mapping)) {
            UploadResult.Uploaded -> logger.lifecycle("Artracker: mapping for $version uploaded")
            UploadResult.Unchanged -> logger.lifecycle("Artracker: mapping for $version already uploaded")
            is UploadResult.Failed -> {
                val message = "Artracker: mapping upload for $version failed: ${result.reason}"
                if (failOnUploadError.get()) throw GradleException(message) else logger.warn(message)
            }
        }
    }

    private companion object {
        const val SECRET_PREFIX = "art_sec_"
    }
}
