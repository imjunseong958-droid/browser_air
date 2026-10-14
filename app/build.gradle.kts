plugins { id("com.android.application") }

val airVersionCode = providers.environmentVariable("AIR_VERSION_CODE")
    .orElse(providers.gradleProperty("airVersionCode")).get().toIntOrNull()
require(airVersionCode != null && airVersionCode in 1..2_100_000_000) {
    "AIR_VERSION_CODE / airVersionCode must be a positive integer at most 2100000000."
}
val airBaseVersionName = providers.gradleProperty("airVersionName").get()
val airVersionName = if (providers.environmentVariable("AIR_VERSION_CODE").isPresent)
    "$airBaseVersionName+$airVersionCode" else airBaseVersionName
require(airVersionName.matches(Regex("[0-9A-Za-z.+_-]{1,64}"))) { "Invalid AIR version name." }

val airSigning = listOf("AIR_KEYSTORE_PATH", "AIR_KEYSTORE_PASSWORD", "AIR_KEY_ALIAS", "AIR_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }
val airSigningConfigured = airSigning.values.all { !it.isNullOrBlank() }
require(airSigningConfigured || airSigning.values.all { it.isNullOrBlank() }) {
    "Release signing requires AIR_KEYSTORE_PATH, AIR_KEYSTORE_PASSWORD, AIR_KEY_ALIAS and AIR_KEY_PASSWORD."
}

android {
    namespace = "dev.air.browser"
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "dev.air.browser"
        minSdk = 29
        targetSdk = 37
        versionCode = airVersionCode
        versionName = airVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".tools/debug.keystore")
        }
        if (airSigningConfigured) {
            create("airRelease") {
                storeFile = rootProject.file(airSigning.getValue("AIR_KEYSTORE_PATH")!!)
                storePassword = airSigning.getValue("AIR_KEYSTORE_PASSWORD")
                keyAlias = airSigning.getValue("AIR_KEY_ALIAS")
                keyPassword = airSigning.getValue("AIR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("airRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { viewBinding = true; buildConfig = true }
    lint { abortOnError = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":navigation"))
    implementation(project(":updates"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.browser:browser:1.10.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.mozilla.geckoview:geckoview:158.0.20261007201254")
    implementation("com.google.android.gms:play-services-auth-api-phone:18.3.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
