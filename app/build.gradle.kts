import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        localFile.inputStream().use { load(it) }
    }
}

// TODO: Move API keys behind a server-side proxy before any real distribution.
fun escapeBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val anthropicApiKey: String = localProperties.getProperty("ANTHROPIC_API_KEY") ?: ""
val anthropicModel: String =
    localProperties.getProperty("ANTHROPIC_MODEL") ?: "claude-sonnet-4-20250514"

val openAiApiKey: String = localProperties.getProperty("OPENAI_API_KEY") ?: ""
val openAiModel: String =
    localProperties.getProperty("OPENAI_MODEL") ?: "gpt-4o-mini"

val openRouterApiKey: String = localProperties.getProperty("OPENROUTER_API_KEY") ?: ""
val openRouterModel: String =
    localProperties.getProperty("OPENROUTER_MODEL") ?: "google/gemini-2.5-flash"

val geminiApiKey: String = localProperties.getProperty("GEMINI_API_KEY") ?: ""
val geminiModel: String =
    localProperties.getProperty("GEMINI_MODEL") ?: "gemini-3.5-flash"

val localLlmBaseUrl: String = localProperties.getProperty("LOCAL_LLM_BASE_URL") ?: ""
val localLlmModel: String =
    localProperties.getProperty("LOCAL_LLM_MODEL") ?: "llama3.2"
val localLlmApiKey: String = localProperties.getProperty("LOCAL_LLM_API_KEY") ?: ""

val llmProvider: String =
    localProperties.getProperty("LLM_PROVIDER") ?: "auto"

// Cheap, fast model used to rewrite the user's request before the agent loop runs.
val fastModel: String =
    localProperties.getProperty("LLM_FAST_MODEL") ?: "gemini-3.5-flash"

val llmMaxTokens: Int =
    localProperties.getProperty("LLM_MAX_TOKENS")?.toIntOrNull() ?: 1024
val agentMaxSteps: Int =
    localProperties.getProperty("AGENT_MAX_STEPS")?.toIntOrNull() ?: 20

android {
    namespace = "com.drishti"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.drishti"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "ANTHROPIC_API_KEY", "\"${escapeBuildConfig(anthropicApiKey)}\"")
        buildConfigField("String", "ANTHROPIC_MODEL", "\"${escapeBuildConfig(anthropicModel)}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${escapeBuildConfig(openAiApiKey)}\"")
        buildConfigField("String", "OPENAI_MODEL", "\"${escapeBuildConfig(openAiModel)}\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"${escapeBuildConfig(openRouterApiKey)}\"")
        buildConfigField("String", "OPENROUTER_MODEL", "\"${escapeBuildConfig(openRouterModel)}\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"${escapeBuildConfig(geminiApiKey)}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${escapeBuildConfig(geminiModel)}\"")
        buildConfigField("String", "LOCAL_LLM_BASE_URL", "\"${escapeBuildConfig(localLlmBaseUrl)}\"")
        buildConfigField("String", "LOCAL_LLM_MODEL", "\"${escapeBuildConfig(localLlmModel)}\"")
        buildConfigField("String", "LOCAL_LLM_API_KEY", "\"${escapeBuildConfig(localLlmApiKey)}\"")
        buildConfigField("String", "LLM_PROVIDER", "\"${escapeBuildConfig(llmProvider)}\"")
        buildConfigField("String", "LLM_FAST_MODEL", "\"${escapeBuildConfig(fastModel)}\"")
        buildConfigField("int", "LLM_MAX_TOKENS", "$llmMaxTokens")
        buildConfigField("int", "AGENT_MAX_STEPS", "$agentMaxSteps")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
