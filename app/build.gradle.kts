plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun env(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

fun envInt(name: String, defaultValue: Int): Int = env(name)?.toIntOrNull() ?: defaultValue

val releaseSigningEnvNames = listOf(
    "TX5DR_ANDROID_KEYSTORE_FILE",
    "TX5DR_ANDROID_KEYSTORE_PASSWORD",
    "TX5DR_ANDROID_KEY_ALIAS",
    "TX5DR_ANDROID_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningEnvNames.associateWith(::env)
val releaseSigningConfigured = releaseSigningValues.values.all { it != null }

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    namespace = "com.tx5dr.bridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tx5dr.bridge"
        minSdk = 28
        targetSdk = 34
        versionCode = envInt("TX5DR_ANDROID_VERSION_CODE", 1)
        versionName = env("TX5DR_ANDROID_VERSION_NAME") ?: "0.1.0-poc"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    androidResources {
        noCompress += setOf("tgz", "gz", "zst", "arm64", "sha256")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("TX5DR_ANDROID_KEYSTORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("TX5DR_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("TX5DR_ANDROID_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("TX5DR_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

tasks.register("checkReleaseSigningSecrets") {
    doLast {
        val missing = releaseSigningEnvNames.filter { env(it) == null }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release APK signing requires environment variables: ${missing.joinToString(", ")}"
            )
        }
        val keystoreFile = file(env("TX5DR_ANDROID_KEYSTORE_FILE")!!)
        if (!keystoreFile.isFile) {
            throw GradleException("Release APK signing keystore does not exist: $keystoreFile")
        }
    }
}

tasks.matching {
    it.name == "assembleRelease" || it.name == "bundleRelease" ||
        (it.name.startsWith("package") && it.name.endsWith("Release"))
}.configureEach {
    dependsOn("checkReleaseSigningSecrets")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.apache.commons:commons-compress:1.26.2")
    testImplementation("junit:junit:4.13.2")
}
