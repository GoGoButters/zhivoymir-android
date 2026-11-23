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
import java.util.Random

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

    @Test
    fun generatedPasswordFormat() {
        val passwords = (1..50).map { DevicePassword.generate() }
        for (pw in passwords) {
            assertTrue(pw.startsWith("zm-android:"))
            assertEquals("zm-android:".length + 32, pw.length)
            assertTrue(Regex("zm-android:[A-Za-z0-9]{32}").matches(pw))
            assertTrue(DevicePassword.isDevice(pw))
        }
        assertEquals(passwords.size, passwords.toSet().size)
        // алфавит используется целиком (62 символа), а не урезанная часть
        val used = passwords.joinToString("") { it.removePrefix(DevicePassword.PREFIX) }.toSet()
        assertTrue(used.size > 50)
        assertFalse(DevicePassword.isDevice("1f0e3dad-99a8-4b1c-a2a4-2a4b1c0e3dad"))
    }

    @Test
    fun autoLoginGeneratesAndReusesPerNick() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        val alice = creds.loginAuto("alice", Random(1))
        assertTrue(DevicePassword.isDevice(alice))
        assertTrue(creds.isComplete())
        assertEquals(alice, creds.loginAuto("alice", Random(2)))
        val bob = creds.loginAuto("bob", Random(3))
        assertTrue(bob != alice)
        assertEquals("bob", creds.nick)
        assertEquals(alice, creds.loginAuto("alice", Random(4)))
        assertEquals("alice", creds.pushToGame(dir))
        assertEquals(alice, NlPasswordFile.read(dir))
        assertEquals(bob, creds.password("bob"))
    }

    @Test
    fun migrationKeepsPcPasswordFrom020() {
        // 0.2.0: игрок ввёл пароль с ПК (без префикса) — он остаётся основным, не подменяется
        val store = MapStore()
        Credentials(store).login("vadim", "pc-uuid-password")
        val creds = Credentials(store)
        assertTrue(creds.isComplete())
        assertEquals("pc-uuid-password", creds.loginAuto("vadim", Random(5)))
        assertEquals("pc-uuid-password", creds.password("vadim"))
        val dir = tmp.newFolder()
        creds.pushToGame(dir)
        assertEquals("pc-uuid-password", NlPasswordFile.read(dir))
    }

    @Test
    fun gameChangeOfDevicePasswordIsReused() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        creds.loginAuto("vadim")
        creds.pushToGame(dir)
        File(dir, ".nl_password").writeText("changedInGame", Charsets.UTF_8)
        assertTrue(creds.pullFromGame(dir))
        assertEquals("changedInGame", creds.loginAuto("vadim"))
    }

    @Test
    fun manualPasswordReplacesDevicePassword() {
        val creds = Credentials(MapStore())
        val dir = tmp.newFolder()
        creds.loginAuto("vadim")
        // перенос на новый планшет: пароль со старого устройства
        val old = "zm-android:" + "a".repeat(32)
        creds.login("vadim", Password.clean(" $old\n"))
        assertEquals(old, creds.password("vadim"))
        assertEquals(old, creds.loginAuto("vadim"))
        creds.pushToGame(dir)
        assertEquals(old, NlPasswordFile.read(dir))
    }
}
