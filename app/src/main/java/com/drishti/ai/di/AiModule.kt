package com.drishti.ai.di

import com.drishti.BuildConfig
import com.drishti.ai.AnthropicApi
import com.drishti.ai.AnthropicClient
import com.drishti.ai.OpenAiApi
import com.drishti.ai.OpenAiClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideJson(): Json = OpenAiClient.createJson()

    @Provides
    @Singleton
    fun provideOpenAiApi(json: Json): OpenAiApi =
        OpenAiClient.createApi(
            OpenAiClient.createOkHttp(BuildConfig.OPENAI_API_KEY),
            json,
        )

    @Provides
    @Singleton
    fun provideAnthropicApi(json: Json): AnthropicApi =
        AnthropicClient.createApi(
            AnthropicClient.createOkHttp(BuildConfig.ANTHROPIC_API_KEY),
            json,
        )
}
