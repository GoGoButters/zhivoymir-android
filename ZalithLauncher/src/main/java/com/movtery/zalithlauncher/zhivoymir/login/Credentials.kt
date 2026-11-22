package com.movtery.zalithlauncher.zhivoymir.login

import java.io.File
import java.io.IOException
import java.util.UUID

/** Ник игрока: правила Minecraft для оффлайн-ников */
object Nick {
    private val ALLOWED = Regex("[A-Za-z0-9_]+")

    /** Пробелы и переводы строк по краям (вставка из буфера) */
    fun clean(raw: String): String = raw.trim()

    /** @return текст ошибки или null, если ник подходит */
    fun error(nick: String): String? = when {
        nick.isEmpty() -> "Введите ник"
        !ALLOWED.matches(nick) -> "Ник: только латинские буквы, цифры и _"
        nick.length < 3 -> "Ник слишком короткий: нужно от 3 символов"
        nick.length > 16 -> "Ник слишком длинный: не больше 16 символов"
        else -> null
    }

    /** Стандартный оффлайновый UUID, как у сервера в offline-mode */
    fun offlineUuid(nick: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$nick".toByteArray(Charsets.UTF_8))
}

object Password {
    fun clean(raw: String): String = raw.trim()

    fun error(password: String): String? = if (password.isEmpty()) "Введите пароль" else null
}

/**
 * Файл пароля мода Nedologin (3.0.0-rc3): `./.nl_password` относительно рабочей папки игры (= game dir).
 * Мод читает его целиком `Files.readString` (UTF-8) без обрезки, пишет `Files.writeString` без перевода строки.
 * Если файла нет — мод сам генерирует случайный UUID-пароль; после `/nl change_password` перезаписывает файл.
 */
object NlPasswordFile {
    const val NAME = ".nl_password"

    fun file(gameDir: File) = File(gameDir, NAME)

    fun read(gameDir: File): String? {
        val file = file(gameDir)
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }
    }

    /** Ровно байты пароля в UTF-8, без завершающего перевода строки */
    fun write(gameDir: File, password: String) {
        if (!gameDir.isDirectory && !gameDir.mkdirs()) throw IOException("Cannot create $gameDir")
        val target = file(gameDir)
        val tmp = File(gameDir, "$NAME.tmp")
        tmp.writeBytes(password.toByteArray(Charsets.UTF_8))
        if (!tmp.renameTo(target)) {
            tmp.delete()
            target.writeBytes(password.toByteArray(Charsets.UTF_8))
        }
    }
}

/** Хранилище строк (SharedPreferences на устройстве, Map в тестах) */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Ник + пароли по никам; синхронизация с `.nl_password` */
class Credentials(private val store: KeyValueStore) {
    val nick: String? get() = store.get(KEY_NICK)

    fun password(nick: String): String? = store.get(KEY_PASSWORD + nick)

    /** Есть ник и пароль к нему — экран входа не нужен */
    fun isComplete(): Boolean {
        val nick = nick ?: return false
        return Nick.error(nick) == null && !password(nick).isNullOrEmpty()
    }

    /** Значения уже проверены [Nick]/[Password] */
    fun login(nick: String, password: String) {
        store.put(KEY_PASSWORD + nick, password)
        store.put(KEY_NICK, nick)
    }

    /**
     * Забрать пароль, который мод поменял в игре (`/nl change_password`): файл отличается от того,
     * что мы записали перед запуском, а пароль на экране входа с тех пор не меняли.
     * @return true, если сохранённый пароль обновлён
     */
    fun pullFromGame(gameDir: File): Boolean {
        val writtenNick = store.get(KEY_WRITTEN_NICK) ?: return false
        val written = store.get(KEY_WRITTEN_PASSWORD) ?: return false
        val current = NlPasswordFile.read(gameDir) ?: return false
        if (current.isBlank() || current == written) return false
        if (password(writtenNick) != written) return false
        store.put(KEY_PASSWORD + writtenNick, current)
        store.put(KEY_WRITTEN_PASSWORD, current)
        return true
    }

    /**
     * Перед запуском: забрать изменения из игры и записать пароль текущего ника в `.nl_password`.
     * @return ник, под которым запускаемся
     */
    fun pushToGame(gameDir: File): String {
        pullFromGame(gameDir)
        val nick = nick ?: throw IllegalStateException("Ник не задан")
        val password = password(nick)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Пароль для $nick не задан")
        NlPasswordFile.write(gameDir, password)
        store.put(KEY_WRITTEN_NICK, nick)
        store.put(KEY_WRITTEN_PASSWORD, password)
        return nick
    }

    companion object {
        const val KEY_NICK = "login_nick"
        const val KEY_PASSWORD = "login_password:"
        const val KEY_WRITTEN_NICK = "nl_written_nick"
        const val KEY_WRITTEN_PASSWORD = "nl_written_password"
    }
}
