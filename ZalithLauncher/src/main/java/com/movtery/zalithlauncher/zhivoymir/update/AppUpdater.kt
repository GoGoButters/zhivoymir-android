package com.movtery.zalithlauncher.zhivoymir.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.movtery.zalithlauncher.BuildConfig
import com.movtery.zalithlauncher.path.DOWNLOAD_OKHTTP_CLIENT
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private const val TAG = "ZhivoyMirUpdate"
private const val PREFS = "zhivoymir"
private const val KEY_LAST_CHECK = "app_update_last_check"
private const val APK_MIME = "application/vnd.android.package-archive"

sealed interface UpdateState {
    data object Idle : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState

    /** APK скачан, но «Установка неизвестных приложений» для лаунчера не разрешена */
    data class NeedPermission(val info: UpdateInfo, val apk: File, val denied: Boolean = false) : UpdateState
    data class Failed(val info: UpdateInfo, val message: String) : UpdateState
}

/** Обновление лаунчера: update.json последнего релиза → APK в cache → системный установщик */
object AppUpdater {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private var downloadJob: Job? = null

    /** Короткие таймауты: проверка не должна задерживать «Играть» */
    private val checkClient by lazy {
        DOWNLOAD_OKHTTP_CLIENT.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun request(url: String) = Request.Builder().url(url).header("Cache-Control", "no-cache").build()

    /**
     * Проверка не чаще [AppUpdate.CHECK_INTERVAL_MS]; любые ошибки — молча
     * @return true, если найдено обновление и показан диалог
     */
    suspend fun check(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (_state.value != UpdateState.Idle) return@withContext false
        checkMutex.withLock {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_CHECK, -1L).takeIf { it >= 0 }
            if (!AppUpdate.shouldCheck(now, last)) return@withLock false
            prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
            try {
                val text = checkClient.newCall(request(AppUpdate.UPDATE_JSON_URL)).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    response.body.string()
                }
                val info = AppUpdate.parse(text)
                Logger.info(TAG, "Latest: ${info.versionName} (${info.versionCode}), current ${BuildConfig.VERSION_CODE}")
                if (AppUpdate.isNewer(info, BuildConfig.VERSION_CODE) && _state.value == UpdateState.Idle) {
                    _state.value = UpdateState.Available(info)
                    true
                } else false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.warning(TAG, "Update check skipped", e)
                false
            }
        }
    }

    /** «Позже» / «Отмена» */
    fun dismiss() {
        downloadJob?.cancel()
        _state.value = UpdateState.Idle
    }

    /** «Обновить»: скачать (или взять уже скачанный) APK, проверить и установить */
    fun update(context: Context) {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Failed -> s.info
            else -> return
        }
        val appContext = context.applicationContext
        _state.value = UpdateState.Downloading(info, 0f)
        val previous = downloadJob
        downloadJob = scope.launch {
            try {
                // отменённая закачка могла ещё не выйти из read() — не пишем в .part вдвоём
                previous?.cancelAndJoin()
                val apk = download(appContext, info)
                withContext(Dispatchers.Main) { install(appContext, info, apk) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.error(TAG, "Update download failed", e)
                _state.value = UpdateState.Failed(info, describe(e))
            }
        }
    }

    private suspend fun download(context: Context, info: UpdateInfo): File {
        val dir = File(context.cacheDir, "zhivoymir-update").apply { mkdirs() }
        val apk = File(dir, info.apk)
        // старые APK и недокачанные файлы
        dir.listFiles()?.filter { it != apk }?.forEach { it.delete() }
        if (AppUpdate.verify(apk, info)) return apk
        apk.delete()

        val part = File(dir, "${info.apk}.part")
        val ctx = coroutineContext
        var done = 0L
        part.outputStream().use { out ->
            DOWNLOAD_OKHTTP_CLIENT.newCall(request(AppUpdate.apkUrl(info))).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ctx.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = UpdateState.Downloading(info, (done.toFloat() / info.size).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (!AppUpdate.verify(part, info)) {
            part.delete()
            throw IOException("файл повреждён (размер или sha256 не совпали)")
        }
        if (!part.renameTo(apk)) throw IOException("не удалось сохранить ${apk.name}")
        return apk
    }

    private fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Повторная попытка после возврата из настроек «Неизвестные приложения» */
    fun retryInstall(context: Context) {
        val s = _state.value as? UpdateState.NeedPermission ?: return
        install(context.applicationContext, s.info, s.apk, afterSettings = true)
    }

    private fun install(context: Context, info: UpdateInfo, apk: File, afterSettings: Boolean = false) {
        if (!AppUpdate.verify(apk, info)) {
            _state.value = UpdateState.Failed(info, "Скачанный файл пропал или повреждён — нажмите «Повторить».")
            return
        }
        if (!canInstall(context)) {
            _state.value = UpdateState.NeedPermission(info, apk, denied = afterSettings)
            return
        }
        // ACTION_VIEW + content:// из FileProvider: системный установщик, работает на Android 8–16
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            // отмена в установщике просто возвращает в лаунчер; APK остаётся в cache для следующей попытки
            _state.value = UpdateState.Idle
        } catch (e: ActivityNotFoundException) {
            Logger.error(TAG, "No package installer", e)
            _state.value = UpdateState.Failed(info, "Не найден системный установщик приложений.")
        }
    }

    /** Экран «Установка неизвестных приложений» для этого лаунчера */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    private fun describe(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Нет подключения к интернету."
        is IOException -> "Не удалось скачать обновление: ${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
