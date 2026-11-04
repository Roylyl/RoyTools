plugins {
    kotlin("android")
    id("com.android.library")
    id("dev.rikka.tools.refine") version "4.4.0"
}
val royToolsVersion = providers.gradleProperty("roytoolsVersion").getOrElse("1.1.1")
val royToolsParts = royToolsVersion.split('.').map { it.toInt() }
val royToolsCode = royToolsParts[0] * 1_000_000 + royToolsParts[1] * 1000 + royToolsParts[2]
android {
    namespace = "com.mahmutalperenunal.adaptivehz"
    defaultConfig { minSdk = 31 }
    buildFeatures { aidl = true; buildConfig = true }
    defaultConfig {
        buildConfigField("int", "VERSION_CODE", royToolsCode.toString())
        buildConfigField("String", "APPLICATION_ID", "\"com.roylyl.tools\"")
        buildConfigField("String", "VERSION_NAME", "\"" + royToolsVersion + "\"")
    }
    sourceSets.getByName("main").java.srcDirs(
        "../vendor/Shizuku/manager/src/main/java",
        "../vendor/Shizuku/server/src/main/java",
        "../vendor/Shizuku/starter/src/main/java",
        "../vendor/Shizuku/common/src/main/java",
        "../vendor/Shizuku/api/server-shared/src/main/java",
        "../vendor/Shizuku/api/rish/src/main/java"
    )
    kotlinOptions { jvmTarget = "21" }
}
dependencies {
    implementation(project(":common"))
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.hidden:compat:4.4.0")
    compileOnly("dev.rikka.hidden:stub:4.4.0")
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}



