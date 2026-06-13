package com.example.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiCommunicator {
    suspend fun callGeminiApi(
        apiKey: String,
        modelCode: String,
        sectionText: String,
        customPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val prompt = buildString {
            append(customPrompt)
            append("\n\n--- متن بخش از سند ---\n")
            append(sectionText)
            append("\n--- انتهای متن بخش ---")
        }

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt))))
            )
            val response = RetrofitClient.service.generateContent(
                model = modelCode,
                apiKey = apiKey,
                request = request
            )
            val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (text != null) {
                Result.success(text)
            } else {
                Result.failure(Exception("بدنه خروجی ارسال شده در API فاقد متن است."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
