pluginManagement {
    repositories {
        mavenCentral()
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        gradlePluginPortal()
    }
    resolutionStrategy { eachPlugin { if (requested.id.id == "com.chaquo.python") useModule("com.chaquo.python:gradle:${requested.version}") } }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
    }
}
rootProject.name = "OAS Android"
include(":app")
