plugins { id("com.android.application") }

val embedNotices by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE"), rootProject.file("../NOTICE"))
    into(layout.buildDirectory.dir("generated/notices"))
}

android {
    namespace = "io.moli.android"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "io.moli.android.app"
        minSdk = 28 // Provisional until the target device is inspected.
        targetSdk = 35
        manifestPlaceholders["moliAppLabel"] = "@string/app_name"
        versionCode = (findProperty("moliVersionCode") as String? ?: "8").toInt().also {
            require(it >= 3) { "Version codes before 3 belong to earlier prototypes" }
        }
        versionName = findProperty("moliVersionName") as String? ?: "0.6.0-preview"
        ndk { abiFilters += (findProperty("moliAbis") as String? ?: "arm64-v8a").split(",") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("debug") {
            isJniDebuggable = true
            val suffix = findProperty("moliAppSuffix") as String? ?: ".preview"
            require(suffix in setOf(".preview", ".preview.receiver", ".preview.diagnostics", ".preview.integrations", ".preview.integrations.v051", ".preview.home.v060")) { "Use an isolated preview identity" }
            applicationIdSuffix = suffix
            manifestPlaceholders["moliAppLabel"] = if (suffix == ".preview.receiver") "@string/app_name_receiver" else if (suffix == ".preview.diagnostics") "@string/app_name_diagnostics" else if (suffix == ".preview.integrations" || suffix == ".preview.integrations.v051" || suffix == ".preview.home.v060") "@string/app_name_integrations" else "@string/app_name_preview"
        }
        // Intentionally unsigned. A durable signing key requires a separate owner decision.
        getByName("release") { isMinifyEnabled = false }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notices"))
}

tasks.named("preBuild") { dependsOn(embedNotices) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
