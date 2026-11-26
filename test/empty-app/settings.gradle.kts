// A test app for the README's Performance table: the same window and card as T9 App Launcher, and nothing else.
// Build: ./gradlew -p test/empty-app assembleRelease
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "t9-empty"
