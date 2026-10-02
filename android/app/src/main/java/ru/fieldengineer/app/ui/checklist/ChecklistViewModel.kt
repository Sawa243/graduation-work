package ru.fieldengineer.app.ui.checklist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.db.ChecklistRow
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.RequestStatusMachine

data class ChecklistUiState(
    val number: String = "",
    val workType: String = "",
    val items: List<ChecklistRow> = emptyList(),
    val editable: Boolean = false,
    val loading: Boolean = true,
)

class ChecklistViewModel(
    private val requestId: Int,
    private val repository: RequestRepository,
) : ViewModel() {

    /** Пункт, комментарий к которому сейчас редактируется в диалоге. */
    val editingComment = MutableStateFlow<ChecklistRow?>(null)

    val state: StateFlow<ChecklistUiState> =
        combine(repository.observeCard(requestId), repository.observeChecklist(requestId)) { card, items ->
            ChecklistUiState(
                number = card?.request?.number.orEmpty(),
                workType = card?.let { "${it.workTypeCode} · ${it.workTypeName}" }.orEmpty(),
                items = items,
                editable = card != null && RequestStatusMachine.canEditWork(card.request.status),
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChecklistUiState())

    /** Сообщение об ошибке сохранения (показывается внизу экрана). */
    val error = MutableStateFlow<String?>(null)

    fun toggle(item: ChecklistRow) {
        if (!state.value.editable) return
        save(item, item.done != true, item.comment)
    }

    fun saveComment(item: ChecklistRow, comment: String) {
        editingComment.value = null
        // checklist_result.comment — varchar(300): длиннее сервер не примет
        save(item, item.done == true, comment.take(MAX_COMMENT))
    }

    private fun save(item: ChecklistRow, done: Boolean, comment: String?) {
        viewModelScope.launch {
            try {
                repository.setChecklistMark(requestId, item.itemId, done, comment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = "Отметка не сохранена: ${e.message}"
            }
        }
    }

    private companion object {
        const val MAX_COMMENT = 300
    }
}
