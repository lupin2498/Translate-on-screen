plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dichmanhinh.screentranslator"
    compileSdk = 34
    // NDK dựng llama.cpp (AI trên máy). Trên GitHub Actions dùng bản NDK mới nhất có sẵn.
    ndkVersion = System.getenv("ANDROID_NDK_LATEST_HOME")?.let { File(it).name } ?: "27.3.13750724"

    defaultConfig {
        applicationId = "com.dichmanhinh.screentranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // Kiểm thử E2E trên emulator x86_64: ./gradlew -Pe2e=true assembleDebug assembleDebugAndroidTest
            if (project.findProperty("e2e") == "true") abiFilters += "x86_64"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_shared")
                // Dùng mã nguồn llama.cpp có sẵn trên máy thay vì tải về: -PllamaSrc=/duong/dan/llama.cpp
                (project.findProperty("llamaSrc") as String?)?.let { arguments += "-DLLAMA_SRC_DIR=$it" }
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "dichmanhinh"
            keyAlias = "dichmanhinh"
            keyPassword = "dichmanhinh"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = false
    }
    packaging {
        // Nén thư viện native (ML Kit, Tesseract, llama.cpp) trong APK: bộ cài nhỏ hơn ~40%, dễ gửi và
        // tải. Thư viện được giải nén ra nativeLibraryDir – llama.cpp nạp các biến thể CPU từ đó.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Nhận dạng chữ (OCR) trên thiết bị - ML Kit
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    // Nhận diện ngôn ngữ từng đoạn chữ (mô hình kèm sẵn, chạy offline)
    implementation("com.google.mlkit:language-id:17.0.6")
    // OCR cho các bộ chữ ML Kit không hỗ trợ (dữ liệu ngôn ngữ tải khi cần)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
