plugins {
    alias(libs.plugins.android.application)
}

// Release signing is read from the environment. Without FRAME_KEYSTORE_FILE the release
// build is still assembled, unsigned, and debug builds are unaffected. A blank variable counts
// as unset: CI hands an empty string for a secret it does not have (pull requests from forks).
fun signingVariable(name: String): Provider<String> =
    providers.environmentVariable(name).map { it.trim() }.filter { it.isNotEmpty() }

val releaseKeystore = signingVariable("FRAME_KEYSTORE_FILE")

android {
    namespace = "io.github.jkfran.frameartmode"
    // OkHttp 5.5.1's Android artifact requires compileSdk 37.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jkfran.frameartmode"
        minSdk = 21
        // 37 would enforce Android 17's local network permission, which a screensaver
        // cannot ask for. See lint.xml.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore.isPresent) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = signingVariable("FRAME_KEYSTORE_PASSWORD").orNull
                keyAlias = signingVariable("FRAME_KEY_ALIAS").orNull
                keyPassword = signingVariable("FRAME_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.kotlinx.coroutines.test)
}
