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
    // Sticker bitmaps are ARGB and can be 16 MB each; size the cache in bytes, not entries.
    private val thumbnailCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    /** Deleted project ids stay tombstoned for this process so a late autosave cannot resurrect them. */
    private val trashedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Serializes manifest writes/renames/deletes so a rename cannot race an in-flight save. */
    private val writeMutex = Mutex()
    /** Summary cache keyed by manifest stamp: the library must not re-probe every video on each refresh. */
    private val summaryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, ProjectSummary>>()
    /** Last import failure reason, surfaced to the user instead of a generic toast. */
    @Volatile
    var lastImportError: String? = null
        private set
    // Access-ordered LinkedHashMap is not thread-safe; imageFor runs from concurrent
    // IO coroutines (screen / camera / background loads), so every touch is guarded.
    private val previewCacheLock = Any()
    private val previewImageCache =
        object : LinkedHashMap<String, Pair<Double, ImageBitmap>>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Double, ImageBitmap>>): Boolean =
                size > 8
        }
    private val videoDurationCache = java.util.concurrent.ConcurrentHashMap<String, Double>()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun listProjects(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        // The live recording is still writing .part files; exposing it would let the library
        // delete or "recover" a project that the encoder still owns.
        val active = AndroidRecorderManager.activeProjectId
        projectsDir.listFiles()
            ?.filter { it.isDirectory && it.name != active }
            ?.mapNotNull { dir -> runCatching { loadSummary(dir) }.getOrNull() }
            ?: emptyList()
    }

    /**
     * Decodes the live manifest, falling back to the rolling previous/ backups when it is
     * missing or corrupt. Without this the backups were write-only dead data and one bad
     * write lost the project permanently.
     */
    private fun decodeManifestWithBackup(dir: File): RecorderProject? {
        val manifest = File(dir, "project.json")
        if (manifest.exists()) {
            runCatching { ProjectCodec.decode(manifest.readText()) }.getOrNull()?.let { return it }
        }
        for (index in 1..3) {
            val backup = File(dir, "previous/project.$index.json")
            if (!backup.exists()) continue
            val recovered = runCatching { ProjectCodec.decode(backup.readText()) }.getOrNull() ?: continue
            Log.w("DogSCStore", "manifest unreadable for ${dir.name}; recovered from previous/project.$index.json")
            // Promote the good backup so future saves rotate from a valid base.
            runCatching { backup.copyTo(manifest, overwrite = true) }
            return recovered
        }
        return null
    }

    suspend fun loadProject(id: String): LoadedProject? = withContext(Dispatchers.IO) {
        val dir = File(projectsDir, id)
        // A digit-only title is a legitimate user rename; normalization happens at import only.
        val project = decodeManifestWithBackup(dir) ?: return@withContext null
        val summary = loadSummary(dir) ?: return@withContext null
        val screenFile = File(dir, project.media?.screen?.relativePath ?: "")
        if (!screenFile.exists() || screenFile.length() <= 0) return@withContext null
        val duration = probe(screenFile)?.durationSeconds?.takeIf { it > 0.01 }
            ?: project.media?.screen?.durationSeconds?.takeIf { it > 0.01 }
            ?: return@withContext null
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
        if (isTrashed(project.id)) return@withContext false
        writeMutex.withLock {
        runCatching {
            // Re-check under the lock: a delete may have landed between the entry check and here;
            // mkdirs() would otherwise recreate the folder and resurrect a ghost project.
            if (isTrashed(project.id)) return@runCatching false
            val dir = File(projectsDir, project.id).apply { mkdirs() }
            val target = File(dir, "project.json")
            // Rolling previous backups (upstream ProjectStore): keep the last few manifests so a
            // bad edit or interrupted write can be recovered. Rotate before the atomic replace.
            rotatePreviousBackups(dir, target)
            val temp = File(dir, "project.json.tmp")
            temp.writeText(ProjectCodec.encode(project))
            // fsync before rename: without it a power loss can leave the renamed
            // manifest empty even though the write "succeeded".
            runCatching {
                java.io.FileOutputStream(temp, true).use { it.fd.sync() }
            }
            // Rename is atomic on Android's filesystem; deleting the old manifest first would
            // leave a crash window with no project.json at all.
            if (!temp.renameTo(target)) {
                target.writeText(temp.readText())
                temp.delete()
            }
            ensureThumbnail(project, dir)
            true
        }.getOrDefault(false)
        }
    }

    /** Rotates project.json -> previous/project.1.json -> .2 -> .3, dropping the oldest. */
    private fun rotatePreviousBackups(dir: File, target: File) {
        if (!target.exists()) return
        runCatching {
            val previousDir = File(dir, "previous").apply { mkdirs() }
            val oldest = File(previousDir, "project.3.json")
            if (oldest.exists()) oldest.delete()
            File(previousDir, "project.2.json").renameTo(File(previousDir, "project.3.json"))
            File(previousDir, "project.1.json").renameTo(File(previousDir, "project.2.json"))
            target.copyTo(File(previousDir, "project.1.json"), overwrite = true)
        }
    }

    suspend fun deleteProject(id: String): Boolean = withContext(Dispatchers.IO) {
        if (id == AndroidRecorderManager.activeProjectId) return@withContext false
        writeMutex.withLock {
            val source = File(projectsDir, id)
            if (!source.exists()) return@withLock false
            // Tombstone before the move so a save already waiting on the lock cannot recreate
            // the folder after it lands in the trash.
            trashedIds.add(id)
            val destination = File(trashDir, "${id}-${System.currentTimeMillis()}")
            val moved = source.renameTo(destination)
            if (!moved) trashedIds.remove(id)
            summaryCache.remove(id)
            moved
        }
    }

    private fun isTrashed(id: String): Boolean {
        if (trashedIds.contains(id)) return true
        return trashDir.listFiles()?.any { it.isDirectory && it.name.startsWith("$id-") } == true
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
            } ?: run {
                lastImportError = "无法读取所选文件"
                return@withContext null
            }
            importExtractedPackage(temp)
        } catch (error: Throwable) {
            lastImportError = error.message?.take(200) ?: "解压项目包失败"
            null
        } finally {
            temp.deleteRecursively()
        }
    }

    suspend fun importProjectDirectory(uri: String): String? = withContext(Dispatchers.IO) {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
        if (root == null || !root.isDirectory) {
            lastImportError = "无法读取所选文件夹"
            return@withContext null
        }
        val temp = File(context.cacheDir, "project-dir-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            copyDocumentTree(root, temp)
            importExtractedPackage(temp)
        } catch (error: Throwable) {
            lastImportError = error.message?.take(200) ?: "读取项目文件夹失败"
            null
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun importExtractedPackage(temp: File): String? {
        val manifest = temp.walkTopDown().firstOrNull { it.isFile && it.name == "project.json" }
        if (manifest == null) {
            lastImportError = "包内没有 project.json，不是有效的项目包"
            return null
        }
        val root = manifest.parentFile ?: temp
        val rawProject = manifest.readText()
        val directResult = ProjectCodec.decodeWithResult(rawProject)
        val directError = directResult.exceptionOrNull()
        val adaptedText = if (directError != null) ProjectPackageCompat.adapt(rawProject) else null
        val decoded = if (directError == null) {
            directResult.getOrNull()
        } else {
            val adaptedResult = adaptedText?.let { ProjectCodec.decodeWithResult(it) }
            val adaptedError = adaptedResult?.exceptionOrNull()
            if (adaptedError != null) {
                Log.e("DogSCImport", "decode failed direct=$directError adapted=$adaptedError", adaptedError)
            }
            // Surface the real reason (e.g. "版本过高请升级") instead of a generic failure toast.
            lastImportError = (adaptedError ?: directError)?.message?.take(200) ?: "项目文件无法解析"
            adaptedResult?.getOrNull()
        }
        if (decoded == null) return null
        val newId = UUID.randomUUID().toString()
        val project = decoded.copy(
            id = newId,
            title = decoded.title.ifBlank { Loc.t("导入的视频") }.take(200),
            createdAtEpochMs = if (decoded.createdAtEpochMs > 0) decoded.createdAtEpochMs else System.currentTimeMillis(),
        )
        val screenRelative = project.media?.screen?.relativePath
        val screenFile = screenRelative?.let { File(root, it) }
        if (screenFile == null || !screenFile.exists() || screenFile.length() <= 0) {
            Log.e("DogSCImport", "missing screen $screenRelative root=$root")
            lastImportError = "项目包缺少屏幕录像文件"
            return null
        }
        if (probe(screenFile)?.durationSeconds?.let { it > 0.01 } != true &&
            project.media?.screen?.durationSeconds?.let { it > 0.01 } != true
        ) {
            Log.e("DogSCImport", "unreadable screen $screenFile")
            lastImportError = "项目包内的录像无法读取，可能已损坏"
            return null
        }
        val destination = File(projectsDir, newId)
        if (!root.copyRecursively(destination, overwrite = true)) {
            Log.e("DogSCImport", "copy failed $root -> $destination")
            // A failed copy used to leave an orphan directory that permanently ate disk space.
            runCatching { destination.deleteRecursively() }
            lastImportError = "复制项目文件失败，请检查存储空间"
            return null
        }
        return try {
            File(destination, "project.json").writeText(ProjectCodec.encode(project))
            ensureThumbnail(project, destination)
            lastImportError = null
            newId
        } catch (error: Throwable) {
            runCatching { destination.deleteRecursively() }
            lastImportError = error.message?.take(200) ?: "写入项目失败，请检查存储空间"
            null
        }
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
        val restored = source.renameTo(destination)
        if (restored) trashedIds.remove(id)
        restored
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
        writeMutex.withLock {
            if (isTrashed(id)) return@withLock false
            val dir = File(projectsDir, id)
            // Only the title field is rewritten from the on-disk manifest. Going through
            // loadProject + saveProject could re-encode a stale snapshot over newer edits
            // and raced the shared project.json.tmp with an in-flight autosave.
            val decoded = decodeManifestWithBackup(dir) ?: return@withLock false
            val manifest = File(dir, "project.json")
            runCatching {
                rotatePreviousBackups(dir, manifest)
                val temp = File(dir, "project.json.tmp")
                temp.writeText(ProjectCodec.encode(decoded.copy(title = title)))
                if (!temp.renameTo(manifest)) {
                    manifest.writeText(temp.readText())
                    temp.delete()
                }
                summaryCache.remove(id)
                true
            }.getOrDefault(false)
        }
    }

    /** Cheap source-side check so a multi-GB unsupported/corrupt file is rejected before copying. */
    private fun probeUri(uri: Uri): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            hasVideo && durationMs > 10
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun importVideo(uri: String): LoadedProject? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(projectsDir, id)
        var imported: LoadedProject? = null
        try {
            if (!probeUri(Uri.parse(uri))) {
                lastImportError = "无法读取这个视频，格式可能不受支持或文件已损坏"
                return@withContext null
            }
            val mediaDir = File(dir, "media").apply { mkdirs() }
            val target = File(mediaDir, "screen.mp4")
            copyUriToFile(Uri.parse(uri), target)
            val info = probe(target)?.takeIf { it.durationSeconds > 0.01 } ?: run {
                lastImportError = "视频复制后无法解析，请检查存储空间"
                return@withContext null
            }
            lastImportError = null
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
            imported = LoadedProject(project, info.durationSeconds, summary, emptyList())
            imported
        } catch (error: Throwable) {
            lastImportError = error.message?.take(200) ?: "导入视频失败，请检查存储空间"
            null
        } finally {
            if (imported == null) runCatching { dir.deleteRecursively() }
        }
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
        synchronized(previewCacheLock) { previewImageCache.clear() }
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
        synchronized(previewCacheLock) {
            val cached = previewImageCache[file.absolutePath]
            if (cached != null && cached.first == frameTime) return cached.second
        }
        val image = bitmap.asImageBitmap()
        synchronized(previewCacheLock) {
            previewImageCache[file.absolutePath] = frameTime to image
        }
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

    suspend fun loadBackgroundThumbnail(project: RecorderProject, atTime: Double): ImageBitmap? = withContext(Dispatchers.IO) {
        val source = project.canvas.backgroundSource as? dev.dogsc.core.model.BackgroundSource.ProjectVideo
            ?: return@withContext null
        val file = File(File(projectsDir, project.id), source.relativePath)
        if (!file.exists()) return@withContext null
        val duration = videoDurationCache.getOrPut(file.absolutePath) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L) / 1000.0
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrDefault(0.0)
        }
        val looped = if (duration > 0.01 && atTime.isFinite()) {
            ((atTime % duration) + duration) % duration
        } else {
            atTime.coerceAtLeast(0.0)
        }
        val bitmap = decodePreviewFrame(file, looped, 720) ?: return@withContext null
        imageFor(file, bitmap)
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
                val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return@withContext null
                val longest = max(frame.width, frame.height)
                val scaled = if (longest > 3_072) {
                    val scale = 3_072f / longest
                    Bitmap.createScaledBitmap(
                        frame,
                        max((frame.width * scale).toInt(), 2),
                        max((frame.height * scale).toInt(), 2),
                        true,
                    ).also { if (it !== frame) frame.recycle() }
                } else frame
                scaled.asImageBitmap()
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
        val project = decodeManifestWithBackup(dir) ?: return null
        val manifest = File(dir, "project.json")
        if (AndroidRecorderManager.activeProjectId != project.id) {
            recoverInterruptedMedia(dir, project)
        }
        val screenFile = File(dir, project.media?.screen?.relativePath ?: "")
        val thumb = File(dir, "thumb.jpg")
        // Probing every video on each library refresh made rename/delete lag for seconds with
        // many projects. Reuse the summary while manifest, media and thumbnail are untouched.
        val stamp = buildString {
            append(dir.absolutePath).append('|')
            append(manifest.lastModified()).append(':').append(manifest.length()).append('|')
            append(screenFile.lastModified()).append(':').append(screenFile.length()).append('|')
            append(thumb.lastModified()).append(':').append(thumb.length())
        }
        summaryCache[project.id]?.let { (cachedStamp, cached) ->
            if (cachedStamp == stamp) return cached
        }
        val info = probe(screenFile)
        val summary = ProjectSummary(
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
        summaryCache[project.id] = stamp to summary
        return summary
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
            // MediaMetadataRetriever already returns the display-oriented frame.
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
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var width = 0
            var height = 0
            var duration = 0L
            var hasVideo = false
            var hasAudio = false
            var rotation = 0
            var rotationKnown = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                        rotationKnown = true
                    }
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
            if (!hasVideo) {
                null
            } else {
                if (!rotationKnown) rotation = videoRotation(file)
                val displayWidth = if (rotation % 180 != 0) height else width
                val displayHeight = if (rotation % 180 != 0) width else height
                MediaInfo(duration / 1_000_000.0, displayWidth, displayHeight, hasAudio)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun videoRotation(file: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        } finally {
            runCatching { retriever.release() }
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
        // Display names come from the picked provider; never let them inject path separators.
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
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
