import java.util.Properties
import java.io.FileInputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties()
localProps.load(FileInputStream(rootProject.file("local.properties")))

android {
    namespace = "com.danieleze.networkshare"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.danieleze.networkshare"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ADMOB_BANNER_ID", "\"${localProps["ADMOB_BANNER_ID"]}\"")
        buildConfigField(
            "String",
            "ADMOB_INTERSTITIAL_ID",
            "\"${localProps["ADMOB_INTERSTITIAL_ID"]}\""
        )
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    //noinspection UseTomlInstead,GradleDependency
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    //noinspection UseTomlInstead,GradleDependency
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    //noinspection UseTomlInstead,GradleDependency
    implementation("androidx.core:core-ktx:1.17.0")
    //noinspection UseTomlInstead
    implementation("io.github.webdanlib:webdav:0.9.0-SNAPSHOT")
    implementation(libs.androidx.compose.foundation)
    //noinspection UseTomlInstead
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.compose.material3.lint)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.foundation)
    implementation(libs.androidx.ui)
    //noinspection UseTomlInstead
    implementation("com.jaredrummler:android-device-names:2.1.1")
    //noinspection UseTomlInstead,GradleDependency
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation(libs.androidx.compose.foundation.foundation2)
    testImplementation(libs.junit)
    //noinspection UseTomlInstead,GradleDependency
    implementation("androidx.browser:browser:1.8.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
