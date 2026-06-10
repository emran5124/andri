package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.UiState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    MainScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val apiKey by viewModel.apiKey.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val selectedFileSize by viewModel.selectedFileSize.collectAsState()
    val outputFileName by viewModel.outputFileName.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val summaryLanguage by viewModel.summaryLanguage.collectAsState()
    val summaryStyle by viewModel.summaryStyle.collectAsState()

    var showKeyVisible by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.selectFile(context, uri)
        }
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Elegant Centered Header
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "خلاصه‌ساز هوشمند جمنای",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = 0.5.sp
                            )
                        )
                        Text(
                            text = "Gemini TXT Summarizer",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.secondary,
                                letterSpacing = 0.8.sp
                            )
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Section 1: API Key Input
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("api_key_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "API Key Icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "تنظیمات کلید API Key",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f)
                                )
                                if (BuildConfig.GEMINI_API_KEY.isNotEmpty() && BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY") {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "پیش‌فرض فعال",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { viewModel.updateApiKey(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("api_key_input"),
                                label = { Text("Gemini API Key") },
                                placeholder = { Text("AI Studio KEY...") },
                                visualTransformation = if (showKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showKeyVisible = !showKeyVisible }) {
                                        Icon(
                                            imageVector = if (showKeyVisible) Icons.Default.Lock else Icons.Default.Settings,
                                            contentDescription = "Toggle Key Visibility"
                                        )
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                )
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "کلید شما در دستگاه شما محفوظ می‌ماند و مستقیماً به سرور گوگل ارسال می‌شود.",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.End,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Section 2: TXT File Selection
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("file_selection_card"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = borderStroke()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (selectedFileName == null) {
                                // Empty state
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                            RoundedCornerShape(16.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = "Document Empty Icon",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = "فایل TXT را برای خلاصه‌سازی انتخاب کنید",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "یک فایل متنی ساده (txt) وارد کنید تا روند خلاصه آغاز گردد.",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    ),
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { filePickerLauncher.launch("text/plain") },
                                    modifier = Modifier
                                        .testTag("select_file_button")
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Add Icon")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("انتخاب فایل متنی")
                                }
                            } else {
                                // File Chosen Screen
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Done,
                                        contentDescription = "Selected Icon",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedFileName ?: "",
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                            maxLines = 1
                                        )
                                        val sizeKb = selectedFileSize?.let { " (${String.format("%.1f", it / 1024.0)} KB)" } ?: ""
                                        Text(
                                            text = "فایل آماده خلاصه‌سازی است$sizeKb",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.clearSelectedFile() },
                                        modifier = Modifier.testTag("clear_file_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Clear File",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 3: AI Customization Rules (Only if file selected to avoid UI noise)
                if (selectedFileName != null) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_card"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = borderStroke()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "تنظیمات خلاصه",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(14.dp))

                                // Out filename
                                OutlinedTextField(
                                    value = outputFileName,
                                    onValueChange = { viewModel.updateOutputFileName(it) },
                                    label = { Text("نام فایل خروجی خلاصه") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp)
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                // Target Language Options
                                Text(
                                    text = "زبان خروجی:",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val languages = listOf("Persian" to "فارسی", "English" to "English", "Original" to "همانند منبع")
                                    languages.forEach { (code, name) ->
                                        val isSelected = summaryLanguage == code
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = { viewModel.updateSummaryLanguage(code) },
                                            label = { Text(name) },
                                            modifier = Modifier.weight(1f),
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Style Options
                                Text(
                                    text = "قالب و ساختار خلاصه:",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val styles = listOf(
                                        "Bulleted Outline" to "لیست و نکات برجسته (Bulleted List)",
                                        "Short Summary" to "خلاصه کوتاه و روان (Short Essay)",
                                        "Detailed Summary" to "خلاصه دقیق با جزئیات کامل (Detailed Guide)"
                                    )
                                    styles.forEach { (styleCode, styleName) ->
                                        val isSelected = summaryStyle == styleCode
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.updateSummaryStyle(styleCode) }
                                                .clip(RoundedCornerShape(8.dp))
                                                .border(
                                                    width = if (isSelected) 2.dp else 1.dp,
                                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(
                                                        alpha = 0.2f
                                                    ),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(
                                                        alpha = 0.2f
                                                    ) else MaterialTheme.colorScheme.surface
                                                )
                                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = isSelected,
                                                    onClick = { viewModel.updateSummaryStyle(styleCode) },
                                                    colors = RadioButtonDefaults.colors(
                                                        selectedColor = MaterialTheme.colorScheme.primary
                                                    )
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = styleName,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Section 4: Process Trigger button
                if (selectedFileName != null && uiState !is UiState.Loading) {
                    item {
                        Button(
                            onClick = { viewModel.summarizeAndSave(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .testTag("summarize_button"),
                            shape = RoundedCornerShape(16.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Run Summarizer Icon"
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "شروع خلاصه‌سازی و ذخیره فایل",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }

                // Section 5: Current Process State & Feedback Output
                item {
                    AnimatedContent(
                        targetState = uiState,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "StateAnimation"
                    ) { state ->
                        when (state) {
                            is UiState.Idle -> Spacer(modifier = Modifier.height(1.dp))
                            is UiState.Loading -> {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("loading_card"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = borderStroke()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        CircularProgressIndicator(
                                            color = MaterialTheme.colorScheme.primary,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier.size(44.dp)
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = state.message,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            ),
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "لطفاً تامل بفرمایید، در حال ارتباط با سرورهای هوش‌مصنوعی جمنای...",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                            is UiState.Success -> {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("success_card"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = borderStroke()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Done,
                                                contentDescription = "Success",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(28.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "خلاصه‌سازی با موفقیت انجام شد!",
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                                Text(
                                                    text = "فایل ذخیره شد در: پوشه Download",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        // Saved file indicator info container
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            border = borderStroke(alpha = 0.15f)
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.List,
                                                    contentDescription = "File Icon",
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = state.savedPath,
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold
                                                    ),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                IconButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(state.summary))
                                                        Toast.makeText(context, "خلاصه در کلیپ‌بورد کپی شد", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Share,
                                                        contentDescription = "Copy text",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Text(
                                            text = "پیش‌نمایش خلاصه:",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    MaterialTheme.colorScheme.surface,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .border(
                                                    width = 1.dp,
                                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(12.dp)
                                        ) {
                                            Text(
                                                text = state.summary,
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    lineHeight = 18.sp
                                                ),
                                                textAlign = if (summaryLanguage == "Persian") TextAlign.Right else TextAlign.Left,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                            is UiState.Error -> {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("error_card"),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    border = borderStroke(colorColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Warning,
                                            contentDescription = "Error Icon",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = state.error,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun borderStroke(
    alpha: Float = 0.12f,
    colorColor: androidx.compose.ui.graphics.Color? = null
) = androidx.compose.foundation.BorderStroke(
    width = 1.dp,
    color = colorColor ?: MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
)
