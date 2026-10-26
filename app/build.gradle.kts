plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The version comes from the git tag CI builds (`-PappVersion=v1.4.2`).
// versionCode = MAJOR*10000 + MINOR*100 + PATCH. The dashboard computes the
// same number from the same tag (src/app_release.py, version_code) to decide
// whether an installed app is out of date, so the two formulas must agree.
val appVersionName: String =
    (project.findProperty("appVersion") as String?)?.trim()?.removePrefix("v")?.ifBlank { null }
        ?: "0.0.1"
val appVersionCode: Int = run {
    val parts = appVersionName.split(".")
    require(parts.size == 3 && parts.all { it.toIntOrNull() != null }) {
        "appVersion must look like v1.2.3, got '$appVersionName'"
    }
    val (major, minor, patch) = parts.map { it.toInt() }
    require(minor < 100 && patch < 100) { "minor and patch must be below 100" }
    maxOf(1, major * 10000 + minor * 100 + patch)
}

// The one site the app opens. Everything privileged (the native bridge, the
// media header rules) is bound to exactly this origin.
val baseUrl: String = (project.findProperty("baseUrl") as String?) ?: "https://zer0space.com"
require(baseUrl.startsWith("https://")) { "baseUrl must be https" }

// Release signing comes from the environment, which CI fills from Actions
// secrets. Locally, without them, a release build is simply left unsigned.
val keystorePath: String? = System.getenv("ZS_KEYSTORE_PATH")?.ifBlank { null }

android {
    namespace = "net.zer0space.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "net.zer0space.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "BASE_URL", "\"$baseUrl\"")
        // In-app updates only ever install an APK from this repo's own releases.
        buildConfigField(
            "String",
            "RELEASE_PREFIX",
            "\"https://github.com/zer0space-net/zer0space-android-app/releases/download/\"",
        )
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("ZS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZS_KEY_ALIAS")
                keyPassword = System.getenv("ZS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else null
        }
        debug {
            // Installs next to the real app, so testing a branch never touches it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // The dependency metadata block is encrypted for Google Play only. This app
    // is not on Play, so it would just be an opaque blob in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        // Lint runs in CI on every push; these are the ones that would mean a
        // broken or unsafe build rather than a style nit.
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Cronet is Chrome's own network stack, provided by Google Play services.
    // Requests made through it look like Chrome on the wire (TLS fingerprint,
    // HTTP/2 settings), which Cloudflare-fronted hosters check. Devices without
    // Play services fall back to OkHttp.
    implementation("com.google.android.gms:play-services-cronet:18.1.0")
}
