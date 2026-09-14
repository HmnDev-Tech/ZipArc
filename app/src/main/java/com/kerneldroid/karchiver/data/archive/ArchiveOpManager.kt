package com.kerneldroid.karchiver.data.archive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OpKind { COMPRESS, EXTRACT }

data class ActiveOp(val kind: OpKind, val label: String, val done: Long, val total: Long)

sealed interface OpOutcome {
    data object Success : OpOutcome
    data class Failed(val message: String) : OpOutcome
    data object Cancelled : OpOutcome
}

data class FinishedOp(val kind: OpKind, val label: String, val outcome: OpOutcome)

object ArchiveOpManager {
    private val _active = MutableStateFlow<ActiveOp?>(null)
    val active: StateFlow<ActiveOp?> = _active.asStateFlow()
    private val _finished = MutableStateFlow<FinishedOp?>(null)
    val finished: StateFlow<FinishedOp?> = _finished.asStateFlow()

    fun started(kind: OpKind, label: String) {
        _active.value = ActiveOp(kind, label, 0L, 0L)
    }

    fun progress(done: Long, total: Long) {
        val current = _active.value ?: return
        _active.value = current.copy(done = done, total = total)
    }

    fun finished(kind: OpKind, label: String, outcome: OpOutcome) {
        _active.value = null
        _finished.value = FinishedOp(kind, label, outcome)
    }

    fun consumeFinished() {
        _finished.value = null
    }
}
