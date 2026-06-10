package com.example.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.JsonSerializer
import com.example.api.Part
import com.example.api.RetrofitClient
import com.example.db.ActiveSession
import com.example.db.ApiKeyConfig
import com.example.db.AppDatabase
import com.example.db.AppSettings
import com.example.db.HistoryLog
import com.example.db.ErrorLog
import com.example.db.ModelConfig
import com.example.db.PromptTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream

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
    private val apiKeyDao = db.apiKeyDao()
    private val promptTemplateDao = db.promptTemplateDao()
    private val activeSessionDao = db.activeSessionDao()
    private val historyLogsDao = db.historyLogsDao()
    private val appSettingsDao = db.appSettingsDao()
    private val errorLogDao = db.errorLogDao()

    // Database Flows
    val apiKeysFlow: StateFlow<List<ApiKeyConfig>> = apiKeyDao.getAllApiKeysFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val promptTemplatesFlow: StateFlow<List<PromptTemplate>> = promptTemplateDao.getAllTemplatesFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeSessionFlow: StateFlow<ActiveSession?> = activeSessionDao.getActiveSessionFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val historyLogsFlow: StateFlow<List<HistoryLog>> = historyLogsDao.getAllHistoryFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val appSettingsFlow: StateFlow<AppSettings?> = appSettingsDao.getSettingsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val errorLogsFlow: StateFlow<List<ErrorLog>> = errorLogDao.getAllErrorLogsFlow()
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

    // Execution Pointer State for fallback tracking
    private var activeKeyIndex = 0
    private var activeModelIndex = 0
    private var currentRetryCount = 0

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
    }

    fun deleteApiKey(id: Int) {
        viewModelScope.launch {
            apiKeyDao.deleteApiKeyById(id)
        }
    }

    fun moveApiKeyUp(key: ApiKeyConfig) {
        viewModelScope.launch {
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
    }

    fun moveApiKeyDown(key: ApiKeyConfig) {
        viewModelScope.launch {
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

    // Prompt templates actions
    fun addPromptTemplate(title: String, prompt: String) {
        viewModelScope.launch {
            val existing = promptTemplateDao.getAllTemplates()
            val order = if (existing.isEmpty()) 0 else (existing.maxOfOrNull { it.priorityOrder } ?: 0) + 1
            promptTemplateDao.insertTemplate(
                PromptTemplate(title = title, promptContent = prompt, priorityOrder = order)
            )
        }
    }

    fun updatePromptTemplate(id: Int, title: String, prompt: String, priorityOrder: Int) {
        viewModelScope.launch {
            promptTemplateDao.updateTemplate(
                PromptTemplate(id = id, title = title, promptContent = prompt, priorityOrder = priorityOrder)
            )
        }
    }

    fun movePromptTemplateUp(template: PromptTemplate) {
        viewModelScope.launch {
            val templates = promptTemplateDao.getAllTemplates()
            val currentIndex = templates.indexOfFirst { it.id == template.id }
            if (currentIndex > 0) {
                val prevTemplate = templates[currentIndex - 1]
                val updatedPrevOrder = template.priorityOrder
                val updatedCurrentOrder = prevTemplate.priorityOrder
                promptTemplateDao.insertTemplate(template.copy(priorityOrder = updatedCurrentOrder))
                promptTemplateDao.insertTemplate(prevTemplate.copy(priorityOrder = updatedPrevOrder))
            }
        }
    }

    fun movePromptTemplateDown(template: PromptTemplate) {
        viewModelScope.launch {
            val templates = promptTemplateDao.getAllTemplates()
            val currentIndex = templates.indexOfFirst { it.id == template.id }
            if (currentIndex != -1 && currentIndex < templates.size - 1) {
                val nextTemplate = templates[currentIndex + 1]
                val updatedNextOrder = template.priorityOrder
                val updatedCurrentOrder = nextTemplate.priorityOrder
                promptTemplateDao.insertTemplate(template.copy(priorityOrder = updatedCurrentOrder))
                promptTemplateDao.insertTemplate(nextTemplate.copy(priorityOrder = updatedNextOrder))
            }
        }
    }

    fun deletePromptTemplate(id: Int) {
        viewModelScope.launch {
            promptTemplateDao.deleteTemplateById(id)
        }
    }

    fun duplicatePromptTemplate(template: PromptTemplate) {
        viewModelScope.launch {
            val existing = promptTemplateDao.getAllTemplates()
            val order = if (existing.isEmpty()) 0 else (existing.maxOfOrNull { it.priorityOrder } ?: 0) + 1
            promptTemplateDao.insertTemplate(
                PromptTemplate(title = "${template.title} (کپی)", promptContent = template.promptContent, priorityOrder = order)
            )
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
        val json = appSettingsFlow.value?.globalModelsJson ?: ""
        if (json.isBlank()) {
            return listOf(
                ModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash"),
                ModelConfig("gemini-3-flash-preview", "Gemini 3-Flash Preview"),
                ModelConfig("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite")
            )
        }
        return JsonSerializer.deserializeModels(json)
    }

    fun addGlobalModel(code: String, title: String) {
        viewModelScope.launch {
            val settings = appSettingsDao.getSettings() ?: AppSettings()
            val currentModels = getGlobalModelsFromSettings(settings).toMutableList()
            if (currentModels.none { it.code == code }) {
                currentModels.add(ModelConfig(code, title))
                val updatedJson = JsonSerializer.serializeModels(currentModels)
                appSettingsDao.insertSettings(settings.copy(globalModelsJson = updatedJson))
            }
        }
    }

    fun deleteGlobalModel(code: String) {
        viewModelScope.launch {
            val settings = appSettingsDao.getSettings() ?: AppSettings()
            val currentModels = getGlobalModelsFromSettings(settings).toMutableList()
            currentModels.removeAll { it.code == code }
            val updatedJson = JsonSerializer.serializeModels(currentModels)
            appSettingsDao.insertSettings(settings.copy(globalModelsJson = updatedJson))
        }
    }

    fun moveGlobalModelUp(model: ModelConfig) {
        viewModelScope.launch {
            val settings = appSettingsDao.getSettings() ?: AppSettings()
            val currentModels = getGlobalModelsFromSettings(settings).toMutableList()
            val index = currentModels.indexOfFirst { it.code == model.code }
            if (index > 0) {
                val temp = currentModels[index]
                currentModels[index] = currentModels[index - 1]
                currentModels[index - 1] = temp
                val updatedJson = JsonSerializer.serializeModels(currentModels)
                appSettingsDao.insertSettings(settings.copy(globalModelsJson = updatedJson))
            }
        }
    }

    fun moveGlobalModelDown(model: ModelConfig) {
        viewModelScope.launch {
            val settings = appSettingsDao.getSettings() ?: AppSettings()
            val currentModels = getGlobalModelsFromSettings(settings).toMutableList()
            val index = currentModels.indexOfFirst { it.code == model.code }
            if (index != -1 && index < currentModels.size - 1) {
                val temp = currentModels[index]
                currentModels[index] = currentModels[index + 1]
                currentModels[index + 1] = temp
                val updatedJson = JsonSerializer.serializeModels(currentModels)
                appSettingsDao.insertSettings(settings.copy(globalModelsJson = updatedJson))
            }
        }
    }

    fun logError(message: String, details: String) {
        viewModelScope.launch {
            errorLogDao.insertErrorLog(ErrorLog(errorMessage = message, details = details))
        }
    }

    fun clearErrorLogs() {
        viewModelScope.launch {
            errorLogDao.clearErrorLogs()
        }
    }

    fun selectPrompt(id: Int?) {
        _selectedPromptId.value = id
    }

    // File Picker handling
    fun selectFile(context: Context, uri: Uri) {
        _selectedFileUri.value = uri
        viewModelScope.launch {
            try {
                _processingState.value = ProcessingState.Loading("در حال خواندن فایل...")
                val resolver = context.contentResolver
                
                var name: String? = null
                var size: Long? = null
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }

                _selectedFileName.value = name ?: "document.txt"
                _selectedFileSize.value = size

                val baseName = name?.substringBeforeLast(".") ?: "document"
                _outputFileName.value = "${baseName}_summary.txt"

                val content = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().use { reader -> reader.readText() }
                    } ?: ""
                }

                if (content.isBlank()) {
                    _processingState.value = ProcessingState.Error("فایل متنی انتخابی خالی است!")
                } else {
                    fileContent = content
                    _processingState.value = ProcessingState.Idle
                }
            } catch (e: Exception) {
                _processingState.value = ProcessingState.Error("خطا در بارگذاری فایل: ${e.message}")
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

        val sections = fileContent.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        if (sections.isEmpty()) {
            _processingState.value = ProcessingState.Error("هیچ بخشی با جداکننده تعریف شده یافت نشد!")
            return
        }

        viewModelScope.launch {
            // Cancel active job
            processingJob?.cancel()
            activeSessionDao.deleteActiveSession()

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

            activeSessionDao.insertActiveSession(session)
            activeKeyIndex = 0
            activeModelIndex = 0
            currentRetryCount = 0

            runProcessingLoop(context, session)
        }
    }

    fun resumeSession(context: Context) {
        viewModelScope.launch {
            val session = activeSessionDao.getActiveSession()
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
            activeSessionDao.deleteActiveSession()
            _processingState.value = ProcessingState.Idle
        }
    }

    // Perform retry manually from error screen
    fun manualRetry(context: Context) {
        viewModelScope.launch {
            val session = activeSessionDao.getActiveSession()
            if (session != null) {
                currentRetryCount = 0
                runProcessingLoop(context, session)
            }
        }
    }

    // Advance dynamically either model or key
    fun proceedToNextFallback(context: Context) {
        viewModelScope.launch {
            val session = activeSessionDao.getActiveSession() ?: return@launch
            val keys = getEffectiveKeys()
            if (keys.isEmpty()) {
                _processingState.value = ProcessingState.Error("پیکربندی معتبر کلید API یافت نشد.")
                return@launch
            }

            val currentKey = keys.getOrNull(activeKeyIndex)
            val models = currentKey?.let { JsonSerializer.deserializeModels(it.modelsJson) } ?: emptyList()

            if (activeModelIndex + 1 < models.size) {
                // Next model same key
                activeModelIndex++
                currentRetryCount = 0
                runProcessingLoop(context, session)
            } else if (activeKeyIndex + 1 < keys.size) {
                // Next key first model
                activeKeyIndex++
                activeModelIndex = 0
                currentRetryCount = 0
                runProcessingLoop(context, session)
            } else {
                _processingState.value = ProcessingState.Error("تمامی کلیدها و مدل‌های موجود بررسی شده و با شکست مواجه شدند.")
            }
        }
    }

    // Force selection manual fallback key & model
    fun manualForceFallback(context: Context, keyIdx: Int, modelIdx: Int) {
        viewModelScope.launch {
            val session = activeSessionDao.getActiveSession() ?: return@launch
            activeKeyIndex = keyIdx
            activeModelIndex = modelIdx
            currentRetryCount = 0
            runProcessingLoop(context, session)
        }
    }

    private suspend fun getEffectiveKeys(): List<ApiKeyConfig> {
        val keys = apiKeyDao.getAllApiKeys()
        if (keys.isNotEmpty()) return keys

        // Config default fallback from build params to retain out-of-box usefulness
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

                // Ensure pointers are safe
                if (activeKeyIndex >= keys.size) {
                    activeKeyIndex = 0
                }
                val rawKey = keys[activeKeyIndex]
                val models = JsonSerializer.deserializeModels(rawKey.modelsJson)
                if (models.isEmpty()) {
                    _processingState.value = ProcessingState.Error("کلید انتخاب شده فاقد مدل‌های خلاصه فعال است.")
                    return@launch
                }
                
                if (activeModelIndex >= models.size) {
                    activeModelIndex = 0
                }
                val rawModel = models[activeModelIndex]

                val settings = appSettingsFlow.value ?: AppSettings()
                val retriesAllowed = settings.retryAttemptsLimit
                val retriesLeft = (retriesAllowed - currentRetryCount).coerceAtLeast(0)

                _processingState.value = ProcessingState.Running(
                    originalFileName = session.originalFileName,
                    currentSection = index + 1,
                    totalSections = total,
                    retriesLeft = retriesLeft,
                    activeKeyTitle = rawKey.title,
                    activeModelTitle = rawModel.title,
                    statusMessage = "در حال ارسال بخش ${index + 1} از $total برای خلاصه‌سازی..."
                )

                // Call the API
                val responseResult = callGeminiApi(
                    apiKey = rawKey.apiKey,
                    modelCode = rawModel.code,
                    sectionText = currentTextSection,
                    customPrompt = session.customPrompt
                )

                if (responseResult.isSuccess) {
                    // Success! append and update DB
                    val responseSummary = responseResult.getOrThrow()
                    summaries.add(responseSummary)
                    currentRetryCount = 0

                    val nextIndex = index + 1
                    val isSessionDone = nextIndex >= total

                    session = session.copy(
                        currentSectionIndex = nextIndex,
                        accumulatedSummariesJson = JsonSerializer.serializeStrings(summaries),
                        isCompleted = isSessionDone
                    )

                    activeSessionDao.insertActiveSession(session)

                    if (isSessionDone) {
                        // Finished entirely! Compiling output
                        _processingState.value = ProcessingState.Loading("در حال جمع‌آوری و ذخیره خروجی نهایی...")
                        val compiledResult = compileAndFormatSummaries(sections, summaries)
                        val baseName = session.outputFileName.substringBeforeLast(".")
                        val textFileName = "$baseName.txt"
                        val htmlFileName = "$baseName.html"

                        val textUri = saveFileToDownloads(context, textFileName, compiledResult, "text/plain")
                        val htmlContent = generateHtmlFromSummaries(session.originalFileName, summaries)
                        val htmlUri = saveFileToDownloads(context, htmlFileName, htmlContent, "text/html")

                        val textUriStr = textUri?.toString() ?: ""
                        val htmlUriStr = htmlUri?.toString() ?: ""

                        // Insert History Log for analytics
                        historyLogsDao.insertHistoryLog(
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

                        // Clean session
                        activeSessionDao.deleteActiveSession()
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
                        // Success wait-delay
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
                    // Error encountered
                    val exception = responseResult.exceptionOrNull()
                    val statusCode = (exception as? HttpException)?.code() ?: 0

                    val errMsg = exception?.localizedMessage ?: exception?.message ?: "$statusCode"
                    val details = "بخش ${index + 1} از $total - کلید: ${rawKey.title} - مدل: ${rawModel.code}\nاستک تریس: ${exception?.stackTraceToString() ?: "ندارد"}"
                    logError("خطا در پردازش بخش ${index + 1}: $errMsg (کد وضعیت: $statusCode)", details)

                    Log.e("SummarizerLoop", "API failure response: ${exception?.message}", exception)

                    if (statusCode == 403) {
                        // 403 Error -> Proxy block / VPN problem. Set block state to halt loop
                        _processingState.value = ProcessingState.VpnBlockError(
                            sectionIndex = index,
                            errorMsg = "خطای ممنوعیت (۴۰۳) به دلیل عدم انطباق IP کشور رخ داد. لطفاً فیلترشکن خود را روشن، خاموش یا به کشوری معتبر تغییر داده و دکمه ادامه را بزنید."
                        )
                        return@launch
                    }

                    // Handles retries
                    currentRetryCount++
                    if (currentRetryCount <= retriesAllowed) {
                        val waitTime = if (statusCode == 503) settings.overloadDelaySeconds else settings.errorDelaySeconds
                        _processingState.value = ProcessingState.Running(
                            originalFileName = session.originalFileName,
                            currentSection = index + 1,
                            totalSections = total,
                            retriesLeft = (retriesAllowed - currentRetryCount).coerceAtLeast(0),
                            activeKeyTitle = rawKey.title,
                            activeModelTitle = rawModel.title,
                            statusMessage = "ناموفق خطای ($statusCode). تلاش مجدد تا ${waitTime} ثانیه دیگر..."
                        )
                        delay(waitTime * 1000L)
                    } else {
                        // Max retries reached
                        if (settings.autoSwitchOnLimit) {
                            // Automatic switch behavior
                            if (activeModelIndex + 1 < models.size) {
                                activeModelIndex++
                                currentRetryCount = 0
                            } else if (activeKeyIndex + 1 < keys.size) {
                                activeKeyIndex++
                                activeModelIndex = 0
                                currentRetryCount = 0
                            } else {
                                // No keys/models left! Show error
                                _processingState.value = ProcessingState.Error(
                                    "خطا در خلاصه‌سازی بخش ${index + 1}. تمامی کلیدها و مدل‌های تعریف شده با خطا مواجه شدند: ${exception?.localizedMessage ?: exception?.message}"
                                )
                                return@launch
                            }
                            // Wait standard error time before moving forward
                            delay(settings.errorDelaySeconds * 1000L)
                        } else {
                            // Manual / Pause state to ask user (Default option 2)
                            _processingState.value = ProcessingState.WaitingForUserDecision(
                                sectionIndex = index,
                                errorMsg = exception?.localizedMessage ?: exception?.message ?: "خطای ناشناخته",
                                keyIndex = activeKeyIndex,
                                modelIndex = activeModelIndex
                            )
                            return@launch
                        }
                    }
                }
            }
        }
    }

    private suspend fun callGeminiApi(
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

    private fun compileAndFormatSummaries(originalSections: List<String>, summaries: List<String>): String {
        val settings = appSettingsFlow.value ?: AppSettings()
        val template = settings.compiledSeparatorTemplate
        return summaries.mapIndexed { idx, summary ->
            template
                .replace("{index}", (idx + 1).toString())
                .replace("{total}", summaries.size.toString())
                .replace("{summary}", summary)
        }.joinToString("\n\n")
    }

    private fun saveFileToDownloads(context: Context, fileName: String, content: String, mimeType: String): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                    outputStream.flush()
                }
                return uri
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { out ->
                out.write(content.toByteArray())
                out.flush()
            }
            return Uri.fromFile(file)
        }
        return null
    }

    class ChapParsed(val title: String, val rawLines: MutableList<String> = mutableListOf())
    class SecParsed(val title: String, val chapters: MutableList<ChapParsed> = mutableListOf())

    private fun parseStructure(rawText: String): List<SecParsed> {
        val lines = rawText.split(Regex("\\r?\\n"))
        val sectionSep = Regex("^={10,}.*$")
        val chapterPat = Regex("^🚩\\s*\\[(.+?)\\]\\s*🚩\\s*$")
        val chapterPat2 = Regex("^🚩\\s*([^🚩\\]\\[]+?)\\s*🚩\\s*$")

        val sections = mutableListOf<SecParsed>()
        var currentSection: SecParsed? = null
        var currentChapter: ChapParsed? = null
        var pendingTitle: String? = null
        var inSep = false

        fun flushChapter() {
            if (currentChapter != null && currentSection != null) {
                currentSection!!.chapters.add(currentChapter!!)
                currentChapter = null
            }
        }

        fun flushSection() {
            if (currentSection != null) {
                flushChapter()
                if (currentSection!!.chapters.isEmpty()) {
                    currentSection!!.chapters.add(ChapParsed(title = currentSection!!.title, rawLines = mutableListOf()))
                }
                sections.add(currentSection!!)
                currentSection = null
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (sectionSep.matches(trimmed)) {
                if (!inSep) {
                    inSep = true
                    pendingTitle = null
                } else {
                    inSep = false
                    if (pendingTitle != null) {
                        flushSection()
                        currentSection = SecParsed(title = pendingTitle!!.trim())
                        currentChapter = null
                        pendingTitle = null
                    }
                }
                continue
            }

            if (inSep) {
                pendingTitle = if (pendingTitle == null) line else pendingTitle + "\n" + line
                continue
            }

            var chapterTitle: String? = null
            val m1 = chapterPat.find(trimmed)
            val m2 = if (m1 == null) chapterPat2.find(trimmed) else null

            if (m1 != null) {
                chapterTitle = m1.groupValues[1].trim()
            } else if (m2 != null) {
                chapterTitle = m2.groupValues[1].trim()
            }

            if (chapterTitle != null) {
                if (currentSection == null) {
                    currentSection = SecParsed(title = "بخش جدید")
                }
                flushChapter()
                currentChapter = ChapParsed(title = chapterTitle)
                continue
            }

            if (currentChapter != null) {
                currentChapter!!.rawLines.add(line)
            } else if (currentSection != null) {
                if (currentSection!!.chapters.isEmpty()) {
                    currentSection!!.chapters.add(ChapParsed(title = currentSection!!.title))
                }
                currentSection!!.chapters.last().rawLines.add(line)
            } else {
                currentSection = SecParsed(title = "خلاصه سند")
                currentChapter = ChapParsed(title = "شروع بخش")
                currentChapter!!.rawLines.add(line)
            }
        }

        flushSection()
        return sections
    }

    private fun parseContent(rawLines: List<String>): String {
        val outputBlocks = mutableListOf<String>()
        val listBuffer = mutableListOf<String>()
        var listOrdered = false

        fun flushList() {
            if (listBuffer.isEmpty()) return
            val tag = if (listOrdered) "ol" else "ul"
            val html = StringBuilder("<$tag class=\"md-list\">")
            for (item in listBuffer) {
                html.append("<li>").append(item).append("</li>")
            }
            html.append("</$tag>")
            outputBlocks.add(html.toString())
            listBuffer.clear()
        }

        for (line in rawLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                flushList()
                outputBlocks.add("<p-break/>")
                continue
            }

            val olMatch = Regex("^(\\s*)(\\d+)\\.\\s+(.+)$").find(line)
            if (olMatch != null) {
                if (listBuffer.isNotEmpty() && !listOrdered) flushList()
                listOrdered = true
                listBuffer.add(applyInline(olMatch.groupValues[3]))
                continue
            }

            val ulMatch = Regex("^(\\s*)[-*+]\\s+(.+)$").find(line)
            if (ulMatch != null) {
                if (listBuffer.isNotEmpty() && listOrdered) flushList()
                listOrdered = false
                listBuffer.add(applyInline(ulMatch.groupValues[2]))
                continue
            }

            if (trimmed.startsWith(">")) {
                flushList()
                val inner = applyInline(trimmed.substring(1).trim())
                outputBlocks.add("<blockquote class=\"md-blockquote\">$inner</blockquote>")
                continue
            }

            val hMatch = Regex("^(#{1,6})\\s+(.+)$").find(trimmed)
            if (hMatch != null) {
                flushList()
                val level = hMatch.groupValues[1].length
                val inner = applyInline(hMatch.groupValues[2])
                outputBlocks.add("<h$level class=\"md-h$level\">$inner</h$level>")
                continue
            }

            if (listBuffer.isNotEmpty()) {
                flushList()
            }
            outputBlocks.add(applyInline(trimmed))
        }
        flushList()

        val html = StringBuilder()
        val paraLines = mutableListOf<String>()

        fun flushPara() {
            if (paraLines.isNotEmpty()) {
                html.append("<p class=\"md-p\">").append(paraLines.joinToString("<br>")).append("</p>")
                paraLines.clear()
            }
        }

        for (block in outputBlocks) {
            if (block.startsWith("<h") || block.startsWith("<blockquote") || block.startsWith("<ul") || block.startsWith("<ol") || block == "<p-break/>") {
                flushPara()
                if (block != "<p-break/>") {
                    html.append(block)
                }
            } else {
                paraLines.add(block)
            }
        }
        flushPara()

        return html.toString()
    }

    private fun applyInline(text: String): String {
        var res = text
        res = res
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        res = res.replace(Regex("!\\[(.*?)\\]\\((.*?)\\)"), "<img class=\"md-img\" src=\"$2\" alt=\"$1\" loading=\"lazy\">")
        res = res.replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "<a class=\"md-link\" href=\"$2\" target=\"_blank\" rel=\"noopener\">$1</a>")
        res = res.replace(Regex("\\*{3}(.+?)\\*{3}"), "<strong><em>$1</em></strong>")
        res = res.replace(Regex("_{3}(.+?)_{3}"), "<strong><em>$1</em></strong>")
        res = res.replace(Regex("\\*{2}(.+?)\\*{2}"), "<strong>$1</strong>")
        res = res.replace(Regex("_{2}(.+?)_{2}"), "<strong>$1</strong>")
        res = res.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        res = res.replace(Regex("_(.+?)_"), "<em>$1</em>")

        res = res.replace(Regex("««([\\s\\S]+?)»»"), "<mark class=\"hl-4\">$1</mark>")
        res = res.replace(Regex("&lt;&lt;([\\s\\S]+?)&gt;&gt;"), "<mark class=\"hl-3\">$1</mark>")
        res = res.replace(Regex("<<([\\s\\S]+?)>>"), "<mark class=\"hl-3\">$1</mark>")
        res = res.replace(Regex("\\[\\[([\\s\\S]+?)\\]\\]"), "<mark class=\"hl-2\">$1</mark>")
        res = res.replace(Regex("\\(\\(([\\s\\S]+?)\\)\\)"), "<mark class=\"hl-1\">$1</mark>")

        res = res.replace(Regex("«([\\s\\S]+?)»"), "<span class=\"cl-4\">$1</span>")
        res = res.replace(Regex("&lt;(((?!sp|ma|im|a|li|ul|ol|p|h\\d).)+?)&gt;"), "<span class=\"cl-3\">$1</span>")
        res = res.replace(Regex("<(((?!sp|ma|im|a|li|ul|ol|p|h\\d).)+?)>"), "<span class=\"cl-3\">$1</span>")
        res = res.replace(Regex("\\[([^\\[\\]]+?)\\]"), "<span class=\"cl-2\">$1</span>")
        res = res.replace(Regex("¥¥([^¥]+?)¥¥"), "<span class=\"cl-1\">$1</span>")

        return res
    }

    private fun normaliseArabic(str: String): String {
        return str
            .replace("ي", "ی")
            .replace("ك", "ک")
            .replace("ـ", "")
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace(Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]"), "")
            .lowercase()
            .trim()
    }

    private fun bookToJson(sections: List<SecParsed>): String {
        val sb = java.lang.StringBuilder()
        sb.append("{\"sections\":[")
        for (i in sections.indices) {
            val sec = sections[i]
            sb.append("{")
            sb.append("\"title\":").append(kotlinJsonEscape(sec.title)).append(",")
            sb.append("\"chapters\":[")
            for (j in sec.chapters.indices) {
                val ch = sec.chapters[j]
                val htmlContent = parseContent(ch.rawLines)
                val plainText = ch.rawLines.joinToString(" ")
                
                sb.append("{")
                sb.append("\"title\":").append(kotlinJsonEscape(ch.title)).append(",")
                sb.append("\"html\":").append(kotlinJsonEscape(htmlContent)).append(",")
                sb.append("\"search\":").append(kotlinJsonEscape(normaliseArabic(plainText))).append(",")
                sb.append("\"plain\":").append(kotlinJsonEscape(if (plainText.length > 300) plainText.substring(0, 300) else plainText))
                sb.append("}")
                if (j < sec.chapters.size - 1) sb.append(",")
            }
            sb.append("]")
            sb.append("}")
            if (i < sections.size - 1) sb.append(",")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun kotlinJsonEscape(str: String): String {
        val out = java.lang.StringBuilder()
        out.append("\"")
        for (element in str) {
            when (element) {
                '\\' -> out.append("\\\\")
                '\"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (element.code < 32) {
                        out.append(String.format("\\u%04x", element.code))
                    } else {
                        out.append(element)
                    }
                }
            }
        }
        out.append("\"")
        return out.toString()
    }

    fun generateHtmlFromSummaries(fileName: String, summaries: List<String>): String {
        val rawText = summaries.joinToString("\n🚩 [بخش خلاصه] 🚩\n")
        val parsed = parseStructure(rawText)
        val bookJson = bookToJson(parsed)
        val randSuffix = java.util.UUID.randomUUID().toString().take(6)
        val lsPrefix = "\"perBook_$randSuffix\""

        return """<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>کتابخوان هوشمند: $fileName</title>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Vazirmatn:wght@300;400;500;700;900&display=swap');
        
        :root {
            --bg: #f8fafc;
            --surface: #ffffff;
            --sidebar-bg: #f1f5f9;
            --text: #0f172a;
            --text-secondary: #475569;
            --primary: #4f46e5;
            --border: #e2e8f0;
            --active-bg: #cbd5e1;
            --toast-bg: #1e293b;
            --toast-text: #ffffff;
        }

        [data-theme="dark"] {
            --bg: #090d16;
            --surface: #111827;
            --sidebar-bg: #1f2937;
            --text: #f3f4f6;
            --text-secondary: #9ca3af;
            --primary: #6366f1;
            --border: #374151;
            --active-bg: #4b5563;
        }

        [data-theme="sepia"] {
            --bg: #f4edd8;
            --surface: #fdf6e3;
            --sidebar-bg: #eee8d5;
            --text: #586e75;
            --text-secondary: #657b83;
            --primary: #b58900;
            --border: #dfd5bc;
            --active-bg: #dfd5bc;
        }

        /* Highlight styles */
        .hl-1 { background-color: rgba(239, 68, 68, 0.2); border-bottom: 2px solid #ef4444; border-radius: 2px; }
        .hl-2 { background-color: rgba(34, 197, 94, 0.2); border-bottom: 2px solid #22c55e; border-radius: 2px; }
        .hl-3 { background-color: rgba(59, 130, 246, 0.2); border-bottom: 2px solid #3b82f6; border-radius: 2px; }
        .hl-4 { background-color: rgba(234, 179, 8, 0.2); border-bottom: 2px solid #eab308; border-radius: 2px; }

        .cl-1 { color: #ef4444; font-weight: bold; }
        .cl-2 { color: #22c55e; font-weight: bold; }
        .cl-3 { color: #3b82f6; font-weight: bold; }
        .cl-4 { color: #eab308; font-weight: bold; }

        * {
            box-sizing: border-box;
            font-family: 'Vazirmatn', sans-serif;
        }

        body {
            margin: 0;
            padding: 0;
            background-color: var(--bg);
            color: var(--text);
            display: flex;
            height: 100vh;
            overflow: hidden;
            transition: background-color 0.3s, color 0.3s;
        }

        /* Sidebar panel layout */
        #sidebar {
            width: 320px;
            background-color: var(--sidebar-bg);
            border-left: 1px solid var(--border);
            display: flex;
            flex-direction: column;
            flex-shrink: 0;
            transition: transform 0.3s ease;
            z-index: 100;
        }

        #main-viewer {
            flex-grow: 1;
            display: flex;
            flex-direction: column;
            overflow: hidden;
            position: relative;
        }

        #top-toolbar {
            height: 60px;
            background-color: var(--surface);
            border-bottom: 1px solid var(--border);
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0 16px;
        }

        .title-display {
            font-weight: 700;
            font-size: 16px;
            color: var(--primary);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            max-width: 50%;
        }

        .controls-group {
            display: flex;
            gap: 8px;
            align-items: center;
        }

        .toolbar-btn {
            background: none;
            border: 1px solid var(--border);
            color: var(--text);
            font-size: 14px;
            font-weight: 700;
            padding: 6px 12px;
            border-radius: 20px;
            cursor: pointer;
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 4px;
            background-color: var(--surface);
            transition: transform 0.2s;
        }

        .toolbar-btn:hover {
            border-color: var(--primary);
            transform: translateY(-1px);
        }

        #reader-container {
            flex-grow: 1;
            overflow-y: auto;
            padding: 32px 16px;
            display: flex;
            justify-content: center;
        }

        #reader-content {
            max-width: 760px;
            width: 100%;
            font-size: 18px;
            line-height: 1.85;
            direction: rtl;
            text-align: justify;
        }

        .search-area {
            padding: 12px;
            border-bottom: 1px solid var(--border);
            background-color: var(--surface);
        }

        .search-input {
            width: 100%;
            padding: 8px 12px;
            border: 1px solid var(--border);
            border-radius: 8px;
            background-color: var(--bg);
            color: var(--text);
            outline: none;
            font-size: 14px;
        }

        .search-input:focus {
            border-color: var(--primary);
        }

        .toc-scroller {
            flex-grow: 1;
            overflow-y: auto;
            padding: 12px;
        }

        .sec-header {
            font-size: 13px;
            font-weight: 900;
            color: var(--text-secondary);
            margin: 12px 4px 6px 4px;
            padding-bottom: 4px;
            border-bottom: 1px dashed var(--border);
        }

        .chap-item {
            padding: 10px 12px;
            font-size: 13.5px;
            cursor: pointer;
            border-radius: 6px;
            margin-bottom: 4px;
            color: var(--text);
            transition: background 0.2s;
            display: flex;
            align-items: center;
            justify-content: space-between;
        }

        .chap-item:hover {
            background-color: var(--active-bg);
        }

        .chap-active {
            background-color: var(--primary) !important;
            color: #ffffff !important;
            font-weight: 700;
        }

        /* Search Results Drawer */
        #search-results-panel {
            flex-grow: 1;
            overflow-y: auto;
            padding: 12px;
            display: none;
        }

        .sr-item {
            padding: 10px;
            border-bottom: 1px solid var(--border);
            cursor: pointer;
            border-radius: 6px;
            background-color: var(--surface);
            margin-bottom: 8px;
        }

        .sr-item:hover {
            background-color: var(--active-bg);
        }

        .sr-title {
            font-weight: 700;
            font-size: 13px;
            color: var(--primary);
        }

        .sr-snippet {
            font-size: 12px;
            color: var(--text-secondary);
            margin-top: 4px;
            line-height: 1.4;
        }

        /* Markdown Styles Inside Viewer */
        .md-h1, .md-h2, .md-h3 {
            color: var(--primary);
            font-weight: 900;
            margin-top: 24px;
            margin-bottom: 12px;
        }
        .md-h1 { font-size: 24px; }
        .md-h2 { font-size: 20px; }
        .md-h3 { font-size: 18px; }

        .md-blockquote {
            border-right: 4px solid var(--primary);
            padding: 12px 18px;
            margin: 18px 0;
            background-color: var(--sidebar-bg);
            border-radius: 0 8px 8px 0;
            font-style: italic;
        }

        .md-list {
            padding-right: 20px;
            margin: 16px 0;
        }

        .md-list li {
            margin-bottom: 8px;
        }

        .md-p {
            margin-bottom: 1.6em;
        }

        .footer-nav {
            height: 50px;
            background-color: var(--surface);
            border-top: 1px solid var(--border);
            display: flex;
            align-items: center;
            justify-content: space-between;
            padding: 0 16px;
        }

        /* Settings Dialog overlay */
        #settings-dialog {
            position: fixed;
            top: 50%;
            left: 50%;
            transform: translate(-50%, -50%);
            background-color: var(--surface);
            border: 1px solid var(--border);
            border-radius: 16px;
            padding: 24px;
            z-index: 1000;
            box-shadow: 0 20px 25px -5px rgba(0,0,0,0.15);
            width: 340px;
            display: none;
        }

        .overlay-bg {
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background-color: rgba(0,0,0,0.5);
            z-index: 900;
            display: none;
        }

        .setting-row {
            margin-bottom: 16px;
        }

        .setting-label {
            font-weight: 700;
            font-size: 13px;
            margin-bottom: 8px;
            display: block;
        }

        .theme-choices {
            display: flex;
            gap: 8px;
        }

        .theme-choice-btn {
            flex: 1;
            padding: 8px;
            border-radius: 8px;
            border: 1px solid var(--border);
            background: none;
            cursor: pointer;
            color: var(--text);
            font-size: 12px;
            font-weight: bold;
        }

        .size-row {
            display: flex;
            gap: 8px;
        }

        .size-btn {
            flex: 1;
            padding: 8px;
            border: 1px solid var(--border);
            background: none;
            cursor: pointer;
            font-weight: bold;
            border-radius: 8px;
            color: var(--text);
        }

        /* Toggle drawer button responsive */
        #toggle-sidebar-btn {
            display: none;
        }

        @media (max-width: 768px) {
            #sidebar {
                position: absolute;
                top: 0; bottom: 0; right: 0;
                transform: translateX(100%);
            }
            #sidebar.open {
                transform: translateX(0);
            }
            #toggle-sidebar-btn {
                display: flex;
            }
        }
    </style>
</head>
<body>

    <div class="overlay-bg" id="overlay" onclick="closeSettings()"></div>

    <!-- SETTINGS PANEL DIALOG -->
    <div id="settings-dialog">
        <h3 style="margin-top:0; color:var(--primary); font-weight:900;">تنظیمات نمایش متن</h3>
        
        <div class="setting-row">
            <span class="setting-label">پوسته رنگی:</span>
            <div class="theme-choices">
                <button class="theme-choice-btn" onclick="applyTheme('light')" style="background-color:#ffffff; color:#0f172a;">روشن</button>
                <button class="theme-choice-btn" onclick="applyTheme('sepia')" style="background-color:#fdf6e3; color:#586e75;">سپیا</button>
                <button class="theme-choice-btn" onclick="applyTheme('dark')" style="background-color:#090d16; color:#f3f4f6;">تاریک</button>
            </div>
        </div>

        <div class="setting-row">
            <span class="setting-label">اندازه قلم متن خلاصه:</span>
            <div class="size-row">
                <button class="size-btn" onclick="changeFontSize(-2)">A- کوچکتر</button>
                <button class="size-btn" onclick="changeFontSize(2)">A+ بزرگتر</button>
            </div>
        </div>

        <div class="setting-row">
            <span class="setting-label">فاصله خطوط:</span>
            <div class="size-row">
                <button class="size-btn" onclick="changeLineHeight(-0.1)">- فشرده‌تر</button>
                <button class="size-btn" onclick="changeLineHeight(0.1)">+ فاصله‌دار</button>
            </div>
        </div>

        <button class="toolbar-btn" style="width:100%; margin-top:12px;" onclick="closeSettings()">تایید و ذخیره</button>
    </div>

    <!-- SIDEBAR NAVIGATION -->
    <div id="sidebar">
        <div class="search-area">
            <input type="text" class="search-input" placeholder="جستجوی کلیدواژه خلاصه..." id="search-box-input" oninput="doSearch()">
        </div>

        <!-- TABLE OF CONTENTS -->
        <div class="toc-scroller" id="toc-scroller-panel">
            <div id="toc-list"></div>
        </div>

        <!-- SEARCH RESULTS BLOCK -->
        <div id="search-results-panel"></div>
    </div>

    <!-- MAIN VIEWER PLATFORM -->
    <div id="main-viewer">
        <div id="top-toolbar">
            <button class="toolbar-btn" id="toggle-sidebar-btn" onclick="toggleSidebarMenu()">☰ فهرست</button>
            <div class="title-display" id="document-header-title">$fileName</div>
            <div class="controls-group">
                <button class="toolbar-btn" onclick="openSettings()">⚙ تنظیمات</button>
            </div>
        </div>

        <div id="reader-container">
            <div id="reader-content"></div>
        </div>

        <div class="footer-nav">
            <button class="toolbar-btn" onclick="prevChapter()" id="prev-btn">◀ صفحه قبل</button>
            <div id="footer-step-indicator" style="font-size:12px; font-weight:700;">۱ / ۱</div>
            <button class="toolbar-btn" onclick="nextChapter()" id="next-btn">صفحه بعد ▶</button>
        </div>
    </div>

    <script>
        const BOOK = $bookJson;
        const LS_PREFIX = $lsPrefix;

        let activeSecIndex = 0;
        let activeChapIndex = 0;
        let fontSize = 18;
        let lineHeight = 1.85;

        // Load config from Local Storage
        function loadPrefs() {
            const savedTheme = localStorage.getItem(LS_PREFIX + "_theme") || "light";
            applyTheme(savedTheme);

            const savedSize = localStorage.getItem(LS_PREFIX + "_fontSize");
            if (savedSize) {
                fontSize = parseInt(savedSize);
                applyTextSettings();
            }

            const savedLineH = localStorage.getItem(LS_PREFIX + "_lineHeight");
            if (savedLineH) {
                lineHeight = parseFloat(savedLineH);
                applyTextSettings();
            }

            const savedSec = localStorage.getItem(LS_PREFIX + "_activeSec");
            const savedChap = localStorage.getItem(LS_PREFIX + "_activeChap");
            if (savedSec && savedChap) {
                activeSecIndex = parseInt(savedSec);
                activeChapIndex = parseInt(savedChap);
            }
        }

        function applyTheme(t) {
            document.body.setAttribute('data-theme', t);
            localStorage.setItem(LS_PREFIX + "_theme", t);
        }

        function applyTextSettings() {
            const content = document.getElementById('reader-content');
            content.style.fontSize = fontSize + "px";
            content.style.lineHeight = lineHeight;
            localStorage.setItem(LS_PREFIX + "_fontSize", fontSize);
            localStorage.setItem(LS_PREFIX + "_lineHeight", lineHeight);
        }

        function changeFontSize(delta) {
            fontSize = Math.min(36, Math.max(12, fontSize + delta));
            applyTextSettings();
        }

        function changeLineHeight(delta) {
            lineHeight = Math.min(2.5, Math.max(1.2, lineHeight + delta));
            applyTextSettings();
        }

        function openSettings() {
            document.getElementById('settings-dialog').style.display = 'block';
            document.getElementById('overlay').style.display = 'block';
        }

        function closeSettings() {
            document.getElementById('settings-dialog').style.display = 'none';
            document.getElementById('overlay').style.display = 'none';
        }

        function toggleSidebarMenu() {
            const sidebar = document.getElementById('sidebar');
            sidebar.classList.toggle('open');
        }

        // Generate TOC
        function renderTOC() {
            const listDiv = document.getElementById('toc-list');
            listDiv.innerHTML = "";

            BOOK.sections.forEach((sec, sIdx) => {
                const secHeader = document.createElement('div');
                secHeader.className = 'sec-header';
                secHeader.textContent = sec.title;
                listDiv.appendChild(secHeader);

                sec.chapters.forEach((chap, cIdx) => {
                    const item = document.createElement('div');
                    item.className = 'chap-item';
                    if (sIdx === activeSecIndex && cIdx === activeChapIndex) {
                        item.classList.add('chap-active');
                    }
                    item.innerHTML = '<span>' + chap.title + '</span>';
                    item.onclick = () => {
                        selectChapter(sIdx, cIdx);
                        if (window.innerWidth <= 768) {
                            toggleSidebarMenu();
                        }
                    };
                    listDiv.appendChild(item);
                });
            });
        }

        function selectChapter(sIdx, cIdx) {
            if (sIdx < 0 || sIdx >= BOOK.sections.length) return;
            const sec = BOOK.sections[sIdx];
            if (cIdx < 0 || cIdx >= sec.chapters.length) return;

            activeSecIndex = sIdx;
            activeChapIndex = cIdx;

            // Save state
            localStorage.setItem(LS_PREFIX + "_activeSec", activeSecIndex);
            localStorage.setItem(LS_PREFIX + "_activeChap", activeChapIndex);

            // Render chapter body content
            const activeChap = sec.chapters[cIdx];
            document.getElementById('reader-content').innerHTML = '<h2>' + activeChap.title + '</h2>' + activeChap.html;
            
            // Scroll reader back to the top
            document.getElementById('reader-container').scrollTop = 0;

            // Update indices indicators
            updatePaginationUI();
            renderTOC();
        }

        function updatePaginationUI() {
            // Calculate flat indicator steps
            let totalChaps = 0;
            let currentChapFlat = 0;
            let flatIdx = 0;

            BOOK.sections.forEach((sec, sIdx) => {
                sec.chapters.forEach((chap, cIdx) => {
                    totalChaps++;
                    if (sIdx === activeSecIndex && cIdx === activeChapIndex) {
                        currentChapFlat = totalChaps;
                    }
                });
            });

            document.getElementById('footer-step-indicator').textContent = currentChapFlat + " / " + totalChaps;
        }

        function nextChapter() {
            const currentSec = BOOK.sections[activeSecIndex];
            if (activeChapIndex + 1 < currentSec.chapters.length) {
                selectChapter(activeSecIndex, activeChapIndex + 1);
            } else if (activeSecIndex + 1 < BOOK.sections.length) {
                selectChapter(activeSecIndex + 1, 0);
            }
        }

        function prevChapter() {
            if (activeChapIndex > 0) {
                selectChapter(activeSecIndex, activeChapIndex - 1);
            } else if (activeSecIndex > 0) {
                const prevSec = BOOK.sections[activeSecIndex - 1];
                selectChapter(activeSecIndex - 1, prevSec.chapters.length - 1);
            }
        }

        // Arabic/Persian normalise function matching local logic
        function normaliseAr(txt) {
            return txt
                .replace(/ي/g, "ی")
                .replace(/ك/g, "ک")
                .replace(/ـ/g, "")
                .replace(/[\u064B-\u065F\u0670]/g, "")
                .toLowerCase()
                .trim();
        }

        function doSearch() {
            const query = normaliseAr(document.getElementById('search-box-input').value);
            const tocScroller = document.getElementById('toc-scroller-panel');
            const resultsPanel = document.getElementById('search-results-panel');

            if (!query) {
                tocScroller.style.display = 'block';
                resultsPanel.style.display = 'none';
                return;
            }

            tocScroller.style.display = 'none';
            resultsPanel.style.display = 'block';
            resultsPanel.innerHTML = "";

            let found = false;
            BOOK.sections.forEach((sec, sIdx) => {
                sec.chapters.forEach((chap, cIdx) => {
                    if (chap.search.includes(query)) {
                        found = true;
                        const srBlock = document.createElement('div');
                        srBlock.className = 'sr-item';
                        srBlock.onclick = () => {
                            selectChapter(sIdx, cIdx);
                            if (window.innerWidth <= 768) {
                                toggleSidebarMenu();
                            }
                        };
                        
                        const title = document.createElement('div');
                        title.className = 'sr-title';
                        title.textContent = sec.title + " » " + chap.title;
                        
                        const snip = document.createElement('div');
                        snip.className = 'sr-snippet';
                        
                        // Extract snippet centered on matched text if possible
                        const normPlain = normaliseAr(chap.plain);
                        const matchIdx = normPlain.indexOf(query);
                        let snippetText = chap.plain.substring(0, 160) + "...";
                        if (matchIdx !== -1) {
                            const start = Math.max(0, matchIdx - 60);
                            const end = Math.min(chap.plain.length, matchIdx + query.length + 85);
                            snippetText = (start > 0 ? "..." : "") + chap.plain.substring(start, end) + (end < chap.plain.length ? "..." : "");
                        }

                        snip.textContent = snippetText;
                        srBlock.appendChild(title);
                        srBlock.appendChild(snip);
                        resultsPanel.appendChild(srBlock);
                    }
                });
            });

            if (!found) {
                resultsPanel.innerHTML = "<div style='text-align:center; padding:16px; font-size:13px; color:var(--text-secondary);'>هیچ موردی منطبق بر جستجوی شما یافت نشد.</div>";
            }
        }

        // Initial setup
        loadPrefs();
        renderTOC();
        selectChapter(activeSecIndex, activeChapIndex);
    </script>
</body>
</html>
""".trimIndent()
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyLogsDao.clearHistory()
        }
    }
}
