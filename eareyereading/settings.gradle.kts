pluginManagement {
    // 顺序敏感：GitHub Actions runner 上 Aliyun 镜像的插件元数据解析会失败
    // （KSP 插件 marker 无法解析，历史 CI 全挂的根因）。
    // 全球官方源前置保证 CI 可用，Aliyun 留在末尾作为国内开发的加速兜底
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    }
}

rootProject.name = "EareyeReading"
include(":app")
