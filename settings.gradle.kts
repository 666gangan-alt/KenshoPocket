pluginManagement { repositories {
    System.getenv("KENSHO_MAVEN_REPO")?.let { maven { url = uri(it) } }
    google(); mavenCentral(); gradlePluginPortal()
} }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        System.getenv("KENSHO_MAVEN_REPO")?.let { maven { url = uri(it) } }
        google(); mavenCentral()
    }
}
rootProject.name = "KenshoPocket"
include(":app")
