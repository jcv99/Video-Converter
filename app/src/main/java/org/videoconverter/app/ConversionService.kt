package org.videoconverter.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal class ConversionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private var handled: String? = null
    private val cancelled = AtomicBoolean()
    private val signals = mutableSetOf<CancellationSignal>()
    private val outputLock = Any()
    private val fileProgress = mutableMapOf<Int, Int>()
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(channel, "Conversion progress", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == cancelAction) {
            cancel()
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        val selection = intent?.let(::requestOf)
        if (running || selection == null || selection.id == handled) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        handled = selection.id
        cancelled.set(false)
        running = true
        try {
            startForeground(notificationId, notification("Preparing files", -1, selection.label),
                foregroundServiceType())
        } catch (error: RuntimeException) {
            Log.w("VideoConverter", "Foreground service unavailable", error)
            startError.value = "Android could not start conversion. Open the app and try again."
            running = false
            busy.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { process(selection) }
            } catch (error: Exception) {
                Log.e("VideoConverter", "Batch failed", error)
                startError.value = BatchStore.recoveryError.value ?:
                    "Could not continue the batch. Check available storage and try again."
            } finally {
                BatchStore.progress.value = -1
                running = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                busy.value = false
            }
        }
        return START_NOT_STICKY
    }

    // Android 14 introduced the mediaProcessing type. Below that, dataSync is the
    // type the platform understands, and it is declared in the manifest alongside
    // mediaProcessing so the type passed here is always a subset of it.
    @SuppressLint("InlinedApi")
    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

    private fun requestOf(intent: Intent): Request? {
        val id = intent.getStringExtra("requestId") ?: return null
        return Request(
            id = id,
            draftId = intent.getStringExtra("draftId"),
            folder = intent.getStringExtra("folder")?.let(Uri::parse),
            target = AudioTarget.fromName(intent.getStringExtra("target")),
            bitrate = intent.getIntExtra("bitrate", 192),
            retry = intent.getBooleanExtra("retry", false),
            replaceRetryDestination = intent.getBooleanExtra("replaceRetryDestination", false),
            downloadPath = intent.getStringExtra("downloadPath"),
            workerLimit = intent.getIntExtra("workerLimit", DEFAULT_BATCH_WORKERS),
            trimSilence = intent.getBooleanExtra("trimSilence", false),
            preserveFolders = intent.getBooleanExtra("preserveFolders", true),
            loudness = Loudness.fromLufs(intent.getIntExtra("loudness", 0)),
            ownsDraft = intent.getBooleanExtra("ownsDraft", false),
        )
    }

    private suspend fun process(selection: Request) {
        BatchStore.prepare(this)
        val wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoConverter:batch")
        val directory = File(cacheDir, "conversion")
        wakeLock.acquire(6 * 60 * 60 * 1000L)
        try {
            val batch = BatchStore.batch.value
            val staged = mutableListOf<File>()
            val cleanup = batch.cleanup.toMutableList()
            fun detach(item: Item): Item {
                if (item.pending != null || item.write != null) cleanup += OutputCleanup(item.pending, item.write)
                return item.copy(pending = null, write = null)
            }
            val sourceItems = if (selection.retry) batch.items.map { item ->
                if (item.status == Status.SAVED) item else {
                    val source = if (selection.replaceRetryDestination) detach(item) else item
                    source.copy(status = Status.QUEUED, detail = "")
                }
            } else {
                batch.items.forEach(::detach)
                // The selection travels through the draft store, so it survives a
                // process restart and never reaches the binder as an intent extra.
                val inputs = selection.draftId?.let { DraftStore.load(this, it) }.orEmpty()
                if (inputs.isEmpty()) throw IOException("Could not read the selected files.")
                inputs.map { Item(it) }
            }
            val items = try {
                val prepared = sourceItems.map { item ->
                    if (item.status == Status.SAVED || item.pending != null || item.write != null || item.input.staged != null ||
                        Storage.hasDurableRead(this, item.input.uri)) return@map item
                    val spool = BatchStore.spoolDirectory(this)
                    if (!spool.mkdirs() && !spool.isDirectory) throw IOException("Could not prepare input storage.")
                    val file = File.createTempFile("input-", ".media", spool)
                    staged += file
                    val signal = synchronized(signals) {
                        checkActive()
                        CancellationSignal().also(signals::add)
                    }
                    try {
                        Storage.spool(this, item.input.uri, file, signal, ::checkActive)
                        item.copy(input = item.input.copy(staged = file.name), inputUnavailable = false)
                    } catch (error: Exception) {
                        file.delete()
                        checkActive()
                        if (error is SecurityException || error is java.io.FileNotFoundException) {
                            item.copy(status = Status.FAILED, detail = "Input access was lost. Select it again.", inputUnavailable = true)
                        } else throw error
                    } finally {
                        synchronized(signals) { signals.remove(signal) }
                    }
                }
                BatchStore.save(Batch(prepared, active = true, target = selection.target, bitrate = selection.bitrate,
                    destination = selection.folder, trimSilence = selection.trimSilence,
                    downloadPath = selection.downloadPath, preserveFolders = selection.preserveFolders,
                    loudness = selection.loudness, draftId = selection.draftId ?: batch.draftId.takeIf { selection.retry },
                    cleanup = cleanup))
                // The journal holds the accepted inputs now, so a draft this service
                // created on its own is no longer needed.
                if (selection.ownsDraft) selection.draftId?.let { DraftStore.remove(this, it) }
                prepared
            } catch (error: Exception) {
                staged.forEach(File::delete)
                throw error
            }
            BatchStore.pruneSpool(this)
            val positions = items.indices.filter { items[it].status == Status.QUEUED }
            runBatchWorkers(positions, cancelled::get, selection.workerLimit) { position ->
                if (cancelled.get()) return@runBatchWorkers
                val item = BatchStore.batch.value.items[position]
                if (item.pending != null || item.write != null) {
                    BatchStore.update(position) { it.copy(status = Status.FAILED, detail = "Check the previous output or choose another destination to retry.") }
                    publishProgress(selection)
                } else {
                    convert(selection, position, item.input, File(directory, position.toString()))
                }
            }
        } finally {
            try {
                directory.deleteRecursively()
                val batch = BatchStore.batch.value
                if (batch.active) {
                    val finished = batch.copy(active = false, items = batch.items.map { item ->
                        if (item.status == Status.QUEUED || item.status == Status.CONVERTING)
                            item.copy(status = Status.INTERRUPTED, detail = "Start again to retry.")
                        else item
                    })
                    try {
                        BatchStore.save(finished)
                    } catch (error: IOException) {
                        BatchStore.requireRecovery()
                        throw error
                    }
                }
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    private fun convert(
        selection: Request, position: Int, file: InputFile, directory: File,
    ) {
        val signal = synchronized(signals) {
            if (cancelled.get()) return
            CancellationSignal().also { signals.add(it) }
        }
        val stage = File(directory, "input")
        val encoded = File(directory, "output.${selection.extension}")
        var output: Uri? = null
        var writeComplete = false
        var lastUpdated = SystemClock.elapsedRealtime()
        fun report(percent: Int) {
            val now = SystemClock.elapsedRealtime()
            if (!cancelled.get() && now - lastUpdated >= 400) {
                lastUpdated = now
                publishProgress(selection, position, percent)
            }
        }
        try {
            BatchStore.update(position) { it.copy(status = Status.CONVERTING, pending = null) }
            publishProgress(selection)
            checkActive()
            if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Could not prepare temporary storage.")
            val input = file.staged?.let { Uri.fromFile(File(BatchStore.spoolDirectory(this), it)) } ?: file.uri
            val result = Storage.openSeekable(this, input, stage, signal, ::checkActive).use { source ->
                checkActive()
                NativeConverter.encodeAudio(source.fd, encoded.path, selection.target.nativeId, selection.bitrate,
                    selection.trimSilence, selection.loudness.lufs) { percent ->
                    report(percent)
                    !cancelled.get()
                }
            }
            checkActive()
            when (result) {
                0 -> Unit
                1 -> throw IOException("Unreadable or unsupported media.")
                2 -> throw IOException("This file has no audio track.")
                3 -> throw IOException("This audio codec is unsupported.")
                else -> throw IOException("Could not encode ${selection.target.label}. Check the file and free space.")
            }
            stage.delete()
            val digest = encoded.inputStream().use { Storage.digest(it, ::checkActive) }
            val reservation = synchronized(outputLock) {
                checkActive()
                val reserved = BatchStore.batch.value.items.mapNotNull(Item::write)
                val location = Storage.planOutput(this, selection.folder, file.name, selection.extension,
                    selection.downloadPath, file.directories.takeIf { selection.preserveFolders }.orEmpty(), reserved)
                OutputWrite(location, encoded.length(), digest).also { write ->
                    BatchStore.update(position) { it.copy(write = write) }
                }
            }
            val uri = Storage.createOutput(this, reservation.location, selection.mime)
            output = uri
            BatchStore.update(position) { it.copy(pending = uri) }
            Storage.export(this, encoded, uri, signal, ::checkActive)
            writeComplete = true
            val ready = reservation.copy(ready = true)
            BatchStore.update(position) { it.copy(write = ready) }
            synchronized(outputLock) {
                val published = Storage.publishOutput(this, uri, ready)
                BatchStore.update(position) { BatchStore.savedItem(it, published) }
            }
            file.staged?.let { File(BatchStore.spoolDirectory(this), it).delete() }
        } catch (error: Exception) {
            val removed = !writeComplete && output?.let { Storage.deleteOutput(this, it) } == true
            val pending = output.takeUnless { removed }
            if (error is CancellationException) {
                BatchStore.update(position) { it.copy(pending = pending, write = it.write.takeUnless { removed }) }
                throw error
            }
            val stopped = cancelled.get()
            val inputUnavailable = output == null && !encoded.exists() &&
                (error is SecurityException || error is java.io.FileNotFoundException)
            val message = when {
                stopped -> "Conversion interrupted. Start again to retry."
                error is SecurityException -> "File or folder access was lost. Select it again."
                else -> error.localizedMessage ?: "Could not convert this file."
            }
            BatchStore.update(position) {
                it.copy(status = if (stopped) Status.INTERRUPTED else Status.FAILED, detail = message, pending = pending, write = it.write.takeUnless { removed },
                    inputUnavailable = inputUnavailable)
            }
        } finally {
            synchronized(signals) { signals.remove(signal) }
            directory.deleteRecursively()
            publishProgress(selection, position)
        }
    }

    @Synchronized
    private fun publishProgress(selection: Request, position: Int? = null, percent: Int? = null) {
        if (position != null) {
            if (percent == null || percent < 0) fileProgress.remove(position) else fileProgress[position] = percent
        }
        val batch = BatchStore.batch.value
        val completed = batch.completedCount
        val total = batch.items.size
        val active = batch.activeCount
        val activeProgress = fileProgress.entries.filter { (index, _) -> batch.items[index].status == Status.CONVERTING }
        val progress = batchProgress(total, completed, active, activeProgress.map { it.value })
        BatchStore.progress.value = progress
        val text = if (cancelled.get()) "Cancelling · $completed of $total finished"
            else "$completed of $total finished · $active active"
        notifications.notify(notificationId, notification(text, progress, selection.label))
    }

    private fun checkActive() {
        if (cancelled.get()) throw IOException("Conversion interrupted.")
    }

    private fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            val activeSignals = synchronized(signals) { signals.toList() }
            activeSignals.forEach { signal -> scope.launch(Dispatchers.IO) { signal.cancel() } }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancel()
        stopSelf()
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun notification(text: String, progress: Int, format: String): Notification {
        val show = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val cancel = PendingIntent.getService(this, 1,
            Intent(this, ConversionService::class.java).setAction(cancelAction), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_converter)
            .setContentTitle("Converting to $format")
            .setContentText(text)
            .setContentIntent(show)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceAtLeast(0), progress < 0)
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
    }

    private data class Request(
        val id: String,
        val draftId: String?,
        val folder: Uri?,
        val target: AudioTarget,
        val bitrate: Int,
        val retry: Boolean,
        val replaceRetryDestination: Boolean,
        val downloadPath: String?,
        val workerLimit: Int,
        val trimSilence: Boolean,
        val preserveFolders: Boolean,
        val loudness: Loudness,
        val ownsDraft: Boolean = false,
    ) {
        val label get() = target.label
        val extension get() = target.extension
        val mime get() = target.mime
    }

    companion object {
        private const val channel = "conversion"
        private const val notificationId = 1
        private const val cancelAction = "org.videoconverter.app.CANCEL"
        val busy = kotlinx.coroutines.flow.MutableStateFlow(false)
        val startError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

        fun start(
            context: Context, inputs: List<InputFile>, folder: Uri?, target: AudioTarget,
            bitrate: Int, retry: Boolean, downloadPath: String? = null,
            workerLimit: Int = DEFAULT_BATCH_WORKERS,
            trimSilence: Boolean = false,
            preserveFolders: Boolean = true,
            loudness: Loudness = Loudness.OFF,
            replaceRetryDestination: Boolean = false,
            draftId: String? = null,
        ) {
            if (inputs.isEmpty() || busy.value) return
            val previous = BatchStore.batch.value
            if (retry && previous.retryableItems.isEmpty()) return
            val id = java.util.UUID.randomUUID().toString()
            // The selection is stored in app-private storage and referenced by id.
            // The intent only carries the id and the batch settings, so a large
            // selection cannot exceed the binder limit.
            var ownsDraft = false
            var draft = draftId
            if (!retry && draft == null) {
                draft = java.util.UUID.randomUUID().toString()
                try {
                    DraftStore.save(context, draft, inputs)
                    ownsDraft = true
                } catch (_: IOException) {
                    DraftStore.remove(context, draft)
                    startError.value = "Could not save the selection. Check available storage."
                    return
                }
            }
            val intent = Intent(context, ConversionService::class.java)
                .putExtra("requestId", id)
                .putExtra("draftId", draft)
                .putExtra("folder", (if (retry && !replaceRetryDestination) previous.destination else folder)?.toString())
                .putExtra("target", (if (retry) previous.target else target).name)
                .putExtra("bitrate", if (retry) previous.bitrate else bitrate)
                .putExtra("retry", retry)
                .putExtra("replaceRetryDestination", replaceRetryDestination)
                .putExtra("downloadPath", if (retry && !replaceRetryDestination) previous.downloadPath else downloadPath)
                .putExtra("workerLimit", workerLimit)
                .putExtra("trimSilence", if (retry) previous.trimSilence else trimSilence)
                .putExtra("preserveFolders", if (retry) previous.preserveFolders else preserveFolders)
                .putExtra("loudness", (if (retry) previous.loudness else loudness).lufs)
                .putExtra("ownsDraft", ownsDraft)
            busy.value = true
            startError.value = null
            try {
                context.startForegroundService(intent)
            } catch (error: RuntimeException) {
                if (ownsDraft) draft?.let { DraftStore.remove(context, it) }
                busy.value = false
                startError.value = "Android could not start conversion. Open the app and try again."
            }
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ConversionService::class.java).setAction(cancelAction))
        }
    }
}
