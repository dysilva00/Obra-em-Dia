package pt.obraemdia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import pt.obraemdia.app.AppContainer
import pt.obraemdia.app.data.db.ScheduleRow
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.SaveOutcome
import pt.obraemdia.app.data.repo.Settings
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.Booking
import pt.obraemdia.domain.schedule.Conflict
import pt.obraemdia.domain.schedule.ConflictDetector
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.Unavailability
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class AgendaItem(val booking: Booking, val conflicts: List<Conflict>)

data class AgendaState(val items: List<AgendaItem>, val timeOff: List<Unavailability>)

/** Mensagem a mostrar ao utilizador depois de uma gravação. */
data class SaveResult(val ok: Boolean, val message: String)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.repository

    val today: LocalDate get() = LocalDate.now(AGENDA_ZONE)

    val snapshot: StateFlow<Snapshot?> =
        repo.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Agenda das próximas semanas (inclui os últimos dias para mostrar o contexto). */
    val agenda: StateFlow<AgendaState?> = run {
        val from = today.minusDays(1).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        val to = today.plusDays(42).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        combine(repo.schedule(from, to), repo.snapshot) { (rows, timeOff), snap ->
            buildAgenda(rows, timeOff, snap.settings[Settings.TRAVEL_MARGIN_MINUTES]?.toLongOrNull() ?: 0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    private fun buildAgenda(rows: List<ScheduleRow>, timeOff: List<TimeOffEntity>, marginMinutes: Long): AgendaState {
        val bookings = rows.groupBy { it.entry.id }.map { (_, group) ->
            val e = group.first()
            Booking(
                id = e.entry.id,
                jobId = e.entry.jobId,
                siteId = e.siteId,
                label = e.jobTitle,
                workerIds = group.map { it.workerId }.toSet(),
                slot = TimeSlot(Instant.ofEpochMilli(e.entry.startMs), Instant.ofEpochMilli(e.entry.endMs)),
                firmness = e.entry.firmness,
            )
        }
        val unavailable = timeOff.map {
            Unavailability(it.id, it.workerId, TimeSlot(Instant.ofEpochMilli(it.startMs), Instant.ofEpochMilli(it.endMs)), it.reason)
        }
        val detector = ConflictDetector(Duration.ofMinutes(marginMinutes))
        return AgendaState(bookings.map { AgendaItem(it, detector.conflictsFor(it, bookings, unavailable)) }, unavailable)
    }

    suspend fun createJob(input: NewJob): Result<String> = runCatching { repo.createJob(input) }

    suspend fun recordTime(input: NewTimeEntries): SaveResult = save {
        when (repo.recordTime(input)) {
            SaveOutcome.Saved -> "Horas registadas"
            SaveOutcome.Duplicate -> "Estas horas já tinham sido gravadas"
        }
    }

    suspend fun recordPayment(input: NewPayment): SaveResult = save {
        when (repo.recordPayment(input)) {
            SaveOutcome.Saved -> "Pagamento registado"
            SaveOutcome.Duplicate -> "Este pagamento já tinha sido gravado"
        }
    }

    suspend fun deleteDemoData(): SaveResult =
        runCatching { container.demoSeeder.deleteDemoData() }.fold(
            onSuccess = { SaveResult(true, "Dados de demonstração removidos") },
            // A remoção é atómica: se falhar (p. ex. horas reais numa obra fictícia), nada foi apagado.
            onFailure = { SaveResult(false, "Nada foi removido: há registos reais ligados a obras de demonstração") },
        )

    private suspend fun save(block: suspend () -> String): SaveResult =
        runCatching { block() }.fold(
            onSuccess = { SaveResult(true, it) },
            onFailure = { SaveResult(false, "Não foi possível gravar: ${it.message ?: "erro desconhecido"}") },
        )
}
