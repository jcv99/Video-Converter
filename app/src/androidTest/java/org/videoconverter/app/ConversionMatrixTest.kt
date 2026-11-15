package org.videoconverter.app

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import org.json.JSONObject

public class ConversionMatrixTest : Instrumentation() {
    private lateinit var files: File
    private var filter: Regex? = null
    private var recoverVideo = false
    private var benchmark = false
    private var trimSilence = false
    private var workflow = false
    private var recovery = false
    private var midstream = false
    private var revokedDestination = false
    private val timings = mutableListOf<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        filter = arguments?.getString("filter")?.toRegex()
        recoverVideo = arguments?.getString("recoverVideo") == "true"
        benchmark = arguments?.getString("benchmark") == "true"
        workflow = arguments?.getString("workflow") == "true"
        recovery = arguments?.getString("recovery") == "true"
        midstream = arguments?.getString("midstream") == "true"
        revokedDestination = arguments?.getString("revokedDestination") == "true"
        trimSilence = arguments?.getString("trimSilence") == "true"
        files = File(targetContext.filesDir, arguments?.getString("fixtureDir") ?: "conversion-matrix")
        start()
    }

    override fun onStart() {
        val journal = android.util.AtomicFile(File(targetContext.filesDir, "batch.json"))
        val original = if (journal.baseFile.exists()) journal.readFully() else null
        var failure: Throwable? = null
        try {
            val stream = journal.startWrite()
            val fixture = if (recoverVideo) """
                {
                  "videoPreset": "HD",
                  "destination": "content://fixture/tree/downloads",
                  "items": [{
                    "input": "content://fixture/video",
                    "name": "video.mp4",
                    "status": "CONVERTING",
                    "trim": {"startMs": 250, "endMs": 850}
                  }, {
                    "input": "content://fixture/other-video",
                    "name": "other.mp4",
                    "status": "SAVED",
                    "output": "content://fixture/saved.mp4"
                  }]
                }
            """.trimIndent() else """{"items":[],"target":"FLAC","bitrate":320}"""
            stream.write(fixture.encodeToByteArray())
            journal.finishWrite(stream)
            if (!recovery && !revokedDestination) BatchStore.initialize(targetContext)
            when {
                recovery -> verifyOutputRecovery(journal)
                revokedDestination -> verifyRevokedDestination(journal)
                midstream -> verifyMidstream()
                workflow -> verifyWorkflows()
                trimSilence -> verifySilenceTrim()
                recoverVideo -> verifyVideoRecovery()
                benchmark -> benchmarkBatches()
                else -> runMatrix()
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (!ConversionService.busy.value) {
                if (original == null) journal.delete()
                else {
                    val stream = journal.startWrite()
                    try {
                        stream.write(original)
                        journal.finishWrite(stream)
                    } catch (error: Exception) {
                        journal.failWrite(stream)
                        failure = failure ?: error
                    }
                }
            }
        }
        val test = when {
            recovery -> "Export recovery, pipe cancellation, and draft enrollment"
            revokedDestination -> "Revoked destination and cleanup retention"
            midstream -> "Midstream sample-rate and channel change"
            workflow -> "Folder, preset, and normalization checks"
            trimSilence -> "Silence trim"
            recoverVideo -> "Legacy MP4 recovery"
            benchmark -> "Batch benchmark"
            else -> "Conversion matrix"
        }
        if (failure == null) finish(0, Bundle().apply {
            putString("stream", (timings + "$test passed").joinToString("\n", prefix = "\n", postfix = "\n"))
        })
        else finish(-1, Bundle().apply { putString("stream", "\n${failure.stackTraceToString()}\n") })
    }

    private fun verifyOutputRecovery(journal: android.util.AtomicFile) {
        val context = targetContext
        val directory = "Download/VideoConverterTest/recovery-${SystemClock.elapsedRealtime()}/"
        val bytes = "complete output".encodeToByteArray()
        val digest = bytes.inputStream().use { Storage.digest(it) }
        val uris = mutableListOf<Uri>()
        val entries = org.json.JSONArray()
        try {
            for (phase in listOf("allocated", "partial", "complete", "ready", "published")) {
                val reserved = Storage.planOutput(context, null, "$phase.wav", "wav",
                    directory, emptyList(), emptyList())
                val write = OutputWrite(reserved, bytes.size.toLong(), digest, phase in setOf("ready", "published"))
                val uri = Storage.createOutput(context, reserved, "audio/wav")
                uris += uri
                if (phase != "allocated") {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use {
                        it.write(if (phase == "partial") bytes.copyOf(2) else bytes)
                    }
                }
                if (phase == "published") Storage.publishOutput(context, uri, write)
                entries.put(JSONObject().put("input", "content://fixture/$phase").put("name", phase)
                    .put("status", "CONVERTING").put("pending", uri.toString().takeUnless { phase == "allocated" })
                    .put("write", write.toJson()))
            }
            journal.baseFile.writeAtomically(JSONObject().put("items", entries).toString().encodeToByteArray())
            BatchStore.initialize(context)
            val items = BatchStore.batch.value.items
            check(items.map { it.status } == listOf(Status.INTERRUPTED, Status.INTERRUPTED,
                Status.SAVED, Status.SAVED, Status.SAVED)) { items.map { "${it.input.name}: ${it.status} ${it.detail}" }.toString() }
            check(items.all { it.pending == null && it.write == null }) { items.take(2).toString() }
            check(uris.take(2).all { Storage.outputMissing(context, it) })
            for ((index, item) in items.drop(2).withIndex()) {
                val uri = checkNotNull(item.output)
                check(uri == uris[index + 2] && Storage.isPublishedDownload(context, uri))
                check(checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }.contentEquals(bytes))
            }
        } finally {
            uris.forEach { Storage.deleteOutput(context, it) }
        }
        verifyPipeCancellation()
        verifyDraftEnrollment(journal.baseFile)
    }

    private fun verifyRevokedDestination(journal: android.util.AtomicFile) {
        val context = targetContext
        // A folder grant the user revoked leaves the owner of the output unknown.
        val revoked = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AVideoConverterRevoked")
        val location = OutputLocation(revoked, null, "revoked.wav", "videoconverter-provisional.wav")
        val write = OutputWrite(location, 12, "0".repeat(64), true)
        val entries = org.json.JSONArray()
        entries.put(JSONObject().put("input", "content://fixture/revoked").put("name", "revoked.wav")
            .put("status", "FAILED").put("detail", "Folder access was lost.")
            .put("pending", "$revoked/document/revoked%3Awav")
            .put("write", write.toJson()))
        journal.baseFile.writeAtomically(JSONObject().put("items", entries).put("target", "WAV")
            .put("bitrate", 192).toString().encodeToByteArray())
        BatchStore.initialize(context)
        val retained = BatchStore.batch.value.items.single()
        check(retained.write != null && retained.pending != null) {
            "The unresolved output record was dropped: $retained"
        }
        check(retained.detail.isNotEmpty()) { "The unresolved output was not surfaced" }

        val activity = startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val input = File(files, "inputs/video-two-tracks.mp4")
        check(input.isFile)
        val path = "Download/VideoConverterTest/revoked-${SystemClock.elapsedRealtime()}/"
        try {
            runOnMainSync {
                ConversionService.start(activity, listOf(InputFile(Uri.fromFile(input), "video-two-tracks.mp4")),
                    null, AudioTarget.MP3, 192, false, path)
            }
            awaitBatch()
            val batch = BatchStore.batch.value
            check(batch.cleanup.size == 1) { batch.cleanup.toString() }
            check(batch.cleanup.single().write?.location?.parent == revoked)
            check(batch.items.any { it.status == Status.SAVED }) {
                batch.items.joinToString { "${it.status} ${it.detail}" }
            }
        } finally {
            BatchStore.batch.value.items.forEach { item ->
                item.output?.let { Storage.deleteOutput(context, it) }
            }
            runOnMainSync { activity.finish() }
        }
    }

    private fun verifyPipeCancellation() {
        for (reading in listOf(true, false)) {
            val (read, write) = android.os.ParcelFileDescriptor.createPipe()
            val started = java.util.concurrent.CountDownLatch(1)
            val cancelled = java.util.concurrent.atomic.AtomicBoolean()
            val result = java.util.concurrent.FutureTask {
                val checkActive = {
                    started.countDown()
                    if (cancelled.get()) throw java.io.InterruptedIOException("Cancelled")
                }
                runCatching {
                    if (reading) Storage.readPipe(read.fileDescriptor, java.io.OutputStream.nullOutputStream(), checkActive)
                    else Storage.writePipe(write.fileDescriptor, ByteArray(256 * 1024).inputStream(), checkActive)
                }.exceptionOrNull()
            }
            val worker = Thread(result).apply { isDaemon = true }
            try {
                worker.start()
                check(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
                SystemClock.sleep(250)
                check(!result.isDone) { "The transfer did not wait on the pipe" }
                cancelled.set(true)
                check(result.get(2, java.util.concurrent.TimeUnit.SECONDS) is java.io.InterruptedIOException)
            } finally {
                read.close()
                write.close()
            }
        }
    }

    private fun verifyDraftEnrollment(journal: File) {
        val context = targetContext
        val selection = List(6_000) { index ->
            InputFile(Uri.parse("file:///missing/${"test-input-".repeat(18)}$index.wav"), "$index.wav")
        }
        check(selection.sumOf { it.uri.toString().length } > 1024 * 1024)
        val draftId = java.util.UUID.randomUUID().toString()
        DraftStore.save(context, draftId, selection)
        val activity = startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val blockedWrite = File(journal.parentFile, "${journal.name}.pending-write")
        try {
            check(blockedWrite.mkdir())
            runOnMainSync {
                ConversionService.start(activity, selection, null, AudioTarget.WAV, 192, false, draftId = draftId)
                ConversionService.cancel(activity)
            }
            awaitBatch(expectError = true)
            check(BatchStore.batch.value.draftId != draftId)
            check(DraftStore.load(context, draftId) == selection)

            runOnMainSync {
                ConversionService.start(activity, selection, null, AudioTarget.WAV, 192, false, draftId = draftId)
                ConversionService.cancel(activity)
            }
            awaitBatch()
            val accepted = BatchStore.batch.value
            check(accepted.draftId == draftId)
            check(accepted.items.size == selection.size && accepted.items.all { it.status == Status.INTERRUPTED })
        } finally {
            blockedWrite.delete()
            DraftStore.remove(context, draftId)
            runOnMainSync { activity.finish() }
        }
    }

    private fun verifyMidstream() {
        val input = File(files, "midstream.aac")
        check(input.isFile)
        val output = File.createTempFile("midstream-", ".wav", targetContext.cacheDir)
        try {
            for (trim in listOf(false, true)) {
                for (loudness in listOf(Loudness.OFF, Loudness.STANDARD)) {
                    val code = android.os.ParcelFileDescriptor.open(
                        input, android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                    ).use { source ->
                        NativeConverter.encodeAudio(source.fd, output.path, AudioTarget.WAV.nativeId,
                            192, trim, loudness.lufs) { true }
                    }
                    check(code == 0) { "Native export failed with trim=$trim and loudness=$loudness: $code" }
                    val wav = output.readBytes()
                    val audio = ByteBuffer.wrap(wav).order(LITTLE_ENDIAN)
                    val sampleRate = audio.getInt(24)
                    val channels = audio.getShort(22).toInt()
                    var offset = 12
                    while (String(wav, offset, 4) != "data") {
                        val bytes = audio.getInt(offset + 4)
                        offset += 8 + bytes + (bytes and 1)
                    }
                    val seconds = audio.getInt(offset + 4).toDouble() / (sampleRate * channels * 2)
                    check(seconds in 1.90..2.20) { "Unexpected decoded duration: $seconds" }
                    fun crossings(start: Double): Int {
                        var count = 0
                        val first = (start * sampleRate).toInt()
                        var previous = audio.getShort(offset + 8 + first * channels * 2)
                        for (frame in first + 1 until first + sampleRate / 4) {
                            val sample = audio.getShort(offset + 8 + frame * channels * 2)
                            if (previous < 0 && sample >= 0) count++
                            previous = sample
                        }
                        return count
                    }
                    check(crossings(0.25) in 100..120 && crossings(1.30) in 210..230)
                }
            }
        } finally {
            output.delete()
        }
    }

    private fun verifyWorkflows() {
        val directory = targetContext.filesDir.resolve("workflow-verification").apply { mkdirs() }
        val input = directory.resolve("input.wav")
        val rate = 48000
        fun fixture(seconds: Double, channels: Int, sample: (Int) -> Short) {
            val frames = (seconds * rate).toInt()
            val bytes = frames * channels * 2
            input.writeBytes(ByteBuffer.allocate(44 + bytes).order(LITTLE_ENDIAN).apply {
                put("RIFF".encodeToByteArray()).putInt(36 + bytes)
                put("WAVEfmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(channels.toShort())
                putInt(rate).putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(16)
                put("data".encodeToByteArray()).putInt(bytes)
                repeat(frames) { index -> repeat(channels) { putShort(sample(index)) } }
            }.array())
        }
        fun encode(
            name: String, loudness: Loudness, format: AudioTarget = AudioTarget.WAV, trim: Boolean = false,
            progress: NativeConverter.Progress = NativeConverter.Progress { true },
        ): Int = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
            NativeConverter.encodeAudio(it.fd, directory.resolve(name).path, format.nativeId, 192,
                trim, loudness.lufs, progress)
        }
        fun tone(index: Int, amplitude: Int = 3000) =
            (amplitude * kotlin.math.sin(index * 2 * Math.PI * 1000 / rate)).toInt().toShort()

        fixture(3.0, 1) { tone(it) }
        for (loudness in Loudness.entries.filter { it != Loudness.OFF }) {
            val name = "target${loudness.lufs}.wav"
            check(encode(name, loudness) == 0) { "Failed to normalize $name" }
        }
        for (target in AudioTarget.entries) {
            val name = "encoder-${target.name}.${target.extension}"
            check(encode(name, Loudness.STANDARD, target) == 0 && directory.resolve(name).length() > 0) {
                "Failed to normalize $target"
            }
        }
        check(encode("cancel.wav", Loudness.STANDARD) { it < 15 } != 0)
        fixture(4.0, 2) { if (it in rate / 2 until rate * 7 / 2) tone(it) else 0 }
        check(encode("trimmed.wav", Loudness.STANDARD, trim = true) == 0)
        fixture(1.0, 1) { 0 }
        check(encode("silence.wav", Loudness.STANDARD) == 0)
        check(encode("silence-off.wav", Loudness.OFF) == 0)
        check(directory.resolve("silence.wav").readBytes().contentEquals(directory.resolve("silence-off.wav").readBytes()))
        fixture(0.1, 1) { tone(it) }
        check(encode("short.wav", Loudness.STANDARD) == 0)
        check(encode("short-off.wav", Loudness.OFF) == 0)
        check(directory.resolve("short.wav").readBytes().contentEquals(directory.resolve("short-off.wav").readBytes()))
        fixture(3.0, 1) { if (it in rate until rate + 8) tone(it, 32760) else tone(it, 240) }
        check(encode("peak.wav", Loudness.LOUD) == 0)
        fixture(1.0, 1) { if (it == rate - 1) 32760 else 0 }
        check(encode("tail-peak.wav", Loudness.LOUD) == 0)
        val tail = directory.resolve("tail-peak.wav").readBytes().takeLast(2).toByteArray()
        check(ByteBuffer.wrap(tail).order(LITTLE_ENDIAN).short in 1..29205)

        val name = "workflow-test-presets"
        val prefs = targetContext.getSharedPreferences(name, 0)
        try {
            val preset = ConversionPreset("Lectures", AudioTarget.FLAC, 320, true, false,
                Loudness.QUIET, Uri.parse("content://fixture/tree/lectures"))
            PresetStore(prefs).save(listOf(preset))
            check(PresetStore(prefs).load() == listOf(preset))
            val edited = preset.copy(name = "Lectures 2", destination = null)
            PresetStore(prefs).save(listOf(edited))
            check(PresetStore(prefs).load() == listOf(edited))
            PresetStore(prefs).save(emptyList())
            check(PresetStore(prefs).load().isEmpty())
        } finally {
            targetContext.deleteSharedPreferences(name)
        }

        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val inputUri = Uri.fromFile(input)
        val inputs = listOf(InputFile(inputUri, "same.wav"), InputFile(inputUri, "same.wav", listOf("A")),
            InputFile(inputUri, "same.wav", listOf("B")))
        val path = "Download/VideoConverterTest/workflow-${SystemClock.elapsedRealtime()}/"
        val outputs = mutableListOf<Uri>()
        try {
            runOnMainSync {
                ConversionService.start(activity, inputs, null, AudioTarget.WAV, 192, false, path,
                    workerLimit = 3, loudness = Loudness.QUIET)
                ConversionService.cancel(activity)
            }
            awaitBatch()
            check(BatchStore.batch.value.items.any { it.status == Status.INTERRUPTED })
            val snapshot = JSONObject(File(activity.filesDir, "batch.json").readText())
            check(snapshot.getInt("loudness") == -23 && snapshot.getBoolean("preserveFolders"))
            check(snapshot.getJSONArray("items").getJSONObject(1).getJSONArray("directories").getString(0) == "A")
            runOnMainSync {
                ConversionService.start(activity, inputs, null, AudioTarget.MP3, 128, true,
                    preserveFolders = false, loudness = Loudness.LOUD)
            }
            awaitBatch()
            check(BatchStore.batch.value.loudness == Loudness.QUIET)
            inspectOutputs(outputs).let { results ->
                check(results.map { it.first } == listOf(path, path + "A/", path + "B/"))
                check(results.all { it.second == "same.wav" })
            }
            runOnMainSync {
                ConversionService.start(activity, inputs, null, AudioTarget.WAV, 192, false, path,
                    workerLimit = 3, preserveFolders = false)
            }
            awaitBatch()
            inspectOutputs(outputs).let { results ->
                check(results.all { it.first == path })
                check(results.map { it.second }.toSet() == setOf("same (1).wav", "same (2).wav", "same (3).wav"))
            }
        } finally {
            outputs.forEach { Storage.deleteOutput(activity, it) }
            runOnMainSync { activity.finish() }
        }
    }

    private fun inspectOutputs(outputs: MutableList<Uri>): List<Pair<String, String>> {
        return BatchStore.batch.value.items.map { item ->
            check(item.status == Status.SAVED) { item.detail }
            val uri = checkNotNull(item.output)
            outputs += uri
            val columns = arrayOf("relative_path", "_display_name")
            checkNotNull(targetContext.contentResolver.query(uri, columns, null, null, null)).use {
                check(it.moveToFirst())
                it.getString(0) to it.getString(1)
            }
        }
    }

    private fun verifySilenceTrim() {
        val rate = 48000
        val level = 12000.toShort()
        val edged = ShortArray(4 * rate) { if (it in rate / 2 until rate || it in 2 * rate until 3 * rate) level else 0 }
        val directory = targetContext.cacheDir.resolve("silence-test").apply { mkdirs() }
        val input = directory.resolve("input.wav")
        val output = directory.resolve("output")

        fun fixture(samples: ShortArray) {
            input.writeBytes(ByteBuffer.allocate(44 + samples.size * 2).order(LITTLE_ENDIAN).apply {
                put("RIFF".encodeToByteArray()).putInt(36 + samples.size * 2)
                put("WAVEfmt ".encodeToByteArray()).putInt(16).putShort(1).putShort(1)
                putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
                put("data".encodeToByteArray()).putInt(samples.size * 2)
                samples.forEach { putShort(it) }
            }.array())
        }

        fun encode(
            target: AudioTarget, enabled: Boolean, progress: NativeConverter.Progress = NativeConverter.Progress { true },
        ): Int =
            ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY).use {
                NativeConverter.encode(it.fd, output.path, target.nativeId, 192, enabled, progress)
            }

        fun wav(bytes: ByteArray = output.readBytes()): ShortArray {
            val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
            buffer.position(12)
            while (buffer.remaining() >= 8) {
                val id = ByteArray(4).also { buffer.get(it) }.decodeToString()
                val size = buffer.int
                if (id == "data") return ShortArray(size / 2) { buffer.short }
                buffer.position(buffer.position() + size + size % 2)
            }
            error("Missing WAV data chunk")
        }

        try {
            fixture(edged)
            check(encode(AudioTarget.WAV, false) == 0 && wav().contentEquals(edged))
            check(encode(AudioTarget.WAV, true) == 0)
            val trimmed = wav()
            check(trimmed.size == 5 * rate / 2 && trimmed.first() == level)
            check(trimmed.sliceArray(rate / 2 until 3 * rate / 2).all { it == 0.toShort() })
            check(trimmed[3 * rate / 2] == level && trimmed[2 * rate] in 5999..6001 && trimmed.last() == 0.toShort())
            for (target in AudioTarget.entries) {
                check(encode(target, true) == 0 && output.length() > 0) { "Failed to trim $target" }
            }
            check(encode(AudioTarget.WAV, true) { it < 10 } != 0)
            check(encode(AudioTarget.WAV, true) { it < 60 } != 0)
            fixture(ShortArray(rate / 2) { level })
            check(encode(AudioTarget.WAV, true) == 0)
            val short = wav()
            check(short.size == rate / 2 && short.first() == level && short[rate / 4] in 5999..6001 && short.last() == 0.toShort())
            fixture(ShortArray(rate))
            check(encode(AudioTarget.WAV, true) == 0 && wav().contentEquals(ShortArray(rate)))
            fixture(edged)
            verifySilenceTrimRetry(input) { wav(it).contentEquals(trimmed) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun verifySilenceTrimRetry(input: File, matchesTrimmed: (ByteArray) -> Boolean) {
        var activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val file = InputFile(Uri.fromFile(input), "silence-test.wav")
        val path = "Download/VideoConverterTest/silence-${SystemClock.elapsedRealtime()}/"
        try {
            check(!BatchStore.batch.value.trimSilence)
            check(findTrimSwitch(checked = false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            findTrimSwitch(checked = true)
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { activity.recreate() }
            activity = checkNotNull(waitForMonitorWithTimeout(monitor, 5000))
            findTrimSwitch(checked = true)
            runOnMainSync {
                ConversionService.start(activity, listOf(file), null, AudioTarget.WAV, 192, false,
                    path, trimSilence = true)
                ConversionService.cancel(activity)
            }
            awaitBatch()
            val interrupted = BatchStore.batch.value.items.single()
            check(interrupted.status == Status.INTERRUPTED && interrupted.output == null && interrupted.pending == null)
            val journal = android.util.AtomicFile(File(activity.filesDir, "batch.json"))
            check(JSONObject(journal.readFully().decodeToString()).getBoolean("trimSilence"))
            check(!File(activity.cacheDir, "conversion").exists())
            runOnMainSync {
                ConversionService.start(activity, listOf(file), null, AudioTarget.WAV, 192, true, path)
            }
            awaitBatch()
            val retried = BatchStore.batch.value
            val item = retried.items.single()
            check(retried.trimSilence && item.status == Status.SAVED) { item.detail }
            val uri = checkNotNull(item.output)
            try {
                val bytes = checkNotNull(activity.contentResolver.openInputStream(uri)).use { it.readBytes() }
                check(matchesTrimmed(bytes))
            } finally {
                Storage.deleteOutput(activity, uri)
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun findTrimSwitch(checked: Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
            if (matches(node)) return node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { find(it, matches)?.let { result -> return result } }
            }
            return null
        }
        val deadline = SystemClock.elapsedRealtime() + 5000
        do {
            waitForIdleSync()
            uiAutomation.rootInActiveWindow?.let { root ->
                val switch = find(root) { node ->
                    node.isCheckable &&
                        (node.text?.contains("Trim silence") == true || (0 until node.childCount).any {
                            node.getChild(it)?.text?.toString() == "Trim silence"
                        })
                }
                if (switch != null) {
                    if (AccessibilityNodeInfoCompat.wrap(switch).isChecked == checked) return switch
                } else {
                    find(root) { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                }
            }
            SystemClock.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        val root = uiAutomation.rootInActiveWindow
        error("Could not find Trim silence switch with checked=$checked in ${root?.packageName}")
    }

    private fun verifyVideoRecovery() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val batch = BatchStore.batch.value
            val (unfinished, saved) = batch.items
            check(!batch.active && batch.formatLabel == "MP4" && batch.mime == "video/mp4")
            check(batch.destination == Uri.parse("content://fixture/tree/downloads"))
            check(unfinished.status == Status.INTERRUPTED)
            check(saved.status == Status.SAVED && saved.output == Uri.parse("content://fixture/saved.mp4"))
            runOnMainSync {
                ConversionService.start(activity, listOf(unfinished.input), null, AudioTarget.MP3, 192, true)
                check(!ConversionService.busy.value) { "Legacy MP4 conversion can still be retried" }
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun runMatrix() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            check(BatchStore.batch.value.target == AudioTarget.FLAC && !BatchStore.batch.value.legacyVideo)
            val inputs = File(files, "inputs").listFiles().orEmpty().sortedBy { it.name }
                .filter { file -> file.name in setOf("video-two-tracks.mp4", "video-no-audio.mp4") ||
                    filter?.containsMatchIn(file.name) != false }
                .map { InputFile(Uri.fromFile(it), it.name) }
            check(inputs.any { it.name == "video-no-audio.mp4" } && inputs.size > 2)
            val failure = mutableListOf<String>()
            val run = files.resolve("run-id").readText().trim()
            val smokeFile = inputs.single { it.name == "video-two-tracks.mp4" }
            val smokePath = "Download/VideoConverterTest/$run/checks/"
            runOnMainSync {
                ConversionService.start(activity, listOf(smokeFile), null, AudioTarget.FLAC, 192, false, smokePath)
                ConversionService.cancel(activity)
            }
            awaitBatch()
            val interrupted = BatchStore.batch.value.items.single()
            check(interrupted.status == Status.INTERRUPTED && interrupted.output == null && interrupted.pending == null)
            check(!File(activity.cacheDir, "conversion").exists()) { "Cancellation left staged files" }
            runOnMainSync {
                ConversionService.start(activity, listOf(smokeFile), null, AudioTarget.MP3, 128, true, smokePath)
            }
            awaitBatch()
            val retried = BatchStore.batch.value
            check(retried.target == AudioTarget.FLAC && retried.bitrate == 192)
            val original = checkNotNull(retried.items.single().output)
            runOnMainSync {
                ConversionService.start(activity, listOf(smokeFile, smokeFile), null, AudioTarget.FLAC, 192, false, smokePath)
            }
            awaitBatch()
            val collisions = BatchStore.batch.value.items.map { item ->
                check(item.status == Status.SAVED && item.pending == null)
                checkNotNull(item.output)
            }
            check(collisions.distinct().size == 2 && original !in collisions)
            check(collisions.map { Storage.describe(activity, it).name }.toSet() ==
                setOf("video-two-tracks (1).flac", "video-two-tracks (2).flac"))
            val journal = android.util.AtomicFile(File(activity.filesDir, "batch.json"))
            val persisted = org.json.JSONObject(journal.readFully().decodeToString()).getJSONArray("items")
            check(persisted.length() == 2)
            collisions.forEachIndexed { index, uri ->
                val item = persisted.getJSONObject(index)
                check(item.getString("status") == "SAVED" && item.getString("output") == uri.toString())
            }
            check(Storage.describe(activity, original).name == "video-two-tracks.flac")
            for (format in AudioTarget.entries) {
                runOnMainSync { ConversionService.start(activity, inputs, null, format, 192, false,
                    "Download/VideoConverterTest/$run/${format.name}/") }
                awaitBatch()
                for (item in BatchStore.batch.value.items) {
                    val silent = item.input.name == "video-no-audio.mp4"
                    val correct = if (silent) item.status == Status.FAILED && item.output == null &&
                        item.detail == "This file has no audio track." else item.status == Status.SAVED && item.output != null
                    if (!correct) failure += "${format.name}/${item.input.name}: ${item.status} ${item.detail}"
                }
                check(BatchStore.batch.value.items.size == inputs.size)
            }
            check(failure.isEmpty()) { failure.joinToString("\n") }
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun benchmarkBatches() {
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val run = files.resolve("run-id").readText().trim()
            val input = files.resolve("inputs/video-two-tracks.mp4")
            check(input.isFile)
            val inputs = List(8) { index -> InputFile(Uri.fromFile(input), "benchmark-$index.${input.extension}") }
            for ((attempt, workerLimit) in listOf(1, 2, 2, 1).withIndex()) {
                val path = "Download/VideoConverterTest/$run/benchmark/MP3-$attempt/"
                val start = SystemClock.elapsedRealtime()
                runOnMainSync {
                    ConversionService.start(activity, inputs, null, AudioTarget.MP3, 192, false,
                        path, workerLimit = workerLimit)
                }
                awaitBatch(pollMs = 10)
                val elapsed = SystemClock.elapsedRealtime() - start
                val batch = BatchStore.batch.value
                check(batch.items.size == inputs.size && batch.items.all { it.status == Status.SAVED }) {
                    batch.items.joinToString { "${it.status} ${it.detail}" }
                }
                timings += "MP3 $workerLimit worker(s): ${elapsed}ms"
                batch.items.forEach { Storage.deleteOutput(activity, checkNotNull(it.output)) }
            }
        } finally {
            runOnMainSync { activity.finish() }
        }
    }

    private fun awaitBatch(pollMs: Long = 100, expectError: Boolean = false) {
        val deadline = SystemClock.elapsedRealtime() + 240_000
        while (ConversionService.busy.value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(pollMs)
        check(!ConversionService.busy.value) { "Batch timed out" }
        val error = ConversionService.startError.value
        check((error != null) == expectError) { error ?: "Expected conversion to report a startup error." }
    }
}
