package com.example.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "api_keys")
data class ApiKeyConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val apiKey: String,
    val priorityOrder: Int,
    val modelsJson: String // Saved as JSON serialized list of ModelConfig
)

@Entity(tableName = "prompt_templates")
data class PromptTemplate(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val promptContent: String
)

@Entity(tableName = "active_session")
data class ActiveSession(
    @PrimaryKey val id: Int = 1, // Always 1 for singleton
    val originalFileName: String,
    val outputFileName: String,
    val separator: String,
    val totalSections: Int,
    val currentSectionIndex: Int,
    val rawSectionsJson: String, // List of original sections
    val accumulatedSummariesJson: String, // List of summaries generated so far
    val isCompleted: Boolean = false,
    val fileUriString: String // Store selected file URI so we can load it on restore
)

@Entity(tableName = "history_logs")
data class HistoryLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String,
    val sectionsCount: Int,
    val savedPath: String,
    val savedHtmlPath: String = "",
    val savedTextUri: String = "",
    val savedHtmlUri: String = "",
    val summaryContent: String
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1, // Singleton
    val successDelaySeconds: Int = 10,
    val errorDelaySeconds: Int = 10,
    val overloadDelaySeconds: Int = 30, // 503 error
    val retryAttemptsLimit: Int = 3, // retry limit before asking or switching
    val autoSwitchOnLimit: Boolean = false, // false = ask user (default), true = auto switch
    val customSeparator: String = "-----",
    val compiledSeparatorTemplate: String = "=========\nsection {index}/{total}\n=========\n{summary}"
)

@Entity(tableName = "error_logs")
data class ErrorLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val errorMessage: String,
    val details: String
)
