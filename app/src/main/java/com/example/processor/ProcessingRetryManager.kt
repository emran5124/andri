package com.example.processor

import com.example.db.AppSettings

sealed class FallbackResult {
    object NextModel : FallbackResult()
    object NextKey : FallbackResult()
    object Exhausted : FallbackResult()
    object WaitManualDecision : FallbackResult()
}

class ProcessingRetryManager {

    var activeKeyIndex: Int = 0
        private set
    var activeModelIndex: Int = 0
        private set
    var currentRetryCount: Int = 0
        private set

    fun resetRetryCount() {
        currentRetryCount = 0
    }

    fun resetPointers() {
        activeKeyIndex = 0
        activeModelIndex = 0
        currentRetryCount = 0
    }

    fun setPointers(keyIdx: Int, modelIdx: Int) {
        activeKeyIndex = keyIdx
        activeModelIndex = modelIdx
        currentRetryCount = 0
    }

    fun incrementRetry() {
        currentRetryCount++
    }

    fun incrementKeyIndex() {
        activeKeyIndex++
    }

    fun incrementModelIndex() {
        activeModelIndex++
    }

    fun resetModelIndex() {
        activeModelIndex = 0
    }

    fun canRetry(settings: AppSettings): Boolean {
        return currentRetryCount <= settings.retryAttemptsLimit
    }

    fun getRetryDelaySeconds(statusCode: Int, settings: AppSettings): Int {
        return if (statusCode == 503) {
            settings.overloadDelaySeconds
        } else {
            settings.errorDelaySeconds
        }
    }

    /**
     * Determines which fallback path to take when all retries are exhausted.
     */
    fun onRetryExhausted(
        settings: AppSettings,
        totalKeys: Int,
        totalModelsForCurrentKey: Int
    ): FallbackResult {
        if (settings.autoSwitchOnLimit) {
            return if (activeModelIndex + 1 < totalModelsForCurrentKey) {
                activeModelIndex++
                currentRetryCount = 0
                FallbackResult.NextModel
            } else if (activeKeyIndex + 1 < totalKeys) {
                activeKeyIndex++
                activeModelIndex = 0
                currentRetryCount = 0
                FallbackResult.NextKey
            } else {
                FallbackResult.Exhausted
            }
        } else {
            return FallbackResult.WaitManualDecision
        }
    }
}
