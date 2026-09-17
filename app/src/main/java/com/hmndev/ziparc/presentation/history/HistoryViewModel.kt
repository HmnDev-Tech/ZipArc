package com.hmndev.ziparc.presentation.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hmndev.ziparc.data.history.HistoryEntry
import com.hmndev.ziparc.data.history.HistoryRepository
import com.hmndev.ziparc.data.history.HistoryTypeFilter
import com.hmndev.ziparc.data.history.filterHistory
import com.hmndev.ziparc.data.search.parseSearchQuery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = HistoryRepository.get(application)

    private val query = MutableStateFlow("")
    private val typeFilter = MutableStateFlow(HistoryTypeFilter.ALL)
    private val newestFirst = MutableStateFlow(true)

    val queryText: StateFlow<String> = query
    val filter: StateFlow<HistoryTypeFilter> = typeFilter
    val sortNewestFirst: StateFlow<Boolean> = newestFirst

    private val stored: StateFlow<List<HistoryEntry>> = repo.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val entries: StateFlow<List<HistoryEntry>> =
        combine(stored, query, typeFilter, newestFirst) { entries, q, filter, newest ->
            filterHistory(entries, parseSearchQuery(q), filter, newest)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hasAnyEntries: StateFlow<Boolean> = stored
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setQuery(value: String) {
        query.value = value
    }

    fun setFilter(value: HistoryTypeFilter) {
        typeFilter.value = value
    }

    fun toggleSort() {
        newestFirst.value = !newestFirst.value
    }

    fun remove(path: String) {
        viewModelScope.launch { repo.remove(path) }
    }

    fun clear() {
        viewModelScope.launch { repo.clear() }
    }
}
