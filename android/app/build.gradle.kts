import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    kotlin("android")
    kotlin("kapt")
    id("com.android.application")
}

android {
    packaging {
        jniLibs {
            // Preserve every native feature while compressing libraries in the APK.
            // Android extracts them at install time for normal System.loadLibrary calls.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    compileOnly(project(":hideapi"))
    // The embedded Shizuku server uses Android system APIs supplied by the device.
    // Make their stubs visible to the final shrinker without bundling system classes.
    compileOnly("dev.rikka.hidden:stub:4.4.0")

    implementation(project(":refresh"))
    implementation(project(":core"))
    implementation(project(":service"))
    implementation(project(":design"))
    implementation(project(":common"))

    implementation(libs.kotlin.coroutine)
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.coordinator)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.material)
    implementation(libs.quickie.bundled)
    implementation(libs.androidx.activity.ktx)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.kotlin.serialization.json)
}

tasks.getByName("clean", type = Delete::class) {
    delete(file("release"))
}

abstract class DownloadGeoFilesTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localDirectory: DirectoryProperty

    @get:Input
    val geoFilesUrls = mapOf(
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.metadb" to "geoip.metadb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat" to "geosite.dat",
        // "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/country.mmdb" to "country.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/GeoLite2-ASN.mmdb" to "ASN.mmdb",
        "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/BundleMRS.7z" to "BundleMRS.7z",
    )

    @TaskAction
    fun prepare() {
        val localGeoDirectory = localDirectory.orNull?.asFile
        if (localGeoDirectory != null) {
            geoFilesUrls.values.forEach { name ->
                val input = localGeoDirectory.resolve(name)
                require(input.isFile && input.length() > 0) {
                    "Local Geo input missing or empty: $name. Set ROYTOOLS_GEO_DIR to a directory containing all four Geo files."
                }
            }
        }
        geoFilesUrls.forEach { (downloadUrl, outputFileName) ->
            val url = URL(downloadUrl)
            val outputPath = outputDirectory.get().file(outputFileName).asFile
            if (localGeoDirectory == null && outputPath.isFile && outputPath.length() > 0) return@forEach
            outputPath.parentFile.mkdirs()
            val temporaryPath = outputPath.toPath().resolveSibling("$outputFileName.part")
            try {
                if (localGeoDirectory != null) {
                    Files.copy(localGeoDirectory.resolve(outputFileName).toPath(), temporaryPath, StandardCopyOption.REPLACE_EXISTING)
                } else {
                    val connection = url.openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }
                    connection.getInputStream().use { input ->
                        Files.copy(input, temporaryPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                require(Files.size(temporaryPath) > 0) { "$outputFileName download was empty" }
                Files.move(temporaryPath, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
                logger.lifecycle("$outputFileName prepared from ${if (localGeoDirectory == null) "network" else "local cache"}")
            } finally {
                Files.deleteIfExists(temporaryPath)
            }
        }
    }
}

val geoFilesDownloadDir = layout.buildDirectory.dir("generated/geoAssets")
val localGeoDirectory = providers.gradleProperty("roytoolsGeoDir")
    .orElse(providers.environmentVariable("ROYTOOLS_GEO_DIR"))
    .orNull?.takeIf { it.isNotBlank() }?.let { file(it) }
if (localGeoDirectory != null) {
    require(localGeoDirectory.canonicalFile != geoFilesDownloadDir.get().asFile.canonicalFile) {
        "ROYTOOLS_GEO_DIR must be an input directory outside app/build/generated/geoAssets."
    }
}
val downloadGeoFiles = tasks.register<DownloadGeoFilesTask>("downloadGeoFiles") {
    outputDirectory.set(geoFilesDownloadDir)
    localGeoDirectory?.let { localDirectory.set(it) }
}

// Register the producer with AGP, so every asset consumer (including lint model
// generation) receives the dependency rather than relying on task-name guesses.
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadGeoFiles) { it.outputDirectory }
    }
}

tasks.getByName("clean", type = Delete::class) {
    delete(geoFilesDownloadDir)
}

