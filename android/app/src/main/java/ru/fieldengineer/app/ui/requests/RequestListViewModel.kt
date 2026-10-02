package ru.fieldengineer.app.ui.requests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.fieldengineer.app.data.db.RequestListRow
import ru.fieldengineer.app.data.repo.AuthRepository
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.SlaIndicator
import ru.fieldengineer.app.domain.TrustedClock
import ru.fieldengineer.app.domain.currentDue
import ru.fieldengineer.app.domain.sortRequests
import ru.fieldengineer.app.ui.ticker

enum class RequestFilter { ACTIVE, FINISHED }

/** Заявка в списке и её индикатор срока (у отменённых и приостановленных — нет). */
data class RequestListItem(val row: RequestListRow, val sla: SlaIndicator?)

data class RequestListUiState(
    val engineerName: String = "",
    val filter: RequestFilter = RequestFilter.ACTIVE,
    val items: List<RequestListItem> = emptyList(),
    val activeCount: Int = 0,
    val finishedCount: Int = 0,
    val loading: Boolean = true,
)

class RequestListViewModel(
    private val auth: AuthRepository,
    repository: RequestRepository,
    clock: TrustedClock,
) : ViewModel() {

    private val filter = MutableStateFlow(RequestFilter.ACTIVE)
    private val engineerName = MutableStateFlow("")

    private val rows = auth.currentEmployeeId?.let(repository::observeMyRequests) ?: flowOf(emptyList())

    val state: StateFlow<RequestListUiState> =
        combine(rows, filter, engineerName, repository.observeSlaCalculator(), ticker()) { all, f, name, sla, _ ->
            val now = clock.now().time
            val sorted = sortRequests(
                all,
                isFinished = { RequestStatus.isFinished(it.status) },
                due = { currentDue(it.reactionDue, it.resolutionDue, it.arrivedAt) },
                finishedAt = { it.closedAt ?: it.registeredAt },
            )
            val finished = sorted.count { RequestStatus.isFinished(it.status) }
            RequestListUiState(
                engineerName = name,
                filter = f,
                items = sorted
                    .filter { RequestStatus.isFinished(it.status) == (f == RequestFilter.FINISHED) }
                    .map { r ->
                        val showSla = r.status != RequestStatus.CANCELLED && r.status != RequestStatus.SUSPENDED
                        RequestListItem(r, if (!showSla) null else sla.indicator(
                            now, r.reactionDue, r.resolutionDue, r.arrivedAt, r.completedAt,
                            r.reactionMin, r.resolutionMin, r.mode,
                        ))
                    },
                activeCount = sorted.size - finished,
                finishedCount = finished,
                loading = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestListUiState())

    init {
        viewModelScope.launch { engineerName.value = auth.currentEmployee()?.fullName.orEmpty() }
    }

    fun setFilter(value: RequestFilter) {
        filter.value = value
    }
}
