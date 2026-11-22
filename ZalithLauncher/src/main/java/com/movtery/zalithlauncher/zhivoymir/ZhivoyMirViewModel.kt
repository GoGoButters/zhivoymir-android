package com.movtery.zalithlauncher.zhivoymir

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.zhivoymir.login.ZhivoyMirAccount
import com.movtery.zalithlauncher.zhivoymir.packwiz.HashMismatchException
import com.movtery.zalithlauncher.zhivoymir.packwiz.PackFormatException
import com.movtery.zalithlauncher.zhivoymir.packwiz.PackInfo
import com.movtery.zalithlauncher.zhivoymir.packwiz.PackSyncException
import com.movtery.zalithlauncher.zhivoymir.packwiz.PackSyncer
import com.movtery.zalithlauncher.zhivoymir.packwiz.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.UnknownHostException

private const val TAG = "ZhivoyMirPlay"

sealed interface PlayState {
    data object Idle : PlayState

    /** Идёт шаг: [progress] < 0 — без процентов */
    data class Working(val step: String, val detail: String? = null, val progress: Float = -1f) : PlayState

    /** Нет сети, но игра уже стоит — можно играть без обновления */
    data class Offline(val message: String) : PlayState

    data class Failed(val message: String) : PlayState
}

/** «Играть»: синхронизация пака → установка → пресеты → запуск */
class ZhivoyMirViewModel : ViewModel() {
    private val _state = MutableStateFlow<PlayState>(PlayState.Idle)
    val state = _state.asStateFlow()

    /** Версия пака: из последнего манифеста, после проверки — с сервера */
    private val _packVersion = MutableStateFlow<String?>(null)
    val packVersion = _packVersion.asStateFlow()

    private var job: Job? = null

    private fun syncer() = PackSyncer(ZhivoyMir.PACK_URL, ZhivoyMir.versionDir(), OkHttpPackHttp)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            syncer().loadManifest().packVersion.takeIf { it.isNotEmpty() }?.let { _packVersion.value = it }
        }
    }

    val isBusy: Boolean get() = job?.isActive == true

    /**
     * @param launch вызывается на главном потоке с готовой версией
     */
    fun play(context: Context, skipUpdate: Boolean = false, launch: (Version) -> Unit) {
        if (isBusy) return
        val appContext = context.applicationContext
        job = viewModelScope.launch {
            try {
                val version = prepare(appContext, skipUpdate) ?: return@launch
                _state.value = PlayState.Idle
                launch(version)
            } catch (e: CancellationException) {
                _state.value = PlayState.Idle
                throw e
            } catch (e: Throwable) {
                Logger.error(TAG, "Play pipeline failed", e)
                _state.value = PlayState.Failed(describe(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = PlayState.Idle
    }

    fun dismiss() {
        if (!isBusy) _state.value = PlayState.Idle
    }

    /** @return версия для запуска или null, если остановились на предупреждении */
    private suspend fun prepare(appContext: Context, skipUpdate: Boolean): Version? {
        val credentials = ZhivoyMirAccount.credentials(appContext)
        if (!credentials.isComplete()) {
            ZhivoyMirAccount.requestLogin()
            throw IllegalStateException("Введите ник и пароль")
        }
        val syncer = syncer()

        if (!skipUpdate) {
            _state.value = PlayState.Working("Проверка обновлений сборки…")
            val pack: PackInfo = try {
                syncer.fetchPack()
            } catch (e: IOException) {
                Logger.warning(TAG, "pack.toml is unavailable", e)
                if (ZhivoyMirGame.isInstalled() && withContext(Dispatchers.IO) { syncer.loadManifest().files.isNotEmpty() }) {
                    _state.value = PlayState.Offline(
                        "Нет связи с сервером сборки — обновления не проверены. Можно играть на уже установленной версии."
                    )
                    return null
                }
                throw e
            }
            _packVersion.value = pack.version

            ZhivoyMirGame.installMode(pack)?.let { modify ->
                _state.value = PlayState.Working(
                    if (modify) "Обновление Minecraft ${pack.minecraft} / NeoForge ${pack.neoforge}…"
                    else "Установка Minecraft ${pack.minecraft} и NeoForge ${pack.neoforge}…"
                )
                ZhivoyMirGame.install(appContext, viewModelScope, pack, modify) { title, progress ->
                    _state.value = PlayState.Working(
                        if (modify) "Обновление Minecraft и NeoForge" else "Установка Minecraft и NeoForge",
                        title,
                        progress
                    )
                }
            }

            _state.value = PlayState.Working("Обновление сборки…")
            val result = syncer.sync(pack) { p -> _state.value = syncState(p) }
            Logger.info(TAG, "Pack synced: $result")
        } else if (!ZhivoyMirGame.isInstalled()) {
            throw IllegalStateException("Игра ещё не установлена — нужен интернет")
        }

        _state.value = PlayState.Working("Настройка игры…")
        ZhivoyMirGame.ensureControls(appContext)
        ZhivoyMirGame.applyPresets(appContext)

        val version = withContext(Dispatchers.IO) { ZhivoyMirGame.loadVersion() }
            ?: throw IllegalStateException("Версия ${ZhivoyMir.VERSION_NAME} повреждена — нажмите «Повторить»")

        // пароль Nedologin выбранного ника — перед каждым запуском (мод читает его при старте игры)
        val nick = withContext(Dispatchers.IO) { credentials.pushToGame(version.getGameDir()) }
        ZhivoyMirAccount.select(nick)
        return version
    }

    private fun syncState(p: SyncProgress): PlayState.Working {
        val mb = "%.1f МБ".format(p.bytes / 1024.0 / 1024.0)
        return when (p.stage) {
            SyncProgress.Stage.INDEX -> PlayState.Working("Обновление сборки…", "Загрузка списка файлов")
            SyncProgress.Stage.METAFILES -> PlayState.Working(
                "Обновление сборки…",
                "Проверка модов: ${p.doneFiles} из ${p.totalFiles}",
                fraction(p)
            )
            SyncProgress.Stage.DOWNLOAD -> PlayState.Working(
                "Загрузка файлов сборки",
                "Файлов: ${p.doneFiles} из ${p.totalFiles} · $mb",
                fraction(p)
            )
            SyncProgress.Stage.CLEANUP -> PlayState.Working("Удаление устаревших файлов…", mb)
        }
    }

    private fun fraction(p: SyncProgress) = if (p.totalFiles > 0) p.doneFiles.toFloat() / p.totalFiles else -1f

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "Нет подключения к интернету. Проверьте сеть и нажмите «Повторить»."
        is HashMismatchException -> "Файл сборки скачался повреждённым (${e.message}). Нажмите «Повторить»."
        is PackSyncException -> "Не удалось обновить сборку: ${e.message}"
        is PackFormatException -> "Ошибка в описании сборки: ${e.message}"
        is IOException -> "Ошибка сети: ${e.message ?: e.javaClass.simpleName}. Нажмите «Повторить»."
        else -> e.message ?: e.javaClass.simpleName
    }
}
