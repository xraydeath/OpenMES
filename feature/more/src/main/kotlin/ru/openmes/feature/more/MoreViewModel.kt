package ru.openmes.feature.more

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.openmes.core.common.runSuspendCatching
import ru.openmes.core.data.AppSettings
import ru.openmes.core.data.DiaryRepository
import ru.openmes.core.data.Session
import ru.openmes.core.data.SessionRepository
import ru.openmes.core.data.SettingsRepository
import ru.openmes.core.data.ThemeMode

class MoreViewModel(
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    val session: StateFlow<Session> = sessionRepository.session

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _avatar = MutableStateFlow<ImageBitmap?>(null)
    val avatar = _avatar.asStateFlow()

    init {
        viewModelScope.launch {
            sessionRepository.session
                .map { (it as? Session.LoggedIn)?.currentChild?.personGuid }
                .distinctUntilChanged()
                .collect { guid -> loadAvatar(guid) }
        }
    }

    /** Сохранённый аватар показывается сразу, затем обновляется из сети. */
    private suspend fun loadAvatar(personGuid: String?) {
        _avatar.value = null
        if (personGuid == null) return
        _avatar.value = diaryRepository.getSavedAvatar(personGuid)?.decodeBitmap()
        runSuspendCatching { diaryRepository.getAvatar(personGuid) }
            .onSuccess { bytes -> _avatar.value = bytes?.decodeBitmap() }
    }

    private suspend fun ByteArray.decodeBitmap(): ImageBitmap? = withContext(Dispatchers.Default) {
        BitmapFactory.decodeByteArray(this@decodeBitmap, 0, size)?.asImageBitmap()
    }

    fun selectChild(personId: String) = viewModelScope.launch {
        sessionRepository.selectChild(personId)
    }

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch {
        settingsRepository.setThemeMode(mode)
    }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDynamicColor(enabled)
    }

    fun setMarksNotifications(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setMarksNotifications(enabled)
    }

    fun setHideMarkValues(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setHideMarkValues(enabled)
    }

    fun setPin(pin: String?) = viewModelScope.launch {
        settingsRepository.setPin(pin)
    }

    fun setBiometricEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setBiometricEnabled(enabled)
    }

    fun logout() = viewModelScope.launch {
        sessionRepository.logout()
    }
}
