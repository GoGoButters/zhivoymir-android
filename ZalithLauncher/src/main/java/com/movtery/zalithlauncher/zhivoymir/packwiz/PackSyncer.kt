package com.movtery.zalithlauncher.zhivoymir.packwiz

import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext

/** Сетевой доступ синхронизатора (в тестах — подмена без сети) */
interface PackHttp {
    fun getBytes(url: String): ByteArray
    fun download(url: String, out: OutputStream, onBytes: (Long) -> Unit)
}

data class SyncProgress(
    val stage: Stage,
    val doneFiles: Int,
    val totalFiles: Int,
    val bytes: Long,
) {
    enum class Stage { INDEX, METAFILES, DOWNLOAD, CLEANUP }
}

data class SyncResult(
    val downloaded: Int,
    val kept: Int,
    val deleted: Int,
    val preserved: Int,
)

class PackSyncException(message: String, cause: Throwable?) : IOException(message, cause)

class HashMismatchException(message: String) : IOException(message)

/**
 * Клиент packwiz: pack.toml → index.toml → файлы в [gameDir].
 * Инкрементально по локальному манифесту, удаляет только то, что ставил сам.
 */
class PackSyncer(
    private val packUrl: String,
    private val gameDir: File,
    private val http: PackHttp,
    private val parallelism: Int = 6,
    private val retries: Int = 3,
    private val retryDelayMs: Long = 1500,
) {
    private val manifestFile = File(gameDir, MANIFEST_PATH)

    val fs: LocalFiles = object : LocalFiles {
        override fun exists(path: String) = File(gameDir, path).isFile
        override fun hash(path: String, format: String): String? =
            File(gameDir, path).takeIf { it.isFile }?.let { PackHash.of(it, format) }
    }

    suspend fun fetchPack(): PackInfo = withContext(Dispatchers.IO) {
        PackwizToml.parsePack(retrying { http.getBytes(packUrl) }.toString(Charsets.UTF_8))
    }

    fun loadManifest(): LocalManifest = readManifest(manifestFile)

    suspend fun sync(pack: PackInfo, onProgress: (SyncProgress) -> Unit = {}): SyncResult = withContext(Dispatchers.IO) {
        onProgress(SyncProgress(SyncProgress.Stage.INDEX, 0, 0, 0))
        val indexBytes = retrying { http.getBytes(PackwizToml.resolveUrl(packUrl, pack.indexFile)) }
        verify(indexBytes, pack.indexHashFormat, pack.indexHash, pack.indexFile)
        val index = PackwizToml.parseIndex(indexBytes.toString(Charsets.UTF_8))
        val manifest = loadManifest()

        // metafile: качаем только изменившиеся
        val toFetch = SyncPlanner.metafilesToFetch(index, manifest, fs).map { it.file }.toSet()
        val fetchedMeta = HashMap<String, ModMeta>()
        val metaDone = AtomicInteger()
        onProgress(SyncProgress(SyncProgress.Stage.METAFILES, 0, toFetch.size, 0))
        parallel(index.files.filter { it.file in toFetch }) { entry ->
            val bytes = retrying { http.getBytes(PackwizToml.resolveUrl(packUrl, entry.file)) }
            verify(bytes, entry.hashFormat, entry.hash, entry.file)
            val meta = PackwizToml.parseMeta(bytes.toString(Charsets.UTF_8))
            synchronized(fetchedMeta) { fetchedMeta[entry.file] = meta }
            onProgress(SyncProgress(SyncProgress.Stage.METAFILES, metaDone.incrementAndGet(), toFetch.size, 0))
        }.firstOrNull()?.let { (entry, e) -> throw PackSyncException("Не удалось получить ${entry.file}: ${e.message}", e) }

        val targets = index.files.map { entry ->
            when {
                !entry.metafile -> SyncPlanner.directTarget(entry, packUrl)
                entry.file in fetchedMeta -> SyncPlanner.metaTarget(entry, fetchedMeta.getValue(entry.file))
                else -> SyncPlanner.cachedMetaTarget(entry, manifest.files.getValue(entry.file))
            }
        }
        val plan = SyncPlanner.plan(targets, manifest, fs)

        val done = AtomicInteger()
        val bytes = AtomicLong()
        val total = plan.download.size
        onProgress(SyncProgress(SyncProgress.Stage.DOWNLOAD, 0, total, 0))
        val failures = parallel(plan.download) { t ->
            downloadTarget(t) { n -> bytes.addAndGet(n) }
            onProgress(SyncProgress(SyncProgress.Stage.DOWNLOAD, done.incrementAndGet(), total, bytes.get().coerceAtLeast(0)))
        }

        onProgress(SyncProgress(SyncProgress.Stage.CLEANUP, total, total, bytes.get()))
        var deleted = 0
        // при неудачных загрузках старое не трогаем: лучше прежний мод, чем никакого
        for (path in if (failures.isEmpty()) plan.delete else emptyList()) {
            val f = File(gameDir, PackPaths.normalize(path))
            if (f.isFile && f.delete()) deleted++
        }

        val failed = failures.map { it.first.indexPath }.toSet()
        writeManifest(manifestFile, SyncPlanner.newManifest(pack, targets, failed, manifest))

        failures.firstOrNull()?.let { (t, e) ->
            throw PackSyncException("Не удалось скачать ${failures.size} файл(ов), напр. ${t.target}: ${e.message}", e)
        }
        SyncResult(plan.download.size, plan.keep.size, deleted, plan.preserved.size)
    }

    private fun downloadTarget(t: TargetFile, onBytes: (Long) -> Unit) {
        val target = File(gameDir, PackPaths.normalize(t.target!!))
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        var lastError: Exception? = null
        for (url in t.urls) {
            repeat(retries) { attempt ->
                var counted = 0L
                try {
                    val hasher = PackHash.hasher(t.hashFormat)
                    part.outputStream().buffered().use { out ->
                        http.download(url, object : OutputStream() {
                            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                hasher.update(b, off, len)
                                out.write(b, off, len)
                            }
                        }) { n ->
                            counted += n
                            onBytes(n)
                        }
                    }
                    val actual = hasher.hex()
                    if (!PackHash.matches(actual, t.hash)) {
                        throw HashMismatchException("хэш ${t.target} не совпал (${t.hashFormat})")
                    }
                    moveReplacing(part, target)
                    return
                } catch (e: CancellationException) {
                    part.delete()
                    throw e
                } catch (e: Exception) {
                    part.delete()
                    onBytes(-counted)
                    lastError = e
                    if (attempt < retries - 1) Thread.sleep(retryDelayMs * (attempt + 1))
                }
            }
        }
        throw lastError ?: IOException("нет ссылок")
    }

    /** Параллельно, не более [parallelism]; возвращает ошибки (элемент → исключение) */
    private suspend fun <T> parallel(items: List<T>, block: suspend (T) -> Unit): List<Pair<T, Exception>> =
        coroutineScope {
            val sem = Semaphore(parallelism)
            items.map { item ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            block(item)
                            null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            item to e
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    private suspend fun <R> retrying(block: () -> R): R {
        var last: Exception? = null
        repeat(retries) { attempt ->
            currentCoroutineContext().ensureActive()
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (attempt < retries - 1) delay(retryDelayMs * (attempt + 1))
            }
        }
        throw last!!
    }

    private fun verify(bytes: ByteArray, format: String, expected: String, name: String) {
        if (expected.isEmpty()) return
        if (!PackHash.matches(PackHash.of(bytes, format), expected)) {
            throw HashMismatchException("хэш $name не совпал с ожидаемым")
        }
    }

    companion object {
        const val MANIFEST_PATH = ".zhivoymir/packwiz-manifest.json"
        private val gson = GsonBuilder().setPrettyPrinting().create()

        fun readManifest(file: File): LocalManifest = runCatching {
            // Gson не знает о null-безопасности Kotlin: битый файл может дать null в полях
            val m: LocalManifest? = gson.fromJson(file.readText(), LocalManifest::class.java)
            val files: Map<String, ManifestEntry?>? = m!!.files
            m.copy(files = files.orEmpty().filterValues { it?.target != null && it.indexHash != null && it.hash != null && it.hashFormat != null }.mapValues { it.value!! })
        }.getOrDefault(LocalManifest())

        fun writeManifest(file: File, manifest: LocalManifest) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeText(gson.toJson(manifest))
            moveReplacing(tmp, file)
        }

        private fun moveReplacing(from: File, to: File) {
            try {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
