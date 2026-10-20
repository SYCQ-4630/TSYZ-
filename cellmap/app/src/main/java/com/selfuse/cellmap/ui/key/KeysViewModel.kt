package com.selfuse.cellmap.ui.key

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.selfuse.cellmap.CellMapApp
import com.selfuse.cellmap.core.model.KeyHealth
import com.selfuse.cellmap.core.model.KeyUi
import com.selfuse.cellmap.data.key.KeyAddResult
import com.selfuse.cellmap.data.key.KeyVerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class KeysUiState(
    val keys: List<KeyUi> = emptyList(),
    val health: KeyHealth = KeyHealth(total = 0, verified = 0, maxDailyUsage = 0),
    val verifyingId: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

private data class KeysExtra(
    val verifyingId: String? = null,
    val busy: Boolean = false,
    val message: String? = null
)

class KeysViewModel(app: CellMapApp) : ViewModel() {
    private val c = app.container
    private val extra = MutableStateFlow(KeysExtra())

    val state: StateFlow<KeysUiState> = combine(
        c.keyRepository.keys,
        c.keyRepository.keyHealth,
        extra
    ) { keys, health, ex ->
        KeysUiState(
            keys = keys,
            health = health,
            verifyingId = ex.verifyingId,
            busy = ex.busy,
            message = ex.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KeysUiState())

    fun add(label: String, rawKey: String) {
        if (extra.value.busy) return
        viewModelScope.launch {
            extra.update { it.copy(busy = true) }
            val result = c.keyRepository.addKey(label, rawKey)
            extra.update {
                it.copy(
                    busy = false,
                    message = when (result) {
                        KeyAddResult.Success -> "已保存，建议立即验证 Key"
                        is KeyAddResult.Error -> result.message
                    }
                )
            }
        }
    }

    fun verify(id: String) {
        if (extra.value.verifyingId != null) return
        viewModelScope.launch {
            extra.update { it.copy(verifyingId = id) }
            val result = c.keyRepository.verify(id)
            val text = when (result) {
                is KeyVerifyResult.Success -> result.message
                is KeyVerifyResult.Invalid -> result.message
                is KeyVerifyResult.NetworkError -> result.message
            }
            extra.update { it.copy(verifyingId = null, message = text) }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            val removed = c.keyRepository.removeKey(id)
            extra.update { it.copy(message = if (removed) "已删除该 Key" else "删除失败，Key 不存在") }
        }
    }

    fun consumeMessage() {
        extra.update { it.copy(message = null) }
    }

    companion object {
        fun factory(app: CellMapApp) = viewModelFactory {
            initializer { KeysViewModel(app) }
        }
    }
}
