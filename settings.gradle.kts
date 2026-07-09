pluginManagement {
    repositories {
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
        }
        maven {
            name = "AliyunCentral"
            url = uri("https://maven.aliyun.com/repository/central")
        }
        maven {
            name = "AliyunGradlePlugin"
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "AliyunGoogle"
            url = uri("https://maven.aliyun.com/repository/google")
        }
        maven {
            name = "AliyunCentral"
            url = uri("https://maven.aliyun.com/repository/central")
        }
        maven {
            name = "AliyunPublic"
            url = uri("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "RocoCatcher"
include(":app")
