package com.movtery.zalithlauncher.zhivoymir.packwiz

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Потоковый хэш в форматах packwiz: sha1, sha256, sha512, md5, murmur2 (CurseForge) */
interface PackHasher {
    fun update(buf: ByteArray, off: Int, len: Int)
    fun hex(): String
}

object PackHash {
    fun hasher(format: String): PackHasher = when (format.lowercase()) {
        "sha1" -> Digest("SHA-1")
        "sha256" -> Digest("SHA-256")
        "sha512" -> Digest("SHA-512")
        "md5" -> Digest("MD5")
        "murmur2" -> Murmur2Hasher()
        else -> throw PackFormatException("Неизвестный формат хэша: $format")
    }

    fun of(bytes: ByteArray, format: String): String =
        hasher(format).apply { update(bytes, 0, bytes.size) }.hex()

    fun of(file: File, format: String): String {
        val h = hasher(format)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                h.update(buf, 0, n)
            }
        }
        return h.hex()
    }

    /** Сравнение хэшей (murmur2 у packwiz — десятичное число, остальные — hex без учёта регистра) */
    fun matches(actual: String, expected: String): Boolean = actual.equals(expected.trim(), ignoreCase = true)

    private class Digest(algorithm: String) : PackHasher {
        private val md = MessageDigest.getInstance(algorithm)
        override fun update(buf: ByteArray, off: Int, len: Int) = md.update(buf, off, len)
        override fun hex(): String = md.digest().joinToString("") { "%02x".format(it) }
    }

    /** MurmurHash2 CurseForge: seed 1, байты-пробелы (9, 10, 13, 32) пропускаются */
    private class Murmur2Hasher : PackHasher {
        private val data = ByteArrayOutputStream()
        override fun update(buf: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) {
                val b = buf[i].toInt()
                if (b != 9 && b != 10 && b != 13 && b != 32) data.write(b)
            }
        }

        override fun hex(): String = (murmur2(data.toByteArray(), 1).toLong() and 0xFFFFFFFFL).toString()
    }

    fun murmur2(data: ByteArray, seed: Int): Int {
        val m = 0x5bd1e995
        val len = data.size
        var h = seed xor len
        var i = 0
        while (len - i >= 4) {
            var k = (data[i].toInt() and 0xff) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                ((data[i + 2].toInt() and 0xff) shl 16) or
                ((data[i + 3].toInt() and 0xff) shl 24)
            k *= m
            k = k xor (k ushr 24)
            k *= m
            h *= m
            h = h xor k
            i += 4
        }
        when (len - i) {
            3 -> {
                h = h xor ((data[i + 2].toInt() and 0xff) shl 16)
                h = h xor ((data[i + 1].toInt() and 0xff) shl 8)
                h = h xor (data[i].toInt() and 0xff)
                h *= m
            }
            2 -> {
                h = h xor ((data[i + 1].toInt() and 0xff) shl 8)
                h = h xor (data[i].toInt() and 0xff)
                h *= m
            }
            1 -> {
                h = h xor (data[i].toInt() and 0xff)
                h *= m
            }
        }
        h = h xor (h ushr 13)
        h *= m
        h = h xor (h ushr 15)
        return h
    }
}
