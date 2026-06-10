package com.example

import android.app.Activity
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.api.JsonSerializer
import com.example.db.ApiKeyConfig
import com.example.db.AppSettings
import com.example.db.HistoryLog
import com.example.db.ModelConfig
import com.example.db.PromptTemplate
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.MainViewModel
import com.example.viewmodel.ProcessingState
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    var currentTab by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val activeSession by viewModel.activeSessionFlow.collectAsState()
    var showResumeDialog by remember { mutableStateOf(false) }
    var hasCheckedResume by remember { mutableStateOf(false) }

    // On start, if there is an active session, prompt user
    LaunchedEffect(activeSession) {
        if (!hasCheckedResume && activeSession != null && activeSession?.isCompleted == false && viewModel.processingState.value is ProcessingState.Idle) {
            showResumeDialog = true
            hasCheckedResume = true
        }
    }

    if (showResumeDialog && activeSession != null) {
        AlertDialog(
            onDismissRequest = { showResumeDialog = false },
            title = {
                Text(
                    text = "بازیابی فعالیت قبلی",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Text(
                    text = "یک پردازش ناتمام مربوط به فایل «${activeSession?.originalFileName}» یافت شد. آیا مایل به ادامه هستید یا می‌خواهید از نو شروع کنید؟",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResumeDialog = false
                        viewModel.resumeSession(context)
                    }
                ) {
                    Text("ادامه پردازش قبلی")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showResumeDialog = false
                        viewModel.abortSession()
                        Toast.makeText(context, "پردازش قبلی حذف شد.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("شروع مجدد", color = MaterialTheme.colorScheme.error)
                }
            }
        )
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                            text = "Gemini Segment Summarizer Pro",
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

            // Body Area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when (currentTab) {
                    0 -> ProcessingTab(viewModel = viewModel)
                    1 -> ApiKeysTab(viewModel = viewModel)
                    2 -> PromptTemplatesTab(viewModel = viewModel)
                    3 -> HistoryTab(viewModel = viewModel)
                    4 -> SettingsTab(viewModel = viewModel)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            // Tab navigation
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = "خانه") },
                    label = { Text("پردازش", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.Lock, contentDescription = "کلیدها") },
                    label = { Text("کلیدهای API", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    icon = { Icon(Icons.Default.Menu, contentDescription = "پرامپت‌ها") },
                    label = { Text("پرامپت‌ها", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = currentTab == 3,
                    onClick = { currentTab = 3 },
                    icon = { Icon(Icons.Default.List, contentDescription = "تاریخچه") },
                    label = { Text("تاریخچه", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                )
                NavigationBarItem(
                    selected = currentTab == 4,
                    onClick = { currentTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "تنظیمات") },
                    label = { Text("تنظیمات", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                )
            }
        }
    }
}

// ================= PROCESSING TAB =================
@Composable
fun ProcessingTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val selectedFileSize by viewModel.selectedFileSize.collectAsState()
    val outputFileName by viewModel.outputFileName.collectAsState()
    val promptTemplates by viewModel.promptTemplatesFlow.collectAsState()
    val selectedPromptId by viewModel.selectedPromptId.collectAsState()
    val processingState by viewModel.processingState.collectAsState()

    var previewTxtContent by remember { mutableStateOf<String?>(null) }
    var previewHtmlContent by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.selectFile(context, uri)
        }
    }

    // Resolve prompt text dynamically based on selection
    var customPromptOverride by remember { mutableStateOf("متن ورودی را کاملاً خلاصه کن.") }
    val activePromptTemplate = promptTemplates.find { it.id == selectedPromptId }

    LaunchedEffect(selectedPromptId, promptTemplates) {
        activePromptTemplate?.let {
            customPromptOverride = it.promptContent
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Step 1: File selection Card
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
                                imageVector = Icons.Default.AddCircle,
                                contentDescription = "Select File Icon",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "فایل متن ورودی (.txt) را انتخاب کنید",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "با انتخاب فایل، سیستم متن را بخش‌بخش کرده و با فواصل منظم خلاصه‌سازی می‌کند.",
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
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("انتخاب فایل متنی")
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Success Selection",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedFileName ?: "",
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val sizeKb = selectedFileSize?.let { " (${String.format("%.1f", it / 1024.0)} KB)" } ?: ""
                                Text(
                                    text = "فایل آماده تجزیه است$sizeKb",
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
                                    contentDescription = "Clear File Selection",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        // File outputs config and Prompts
        if (selectedFileName != null && processingState !is ProcessingState.Running) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = borderStroke()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "نام فایل خروجی خلاصه:",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = outputFileName,
                            onValueChange = { viewModel.updateOutputFileName(it) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "انتخابِ پرامپت (دستورالعمل):",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Right
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (promptTemplates.isEmpty()) {
                            Text(
                                text = "هیچ پرامپت سفارشی یافت نشد. می‌توانید از تب پرامپت‌ها نمونه اضافه نمایید.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Right
                            )
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                promptTemplates.forEach { pt ->
                                    val isSelected = pt.id == selectedPromptId
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.selectPrompt(pt.id) },
                                        label = { Text(pt.title) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Prompt content template editor box
                        OutlinedTextField(
                            value = customPromptOverride,
                            onValueChange = { customPromptOverride = it },
                            label = { Text("متن پرامپت (قابل ویرایش مستقیم)") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 4
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.startNewSession(context, customPromptOverride) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("summarize_button"),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run Loop")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("شروع فرآیند خلاصه‌سازی بخش‌به‌بخش", fontWeight = FontWeight.Bold)
                }
            }
        }

        // Monitoring current state outputs
        item {
            AnimatedContent(
                targetState = processingState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "ProgressAnimation"
            ) { state ->
                when (state) {
                    is ProcessingState.Idle -> Spacer(modifier = Modifier.height(1.dp))

                    is ProcessingState.Loading -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
                                CircularProgressIndicator(modifier = Modifier.size(44.dp))
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(state.message, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    is ProcessingState.Running -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.12f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = borderStroke()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 3.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = "بخش ${state.currentSection} از ${state.totalSections} در حال پردازش...",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            text = "فایل فعال: ${state.originalFileName}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                LinearProgressIndicator(
                                    progress = { state.currentSection.toFloat() / state.totalSections },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = "کلید فعال: ${state.activeKeyTitle}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "مدل فعال: ${state.activeModelTitle}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        Text(
                                            text = "حداکثر ریرتای باقی‌مانده: ${state.retriesLeft}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = state.statusMessage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = { viewModel.abortSession() },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Stop")
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("توقف و لغو پردازش")
                                }
                            }
                        }
                    }

                    is ProcessingState.VpnBlockError -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = borderStroke(colorColor = MaterialTheme.colorScheme.error)
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = "VPN Lock", tint = MaterialTheme.colorScheme.error)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("خطای ممنوعیت IP (۴۰۳) - نیاز به فیلترشکن", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = state.errorMsg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = { viewModel.manualRetry(context) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("فیلترشکن را تغییر دادم، ادامه بده")
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = { viewModel.abortSession() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("لغو کامل فرآیند", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    is ProcessingState.WaitingForUserDecision -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            border = borderStroke(colorColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "ارور در خلاصه‌سازی بخش ${state.sectionIndex + 1}",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "پیغام خطای سیستم: ${state.errorMsg}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    textAlign = TextAlign.Right
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Button(
                                    onClick = { viewModel.manualRetry(context) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("تلاش مجدد برای همین مدل")
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                Button(
                                    onClick = { viewModel.proceedToNextFallback(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("نفیذ و رفتن به کلید یا مدل بعدی در نوبت")
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                TextButton(
                                    onClick = { viewModel.abortSession() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("لغو کامل خلاصه سازی", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    is ProcessingState.Success -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Success",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "خلاصه‌سازی با موفقیت پایان یافت!",
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "۲ فایل به صورت همزمان در پوشه Download ذخیره شدند:",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // TXT Output
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    border = borderStroke(alpha = 0.15f)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.List,
                                                contentDescription = "Text file",
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
                                                    shareFile(context, state.textFileUri, "text/plain")
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = "Share text file",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = {
                                                previewTxtContent = state.summary
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("📝 دیدن فایل txt")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // HTML Output
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    border = borderStroke(alpha = 0.15f)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Info,
                                                contentDescription = "HTML file",
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = state.savedHtmlPath,
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    shareFile(context, state.htmlFileUri, "text/html")
                                                },
                                                modifier = Modifier.size(36.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Share,
                                                    contentDescription = "Share HTML file",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = {
                                                previewHtmlContent = state.htmlContent
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text("🌐 دیدن فایل html")
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = {
                                        shareMultipleFiles(context, state.textFileUri, state.htmlFileUri)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share both"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("📥 به اشتراک‌گذاری این دو (متن و وب)")
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "پیش‌نمایش خلاصه نهایی:",
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
                                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    is ProcessingState.Error -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
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
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.error
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

    previewTxtContent?.let { txt ->
        Dialog(onDismissRequest = { previewTxtContent = null }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
                    .padding(16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "پیش‌نمایش متنی خلاصه",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        IconButton(onClick = { previewTxtContent = null }) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "بستن")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    ) {
                        Text(
                            text = txt,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(txt))
                                Toast.makeText(context, "کل متن کپی شد", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("📋 کپی متن")
                        }
                        OutlinedButton(
                            onClick = { previewTxtContent = null },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("بستن")
                        }
                    }
                }
            }
        }
    }

    previewHtmlContent?.let { html ->
        HtmlPreviewDialog(htmlContent = html, onDismissRequest = { previewHtmlContent = null })
    }
}

// ================= API KEYS TAB =================
@Composable
fun ApiKeysTab(viewModel: MainViewModel) {
    val context = LocalContext.current
    val apiKeys by viewModel.apiKeysFlow.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var keyTitle by remember { mutableStateOf("") }
    var keyValue by remember { mutableStateOf("") }

    // Models tracking
    val selectedModelsList = remember { mutableStateListOf<ModelConfig>() }

    val defaultModelOptions by viewModel.globalModelsFlow.collectAsState()

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "افزودن API Key جدید",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = keyTitle,
                            onValueChange = { keyTitle = it },
                            label = { Text("نام کلید (مثلاً کلید اضطراری)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = keyValue,
                            onValueChange = { keyValue = it },
                            label = { Text("مقدار API Key کلید") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        Text(
                            text = "مدل‌های مجاز برای این کلید:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            textAlign = TextAlign.Right
                        )
                    }

                    // Recommended Checklist
                    itemsIndexed(defaultModelOptions) { _, config ->
                        val isChecked = selectedModelsList.any { it.code == config.code }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        selectedModelsList.removeAll { it.code == config.code }
                                    } else {
                                        selectedModelsList.add(config)
                                    }
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = {
                                    if (isChecked) {
                                        selectedModelsList.removeAll { it.code == config.code }
                                    } else {
                                        selectedModelsList.add(config)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(config.title, fontSize = 13.sp)
                        }
                    }

                    // Standard User Input Custom Model Code
                    item {
                        var customCode by remember { mutableStateOf("") }
                        var customTitle by remember { mutableStateOf("") }

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            OutlinedTextField(
                                value = customCode,
                                onValueChange = { customCode = it },
                                label = { Text("کد مدل") },
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = customTitle,
                                onValueChange = { customTitle = it },
                                label = { Text("عنوان مدل") },
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall,
                                singleLine = true
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            onClick = {
                                if (customCode.isNotBlank() && customTitle.isNotBlank()) {
                                    selectedModelsList.add(ModelConfig(customCode, customTitle))
                                    customCode = ""
                                    customTitle = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text("افزودن مدل سفارشی فوق به لیست")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (keyTitle.isNotBlank() && keyValue.isNotBlank() && selectedModelsList.isNotEmpty()) {
                            viewModel.addApiKey(keyTitle, keyValue, selectedModelsList.toList())
                            keyTitle = ""
                            keyValue = ""
                            selectedModelsList.clear()
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("ذخیره کلید")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("لغو")
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Key")
            }
        }
    ) { p ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(p)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Keys info", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("مدیریت و اولویت کلیدهای API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }

            item {
                Text(
                    text = "ترتیب قرارگیری کلیدها تعیین‌کننده اولویت استفاده پیش فرض است. با فلش‌ها می‌توانید اولویت را جابجا کنید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                    textAlign = TextAlign.Right
                )
            }

            item {
                HorizontalDivider()
            }

            // API Keys List Section
            if (apiKeys.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("هیچ کلیدی افزوده نشده است. روی دکمه + کلیک کنید.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            } else {
                itemsIndexed(apiKeys) { idx, config ->
                    val models = JsonSerializer.deserializeModels(config.modelsJson)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = borderStroke(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${idx + 1}. ${config.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                // Blur key representation
                                Text(
                                    text = "کلید: " + config.apiKey.take(4) + "..." + config.apiKey.takeLast(4),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "مدل‌های تخصیصی: " + models.map { it.title }.joinToString("، "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row {
                                IconButton(onClick = { viewModel.moveApiKeyUp(config) }) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                                }
                                IconButton(onClick = { viewModel.moveApiKeyDown(config) }) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                                }
                                IconButton(onClick = { viewModel.deleteApiKey(config.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Global Model Management inside ApiKeysTab
            item {
                var newModelCode by remember { mutableStateOf("") }
                var newModelTitle by remember { mutableStateOf("") }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.08f)),
                    border = borderStroke(alpha = 0.15f)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.List, contentDescription = "Model configuration", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("مدیریت سراسری گزینه‌های مدل‌های هوش مصنوعی (موجود در برنامه):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Text("مدل‌های وارد شده در این لیست در زمان افزودن کلیدهای API برای انتخاب مجاز بودن و در صفحه اصلی برای فرآيند خلاصه‌سازی در دسترس خواهند بود.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        // Form to add a new model option
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = newModelCode,
                                onValueChange = { newModelCode = it },
                                label = { Text("کد شناسه مدل", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("gemini-1.5-pro", fontSize = 11.sp) }
                            )
                            OutlinedTextField(
                                value = newModelTitle,
                                onValueChange = { newModelTitle = it },
                                label = { Text("عنوان نمایشی مدل", fontSize = 11.sp) },
                                modifier = Modifier.weight(1.2f),
                                singleLine = true,
                                placeholder = { Text("Gemini 1.5 Pro", fontSize = 11.sp) }
                            )
                            Button(
                                onClick = {
                                    if (newModelCode.isNotBlank() && newModelTitle.isNotBlank()) {
                                        viewModel.addGlobalModel(newModelCode.trim(), newModelTitle.trim())
                                        newModelCode = ""
                                        newModelTitle = ""
                                        Toast.makeText(context, "مدل جدید با موفقیت به گزینه‌های سراسری برنامه اضافه گشت.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "کد شناسه و عنوان نمایشی نباید خالی باشد.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.height(52.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) {
                                Text("افزودن", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("لیست مدل‌های موجود و ترتیب نمایش آن:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (defaultModelOptions.isEmpty()) {
                                Text("هیچ مدلی ثبت نشده است.", fontSize = 11.sp, modifier = Modifier.padding(8.dp))
                            } else {
                                defaultModelOptions.forEach { model ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(model.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            Text("شناسه مدل: ${model.code}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                        }
                                        IconButton(
                                            onClick = { viewModel.moveGlobalModelUp(model) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.moveGlobalModelDown(model) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down", modifier = Modifier.size(18.dp))
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteGlobalModel(model.code) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
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
}

// ================= PROMPT TEMPLATES TAB =================
@Composable
fun PromptTemplatesTab(viewModel: MainViewModel) {
    val templates by viewModel.promptTemplatesFlow.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingTemplate by remember { mutableStateOf<PromptTemplate?>(null) }

    var promptTitle by remember { mutableStateOf("") }
    var promptContent by remember { mutableStateOf("") }

    var expandedId by remember { mutableStateOf<Int?>(null) }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = "افزودن پرامپت الگو جدید",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = promptTitle,
                        onValueChange = { promptTitle = it },
                        label = { Text("عنوان پرامپت (مثلاً خلاصه فنی مهندسی)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = promptContent,
                        onValueChange = { promptContent = it },
                        label = { Text("متن پرامپت و قوانین خلاصه‌سازی") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (promptTitle.isNotBlank() && promptContent.isNotBlank()) {
                            viewModel.addPromptTemplate(promptTitle, promptContent)
                            promptTitle = ""
                            promptContent = ""
                            showAddDialog = false
                        }
                    }
                ) {
                    Text("ذخیره الگو")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("لغو")
                }
            }
        )
    }

    if (showEditDialog && editingTemplate != null) {
        var editTitle by remember { mutableStateOf(editingTemplate!!.title) }
        var editContent by remember { mutableStateOf(editingTemplate!!.promptContent) }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = {
                Text(
                    text = "ویرایش پرامپت الگو",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text("عنوان پرامپت") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editContent,
                        onValueChange = { editContent = it },
                        label = { Text("متن پرامپت و قوانین خلاصه‌سازی") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 12
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (editTitle.isNotBlank() && editContent.isNotBlank()) {
                            viewModel.updatePromptTemplate(
                                id = editingTemplate!!.id,
                                title = editTitle,
                                prompt = editContent,
                                priorityOrder = editingTemplate!!.priorityOrder
                            )
                            showEditDialog = false
                            editingTemplate = null
                        }
                    }
                ) {
                    Text("ذخیره تغییرات")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEditDialog = false
                        editingTemplate = null
                    }
                ) {
                    Text("لغو")
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Template")
            }
        }
    ) { p ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(p)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Menu, contentDescription = "Prompts info", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("مدیریت قالب‌های پرامپت فرآیند خلاصه‌سازی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "می‌توانید پرامپت‌های دلخواه خود را اضافه یا ویرایش کنید. برای دیدن متن کامل هر قالب روی کادر مربوطه کلیک کنید.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
                textAlign = TextAlign.Right
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (templates.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("الگویی ثبت نشده است. از دکمه + ثبت الگو اقدام نمایید.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(templates) { _, template ->
                        val isExpanded = expandedId == template.id
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            border = borderStroke(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = template.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { viewModel.movePromptTemplateUp(template) }) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                                    }
                                    IconButton(onClick = { viewModel.movePromptTemplateDown(template) }) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                                    }
                                    IconButton(
                                        onClick = {
                                            editingTemplate = template
                                            showEditDialog = true
                                        }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit Template", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { viewModel.deletePromptTemplate(template.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete Template", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                        .clickable {
                                            expandedId = if (isExpanded) null else template.id
                                        }
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        text = template.promptContent,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                                        overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Right,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (isExpanded) {
                                    Text(
                                        text = "👆 برای بستن مجدد کلیک کنید.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 4.dp).align(Alignment.End)
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

// ================= HISTORY TAB =================
@Composable
fun HistoryTab(viewModel: MainViewModel) {
    val history by viewModel.historyLogsFlow.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    var viewingLog by remember { mutableStateOf<HistoryLog?>(null) }

    if (viewingLog != null) {
        val currentLog = viewingLog!!
        AlertDialog(
            onDismissRequest = { viewingLog = null },
            title = {
                Text(
                    text = currentLog.fileName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                ) {
                    Text(
                        text = "متن کامل خلاصه تولید شده:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = currentLog.summaryContent,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Right,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "گزینه‌های اشتراک‌گذاری:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (currentLog.savedTextUri.isNotEmpty()) {
                                    shareFile(context, currentLog.savedTextUri, "text/plain")
                                } else {
                                    Toast.makeText(context, "فایل متنی در این مسیر موجود نیست.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share TXT", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("فایل TXT", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(
                            onClick = {
                                if (currentLog.savedHtmlUri.isNotEmpty()) {
                                    shareFile(context, currentLog.savedHtmlUri, "text/html")
                                } else {
                                    Toast.makeText(context, "فایل وب در این مسیر موجود نیست.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = "Share HTML", modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("فایل HTML", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(currentLog.summaryContent))
                        Toast.makeText(context, "کل متن در کلیپ‌بورد کپی شد.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("کپی متن")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewingLog = null }) {
                    Text("بستن")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.List, contentDescription = "History", tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(8.dp))
            Text("تاریخچه فعالیت‌های اخیر خلاصه‌سازی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            if (history.isNotEmpty()) {
                TextButton(onClick = { viewModel.clearHistory() }) {
                    Text("حذف همه", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("تاریخچه تراکنش‌های خلاصه‌سازی خالی است.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(history) { _, log ->
                    val dateFormatted = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(log.timestamp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewingLog = log
                            },
                        border = borderStroke(alpha = 0.12f)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = log.fileName,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = dateFormatted,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "تعداد بخش‌ها: ${log.sectionsCount} | ذخیره‌شده به عنوان: ${log.savedPath}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = log.summaryContent,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Right,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "👆 برای دیدن کل متن و اشتراک‌گذاری ضربه بزنید.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Button(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(log.summaryContent))
                                        Toast.makeText(context, "کپی در حافظه کلیپ‌بورد انجام شد.", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("کپی خلاصه", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================= SETTINGS TAB =================
@Composable
fun SettingsTab(viewModel: MainViewModel) {
    val settingsState by viewModel.appSettingsFlow.collectAsState()
    val settings = settingsState ?: AppSettings()
    val errorLogs by viewModel.errorLogsFlow.collectAsState()

    var successDelay by remember { mutableStateOf("10") }
    var errorDelay by remember { mutableStateOf("10") }
    var overloadDelay by remember { mutableStateOf("30") }
    var retriesLimit by remember { mutableStateOf("3") }
    var autoSwitch by remember { mutableStateOf(false) }
    var separator by remember { mutableStateOf("-----") }
    var compiledSeparatorTemplate by remember { mutableStateOf("\n=========\nبخش {index}/{total}\n=========\n{summary}") }

    LaunchedEffect(settingsState) {
        settingsState?.let {
            successDelay = it.successDelaySeconds.toString()
            errorDelay = it.errorDelaySeconds.toString()
            overloadDelay = it.overloadDelaySeconds.toString()
            retriesLimit = it.retryAttemptsLimit.toString()
            autoSwitch = it.autoSwitchOnLimit
            separator = it.customSeparator
            compiledSeparatorTemplate = it.compiledSeparatorTemplate
        }
    }

    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = "Set", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("تنظیمات فواصل زمانی و خطایابی", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        item {
            HorizontalDivider()
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("جداکننده متنی بخش‌ها (Separator):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(
                        value = separator,
                        onValueChange = { separator = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("مثلاً ----") }
                    )
                    Text("هرجا در سند این عبارت وجود داشته باشد، به بخش جدید تفکیک خواهد شد.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("قالب نهایی بخش‌های خلاصه در فایل نهایی (Output Template):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    OutlinedTextField(
                        value = compiledSeparatorTemplate,
                        onValueChange = { compiledSeparatorTemplate = it },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                        placeholder = { Text("قالب دلخواه جداکننده") }
                    )
                    Text("از متغیرهای {index}، {total} و {summary} می‌توانید برای طراحی ساختار خلاصه فایل نهایی استفاده کنید.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("فواصل خواب و استراحت (ثانیه):", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = successDelay,
                            onValueChange = { successDelay = it },
                            label = { Text("پس از موفقیت") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = errorDelay,
                            onValueChange = { errorDelay = it },
                            label = { Text("پس از خطای ساده") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }

                    OutlinedTextField(
                        value = overloadDelay,
                        onValueChange = { overloadDelay = it },
                        label = { Text("پس از خطای لود ۵۰۳") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("سیاست تلاش مجدد و خطا:", fontWeight = FontWeight.Bold, fontSize = 13.sp)

                    OutlinedTextField(
                        value = retriesLimit,
                        onValueChange = { retriesLimit = it },
                        label = { Text("حداکثر دفعات تکرار برای یک مدل") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { autoSwitch = !autoSwitch }
                            .padding(vertical = 4.dp)
                    ) {
                        Switch(checked = autoSwitch, onCheckedChange = { autoSwitch = it })
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("پرش خودکار روی مدل/کلید بعدی بر اثر شکست", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("در صورت خاموش بودن، برای هر مدل سیستم توقف کرده و از شما سوال می‌کند.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.12f)),
                border = borderStroke(colorColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = "Logs", tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("گزارش خطاهای سیستم (${errorLogs.size} مورد)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        }
                        if (errorLogs.isNotEmpty()) {
                            TextButton(onClick = { viewModel.clearErrorLogs() }) {
                                Text("پاک‌سازی همه", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    }

                    if (errorLogs.isEmpty()) {
                        Text(
                            text = "تنظیمات پایدار است. هیچ خطای سیستمی یا سهمیه‌ای (Quota) گزارش نشده است.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            errorLogs.forEach { log ->
                                val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = "[$dateStr] ${log.errorMessage}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    if (log.details.isNotEmpty()) {
                                        Text(
                                            text = log.details,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = FontFamily.Monospace,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val compiledLogs = errorLogs.joinToString("\n\n") { log ->
                                    val dateStr = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(log.timestamp))
                                    "[$dateStr]\nخطا: ${log.errorMessage}\nجزئیات: ${log.details}\n"
                                }
                                shareText(context, "اشتراک‌گذاری گزارش خطاها", compiledLogs)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("📤 اشتراک‌گذاری گزارش خطاها")
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    val sDelay = successDelay.toIntOrNull() ?: 10
                    val eDelay = errorDelay.toIntOrNull() ?: 10
                    val oDelay = overloadDelay.toIntOrNull() ?: 30
                    val limit = retriesLimit.toIntOrNull() ?: 3

                    viewModel.updateSettings(
                        AppSettings(
                            successDelaySeconds = sDelay,
                            errorDelaySeconds = eDelay,
                            overloadDelaySeconds = oDelay,
                            retryAttemptsLimit = limit,
                            autoSwitchOnLimit = autoSwitch,
                            customSeparator = separator,
                            compiledSeparatorTemplate = compiledSeparatorTemplate
                        )
                    )
                    Toast.makeText(context, "تنظیمات اختصاصی با موفقیت اعمال گشت.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("ثبت و ذخیره تنظیمات")
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

@Composable
fun HtmlPreviewDialog(htmlContent: String, onDismissRequest: () -> Unit) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "پیش‌نمایش سند HTML خلاصه شده",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "بستن")
                    }
                }
                
                // WebView Container
                AndroidView(
                    factory = { context ->
                        android.webkit.WebView(context).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                textZoom = 100
                            }
                            webChromeClient = android.webkit.WebChromeClient()
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        }
    }
}

fun shareFile(context: Context, uriString: String, fileMimeType: String) {
    if (uriString.isEmpty()) {
        Toast.makeText(context, "فایل ثبت نشده است.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = fileMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری فایل"))
    } catch (e: Exception) {
        Toast.makeText(context, "خطا در اشتراک‌گذاری: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun shareMultipleFiles(context: Context, textUriStr: String, htmlUriStr: String) {
    if (textUriStr.isEmpty() || htmlUriStr.isEmpty()) {
        Toast.makeText(context, "فایل‌ها ثبت نشده‌اند.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uris = arrayListOf(Uri.parse(textUriStr), Uri.parse(htmlUriStr))
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری هر دو فایل"))
    } catch (e: Exception) {
        Toast.makeText(context, "خطا در اشتراک‌گذاری: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun shareText(context: Context, title: String, content: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        context.startActivity(Intent.createChooser(intent, title))
    } catch (e: Exception) {
        Toast.makeText(context, "خطا در اشتراک‌گذاری متن: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
