package org.videoconverter.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.io.OutputStream

internal object Storage {
    const val defaultFolder = "Downloads/VideoConverter"
    private val defaultRelativePath = "${Environment.DIRECTORY_DOWNLOADS}/VideoConverter/"
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun describe(context: Context, uri: Uri): InputFile {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return InputFile(uri, name?.takeIf(String::isNotBlank) ?: "Media")
    }

    fun folderName(folder: Uri): String = DocumentsContract.getTreeDocumentId(folder)
        .substringAfter(':').substringAfterLast('/').ifBlank { "Selected folder" }

    private fun root(folder: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))

    suspend fun convertibleFiles(context: Context, folder: Uri): List<InputFile> {
        val coroutine = currentCoroutineContext()
        val rootId = DocumentsContract.getTreeDocumentId(folder)
        val pending = ArrayDeque(listOf(rootId to emptyList<String>()))
        val seen = mutableSetOf(rootId)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val staging = File.createTempFile("folder-probe-", ".media", context.cacheDir)
        val callback = NativeConverter.Progress { coroutine.isActive }
        val resolver = context.contentResolver
        try {
            return buildList {
                while (pending.isNotEmpty()) {
                    coroutine.ensureActive()
                    val (parent, directories) = pending.removeFirst()
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, parent)
                    val files: List<InputFile> = resolver.query(children, columns, null, null, null)?.use { cursor ->
                        buildList<InputFile> {
                            while (cursor.moveToNext()) {
                                coroutine.ensureActive()
                                val id = cursor.getString(0) ?: continue
                                if (!seen.add(id)) continue
                                if (cursor.getString(2) == Document.MIME_TYPE_DIR) {
                                    pending.addLast(id to (directories + (cursor.getString(1) ?: "Folder")))
                                } else {
                                    val uri = DocumentsContract.buildDocumentUriUsingTree(folder, id)
                                    add(InputFile(uri, cursor.getString(1) ?: "Media", directories))
                                }
                            }
                        }
                    } ?: throw IOException("Could not list the selected folder.")
                    for (file in files) {
                        coroutine.ensureActive()
                        val supported = try {
                            openSeekable(context, file.uri, staging, null, coroutine::ensureActive).use {
                                NativeConverter.canConvert(it.fd, callback)
                            }
                        } catch (_: IOException) {
                            false
                        } catch (_: SecurityException) {
                            false
                        }
                        if (supported) add(file)
                    }
                }
            }
        } finally {
            staging.delete()
        }
    }

    fun canWrite(context: Context, folder: Uri): Boolean = runCatching {
        if (context.contentResolver.persistedUriPermissions.none { it.uri == folder && it.isWritePermission })
            return false
        context.contentResolver.query(root(folder), arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
            ?.use { it.moveToFirst() && it.getInt(0) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0 }
            ?: false
    }.getOrDefault(false)

    fun openSeekable(
        context: Context,
        uri: Uri,
        staging: File,
        signal: CancellationSignal?,
        checkActive: () -> Unit,
    ): ParcelFileDescriptor {
        val input = context.contentResolver.openFileDescriptor(uri, "r", signal)
            ?: throw IOException("Could not open this file.")
        try {
            // Android can supply a pipe for content shared by another app.
            Os.lseek(input.fileDescriptor, 0, OsConstants.SEEK_SET)
            return input
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.ESPIPE) {
                input.close()
                throw IOException(error)
            }
        }
        input.use { descriptor ->
            staging.outputStream().use { destination -> readPipe(descriptor.fileDescriptor, destination, checkActive) }
            descriptor.checkError()
        }
        return ParcelFileDescriptor.open(staging, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    fun hasDurableRead(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return true
        val tree = if (DocumentsContract.isTreeUri(uri)) {
            DocumentsContract.buildTreeDocumentUri(requireNotNull(uri.authority), DocumentsContract.getTreeDocumentId(uri))
        } else null
        return context.contentResolver.persistedUriPermissions.any {
            it.isReadPermission && (it.uri == uri || it.uri == tree)
        }
    }

    fun spool(context: Context, uri: Uri, file: File, signal: CancellationSignal, checkActive: () -> Unit) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r", signal)
            ?: throw IOException("Could not open this file.")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { source ->
            file.outputStream().use { destination ->
                if (isPipe(descriptor.fileDescriptor)) readPipe(descriptor.fileDescriptor, destination, checkActive)
                else copy(source, destination, checkActive)
                destination.fd.sync()
            }
            descriptor.checkError()
        }
    }

    private fun isPipe(fd: FileDescriptor): Boolean = try {
        val mode = Os.fstat(fd).st_mode
        OsConstants.S_ISFIFO(mode) || OsConstants.S_ISSOCK(mode)
    } catch (error: ErrnoException) {
        throw IOException(error)
    }

    private fun nonblocking(fd: FileDescriptor) = try {
        val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
        Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
    } catch (error: ErrnoException) {
        throw IOException(error)
    }

    private fun pipeOperation(fd: FileDescriptor, events: Int, checkActive: () -> Unit, operation: () -> Int): Int {
        val descriptor = StructPollfd().apply {
            this.fd = fd
            this.events = events.toShort()
        }
        while (true) {
            checkActive()
            try {
                if (Os.poll(arrayOf(descriptor), 100) != 0) return operation()
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN && error.errno != OsConstants.EINTR) throw IOException(error)
            }
        }
    }

    internal fun readPipe(fd: FileDescriptor, output: OutputStream, checkActive: () -> Unit) {
        nonblocking(fd)
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = pipeOperation(fd, OsConstants.POLLIN, checkActive) { Os.read(fd, buffer, 0, buffer.size) }
            if (count == 0) return
            output.write(buffer, 0, count)
        }
    }

    internal fun writePipe(fd: FileDescriptor, input: InputStream, checkActive: () -> Unit) {
        nonblocking(fd)
        val buffer = ByteArray(128 * 1024)
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count < 0) return
            var offset = 0
            while (offset < count) {
                offset += pipeOperation(fd, OsConstants.POLLOUT, checkActive) {
                    Os.write(fd, buffer, offset, count - offset)
                }
            }
        }
    }

    private fun availableName(name: String, extension: String, existing: Set<String>): String {
        val stem = name.substringBeforeLast('.', name).ifBlank { "Media" }
            .replace(Regex("[/\\\\\\p{Cntrl}]"), "_").take(64)
        var candidate = "$stem.$extension"
        var number = 1
        while (candidate.lowercase() in existing) {
            candidate = "$stem (${number++}).$extension"
        }
        return candidate
    }

    private fun directoryName(name: String): String = name.map { character ->
        if (character in """/\:*?"<>|""" || character.isISOControl()) '_' else character
    }.joinToString("").trim().trimEnd('.').ifBlank { "Folder" }

    private fun resolveDirectory(context: Context, tree: Uri, names: List<String>): Uri {
        val resolver = context.contentResolver
        var parent = root(tree)
        for (name in names) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
            val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
            val cursor = resolver.query(children, columns, null, null, null)
                ?: throw IOException("Could not list output folders.")
            val existing = cursor.use {
                var directory: Uri? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) != name) continue
                    if (cursor.getString(2) != Document.MIME_TYPE_DIR)
                        throw IOException("A file blocks the output folder: $name")
                    directory = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    break
                }
                directory
            }
            parent = existing ?: DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Could not create output folder: $name")
        }
        return parent
    }

    private fun downloadQuery(path: String, name: String? = null) = Bundle().apply {
        val pathColumn = MediaStore.Downloads.RELATIVE_PATH
        val nameColumn = MediaStore.Downloads.DISPLAY_NAME
        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
            if (name == null) "$pathColumn = ?" else "$pathColumn = ? AND $nameColumn = ?")
        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            if (name == null) arrayOf(path) else arrayOf(path, name))
        putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
    }

    fun planOutput(
        context: Context, folder: Uri?, filename: String, extension: String,
        downloadPath: String?, directories: List<String>, reserved: List<OutputWrite>,
    ): OutputLocation {
        val namesInPath = directories.map(::directoryName)
        val basePath = downloadPath ?: defaultRelativePath
        val relativePath = if (namesInPath.isEmpty()) basePath
            else basePath.trimEnd('/') + "/" + namesInPath.joinToString("/") + "/"
        val resolver = context.contentResolver
        val parent = folder?.let { resolveDirectory(context, it, namesInPath) }
        val path = relativePath.takeIf { parent == null }
        val cursor = if (parent == null) {
            resolver.query(downloads, arrayOf(MediaStore.Downloads.DISPLAY_NAME), downloadQuery(relativePath), null)
        } else {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
            resolver.query(children, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
        } ?: throw IOException("Could not list output files.")
        val names = reserved.asSequence().map { it.location }
            .filter { it.parent == parent && it.path == path }
            .map { it.name.lowercase() }.toMutableSet()
        cursor.use {
            while (it.moveToNext()) it.getString(0)?.let { name -> names += name.lowercase() }
        }
        val name = availableName(filename, extension, names)
        // Files created by the platform's external-storage provider support rename.
        val useProvisional = parent == null || parent.authority == "com.android.externalstorage.documents"
        val allocatedName = if (useProvisional) "videoconverter-${java.util.UUID.randomUUID()}.$extension" else name
        return OutputLocation(parent, path, name, allocatedName)
    }

    fun createOutput(context: Context, location: OutputLocation, mime: String): Uri {
        val resolver = context.contentResolver
        val parent = location.parent
        val output = if (parent == null) {
            resolver.insert(downloads, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, location.allocatedName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, location.path)
                put(MediaStore.Downloads.IS_PENDING, 1)
            })
        } else DocumentsContract.createDocument(resolver, parent, mime, location.allocatedName)
        return output ?: throw IOException("Could not create a file in the output folder.")
    }

    fun findReservedDownload(context: Context, location: OutputLocation): Uri? {
        val path = location.path ?: return null
        val cursor = context.contentResolver.query(downloads, arrayOf(MediaStore.Downloads._ID),
            downloadQuery(path, location.allocatedName), null) ?: throw IOException("Could not look up the output.")
        return cursor.use {
            if (it.count == 1 && it.moveToFirst()) android.content.ContentUris.withAppendedId(downloads, it.getLong(0))
            else if (it.count == 0) null
            else throw IOException("More than one document matches the output reservation.")
        }
    }

    fun digest(source: InputStream, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(128 * 1024)
        while (true) {
            checkActive()
            val count = source.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyOutput(context: Context, uri: Uri, write: OutputWrite): Boolean {
        val descriptor = try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Could not verify the output file.")
        } catch (error: java.io.FileNotFoundException) {
            // An inserted Downloads row may exist before its backing file is opened.
            if (write.location.parent == null && !isPublishedDownload(context, uri)) return false
            throw error
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { source ->
            val size = descriptor.statSize
            if (size >= 0 && size != write.size) return false
            if (isPipe(descriptor.fileDescriptor)) {
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var activity = android.os.SystemClock.elapsedRealtime()
                val meter = object : OutputStream() {
                    override fun write(value: Int) = error("Expected a buffer.")
                    override fun write(buffer: ByteArray, offset: Int, count: Int) {
                        received += count
                        if (received > write.size) throw IOException("Output exceeds the expected size.")
                        digest.update(buffer, offset, count)
                        activity = android.os.SystemClock.elapsedRealtime()
                    }
                }
                readPipe(descriptor.fileDescriptor, meter) {
                    if (android.os.SystemClock.elapsedRealtime() - activity >= 5_000)
                        throw IOException("The output provider stopped responding.")
                }
                descriptor.checkError()
                received == write.size && digest.digest().joinToString("") { "%02x".format(it) } == write.digest
            } else digest(source) == write.digest
        }
    }

    fun findDocument(context: Context, parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val columns = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID)
        val cursor = context.contentResolver.query(children, columns, null, null, null)
            ?: throw IOException("Could not list the output folder.")
        return cursor.use {
            var match: Uri? = null
            while (it.moveToNext()) {
                if (!it.getString(0).equals(name, ignoreCase = true)) continue
                if (match != null) throw IOException("More than one document matches the output reservation.")
                match = DocumentsContract.buildDocumentUriUsingTree(parent, it.getString(1))
            }
            match
        }
    }

    fun outputMissing(context: Context, uri: Uri): Boolean {
        val column = if (uri.authority == MediaStore.AUTHORITY) MediaStore.Downloads._ID else Document.COLUMN_DOCUMENT_ID
        return context.contentResolver.query(uri, arrayOf(column), null, null, null)
            ?.use { !it.moveToFirst() } ?: false
    }

    fun isPublishedDownload(context: Context, uri: Uri): Boolean {
        if (uri.authority != MediaStore.AUTHORITY) return false
        return context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.IS_PENDING), null, null, null)
            ?.use { it.moveToFirst() && it.getInt(0) == 0 } == true
    }

    fun publishOutput(context: Context, uri: Uri, write: OutputWrite): Uri {
        val location = write.location
        val resolver = context.contentResolver
        if (location.parent != null) {
            if (location.allocatedName == location.name) return uri
            return DocumentsContract.renameDocument(resolver, uri, location.name)
                ?: throw IOException("Could not publish the output file.")
        }
        if (isPublishedDownload(context, uri)) return uri
        val values = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
            put(MediaStore.Downloads.DISPLAY_NAME, write.location.name)
        }
        if (resolver.update(uri, values, null, null) != 1) throw IOException("Could not publish the output file.")
        return uri
    }

    fun export(
        context: Context,
        file: File,
        uri: Uri,
        signal: CancellationSignal,
        checkActive: () -> Unit,
    ) {
        val resolver = context.contentResolver
        val descriptor = resolver.openFileDescriptor(uri, "w", signal)
            ?: throw IOException("Could not write the output file.")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { destination ->
            file.inputStream().use { source ->
                if (isPipe(descriptor.fileDescriptor)) writePipe(descriptor.fileDescriptor, source, checkActive)
                else copy(source, destination, checkActive)
            }
            descriptor.checkError()
        }
    }

    fun deleteOutput(context: Context, uri: Uri): Boolean = runCatching {
        if (uri.authority == MediaStore.AUTHORITY) {
            context.contentResolver.delete(uri, null, null)
            true
        } else DocumentsContract.deleteDocument(context.contentResolver, uri)
    }.getOrDefault(false)

    private fun copy(source: InputStream, destination: OutputStream, checkActive: () -> Unit) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            checkActive()
            val count = source.read(buffer)
            if (count == -1) break
            destination.write(buffer, 0, count)
        }
    }
}
