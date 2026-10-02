package ru.fieldengineer.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.domain.ClosedRequest
import ru.fieldengineer.app.domain.MOSCOW
import ru.fieldengineer.app.domain.Period
import ru.fieldengineer.app.domain.PeriodStats
import ru.fieldengineer.app.domain.RequestStatus
import ru.fieldengineer.app.domain.TrustedClock
import ru.fieldengineer.app.domain.WorkTimeCalculator
import ru.fieldengineer.app.ui.ticker
import java.time.Duration
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

data class StatsUiState(
    val monthTitle: String = "",
    val todayWork: Duration = Duration.ZERO,
    val todayRoad: Duration = Duration.ZERO,
    val monthWork: Duration = Duration.ZERO,
    val monthRoad: Duration = Duration.ZERO,
    val closedInMonth: Int = 0,
    val resolvedInTimePercent: Int? = null,
    val reactedInTimePercent: Int? = null,
    val active: Int = 0,
)

/**
 * Э9 «Мои показатели» (проект, раздел 4): трудозатраты с обрезкой по границам суток и
 * месяца (по Москве), доля заявок в срок среди закрытых в месяце.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(engineerId: Int?, repository: RequestRepository, private val clock: TrustedClock) : ViewModel() {

    // текущий месяц пересчитывается по таймеру: если экран открыт в полночь 31-го, запрос сменится
    private val month = ticker().map { YearMonth.from(clock.now().time.atZone(MOSCOW)) }.distinctUntilChanged()

    val state: StateFlow<StatsUiState> = (if (engineerId == null) flowOf(StatsUiState()) else
        month.flatMapLatest { m ->
            val period = PeriodStats.month(clock.now().time)
            combine(
                repository.observeEmployeeIntervals(engineerId, period.from, period.to),
                repository.observeMyRequests(engineerId),
                ticker(),
            ) { intervals, requests, _ ->
                val now = clock.now().time
                val day = PeriodStats.day(now)
                fun sum(p: Period, kind: Int) =
                    WorkTimeCalculator.inPeriod(intervals, p.from, p.to, now, kind)
                val share = PeriodStats.inTimeShare(
                    requests.filter { it.status == RequestStatus.CLOSED && it.closedAt != null }.map {
                        ClosedRequest(it.closedAt!!, it.arrivedAt, it.completedAt, it.reactionDue, it.resolutionDue)
                    },
                    period,
                )
                StatsUiState(
                    monthTitle = m.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.forLanguageTag("ru")) +
                        " ${m.year}",
                    todayWork = sum(day, WorkTimeCalculator.KIND_WORK),
                    todayRoad = sum(day, WorkTimeCalculator.KIND_ROAD),
                    monthWork = sum(period, WorkTimeCalculator.KIND_WORK),
                    monthRoad = sum(period, WorkTimeCalculator.KIND_ROAD),
                    closedInMonth = share.closed,
                    resolvedInTimePercent = share.resolvedPercent,
                    reactedInTimePercent = share.reactedPercent,
                    active = requests.count { !RequestStatus.isFinished(it.status) },
                )
            }
        }).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())
}
