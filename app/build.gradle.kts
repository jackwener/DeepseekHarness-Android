plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

val thirdPartyOut: String = providers.gradleProperty("dsh.thirdPartyOut").orElse("../toolchains/out/third-party").get()

fun shizukuPin(name: String): String =
    Regex("^$name=(\\S+)$", RegexOption.MULTILINE).find(rootProject.file("tools/fetch-shizuku.sh").readText())?.groupValues?.get(1)
        ?: error("tools/fetch-shizuku.sh: missing $name pin")

android {
    namespace = "io.github.jackwener.dsh"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.jackwener.dsh"
        minSdk = 29
        targetSdk = 36
        versionCode = 15
        versionName = "0.2.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
        // Bundled official Shizuku APK (in-app install/update); pinned in tools/fetch-shizuku.sh.
        buildConfigField("String", "SHIZUKU_VERSION", "\"${shizukuPin("VERSION")}\"")
        buildConfigField("long", "SHIZUKU_VERSION_CODE", "${shizukuPin("VERSION_CODE")}L")
    }

    // Release signing comes from Gradle properties (e.g. ~/.gradle/gradle.properties or -P), never from the repo:
    // dsh.signing.storeFile, dsh.signing.storePassword, dsh.signing.keyAlias, dsh.signing.keyPassword.
    // Without them a release build falls back to the debug key.
    val signingProp = { k: String -> providers.gradleProperty("dsh.signing.$k").orNull }
    signingConfigs {
        if (signingProp("storeFile") != null) create("release") {
            storeFile = file(signingProp("storeFile")!!)
            storePassword = signingProp("storePassword")
            keyAlias = signingProp("keyAlias")
            keyPassword = signingProp("keyPassword")
        }
    }
    buildTypes {
        debug {
            // Optional side-by-side real-device testing without replacing the user's installed app.
            applicationIdSuffix = providers.gradleProperty("dsh.debugSuffix").orNull
        }
        release {
            // R8 stays off until a minified build has been verified on a device.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // require-builtin is replaced by --expose-internals (js/patches/02); never ship the old addon.
            excludes += "**/libdsh_require_builtin.so"
        }
    }
    // Native engine (libnode, JNI wrapper, addons, rg) and the dsh engine bundle are built outside Gradle:
    // native/build.sh and js/build-engine.sh. Point these at their output directories.
    sourceSets["main"].jniLibs.directories += providers.gradleProperty("dsh.nativeOut").orElse("../toolchains/out/jniLibs").get()
    sourceSets["main"].assets.directories += providers.gradleProperty("dsh.engineOut").orElse("../toolchains/out/engine").get()
    // assets/shizuku/{shizuku.apk,LICENSE}, written by tools/fetch-shizuku.sh (task fetchShizuku below).
    sourceSets["main"].assets.directories += "$thirdPartyOut/assets"
}

// Downloads + verifies (sha256 and signing cert) the pinned Shizuku release; a no-op once it is there.
// -Pdsh.fetchShizuku=false skips it: the app then falls back to Play Store / GitHub for installing Shizuku.
val fetchShizuku = tasks.register<Exec>("fetchShizuku") {
    val script = rootProject.file("tools/fetch-shizuku.sh")
    val out = file(thirdPartyOut)
    inputs.file(script)
    outputs.dir(out.resolve("assets/shizuku"))
    commandLine(script.absolutePath, out.absolutePath)
}
if (providers.gradleProperty("dsh.fetchShizuku").orElse("true").get().toBoolean()) {
    tasks.named("preBuild") { dependsOn(fetchShizuku) }
}

kotlin { jvmToolchain(21) }

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.coil.compose)
    implementation(libs.markdown.m3)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Room migration tests on the JVM: the real SQLite engine runs the migration SQL against the exported schemas.
    testImplementation(libs.sqlite.jdbc)
    // Geofences on devices with Play services (background-FGS-start exempt); LocationManager otherwise.
    implementation(libs.play.services.location)
    // PDF text for file_read (Apache-2.0, ~3 MB). BouncyCastle is only needed for encrypted PDFs, which
    // file_read reports as unreadable instead (saves ~8 MB).
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    // Offline text recognition (Chinese + Latin, bundled model) for custom-drawn screens without readable nodes.
    implementation(libs.mlkit.text.chinese)
}
