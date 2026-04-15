plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias (libs.plugins.ksp)
}

configurations.configureEach {
    exclude(group = "com.intellij", module = "annotations")
}

configurations.all {
    resolutionStrategy {
        // 强制将所有 com.intellij:annotations 替换为官方新版
        force("org.jetbrains:annotations:23.0.0")
    }
}

android {
    namespace = "com.angh.audioonly"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.angh.audioonly"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 指定 Room Schema 导出目录
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "$projectDir/schemas"
                )
            }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 核心播放器
    implementation(libs.androidx.media3.exoplayer)
    // UI 组件
    implementation(libs.androidx.media3.ui)
    // 会话管理 (用于通知栏和锁屏控制)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    // Guava 用于 Media3 的 Future 支持
    implementation(libs.guava) {
        exclude(group = "com.intellij", module = "annotations")
    }
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.okhttp)
    implementation(libs.jsoup) {
        exclude(group = "com.intellij", module = "annotations")
    }
    //json serialization
    implementation(libs.kotlinx.serialization.json)

    // 添加 Room 依赖
    implementation(libs.androidx.room.runtime)
    // 使用 KSP 处理器
    ksp(libs.androidx.room.compiler)
    // 添加 KTX 扩展以支持协程
    implementation(libs.androidx.room.ktx)

    //viewmodel
    implementation(libs.lifecycle.viewmodel.ktx)
}