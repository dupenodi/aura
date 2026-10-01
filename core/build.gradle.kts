import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnit()
    // Live evals hit real model APIs; they run through the `eval` task, never in `test`.
    exclude("**/LiveEval*")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

/**
 * Runs the guide agent against the simulated phone with a real model and prints a scorecard.
 *
 *   OPENROUTER_API_KEY=… ./gradlew :core:eval
 *   ./gradlew :core:eval --args="--models google/gemini-2.5-flash,anthropic/claude-haiku-4.5 --runs 3"
 */
tasks.register<JavaExec>("eval") {
    group = "verification"
    description = "Live agent eval against the simulated phone (needs OPENROUTER_API_KEY)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.drishti.core.eval.LiveEvalKt")
    workingDir = rootProject.projectDir
    // Keys can also live in local.properties, the same place the app reads them from.
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    listOf("OPENROUTER_API_KEY", "SARVAM_API_KEY", "OPENROUTER_MODEL", "OPENROUTER_SMART_MODEL")
        .forEach { k -> (System.getenv(k) ?: local.getProperty(k))?.let { environment(k, it) } }
}
