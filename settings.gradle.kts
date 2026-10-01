pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Only Android artifacts come from Google's repo, so a build without access to it
        // (the agent sandbox) still resolves :core from Maven Central.
        google {
            content {
                includeGroupByRegex("androidx\\..*")
                includeGroupByRegex("com\\.android\\..*")
                includeGroupByRegex("com\\.google\\.android\\..*")
                includeGroup("com.google.testing.platform")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Drishti"

include(":core")

// The Android app needs an SDK; everything that decides what Aura does lives in :core and
// builds on a plain JVM. Without an SDK (CI containers, the agent sandbox) only :core is
// configured, so its tests and evals still run. Force either way with -Paura.android=true|false.
val androidEnabled: Boolean = run {
    providers.gradleProperty("aura.android").orNull?.let { return@run it.toBoolean() }
    val local = java.util.Properties().apply {
        val f = file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val sdk = local.getProperty("sdk.dir")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    sdk != null && file(sdk).isDirectory
}
gradle.extra["auraAndroid"] = androidEnabled
if (androidEnabled) include(":app")
