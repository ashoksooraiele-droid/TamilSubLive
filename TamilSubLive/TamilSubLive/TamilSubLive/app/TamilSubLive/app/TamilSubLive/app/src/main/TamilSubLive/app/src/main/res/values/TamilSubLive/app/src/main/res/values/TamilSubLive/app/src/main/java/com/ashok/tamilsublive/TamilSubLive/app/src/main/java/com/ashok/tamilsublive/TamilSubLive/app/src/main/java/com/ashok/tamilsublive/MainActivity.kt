package com.ashok.tamilsublive

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private fun resolveDisplayName(context: Context, uri: Uri): String {
    var name: String? = null
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIdx >= 0 && cursor.moveToFirst()) {
            name = cursor.getString(nameIdx)
        }
    }
    return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle_${System.currentTimeMillis()}.srt"
}

private fun nowCmdStamp(): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
}

class MainActivity : ComponentActivity() {
    private val translatorService = HybridTranslatorService()

    override fun onDestroy() {
        translatorService.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                TamilSubLiveScreen(
                    service = translatorService,
                    onExitApp = {
                        finishAffinity()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TamilSubLiveScreen(
    service: HybridTranslatorService,
    onExitApp: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val cmdListState = rememberLazyListState()

    var mode by remember { mutableStateOf(TranslateMode.ONLINE_FAST) }
    var selectedLangOption by remember { mutableStateOf(SupportedSourceLanguages.options.first()) }
    var followLive by remember { mutableStateOf(true) }
    var autoSaveEachFile by remember { mutableStateOf(true) }
    var isRunning by remember { mutableStateOf(false) }
    var isDownloadingModel by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf("Ready - Add multiple SRT files & auto-translate all to Tamil") }

    val cmdLogs = remember {
        mutableStateListOf(
            "C:\\TamilSubLive> ready [Any -> Tamil | Online/Offline | Multi-SRT]"
        )
    }

    fun appendCmdLog(line: String) {
        val entry = "[${nowCmdStamp()}] $line"
        cmdLogs.add(entry)
        if (cmdLogs.size > 400) {
            cmdLogs.removeAt(0)
        }
        scope.launch {
            val lastIdx = (cmdLogs.size - 1).coerceAtLeast(0)
            cmdListState.animateScrollToItem(lastIdx)
        }
    }

    var editorText by remember {
        mutableStateOf(
            """1
00:00:01,000 --> 00:00:03,500
Hello! Select multiple SRT files or type lines to translate live to Tamil.

2
00:00:04,000 --> 00:00:07,000
Watch live CMD terminal logs as each line translates to Tamil."""
        )
    }

    var srtQueue by remember { mutableStateOf<List<SrtQueueItem>>(emptyList()) }
    var activeFileIndex by remember { mutableIntStateOf(-1) }
    var cues by remember { mutableStateOf<List<CueBlock>>(emptyList()) }
    var doneCount by remember { mutableIntStateOf(0) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var pendingSaveFileIndex by remember { mutableStateOf<Int?>(null) }

    val canExportSrt = cues.isNotEmpty() || srtQueue.any { it.isCompleted || it.cues.isNotEmpty() }

    fun stopCurrentWork() {
        activeJob?.cancel()
        activeJob = null
        isRunning = false
        isDownloadingModel = false
        srtQueue = srtQueue.map { it.copy(isTranslatingNow = false) }
        statusBanner = "Stopped"
        appendCmdLog("WARN: Process stopped by user.")
        Toast.makeText(context, "Stopped", Toast.LENGTH_SHORT).show()
    }

    fun exitApplicationCleanly() {
        appendCmdLog("EXIT: Releasing resources and closing app...")
        activeJob?.cancel()
        activeJob = null
        isRunning = false
        isDownloadingModel = false
        service.release()
        onExitApp()
    }

    fun writeSrtToUri(uri: Uri, content: String): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun autoSaveTranslatedFileToAppStorage(item: SrtQueueItem): String? {
        return try {
            val outDir = File(context.getExternalFilesDir(null), "TranslatedSRT").apply { mkdirs() }
            val outFile = File(outDir, SubtitleCodec.makeTamilOutputFileName(item.fileName))
            outFile.writeText(SubtitleCodec.buildSrtOutput(item.cues), Charsets.UTF_8)
            outFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun switchMode(newMode: TranslateMode) {
        if (mode == newMode && !isRunning && !isDownloadingModel) return
        activeJob?.cancel()
        isRunning = false
        isDownloadingModel = false
        mode = newMode
        statusBanner = if (newMode == TranslateMode.ONLINE_FAST) {
            "Switched to Online Mode (Fast Batch -> Tamil)"
        } else {
            "Switched to Offline Mode (ML Kit On-Device -> Tamil)"
        }
        appendCmdLog("MODE -> ${if (newMode == TranslateMode.ONLINE_FAST) "ONLINE_FAST" else "OFFLINE_MLKIT"}")
    }

    val pickMultipleSrtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            activeJob?.cancel()
            isRunning = false
            isDownloadingModel = false

            val newItems = mutableListOf<SrtQueueItem>()
            for (uri in uris) {
                try {
                    val content = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    if (content.isNotBlank()) {
                        val parsed = SubtitleCodec.parseBlocks(content)
                        val displayName = resolveDisplayName(context, uri)
                        newItems.add(
                            SrtQueueItem(
                                id = UUID.randomUUID().toString(),
                                fileName = displayName,
                                rawContent = content,
                                cues = parsed
                            )
                        )
                        appendCmdLog("QUEUED: $displayName (${parsed.size} cues)")
                    }
                } catch (_: Exception) {
                }
            }

            if (newItems.isNotEmpty()) {
                val combined = srtQueue + newItems
                srtQueue = combined
                val firstNewIdx = combined.size - newItems.size
                activeFileIndex = firstNewIdx
                editorText = newItems.first().rawContent
                cues = newItems.first().cues
                doneCount = 0
                statusBanner = "Added ${newItems.size} SRT file(s). Total in queue: ${combined.size}"
                Toast.makeText(context, "Added ${newItems.size} SRT file(s)", Toast.LENGTH_SHORT).show()
            } else {
                statusBanner = "Selected files were empty or unreadable"
            }
        }
    }

    val saveSingleSrtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/x-subrip")
    ) { uri: Uri? ->
        val idx = pendingSaveFileIndex
        pendingSaveFileIndex = null
        if (uri != null) {
            val contentToSave = if (idx != null && idx in srtQueue.indices) {
                SubtitleCodec.buildSrtOutput(srtQueue[idx].cues)
            } else {
                SubtitleCodec.buildSrtOutput(cues)
            }
            if (writeSrtToUri(uri, contentToSave)) {
                if (idx != null && idx in srtQueue.indices) {
                    srtQueue = srtQueue.toMutableList().apply {
                        this[idx] = this[idx].copy(isSaved = true)
                    }
                }
                appendCmdLog("SAVED SRT file successfully.")
                Toast.makeText(context, "Saved Tamil SRT!", Toast.LENGTH_SHORT).show()
                statusBanner = "Saved Tamil SRT file"
            } else {
                statusBanner = "Save failed"
            }
        }
    }

    fun createSrtFromEditor() {
        activeJob?.cancel()
        isRunning = false
        val built = SubtitleCodec.buildSrtFromLines(editorText)
        if (built.isEmpty()) {
            statusBanner = "SRT create panna text ezhudhunga"
            return
        }
        cues = built
        doneCount = 0
        editorText = SubtitleCodec.buildSrtOutput(built)
        appendCmdLog("CREATED ${built.size} SRT blocks from editor.")
        statusBanner = "Created ${built.size} SRT cues! Save SRT or Start Live Translate"
        Toast.makeText(context, "Created ${built.size} SRT cues", Toast.LENGTH_SHORT).show()
    }

    fun startModelDownload() {
        if (isDownloadingModel || isRunning) return
        val sampleLines = (
            srtQueue.firstOrNull()?.cues?.map { it.sourceText }
                ?: SubtitleCodec.buildSrtFromLines(editorText).map { it.sourceText }
            ).take(5)
        val chosenLang = selectedLangOption
        activeJob = scope.launch {
            isDownloadingModel = true
            statusBanner = "Preparing offline model (${chosenLang.label} -> Tamil)..."
            appendCmdLog("MLKIT: Checking/downloading model (${chosenLang.label} -> ta)...")
            try {
                val langCode = service.downloadOfflineModelsIfNeeded(chosenLang, sampleLines) { msg ->
                    statusBanner = msg
                    appendCmdLog("MLKIT: $msg")
                }
                statusBanner = "Offline model ready ($langCode -> ta)"
                appendCmdLog("MLKIT READY: $langCode -> ta")
                Toast.makeText(context, "Offline model ready!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                statusBanner = "Model download failed: ${e.message ?: "Error"}"
                appendCmdLog("ERR: Model download failed - ${e.message ?: "Error"}")
            } finally {
                isDownloadingModel = false
            }
        }
    }

    suspend fun translateSingleFileItem(
        fileIdx: Int,
        initialItem: SrtQueueItem,
        currentMode: TranslateMode,
        chosenLang: SourceLanguageOption,
        batchSize: Int
    ) {
        val parsed = if (initialItem.cues.isNotEmpty()) {
            initialItem.cues.map { it.copy(tamilText = "", isTranslated = false) }
        } else {
            SubtitleCodec.buildSrtFromLines(initialItem.rawContent)
        }

        if (parsed.isEmpty()) {
            srtQueue = srtQueue.toMutableList().apply {
                if (fileIdx in indices) {
                    this[fileIdx] = this[fileIdx].copy(
                        isTranslatingNow = false,
                        isCompleted = true,
                        errorText = "Empty"
                    )
                }
            }
            appendCmdLog("SKIP [${initialItem.fileName}]: No subtitle lines.")
            return
        }

        activeFileIndex = fileIdx
        cues = parsed
        doneCount = 0
        appendCmdLog("=== FILE ${fileIdx + 1}/${srtQueue.size}: ${initialItem.fileName} (${parsed.size} cues) ===")

        srtQueue = srtQueue.toMutableList().apply {
            if (fileIdx in indices) {
                this[fileIdx] = this[fileIdx].copy(
                    cues = parsed,
                    completedCues = 0,
                    isCompleted = false,
                    isTranslatingNow = true,
                    errorText = null
                )
            }
        }

        val mutableCues = parsed.toMutableList()
        val chunks = mutableCues.indices.toList().chunked(batchSize)

        for (chunkIdx in chunks) {
            val batchSource = chunkIdx.map { mutableCues[it].sourceText }
            val batchResult = if (currentMode == TranslateMode.ONLINE_FAST) {
                service.translateBatchOnline(batchSource, chosenLang)
            } else {
                service.translateBatchOffline(batchSource, chosenLang)
            }

            for (i in chunkIdx.indices) {
                val targetIdx = chunkIdx[i]
                val srcLine = mutableCues[targetIdx].sourceText
                val translatedLine = batchResult.getOrElse(i) { srcLine }
                mutableCues[targetIdx] = mutableCues[targetIdx].copy(
                    tamilText = translatedLine,
                    isTranslated = true
                )
                val shortSrc = srcLine.replace("\n", " ").take(42)
                val shortTa = translatedLine.replace("\n", " ").take(48)
                appendCmdLog("#${mutableCues[targetIdx].number} \"$shortSrc\" => \"$shortTa\"")
            }

            val updatedDone = (doneCount + chunkIdx.size).coerceAtMost(mutableCues.size)
            doneCount = updatedDone
            val snapshotCues = mutableCues.toList()
            cues = snapshotCues

            srtQueue = srtQueue.toMutableList().apply {
                if (fileIdx in indices) {
                    this[fileIdx] = this[fileIdx].copy(
                        cues = snapshotCues,
                        completedCues = updatedDone
                    )
                }
            }

            statusBanner = "File ${fileIdx + 1}/${srtQueue.size} (${initialItem.fileName}): $updatedDone/${snapshotCues.size}"

            if (followLive && updatedDone > 0) {
                listState.animateScrollToItem((updatedDone - 1).coerceAtLeast(0))
            }
        }

        val finishedItem = SrtQueueItem(
            id = initialItem.id,
            fileName = initialItem.fileName,
            rawContent = initialItem.rawContent,
            cues = mutableCues.toList(),
            completedCues = mutableCues.size,
            isCompleted = true,
            isTranslatingNow = false
        )

        val autoSavedPath = if (autoSaveEachFile) autoSaveTranslatedFileToAppStorage(finishedItem) else null
        if (autoSavedPath != null) {
            appendCmdLog("AUTO-SAVED -> ${SubtitleCodec.makeTamilOutputFileName(initialItem.fileName)}")
        }

        srtQueue = srtQueue.toMutableList().apply {
            if (fileIdx in indices) {
                this[fileIdx] = finishedItem.copy(isSaved = autoSavedPath != null)
            }
        }
    }

    fun startAutoTranslateAllFiles() {
        activeJob?.cancel()
        val currentMode = mode
        val chosenLang = selectedLangOption
        val batchSize = if (currentMode == TranslateMode.ONLINE_FAST) 10 else 5

        activeJob = scope.launch {
            isRunning = true
            isDownloadingModel = currentMode == TranslateMode.OFFLINE_MLKIT
            appendCmdLog("START: Mode=${if (currentMode == TranslateMode.ONLINE_FAST) "ONLINE" else "OFFLINE"} | Lang=${chosenLang.label} -> Tamil")

            try {
                if (srtQueue.isNotEmpty()) {
                    if (currentMode == TranslateMode.OFFLINE_MLKIT) {
                        val firstSample = srtQueue.first().cues.take(5).map { it.sourceText }
                        service.downloadOfflineModelsIfNeeded(chosenLang, firstSample) { msg ->
                            statusBanner = msg
                            appendCmdLog("MLKIT: $msg")
                        }
                        isDownloadingModel = false
                    }

                    for (idx in srtQueue.indices) {
                        val item = srtQueue[idx]
                        translateSingleFileItem(
                            fileIdx = idx,
                            initialItem = item,
                            currentMode = currentMode,
                            chosenLang = chosenLang,
                            batchSize = batchSize
                        )
                    }

                    isRunning = false
                    val savedMsg = if (autoSaveEachFile) " (Auto-saved to TranslatedSRT folder)" else ""
                    statusBanner = "All ${srtQueue.size} SRT files translated to Tamil!$savedMsg"
                    appendCmdLog("DONE: All ${srtQueue.size} SRT files translated to Tamil!")
                    Toast.makeText(context, "All ${srtQueue.size} SRT files completed!", Toast.LENGTH_LONG).show()
                } else {
                    val parsed = SubtitleCodec.buildSrtFromLines(editorText)
                    if (parsed.isEmpty()) {
                        isRunning = false
                        isDownloadingModel = false
                        statusBanner = "Add SRT files or enter text to translate"
                        appendCmdLog("WARN: Nothing to translate.")
                        return@launch
                    }

                    cues = parsed
                    doneCount = 0

                    if (currentMode == TranslateMode.OFFLINE_MLKIT) {
                        service.downloadOfflineModelsIfNeeded(
                            selectedOption = chosenLang,
                            sampleTexts = parsed.take(5).map { it.sourceText }
                        ) { msg ->
                            statusBanner = msg
                            appendCmdLog("MLKIT: $msg")
                        }
                        isDownloadingModel = false
                    }

                    val mutableCues = parsed.toMutableList()
                    val chunks = mutableCues.indices.toList().chunked(batchSize)

                    for (chunkIdx in chunks) {
                        val batchSource = chunkIdx.map { mutableCues[it].sourceText }
                        val batchResult = if (currentMode == TranslateMode.ONLINE_FAST) {
                            service.translateBatchOnline(batchSource, chosenLang)
                        } else {
                            service.translateBatchOffline(batchSource, chosenLang)
                        }

                        for (i in chunkIdx.indices) {
                            val targetIdx = chunkIdx[i]
                            val srcLine = mutableCues[targetIdx].sourceText
                            val translatedLine = batchResult.getOrElse(i) { srcLine }
                            mutableCues[targetIdx] = mutableCues[targetIdx].copy(
                                tamilText = translatedLine,
                                isTranslated = true
                            )
                            val shortSrc = srcLine.replace("\n", " ").take(42)
                            val shortTa = translatedLine.replace("\n", " ").take(48)
                            appendCmdLog("#${mutableCues[targetIdx].number} \"$shortSrc\" => \"$shortTa\"")
                        }

                        doneCount = (doneCount + chunkIdx.size).coerceAtMost(mutableCues.size)
                        cues = mutableCues.toList()
                        statusBanner = "Live translating (${chosenLang.label} -> Tamil): $doneCount / ${mutableCues.size}"

                        if (followLive && doneCount > 0) {
                            listState.animateScrollToItem((doneCount - 1).coerceAtLeast(0))
                        }
                    }

                    isRunning = false
                    statusBanner = "Completed: $doneCount / ${parsed.size} blocks -> Tamil"
                    appendCmdLog("DONE: Completed $doneCount/${parsed.size} blocks -> Tamil.")
                }
            } catch (_: CancellationException) {
                isRunning = false
                isDownloadingModel = false
                srtQueue = srtQueue.map { it.copy(isTranslatingNow = false) }
                statusBanner = "Translation stopped"
                appendCmdLog("STOPPED: Translation cancelled.")
            } catch (e: Exception) {
                isRunning = false
                isDownloadingModel = false
                srtQueue = srtQueue.map { it.copy(isTranslatingNow = false) }
                statusBanner = "Error: ${e.message ?: "Failed"}"
                appendCmdLog("ERROR: ${e.message ?: "Failed"}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TamilSubLive CMD") },
                actions = {
                    OutlinedButton(
                        onClick = { stopCurrentWork() },
                        modifier = Modifier.padding(end = 6.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                    Button(
                        onClick = { exitApplicationCleanly() },
                        modifier = Modifier.padding(end = 8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ExitToApp,
                            contentDescription = "Exit",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Exit")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isOnline = mode == TranslateMode.ONLINE_FAST
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isOnline) "Mode: Online (Fast Batch)" else "Mode: Offline (ML Kit)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Source: ${selectedLangOption.label} -> Tamil",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = isOnline,
                            onCheckedChange = { checked ->
                                switchMode(
                                    if (checked) TranslateMode.ONLINE_FAST else TranslateMode.OFFLINE_MLKIT
                                )
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = isOnline,
                            onClick = { switchMode(TranslateMode.ONLINE_FAST) },
                            label = { Text("Online Mode") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = !isOnline,
                            onClick = { switchMode(TranslateMode.OFFLINE_MLKIT) },
                            label = { Text("Offline Mode") },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(SupportedSourceLanguages.options) { langOpt ->
                            FilterChip(
                                selected = selectedLangOption.code == langOpt.code,
                                onClick = {
                                    if (!isRunning && !isDownloadingModel) {
                                        selectedLangOption = langOpt
                                        statusBanner = "Language set to ${langOpt.label} -> Tamil"
                                        appendCmdLog("LANG -> ${langOpt.label} -> ta")
                                    }
                                },
                                label = { Text(langOpt.label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = followLive,
                                onCheckedChange = { followLive = it }
                            )
                            Text("Auto-Scroll", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoSaveEachFile,
                                onCheckedChange = { autoSaveEachFile = it }
                            )
                            Text("Auto-Save Files", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (!isOnline) {
                        OutlinedButton(
                            onClick = { startModelDownload() },
                            enabled = !isDownloadingModel && !isRunning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isDownloadingModel) "Downloading Model..."
                                else "Download Offline Model (${selectedLangOption.label} -> Tamil)"
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { pickMultipleSrtLauncher.launch(arrayOf("*/*")) },
                    enabled = !isRunning && !isDownloadingModel,
                    modifier = Modifier.weight(1.3f)
                ) {
                    Text("+ Add SRT Files")
                }
                OutlinedButton(
                    onClick = {
                        val targetIdx = if (activeFileIndex in srtQueue.indices) activeFileIndex else null
                        val suggestedName = if (targetIdx != null) {
                            SubtitleCodec.makeTamilOutputFileName(srtQueue[targetIdx].fileName)
                        } else {
                            "translated_tamil.srt"
                        }
                        pendingSaveFileIndex = targetIdx
                        saveSingleSrtLauncher.launch(suggestedName)
                    },
                    enabled = canExportSrt,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save SRT")
                }
                OutlinedButton(
                    onClick = {
                        val exported = SubtitleCodec.buildSrtOutput(cues)
                        clipboard.setText(AnnotatedString(exported))
                        Toast.makeText(context, "Copied SRT!", Toast.LENGTH_SHORT).show()
                    },
                    enabled = cues.isNotEmpty(),
                    modifier = Modifier.weight(0.8f)
                ) {
                    Text("Copy")
                }
            }

            if (srtQueue.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SRT Queue (${srtQueue.count { it.isCompleted }}/${srtQueue.size} done)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = {
                                    if (!isRunning && !isDownloadingModel) {
                                        srtQueue = emptyList()
                                        activeFileIndex = -1
                                        cues = emptyList()
                                        doneCount = 0
                                        statusBanner = "Cleared SRT queue"
                                        appendCmdLog("QUEUE CLEARED.")
                                    }
                                }
                            ) {
                                Text("Clear All")
                            }
                        }

                        srtQueue.forEachIndexed { idx, item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${idx + 1}. ${item.fileName}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (idx == activeFileIndex) FontWeight.Bold else FontWeight.Normal
                                    )
                                    val subStatus = when {
                                        item.isTranslatingNow -> "Translating ${item.completedCues}/${item.cues.size}..."
                                        item.isCompleted && item.isSaved -> "Completed & Auto-Saved (${item.cues.size} cues)"
                                        item.isCompleted -> "Completed (${item.cues.size} cues)"
                                        else -> "Queued (${item.cues.size} cues)"
                                    }
                                    Text(
                                        text = subStatus,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                if (item.isCompleted) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Done"
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        activeFileIndex = idx
                                        editorText = item.rawContent
                                        cues = item.cues
                                        doneCount = item.completedCues
                                        statusBanner = "Viewing ${item.fileName}"
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "View File"
                                    )
                                }

                                IconButton(
                                    enabled = !isRunning && !isDownloadingModel,
                                    onClick = {
                                        val updated = srtQueue.toMutableList().apply { removeAt(idx) }
                                        srtQueue = updated
                                        activeFileIndex = if (updated.isEmpty()) -1 else 0
                                        cues = updated.firstOrNull()?.cues ?: emptyList()
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Remove File"
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = editorText,
                    onValueChange = { editorText = it },
                    label = { Text("Or enter lines / SRT blocks directly") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(82.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (srtQueue.isEmpty()) {
                    OutlinedButton(
                        onClick = { createSrtFromEditor() },
                        enabled = !isRunning && !isDownloadingModel,
                        modifier = Modifier.weight(0.85f)
                    ) {
                        Text("Create SRT")
                    }
                }
                Button(
                    onClick = { startAutoTranslateAllFiles() },
                    enabled = !isRunning && !isDownloadingModel,
                    modifier = Modifier.weight(1.25f)
                ) {
                    Text(
                        if (srtQueue.size > 1) "Auto-Translate All (${srtQueue.size})"
                        else "Start Live Translate"
                    )
                }
                OutlinedButton(
                    onClick = { stopCurrentWork() }
                ) {
                    Text("Stop")
                }
                OutlinedButton(
                    onClick = { exitApplicationCleanly() }
                ) {
                    Text("Exit")
                }
            }

            Text(
                text = statusBanner,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )

            if (isDownloadingModel) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (cues.isNotEmpty()) {
                LinearProgressIndicator(
                    progress = {
                        (doneCount.toFloat() / cues.size.toFloat()).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(145.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0F10))
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1A1F24))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "LIVE CMD TERMINAL",
                            color = Color(0xFF00E676),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = { cmdLogs.clear() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text(
                                text = "Clear",
                                color = Color(0xFFB0BEC5),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                    LazyColumn(
                        state = cmdListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(cmdLogs) { logLine ->
                            Text(
                                text = logLine,
                                color = when {
                                    logLine.contains("=>") -> Color(0xFF69F0AE)
                                    logLine.contains("ERR") || logLine.contains("ERROR") || logLine.contains("WARN") -> Color(0xFFFFAB40)
                                    logLine.contains("DONE") || logLine.contains("READY") || logLine.contains("AUTO-SAVED") -> Color(0xFF40C4FF)
                                    else -> Color(0xFFE0E0E0)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(cues) { _, cue ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (cue.isTranslated) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (cue.timeRange.isNotBlank()) {
                                Text(
                                    text = "#${cue.number}  ${cue.timeRange}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                text = cue.sourceText,
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider()
                            Text(
                                text = if (cue.isTranslated) cue.tamilText else "Waiting...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (cue.isTranslated) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}
