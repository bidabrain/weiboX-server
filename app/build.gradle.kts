plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

// 版本号默认用下面的值；CI 可用 -PweiboxVersionCode / -PweiboxVersionName 覆盖，本地构建行为不变。
val weiboxVersionCode = (project.findProperty("weiboxVersionCode") as String?)?.toInt() ?: 2
val weiboxVersionName = (project.findProperty("weiboxVersionName") as String?) ?: "1.1"

android {
    namespace = "com.weibox.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.weibox.app"
        minSdk = 26
        targetSdk = 34
        versionCode = weiboxVersionCode
        versionName = weiboxVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // 固定的 debug keystore（随仓库提交）。有它才能保证每次构建——不管本地还是 CI——
    // 签名完全一致，新包可以直接覆盖升级安装。文件不存在时回落到 AGP 默认的
    // ~/.android/debug.keystore（每台机器各不相同，跨机器装不上）。
    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file("app/debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 用 debug 签名给 release 包签名（仅自用 / 侧载；不能上架 Google Play）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation("org.jsoup:jsoup:1.18.1")
    implementation(libs.lifecycle.process)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    kapt(libs.hilt.ext.compiler)
}
