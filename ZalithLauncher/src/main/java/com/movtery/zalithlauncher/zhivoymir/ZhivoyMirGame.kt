package com.movtery.zalithlauncher.zhivoymir

import android.content.Context
import com.movtery.layer_controller.layout.loadLayoutFromString
import com.movtery.layer_controller.utils.saveToFile
import com.movtery.zalithlauncher.coroutine.TaskStage
import com.movtery.zalithlauncher.game.addons.modloader.forgelike.neoforge.NeoForgeVersion
import com.movtery.zalithlauncher.game.control.ControlManager
import com.movtery.zalithlauncher.game.download.game.GameDownloadInfo
import com.movtery.zalithlauncher.game.download.game.GameInstaller
import com.movtery.zalithlauncher.game.multirt.RuntimesManager
import com.movtery.zalithlauncher.game.renderer.renderers.MobileGluesRenderer
import com.movtery.zalithlauncher.game.version.installed.SettingState
import com.movtery.zalithlauncher.game.version.installed.VersionConfig
import com.movtery.zalithlauncher.game.version.installed.VersionsManager
import com.movtery.zalithlauncher.game.version.multiplayer.AllServers
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.ui.toAndroidString
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.utils.platform.getTotalMemory
import com.movtery.zalithlauncher.zhivoymir.packwiz.PackHash
import com.movtery.zalithlauncher.zhivoymir.packwiz.PackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "ZhivoyMir"

/** Установка версии и пресеты средствами ZL2 */
object ZhivoyMirGame {
    private fun loaderMarker() = File(ZhivoyMir.stateDir(), "loader")
    private fun presetsMarker() = File(ZhivoyMir.stateDir(), "presets")
    private fun controlsMarker() = File(ZhivoyMir.stateDir(), "controls.sha256")

    private fun loaderId(pack: PackInfo) = "${pack.minecraft}|${pack.neoforge.orEmpty()}"

    fun isInstalled(): Boolean = VersionsManager.isVersionExists(ZhivoyMir.VERSION_NAME, checkJson = true)

    /** null — ставить не нужно; false — поставить заново; true — пересобрать существующую */
    fun installMode(pack: PackInfo): Boolean? = when {
        !isInstalled() -> false
        loaderMarker().readTextOrNull() != loaderId(pack) -> true
        else -> null
    }

    /** Ставит MC + NeoForge из pack.toml через GameInstaller */
    suspend fun install(
        context: Context,
        scope: CoroutineScope,
        pack: PackInfo,
        modify: Boolean,
        onProgress: (title: String, progress: Float) -> Unit,
    ) {
        val info = GameDownloadInfo(
            gameVersion = pack.minecraft,
            customVersionName = ZhivoyMir.VERSION_NAME,
            neoforge = pack.neoforge?.let { NeoForgeVersion(it, isLegacyForge = false) },
        )
        val installer = GameInstaller(context, info, scope)
        val poller = scope.launch {
            while (true) {
                installer.tasksFlow.value.firstOrNull { it.task.stage.value == TaskStage.RUNNING }?.let { t ->
                    onProgress(t.title.toAndroidString(context), t.task.progress.value)
                }
                delay(300)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val done: () -> Unit = { if (cont.isActive) cont.resume(Unit) }
                val fail: (Throwable) -> Unit = { if (cont.isActive) cont.resumeWithException(it) }
                if (modify) {
                    installer.modifyVersion(onModified = done, onError = fail)
                } else {
                    installer.installGame(
                        onInstalled = { done() },
                        onError = fail,
                        onGameAlreadyInstalled = {
                            fail(IllegalStateException("Версия ${ZhivoyMir.VERSION_NAME} уже установлена"))
                        }
                    )
                }
                cont.invokeOnCancellation { installer.cancelInstall() }
            }
        } finally {
            poller.cancel()
        }
        withContext(Dispatchers.IO) {
            loaderMarker().writeTextSafe(loaderId(pack))
            // после переустановки пресеты применяются заново
            presetsMarker().delete()
        }
    }

    /** ОЗУ: min(6 ГБ, общая − 4 ГБ), но не меньше 4 ГБ */
    fun recommendedRamMb(context: Context): Int {
        val totalMb = (getTotalMemory(context) / (1024 * 1024)).toInt()
        return maxOf(4096, minOf(6144, totalMb - 4096))
    }

    /** Пресеты версии: один раз на ревизию [ZhivoyMir.PRESETS_REV] (дальше игрок может менять сам) */
    suspend fun applyPresets(context: Context) = withContext(Dispatchers.IO) {
        if (presetsMarker().readTextOrNull() == ZhivoyMir.PRESETS_REV.toString()) return@withContext
        val versionDir = ZhivoyMir.versionDir()
        val config = VersionConfig.parseConfig(versionDir)
        config.isolationType = SettingState.ENABLE
        config.renderer = MobileGluesRenderer.getUniqueIdentifier()
        RuntimesManager.getExactJreName(ZhivoyMir.JAVA_MAJOR)?.let { config.javaRuntime = it }
            ?: Logger.warning(TAG, "Java ${ZhivoyMir.JAVA_MAJOR} runtime not found, leaving auto pick")
        config.ramAllocation = recommendedRamMb(context)
        config.control = ZhivoyMir.CONTROLS_FILE
        serverAddress()?.let { config.serverIp = it }
        config.saveWithThrowable()
        presetsMarker().writeTextSafe(ZhivoyMir.PRESETS_REV.toString())
    }

    /** Адрес первого сервера из servers.dat пака */
    suspend fun serverAddress(): String? {
        val file = File(ZhivoyMir.versionDir(), "servers.dat")
        if (!file.isFile) return null
        return AllServers().apply { loadServers(file) }.serverList
            .firstOrNull { it.originIp.isNotBlank() }?.originIp
    }

    /** Раскладка из assets: импорт при первом запуске и при изменении файла в APK */
    suspend fun ensureControls(context: Context) = withContext(Dispatchers.IO) {
        val bytes = context.assets.open(ZhivoyMir.CONTROLS_ASSET).use { it.readBytes() }
        val hash = PackHash.of(bytes, "sha256")
        val file = File(PathManager.DIR_CONTROL_LAYOUTS, ZhivoyMir.CONTROLS_FILE)
        if (file.isFile && controlsMarker().readTextOrNull() == hash) return@withContext
        file.parentFile?.mkdirs()
        loadLayoutFromString(bytes.toString(Charsets.UTF_8)).saveToFile(file)
        controlsMarker().writeTextSafe(hash)
        ControlManager.refresh()
    }

    /** Свежий объект версии для запуска (и выбрать её текущей) */
    fun loadVersion() = VersionsManager.loadVersion(
        com.movtery.zalithlauncher.game.path.getGameHome(),
        ZhivoyMir.VERSION_NAME
    )?.takeIf { it.isValid() }?.also {
        VersionsManager.saveCurrentVersion(ZhivoyMir.VERSION_NAME, refresh = false)
        VersionsManager.refresh(TAG, trySetVersion = ZhivoyMir.VERSION_NAME)
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    private fun File.writeTextSafe(text: String) {
        parentFile?.mkdirs()
        writeText(text)
    }
}
