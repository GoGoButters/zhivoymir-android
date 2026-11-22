package com.movtery.zalithlauncher.zhivoymir

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.version.installed.Version

private const val PREFS = "zhivoymir"
private const val KEY_MIC_ASKED = "mic_permission_asked"

/**
 * Главный экран «Живого мира»: «Играть» + вход в обычный интерфейс ZL2 («Дополнительно»)
 * @param upstream обычный главный экран ZL2
 */
@Composable
fun ZhivoyMirHomeHost(
    onLaunchGame: (Version) -> Unit,
    upstream: @Composable () -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (advanced) {
        Box(modifier = Modifier.fillMaxSize()) {
            upstream()
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
            ) {
                TextButton(onClick = { advanced = false }) {
                    Text("← Живой мир: «Играть»")
                }
            }
        }
    } else {
        ZhivoyMirHome(onLaunchGame = onLaunchGame, onAdvanced = { advanced = true })
    }
}

@Composable
fun ZhivoyMirHome(
    onLaunchGame: (Version) -> Unit,
    onAdvanced: () -> Unit,
    viewModel: ZhivoyMirViewModel = viewModel(LocalContext.current.findActivity()),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val packVersion by viewModel.packVersion.collectAsState()
    val account by AccountsManager.currentAccountFlow.collectAsState()

    var pendingLaunch by remember { mutableStateOf<Version?>(null) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        // запускаем при любом ответе: без микрофона просто не будет голосового чата
        pendingLaunch?.let(onLaunchGame)
        pendingLaunch = null
    }

    val launchWithMic: (Version) -> Unit = { version ->
        if (needAskMicrophone(context)) {
            prefs(context).edit().putBoolean(KEY_MIC_ASKED, true).apply()
            pendingLaunch = version
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            onLaunchGame(version)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Живой мир",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )

            val busy = state is PlayState.Working
            Button(
                onClick = { viewModel.play(context, launch = launchWithMic) },
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Text("Играть", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            }

            Text(
                text = buildString {
                    append("Сборка ").append(packVersion ?: "—")
                    append(" · Ник: ").append(account?.username ?: "не выбран")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            StateBlock(
                state = state,
                onRetry = { viewModel.play(context, launch = launchWithMic) },
                onPlayOffline = { viewModel.play(context, skipUpdate = true, launch = launchWithMic) },
                onCancel = { viewModel.cancel() },
                onDismiss = { viewModel.dismiss() },
            )

            Spacer(modifier = Modifier.size(8.dp))
            TextButton(onClick = onAdvanced, enabled = !busy) {
                Text("Дополнительно (версии, аккаунты, настройки)")
            }
        }
    }
}

@Composable
private fun StateBlock(
    state: PlayState,
    onRetry: () -> Unit,
    onPlayOffline: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        PlayState.Idle -> Unit
        is PlayState.Working -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(state.step, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (state.progress in 0f..1f) {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, maxLines = 2)
            }
            TextButton(onClick = onCancel) { Text("Отмена") }
        }
        is PlayState.Offline -> Message(state.message, MaterialTheme.colorScheme.tertiary) {
            Button(onClick = onPlayOffline) { Text("Играть без обновления") }
            OutlinedButton(onClick = onRetry) { Text("Повторить") }
        }
        is PlayState.Failed -> Message(state.message, MaterialTheme.colorScheme.error) {
            Button(onClick = onRetry) { Text("Повторить") }
            OutlinedButton(onClick = onDismiss) { Text("Закрыть") }
        }
    }
}

@Composable
private fun Message(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    buttons: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
    }
}

/** ViewModel живёт на уровне Activity — загрузка не прерывается при переходах по экранам */
private tailrec fun Context.findActivity(): ComponentActivity = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No activity")
}

private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

private fun needAskMicrophone(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
        !prefs(context).getBoolean(KEY_MIC_ASKED, false)
