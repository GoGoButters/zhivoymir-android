package com.movtery.zalithlauncher.zhivoymir.packwiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = "https://gogobutters.github.io/mc/android/pack.toml"

/** Файлы «на диске»: путь → хэш (в формате, в котором его спросят) */
private class FakeFiles(val files: MutableMap<String, String> = mutableMapOf()) : LocalFiles {
    var hashCalls = 0
    override fun exists(path: String) = path in files
    override fun hash(path: String, format: String): String? {
        hashCalls++
        return files[path]
    }
}

class SyncPlanTest {
    private val pack = PackwizToml.parsePack(fixtureText("pack.toml"))
    private val index = PackwizToml.parseIndex(fixtureText("index.toml"))
    private val metas = listOf("mods/ae2.pw.toml", "mods/backpacked.pw.toml", "mods/ambientsounds.pw.toml")
        .associateWith { PackwizToml.parseMeta(fixtureText(it)) }

    /** Реальный индекс, сокращённый до прямых файлов + трёх metafile из фикстур */
    private val smallIndex = PackIndex(
        index.hashFormat,
        index.files.filter { !it.metafile || it.file in metas }
    )

    private fun resolveAll(idx: PackIndex, manifest: LocalManifest, fs: LocalFiles): List<TargetFile> {
        val fetch = SyncPlanner.metafilesToFetch(idx, manifest, fs).map { it.file }.toSet()
        return idx.files.map { e ->
            when {
                !e.metafile -> SyncPlanner.directTarget(e, BASE)
                e.file in fetch -> SyncPlanner.metaTarget(e, metas.getValue(e.file))
                else -> SyncPlanner.cachedMetaTarget(e, manifest.files.getValue(e.file))
            }
        }
    }

    /** Состояние после успешной установки: манифест + файлы на диске */
    private fun installed(idx: PackIndex = smallIndex): Pair<LocalManifest, FakeFiles> {
        val targets = resolveAll(idx, LocalManifest(), FakeFiles())
        val fs = FakeFiles()
        targets.forEach { t -> t.target?.let { fs.files[it] = t.hash } }
        return SyncPlanner.newManifest(pack, targets, emptySet(), LocalManifest()) to fs
    }

    @Test
    fun freshInstallDownloadsEverythingForClient() {
        val fs = FakeFiles()
        val targets = resolveAll(smallIndex, LocalManifest(), fs)
        val plan = SyncPlanner.plan(targets, LocalManifest(), fs)
        assertEquals(smallIndex.files.size, plan.download.size)
        assertTrue(plan.delete.isEmpty() && plan.keep.isEmpty() && plan.preserved.isEmpty())
        val ae2 = plan.download.single { it.indexPath == "mods/ae2.pw.toml" }
        assertEquals("mods/appliedenergistics2-19.2.18.jar", ae2.target)
        assertEquals("sha512", ae2.hashFormat)
        val bp = plan.download.single { it.indexPath == "mods/backpacked.pw.toml" }
        assertTrue(bp.urls.first().startsWith("https://edge.forgecdn.net/files/7866/688/"))
        val options = plan.download.single { it.indexPath == "options.txt" }
        assertEquals("https://gogobutters.github.io/mc/android/options.txt", options.urls.single())
    }

    @Test
    fun allMetafilesOfRealIndexNeedFetchOnFirstRun() {
        assertEquals(159, SyncPlanner.metafilesToFetch(index, LocalManifest(), FakeFiles()).size)
    }

    @Test
    fun secondRunIsNoOpWithoutHashing() {
        val (manifest, fs) = installed()
        assertTrue(SyncPlanner.metafilesToFetch(smallIndex, manifest, fs).isEmpty())
        val plan = SyncPlanner.plan(resolveAll(smallIndex, manifest, fs), manifest, fs)
        assertTrue(plan.download.isEmpty())
        assertTrue(plan.delete.isEmpty())
        assertEquals(3, plan.preserved.size)
        assertEquals(smallIndex.files.size - 3, plan.keep.size)
        assertEquals(0, fs.hashCalls)
    }

    @Test
    fun changedFileIsTheOnlyDownload() {
        val (manifest, fs) = installed()
        val changed = smallIndex.files.first { !it.metafile && !it.preserve }
        val idx = smallIndex.copy(files = smallIndex.files.map { if (it === changed) it.copy(hash = "00ff") else it })
        val plan = SyncPlanner.plan(resolveAll(idx, manifest, fs), manifest, fs)
        assertEquals(listOf(changed.file), plan.download.map { it.target })
    }

    @Test
    fun missingFileIsRedownloadedAndMetafileRefetched() {
        val (manifest, fs) = installed()
        fs.files.remove("mods/appliedenergistics2-19.2.18.jar")
        fs.files.remove("config/fallingtree.json")
        assertEquals(
            listOf("mods/ae2.pw.toml"),
            SyncPlanner.metafilesToFetch(smallIndex, manifest, fs).map { it.file }
        )
        val plan = SyncPlanner.plan(resolveAll(smallIndex, manifest, fs), manifest, fs)
        assertEquals(
            setOf("mods/appliedenergistics2-19.2.18.jar", "config/fallingtree.json"),
            plan.download.map { it.target }.toSet()
        )
    }

