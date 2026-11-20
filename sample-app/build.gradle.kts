plugins {
    id("com.android.application")
}

android {
    namespace = "com.mentoster.flip7anglesdk.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mentoster.flip7anglesdk.sample"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    // This is a separate APK consuming the same binary AAR SDK module.
    implementation(project(":hinge-angle-sdk"))
}
