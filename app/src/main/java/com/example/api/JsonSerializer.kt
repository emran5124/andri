package com.example.api

import com.example.db.ModelConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object JsonSerializer {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    fun serializeModels(models: List<ModelConfig>): String {
        val type = Types.newParameterizedType(List::class.java, ModelConfig::class.java)
        val adapter = moshi.adapter<List<ModelConfig>>(type)
        return adapter.toJson(models)
    }

    fun deserializeModels(json: String): List<ModelConfig> {
        if (json.isBlank()) return emptyList()
        return try {
            val type = Types.newParameterizedType(List::class.java, ModelConfig::class.java)
            val adapter = moshi.adapter<List<ModelConfig>>(type)
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializeStrings(strings: List<String>): String {
        val type = Types.newParameterizedType(List::class.java, String::class.java)
        val adapter = moshi.adapter<List<String>>(type)
        return adapter.toJson(strings)
    }

    fun deserializeStrings(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val type = Types.newParameterizedType(List::class.java, String::class.java)
            val adapter = moshi.adapter<List<String>>(type)
            adapter.fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun serializeBackup(apiKeys: List<com.example.db.ApiKeyConfig>, prompts: List<com.example.db.PromptTemplate>): String {
        return try {
            val backupKeys = apiKeys.map { BackupApiKey(it.title, it.apiKey, it.priorityOrder, it.modelsJson) }
            val backupPrompts = prompts.map { BackupPrompt(it.title, it.promptContent, it.priorityOrder) }
            val backupData = BackupData(backupKeys, backupPrompts)
            val adapter = moshi.adapter(BackupData::class.java)
            adapter.toJson(backupData)
        } catch (e: Exception) {
            ""
        }
    }

    fun deserializeBackup(json: String): BackupData? {
        if (json.isBlank()) return null
        return try {
            val adapter = moshi.adapter(BackupData::class.java)
            adapter.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class BackupApiKey(
    val title: String,
    val apiKey: String,
    val priorityOrder: Int,
    val modelsJson: String
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class BackupPrompt(
    val title: String,
    val promptContent: String,
    val priorityOrder: Int
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class BackupData(
    val apiKeys: List<BackupApiKey>?,
    val prompts: List<BackupPrompt>?
)
