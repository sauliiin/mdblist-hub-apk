package com.mdblisthub.tv.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mdblisthub.tv.core.data.DataGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginState(
    val key: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
)

class LoginViewModel(private val graph: DataGraph) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    fun onKeyChange(value: String) {
        _state.update { it.copy(key = value, error = null) }
    }

    fun signIn() {
        val key = _state.value.key.trim()
        if (key.isEmpty() || _state.value.busy) return

        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            graph.auth.signIn(key).fold(
                onSuccess = {
                    // A fresh session has an empty cache, so the workers start
                    // immediately rather than waiting for their next window.
                    graph.scheduler.onSignedIn()
                    _state.update { it.copy(busy = false, signedIn = true) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            error = "Não consegui validar a chave. Confira e tente de novo. " +
                                "(${error.message ?: "sem detalhe"})",
                        )
                    }
                },
            )
        }
    }
}
