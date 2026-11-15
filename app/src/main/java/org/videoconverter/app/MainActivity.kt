package org.videoconverter.app

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import org.json.JSONArray
import kotlin.math.roundToInt

internal class MainActivity : ComponentActivity() {
    private val preferences by lazy { getSharedPreferences("conversion", MODE_PRIVATE) }
    private var workerLimit by mutableStateOf(DEFAULT_BATCH_WORKERS)
    private var selected by mutableStateOf(emptyList<InputFile>())
    private var draftId = java.util.UUID.randomUUID().toString()
    private var submittedDraftId: String? = null
    private var loadingBatch by mutableStateOf(true)

    private fun reloadBatch() {
        loadingBatch = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { BatchStore.initialize(this@MainActivity) }
            } finally {
                loadingBatch = false
            }
        }
    }
    private var reselectIndex: Int? = null

    private fun saveSelection(files: List<InputFile>): Boolean {
        val nextId = java.util.UUID.randomUUID().toString()
        return try {
            DraftStore.save(this, nextId, files)
            DraftStore.setActive(this, nextId)
            DraftStore.remove(this, draftId)
            draftId = nextId
            selected = files
            true
        } catch (_: IOException) {
            DraftStore.remove(this, nextId)
            message = "Could not save the selection. Check available storage."
            false
        }
    }
    private var folder by mutableStateOf<Uri?>(null)
    private var remembered by mutableStateOf<Uri?>(null)
    private var bitrate by mutableStateOf(192)
    private var trimSilence by mutableStateOf(false)
    private var preserveFolders by mutableStateOf(true)
    private var loudness by mutableStateOf(Loudness.OFF)
    private val presetStore by lazy { PresetStore(preferences) }
    private var presets by mutableStateOf(emptyList<ConversionPreset>())
    private var retryNeedsDestination by mutableStateOf(false)
    private var replaceRetryDestination by mutableStateOf(false)
    private var target by mutableStateOf(AudioTarget.MP3)
    private var showTargets by mutableStateOf(false)
    private var showQuality by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var importCount by mutableStateOf(0)
    private var scanningFolder by mutableStateOf(false)

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        importFiles(uris)
    }

    private val reselectInput = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val index = reselectIndex
        reselectIndex = null
        if (uri != null && index != null && !ConversionService.busy.value) {
            if (runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isFailure) {
                message = "The selected file could not be kept for retry. Pick another file."
            } else {
                try {
                    BatchStore.update(index) { item ->
                        if (!item.inputUnavailable) item else item.copy(
                            input = item.input.copy(uri = uri, staged = null), inputUnavailable = false,
                            detail = "Source updated. Retry unfinished files to convert it.",
                        )
                    }
                    message = null
                } catch (_: IOException) {
                    message = "Could not update the source. Check available storage."
                }
            }
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching { contentResolver.takePersistableUriPermission(uri, flags) }.isSuccess
            if (persisted) {
                selectDestination(uri)
            } else {
                message = "This folder could not be opened for writing. Choose another folder."
            }
        }
    }

    private val pickInputFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importFolder(uri)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startConversion()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        reloadBatch()
        workerLimit = preferences.getInt("workerLimit", DEFAULT_BATCH_WORKERS)
            .coerceIn(MIN_BATCH_WORKERS, MAX_BATCH_WORKERS)
        remembered = preferences.getString("folder", null)?.let(Uri::parse)
        folder = when {
            savedInstanceState?.containsKey("folder") == true -> savedInstanceState.getString("folder")?.let(Uri::parse)
            else -> remembered
        }
        val inputPaths = savedInstanceState?.getStringArrayList("inputPaths").orEmpty()
        selected = savedInstanceState?.getStringArrayList("inputs").orEmpty()
            .zip(savedInstanceState?.getStringArrayList("names").orEmpty())
            .mapIndexed { index, (uri, name) ->
                val dirs = inputPaths.getOrNull(index)?.let(::JSONArray)
                InputFile(Uri.parse(uri), name, if (dirs == null) emptyList() else
                    List(dirs.length()) { dirs.getString(it) })
            }
        (DraftStore.active(this) ?: savedInstanceState?.getString("draftId"))?.let { id ->
            draftId = id
            selected = DraftStore.load(this, id)
        }
        target = AudioTarget.fromName(savedInstanceState?.getString("target"))
        bitrate = savedInstanceState?.getInt("bitrate", 192) ?: 192
        trimSilence = savedInstanceState?.getBoolean("trimSilence") ?: false
        preserveFolders = savedInstanceState?.getBoolean("preserveFolders", true) ?: true
        loudness = Loudness.fromLufs(savedInstanceState?.getInt("loudness") ?: 0)
        presets = presetStore.load()
        submittedDraftId = savedInstanceState?.getString("submittedDraftId")
        reselectIndex = savedInstanceState?.getInt("reselectIndex", -1)?.takeIf { it >= 0 }
        retryNeedsDestination = savedInstanceState?.getBoolean("retryNeedsDestination") ?: false
        replaceRetryDestination = savedInstanceState?.getBoolean("replaceRetryDestination") ?: false
        if (savedInstanceState == null) importShare(intent)
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFFABCFB9))
                else lightColorScheme(primary = Color(0xFF35634D), surface = Color(0xFFF8FAF5))
            MaterialTheme(colorScheme = colors) { ConverterScreen() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importShare(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folder", folder?.toString())
        outState.putInt("bitrate", bitrate)
        outState.putBoolean("trimSilence", trimSilence)
        outState.putString("target", target.name)
        outState.putBoolean("preserveFolders", preserveFolders)
        outState.putInt("loudness", loudness.lufs)
        outState.putBoolean("retryNeedsDestination", retryNeedsDestination)
        outState.putBoolean("replaceRetryDestination", replaceRetryDestination)
        outState.putString("draftId", draftId)
        outState.putString("submittedDraftId", submittedDraftId)
        outState.putInt("reselectIndex", reselectIndex ?: -1)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    private fun legacyStreamUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND)
            (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)?.let(uris::add)
        else uris += intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        return uris
    }

    private fun importShare(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (intent.action == Intent.ACTION_SEND)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::add)
                else addAll(intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty())
            } else addAll(legacyStreamUris(intent))
            intent.clipData?.let { clip ->
                repeat(clip.itemCount) { i -> clip.getItemAt(i).uri?.let(::add) }
            }
        }
        uris.filter { it.scheme == "content" }.forEach { uri ->
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        importFiles(uris)
    }

    private fun importFiles(uris: List<Uri>) {
        val files = uris.filter { it.scheme == "content" }.distinct()
        if (files.isEmpty()) return
        importCount++
        lifecycleScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    files.map { Storage.describe(this@MainActivity, it) }
                }
                message = null
                addInputs(imported)
            } catch (_: IOException) {
                message = "Could not read the selected files. Check available storage and try again."
            } finally {
                importCount--
            }
        }
    }

    private fun addInputs(files: List<InputFile>) {
        val byDocument = linkedMapOf<Uri, InputFile>()
        for (file in selected + files) {
            val key = if (DocumentsContract.isDocumentUri(this, file.uri)) {
                DocumentsContract.buildDocumentUri(file.uri.authority!!, DocumentsContract.getDocumentId(file.uri))
            } else file.uri
            val previous = byDocument[key]
            if (previous == null || (previous.directories.isEmpty() && file.directories.isNotEmpty()))
                byDocument[key] = file
        }
        if (!saveSelection(byDocument.values.toList())) return
        retryNeedsDestination = false
        replaceRetryDestination = false
    }

    private fun importFolder(uri: Uri) {
        scanningFolder = true
        message = null
        lifecycleScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    Storage.convertibleFiles(this@MainActivity, uri)
                }
                addInputs(files)
                if (files.isEmpty()) message = "No convertible files found in this folder."
            } catch (_: IOException) {
                message = "Could not read this folder."
            } catch (_: SecurityException) {
                message = "Could not access this folder."
            } finally {
                scanningFolder = false
            }
        }
    }

    private fun selectDestination(value: Uri?) {
        folder = value
        if (retryNeedsDestination || (selected.isEmpty() && BatchStore.batch.value.retryableItems.isNotEmpty())) {
            replaceRetryDestination = true
        }
        retryNeedsDestination = false
        message = null
    }

    private fun currentPreset(name: String) =
        ConversionPreset(name, target, bitrate, trimSilence, preserveFolders, loudness, folder)

    private fun savePresets(values: List<ConversionPreset>) {
        presetStore.save(values)
        presets = values
    }

    private fun applyPreset(preset: ConversionPreset) {
        target = preset.target
        bitrate = preset.bitrate
        trimSilence = preset.trimSilence
        preserveFolders = preset.preserveFolders
        loudness = preset.loudness
        folder = preset.destination
        replaceRetryDestination = false
        message = null
        val destination = preset.destination ?: return
        lifecycleScope.launch {
            importCount++
            try {
                if (!withContext(Dispatchers.IO) { Storage.canWrite(this@MainActivity, destination) } &&
                    folder == destination
                ) message = "Preset folder unavailable. Choose it again or use Downloads."
            } finally {
                importCount--
            }
        }
    }

    private fun rememberFolder(value: Uri?) {
        remembered = value
        preferences.edit { putString("folder", value?.toString()) }
    }

    private fun prepareConversion() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notificationAsked", false)
        ) {
            preferences.edit { putBoolean("notificationAsked", true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startConversion()
    }

    private fun startConversion() {
        val retry = selected.isEmpty()
        val requestedDraft = draftId.takeUnless { retry }
        val files = if (retry) BatchStore.batch.value.retryableItems.map(Item::input) else selected
        if (files.isEmpty()) return
        val replace = retry && replaceRetryDestination
        val destination = if (retry && !replace) BatchStore.batch.value.destination else folder
        message = null
        lifecycleScope.launch {
            importCount++
            val writable = try {
                destination == null || withContext(Dispatchers.IO) { Storage.canWrite(this@MainActivity, destination) }
            } finally {
                importCount--
            }
            if (!writable) {
                if (retry) retryNeedsDestination = true
                message = "Folder unavailable. Choose it again or use Downloads."
                return@launch
            }
            if (!retry) submittedDraftId = requestedDraft
            ConversionService.start(this@MainActivity, files, destination, target, bitrate, retry = retry,
                workerLimit = workerLimit, trimSilence = trimSilence, preserveFolders = preserveFolders,
                loudness = loudness, replaceRetryDestination = replace, draftId = requestedDraft)
        }
    }

    private fun launchOutput(uri: Uri, label: String, mime: String, share: Boolean) {
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        } else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.clipData = ClipData.newRawUri(label, uri)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { startActivity(if (share) Intent.createChooser(intent, null) else intent) }
            .onFailure { message = "No app could open this file." }
    }

    @Composable
    private fun PresetControls(enabled: Boolean) {
        var menu by rememberSaveable { mutableStateOf(false) }
        var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
        var naming by rememberSaveable { mutableStateOf(false) }
        var renaming by rememberSaveable { mutableStateOf(false) }
        var name by rememberSaveable { mutableStateOf("") }
        val preset = presets.firstOrNull { it.name == selectedName }
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = enabled) { Text("Presets") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Save current settings") }, onClick = {
                    menu = false
                    renaming = false
                    name = ""
                    naming = true
                })
                presets.forEach { entry ->
                    DropdownMenuItem(text = { Text(entry.name) }, onClick = {
                        menu = false
                        selectedName = entry.name
                    })
                }
            }
        }
        if (preset != null && !naming) {
            AlertDialog(onDismissRequest = { selectedName = null },
                title = { Text(preset.name) },
                text = {
                    Column {
                        TextButton(onClick = {
                            savePresets(presets.map { if (it == preset) currentPreset(it.name) else it })
                            selectedName = null
                        }) { Text("Update from current settings") }
                        TextButton(onClick = {
                            renaming = true
                            name = preset.name
                            naming = true
                        }) { Text("Rename") }
                        TextButton(onClick = {
                            savePresets(presets - preset)
                            selectedName = null
                        }) { Text("Delete") }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        applyPreset(preset)
                        selectedName = null
                    }) { Text("Apply") }
                },
                dismissButton = { TextButton(onClick = { selectedName = null }) { Text("Cancel") } },
            )
        }
        if (naming) {
            val candidate = name.trim()
            val duplicate = presets.any { it.name.equals(candidate, ignoreCase = true) && (!renaming || it != preset) }
            AlertDialog(onDismissRequest = { naming = false },
                title = { Text(if (renaming) "Rename preset" else "Save preset") },
                text = {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") },
                        singleLine = true, isError = duplicate,
                        supportingText = if (duplicate) ({ Text("That name already exists.") }) else null)
                },
                confirmButton = {
                    TextButton(enabled = candidate.isNotBlank() && !duplicate, onClick = {
                        val updated = if (renaming && preset != null)
                            presets.map { if (it == preset) it.copy(name = candidate) else it }
                        else presets + currentPreset(candidate)
                        savePresets(updated)
                        naming = false
                        selectedName = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun ConverterScreen() {
        var showOptions by rememberSaveable { mutableStateOf(false) }
        val batch by BatchStore.batch.collectAsState()
        val progress by BatchStore.progress.collectAsState()
        LaunchedEffect(batch.draftId) {
            if (batch.draftId != null && (batch.draftId == draftId || batch.draftId == submittedDraftId)) {
                val accepted = batch.items.map { it.input.copy(staged = null) }.toSet()
                if (saveSelection(selected.filterNot(accepted::contains))) {
                    submittedDraftId = null
                    retryNeedsDestination = false
                    replaceRetryDestination = false
                }
            }
        }
        val busy by ConversionService.busy.collectAsState()
        val startError by ConversionService.startError.collectAsState()
        val recoveryError by BatchStore.recoveryError.collectAsState()
        val retry = batch.retryableItems.isNotEmpty()
        val retrying = selected.isEmpty() && retry
        val currentFolder = folder
        var showLoudness by rememberSaveable { mutableStateOf(false) }
        val savedCount = batch.items.count { it.status == Status.SAVED }
        val editable = !busy && !loadingBatch && importCount == 0 && !scanningFolder
        if (showOptions) {
            var pendingLimit by rememberSaveable { mutableStateOf(workerLimit) }
            AlertDialog(
                onDismissRequest = { showOptions = false },
                title = { Text("Options") },
                text = {
                    Column {
                        Text("Parallel files · $pendingLimit")
                        Slider(
                            value = pendingLimit.toFloat(),
                            onValueChange = { pendingLimit = it.roundToInt() },
                            valueRange = MIN_BATCH_WORKERS.toFloat()..MAX_BATCH_WORKERS.toFloat(),
                            steps = MAX_BATCH_WORKERS - MIN_BATCH_WORKERS - 1,
                        )
                        Text("Applies to the next batch. High values may slow some devices.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        workerLimit = pendingLimit
                        preferences.edit { putInt("workerLimit", pendingLimit) }
                        showOptions = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showOptions = false }) { Text("Cancel") } },
            )
        }
        Scaffold { insets ->
            Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Video Converter", style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.weight(1f))
                            TextButton(onClick = { showOptions = true }) { Text("Options") }
                        }
                    }
                    item {
                        Column {
                            OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, enabled = editable) {
                                Text("Pick files")
                            }
                            OutlinedButton(onClick = { pickInputFolder.launch(null) }, enabled = editable) {
                                Text("Pick input folder")
                            }
                            if (scanningFolder) {
                                Text("Scanning folder…")
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                    item { PresetControls(editable) }
                    if (selected.isNotEmpty()) {
                        item { Text("${selected.size} selected", style = MaterialTheme.typography.titleMedium) }
                        items(selected, key = { it.uri }) { file ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(file.name, modifier = Modifier.weight(1f), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                TextButton(onClick = { saveSelection(selected - file) }, enabled = editable) {
                                    Text("Remove")
                                }
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Text("Save to", style = MaterialTheme.typography.titleSmall)
                                Text(currentFolder?.let(Storage::folderName) ?: Storage.defaultFolder)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = {
                                        pickFolder.launch(if (retryNeedsDestination) batch.destination else currentFolder)
                                    }, enabled = editable) {
                                        Text("Choose folder")
                                    }
                                    if (currentFolder != null || retryNeedsDestination) {
                                        TextButton(onClick = {
                                            selectDestination(null)
                                        }, enabled = editable) { Text("Use Downloads") }
                                    }
                                }
                                if (currentFolder != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = currentFolder == remembered, enabled = editable,
                                            onCheckedChange = { rememberFolder(if (it) currentFolder else null) })
                                        Text("Remember this folder")
                                    }
                                } else if (remembered != null) {
                                    TextButton(onClick = { selectDestination(remembered) }, enabled = editable) {
                                        Text("Use remembered folder")
                                    }
                                    TextButton(onClick = { rememberFolder(null) }, enabled = editable) {
                                        Text("Forget remembered folder")
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Box {
                            OutlinedButton(onClick = { showTargets = true }, enabled = editable) {
                                Text("Output format · ${target.label}")
                            }
                            DropdownMenu(expanded = showTargets, onDismissRequest = { showTargets = false }) {
                                AudioTarget.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                        target = option
                                        showTargets = false
                                    })
                                }
                            }
                        }
                    }
                    if (target == AudioTarget.MP3) item {
                        TextButton(onClick = { showQuality = !showQuality }, enabled = editable) {
                            Text("MP3 quality · $bitrate kbps")
                        }
                        if (showQuality) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(128, 192, 320).forEach { option ->
                                    FilterChip(selected = bitrate == option, onClick = { bitrate = option },
                                        label = { Text("$option kbps") }, enabled = editable)
                                }
                            }
                        }
                    }
                    if (selected.any { it.directories.isNotEmpty() }) item {
                        val checked = preserveFolders
                        val enabled = editable
                        Row(Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch) { preserveFolders = it },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Preserve folder structure", Modifier.weight(1f))
                            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                        }
                    }
                    item {
                        Box {
                            OutlinedButton(onClick = { showLoudness = true }, enabled = editable) {
                                Text("Normalize loudness · ${loudness.label}")
                            }
                            DropdownMenu(showLoudness, onDismissRequest = { showLoudness = false }) {
                                Loudness.entries.forEach { option ->
                                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                                        loudness = option
                                        showLoudness = false
                                    })
                                }
                            }
                        }
                    }
                    item {
                        val checked = trimSilence
                        val enabled = editable
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(checked, enabled, Role.Switch) { trimSilence = it },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Trim silence")
                                Text("Trim both ends · 1-second fade-out", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
                        }
                    }
                    item {
                        if (busy) {
                            OutlinedButton(onClick = { ConversionService.cancel(this@MainActivity) },
                                modifier = Modifier.fillMaxWidth()) { Text("Cancel batch") }
                            if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            if (retrying) {
                                val retryFolder = if (replaceRetryDestination) folder else batch.destination
                                val destinationLabel = retryFolder?.let(Storage::folderName) ?: Storage.defaultFolder
                                Text("Retry uses ${batch.formatLabel} · $destinationLabel",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = ::prepareConversion, enabled = editable && (selected.isNotEmpty() || retry),
                                modifier = Modifier.fillMaxWidth()) {
                                Text(if (selected.isEmpty() && retry) "Retry unfinished files"
                                    else "Convert to ${target.label}")
                            }
                        }
                    }
                    (message ?: startError ?: recoveryError)?.let { text ->
                        item { Text(text, color = MaterialTheme.colorScheme.error) }
                    }
                    if (recoveryError != null && !busy) {
                        item {
                            TextButton(onClick = ::reloadBatch, enabled = !loadingBatch) { Text("Retry journal recovery") }
                        }
                    }
                    if (batch.cleanup.isNotEmpty()) {
                        item {
                            Text("Check previous output folders for ${batch.cleanup.size} unresolved files.")
                        }
                    }
                    if (batch.items.isNotEmpty()) {
                        item {
                            val summary = if (batch.active) {
                                "${batch.completedCount} of ${batch.items.size} finished · ${batch.activeCount} active"
                            } else {
                                "$savedCount of ${batch.items.size} saved"
                            }
                            Text(summary, style = MaterialTheme.typography.titleMedium)
                        }
                        itemsIndexed(batch.items) { index, entry ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(entry.input.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val status = when (entry.status) {
                                        Status.QUEUED -> "Queued"
                                        Status.CONVERTING -> "Converting"
                                        Status.SAVED -> "${batch.formatLabel} saved"
                                        Status.FAILED, Status.INTERRUPTED -> if (batch.legacyVideo)
                                            "MP4 export ended. Select the source again to extract audio."
                                        else entry.detail
                                    }
                                    Text(status, style = MaterialTheme.typography.bodySmall)
                                    if (entry.inputUnavailable && !busy) {
                                        TextButton(onClick = {
                                            reselectIndex = index
                                            reselectInput.launch(arrayOf("*/*"))
                                        }) { Text("Select source again") }
                                    }
                                    entry.output?.let { uri ->
                                        Row {
                                            TextButton(onClick = { launchOutput(uri, batch.formatLabel, batch.mime, false) }) { Text("Open") }
                                            TextButton(onClick = { launchOutput(uri, batch.formatLabel, batch.mime, true) }) { Text("Share") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Box(Modifier.padding(bottom = 8.dp)) }
                }
            }
        }
    }
}
