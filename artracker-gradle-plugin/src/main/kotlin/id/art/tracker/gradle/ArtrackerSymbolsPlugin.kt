package id.art.tracker.gradle

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.VariantOutputConfiguration
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers `uploadArtracker<Variant>Mapping` for every minified variant of an Android application and runs it after
 * `assemble<Variant>` and `bundle<Variant>`.
 */
public class ArtrackerSymbolsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("artracker", ArtrackerExtension::class.java)
        extension.uploadMapping.convention(true)
        extension.failOnUploadError.convention(false)
        extension.apiUrl.convention(
            project.providers.environmentVariable("ARTRACKER_API_URL")
                .orElse(project.providers.gradleProperty("artracker.apiUrl")),
        )
        extension.secretKey.convention(
            project.providers.environmentVariable("ARTRACKER_SECRET_KEY")
                .orElse(project.providers.gradleProperty("artracker.secretKey")),
        )

        project.plugins.withId("com.android.application") {
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                if (!extension.uploadMapping.get()) return@onVariants
                // No minification means no mapping file: nothing to upload, nothing to register.
                if ((variant as? ApplicationVariant)?.isMinifyEnabled != true) return@onVariants

                // ABI or density splits have no SINGLE output: use the universal one, else the first.
                val output = variant.outputs.firstOrNull { it.outputType == VariantOutputConfiguration.OutputType.SINGLE }
                    ?: variant.outputs.firstOrNull { it.outputType == VariantOutputConfiguration.OutputType.UNIVERSAL }
                    ?: variant.outputs.first()
                val capitalized = variant.name.replaceFirstChar { it.uppercase() }
                val upload = project.tasks.register("uploadArtracker${capitalized}Mapping", UploadMappingTask::class.java) { task ->
                    task.description = "Uploads the ${variant.name} mapping.txt to Artracker."
                    task.group = "artracker"
                    task.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                    task.versionName.set(output.versionName.orElse(""))
                    task.versionCode.set(output.versionCode.orElse(0))
                    task.apiUrl.set(extension.apiUrl)
                    task.secretKey.set(extension.secretKey)
                    task.failOnUploadError.set(extension.failOnUploadError)
                }
                project.tasks
                    .matching { it.name == "assemble$capitalized" || it.name == "bundle$capitalized" }
                    .configureEach { it.finalizedBy(upload) }
            }
        }
    }
}
