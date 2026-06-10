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
            promptTemplateDao.insertTemplate(
                PromptTemplate(title = title, promptContent = prompt)
            )
        }
    }

    fun deletePromptTemplate(id: Int) {
        viewModelScope.launch {
            promptTemplateDao.deleteTemplateById(id)
        }
    }

    fun duplicatePromptTemplate(template: PromptTemplate) {
        viewModelScope.launch {
            promptTemplateDao.insertTemplate(
                PromptTemplate(title = "${template.title} (کپی)", promptContent = template.promptContent)
            )
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
                fileUriString = _selectedFileUri.value?.toString() ?: ""
            )

            activeSessionDao.insertActiveSession(session)
            activeKeyIndex = 0
            activeModelIndex = 0
            currentRetryCount = 0

            runProcessingLoop(context, session, customPromptText)
        }
    }

    fun resumeSession(context: Context, customPromptText: String) {
        viewModelScope.launch {
            val session = activeSessionDao.getActiveSession()
            if (session != null) {
                runProcessingLoop(context, session, customPromptText)
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
    fun manualRetry(context: Context, customPromptText: String) {
        viewModelScope.launch {
            val session = activeSessionDao.getActiveSession()
            if (session != null) {
                currentRetryCount = 0
                runProcessingLoop(context, session, customPromptText)
            }
        }
    }

    // Advance dynamically either model or key
    fun proceedToNextFallback(context: Context, customPromptText: String) {
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
                runProcessingLoop(context, session, customPromptText)
            } else if (activeKeyIndex + 1 < keys.size) {
                // Next key first model
                activeKeyIndex++
                activeModelIndex = 0
                currentRetryCount = 0
                runProcessingLoop(context, session, customPromptText)
            } else {
                _processingState.value = ProcessingState.Error("تمامی کلیدها و مدل‌های موجود بررسی شده و با شکست مواجه شدند.")
            }
        }
    }

    // Force selection manual fallback key & model
    fun manualForceFallback(context: Context, customPromptText: String, keyIdx: Int, modelIdx: Int) {
        viewModelScope.launch {
            val session = activeSessionDao.getActiveSession() ?: return@launch
            activeKeyIndex = keyIdx
            activeModelIndex = modelIdx
            currentRetryCount = 0
            runProcessingLoop(context, session, customPromptText)
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

    private fun runProcessingLoop(context: Context, startSession: ActiveSession, customPromptText: String) {
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
                    customPrompt = customPromptText
                )

                if (responseResult.isSuccess) {
                    // Success! append and update DB
                    val responseSummary = responseResult.getOrThrow()
                    summaries.add(responseSummary)

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

    fun generateHtmlFromSummaries(fileName: String, summaries: List<String>): String {
        val escapedSummariesJson = JsonSerializer.serializeStrings(summaries)
        
        return """
<!DOCTYPE html>
<html lang="fa" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>خلاصه سند: $fileName</title>
    <style>
        @import url('https://fonts.googleapis.com/css2?family=Vazirmatn:wght@300;400;700;900&display=swap');
        
        :root {
            --bg-color: #f7f9fc;
            --card-color: #ffffff;
            --text-color: #1a202c;
            --primary-color: #4f46e5;
            --border-color: #e2e8f0;
            --muted-color: #718096;
            
            --tc-green: #2e7d32;
            --tc-red: #c62828;
            --tc-yellow: #f9a825;
            --tc-blue: #1565c0;
            
            --hl-green: #e8f5e9;
            --hl-red: #ffebee;
            --hl-yellow: #fffde7;
            --hl-blue: #e3f2fd;
        }

        [data-theme="dark"] {
            --bg-color: #0f172a;
            --card-color: #1e293b;
            --text-color: #f8fafc;
            --primary-color: #818cf8;
            --border-color: #334155;
            --muted-color: #94a3b8;
            
            --tc-green: #81c784;
            --tc-red: #e57373;
            --tc-yellow: #ffd54f;
            --tc-blue: #64b5f6;
            
            --hl-green: #1b5e20;
            --hl-red: #b71c1c;
            --hl-yellow: #f57f17;
            --hl-blue: #0d47a1;
        }

        * {
            box-sizing: border-box;
            transition: background-color 0.3s, color 0.3s, border-color 0.3s;
        }

        body {
            font-family: 'Vazirmatn', sans-serif;
            background-color: var(--bg-color);
            color: var(--text-color);
            margin: 0;
            padding: 0;
            line-height: 1.8;
            font-size: 16px;
        }

        .container {
            max-width: 800px;
            margin: 0 auto;
            padding: 40px 20px;
        }

        header {
            margin-bottom: 40px;
            text-align: center;
            border-bottom: 2px solid var(--border-color);
            padding-bottom: 20px;
            position: relative;
        }

        h1 {
            font-weight: 900;
            font-size: 32px;
            color: var(--primary-color);
            margin: 0 0 10px 0;
        }

        .meta {
            color: var(--muted-color);
            font-size: 14px;
        }

        .controls {
            position: fixed;
            top: 20px;
            left: 20px;
            z-index: 1000;
            display: flex;
            gap: 10px;
        }

        .btn {
            background-color: var(--card-color);
            color: var(--text-color);
            border: 1px solid var(--border-color);
            padding: 10px 15px;
            border-radius: 50px;
            cursor: pointer;
            font-weight: 700;
            box-shadow: 0 4px 6px -1px rgba(0,0,0,0.1);
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 14px;
        }

        .btn:hover {
            border-color: var(--primary-color);
            transform: translateY(-2px);
        }

        .section-card {
            background-color: var(--card-color);
            border: 1px solid var(--border-color);
            border-radius: 16px;
            padding: 24px;
            margin-bottom: 30px;
            box-shadow: 0 10px 15px -3px rgba(0, 0, 0, 0.05);
        }

        .section-header {
            font-size: 14px;
            font-weight: 700;
            color: var(--primary-color);
            border-bottom: 1px dashed var(--border-color);
            padding-bottom: 10px;
            margin-bottom: 15px;
            display: flex;
            justify-content: space-between;
        }

        .summary-body {
            font-size: 16px;
            white-space: pre-line;
        }

        /* Highlight classes */
        .hl-green { background-color: var(--hl-green); padding: 2px 6px; border-radius: 4px; font-weight: bold; }
        .hl-red { background-color: var(--hl-red); padding: 2px 6px; border-radius: 4px; font-weight: bold; }
        .hl-yellow { background-color: var(--hl-yellow); padding: 2px 6px; border-radius: 4px; font-weight: bold; }
        .hl-blue { background-color: var(--hl-blue); padding: 2px 6px; border-radius: 4px; font-weight: bold; }

        /* Text colors */
        .tc-green { color: var(--tc-green); font-weight: bold; }
        .tc-red { color: var(--tc-red); font-weight: bold; }
        .tc-yellow { color: var(--tc-yellow); font-weight: bold; }
        .tc-blue { color: var(--tc-blue); font-weight: bold; }

        strong {
            font-weight: 700;
            color: var(--primary-color);
        }
    </style>
</head>
<body>

    <div class="controls">
        <button class="btn" onclick="toggleTheme()" id="themeBtn">🌓 تغییر پوسته</button>
    </div>

    <div class="container">
        <header>
            <h1>خلاصه سند هوشمند</h1>
            <div class="meta">نام فایل اصلی: $fileName | تعداد بخش‌ها: ${summaries.size}</div>
        </header>

        <div id="content-list"></div>
    </div>

    <script>
        const summaries = $escapedSummariesJson;

        function toggleTheme() {
            const body = document.body;
            const theme = body.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';
            body.setAttribute('data-theme', theme);
            localStorage.setItem('theme', theme);
        }

        // Restore saved theme preference
        const savedTheme = localStorage.getItem('theme') || 'light';
        document.body.setAttribute('data-theme', savedTheme);

        function parseStyles(text) {
            let html = text
                .replace(/&/g, "&amp;")
                .replace(/</g, "&lt;")
                .replace(/>/g, "&gt;");

            let prev;
            let count = 0;
            do {
                prev = html;
                
                // Highlights double
                html = html.replace(/\[\[([^\]]+)\]\]/g, '<span class="hl-green">${'$'}1</span>');
                html = html.replace(/\(\(([^)]+)\)\)/g, '<span class="hl-red">${'$'}1</span>');
                html = html.replace(/««([^»]+)»»/g, '<span class="hl-yellow">${'$'}1</span>');
                html = html.replace(/&lt;&lt;((?:(?!&gt;&gt;).)+)&gt;&gt;/g, '<span class="hl-blue">${'$'}1</span>');

                // Text Colors single
                html = html.replace(/\[([^\][()«»]+)\]/g, '<span class="tc-green">${'$'}1</span>');
                html = html.replace(/¥¥([^¥]+)¥¥/g, '<span class="tc-red">${'$'}1</span>');
                html = html.replace(/«([^«»]+)»/g, '<span class="tc-yellow">${'$'}1</span>');
                html = html.replace(/&lt;((?:(?!&gt;).)+)&gt;/g, '<span class="tc-blue">${'$'}1</span>');

                // bold text **text**
                html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>${'$'}1</strong>');
                
                count++;
            } while (html !== prev && count < 10);

            return html;
        }

        const listDiv = document.getElementById('content-list');
        summaries.forEach((summaryText, index) => {
            const card = document.createElement('div');
            card.className = 'section-card';
            
            const header = document.createElement('div');
            header.className = 'section-header';
            header.innerHTML = '<span>بخش ' + (index + 1) + ' از ' + summaries.length + '</span>';
            
            const body = document.createElement('div');
            body.className = 'summary-body';
            body.innerHTML = parseStyles(summaryText);
            
            card.appendChild(header);
            card.appendChild(body);
            listDiv.appendChild(card);
        });
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
