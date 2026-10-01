// Plugins go on the root classpath through buildscript (not a plugins block) so the Android
// Gradle Plugin is only resolved when :app is part of the build. Kotlin's Android plugin must
// share a classloader with AGP, so both live here rather than in the subprojects.
buildscript {
    val kotlinVersion = "2.0.21"
    val androidEnabled = gradle.extra["auraAndroid"] as Boolean
    repositories {
        if (androidEnabled) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        if (androidEnabled) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        }
    }
}
