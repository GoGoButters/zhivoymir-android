package com.movtery.zalithlauncher.zhivoymir.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class AppUpdateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sha = "AB".repeat(32)

    private fun json(
        versionCode: String = "200",
        apk: String = "\"ZhivoyMir-0.2.0.apk\"",
        size: String = "123",
        sha256: String = "\"$sha\"",
        extra: String = "",
    ) = """{"versionCode": $versionCode, "versionName": "0.2.0-zl2.8.2", "apk": $apk,
        "size": $size, "sha256": $sha256, "notes": "  Что нового\nстрока 2 " $extra}"""

    private fun assertBad(text: String) {
        try {
            AppUpdate.parse(text)
            fail("expected UpdateFormatException for $text")
        } catch (_: UpdateFormatException) {
        }
    }

    @Test
    fun parsesValid() {
        val info = AppUpdate.parse(json(extra = """, "future": [1, 2]"""))
        assertEquals(200, info.versionCode)
        assertEquals("0.2.0-zl2.8.2", info.versionName)
        assertEquals("ZhivoyMir-0.2.0.apk", info.apk)
        assertEquals(123L, info.size)
        assertEquals(sha.lowercase(), info.sha256)
        assertEquals("Что нового\nстрока 2", info.notes)
        assertNull(info.url)
        assertEquals("${AppUpdate.REPO_URL}/releases/latest/download/ZhivoyMir-0.2.0.apk", AppUpdate.apkUrl(info))
    }

    @Test
    fun usesTagUrlWhenGiven() {
        val url = "https://github.com/GoGoButters/zhivoymir-android/releases/download/v0.2.0/ZhivoyMir-0.2.0.apk"
        val info = AppUpdate.parse(json(extra = """, "url": "$url""""))
        assertEquals(url, AppUpdate.apkUrl(info))
        // не https — игнорируем
        assertNull(AppUpdate.parse(json(extra = """, "url": "http://evil/x.apk"""")).url)
    }

    @Test
    fun rejectsBad() {
        assertBad("not json")
        assertBad("[1]")
        assertBad("{}")
        assertBad(json(versionCode = "\"200\""))
        assertBad(json(versionCode = "0"))
        assertBad(json(versionCode = "1.5"))
        assertBad(json(apk = "\"../x.apk\""))
        assertBad(json(apk = "\"\""))
        assertBad(json(size = "0"))
        assertBad(json(sha256 = "\"abc\""))
        assertBad(json(sha256 = "\"${"zz".repeat(32)}\""))
    }

    @Test
    fun newerByVersionCode() {
        val info = AppUpdate.parse(json())
        assertTrue(AppUpdate.isNewer(info, 1))
        assertTrue(AppUpdate.isNewer(info, 199))
        assertFalse(AppUpdate.isNewer(info, 200))
        assertFalse(AppUpdate.isNewer(info, 10000))
    }

    @Test
    fun versionCodeFormula() {
        assertEquals(200, AppUpdate.versionCodeOf("0.2.0-zl2.8.2"))
        assertEquals(200, AppUpdate.versionCodeOf("0.2.0"))
        assertEquals(10203, AppUpdate.versionCodeOf("1.2.3"))
        assertEquals(9999, AppUpdate.versionCodeOf("0.99.99"))
        assertNull(AppUpdate.versionCodeOf("0.100.0"))
        assertNull(AppUpdate.versionCodeOf("v0.2.0"))
        assertNull(AppUpdate.versionCodeOf("0.2"))
        // каждый следующий релиз больше, и всё больше versionCode=1 у v0.1.0
        val order = listOf("0.1.1", "0.2.0", "0.2.1", "0.10.0", "1.0.0", "1.0.1", "2.0.0")
        val codes = order.map { AppUpdate.versionCodeOf(it)!! }
        assertTrue(codes.first() > 1)
        assertEquals(codes.sorted(), codes)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun throttle() {
        val min = 60_000L
        assertTrue(AppUpdate.shouldCheck(1000, null))
        assertFalse(AppUpdate.shouldCheck(1000 + 9 * min, 1000))
        assertTrue(AppUpdate.shouldCheck(1000 + 10 * min, 1000))
        assertTrue(AppUpdate.shouldCheck(500, 1000)) // часы ушли назад
    }

    @Test
    fun verifiesSizeAndSha256() {
        val bytes = "fake apk".toByteArray()
        val hex = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file = tmp.newFile("a.apk").apply { writeBytes(bytes) }
        val info = AppUpdate.parse(json(size = bytes.size.toString(), sha256 = "\"${hex.uppercase()}\""))
        assertTrue(AppUpdate.verify(file, info))
        assertFalse(AppUpdate.verify(file, info.copy(size = info.size + 1)))
        assertFalse(AppUpdate.verify(file, info.copy(sha256 = sha.lowercase())))
        assertFalse(AppUpdate.verify(tmp.root.resolve("missing.apk"), info))
    }
}
