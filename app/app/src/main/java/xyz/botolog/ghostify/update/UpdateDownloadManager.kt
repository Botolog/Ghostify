package xyz.botolog.ghostify.update

import java.io.File
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

interface UpdateDownloader {
    suspend fun download(
        url: String,
        destPart: File,
        resumeFrom: Long,
        totalHint: Long?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Long?
}

class UpdateDownloadManager(
    scope: CoroutineScope? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val downloader: UpdateDownloader = HttpUpdateDownloader(),
) {
    private val ownedScope: CoroutineScope? = if (scope == null) {
        CoroutineScope(SupervisorJob() + ioDispatcher)
    } else {
        null
    }
    private val scope: CoroutineScope = scope ?: ownedScope!!

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val lock = Any()
    private var activeJob: Job? = null
    private var activeUrl: String? = null
    private var activeVersion: String? = null
    private var activeUpdatesDir: File? = null
    private var workManager: WorkManager? = null
    private var workerCollectJob: Job? = null
    private var cancelPending: Boolean = false

    fun currentState(): DownloadState = _state.value

    fun isDownloading(url: String? = null): Boolean {
        if (_state.value !is DownloadState.Downloading) return false
        if (url == null) return true
        synchronized(lock) { return activeUrl == url }
    }

    fun activeTarget(): Pair<String?, String?> = synchronized(lock) {
        activeUrl to activeVersion
    }

    fun bindWorkManager(manager: WorkManager) {
        synchronized(lock) {
            if (workManager === manager && workerCollectJob != null) return
            workManager = manager
            workerCollectJob?.cancel()
            workerCollectJob = scope.launch {
                try {
                    manager.getWorkInfosForUniqueWorkFlow(UpdateDownloadWorker.UNIQUE_WORK)
                        .collect { infos -> applyWorkerInfos(infos) }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun usesWorkManager(): Boolean = synchronized(lock) { workManager != null }

    fun start(updatesDir: File, info: UpdateInfo): Boolean {
        val wm = synchronized(lock) { workManager }
        if (wm != null) {
            return startViaWorker(wm, updatesDir, info)
        }
        synchronized(lock) {
            cancelPending = false
            val current = _state.value
            if (current is DownloadState.Downloading && activeUrl == info.apkDownloadUrl) {
                return false
            }
            activeJob?.cancel()
            activeUrl = info.apkDownloadUrl
            activeVersion = info.versionName
            activeUpdatesDir = updatesDir
            if (_state.value !is DownloadState.Downloading) {
                val existing = try {
                    val part = File(updatesDir, UpdateDownloadHelper.partName())
                    if (part.exists()) part.length() else 0L
                } catch (_: Exception) {
                    0L
                }
                val total = try {
                    readMeta(updatesDir)?.takeIf {
                        UpdateDownloadHelper.metaMatches(it, info.apkDownloadUrl, info.versionName)
                    }?.totalBytes
                } catch (_: Exception) {
                    null
                }
                _state.value = DownloadState.Downloading(UpdateDownloadHelper.progress(existing, total))
            }
        }
        val job = scope.launch {
            runDownload(updatesDir, info)
        }
        synchronized(lock) { activeJob = job }
        return true
    }

    fun resume(updatesDir: File, info: UpdateInfo): Boolean {
        val wm = synchronized(lock) { workManager }
        if (wm != null) {
            return startViaWorker(wm, updatesDir, info)
        }
        synchronized(lock) {
            cancelPending = false
            if (_state.value is DownloadState.Downloading && activeUrl == info.apkDownloadUrl) {
                return false
            }
            activeUrl = info.apkDownloadUrl
            activeVersion = info.versionName
            activeUpdatesDir = updatesDir
            if (_state.value !is DownloadState.Downloading) {
                val existing = try {
                    val part = File(updatesDir, UpdateDownloadHelper.partName())
                    if (part.exists()) part.length() else 0L
                } catch (_: Exception) {
                    0L
                }
                val total = try {
                    readMeta(updatesDir)?.takeIf {
                        UpdateDownloadHelper.metaMatches(it, info.apkDownloadUrl, info.versionName)
                    }?.totalBytes
                } catch (_: Exception) {
                    null
                }
                _state.value = DownloadState.Downloading(UpdateDownloadHelper.progress(existing, total))
            }
        }
        val job = scope.launch {
            runDownload(updatesDir, info)
        }
        synchronized(lock) { activeJob = job }
        return true
    }

    fun pause(): Boolean {
        val wm = synchronized(lock) { workManager }
        if (wm != null) {
            synchronized(lock) {
                if (_state.value !is DownloadState.Downloading) return false
            }
            try {
                wm.cancelUniqueWork(UpdateDownloadWorker.UNIQUE_WORK)
            } catch (_: Exception) {
                return false
            }
            return true
        }
        val jobToCancel = synchronized(lock) {
            val current = _state.value
            if (current !is DownloadState.Downloading) return false
            activeJob
        } ?: return false
        jobToCancel.cancel()
        return true
    }

    suspend fun cancelAndJoin(updatesDir: File? = null) {
        cancel(updatesDir)
        val job = synchronized(lock) { activeJob }
        job?.cancelAndJoin()
    }

    fun cancel(updatesDir: File? = null): Boolean {
        val wm = synchronized(lock) { workManager }
        val dir = updatesDir ?: synchronized(lock) { activeUpdatesDir }
        val current = _state.value
        val isActive = current is DownloadState.Downloading ||
            current is DownloadState.Paused ||
            current is DownloadState.Error
        if (!isActive) {
            if (dir != null) {
                try {
                    File(dir, UpdateDownloadHelper.partName()).delete()
                } catch (_: Exception) {
                }
                try {
                    File(dir, UpdateDownloadHelper.META_NAME).delete()
                } catch (_: Exception) {
                }
            }
            return false
        }
        synchronized(lock) { cancelPending = true }
        if (wm != null) {
            try {
                wm.cancelUniqueWork(UpdateDownloadWorker.UNIQUE_WORK)
            } catch (_: Exception) {
            }
        }
        synchronized(lock) { activeJob }?.cancel()
        if (dir != null) {
            try {
                File(dir, UpdateDownloadHelper.partName()).delete()
            } catch (_: Exception) {
            }
            try {
                File(dir, UpdateDownloadHelper.META_NAME).delete()
            } catch (_: Exception) {
            }
        }
        _state.value = DownloadState.Idle
        synchronized(lock) {
            activeJob = null
            activeUrl = null
            activeVersion = null
            activeUpdatesDir = null
        }
        return true
    }

    private fun startViaWorker(wm: WorkManager, updatesDir: File, info: UpdateInfo): Boolean {
        synchronized(lock) {
            cancelPending = false
            val current = _state.value
            if (current is DownloadState.Downloading && activeUrl == info.apkDownloadUrl) {
                return false
            }
            if (activeUrl != null && activeUrl != info.apkDownloadUrl) {
                try {
                    wm.cancelUniqueWork(UpdateDownloadWorker.UNIQUE_WORK)
                } catch (_: Exception) {
                }
            }
            activeUrl = info.apkDownloadUrl
            activeVersion = info.versionName
            activeUpdatesDir = updatesDir
            if (_state.value !is DownloadState.Downloading) {
                val existing = try {
                    val part = File(updatesDir, UpdateDownloadHelper.partName())
                    if (part.exists()) part.length() else 0L
                } catch (_: Exception) {
                    0L
                }
                val total = try {
                    readMeta(updatesDir)?.takeIf {
                        UpdateDownloadHelper.metaMatches(it, info.apkDownloadUrl, info.versionName)
                    }?.totalBytes
                } catch (_: Exception) {
                    null
                }
                _state.value = DownloadState.Downloading(UpdateDownloadHelper.progress(existing, total))
            }
        }
        try {
            wm.enqueueUniqueWork(
                UpdateDownloadWorker.UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                UpdateDownloadWorker.buildRequest(info.apkDownloadUrl, info.versionName),
            )
        } catch (_: Exception) {
            val job = scope.launch {
                runDownload(updatesDir, info)
            }
            synchronized(lock) { activeJob = job }
        }
        return true
    }

    private fun applyWorkerInfos(infos: List<WorkInfo>) {
        try {
            val info = infos.firstOrNull()
            if (info == null) {
                return
            }
            val dir = synchronized(lock) { activeUpdatesDir } ?: return
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    if (_state.value !is DownloadState.Downloaded) {
                        _state.value = DownloadState.Downloading(currentProgressOrZero(dir))
                    }
                }
                WorkInfo.State.RUNNING -> {
                    val progress = info.progress.getFloat(UpdateDownloadWorker.KEY_PROGRESS, Float.NaN)
                    val resolved = if (progress.isNaN()) currentProgressOrZero(dir) else progress.coerceIn(0f, 1f)
                    _state.value = DownloadState.Downloading(resolved)
                }
                WorkInfo.State.SUCCEEDED -> {
                    val path = info.outputData.getString(UpdateDownloadWorker.KEY_FILE)
                    val file = if (path != null) File(path) else File(dir, UpdateDownloadHelper.FINAL_NAME)
                    if (file.exists() && isValidApk(file)) {
                        _state.value = DownloadState.Downloaded(file)
                        synchronized(lock) {
                            activeJob = null
                            activeUrl = null
                            activeVersion = null
                            activeUpdatesDir = null
                        }
                    } else {
                        val restored = computeRestoredState(dir)
                        _state.value = restored
                    }
                }
                WorkInfo.State.FAILED -> {
                    val message = info.outputData.getString(UpdateDownloadWorker.KEY_ERROR) ?: "Download failed"
                    val dataProgress = info.outputData.getFloat(UpdateDownloadWorker.KEY_PROGRESS, Float.NaN)
                    val resolved = if (dataProgress.isNaN()) currentProgressOrZero(dir) else dataProgress.coerceIn(0f, 1f)
                    val partExists = try {
                        val part = File(dir, UpdateDownloadHelper.partName())
                        part.exists() && part.length() > 0
                    } catch (_: Exception) {
                        false
                    }
                    if (resolved <= 0f && !partExists) {
                        _state.value = DownloadState.Error(message, 0f, false)
                    } else {
                        _state.value = DownloadState.Error(message, resolved, partExists)
                    }
                    synchronized(lock) { activeJob = null }
                }
                WorkInfo.State.CANCELLED -> {
                    val wasCancel = synchronized(lock) { cancelPending }
                    if (wasCancel) {
                        _state.value = DownloadState.Idle
                        synchronized(lock) {
                            activeJob = null
                            activeUrl = null
                            activeVersion = null
                            activeUpdatesDir = null
                        }
                        return
                    }
                    val current = _state.value
                    if (current is DownloadState.Downloaded) return
                    _state.value = DownloadState.Paused(currentProgressOrZero(dir))
                    synchronized(lock) { activeJob = null }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun currentProgressOrZero(updatesDir: File): Float {
        return try {
            val part = File(updatesDir, UpdateDownloadHelper.partName())
            val existing = if (part.exists()) part.length() else 0L
            val total = readMeta(updatesDir)?.totalBytes
            UpdateDownloadHelper.progress(existing, total)
        } catch (_: Exception) {
            0f
        }
    }

    fun restore(updatesDir: File): DownloadState {
        synchronized(lock) { cancelPending = false }
        return try {
            val restored = computeRestoredState(updatesDir)
            _state.value = restored
            synchronized(lock) {
                when (restored) {
                    is DownloadState.Downloaded -> {
                        activeUrl = null
                        activeVersion = null
                        activeUpdatesDir = null
                        activeJob = null
                    }
                    is DownloadState.Paused -> {
                        val meta = readMeta(updatesDir)
                        if (meta != null) {
                            activeUrl = meta.url
                            activeVersion = meta.versionName
                        }
                        activeUpdatesDir = updatesDir
                        activeJob = null
                    }
                    else -> {
                        activeJob = null
                    }
                }
            }
            restored
        } catch (_: Exception) {
            DownloadState.Idle
        }
    }

    fun attachRestored(state: DownloadState) {
        _state.value = state
    }

    private suspend fun runDownload(updatesDir: File, info: UpdateInfo) {
        try {
            updatesDir.mkdirs()
            val partFile = File(updatesDir, UpdateDownloadHelper.partName())
            val finalFile = File(updatesDir, UpdateDownloadHelper.FINAL_NAME)
            val metaFile = File(updatesDir, UpdateDownloadHelper.META_NAME)

            val existingMeta = readMeta(updatesDir)
            if (existingMeta != null && !UpdateDownloadHelper.metaMatches(existingMeta, info.apkDownloadUrl, info.versionName)) {
                try {
                    partFile.delete()
                } catch (_: Exception) {
                }
            }

            if (finalFile.exists() && finalFile.length() > 0 && isValidApk(finalFile)) {
                val metaForFinal = readMeta(updatesDir)
                if (metaForFinal == null || UpdateDownloadHelper.metaMatches(metaForFinal, info.apkDownloadUrl, info.versionName)) {
                    _state.value = DownloadState.Downloaded(finalFile)
                    synchronized(lock) {
                        activeJob = null
                        activeUrl = null
                        activeVersion = null
                        activeUpdatesDir = null
                    }
                    return
                }
            }

            var existing = if (partFile.exists()) partFile.length() else 0L
            val metaTotal = readMeta(updatesDir)?.takeIf {
                UpdateDownloadHelper.metaMatches(it, info.apkDownloadUrl, info.versionName)
            }?.totalBytes
            if (!UpdateDownloadHelper.shouldResume(existing, metaTotal) && existing > 0L) {
                val totalKnown = metaTotal
                if (totalKnown != null && totalKnown > 0 && existing >= totalKnown) {
                    existing = 0L
                    try {
                        partFile.delete()
                    } catch (_: Exception) {
                    }
                }
            }
            _state.value = DownloadState.Downloading(UpdateDownloadHelper.progress(existing, metaTotal))

            val total = downloader.download(
                info.apkDownloadUrl,
                partFile,
                existing,
                metaTotal,
            ) { downloaded, totalBytes ->
                _state.value = DownloadState.Downloading(UpdateDownloadHelper.progress(downloaded, totalBytes))
                if (totalBytes != null && totalBytes > 0) {
                    writeMeta(updatesDir, info.apkDownloadUrl, info.versionName, totalBytes)
                }
            }

            writeMeta(updatesDir, info.apkDownloadUrl, info.versionName, total)

            val completedPartLen = if (partFile.exists()) partFile.length() else 0L
            if (total != null && total > 0 && completedPartLen < total) {
                _state.value = DownloadState.Paused(UpdateDownloadHelper.progress(completedPartLen, total))
                return
            }

            if (!isValidApk(partFile)) {
                _state.value = DownloadState.Error(
                    message = "Downloaded file is not a valid APK",
                    progress = UpdateDownloadHelper.progress(completedPartLen, total),
                    resumable = false,
                )
                try {
                    partFile.delete()
                } catch (_: Exception) {
                }
                synchronized(lock) {
                    activeJob = null
                    activeUrl = null
                    activeVersion = null
                }
                return
            }

            val renamed = renamePartToFinal(partFile, finalFile)
            if (!renamed || !finalFile.exists()) {
                _state.value = DownloadState.Error(
                    message = "Could not finalize downloaded update",
                    progress = UpdateDownloadHelper.progress(completedPartLen, total),
                    resumable = true,
                )
                return
            }
            _state.value = DownloadState.Downloaded(finalFile)
            synchronized(lock) {
                activeJob = null
                activeUrl = null
                activeVersion = null
                activeUpdatesDir = null
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            val wasCancel = synchronized(lock) { cancelPending }
            if (wasCancel) {
                try {
                    File(updatesDir, UpdateDownloadHelper.partName()).delete()
                } catch (_: Exception) {
                }
                try {
                    File(updatesDir, UpdateDownloadHelper.META_NAME).delete()
                } catch (_: Exception) {
                }
                _state.value = DownloadState.Idle
                synchronized(lock) {
                    activeJob = null
                    activeUrl = null
                    activeVersion = null
                    activeUpdatesDir = null
                }
                throw ce
            }
            val dir = synchronized(lock) { activeUpdatesDir } ?: updatesDir
            val progress = computePausedProgress(dir)
            _state.value = DownloadState.Paused(progress)
            throw ce
        } catch (e: Exception) {
            Timber.e("UpdateDownloadManager: download failed: ${e.message}")
            val dir = synchronized(lock) { activeUpdatesDir } ?: updatesDir
            val progress = computePausedProgress(dir)
            val resumable = progress > 0f
            _state.value = DownloadState.Error(
                message = "Download failed: ${e.message}",
                progress = progress,
                resumable = resumable,
            )
            synchronized(lock) { activeJob = null }
        }
    }

    private suspend fun computePausedProgress(updatesDir: File): Float = withContext(ioDispatcher) {
        try {
            val partFile = File(updatesDir, UpdateDownloadHelper.partName())
            val existing = if (partFile.exists()) partFile.length() else 0L
            val total = readMeta(updatesDir)?.totalBytes
            UpdateDownloadHelper.progress(existing, total)
        } catch (_: Exception) {
            0f
        }
    }

    private fun computeRestoredState(updatesDir: File): DownloadState {
        val finalFile = File(updatesDir, UpdateDownloadHelper.FINAL_NAME)
        if (finalFile.exists() && finalFile.length() > 0 && isValidApk(finalFile)) {
            return DownloadState.Downloaded(finalFile)
        }
        val partFile = File(updatesDir, UpdateDownloadHelper.partName())
        if (partFile.exists() && partFile.length() > 0) {
            val total = readMeta(updatesDir)?.totalBytes
            return DownloadState.Paused(UpdateDownloadHelper.progress(partFile.length(), total))
        }
        return DownloadState.Idle
    }

    private fun readMeta(updatesDir: File): UpdateDownloadHelper.Meta? {
        return try {
            val metaFile = File(updatesDir, UpdateDownloadHelper.META_NAME)
            if (!metaFile.exists()) return null
            UpdateDownloadHelper.decodeMeta(metaFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMeta(updatesDir: File, url: String, version: String?, total: Long?) {
        try {
            updatesDir.mkdirs()
            val metaFile = File(updatesDir, UpdateDownloadHelper.META_NAME)
            val previous = try {
                if (metaFile.exists()) UpdateDownloadHelper.decodeMeta(metaFile.readText()) else null
            } catch (_: Exception) {
                null
            }
            val resolvedTotal = total ?: previous?.takeIf { it.url == url }?.totalBytes
            metaFile.writeText(UpdateDownloadHelper.encodeMeta(url, version, resolvedTotal))
        } catch (_: Exception) {
        }
    }

    private fun isValidApk(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < 4) return false
            val header = ByteArray(4)
            file.inputStream().use { input ->
                var read = 0
                while (read < 4) {
                    val n = input.read(header, read, 4 - read)
                    if (n == -1) break
                    read += n
                }
                if (read < 4) return false
            }
            if (!UpdateDownloadHelper.isZipMagic(header)) return false
            try {
                java.util.zip.ZipFile(file).use { zip ->
                    zip.getEntry("AndroidManifest.xml") != null || zip.entries().hasMoreElements()
                }
            } catch (_: Exception) {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun renamePartToFinal(partFile: File, finalFile: File): Boolean {
        return try {
            try {
                finalFile.delete()
            } catch (_: Exception) {
            }
            if (partFile.renameTo(finalFile)) return true
            partFile.copyTo(finalFile, overwrite = true)
            partFile.delete()
            true
        } catch (_: Exception) {
            false
        }
    }
}
