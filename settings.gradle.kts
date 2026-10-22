rootProject.name = "Nuvio"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // mpvlibAndroid (the libmpv build used by mpvRx) is published only as GitHub release assets.
        exclusiveContent {
            forRepository {
                ivy {
                    name = "mpvlibGitHubReleases"
                    url = uri("https://github.com/Riteshp2001/mpvlibAndroid/releases/download")
                    patternLayout {
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("app.gyrolet.mpvlib")
            }
        }
    }
}

include(":composeApp")
include(":androidApp")
