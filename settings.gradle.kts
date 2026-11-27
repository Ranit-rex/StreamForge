pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories { google(); maven { url = uri("https://repo1.maven.org/maven2") }; mavenCentral(); maven { url = uri("https://jitpack.io") } }
}
rootProject.name = "CastDoo"
include(":android", ":admin", ":beta")
