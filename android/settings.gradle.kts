pluginManagement {
    repositories {
        // 国内镜像优先：首次构建要拉 AGP / Kotlin / Hilt 几百 MB，直连 repo1 只有 ~0.7MB/s。
        // 镜像缺包时会自动回落到下面的官方源，删掉这四行不影响可构建性。
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}
rootProject.name = "VeilMusic"
include(":app")
