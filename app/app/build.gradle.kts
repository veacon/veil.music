import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

// One release definition also supplies packaging, website metadata and delivery checks.
val releaseVersion = Properties().apply {
    rootProject.file("release-version.properties").inputStream().use { load(it) }
}

// Short git commit the build was cut from — shown next to the version and used by the beta updater to
// tell whether the rolling "dev" prerelease is a different build than the one running. Configuration-cache
// safe via providers.exec; falls back to "unknown" outside a git checkout (e.g. a source tarball).
val gitSha: String = try {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }.orElse("unknown").get().ifBlank { "unknown" }
} catch (_: Exception) {
    // git 不在 PATH 上（如临时诊断构建环境）：git 可执行文件缺失会抛进程启动异常，回退 unknown。
    "unknown"
}

android {
    namespace = "com.veacon.veilmusic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.veacon.veilmusic"
        minSdk = 23
        targetSdk = 35
        versionCode = releaseVersion.getProperty("versionCode").toInt()
        versionName = releaseVersion.getProperty("versionName")
        buildConfigField("String", "UPDATE_BASE_URL", "\"https://github.com/veacon/veil.music\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Set your proxy server IP here
        // Proxy server URL. Set `proxyBaseUrl` in local.properties (gitignored)
        // to your machine's LAN IP, e.g. http://192.168.1.50:3000/. Falls back
        // to the Android-emulator host loopback. Also overridable in the Dev menu.
        val proxyBaseUrl = localProps.getProperty("proxyBaseUrl") ?: "http://10.0.2.2:3000/"
        buildConfigField("String", "PROXY_BASE_URL", "\"$proxyBaseUrl\"")
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            // Continue the installed candidate's certificate so adb install -r preserves data.
            // The build type and debuggable flag remain Release regardless of the key's name.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        // lifecycle 2.8.6's checker is binary-incompatible with this Kotlin UAST.
        // This app uses StateFlow, not MutableLiveData; keep all other lint checks enabled.
        disable += "NullSafeMutableLiveData"
    }

    // Robolectric 默认走 Maven 解析，每次都会抢 ~/.robolectric-download-lock；
    // 并行 fork 时多个 JVM 同时创建这个锁文件，Windows 上会直接抛
    // "Couldn't create lock file" 让随机几个测试挂掉。改成离线模式指向本地扁平目录
    // （.robolectric-deps），完全不碰那把锁，也不再联网解析。可在配置阶段求值。
    val robolectricOfflineDir = System.getenv("ROBOLECTRIC_DEPENDENCY_DIR")
        ?: rootProject.layout.projectDirectory.dir(".robolectric-deps").asFile.absolutePath

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // 680 个 Robolectric 单测默认挤在 1 个测试 JVM 里串行跑，是构建耗时的大头
            // （串行 ~163s，4 个并行 fork 后 ~81s）。每个 fork 的 Robolectric 约需 1.2G，
            // 再往上加会和 Gradle/Kotlin 守护进程抢内存反而变慢。
            it.maxParallelForks = 4
            it.maxHeapSize = "1536m"
            // 固定中文环境：界面文案走 tr()，AppLanguage 未初始化时按 JVM 默认 Locale 解析。
            // 不固定的话，并行 fork / 单独跑某个测试类时会随机落到英文分支，标签断言就挂了。
            it.systemProperty("user.language", "zh")
            it.systemProperty("user.country", "CN")
            it.systemProperty("amtv.test.artifacts", project.layout.buildDirectory.dir("test-artifacts").get().asFile.absolutePath)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir", robolectricOfflineDir)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
    packaging {
        resources.excludes += setOf(
            "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
            "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
            "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties"
        )
    }
    kotlinOptions {
        jvmTarget = "17"
        // quickjs-kt 用 Kotlin 2.4 编译，而本项目用的是 2.1.20。
        // 其 JVM 签名/ABI 与 2.1 兼容，只跳过元数据版本闸门即可正常引用。
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    // tv-foundation 1.0.0 resolves Compose UI 1.10.3; keep device-test APIs aligned.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.10.3")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.10.3")

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

    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Media3
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // FFmpeg audio decoder extension, built from media3 1.4.1 source for armeabi-v7a.
    // Native lib lives in src/main/jniLibs; these are just the FfmpegAudioRenderer
    // classes. Gives on-device HE-AAC decoding that matches the proxy (kills chop).
    implementation(files("libs/media3-ffmpeg.jar"))

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.15.1")

    // QuickJS：端内执行落雪音源脚本（ES2020，含 async/await）。
    // 用 quickjs-kt 而不是 taoweiji/quickjs-android：后者不执行 Promise 微任务，
    // 脚本 `await lx.request(...)` 永远不会恢复（实测：HTTP 已 200 回包，bridge 仍超时）。
    // quickjs-kt 自带任务泵 + 中断超时，且支持 suspend 绑定（HTTP 直接作为 async binding）。
    implementation("io.github.dokar3:quickjs-kt-android:1.0.15")

    // Images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // QR 码（导入歌单弹窗 / 手机配置网页展示二维码）
    implementation("com.google.zxing:core:3.5.3")

    // Palette (artwork → accent color)
    implementation("androidx.palette:palette-ktx:1.0.0")

    // AppCompat (for theme)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // DI
    implementation("com.google.dagger:hilt-android:2.56")
    ksp("com.google.dagger:hilt-compiler:2.56")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
