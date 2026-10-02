package ru.fieldengineer.app.ui.card

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.db.RequestCardRow
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.RequestStatusMachine
import ru.fieldengineer.app.domain.SlaIndicator
import ru.fieldengineer.app.domain.TrustedClock
import ru.fieldengineer.app.domain.WorkTimeCalculator
import ru.fieldengineer.app.ui.ticker
import java.time.Duration

data class RequestCardUiState(
    val card: RequestCardRow? = null,
    val sla: SlaIndicator? = null,
    val roadTime: Duration = Duration.ZERO,
    val workTime: Duration = Duration.ZERO,
    val checklistDone: Int = 0,
    val checklistTotal: Int = 0,
    val targets: List<Int> = emptyList(),
    val canEditWork: Boolean = false,
    val workPhotos: Int = 0,
)

/** Состояние, которое меняет сам пользователь: черновик «Что сделано», ошибки, подтверждение. */
data class CardInput(
    val resultDraft: String? = null,     // null — черновик не трогали, показываем сохранённое
    val errors: List<String> = emptyList(),
    val confirmTarget: Int? = null,
    val busy: Boolean = false,
)

class RequestCardViewModel(
    private val requestId: Int,
    private val engineerId: Int?,
    private val repository: RequestRepository,
    clock: TrustedClock,
) : ViewModel() {

    private val _input = MutableStateFlow(CardInput())
    val input: StateFlow<CardInput> = _input

    val state: StateFlow<RequestCardUiState> = combine(
        repository.observeCard(requestId),
        repository.observeChecklist(requestId),
        // интервалы и фото объединены в пару: combine принимает не больше пяти потоков
        combine(repository.observeIntervals(requestId), repository.observePhotos(requestId), ::Pair),
        repository.observeSlaCalculator(),
        ticker(),
    ) { card, checklist, (intervals, photos), sla, _ ->
        if (card == null) return@combine RequestCardUiState()
        val r = card.request
        val now = clock.now().time
        val showSla = r.status != RequestStatus.CANCELLED && r.status != RequestStatus.SUSPENDED
        RequestCardUiState(
            card = card,
            sla = if (!showSla) null else sla.indicator(now, r.reactionDue, r.resolutionDue, r.arrivedAt,
                r.completedAt, card.reactionMin, card.resolutionMin, card.mode),
            roadTime = WorkTimeCalculator.total(intervals, now, WorkTimeCalculator.KIND_ROAD),
            workTime = WorkTimeCalculator.total(intervals, now, WorkTimeCalculator.KIND_WORK),
            checklistDone = checklist.count { it.done == true },
            checklistTotal = checklist.size,
            targets = RequestStatusMachine.targetsFor(r.status),
            canEditWork = RequestStatusMachine.canEditWork(r.status),
            workPhotos = photos.count { it.kind == RequestRepository.PHOTO_WORK },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestCardUiState())

    private var autosave: Job? = null

    /** «Что сделано» сохраняется само через секунду после последнего изменения. */
    fun onResultDraft(text: String) {
        _input.update { it.copy(resultDraft = text) }
        autosave?.cancel()
        autosave = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            safely { repository.saveResultText(requestId, text) }
        }
    }

    /** «Работы выполнены» и «Приостановить» — только после подтверждения. */
    fun onTarget(to: Int) {
        if (to == RequestStatus.WORK_DONE || to == RequestStatus.SUSPENDED) {
            _input.update { it.copy(confirmTarget = to) }
        } else {
            changeStatus(to)
        }
    }

    fun dismissConfirm() = _input.update { it.copy(confirmTarget = null) }
    fun dismissErrors() = _input.update { it.copy(errors = emptyList()) }

    fun changeStatus(to: Int) {
        if (_input.value.busy) return
        _input.update { it.copy(busy = true, confirmTarget = null) }
        viewModelScope.launch {
            autosave?.cancel()
            val errors = safely {
                // несохранённый текст «Что сделано» сохраняется перед проверкой
                _input.value.resultDraft?.let { repository.saveResultText(requestId, it) }
                if (engineerId == null) listOf("Сеанс завершён — войдите заново")
                else repository.changeStatus(requestId, to, engineerId)
            } ?: listOf("Не удалось сохранить изменения")
            _input.update {
                it.copy(busy = false, errors = errors, resultDraft = if (errors.isEmpty()) null else it.resultDraft)
            }
        }
    }

    /** Ошибку базы показываем на экране, а не роняем приложение. */
    private suspend fun <T> safely(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _input.update { it.copy(errors = listOf("Ошибка сохранения: ${e.message}")) }
            null
        }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 1_000L
    }
}
