plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Short git commit the build was cut from — shown next to the version and used by the beta updater to
// tell whether the rolling "dev" prerelease is a different build than the one running. Configuration-cache
// safe via providers.exec; falls back to "unknown" outside a git checkout (e.g. a source tarball).
val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }.orElse("unknown").get().ifBlank { "unknown" }

android {
    namespace = "com.veil.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.veil.music"
        // v1.1 起明确放弃 32 位设备与 Android 8.0 以下系统：只支持
        // 64 位 arm64-v8a + Android 8.0（API 26）及以上。过滤掉依赖 AAR 自带的
        // armeabi-v7a / x86 / x86_64 版本 QuickJS，只打一份 arm64-v8a native 库。
        // 产物是单一 app-debug.apk（CI 与更新器都按这个名字找包）。
        minSdk = 26
        targetSdk = 35
        // versionCode 必须单调递增（覆盖安装时系统会比对），所以对外叫 1.1.1 之后它继续往上走，
        // 否则从旧版本升级会直接报 INSTALL_FAILED_VERSION_DOWNGRADE。
        versionCode = 21
        versionName = "1.1.1"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        // 只跑 NewApi：Compose 自带的检测器在 Kotlin 2.1 的 analysis API 上会抛
        // IncompatibleClassChangeError，把整个分析带崩（lint 侧兼容问题，非代码缺陷）。
        // NewApi 正是本项目要守住的底线 —— minSdk 26 / Android 8.0 兼容。
        checkOnly += "NewApi"
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // TV
    implementation("androidx.tv:tv-foundation:1.0.0")
    implementation("androidx.tv:tv-material:1.0.0")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Media3
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // LX 音源：脚本在 QuickJS 里跑（lx-music v4 协议）
    implementation("wang.harlon.quickjs:wrapper-android:3.2.3")
    // 音源模型 / 脚本清单的 JSON 持久化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Network —— 只剩 OkHttp：目录接口与脚本桥都直接用它，Retrofit/Moshi 已随
    // Apple 的数据层一起移除（音源响应格式各平台差异极大，手写解析比声明式更省事）。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 手机管理页的局域网 HTTP 服务：自带 multipart 表单/文件上传解析（脚本 .js 上传、
    // 热搜推送等手机端输入都靠它），复刻手机助手。
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Palette (artwork → accent color)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // AppCompat (for theme)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // DI
    implementation("com.google.dagger:hilt-android:2.56")
    ksp("com.google.dagger:hilt-compiler:2.56")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
