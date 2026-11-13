buildscript {
    dependencies {
        // AGP 9 has Kotlin built in and pulls KGP 2.2.10; this raises it to the catalog's Kotlin.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
