pluginManagement {
    repositories {
        val user = System.getenv("ARTIFACTORY_USER")
        val password = System.getenv("ARTIFACTORY_PASSWORD")
        if (!user.isNullOrBlank() && !password.isNullOrBlank()) {
            maven {
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val artifactoryUser = System.getenv("ARTIFACTORY_USER")
val artifactoryPassword = System.getenv("ARTIFACTORY_PASSWORD")
if (!artifactoryUser.isNullOrBlank() && !artifactoryPassword.isNullOrBlank()) {
    gradle.extra["testRepositoryUser"] = artifactoryUser
    gradle.extra["testRepositoryPassword"] = artifactoryPassword
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!artifactoryUser.isNullOrBlank() && !artifactoryPassword.isNullOrBlank()) {
            maven {
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = artifactoryUser
                    password = artifactoryPassword
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "weartak-plugin-nearby-drop"
include(":protocol", ":app")
