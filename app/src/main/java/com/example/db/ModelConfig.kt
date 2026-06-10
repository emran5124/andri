package com.example.db

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ModelConfig(
    val code: String,
    val title: String
)
