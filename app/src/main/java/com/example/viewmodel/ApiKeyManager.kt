package com.example.viewmodel

import com.example.api.JsonSerializer
import com.example.db.ApiKeyConfig
import com.example.db.AppDatabase
import com.example.db.ModelConfig

class ApiKeyManager(private val db: AppDatabase) {

    private val apiKeyDao = db.apiKeyDao()

    fun getAllApiKeysFlow() = apiKeyDao.getAllApiKeysFlow()

    suspend fun getAllApiKeys(): List<ApiKeyConfig> = apiKeyDao.getAllApiKeys()

    suspend fun addApiKey(title: String, apiKey: String, models: List<ModelConfig>) {
        val keys = apiKeyDao.getAllApiKeys()
        val nextOrder = (keys.maxByOrNull { it.priorityOrder }?.priorityOrder ?: 0) + 1
        val jsonModels = JsonSerializer.serializeModels(models)
        apiKeyDao.insertApiKey(
            ApiKeyConfig(
                title = title,
                apiKey = apiKey,
                priorityOrder = nextOrder,
                modelsJson = jsonModels
            )
        )
    }

    suspend fun updateApiKey(id: Int, title: String, apiKey: String, models: List<ModelConfig>, priorityOrder: Int) {
        val jsonModels = JsonSerializer.serializeModels(models)
        apiKeyDao.updateApiKey(
            ApiKeyConfig(
                id = id,
                title = title,
                apiKey = apiKey,
                priorityOrder = priorityOrder,
                modelsJson = jsonModels
            )
        )
    }

    suspend fun deleteApiKey(id: Int) {
        apiKeyDao.deleteApiKeyById(id)
    }

    suspend fun moveApiKeyUp(key: ApiKeyConfig) {
        val keys = apiKeyDao.getAllApiKeys()
        val currentIndex = keys.indexOfFirst { it.id == key.id }
        if (currentIndex > 0) {
            val prevKey = keys[currentIndex - 1]
            val updatedPrevOrder = key.priorityOrder
            val updatedCurrentOrder = prevKey.priorityOrder
            apiKeyDao.insertApiKey(key.copy(priorityOrder = updatedCurrentOrder))
            apiKeyDao.insertApiKey(prevKey.copy(priorityOrder = updatedPrevOrder))
        }
    }

    suspend fun moveApiKeyDown(key: ApiKeyConfig) {
        val keys = apiKeyDao.getAllApiKeys()
        val currentIndex = keys.indexOfFirst { it.id == key.id }
        if (currentIndex != -1 && currentIndex < keys.size - 1) {
            val nextKey = keys[currentIndex + 1]
            val updatedNextOrder = key.priorityOrder
            val updatedCurrentOrder = nextKey.priorityOrder
            apiKeyDao.insertApiKey(key.copy(priorityOrder = updatedCurrentOrder))
            apiKeyDao.insertApiKey(nextKey.copy(priorityOrder = updatedNextOrder))
        }
    }
}
