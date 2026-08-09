import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The Sarvam key lives in local.properties and reaches the app through
// BuildConfig, so no key is ever committed in a source file.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// trim(): a stray space after '=' in local.properties would otherwise ride
// along into the auth header and 401 on every call.
val sarvamApiKey: String = localProps.getProperty("sarvam.api.key", "").trim()

android {
    namespace = "com.screensaathi"

    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    // AGP defaults to 36.0.0, which is not installed on this machine.
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.screensaathi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SARVAM_API_KEY", "\"$sarvamApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            // The classes under test call android.util.Log. Without this every
            // Log line throws "not mocked" and the test fails for no reason.
            isReturnDefaultValues = true

            all {
                // The eval suite prints its scorecard and each failing case to
                // stdout. Without this Gradle swallows it and a red build says
                // nothing about WHICH case regressed.
                it.testLogging {
                    showStandardStreams = true
                    events("passed", "failed", "skipped")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // android.jar's org.json is a stub that throws. This is the real thing, so
    // the DSL and planner parsers can be tested as they actually behave.
    testImplementation(libs.json)
}
