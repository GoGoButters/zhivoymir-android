package com.movtery.zalithlauncher.zhivoymir.login

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.movtery.zalithlauncher.utils.logging.Logger
import com.movtery.zalithlauncher.zhivoymir.McButton
import com.movtery.zalithlauncher.zhivoymir.ZhivoyMirBackground
import com.movtery.zalithlauncher.zhivoymir.ZhivoyMirNotice
import com.movtery.zalithlauncher.zhivoymir.ZhivoyMirStyle
import com.movtery.zalithlauncher.zhivoymir.ZhivoyMirTitle
import kotlinx.coroutines.launch

/**
 * Экран входа: ник + пароль Nedologin.
 * @param initialNick/initialPassword заполненные поля для «Сменить ник / пароль»
 * @param onCancel null — первый запуск, отменить нельзя
 */
@Composable
fun ZhivoyMirLogin(
    initialNick: String,
    initialPassword: String,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var nick by rememberSaveable { mutableStateOf(initialNick) }
    var password by rememberSaveable { mutableStateOf(initialPassword) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var nickError by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordError by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    onCancel?.let { BackHandler(onBack = it) }

    val submit: () -> Unit = submit@{
        if (busy) return@submit
        val cleanNick = Nick.clean(nick)
        val cleanPassword = Password.clean(password)
        nick = cleanNick
        password = cleanPassword
        nickError = Nick.error(cleanNick)
        passwordError = Password.error(cleanPassword)
        if (nickError != null || passwordError != null) return@submit
        focus.clearFocus()
        busy = true
        scope.launch {
            try {
                ZhivoyMirAccount.credentials(context).login(cleanNick, cleanPassword)
                ZhivoyMirAccount.select(cleanNick)
                onDone()
            } catch (e: Exception) {
                Logger.error("ZhivoyMirLogin", "Login failed", e)
                nickError = "Не удалось сохранить аккаунт: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    ZhivoyMirBackground {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            val minHeight = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = minHeight)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                ZhivoyMirTitle()
                ZhivoyMirNotice()

                Column(
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Как тебя зовут, странник?", style = ZhivoyMirStyle.pixel(13.sp))

                    McTextField(
                        value = nick,
                        onValueChange = { nick = it; nickError = null },
                        label = "Ник",
                        error = nickError,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    )
                    Hint(nickError ?: "3–16 символов: латинские буквы, цифры, _", nickError != null)

                    McTextField(
                        value = password,
                        onValueChange = { password = it; passwordError = null },
                        label = "Пароль",
                        error = passwordError,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        trailing = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(if (showPassword) "скрыть" else "показать", color = ZhivoyMirStyle.TextLight)
                            }
                        },
                    )
                    Hint(
                        passwordError
                            ?: "Новый игрок — придумайте пароль, он закрепит ник на сервере. " +
                            "Уже играете на ПК — введите пароль с ПК (файл .nl_password в папке игры на ПК).",
                        passwordError != null,
                    )

                    McButton(
                        text = if (busy) "Вход…" else "Войти",
                        onClick = submit,
                        enabled = !busy,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth(),
                        fontSize = 16.sp,
                    )
                    onCancel?.let {
                        McButton(text = "Отмена", onClick = it, modifier = Modifier.fillMaxWidth(), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = TextStyle(
            fontSize = 13.sp,
            color = if (error) ZhivoyMirStyle.TextError else ZhivoyMirStyle.TextLight,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
private fun McTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    keyboardOptions: KeyboardOptions,
    keyboardActions: KeyboardActions,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        isError = error != null,
        singleLine = true,
        textStyle = TextStyle(fontSize = 18.sp),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        trailingIcon = trailing,
        shape = RectangleShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ZhivoyMirStyle.TextLight,
            unfocusedTextColor = ZhivoyMirStyle.TextLight,
            focusedContainerColor = Color.Black,
            unfocusedContainerColor = Color.Black,
            errorContainerColor = Color.Black,
            errorTextColor = ZhivoyMirStyle.TextLight,
            cursorColor = ZhivoyMirStyle.TextLight,
            focusedBorderColor = Color.White,
            unfocusedBorderColor = ZhivoyMirStyle.FieldBorder,
            errorBorderColor = ZhivoyMirStyle.TextError,
            focusedLabelColor = Color.White,
            unfocusedLabelColor = ZhivoyMirStyle.TextHint,
            errorLabelColor = ZhivoyMirStyle.TextError,
        ),
    )
}
