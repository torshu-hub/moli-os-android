import java.util.Properties
plugins { id("com.android.application") }
val beta = Properties().apply { rootProject.file("beta.properties").inputStream().use { load(it) } }
require(beta.getProperty("applicationId") == "io.moli.android.app.beta2")
val embedNotices by tasks.registering(Copy::class) {
    from(rootProject.file("../LICENSE"), rootProject.file("../NOTICE"))
    from(rootProject.file("../licenses/ha-solarman-MIT.txt"), rootProject.file("../licenses/meross-lan-MIT.txt"))
    into(layout.buildDirectory.dir("generated/notices"))
}
android {
    namespace = "io.moli.android"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = beta.getProperty("applicationId")
        versionCode = beta.getProperty("versionCode").toInt()
        versionName = beta.getProperty("versionName")
        minSdk = 28
        targetSdk = 35
        ndk { abiFilters += (findProperty("moliAbis") as String? ?: "arm64-v8a").split(",") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".test"; isJniDebuggable = true
            versionNameSuffix = "-test"
        }
        getByName("release") { isMinifyEnabled = false; signingConfig = null }
        create("beta") { initWith(getByName("release")); signingConfig = null }
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

tasks.matching { it.name == "mergeBetaNativeLibs" }.configureEach {
    doFirst { require(file("src/main/jniLibs/arm64-v8a/libmoli_android.so").isFile) { "Build the ARM64 JNI engine before packaging Beta 2" } }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { it.versionCode.set((findProperty("moliFixtureVersionCode") as String? ?: "1").toInt()) }
    }
}
