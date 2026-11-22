package com.movtery.zalithlauncher.zhivoymir.update

import com.movtery.zalithlauncher.zhivoymir.packwiz.PackHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

class UpdateFormatException(message: String) : Exception(message)

/** Описание релиза — asset update.json (генерирует scripts/make-update-json.py в CI) */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** Имя asset APK в релизе */
    val apk: String,
    val size: Long,
    val sha256: String,
    val notes: String,
    /** Прямая ссылка на APK этого тега; null — releases/latest/download/[apk] */
    val url: String? = null,
)

object AppUpdate {
    const val REPO_URL = "https://github.com/GoGoButters/zhivoymir-android"
    const val UPDATE_JSON_URL = "$REPO_URL/releases/latest/download/update.json"

    /** Не чаще раза в 10 минут */
    const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)""")

    fun parse(text: String): UpdateInfo {
        val root: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw UpdateFormatException("update.json не JSON-объект: ${e.message}")
        }
        fun str(key: String): String? = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun long(key: String): Long? = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        fun need(key: String): Nothing = throw UpdateFormatException("update.json: нет поля «$key»")

        val versionCode = long("versionCode")?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: need("versionCode")
        val versionName = str("versionName")?.takeIf { it.isNotBlank() } ?: need("versionName")
        val apk = str("apk")?.takeIf { it.isNotBlank() && '/' !in it && '\\' !in it && it != ".." } ?: need("apk")
        val size = long("size")?.takeIf { it > 0 } ?: need("size")
        val sha256 = str("sha256")?.takeIf { SHA256.matches(it) }?.lowercase() ?: need("sha256")
        val url = str("url")?.takeIf { it.startsWith("https://") }
        return UpdateInfo(versionCode, versionName, apk, size, sha256, str("notes").orEmpty().trim(), url)
    }

    fun isNewer(info: UpdateInfo, currentVersionCode: Int): Boolean = info.versionCode > currentVersionCode

    fun apkUrl(info: UpdateInfo): String = info.url ?: "$REPO_URL/releases/latest/download/${info.apk}"

    /** MAJOR*10000 + MINOR*100 + PATCH; так же считает Gradle (build.gradle.kts) и CI */
    fun versionCodeOf(versionName: String): Int? {
        val (major, minor, patch) = VERSION.find(versionName)?.destructured ?: return null
        val mi = minor.toIntOrNull() ?: return null
        val pa = patch.toIntOrNull() ?: return null
        if (mi >= 100 || pa >= 100) return null
        return (major.toIntOrNull() ?: return null) * 10000 + mi * 100 + pa
    }

    /**
     * @param lastCheckMs время последней удачной проверки, null — не было
     * Часы ушли назад (now < last) — проверяем, чтобы не застрять
     */
    fun shouldCheck(nowMs: Long, lastCheckMs: Long?, intervalMs: Long = CHECK_INTERVAL_MS): Boolean =
        lastCheckMs == null || nowMs < lastCheckMs || nowMs - lastCheckMs >= intervalMs

    /** Размер и sha256 скачанного APK совпадают с update.json */
    fun verify(file: File, info: UpdateInfo): Boolean =
        file.isFile && file.length() == info.size && PackHash.matches(PackHash.of(file, "sha256"), info.sha256)
}
