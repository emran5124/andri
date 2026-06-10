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
}
