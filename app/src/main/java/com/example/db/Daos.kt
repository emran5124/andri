package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiKeyDao {
    @Query("SELECT * FROM api_keys ORDER BY priorityOrder ASC")
    fun getAllApiKeysFlow(): Flow<List<ApiKeyConfig>>

    @Query("SELECT * FROM api_keys ORDER BY priorityOrder ASC")
    suspend fun getAllApiKeys(): List<ApiKeyConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApiKey(config: ApiKeyConfig)

    @Update
    suspend fun updateApiKey(config: ApiKeyConfig)

    @Query("DELETE FROM api_keys WHERE id = :id")
    suspend fun deleteApiKeyById(id: Int)
}

@Dao
interface PromptTemplateDao {
    @Query("SELECT * FROM prompt_templates ORDER BY id DESC")
    fun getAllTemplatesFlow(): Flow<List<PromptTemplate>>

    @Query("SELECT * FROM prompt_templates ORDER BY id DESC")
    suspend fun getAllTemplates(): List<PromptTemplate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: PromptTemplate)

    @Update
    suspend fun updateTemplate(template: PromptTemplate)

    @Query("DELETE FROM prompt_templates WHERE id = :id")
    suspend fun deleteTemplateById(id: Int)
}

@Dao
interface ActiveSessionDao {
    @Query("SELECT * FROM active_session WHERE id = 1 LIMIT 1")
    suspend fun getActiveSession(): ActiveSession?

    @Query("SELECT * FROM active_session WHERE id = 1 LIMIT 1")
    fun getActiveSessionFlow(): Flow<ActiveSession?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActiveSession(session: ActiveSession)

    @Query("DELETE FROM active_session WHERE id = 1")
    suspend fun deleteActiveSession()
}

@Dao
interface HistoryLogsDao {
    @Query("SELECT * FROM history_logs ORDER BY timestamp DESC")
    fun getAllHistoryFlow(): Flow<List<HistoryLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryLog(log: HistoryLog)

    @Query("DELETE FROM history_logs")
    suspend fun clearHistory()
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): AppSettings?

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<AppSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSettings(settings: AppSettings)
}

@Dao
interface ErrorLogDao {
    @Query("SELECT * FROM error_logs ORDER BY timestamp DESC")
    fun getAllErrorLogsFlow(): Flow<List<ErrorLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertErrorLog(log: ErrorLog)

    @Query("DELETE FROM error_logs")
    suspend fun clearErrorLogs()
}
