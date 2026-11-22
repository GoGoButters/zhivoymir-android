package com.movtery.zalithlauncher.zhivoymir.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Диалоги обновления лаунчера
 * @param onLater пользователь отказался/закрыл — можно продолжать (например, запуск игры)
 */
@Composable
fun AppUpdateDialog(onLater: () -> Unit) {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        AppUpdater.retryInstall(context)
    }
    val later = {
        AppUpdater.dismiss()
        onLater()
    }
    val noDismiss = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)

    when (val s = state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = later,
            title = { Text("Доступна новая версия ${s.info.versionName}") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (s.info.notes.isNotEmpty()) Text(s.info.notes, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Размер: %.1f МБ".format(s.info.size / 1024.0 / 1024.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { AppUpdater.update(context) }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = later) { Text("Позже") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            properties = noDismiss,
            title = { Text("Загрузка версии ${s.info.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "%d%% · %.1f из %.1f МБ".format(
                            (s.progress * 100).toInt(),
                            s.progress * s.info.size / 1024.0 / 1024.0,
                            s.info.size / 1024.0 / 1024.0,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = later) { Text("Отмена") } },
        )
        is UpdateState.NeedPermission -> AlertDialog(
            onDismissRequest = {},
            properties = noDismiss,
            title = { Text("Нужно разрешение на установку") },
            text = {
                Text(
                    buildString {
                        if (s.denied) append("Разрешение пока не выдано.\n\n")
                        append("Обновление скачано. Чтобы его установить, Android должен разрешить лаунчеру ")
                        append("«Живой мир» устанавливать приложения.\n\n")
                        append("Нажмите «Открыть настройки», включите «Разрешить установку из этого источника» ")
                        append("и вернитесь назад — установка продолжится.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        settingsLauncher.launch(AppUpdater.unknownSourcesIntent(context))
                    } catch (_: ActivityNotFoundException) {
                        settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    }
                }) { Text("Открыть настройки") }
            },
            dismissButton = { TextButton(onClick = later) { Text("Позже") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = later,
            title = { Text("Обновление не удалось") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { AppUpdater.update(context) }) { Text("Повторить") } },
            dismissButton = { TextButton(onClick = later) { Text("Позже") } },
        )
    }
}
