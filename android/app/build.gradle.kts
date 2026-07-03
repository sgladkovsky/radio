plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sgladkovsky.radio"
    compileSdk = 34
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "com.sgladkovsky.radio"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.1"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        jniLibs {
            // Extract on install — reliable on devices like Xiaomi Mi 9T (4 KB pages).
            useLegacyPackaging = true
        }
    }
}

tasks.register<Exec>("alignNativeLibs16K") {
    group = "build"
    description = "Realign arm64 native libraries to 16 KB ELF segments"
    commandLine("python3", "${rootProject.projectDir}/scripts/align_native_libs.py")
}

tasks.named("preBuild") {
    dependsOn("alignNativeLibs16K")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
}
