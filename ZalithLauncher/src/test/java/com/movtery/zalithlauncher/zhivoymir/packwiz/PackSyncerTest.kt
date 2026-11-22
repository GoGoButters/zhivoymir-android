package com.movtery.zalithlauncher.zhivoymir.packwiz

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Collections

private const val ROOT = "https://example.test/mc/android/"

/** Сервер пака в памяти */
private class FakeHttp : PackHttp {
    val files = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
    val requests = Collections.synchronizedList(mutableListOf<String>())
    val failing = Collections.synchronizedSet(mutableSetOf<String>())

    override fun getBytes(url: String): ByteArray {
        requests += url
        if (url in failing) throw IOException("offline")
        return files[url] ?: throw IOException("404 $url")
    }

    override fun download(url: String, out: OutputStream, onBytes: (Long) -> Unit) {
        val data = getBytes(url)
        out.write(data)
        onBytes(data.size.toLong())
    }
}

class PackSyncerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val http = FakeHttp()
    private val jar = "fake jar bytes".toByteArray()
    private val cfJar = "curseforge jar".toByteArray()
    private val config = "a=1\n".toByteArray()
    private val options = "lang:ru_ru\n".toByteArray()

    private fun sha256(b: ByteArray) = PackHash.of(b, "sha256")

    private fun meta(filename: String, url: String?, data: ByteArray, side: String = "both", cfId: Long? = null) = buildString {
        append("name = \"$filename\"\nfilename = \"$filename\"\nside = \"$side\"\n\n[download]\n")
        if (url != null) append("url = \"$url\"\n")
        append("hash-format = \"sha1\"\nhash = \"${PackHash.of(data, "sha1")}\"\n")
        if (cfId != null) append("mode = \"metadata:curseforge\"\n\n[update]\n[update.curseforge]\nfile-id = $cfId\nproject-id = 1\n")
    }.toByteArray()

    /** Публикует пак: имя → содержимое (metafile — по суффиксу .pw.toml) */
    private fun publish(files: Map<String, ByteArray>, preserve: Set<String> = emptySet()) {
        val index = buildString {
            append("hash-format = \"sha256\"\n")
            files.forEach { (path, data) ->
                append("\n[[files]]\nfile = \"$path\"\nhash = \"${sha256(data)}\"\n")
                if (path.endsWith(".pw.toml")) append("metafile = true\n")
                if (path in preserve) append("preserve = true\n")
                http.files[ROOT + path] = data
            }
        }.toByteArray()
        http.files[ROOT + "index.toml"] = index
        http.files[ROOT + "pack.toml"] = """
            name = "Test"
            version = "1.0"
            pack-format = "packwiz:1.1.0"

            [index]
            file = "index.toml"
            hash-format = "sha256"
            hash = "${sha256(index)}"

            [versions]
            minecraft = "1.21.1"
            neoforge = "21.1.265"
        """.trimIndent().toByteArray()
    }

    private fun basePack(): MutableMap<String, ByteArray> {
        val cfPath = "files/1234/567/cf-mod.jar"
        http.files["https://edge.forgecdn.net/$cfPath"] = cfJar
        http.files[ROOT + "dl/mod.jar"] = jar
        return linkedMapOf(
            "mods/mod.pw.toml" to meta("mod.jar", ROOT + "dl/mod.jar", jar),
            "mods/cf.pw.toml" to meta("cf-mod.jar", null, cfJar, cfId = 1234567),
            "mods/server.pw.toml" to meta("server-only.jar", ROOT + "dl/server.jar", jar, side = "server"),
            "config/a.toml" to config,
            "options.txt" to options,
        )
    }

    private fun syncer(game: File) = PackSyncer(ROOT + "pack.toml", game, http, parallelism = 4, retries = 2, retryDelayMs = 0)

    private fun sync(game: File): SyncResult = runBlocking {
        val s = syncer(game)
        s.sync(s.fetchPack())
    }

    @Test
    fun fullThenIncrementalSync() {
        val game = tmp.newFolder("game")
        val pack = basePack()
        publish(pack, preserve = setOf("options.txt"))

        val first = sync(game)
        assertEquals(4, first.downloaded)
        assertTrue(File(game, "mods/mod.jar").readBytes().contentEquals(jar))
        assertTrue(File(game, "mods/cf-mod.jar").readBytes().contentEquals(cfJar))
        assertFalse(File(game, "mods/server-only.jar").exists())
        assertTrue(File(game, "options.txt").exists())
        assertTrue(game.walk().none { it.name.endsWith(".part") })

        // повторный запуск: ничего не качается, metafile не запрашиваются
        File(game, "options.txt").writeText("user settings")
        http.requests.clear()
        val second = sync(game)
        assertEquals(0, second.downloaded)
        assertEquals(1, second.preserved)
        assertEquals(listOf(ROOT + "pack.toml", ROOT + "index.toml"), http.requests.toList())
        assertEquals("user settings", File(game, "options.txt").readText())

        // мод удалён из пака, конфиг изменён; чужой мод игрока не трогаем
        File(game, "mods/player.jar").writeText("mine")
        pack.remove("mods/mod.pw.toml")
        pack["config/a.toml"] = "a=2\n".toByteArray()
        publish(pack, preserve = setOf("options.txt"))
        val third = sync(game)
        assertEquals(1, third.downloaded)
        assertEquals(1, third.deleted)
        assertFalse(File(game, "mods/mod.jar").exists())
        assertTrue(File(game, "mods/player.jar").exists())
        assertEquals("a=2\n", File(game, "config/a.toml").readText())
    }

    @Test
    fun hashMismatchFailsWithoutLeavingFiles() {
        val game = tmp.newFolder("game")
        val pack = basePack()
        publish(pack)
        http.files[ROOT + "dl/mod.jar"] = "tampered".toByteArray()
        try {
            sync(game)
            fail("expected failure")
        } catch (e: PackSyncException) {
            assertTrue(e.cause is HashMismatchException)
        }
        assertFalse(File(game, "mods/mod.jar").exists())
        assertTrue(game.walk().none { it.name.endsWith(".part") })
        // остальное скачано и записано в манифест — повторно не качается
        assertTrue(File(game, "mods/cf-mod.jar").exists())
        val manifest = PackSyncer.readManifest(File(game, PackSyncer.MANIFEST_PATH))
        assertTrue("mods/cf.pw.toml" in manifest.files)
        assertFalse("mods/mod.pw.toml" in manifest.files)
    }

    @Test
    fun cdnFallbackAndRetry() {
        val game = tmp.newFolder("game")
        val pack = basePack()
        val cfPath = "files/1234/567/cf-mod.jar"
        http.files.remove("https://edge.forgecdn.net/$cfPath")
        http.files["https://mediafilez.forgecdn.net/$cfPath"] = cfJar
        publish(pack)
        sync(game)
        assertTrue(File(game, "mods/cf-mod.jar").readBytes().contentEquals(cfJar))
    }

    @Test
    fun corruptedIndexIsRejected() {
        val game = tmp.newFolder("game")
        publish(basePack())
        http.files[ROOT + "index.toml"] = "hash-format = \"sha256\"\n".toByteArray()
        try {
            sync(game)
            fail("expected failure")
        } catch (e: HashMismatchException) {
            // ok
        }
    }

    @Test(expected = IOException::class)
    fun offlineFetchThrows() {
        val game = tmp.newFolder("game")
        publish(basePack())
        http.failing += ROOT + "pack.toml"
        runBlocking { syncer(game).fetchPack() }
    }
}
