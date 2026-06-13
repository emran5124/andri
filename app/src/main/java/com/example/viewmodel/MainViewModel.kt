package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.GeminiCommunicator
import com.example.api.JsonSerializer
import com.example.db.ActiveSession
import com.example.db.ApiKeyConfig
import com.example.db.AppDatabase
import com.example.db.AppSettings
import com.example.db.HistoryLog
import com.example.db.ErrorLog
import com.example.db.ModelConfig
import com.example.db.PromptTemplate
import com.example.db.SessionPersistenceManager
import com.example.export.HtmlExporter
import com.example.export.TxtExporter
import com.example.processor.ChunkProcessor
import com.example.processor.ProcessingRetryManager
import com.example.processor.FallbackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException

sealed interface ProcessingState {
    object Idle : ProcessingState
    data class Loading(val message: String) : ProcessingState
    data class Success(
        val summary: String,
        val savedPath: String,
        val savedHtmlPath: String = "",
        val textFileUri: String = "",
        val htmlFileUri: String = "",
        val htmlContent: String = ""
    ) : ProcessingState
    data class Error(val error: String) : ProcessingState
    
    data class Running(
        val originalFileName: String,
        val currentSection: Int,
        val totalSections: Int,
        val retriesLeft: Int,
        val activeKeyTitle: String,
        val activeModelTitle: String,
        val statusMessage: String
    ) : ProcessingState
    
    data class WaitingForUserDecision(
        val sectionIndex: Int,
        val errorMsg: String,
        val keyIndex: Int,
        val modelIndex: Int
    ) : ProcessingState
    
