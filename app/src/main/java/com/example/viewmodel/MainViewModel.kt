package com.example.viewmodel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.api.Content
import com.example.api.GenerateContentRequest
import com.example.api.Part
import com.example.api.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed interface UiState {
    object Idle : UiState
    data class Loading(val message: String) : UiState
    data class Success(val summary: String, val savedPath: String) : UiState
    data class Error(val error: String) : UiState
}

class MainViewModel : ViewModel() {

    private val _apiKey = MutableStateFlow(BuildConfig.GEMINI_API_KEY.ifBlank { "" })
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedFileUri = MutableStateFlow<Uri?>(null)
    val selectedFileUri: StateFlow<Uri?> = _selectedFileUri.asStateFlow()

    private val _selectedFileName = MutableStateFlow<String?>(null)
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()

    private val _selectedFileSize = MutableStateFlow<Long?>(null)
    val selectedFileSize: StateFlow<Long?> = _selectedFileSize.asStateFlow()

    private val _outputFileName = MutableStateFlow("")
    val outputFileName: StateFlow<String> = _outputFileName.asStateFlow()

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _summaryLanguage = MutableStateFlow("Persian")
    val summaryLanguage: StateFlow<String> = _summaryLanguage.asStateFlow()

    private val _summaryStyle = MutableStateFlow("Bulleted Outline")
    val summaryStyle: StateFlow<String> = _summaryStyle.asStateFlow()

    private var fileContent: String = ""

    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    fun updateOutputFileName(name: String) {
        _outputFileName.value = name
    }

    fun updateSummaryLanguage(lang: String) {
        _summaryLanguage.value = lang
    }

    fun updateSummaryStyle(style: String) {
        _summaryStyle.value = style
    }

    fun selectFile(context: Context, uri: Uri) {
        _selectedFileUri.value = uri
        viewModelScope.launch {
            try {
                _uiState.value = UiState.Loading("Reading file...")
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
                    _uiState.value = UiState.Error("Selected file is empty")
                } else {
                    fileContent = content
                    _uiState.value = UiState.Idle
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Error reading file: ${e.message}")
            }
        }
    }

    fun clearSelectedFile() {
        _selectedFileUri.value = null
        _selectedFileName.value = null
        _selectedFileSize.value = null
        _outputFileName.value = ""
        fileContent = ""
        _uiState.value = UiState.Idle
    }

    fun summarizeAndSave(context: Context) {
        val key = _apiKey.value.trim()
        val text = fileContent
        val outName = _outputFileName.value.trim().ifBlank { "${_selectedFileName.value?.substringBeforeLast(".") ?: "document"}_summary.txt" }
        val lang = _summaryLanguage.value
        val style = _summaryStyle.value

        if (key.isEmpty()) {
            _uiState.value = UiState.Error("Please enter a valid Gemini API Key")
            return
        }
        if (text.isEmpty()) {
            _uiState.value = UiState.Error("Please select a non-empty .txt file first")
            return
        }

        viewModelScope.launch {
            _uiState.value = UiState.Loading("Summarizing with Gemini AI...")
            try {
                // Build dynamic instructions
                val prompt = buildString {
                    append("You are an expert document summarizing assistant.\n")
                    append("Please analyze the document provided below and write a highly polished, helpful summary.\n\n")
                    
                    when (lang) {
                        "Persian" -> append("CRITICAL RULE: The entire summary must be written in Persian (Farsi / فارسی).\n")
                        "English" -> append("CRITICAL RULE: The entire summary must be written in English.\n")
                        else -> append("CRITICAL RULE: The entire summary must be written in the same language as the original text.\n")
                    }

                    when (style) {
                        "Bulleted Outline" -> append("Format style: List the key points, main arguments, and action items in a well-structured bullet-point list.\n")
                        "Short Summary" -> append("Format style: A brief, high-level summary of 1-2 paragraphs capturing the essence of the document.\n")
                        "Detailed Summary" -> append("Format style: A comprehensive structured outline of sections, background, logic and conclusions.\n")
                    }

                    append("\n--- START OF DOCUMENT ---\n")
                    append(text)
                    append("\n--- END OF DOCUMENT ---\n")
                }

                val request = GenerateContentRequest(
                    contents = listOf(Content(parts = listOf(Part(text = prompt))))
                )

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.service.generateContent(
                        model = "gemini-3.5-flash",
                        apiKey = key,
                        request = request
                    )
                }

                val summaryText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                if (summaryText.isNullOrBlank()) {
                    _uiState.value = UiState.Error("Gemini generated an empty response. Please check your API key and try again.")
                    return@launch
                }

                _uiState.value = UiState.Loading("Saving summary to Downloads...")

                val savedPathMessage = withContext(Dispatchers.IO) {
                    saveTextToDownloads(context, outName, summaryText)
                }

                _uiState.value = UiState.Success(summaryText, savedPathMessage)

            } catch (e: Exception) {
                _uiState.value = UiState.Error("Error: ${e.localizedMessage ?: e.message}")
            }
        }
    }

    private fun saveTextToDownloads(context: Context, fileName: String, text: String): String {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(text.toByteArray())
                    outputStream.flush()
                }
                return fileName
            } else {
                throw Exception("Failed to insert file to MediaStore")
            }
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val file = File(downloadsDir, fileName)
            FileOutputStream(file).use { out ->
                out.write(text.toByteArray())
                out.flush()
            }
            return file.name
        }
    }
}
