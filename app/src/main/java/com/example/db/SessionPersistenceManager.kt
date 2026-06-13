package com.example.db

import kotlinx.coroutines.flow.Flow

class SessionPersistenceManager(private val db: AppDatabase) {

    private val activeSessionDao = db.activeSessionDao()
    private val historyLogsDao = db.historyLogsDao()
    private val errorLogDao = db.errorLogDao()

    fun getActiveSessionFlow(): Flow<ActiveSession?> = activeSessionDao.getActiveSessionFlow()

    suspend fun getActiveSession(): ActiveSession? = activeSessionDao.getActiveSession()

    suspend fun insertActiveSession(session: ActiveSession) {
        activeSessionDao.insertActiveSession(session)
    }

    suspend fun deleteActiveSession() {
        activeSessionDao.deleteActiveSession()
    }

    suspend fun insertHistoryLog(log: HistoryLog) {
        historyLogsDao.insertHistoryLog(log)
    }

    suspend fun clearHistory() {
        historyLogsDao.clearHistory()
    }

    suspend fun logError(message: String, details: String) {
        errorLogDao.insertErrorLog(
            ErrorLog(
                timestamp = System.currentTimeMillis(),
                errorMessage = message,
                details = details
            )
        )
    }

    suspend fun clearErrorLogs() {
        errorLogDao.clearErrorLogs()
    }
}
