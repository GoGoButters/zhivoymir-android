package com.movtery.zalithlauncher.zhivoymir

import com.movtery.zalithlauncher.game.path.getVersionsHome
import com.movtery.zalithlauncher.path.DOWNLOAD_OKHTTP_CLIENT
import com.movtery.zalithlauncher.zhivoymir.packwiz.PackHttp
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Константы сборки «Живой мир» */
object ZhivoyMir {
    const val PACK_URL = "https://gogobutters.github.io/mc/android/pack.toml"

    /** Изолированная версия игры */
    const val VERSION_NAME = "ZhivoyMir"

    /** Раскладка «Живой мир (планшет)»: asset и имя файла в control_layouts */
    const val CONTROLS_ASSET = "zhivoymir-controls.json"
    const val CONTROLS_FILE = "zhivoymir-controls.json"

    /** Поднять, если поменялись пресеты версии — применятся заново один раз */
    const val PRESETS_REV = 1

    const val JAVA_MAJOR = 21

    fun versionDir(): File = File(getVersionsHome(), VERSION_NAME)

    /** Служебные файлы лаунчера внутри версии */
    fun stateDir(): File = File(versionDir(), ".zhivoymir")
}

/** HTTP для packwiz на клиенте загрузок ZL2 */
object OkHttpPackHttp : PackHttp {
    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Cache-Control", "no-cache")
        .build()

    override fun getBytes(url: String): ByteArray =
        DOWNLOAD_OKHTTP_CLIENT.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
            response.body.bytes()
        }

    override fun download(url: String, out: OutputStream, onBytes: (Int) -> Unit) {
        DOWNLOAD_OKHTTP_CLIENT.newCall(request(url)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}: $url")
            response.body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
        }
    }
}