    @Test
    fun removedFromIndexIsDeletedButNotPreservedOrForeign() {
        val (manifest, fs) = installed()
        fs.files["mods/my-own-mod.jar"] = "x"
        val idx = smallIndex.copy(files = smallIndex.files.filter {
            it.file != "mods/ae2.pw.toml" && it.file != "config/fallingtree.json" && it.file != "options.txt"
        })
        val plan = SyncPlanner.plan(resolveAll(idx, manifest, fs), manifest, fs)
        assertEquals(
            setOf("mods/appliedenergistics2-19.2.18.jar", "config/fallingtree.json"),
            plan.delete.toSet()
        )
        assertTrue(plan.download.isEmpty())
    }

    @Test
    fun preserveDoesNotOverwriteExistingFile() {
        val fs = FakeFiles(mutableMapOf("options.txt" to "user-edited", "servers.dat" to "user"))
        val plan = SyncPlanner.plan(resolveAll(smallIndex, LocalManifest(), fs), LocalManifest(), fs)
        assertEquals(setOf("options.txt", "servers.dat"), plan.preserved.map { it.target }.toSet())
        assertTrue(plan.download.none { it.target == "options.txt" || it.target == "servers.dat" })
        assertTrue(plan.download.any { it.target == "config/voicechat/voicechat-client.properties" })
    }

    @Test
    fun existingFileWithRightHashIsAdopted() {
        val entry = smallIndex.files.first { !it.metafile && !it.preserve }
        val fs = FakeFiles(mutableMapOf(entry.file to entry.hash))
        val plan = SyncPlanner.plan(resolveAll(smallIndex, LocalManifest(), fs), LocalManifest(), fs)
        assertEquals(listOf(entry.file), plan.keep.map { it.target })
    }

    @Test
    fun corruptedFileWithoutManifestIsReplaced() {
        val entry = smallIndex.files.first { !it.metafile && !it.preserve }
        val fs = FakeFiles(mutableMapOf(entry.file to "bad"))
        val plan = SyncPlanner.plan(resolveAll(smallIndex, LocalManifest(), fs), LocalManifest(), fs)
        assertTrue(plan.download.any { it.target == entry.file })
    }

    @Test
    fun updatedMetafileReplacesOldJar() {
        val (manifest, fs) = installed()
        val e = smallIndex.files.first { it.file == "mods/ae2.pw.toml" }
        val idx = smallIndex.copy(files = smallIndex.files.map { if (it === e) it.copy(hash = "new") else it })
        val newMeta = metas.getValue(e.file).copy(filename = "appliedenergistics2-19.2.19.jar", hash = "abc")
        val targets = idx.files.map { x ->
            if (x.file == e.file) SyncPlanner.metaTarget(x, newMeta)
            else if (!x.metafile) SyncPlanner.directTarget(x, BASE)
            else SyncPlanner.cachedMetaTarget(x, manifest.files.getValue(x.file))
        }
        assertEquals(listOf(e.file), SyncPlanner.metafilesToFetch(idx, manifest, fs).map { it.file })
        val plan = SyncPlanner.plan(targets, manifest, fs)
        assertEquals(listOf("mods/appliedenergistics2-19.2.19.jar"), plan.download.map { it.target })
        assertEquals(listOf("mods/appliedenergistics2-19.2.18.jar"), plan.delete)
    }

    @Test
    fun modBecomingServerOnlyIsRemovedAndSkipped() {
        val (manifest, fs) = installed()
        val e = smallIndex.files.first { it.file == "mods/ambientsounds.pw.toml" }
        val server = metas.getValue(e.file).copy(side = "server")
        val targets = smallIndex.files.map { x ->
            if (x.file == e.file) SyncPlanner.metaTarget(x.copy(hash = "changed"), server)
            else if (!x.metafile) SyncPlanner.directTarget(x, BASE)
            else SyncPlanner.cachedMetaTarget(x, manifest.files.getValue(x.file))
        }
        val plan = SyncPlanner.plan(targets, manifest, fs)
        assertEquals(listOf(e.file), plan.skipped.map { it.indexPath })
        assertEquals(listOf("mods/AmbientSounds_NEOFORGE_v6.3.11_mc1.21.1.jar"), plan.delete)

        // сервер-онли мод запоминается и больше не перекачивается
        val next = SyncPlanner.newManifest(pack, targets, emptySet(), manifest)
        val again = smallIndex.copy(files = smallIndex.files.map { if (it.file == e.file) it.copy(hash = "changed") else it })
        assertTrue(SyncPlanner.metafilesToFetch(again, next, fs).isEmpty())
    }

    @Test
    fun failedDownloadsKeepOldManifestEntries() {
        val (manifest, _) = installed()
        val targets = resolveAll(smallIndex, LocalManifest(), FakeFiles())
        val next = SyncPlanner.newManifest(pack, targets, setOf("mods/ae2.pw.toml", "brand-new.txt"), manifest)
        assertEquals(manifest.files["mods/ae2.pw.toml"], next.files["mods/ae2.pw.toml"])
        assertEquals(pack.version, next.packVersion)
        assertEquals("21.1.265", next.neoforge)
    }
}