    data class VpnBlockError(
        val sectionIndex: Int,
        val errorMsg: String
    ) : ProcessingState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application, viewModelScope)
    private val appSettingsDao = db.appSettingsDao()

    // Decomposed Single Responsibility Components
    private val apiKeyManager = ApiKeyManager(db)
    private val promptManager = PromptManager(db)
    private val sessionPersistenceManager = SessionPersistenceManager(db)
    private val geminiCommunicator = GeminiCommunicator()
    private val chunkProcessor = ChunkProcessor()
    private val retryManager = ProcessingRetryManager()
    private val txtExporter = TxtExporter()
    private val htmlExporter = HtmlExporter()

    // Database Flows
    val apiKeysFlow: StateFlow<List<ApiKeyConfig>> = apiKeyManager.getAllApiKeysFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val promptTemplatesFlow: StateFlow<List<PromptTemplate>> = promptManager.getAllTemplatesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeSessionFlow: StateFlow<ActiveSession?> = sessionPersistenceManager.getActiveSessionFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val historyLogsFlow: StateFlow<List<HistoryLog>> = db.historyLogsDao().getAllHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appSettingsFlow: StateFlow<AppSettings?> = appSettingsDao.getSettingsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val errorLogsFlow: StateFlow<List<ErrorLog>> = db.errorLogDao().getAllErrorLogsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val globalModelsFlow: StateFlow<List<ModelConfig>> = appSettingsFlow
        .map { settings ->
            val json = settings?.globalModelsJson ?: ""
            if (json.isBlank()) {
                listOf(
                    ModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash"),
                    ModelConfig("gemini-3-flash-preview", "Gemini 3-Flash Preview"),
                    ModelConfig("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite")
                )
            } else {
                JsonSerializer.deserializeModels(json)
            }
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            listOf(
                ModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash"),
                ModelConfig("gemini-3-flash-preview", "Gemini 3-Flash Preview"),
                ModelConfig("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite")
            )
        )

    // Selection States
    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _selectedFileSize = MutableStateFlow<Long?>(null)
    val selectedFileSize: StateFlow<Long?> = _selectedFileSize.asStateFlow()

    private val _outputFileName = MutableStateFlow("")
    val outputFileName: StateFlow<String> = _outputFileName.asStateFlow()

    private val _selectedPromptId = MutableStateFlow<Int?>(null)
    val selectedPromptId: StateFlow<Int?> = _selectedPromptId.asStateFlow()

    private val _processingState = MutableStateFlow<ProcessingState>(ProcessingState.Idle)
    val processingState: StateFlow<ProcessingState> = _processingState.asStateFlow()

    private var fileContent: String = ""
    private var processingJob: Job? = null

    init {
        // Ensure settings are pre-populated
        viewModelScope.launch {
            if (appSettingsDao.getSettings() == null) {
                appSettingsDao.insertSettings(AppSettings())
            }
        }
    }

    // Settings actions
    fun updateSettings(settings: AppSettings) {
        viewModelScope.launch {
            appSettingsDao.insertSettings(settings)
        }
    }

    // API Key actions
    fun addApiKey(title: String, apiKey: String, models: List<ModelConfig>) {
        viewModelScope.launch {
            apiKeyManager.addApiKey(title, apiKey, models)
        }
    }

    fun updateApiKey(id: Int, title: String, apiKey: String, models: List<ModelConfig>, priorityOrder: Int) {
        viewModelScope.launch {
            apiKeyManager.updateApiKey(id, title, apiKey, models, priorityOrder)
        }
    }

    fun deleteApiKey(id: Int) {
        viewModelScope.launch {
            apiKeyManager.deleteApiKey(id)
        }
    }

    fun moveApiKeyUp(key: ApiKeyConfig) {
        viewModelScope.launch {
            apiKeyManager.moveApiKeyUp(key)
        }
    }

    fun moveApiKeyDown(key: ApiKeyConfig) {
        viewModelScope.launch {
            apiKeyManager.moveApiKeyDown(key)
        }
    }

    // Prompt templates actions
    fun addPromptTemplate(title: String, prompt: String) {
        viewModelScope.launch {
            promptManager.addPromptTemplate(title, prompt)
        }
    }

    fun updatePromptTemplate(id: Int, title: String, prompt: String, priorityOrder: Int) {
        viewModelScope.launch {
            promptManager.updatePromptTemplate(id, title, prompt, priorityOrder)
        }
    }

    fun movePromptTemplateUp(template: PromptTemplate) {
        viewModelScope.launch {
            promptManager.movePromptTemplateUp(template)
        }
    }

    fun movePromptTemplateDown(template: PromptTemplate) {
        viewModelScope.launch {
            promptManager.movePromptTemplateDown(template)
        }
    }

    fun deletePromptTemplate(id: Int) {
        viewModelScope.launch {
            promptManager.deletePromptTemplate(id)
        }
    }

    fun duplicatePromptTemplate(template: PromptTemplate) {
        viewModelScope.launch {
            promptManager.duplicatePromptTemplate(template)
        }
    }

    // Global models list management inside AppSettings
    private fun getGlobalModelsFromSettings(settings: AppSettings?): List<ModelConfig> {
        val json = settings?.globalModelsJson ?: ""
        if (json.isBlank()) {
            return listOf(
                ModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash"),
                ModelConfig("gemini-3-flash-preview", "Gemini 3-Flash Preview"),
                ModelConfig("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite")
            )
        }
        return JsonSerializer.deserializeModels(json)
    }

    fun getGlobalModels(): List<ModelConfig> {
        val settings = appSettingsFlow.value
        return getGlobalModelsFromSettings(settings)
    }

    fun addGlobalModel(code: String, title: String) {
        viewModelScope.launch {
            val settings = appSettingsFlow.value ?: AppSettings()
            val current = getGlobalModelsFromSettings(settings).toMutableList()
            if (current.none { it.code == code }) {
                current.add(ModelConfig(code, title))
                val updatedSettings = settings.copy(globalModelsJson = JsonSerializer.serializeModels(current))
                updateSettings(updatedSettings)
            }
        }
    }

    fun deleteGlobalModel(code: String) {
        viewModelScope.launch {
            val settings = appSettingsFlow.value ?: AppSettings()
            val current = getGlobalModelsFromSettings(settings).toMutableList()
            val removed = current.removeIf { it.code == code }
            if (removed) {
                val updatedSettings = settings.copy(globalModelsJson = JsonSerializer.serializeModels(current))
                updateSettings(updatedSettings)
            }
        }
    }

    fun moveGlobalModelUp(model: ModelConfig) {
        viewModelScope.launch {
            val settings = appSettingsFlow.value ?: AppSettings()
            val current = getGlobalModelsFromSettings(settings).toMutableList()
            val idx = current.indexOfFirst { it.code == model.code }
            if (idx > 0) {
                val temp = current[idx]
                current[idx] = current[idx - 1]
                current[idx - 1] = temp
                val updatedSettings = settings.copy(globalModelsJson = JsonSerializer.serializeModels(current))
                updateSettings(updatedSettings)
            }
        }
    }

    fun moveGlobalModelDown(model: ModelConfig) {
        viewModelScope.launch {
            val settings = appSettingsFlow.value ?: AppSettings()
            val current = getGlobalModelsFromSettings(settings).toMutableList()
            val idx = current.indexOfFirst { it.code == model.code }
            if (idx != -1 && idx < current.size - 1) {
                val temp = current[idx]
                current[idx] = current[idx + 1]
                current[idx + 1] = temp
                val updatedSettings = settings.copy(globalModelsJson = JsonSerializer.serializeModels(current))
                updateSettings(updatedSettings)
            }
        }
    }

    fun logError(message: String, details: String) {
        viewModelScope.launch {
            sessionPersistenceManager.logError(message, details)
        }
    }

    fun clearErrorLogs() {
        viewModelScope.launch {
            sessionPersistenceManager.clearErrorLogs()
        }
    }

    fun selectPrompt(id: Int?) {
        _selectedPromptId.value = id
    }

    fun selectFile(context: Context, uri: Uri) {
        _selectedFileUri.value = uri
        val resolver = context.contentResolver
        
        var name: String? = null
        var size: Long? = null
        try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) name = cursor.getString(nameIdx)
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error fetching file metadata", e)
        }

        if (name == null) {
            name = uri.lastPathSegment ?: "document.txt"
        }
        
        _selectedFileName.value = name
        _selectedFileSize.value = size
        
        val baseName = name!!.substringBeforeLast(".")
        _outputFileName.value = "${baseName}_summary.txt"

        viewModelScope.launch(Dispatchers.IO) {
            try {
                resolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    fileContent = String(bytes, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _processingState.value = ProcessingState.Error("خطا در بارگذاری فایل: ${e.message}")
                }
            }
        }
    }

    fun clearSelectedFile() {
        _selectedFileUri.value = null
        _selectedFileName.value = null
        _selectedFileSize.value = null
        _outputFileName.value = ""
        fileContent = ""
        _processingState.value = ProcessingState.Idle
    }

    fun updateOutputFileName(name: String) {
        _outputFileName.value = name
    }

    // Loop & Session execution
    fun startNewSession(context: Context, customPromptText: String) {
        val settings = appSettingsFlow.value ?: AppSettings()
        val separator = settings.customSeparator

        if (fileContent.isBlank()) {
            _processingState.value = ProcessingState.Error("لطفاً ابتدا فایل متنی صحیح انتخاب کنید.")
            return
        }

        val sections = chunkProcessor.splitIntoSections(fileContent, separator)
        if (sections.isEmpty()) {
            _processingState.value = ProcessingState.Error("هیچ بخشی با جداکننده تعریف شده یافت نشد!")
            return
        }

        viewModelScope.launch {
            processingJob?.cancel()
            sessionPersistenceManager.deleteActiveSession()

            val origName = _selectedFileName.value ?: "document.txt"
            val outName = _outputFileName.value.trim().ifBlank { "${origName.substringBeforeLast(".")}_summary.txt" }

            val session = ActiveSession(
                originalFileName = origName,
                outputFileName = outName,
                separator = separator,
                totalSections = sections.size,
                currentSectionIndex = 0,
                rawSectionsJson = JsonSerializer.serializeStrings(sections),
                accumulatedSummariesJson = JsonSerializer.serializeStrings(emptyList()),
                isCompleted = false,
                fileUriString = _selectedFileUri.value?.toString() ?: "",
                customPrompt = customPromptText
            )

            sessionPersistenceManager.insertActiveSession(session)
            retryManager.resetPointers()

            runProcessingLoop(context, session)
        }
    }

    fun resumeSession(context: Context) {
        viewModelScope.launch {
            val session = sessionPersistenceManager.getActiveSession()
            if (session != null) {
                runProcessingLoop(context, session)
            } else {
                _processingState.value = ProcessingState.Idle
            }
        }
    }

    fun abortSession() {
        processingJob?.cancel()
        viewModelScope.launch {
            sessionPersistenceManager.deleteActiveSession()
            _processingState.value = ProcessingState.Idle
        }
    }

    fun manualRetry(context: Context) {
        viewModelScope.launch {
            val session = sessionPersistenceManager.getActiveSession()
            if (session != null) {
                retryManager.resetRetryCount()
                runProcessingLoop(context, session)
            }
        }
    }

    fun proceedToNextFallback(context: Context) {
        viewModelScope.launch {
            val session = sessionPersistenceManager.getActiveSession() ?: return@launch
            val keys = getEffectiveKeys()
            if (keys.isEmpty()) {
                _processingState.value = ProcessingState.Error("پیکربندی معتبر کلید API یافت نشد.")
                return@launch
            }

            val currentKey = keys.getOrNull(retryManager.activeKeyIndex)
            val models = currentKey?.let { JsonSerializer.deserializeModels(it.modelsJson) } ?: emptyList()

            if (retryManager.activeModelIndex + 1 < models.size) {
                retryManager.incrementModelIndex()
                retryManager.resetRetryCount()
                runProcessingLoop(context, session)
            } else if (retryManager.activeKeyIndex + 1 < keys.size) {
                retryManager.incrementKeyIndex()
                retryManager.resetModelIndex()
                retryManager.resetRetryCount()
                runProcessingLoop(context, session)
            } else {
                _processingState.value = ProcessingState.Error("تمامی کلیدها و مدل‌های موجود بررسی شده و با شکست مواجه شدند.")
            }
        }
    }

    fun manualForceFallback(context: Context, keyIdx: Int, modelIdx: Int) {
        viewModelScope.launch {
            val session = sessionPersistenceManager.getActiveSession() ?: return@launch
            retryManager.setPointers(keyIdx, modelIdx)
            runProcessingLoop(context, session)
        }
    }

    private suspend fun getEffectiveKeys(): List<ApiKeyConfig> {
        val keys = apiKeyManager.getAllApiKeys()
        if (keys.isNotEmpty()) return keys

        val defaultVal = BuildConfig.GEMINI_API_KEY
        return if (defaultVal.isNotEmpty() && defaultVal != "MY_GEMINI_API_KEY") {
            listOf(
                ApiKeyConfig(
                    id = -99,
                    title = "کلید پیش‌فرض برنامه",
                    apiKey = defaultVal,
                    priorityOrder = 1,
                    modelsJson = JsonSerializer.serializeModels(
                        listOf(
                            ModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash")
                        )
                    )
                )
            )
        } else {
            emptyList()
        }
    }

    private fun runProcessingLoop(context: Context, startSession: ActiveSession) {
        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            var session = startSession
            val sections = JsonSerializer.deserializeStrings(session.rawSectionsJson)
            val summaries = JsonSerializer.deserializeStrings(session.accumulatedSummariesJson).toMutableList()
            val total = session.totalSections

            while (session.currentSectionIndex < total) {
                val index = session.currentSectionIndex
                val currentTextSection = sections[index]

                val keys = getEffectiveKeys()
                if (keys.isEmpty()) {
                    _processingState.value = ProcessingState.Error("برای خلاصه‌سازی نیاز به تنظیم حداقل یک کلید API در صفحه مربوطه است!")
                    return@launch
                }

                val keyIdx = if (retryManager.activeKeyIndex >= keys.size) 0 else retryManager.activeKeyIndex
                val rawKey = keys[keyIdx]
                val models = JsonSerializer.deserializeModels(rawKey.modelsJson)
                if (models.isEmpty()) {
                    _processingState.value = ProcessingState.Error("کلید انتخاب شده فاقد مدل‌های خلاصه فعال است.")
                    return@launch
                }
                
                val modelIdx = if (retryManager.activeModelIndex >= models.size) 0 else retryManager.activeModelIndex
                val rawModel = models[modelIdx]

                val settings = appSettingsFlow.value ?: AppSettings()
                val retriesAllowed = settings.retryAttemptsLimit
                val retriesLeft = (retriesAllowed - retryManager.currentRetryCount).coerceAtLeast(0)

                _processingState.value = ProcessingState.Running(
                    originalFileName = session.originalFileName,
                    currentSection = index + 1,
                    totalSections = total,
                    retriesLeft = retriesLeft,
                    activeKeyTitle = rawKey.title,
                    activeModelTitle = rawModel.title,
                    statusMessage = "در حال ارسال بخش ${index + 1} از $total برای خلاصه‌سازی..."
                )

                // Call the Gemini API communicator component
                val responseResult = geminiCommunicator.callGeminiApi(
                    apiKey = rawKey.apiKey,
                    modelCode = rawModel.code,
                    sectionText = currentTextSection,
                    customPrompt = session.customPrompt
                )

                if (responseResult.isSuccess) {
                    val responseSummary = responseResult.getOrThrow()
                    summaries.add(responseSummary)
                    retryManager.resetRetryCount()

                    val nextIndex = index + 1
                    val isSessionDone = nextIndex >= total

                    session = session.copy(
                        currentSectionIndex = nextIndex,
                        accumulatedSummariesJson = JsonSerializer.serializeStrings(summaries),
                        isCompleted = isSessionDone
                    )

                    sessionPersistenceManager.insertActiveSession(session)

                    if (isSessionDone) {
                        _processingState.value = ProcessingState.Loading("در حال جمع‌آوری و ذخیره خروجی نهایی...")
                        val compiledResult = txtExporter.compileAndFormatSummaries(summaries, settings)
                        val baseName = session.outputFileName.substringBeforeLast(".")
                        val textFileName = "$baseName.txt"
                        val htmlFileName = "$baseName.html"

                        val textUri = txtExporter.saveFileToDownloads(context, textFileName, compiledResult, "text/plain")
                        val htmlContent = htmlExporter.generateHtmlFromSummaries(session.originalFileName, summaries)
                        val htmlUri = txtExporter.saveFileToDownloads(context, htmlFileName, htmlContent, "text/html")

                        val textUriStr = textUri?.toString() ?: ""
                        val htmlUriStr = htmlUri?.toString() ?: ""

                        db.historyLogsDao().insertHistoryLog(
                            HistoryLog(
                                fileName = session.originalFileName,
                                sectionsCount = total,
                                savedPath = textFileName,
                                savedHtmlPath = htmlFileName,
                                savedTextUri = textUriStr,
                                savedHtmlUri = htmlUriStr,
                                summaryContent = compiledResult
                            )
                        )

                        sessionPersistenceManager.deleteActiveSession()
                        _processingState.value = ProcessingState.Success(
                            summary = compiledResult,
                            savedPath = textFileName,
                            savedHtmlPath = htmlFileName,
                            textFileUri = textUriStr,
                            htmlFileUri = htmlUriStr,
                            htmlContent = htmlContent
                        )
                        return@launch
                    } else {
                        _processingState.value = ProcessingState.Running(
                            originalFileName = session.originalFileName,
                            currentSection = index + 1,
                            totalSections = total,
                            retriesLeft = retriesLeft,
                            activeKeyTitle = rawKey.title,
                            activeModelTitle = rawModel.title,
                            statusMessage = "بخش ${index + 1} دریافت شد. ${settings.successDelaySeconds} ثانیه استراحت..."
                        )
                        delay(settings.successDelaySeconds * 1000L)
                    }
                } else {
                    val exception = responseResult.exceptionOrNull()
                    val statusCode = (exception as? HttpException)?.code() ?: 0

                    val errMsg = exception?.localizedMessage ?: exception?.message ?: "$statusCode"
                    val details = "بخش ${index + 1} از $total - کلید: ${rawKey.title} - مدل: ${rawModel.code}\nاستک تریس: ${exception?.stackTraceToString() ?: "ندارد"}"
                    logError("خطا در پردازش بخش ${index + 1}: $errMsg (کد وضعیت: $statusCode)", details)

                    Log.e("SummarizerLoop", "API failure response: ${exception?.message}", exception)

                    if (statusCode == 403) {
                        _processingState.value = ProcessingState.VpnBlockError(
                            sectionIndex = index,
                            errorMsg = "خطای ممنوعیت (۴۰۳) به دلیل عدم انطباق IP کشور رخ داد. لطفاً فیلترشکن خود را روشن، خاموش یا به کشوری معتبر تغییر داده و دکمه ادامه را بزنید."
                        )
                        return@launch
                    }

                    retryManager.incrementRetry()
                    if (retryManager.canRetry(settings)) {
                        val waitTime = retryManager.getRetryDelaySeconds(statusCode, settings)
                        _processingState.value = ProcessingState.Running(
                            originalFileName = session.originalFileName,
                            currentSection = index + 1,
                            totalSections = total,
                            retriesLeft = (retriesAllowed - retryManager.currentRetryCount).coerceAtLeast(0),
                            activeKeyTitle = rawKey.title,
                            activeModelTitle = rawModel.title,
                            statusMessage = "ناموفق خطای ($statusCode). تلاش مجدد تا ${waitTime} ثانیه دیگر..."
                        )
                        delay(waitTime * 1000L)
                    } else {
                        when (retryManager.onRetryExhausted(settings, keys.size, models.size)) {
                            FallbackResult.NextModel -> {
                                delay(settings.errorDelaySeconds * 1000L)
                            }
                            FallbackResult.NextKey -> {
                                delay(settings.errorDelaySeconds * 1000L)
                            }
                            FallbackResult.Exhausted -> {
                                _processingState.value = ProcessingState.Error(
                                    "خطا در خلاصه‌سازی بخش ${index + 1}. تمامی کلیدها و مدل‌های تعریف شده با خطا مواجه شدند: ${exception?.localizedMessage ?: exception?.message}"
                                )
                                return@launch
                            }
                            FallbackResult.WaitManualDecision -> {
                                _processingState.value = ProcessingState.WaitingForUserDecision(
                                    sectionIndex = index,
                                    errorMsg = exception?.localizedMessage ?: exception?.message ?: "خطای ناشناخته",
                                    keyIndex = retryManager.activeKeyIndex,
                                    modelIndex = retryManager.activeModelIndex
                                )
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    fun generateHtmlFromSummaries(fileName: String, summaries: List<String>): String {
        return htmlExporter.generateHtmlFromSummaries(fileName, summaries)
    }

    fun clearHistory() {
        viewModelScope.launch {
            sessionPersistenceManager.clearHistory()
        }
    }
}
