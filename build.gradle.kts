@file:Suppress("UNUSED_VARIABLE")

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import java.net.URL
import java.util.*

buildscript {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }
    dependencies {
        classpath(libs.build.android)
        classpath(libs.build.kotlin.common)
        classpath(libs.build.kotlin.serialization)
        classpath(libs.build.ksp)
        classpath(libs.build.golang)
    }
}

subprojects {
    repositories {
        mavenCentral()
        google()
        maven("https://raw.githubusercontent.com/MetaCubeX/maven-backup/main/releases")
    }

    val isApp = name == "app"

    apply(plugin = if (isApp) "com.android.application" else "com.android.library")

    extensions.configure<BaseExtension> {
        buildFeatures.buildConfig = true
        defaultConfig {
            if (isApp) {
                applicationId = "com.roylyl.tools"
            }

            project.name.let { name ->
                namespace = if (name == "app") "com.github.kr328.clash"
                else "com.github.kr328.clash.$name"
            }

            minSdk = if (isApp) 31 else 21
            targetSdk = 35

            // versionCode must increase for every distributed APK.
            versionName = rootProject.providers.gradleProperty("roytoolsVersion").getOrElse("2.0.0")
            val parts = versionName!!.split('.').map { it.toInt() }
            require(parts.size == 3 && parts[0] in 0..2100 && parts[1] in 0..999 && parts[2] in 0..999) {
                "roytoolsVersion must be major.minor.patch"
            }
            versionCode = parts[0] * 1_000_000 + parts[1] * 1000 + parts[2]

            resValue("string", "release_name", "$versionName")
            resValue("integer", "release_code", "$versionCode")

            ndk {
                abiFilters += if (isApp) listOf("arm64-v8a") else listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }

            externalNativeBuild {
                cmake {
                    abiFilters("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                }
            }

            if (!isApp) {
                consumerProguardFiles("consumer-rules.pro")
            } else {
                setProperty("archivesBaseName", "RoyTools-$versionName")
            }
        }

        ndkVersion = "29.0.14206865"

        compileSdkVersion(defaultConfig.targetSdk!!)

        if (isApp) {
            packagingOptions {
                resources {
                    excludes.add("DebugProbesKt.bin")
                    excludes.add("META-INF/versions/9/OSGI-INF/MANIFEST.MF")
                }
            }
        }

        productFlavors {
            flavorDimensions("feature")

            val removeSuffix = true

            create("alpha") {
                dimension = flavorDimensionList[0]


                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                resValue("string", "launch_name", "RoyTools")
                resValue("string", "application_name", "RoyTools")

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".alpha"
                }
            }

            create("meta") {
                isDefault = true

                dimension = flavorDimensionList[0]

                buildConfigField("boolean", "PREMIUM", "Boolean.parseBoolean(\"false\")")

                resValue("string", "launch_name", "RoyTools")
                resValue("string", "application_name", "RoyTools")

                if (isApp && !removeSuffix) {
                    applicationIdSuffix = ".meta"
                }
            }
        }

        sourceSets {
            getByName("meta") {
                java.srcDirs("src/foss/java")
            }
            getByName("alpha") {
                java.srcDirs("src/foss/java")
            }
        }

        signingConfigs {
            val signingFile = rootProject.file("signing.properties")
            val signingProperties = Properties().apply {
                if (signingFile.exists()) signingFile.inputStream().use(this::load)
            }
            fun signingValue(environment: String, property: String): String? =
                System.getenv(environment)?.takeIf { it.isNotBlank() }
                    ?: signingProperties.getProperty(property)?.takeIf { it.isNotBlank() }
            val keystorePath = signingValue("ROYTOOLS_KEYSTORE_PATH", "keystore.file")
                ?: if (signingFile.exists()) "release.keystore" else null
            if (keystorePath != null) {
                val password = signingValue("ROYTOOLS_KEYSTORE_PASSWORD", "keystore.password")
                val alias = signingValue("ROYTOOLS_KEY_ALIAS", "key.alias")
                val aliasPassword = signingValue("ROYTOOLS_KEY_PASSWORD", "key.password")
                require(password != null && alias != null && aliasPassword != null) {
                    "Release signing requires keystore path/password and key alias/password; use local signing.properties or ROYTOOLS_KEYSTORE_* / ROYTOOLS_KEY_* environment variables."
                }
                require(rootProject.file(keystorePath).isFile) { "The configured release keystore does not exist." }
                create("release") {
                    storeFile = rootProject.file(keystorePath)
                    storePassword = password
                    keyAlias = alias
                    keyPassword = aliasPassword
                }
            }
        }

        buildTypes {
            named("release") {
                isMinifyEnabled = isApp
                isShrinkResources = isApp
                // An unsigned Release is explicit; never silently publish a debug key.
                signingConfig = signingConfigs.findByName("release")
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
            named("debug") {
                versionNameSuffix = ""
            }
        }

        buildFeatures.apply {
            dataBinding {
                isEnabled = name != "hideapi"
            }
        }

        if (isApp) {
            this as AppExtension

            splits {
                abi {
                    isEnable = true
                    isUniversalApk = true
                    reset()
                    include("arm64-v8a")
                }
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}



