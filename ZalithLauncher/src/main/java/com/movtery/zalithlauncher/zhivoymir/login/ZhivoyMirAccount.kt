package com.movtery.zalithlauncher.zhivoymir.login

import android.content.Context
import android.content.SharedPreferences
import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.AccountType
import com.movtery.zalithlauncher.game.account.AccountsManager
import com.movtery.zalithlauncher.game.account.accountUUID
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.account.wardrobe.SkinModelType
import com.movtery.zalithlauncher.utils.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ZhivoyMirAccount"

/** Те же настройки, что у главного экрана «Живого мира» */
private const val PREFS = "zhivoymir"

private class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

object ZhivoyMirAccount {
    private val _loginRequested = MutableStateFlow(false)

    /** Запуск без аккаунта (проверка ZL2) — показать наш экран входа вместо экрана аккаунтов ZL2 */
    val loginRequested = _loginRequested.asStateFlow()

    fun requestLogin() {
        _loginRequested.value = true
    }

    fun clearLoginRequest() {
        _loginRequested.value = false
    }

    fun credentials(context: Context) = Credentials(
        PrefsStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    )

    /**
     * Локальный аккаунт ника (переиспользуется, если уже есть) со стандартным оффлайновым UUID — выбрать текущим
     */
    suspend fun select(nick: String) {
        val profileId = accountUUID(Nick.offlineUuid(nick))
        val existing = AccountsManager.accountsFlow.value.firstOrNull { it.isLocalAccount() && it.username == nick }
        when {
            existing == null -> {
                Logger.info(TAG, "Creating local account $nick")
                AccountsManager.suspendSaveAccount(
                    Account(username = nick, profileId = profileId, accountType = AccountType.LOCAL.tag)
                )
            }
            existing.profileId != profileId -> {
                existing.profileId = profileId
                existing.skinModelType = SkinModelType.NONE
                AccountsManager.suspendSaveAccount(existing)
            }
            AccountsManager.currentAccountFlow.value?.uniqueUUID != existing.uniqueUUID ->
                AccountsManager.setCurrentAccount(existing)
        }
    }
}
