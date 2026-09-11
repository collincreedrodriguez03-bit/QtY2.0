package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiThinkingClient
import com.example.data.PriceTickEntity
import com.example.data.QtyRepository
import com.example.engine.HorizonPrediction
import com.example.engine.WalkForwardResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QtyUiState(
    val currentBtcPrice: Double? = null,
    val isLivePolling: Boolean = false,
    val horizons: List<HorizonPrediction> = emptyList(),
    val recentTicks: List<PriceTickEntity> = emptyList(),
    val activeAudit: WalkForwardResult? = null,
    val isAuditing: Boolean = false,
    val aiAuditResult: String = "",
    val isAiThinking: Boolean = false,
    val errorMessage: String? = null
)

class QtyViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = QtyRepository(application)

    private val _uiState = MutableStateFlow(QtyUiState())
    val uiState: StateFlow<QtyUiState> = _uiState.asStateFlow()

    private var pollingJob: Job? = null

    init {
        observingDatabase()
        runSingleTickUpdate()
    }

    private fun observingDatabase() {
        viewModelScope.launch {
            repository.recentTicks.collect { ticks ->
                _uiState.update { it.copy(recentTicks = ticks) }
            }
        }
    }

    fun toggleLivePolling() {
        val newState = !_uiState.value.isLivePolling
        _uiState.update { it.copy(isLivePolling = newState) }
        if (newState) {
            startPolling()
        } else {
            pollingJob?.cancel()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (_uiState.value.isLivePolling) {
                runSingleTickUpdate()
                delay(3000)
            }
        }
    }

    fun runSingleTickUpdate() {
        viewModelScope.launch {
            try {
                val (price, preds) = repository.fetchAndProcessTick()
                _uiState.update {
                    it.copy(
                        currentBtcPrice = price,
                        horizons = preds,
                        errorMessage = if (price == null) "API Telemetry Unavailable - Failed Closed." else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.localizedMessage, currentBtcPrice = null) }
            }
        }
    }

    fun runWalkForwardAudit(horizon: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuditing = true) }
            try {
                val result = repository.runWalkForwardAudit(horizon)
                _uiState.update { it.copy(activeAudit = result, isAuditing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isAuditing = false, errorMessage = e.localizedMessage) }
            }
        }
    }

    fun requestHighThinkingAudit(customPrompt: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAiThinking = true, aiAuditResult = "") }
            val prompt = if (customPrompt.isNotBlank()) customPrompt else "Perform a rigorous quantitative audit of QtY BTC prediction engine across 5s-900s horizons, evaluating win rate calibration, feature importance stability, and no-lookahead provenance."
            val result = GeminiThinkingClient.auditTradingEngine(prompt)
            _uiState.update { it.copy(isAiThinking = false, aiAuditResult = result) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }
}
