package com.movtery.zalithlauncher.zhivoymir.packwiz

import com.moandjiezana.toml.Toml
import java.net.URLEncoder

/** pack.toml */
data class PackInfo(
    val name: String,
    val version: String,
    val indexFile: String,
    val indexHashFormat: String,
    val indexHash: String,
    val minecraft: String,
    val neoforge: String?,
)

/** Запись [[files]] из index.toml */
data class IndexEntry(
    val file: String,
    val hashFormat: String,
    val hash: String,
    val metafile: Boolean,
    val preserve: Boolean,
)

data class PackIndex(val hashFormat: String, val files: List<IndexEntry>)

/** *.pw.toml */
data class ModMeta(
    val name: String,
    val filename: String,
    val side: String,
    val url: String?,
    val hashFormat: String,
    val hash: String,
    val curseforgeFileId: Long?,
) {
    /** Нужен ли файл клиенту */
    val isClient: Boolean get() = side == "client" || side == "both" || side.isEmpty()
}

class PackFormatException(message: String) : RuntimeException(message)

object PackwizToml {
    fun parsePack(text: String): PackInfo {
        val toml = Toml().read(text)
        val index = toml.getTable("index") ?: throw PackFormatException("pack.toml: нет секции [index]")
        val versions = toml.getTable("versions") ?: throw PackFormatException("pack.toml: нет секции [versions]")
        return PackInfo(
            name = toml.getString("name", ""),
            version = toml.getString("version", ""),
            indexFile = index.getString("file") ?: throw PackFormatException("pack.toml: нет index.file"),
            indexHashFormat = index.getString("hash-format", "sha256"),
            indexHash = index.getString("hash", ""),
            minecraft = versions.getString("minecraft") ?: throw PackFormatException("pack.toml: нет versions.minecraft"),
            neoforge = versions.getString("neoforge"),
        )
    }

    fun parseIndex(text: String): PackIndex {
        val toml = Toml().read(text)
        val defaultFormat = toml.getString("hash-format", "sha256")
        val files = (toml.getTables("files") ?: emptyList()).map { t ->
            val file = t.getString("file") ?: throw PackFormatException("index.toml: запись без file")
            IndexEntry(
                file = PackPaths.normalize(file),
                hashFormat = t.getString("hash-format", defaultFormat),
                hash = t.getString("hash", ""),
                metafile = t.getBoolean("metafile", false),
                preserve = t.getBoolean("preserve", false),
            )
        }
        return PackIndex(defaultFormat, files)
    }

    fun parseMeta(text: String): ModMeta {
        val toml = Toml().read(text)
        val download = toml.getTable("download") ?: throw PackFormatException("metafile: нет секции [download]")
        val filename = toml.getString("filename") ?: throw PackFormatException("metafile: нет filename")
        return ModMeta(
            name = toml.getString("name", filename),
            filename = filename,
            side = toml.getString("side", "both"),
            url = download.getString("url")?.takeIf { it.isNotBlank() },
            hashFormat = download.getString("hash-format") ?: throw PackFormatException("metafile $filename: нет hash-format"),
            hash = download.getString("hash") ?: throw PackFormatException("metafile $filename: нет hash"),
            curseforgeFileId = toml.getTable("update")?.getTable("curseforge")?.getLong("file-id"),
        )
    }

    /** Ссылки на файл мода: download.url, иначе CDN CurseForge по file-id */
    fun downloadUrls(meta: ModMeta): List<String> {
        meta.url?.let { return listOf(it) }
        val id = meta.curseforgeFileId?.toString()
            ?: throw PackFormatException("metafile ${meta.filename}: нет download.url и update.curseforge.file-id")
        if (id.length < 5) throw PackFormatException("metafile ${meta.filename}: странный file-id $id")
        val path = "files/${id.substring(0, 4)}/${id.substring(4).toInt()}/${encodeSegment(meta.filename)}"
        return listOf("https://edge.forgecdn.net/$path", "https://mediafilez.forgecdn.net/$path")
    }

    /** URL файла относительно базового URL пака (каталог pack.toml) */
    fun resolveUrl(baseUrl: String, relPath: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else baseUrl.substringBeforeLast('/') + "/"
        return base + relPath.split('/').joinToString("/") { encodeSegment(it) }
    }

    private fun encodeSegment(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

object PackPaths {
    /** Нормализует относительный путь и отвергает выход за пределы каталога игры */
    fun normalize(path: String): String {
        val p = path.replace('\\', '/').trim()
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (p.startsWith("/") || parts.isEmpty() || parts.any { it == ".." } || parts.first().contains(':')) {
            throw PackFormatException("Недопустимый путь в паке: $path")
        }
        return parts.joinToString("/")
    }

    fun parent(path: String): String = path.substringBeforeLast('/', "")

    fun join(dir: String, name: String): String = normalize(if (dir.isEmpty()) name else "$dir/$name")
}
