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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenMES"

include(":app")
include(":core:designsystem")
include(":core:common")
include(":core:model")
include(":core:network")
include(":core:data")
include(":feature:auth")
include(":feature:schedule")
include(":feature:marks")
include(":feature:homework")
include(":feature:more")
