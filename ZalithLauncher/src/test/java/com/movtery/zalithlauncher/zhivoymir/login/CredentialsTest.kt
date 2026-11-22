package com.movtery.zalithlauncher.zhivoymir.login

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CredentialsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class MapStore : KeyValueStore {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) {
            if (value == null) map.remove(key) else map[key] = value
        }
    }

    @Test
    fun nickValidation() {
        assertNull(Nick.error("vadim"))
        assertNull(Nick.error("Abc"))
        assertNull(Nick.error("A_b_C_1234567890"))
        assertNotNull(Nick.error(""))
        assertEquals("Ник слишком короткий: нужно от 3 символов", Nick.error("ab"))
        assertEquals("Ник слишком длинный: не больше 16 символов", Nick.error("a".repeat(17)))
        assertEquals("Ник: только латинские буквы, цифры и _", Nick.error("вадим"))
        assertNotNull(Nick.error("va dim"))
        assertNotNull(Nick.error("vadim!"))
    }

    @Test
    fun cleanTrimsPastedWhitespace() {
        assertEquals("vadim", Nick.clean("  vadim\n"))
        assertEquals("pa ss", Password.clean("\t pa ss \r\n"))
        assertNotNull(Password.error(Password.clean(" \n ")))
        assertNull(Password.error("x"))
    }

    @Test
    fun offlineUuidMatchesServer() {
        // Python: uuid.UUID(bytes=md5(b"OfflinePlayer:vadim") with version 3 / RFC 4122 variant)
        assertEquals("3252ec1e-d486-3c0a-9e0e-ea126c5d1de4", Nick.offlineUuid("vadim").toString())
        assertEquals(3, Nick.offlineUuid("vadim").version())
    }

    @Test
    fun fileIsExactUtf8WithoutNewline() {
        val dir = File(tmp.root, "game")
        NlPasswordFile.write(dir, "пароль 1")
        val file = File(dir, ".nl_password")
        assertArrayEquals("пароль 1".toByteArray(Charsets.UTF_8), file.readBytes())
        assertEquals("пароль 1", NlPasswordFile.read(dir))
        NlPasswordFile.write(dir, "x")
        assertArrayEquals(byteArrayOf('x'.code.toByte()), file.readBytes())
        assertFalse(File(dir, ".nl_password.tmp").exists())
        assertNull(NlPasswordFile.read(File(tmp.root, "missing")))
    }

    @Test
    fun loginAndPush() {
        val store = MapStore()
        val creds = Credentials(store)
        assertFalse(creds.isComplete())
        creds.login("vadim", "secret")
        assertTrue(creds.isComplete())
        val dir = tmp.newFolder()
        assertEquals("vadim", creds.pushToGame(dir))
        assertEquals("secret", NlPasswordFile.read(dir))
    }

    @Test
    fun passwordsAreKeptPerNick() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        creds.login("alice", "a1")
        creds.login("bob", "b1")
        creds.pushToGame(dir)
        assertEquals("b1", NlPasswordFile.read(dir))
        creds.login("alice", "a1")
        creds.pushToGame(dir)
        assertEquals("a1", NlPasswordFile.read(dir))
        assertEquals("b1", creds.password("bob"))
    }

    @Test
    fun pullsPasswordChangedInGame() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        creds.login("vadim", "old")
        creds.pushToGame(dir)
        // игрок сделал /nl change_password — мод перезаписал файл
        File(dir, ".nl_password").writeText("new", Charsets.UTF_8)
        assertTrue(creds.pullFromGame(dir))
        assertEquals("new", creds.password("vadim"))
        assertFalse(creds.pullFromGame(dir))
        creds.pushToGame(dir)
        assertEquals("new", NlPasswordFile.read(dir))
    }

    @Test
    fun passwordFromLoginScreenWinsOverGameChange() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        creds.login("vadim", "old")
        creds.pushToGame(dir)
        File(dir, ".nl_password").writeText("fromGame", Charsets.UTF_8)
        creds.login("vadim", "fromScreen")
        creds.pushToGame(dir)
        assertEquals("fromScreen", NlPasswordFile.read(dir))
        assertEquals("fromScreen", creds.password("vadim"))
    }

    @Test
    fun gameChangeGoesToNickThatPlayed() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        creds.login("alice", "a1")
        creds.pushToGame(dir)
        File(dir, ".nl_password").writeText("a2", Charsets.UTF_8)
        creds.login("bob", "b1")
        creds.pushToGame(dir)
        assertEquals("a2", creds.password("alice"))
        assertEquals("b1", NlPasswordFile.read(dir))
    }

    @Test
    fun blankOrMissingFileIsIgnored() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        creds.login("vadim", "pw")
        creds.pushToGame(dir)
        File(dir, ".nl_password").writeText("  \n", Charsets.UTF_8)
        assertFalse(creds.pullFromGame(dir))
        File(dir, ".nl_password").delete()
        assertFalse(creds.pullFromGame(dir))
        assertEquals("pw", creds.password("vadim"))
    }
}
