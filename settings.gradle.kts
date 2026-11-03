pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor se publica vía JitPack (com.github.TeamNewPipe:NewPipeExtractor).
        maven {
            url = uri("https://jitpack.io")
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "app"
include(":app")
