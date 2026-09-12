package com.niutrip.app.ui.deeplink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.niutrip.app.data.remote.ApiService
import com.niutrip.app.data.remote.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class IncomingShareDecision { CHECKING, PROMPT, IGNORE }

class IncomingShareViewModel(token: String, api: ApiService) : ViewModel() {
    private val _decision = MutableStateFlow(IncomingShareDecision.CHECKING)
    val decision = _decision.asStateFlow()

    init {
        viewModelScope.launch {
            _decision.value = runCatching { apiCall { api.inspectShare(token) } }
                .fold(
                    onSuccess = { if (it.is_owner) IncomingShareDecision.IGNORE else IncomingShareDecision.PROMPT },
                    onFailure = { IncomingShareDecision.IGNORE },
                )
        }
    }
}
