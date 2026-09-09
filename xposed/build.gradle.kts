plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "moe.nepnep.hduhelper.xposed"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "moe.nepnep.hduhelper.xposed"
        minSdk = 33
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            if (providers.gradleProperty("moduleCiVersionName").isPresent) {
                // CI signs in a separate job; never use a per-run debug certificate.
                signingConfig = null
            }
        }
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        providers.gradleProperty("moduleCiVersionName").orNull?.let { ciVersion ->
            require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9a-f]{7}").matches(ciVersion)) {
                "moduleCiVersionName must be vMAJOR.MINOR.PATCH.COMMIT (7 lowercase hex characters)"
            }
            variant.outputs.forEach { it.versionName.set(ciVersion) }
        }
    }
}

dependencies {
    implementation(project(":xposed-contract"))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
}
