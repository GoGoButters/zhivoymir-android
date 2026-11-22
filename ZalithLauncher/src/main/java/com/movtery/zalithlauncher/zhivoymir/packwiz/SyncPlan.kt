package com.movtery.zalithlauncher.zhivoymir.packwiz

/** Что мы ставили в прошлый раз: ключ — путь записи в index.toml */
data class ManifestEntry(
    /** Хэш записи в index.toml (для metafile — хэш самого .pw.toml) */
    val indexHash: String,
    /** Путь установленного файла; пусто — файл не для клиента (side = server) */
    val target: String,
    val hashFormat: String,
    val hash: String,
    val preserve: Boolean = false,
)

data class LocalManifest(
    val packVersion: String = "",
    val minecraft: String = "",
    val neoforge: String? = null,
    val files: Map<String, ManifestEntry> = emptyMap(),
)

/** Взгляд на файлы в каталоге игры (для тестов подменяется) */
interface LocalFiles {
    fun exists(path: String): Boolean
    fun hash(path: String, format: String): String?
}

/** Файл пака после разбора metafile */
data class TargetFile(
    val indexPath: String,
    val indexHash: String,
    /** null — файл не нужен клиенту */
    val target: String?,
    val urls: List<String>,
    val hashFormat: String,
    val hash: String,
    val preserve: Boolean,
)

data class SyncPlan(
    val download: List<TargetFile>,
    val keep: List<TargetFile>,
    val preserved: List<TargetFile>,
    val skipped: List<TargetFile>,
    /** Пути файлов, которые ставили мы и которых больше нет в паке */
    val delete: List<String>,
)

object SyncPlanner {
    /** metafile не изменился с прошлой установки, и его файл на месте — .pw.toml можно не качать */
    fun isMetaUnchanged(entry: IndexEntry, manifest: LocalManifest, fs: LocalFiles): Boolean {
        val m = manifest.files[entry.file] ?: return false
        if (!PackHash.matches(m.indexHash, entry.hash)) return false
        return m.target.isEmpty() || fs.exists(m.target)
    }

    fun metafilesToFetch(index: PackIndex, manifest: LocalManifest, fs: LocalFiles): List<IndexEntry> =
        index.files.filter { it.metafile && !isMetaUnchanged(it, manifest, fs) }

    /** Обычный файл индекса */
    fun directTarget(entry: IndexEntry, baseUrl: String) = TargetFile(
        indexPath = entry.file,
        indexHash = entry.hash,
        target = entry.file,
        urls = listOf(PackwizToml.resolveUrl(baseUrl, entry.file)),
        hashFormat = entry.hashFormat,
        hash = entry.hash,
        preserve = entry.preserve,
    )

    /** metafile, разобранный заново */
    fun metaTarget(entry: IndexEntry, meta: ModMeta) = TargetFile(
        indexPath = entry.file,
        indexHash = entry.hash,
        target = if (meta.isClient) PackPaths.join(PackPaths.parent(entry.file), meta.filename) else null,
        urls = if (meta.isClient) PackwizToml.downloadUrls(meta) else emptyList(),
        hashFormat = meta.hashFormat,
        hash = meta.hash,
        preserve = entry.preserve,
    )

    /** metafile без изменений — берём данные из манифеста */
    fun cachedMetaTarget(entry: IndexEntry, m: ManifestEntry) = TargetFile(
        indexPath = entry.file,
        indexHash = entry.hash,
        target = m.target.ifEmpty { null },
        urls = emptyList(),
        hashFormat = m.hashFormat,
        hash = m.hash,
        preserve = entry.preserve,
    )

    fun plan(targets: List<TargetFile>, manifest: LocalManifest, fs: LocalFiles): SyncPlan {
        val download = mutableListOf<TargetFile>()
        val keep = mutableListOf<TargetFile>()
        val preserved = mutableListOf<TargetFile>()
        val skipped = mutableListOf<TargetFile>()

        for (t in targets) {
            val target = t.target
            if (target == null) {
                skipped += t
                continue
            }
            val exists = fs.exists(target)
            val m = manifest.files[t.indexPath]
            when {
                t.preserve && exists -> preserved += t
                exists && m != null && m.target == target &&
                    m.hashFormat.equals(t.hashFormat, ignoreCase = true) &&
                    PackHash.matches(m.hash, t.hash) -> keep += t
                exists && fs.hash(target, t.hashFormat)?.let { PackHash.matches(it, t.hash) } == true -> keep += t
                else -> {
                    if (t.urls.isEmpty()) throw PackFormatException("Нет ссылки для ${t.indexPath}")
                    download += t
                }
            }
        }

        val newTargets = targets.associate { it.indexPath to it.target }
        val stillUsed = targets.mapNotNull { it.target }.toSet()
        val delete = manifest.files.mapNotNull { (key, m) ->
            when {
                m.target.isEmpty() -> null
                newTargets[key] == m.target -> null
                m.preserve -> null
                m.target in stillUsed -> null
                else -> m.target
            }
        }.distinct()

        return SyncPlan(download, keep, preserved, skipped, delete)
    }

    /** Манифест после синхронизации; для неудачных загрузок остаются старые записи */
    fun newManifest(
        pack: PackInfo,
        targets: List<TargetFile>,
        failed: Set<String>,
        old: LocalManifest,
    ): LocalManifest {
        val files = LinkedHashMap<String, ManifestEntry>()
        for (t in targets) {
            if (t.indexPath in failed) {
                old.files[t.indexPath]?.let { files[t.indexPath] = it }
                continue
            }
            files[t.indexPath] = ManifestEntry(t.indexHash, t.target ?: "", t.hashFormat, t.hash, t.preserve)
        }
        return LocalManifest(pack.version, pack.minecraft, pack.neoforge, files)
    }
}
