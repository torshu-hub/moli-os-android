import java.util.Properties

plugins { id("com.android.application") }

val beta = Properties().apply { rootProject.file("beta.properties").inputStream().use { load(it) } }
val betaCode = beta.getProperty("versionCode").toInt()
val betaName = beta.getProperty("versionName")
require(beta.getProperty("applicationId") == "io.moli.android.app.beta" && betaCode > 16)
require(betaName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-beta\\.[1-9][0-9]*")))
val updateFixture = findProperty("moliUpdateFixture") == "true"

val embedNotices by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE"), rootProject.file("../NOTICE"))
    from(rootProject.file("../licenses/meross-lan-MIT.txt"))
    from(rootProject.file("../licenses/ha-solarman-MIT.txt"))
    into(layout.buildDirectory.dir("generated/notices"))
}

val merossTestFixtures by tasks.registering(Copy::class) {
    from(rootProject.file("../integrations/meross-mss310/fixtures")) { into("mss310") }
    into(layout.buildDirectory.dir("generated/meross-test-assets"))
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
        versionCode = (findProperty("moliVersionCode") as String? ?: betaCode.toString()).toInt().also {
            require(it >= 3) { "Version codes before 3 belong to earlier prototypes" }
        }
        versionName = findProperty("moliVersionName") as String? ?: betaName
        ndk { abiFilters += (findProperty("moliAbis") as String? ?: "arm64-v8a").split(",") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("debug") {
            isJniDebuggable = true
            buildConfigField("boolean", "UPDATE_FIXTURE", updateFixture.toString())
            val suffix = findProperty("moliAppSuffix") as String? ?: ".preview"
            require((suffix == ".beta" && updateFixture) || suffix in setOf(".preview", ".preview.receiver", ".preview.diagnostics", ".preview.integrations", ".preview.integrations.v051", ".preview.home.v060", ".preview.home.v061", ".preview.equipment.v070", ".preview.meross.v080", ".preview.network.v090", ".preview.account.v0100", ".preview.usability.v0110", ".preview.automatic.v0130")) { "Use an isolated preview identity" }
            applicationIdSuffix = suffix
            manifestPlaceholders["moliAppLabel"] = if (suffix == ".beta") "@string/app_name_beta_fixture" else if (suffix == ".preview.receiver") "@string/app_name_receiver" else if (suffix == ".preview.diagnostics") "@string/app_name_diagnostics" else if (suffix == ".preview.integrations" || suffix == ".preview.integrations.v051" || suffix == ".preview.home.v060" || suffix == ".preview.home.v061" || suffix == ".preview.equipment.v070" || suffix == ".preview.meross.v080" || suffix == ".preview.network.v090" || suffix == ".preview.account.v0100" || suffix == ".preview.usability.v0110" || suffix == ".preview.automatic.v0130") "@string/app_name_integrations" else "@string/app_name_preview"
        }
        // Intentionally unsigned. A durable signing key requires a separate owner decision.
        getByName("release") { isMinifyEnabled = false }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            manifestPlaceholders["moliAppLabel"] = "@string/app_name_beta"
            signingConfig = null // Never fall back to the temporary debug signer.
            buildConfigField("boolean", "UPDATE_FIXTURE", "false")
        }
    }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notices"))
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("../integrations/ipp/fixtures"))
    sourceSets.getByName("androidTest").assets.srcDir(layout.buildDirectory.dir("generated/meross-test-assets"))
}

tasks.named("preBuild") { dependsOn(embedNotices, merossTestFixtures) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Debug fixture overrides must never change the identity/version of a distributable beta.
androidComponents {
    onVariants(selector().withBuildType("beta")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(betaCode)
            output.versionName.set(betaName)
        }
    }
}
