package com.movtery.zalithlauncher.zhivoymir.packwiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun fixture(path: String): ByteArray =
    PackwizTomlTest::class.java.getResourceAsStream("/zhivoymir/packwiz/$path")!!.use { it.readBytes() }

internal fun fixtureText(path: String) = fixture(path).toString(Charsets.UTF_8)

class PackwizTomlTest {
    @Test
    fun parsesRealPackToml() {
        val pack = PackwizToml.parsePack(fixtureText("pack.toml"))
        assertEquals("Живой мир (Android)", pack.name)
        assertEquals("1.21.1", pack.minecraft)
        assertEquals("21.1.265", pack.neoforge)
        assertEquals("index.toml", pack.indexFile)
        assertEquals("sha256", pack.indexHashFormat)
        assertEquals(64, pack.indexHash.length)
    }

    @Test
    fun realIndexHashMatchesPackToml() {
        val pack = PackwizToml.parsePack(fixtureText("pack.toml"))
        val actual = PackHash.of(fixture("index.toml"), pack.indexHashFormat)
        assertTrue(PackHash.matches(actual, pack.indexHash))
    }

    @Test
    fun parsesRealIndex() {
        val index = PackwizToml.parseIndex(fixtureText("index.toml"))
        assertEquals("sha256", index.hashFormat)
        assertEquals(3307, index.files.size)
        assertEquals(159, index.files.count { it.metafile })
        val preserved = index.files.filter { it.preserve }.map { it.file }.toSet()
        assertEquals(setOf("options.txt", "servers.dat", "config/voicechat/voicechat-client.properties"), preserved)
        assertTrue(index.files.all { it.hash.isNotEmpty() && it.hashFormat == "sha256" })
    }

    @Test
    fun metafileHashesMatchIndex() {
        val index = PackwizToml.parseIndex(fixtureText("index.toml")).files.associateBy { it.file }
        for (name in listOf("mods/ae2.pw.toml", "mods/backpacked.pw.toml", "mods/ambientsounds.pw.toml")) {
            val entry = index.getValue(name)
            assertTrue(entry.metafile)
            assertTrue(name, PackHash.matches(PackHash.of(fixture(name), entry.hashFormat), entry.hash))
        }
    }

    @Test
    fun parsesModrinthMetafile() {
        val meta = PackwizToml.parseMeta(fixtureText("mods/ae2.pw.toml"))
        assertEquals("appliedenergistics2-19.2.18.jar", meta.filename)
        assertEquals("sha512", meta.hashFormat)
        assertTrue(meta.isClient)
        assertNull(meta.curseforgeFileId)
        assertEquals(listOf(meta.url), PackwizToml.downloadUrls(meta))
    }

    @Test
    fun curseforgeMetafileWithoutUrlUsesCdn() {
        val meta = PackwizToml.parseMeta(fixtureText("mods/backpacked.pw.toml"))
        assertNull(meta.url)
        assertEquals("sha1", meta.hashFormat)
        assertEquals(7866688L, meta.curseforgeFileId)
        assertEquals(
            "https://edge.forgecdn.net/files/7866/688/backpacked-neoforge-1.21.1-3.0.5.jar",
            PackwizToml.downloadUrls(meta).first()
        )
    }

    @Test
    fun clientAndServerSides() {
        assertTrue(PackwizToml.parseMeta(fixtureText("mods/ambientsounds.pw.toml")).isClient)
        val server = PackwizToml.parseMeta(
            fixtureText("mods/ambientsounds.pw.toml").replace("side = \"client\"", "side = \"server\"")
        )
        assertFalse(server.isClient)
    }

    @Test
    fun cdnIdWithLeadingZerosInTail() {
        val meta = ModMeta("x", "a b+c.jar", "both", null, "sha1", "0", 5123045L)
        assertEquals("https://edge.forgecdn.net/files/5123/45/a%20b%2Bc.jar", PackwizToml.downloadUrls(meta).first())
    }

    @Test
    fun resolvesRelativeUrls() {
        val base = "https://gogobutters.github.io/mc/android/pack.toml"
        assertEquals("https://gogobutters.github.io/mc/android/index.toml", PackwizToml.resolveUrl(base, "index.toml"))
        assertEquals(
            "https://gogobutters.github.io/mc/android/config/my%20dir/a.toml",
            PackwizToml.resolveUrl(base, "config/my dir/a.toml")
        )
    }

    @Test
    fun rejectsUnsafePaths() {
        for (bad in listOf("../x.jar", "mods/../../x", "/etc/passwd", "C:/x", "")) {
            val thrown = runCatching { PackPaths.normalize(bad) }.exceptionOrNull()
            assertTrue(bad, thrown is PackFormatException)
        }
        assertEquals("mods/a.jar", PackPaths.normalize("./mods//a.jar"))
        assertEquals("mods/a.jar", PackPaths.join("mods", "a.jar"))
        assertEquals("a.jar", PackPaths.join("", "a.jar"))
    }
}

class PackHashTest {
    @Test
    fun knownVectors() {
        val abc = "abc".toByteArray()
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", PackHash.of(abc, "sha1"))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PackHash.of(abc, "sha256"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", PackHash.of(abc, "md5"))
        assertTrue(PackHash.of(abc, "sha512").startsWith("ddaf35a193617aba"))
        assertTrue(PackHash.matches("ABCDEF", "abcdef"))
    }

    @Test
    fun murmur2IgnoresWhitespace() {
        val a = PackHash.of("a b\tc\r\nd".toByteArray(), "murmur2")
        val b = PackHash.of("abcd".toByteArray(), "murmur2")
        assertEquals(a, b)
        assertTrue(a.toLong() >= 0)
    }

    @Test(expected = PackFormatException::class)
    fun unknownFormat() {
        PackHash.hasher("crc32")
    }
}
