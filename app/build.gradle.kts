plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tj.bikhon.iphonmuslim"
    compileSdk = 34

    defaultConfig {
        applicationId = "tj.bikhon.iphonmuslim"
        minSdk = 21
        targetSdk = 34
        versionCode = 11
        versionName = "3.5"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
    }

    // Калиди доимӣ — навсозиҳо бе нест кардани версияи кӯҳна насб мешаванд
    signingConfigs {
        create("shared") {
            storeFile = file("iphon.keystore")
            storePassword = "iphonmuslim"
            keyAlias = "iphon"
            keyPassword = "iphonmuslim"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("shared") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // ML Kit — модели ёфтани рӯй дар худи апп (бе интернет кор мекунад)
    implementation("com.google.mlkit:face-detection:16.1.7")
    // шакли бадан — барои намуди «танҳо ҷои зан»
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    // модели муайян кардани зан/мард (InsightFace genderage, ONNX)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")
}
