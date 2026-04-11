import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties()
localProps.load(FileInputStream(rootProject.file("local.properties")))

android {
    namespace = "com.example.networkshare"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.networkshare"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ADMOB_BANNER_ID", "\"${localProps["ADMOB_BANNER_ID"]}\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${localProps["ADMOB_INTERSTITIAL_ID"]}\"")
        manifestPlaceholders["ADMOB_APP_ID"] = localProps["ADMOB_APP_ID"] as String
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation(libs.androidx.compose.foundation)
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.compose.material3.lint)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.foundation)
    implementation(libs.androidx.ui)
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    testImplementation(libs.junit)
    implementation("androidx.browser:browser:1.8.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}