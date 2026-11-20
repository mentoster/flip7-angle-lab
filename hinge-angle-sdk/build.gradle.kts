plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "com.mentoster"
version = "0.1.0"

android {
    namespace = "com.mentoster.flip7angle.sdk"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.mentoster"
                artifactId = "flip7-hinge-sdk"
                version = "0.1.0"
                pom {
                    name.set("Flip7 Hinge Angle SDK")
                    description.set(
                        "Offline Galaxy Z Flip7 hinge-angle estimation from public Android sensors",
                    )
                    url.set("https://github.com/mentoster/flip7-angle-lab")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    scm {
                        url.set("https://github.com/mentoster/flip7-angle-lab")
                        connection.set("scm:git:https://github.com/mentoster/flip7-angle-lab.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "localRelease"
                url = uri(layout.buildDirectory.dir("maven-repository"))
            }
        }
    }
}
