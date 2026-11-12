package dev.dogsc.app.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.documentfile.provider.DocumentFile
import dev.dogsc.app.android.AndroidRecorderManager
import dev.dogsc.app.android.recording.FragmentedRecordingRecovery
import androidx.compose.ui.graphics.asImageBitmap
import dev.dogsc.app.platform.LoadedProject
import dev.dogsc.app.platform.MediaProbe
import dev.dogsc.app.platform.ProjectSummary
import dev.dogsc.app.platform.ScenePreset
import dev.dogsc.app.ui.Loc
import dev.dogsc.core.model.CaptureConfiguration
import dev.dogsc.core.model.CaptureSource
import dev.dogsc.core.model.PointerEventKind
import dev.dogsc.core.model.PointerEventRecord
import dev.dogsc.core.model.ProjectMediaManifest
import dev.dogsc.core.model.ProjectMediaReference
import dev.dogsc.core.model.RecorderProject
import dev.dogsc.core.project.ProjectCodec
import dev.dogsc.core.project.ProjectPackageCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class AndroidProjectRepository(private val context: Context) {
    private val root = File(context.getExternalFilesDir(null) ?: context.filesDir, "DogSC")
    private val projectsDir = File(root, "projects").apply { mkdirs() }
    private val trashDir = File(root, "trash").apply { mkdirs() }
    private val thumbnailCache = LruCache<String, Bitmap>(24)
    private val previewImageCache = HashMap<String, Pair<Double, ImageBitmap>>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        projectsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            runCatching { loadSummary(dir) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun loadProject(id: String): LoadedProject? = withContext(Dispatchers.IO) {
        val dir = File(projectsDir, id)
        val manifest = File(dir, "project.json")
        if (!manifest.exists()) return@withContext null
        val decoded = runCatching { ProjectCodec.decode(manifest.readText()) }.getOrNull() ?: return@withContext null
        val project = if (decoded.title.all { it.isDigit() } && decoded.title.isNotBlank()) {
            decoded.copy(title = Loc.t("导入的视频"))
        } else decoded
        val summary = loadSummary(dir) ?: return@withContext null
        val duration = probe(File(dir, project.media?.screen?.relativePath ?: ""))?.durationSeconds ?: 0.0
        val pointerEvents = readPointerEvents(project, dir)
        val camera = project.media?.camera?.let { probe(File(dir, it.relativePath)) }
        LoadedProject(
            project = project,
            sourceDuration = duration,
            summary = summary,
            pointerEvents = pointerEvents,
            cameraAspectRatio = camera?.let { if (it.height > 0) it.width.toDouble() / it.height else null },
        )
    }

    suspend fun saveProject(project: RecorderProject): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(projectsDir, project.id).apply { mkdirs() }
            val temp = File(dir, "project.json.tmp")
            temp.writeText(ProjectCodec.encode(project))
            val target = File(dir, "project.json")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
            ensureThumbnail(project, dir)
            true
        }.getOrDefault(false)
    }

    suspend fun deleteProject(id: String): Boolean = withContext(Dispatchers.IO) {
        val source = File(projectsDir, id)
        if (!source.exists()) return@withContext false
        val destination = File(trashDir, "${id}-${System.currentTimeMillis()}")
        source.renameTo(destination)
    }

    suspend fun exportProjectPackage(id: String, outputUri: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(projectsDir, id)
        if (!dir.exists()) return@withContext false
        runCatching {
            context.contentResolver.openOutputStream(Uri.parse(outputUri))?.use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                    dir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val name = file.relativeTo(dir).invariantSeparatorsPath
                        if (name.startsWith("../")) return@forEach
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: return@withContext false
            true
        }.getOrDefault(false)
    }

    suspend fun importProjectPackage(uri: String): String? = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "project-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.replace('\\', '/')
                        if (name.startsWith("/") || name.split('/').any { it == ".." }) continue
                        val target = File(temp, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                        zip.closeEntry()
                    }
                }
            } ?: return@withContext null
            importExtractedPackage(temp)
        } catch (_: Throwable) {
            null
        } finally {
            temp.deleteRecursively()
        }
    }

    suspend fun importProjectDirectory(uri: String): String? = withContext(Dispatchers.IO) {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
            ?: return@withContext null
        if (!root.isDirectory) return@withContext null
        val temp = File(context.cacheDir, "project-dir-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            copyDocumentTree(root, temp)
            importExtractedPackage(temp)
        } catch (_: Throwable) {
            null
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun importExtractedPackage(temp: File): String? {
        val manifest = temp.walkTopDown().firstOrNull { it.isFile && it.name == "project.json" }
            ?: return null
        val root = manifest.parentFile ?: temp
        val rawProject = manifest.readText()
        val directError = runCatching { ProjectCodec.decode(rawProject) }.exceptionOrNull()
        val adaptedText = if (directError != null) ProjectPackageCompat.adapt(rawProject) else null
        val decoded = if (directError == null) {
            ProjectCodec.decode(rawProject)
        } else {
            val adaptedError = adaptedText?.let { runCatching { ProjectCodec.decode(it) }.exceptionOrNull() }
            if (adaptedError != null) {
                Log.e("DogSCImport", "decode failed direct=$directError adapted=$adaptedError", adaptedError)
            }
            adaptedText?.let { ProjectCodec.decodeOrNull(it) }
        } ?: return null
        val newId = UUID.randomUUID().toString()
        val project = decoded.copy(
            id = newId,
            title = decoded.title.ifBlank { Loc.t("导入的视频") }.take(200),
            createdAtEpochMs = if (decoded.createdAtEpochMs > 0) decoded.createdAtEpochMs else System.currentTimeMillis(),
        )
        val screenRelative = project.media?.screen?.relativePath
        if (screenRelative == null || !File(root, screenRelative).exists()) {
            Log.e("DogSCImport", "missing screen $screenRelative root=$root")
            return null
        }
        val destination = File(projectsDir, newId)
        if (!root.copyRecursively(destination, overwrite = true)) {
            Log.e("DogSCImport", "copy failed $root -> $destination")
            return null
        }
        File(destination, "project.json").writeText(ProjectCodec.encode(project))
        ensureThumbnail(project, destination)
        return newId
    }

    private fun copyDocumentTree(source: DocumentFile, destination: File) {
        if (!destination.exists()) destination.mkdirs()
        source.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            if (name == "." || name == ".." || name.contains('/') || name.contains('\\')) return@forEach
            val target = File(destination, name)
            if (child.isDirectory) {
                copyDocumentTree(child, target)
            } else if (child.isFile) {
                runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    suspend fun loadTrash(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        trashDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { loadSummary(it) }
            ?.sortedByDescending { it.updatedAtEpochMs }
            ?: emptyList()
    }

    suspend fun clearTrash(): Boolean = withContext(Dispatchers.IO) {
        trashDir.listFiles()?.forEach { it.deleteRecursively() }
        trashDir.listFiles()?.isEmpty() != false
    }

    suspend fun restoreProject(id: String): Boolean = withContext(Dispatchers.IO) {
        val source = trashDir.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("$id-") }
            ?: return@withContext false
        val destination = File(projectsDir, id)
        if (destination.exists()) return@withContext false
        source.renameTo(destination)
    }

    suspend fun duplicateProject(id: String): Boolean = withContext(Dispatchers.IO) {
        val source = File(projectsDir, id)
        if (!source.exists()) return@withContext false
        val loaded = loadProject(id) ?: return@withContext false
        val newId = UUID.randomUUID().toString()
        val target = File(projectsDir, newId)
        source.copyRecursively(target, overwrite = false)
        val newProject = loaded.project.copy(
            id = newId,
            title = "${loaded.project.title} ${Loc.t("副本")}",
            createdAtEpochMs = System.currentTimeMillis(),
        )
        val manifest = File(target, "project.json")
        manifest.writeText(ProjectCodec.encode(newProject))
        File(target, "thumb.jpg").delete()
        ensureThumbnail(newProject, target)
        true
    }

    suspend fun renameProject(id: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val loaded = loadProject(id) ?: return@withContext false
        saveProject(loaded.project.copy(title = title))
    }

    suspend fun importVideo(uri: String): LoadedProject? = withContext(Dispatchers.IO) {
        runCatching {
            val id = UUID.randomUUID().toString()
            val dir = File(projectsDir, id).apply { mkdirs() }
            val mediaDir = File(dir, "media").apply { mkdirs() }
            val target = File(mediaDir, "screen.mp4")
            copyUriToFile(Uri.parse(uri), target)
            val info = probe(target) ?: run {
                dir.deleteRecursively()
                return@withContext null
            }
            val displayName = queryDisplayName(Uri.parse(uri)) ?: Loc.t("导入的视频")
            val rawTitle = displayName.substringBeforeLast('.').ifBlank { Loc.t("导入的视频") }
            val title = if (rawTitle.all { it.isDigit() } || rawTitle.length > 60) Loc.t("导入的视频") else rawTitle
            val project = RecorderProject(
                id = id,
                title = title,
                createdAtEpochMs = System.currentTimeMillis(),
                capture = CaptureConfiguration(
                    source = CaptureSource.IMPORTED,
                    recordsSystemAudio = info.hasAudio,
                    recordsMicrophone = false,
                    recordsCamera = false,
                ),
                audio = dev.dogsc.core.model.AudioStyle(systemVolume = 1.0, microphoneVolume = 1.0),
                media = ProjectMediaManifest(
                    screen = ProjectMediaReference(
                        relativePath = "media/screen.mp4",
                        durationSeconds = info.durationSeconds,
                        pixelWidth = info.width,
                        pixelHeight = info.height,
                    ),
                ),
            )
            ProjectCodec.encode(project).let { File(dir, "project.json").writeText(it) }
            ensureThumbnail(project, dir)
            val summary = loadSummary(dir) ?: return@withContext null
            LoadedProject(project, info.durationSeconds, summary, emptyList())
        }.getOrNull()
    }

    suspend fun importSticker(projectId: String, uri: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(projectsDir, projectId)
            val mediaDir = File(dir, "media").apply { mkdirs() }
            val extension = queryExtension(Uri.parse(uri)) ?: "png"
            val name = "sticker-${UUID.randomUUID()}.$extension"
            copyUriToFile(Uri.parse(uri), File(mediaDir, name))
            "media/$name"
        }.getOrNull()
    }

    suspend fun importBackground(projectId: String, uri: String, isVideo: Boolean): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(projectsDir, projectId)
            val mediaDir = File(dir, "media").apply { mkdirs() }
            val extension = if (isVideo) "mp4" else (queryExtension(Uri.parse(uri)) ?: "png")
            val name = "background-${UUID.randomUUID()}.$extension"
            copyUriToFile(Uri.parse(uri), File(mediaDir, name))
            "media/$name"
        }.getOrNull()
    }

    private val frameMutex = Mutex()
    private val previewDecoder = PreviewFrameDecoder()

    /** Preview frames come from a persistent MediaCodec decoder, not a retriever per request. */
    private suspend fun decodePreviewFrame(file: File, atTime: Double, maxWidth: Int = 720): Bitmap? =
        frameMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { previewDecoder.frame(file, atTime, maxWidth) }.getOrNull()
            }
        }

    fun releasePreviewFrames() {
        runCatching { previewDecoder.close() }
        previewImageCache.clear()
    }

    suspend fun loadThumbnail(project: RecorderProject, atTime: Double): ImageBitmap? {
        val path = project.media?.screen?.relativePath ?: return null
        val file = File(File(projectsDir, project.id), path)
        if (!file.exists()) return null
        val bitmap = decodePreviewFrame(file, atTime) ?: return null
        return imageFor(file, bitmap)
    }

    suspend fun loadCameraThumbnail(project: RecorderProject, atTime: Double): ImageBitmap? {
        val path = project.media?.camera?.relativePath ?: return null
        val file = File(File(projectsDir, project.id), path)
        if (!file.exists()) return null
        val bitmap = decodePreviewFrame(file, atTime) ?: return null
        return imageFor(file, bitmap)
    }

    private fun imageFor(file: File, bitmap: android.graphics.Bitmap): ImageBitmap {
        val frameTime = previewDecoder.lastFrameTime(file)
        val cached = previewImageCache[file.absolutePath]
        if (cached != null && cached.first == frameTime) return cached.second
        val image = bitmap.asImageBitmap()
        previewImageCache[file.absolutePath] = frameTime to image
        return image
    }

    suspend fun loadStickerBitmap(project: RecorderProject, relativePath: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(File(projectsDir, project.id), relativePath)
        if (!file.exists()) return@withContext null
        val key = "sticker:${file.absolutePath}"
        thumbnailCache.get(key)?.let { return@withContext it.asImageBitmap() }
        runCatching {
            val bitmap = decodeBitmapCapped(file, 2_048) ?: return@runCatching null
            thumbnailCache.put(key, bitmap)
            bitmap.asImageBitmap()
        }.getOrNull()
    }

    suspend fun loadBackgroundBitmap(project: RecorderProject): ImageBitmap? = withContext(Dispatchers.IO) {
        val source = project.canvas.backgroundSource
        val relative = when (source) {
            is dev.dogsc.core.model.BackgroundSource.ProjectImage -> source.relativePath
            is dev.dogsc.core.model.BackgroundSource.ProjectVideo -> source.relativePath
            else -> return@withContext null
        }
        val file = File(File(projectsDir, project.id), relative)
        if (!file.exists()) return@withContext null
        if (source.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.asImageBitmap()
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        } else {
            runCatching {
                decodeBitmapCapped(file, 3_072)?.asImageBitmap()
            }.getOrNull()
        }
    }

    suspend fun probe(project: RecorderProject): MediaProbe? = withContext(Dispatchers.IO) {
        val dir = File(projectsDir, project.id)
        val screen = probe(File(dir, project.media?.screen?.relativePath ?: "")) ?: return@withContext null
        val camera = project.media?.camera?.let { probe(File(dir, it.relativePath)) }
        MediaProbe(
            screenDuration = screen.durationSeconds,
            screenWidth = screen.width,
            screenHeight = screen.height,
            cameraAspectRatio = camera?.let { if (it.height > 0) it.width.toDouble() / it.height else null },
            hasMicrophoneAudio = project.media?.microphone?.let { File(dir, it.relativePath).exists() } == true,
            hasSystemAudio = project.media?.systemAudio?.let { File(dir, it.relativePath).exists() } == true || screen.hasAudio,
            pointerEventCount = readPointerEvents(project, dir).size,
        )
    }

    fun projectDirectory(id: String): File = File(projectsDir, id)

    suspend fun loadScenePresets(): List<ScenePreset> = withContext(Dispatchers.IO) {
        val file = File(root, "scene-presets.json")
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ScenePreset.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    suspend fun saveScenePresets(presets: List<ScenePreset>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(root, "scene-presets.json")
            file.writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ScenePreset.serializer()), presets))
            true
        }.getOrDefault(false)
    }

    fun loadPointerEvents(project: RecorderProject): List<PointerEventRecord> =
        readPointerEvents(project, projectDirectory(project.id))

    /**
     * A recording killed mid-way leaves fragmented `.part` files next to the final media.
     * Opening the project remuxes whatever footage reached disk instead of showing an empty editor.
     */
    private fun recoverInterruptedMedia(dir: File, project: RecorderProject) {
        val media = project.media ?: return
        val references = listOfNotNull(media.screen, media.camera, media.microphone, media.systemAudio)
        for (reference in references) {
            val target = File(dir, reference.relativePath)
            runCatching { FragmentedRecordingRecovery.recover(target) }
                .onSuccess { if (it) Log.d("DogSCRecovery", "recovered ${reference.relativePath}") }
                .onFailure { Log.w("DogSCRecovery", "recovery failed for ${reference.relativePath}", it) }
        }
    }

    private fun loadSummary(dir: File): ProjectSummary? {
        val manifest = File(dir, "project.json")
        if (!manifest.exists()) return null
        val decoded = runCatching { ProjectCodec.decode(manifest.readText()) }.getOrNull() ?: return null
        val project = if (decoded.title.all { it.isDigit() } && decoded.title.isNotBlank()) {
            decoded.copy(title = Loc.t("导入的视频"))
        } else decoded
        if (AndroidRecorderManager.activeProjectId != project.id) {
            recoverInterruptedMedia(dir, project)
        }
        val screenFile = File(dir, project.media?.screen?.relativePath ?: "")
        val info = probe(screenFile)
        val thumb = File(dir, "thumb.jpg")
        return ProjectSummary(
            id = project.id,
            title = project.title,
            updatedAtEpochMs = manifest.lastModified(),
            createdAtEpochMs = project.createdAtEpochMs,
            durationSeconds = info?.durationSeconds ?: 0.0,
            hasCamera = project.media?.camera != null,
            hasMicrophone = project.media?.microphone != null,
            hasSystemAudio = project.media?.systemAudio != null || (info?.hasAudio == true),
            pixelWidth = info?.width ?: project.media?.screen?.pixelWidth,
            pixelHeight = info?.height ?: project.media?.screen?.pixelHeight,
            thumbnailPath = if (thumb.exists()) thumb.absolutePath else null,
        )
    }

    private fun ensureThumbnail(project: RecorderProject, dir: File) {
        val thumb = File(dir, "thumb.jpg")
        if (thumb.exists() && thumb.length() > 0) return
        val screen = File(dir, project.media?.screen?.relativePath ?: return)
        if (!screen.exists()) return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(screen.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
            val maxWidth = 960
            val scale = min(1f, maxWidth.toFloat() / max(bitmap.width, 1))
            val output = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
            } else bitmap
            FileOutputStream(thumb).use { output.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun probe(file: File): MediaInfo? {
        if (!file.exists() || file.length() <= 0) return null
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var width = 0
            var height = 0
            var duration = 0L
            var hasVideo = false
            var hasAudio = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = max(duration, format.getLong(MediaFormat.KEY_DURATION))
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        duration = max(duration, format.getLong(MediaFormat.KEY_DURATION))
                    }
                }
            }
            extractor.release()
            if (!hasVideo) return null
            MediaInfo(duration / 1_000_000.0, width, height, hasAudio)
        } catch (_: Exception) {
            null
        }
    }

    private fun readPointerEvents(project: RecorderProject, dir: File): List<PointerEventRecord> {
        val path = project.media?.pointerEvents?.relativePath ?: return emptyList()
        val file = File(dir, path)
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val event = json.decodeFromString(PointerEventLine.serializer(), line)
                    PointerEventRecord(
                        time = event.time,
                        location = dev.dogsc.core.model.NormalizedPoint(event.x, event.y),
                        kind = when (event.kind) {
                            "leftClick" -> PointerEventKind.LEFT_CLICK
                            "rightClick" -> PointerEventKind.RIGHT_CLICK
                            "down" -> PointerEventKind.DOWN
                            "up" -> PointerEventKind.UP
                            else -> PointerEventKind.MOVE
                        },
                        cursorAssetID = event.cursor?.let { dev.dogsc.core.model.CursorAssetID(it) },
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun copyUriToFile(uri: Uri, target: File) {
        target.parentFile?.mkdirs()
        val input = when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)
            "file" -> File(uri.path ?: "").inputStream()
            else -> context.contentResolver.openInputStream(uri)
        } ?: error("无法读取文件")
        input.use { source ->
            FileOutputStream(target).use { output -> source.copyTo(output, 1 shl 20) }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }

    private fun queryExtension(uri: Uri): String? {
        val name = queryDisplayName(uri) ?: return null
        return name.substringAfterLast('.', "").ifBlank { null }?.lowercase()
    }

    @Serializable
    private data class PointerEventLine(
        val time: Double,
        val x: Double,
        val y: Double,
        val kind: String = "move",
        val cursor: String? = null,
    )

    data class MediaInfo(
        val durationSeconds: Double,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean,
    )
}
