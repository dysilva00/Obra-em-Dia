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
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.repo.BookingOutcome
import pt.obraemdia.app.data.repo.ExpenseOutcome
import pt.obraemdia.app.data.repo.NewExpense
import pt.obraemdia.app.data.repo.NewBooking
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewPayment
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.SaveOutcome
import pt.obraemdia.app.data.repo.Settings
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.data.repo.StatusOutcome
import pt.obraemdia.app.data.repo.toBookings
import pt.obraemdia.app.data.repo.toUnavailabilities
import pt.obraemdia.domain.job.StatusChange
import pt.obraemdia.domain.job.label
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.Booking
import pt.obraemdia.domain.schedule.Conflict
import pt.obraemdia.domain.schedule.ConflictDetector
import pt.obraemdia.domain.schedule.SlotFinder
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.Unavailability
import java.time.Duration
import java.time.LocalDate

data class AgendaItem(val booking: Booking, val conflicts: List<Conflict>)

data class AgendaState(val items: List<AgendaItem>, val timeOff: List<Unavailability>, val marginMinutes: Long = 0)

/** Mensagem a mostrar ao utilizador depois de uma gravação. */
data class SaveResult(val ok: Boolean, val message: String)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.repository

    val today: LocalDate get() = LocalDate.now(AGENDA_ZONE)

    val snapshot: StateFlow<Snapshot?> =
        repo.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Agenda de ~4 meses para trás a 1 ano para a frente, para o calendário mensal.
     * Com duas pessoas são poucas centenas de reservas; não justifica paginação.
     */
    val agenda: StateFlow<AgendaState?> = run {
        val from = today.minusDays(120).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        val to = today.plusDays(366).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        combine(repo.schedule(from, to), repo.snapshot) { (rows, timeOff), snap ->
            buildAgenda(rows, timeOff, snap.settings[Settings.TRAVEL_MARGIN_MINUTES]?.toLongOrNull() ?: 0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    private fun buildAgenda(rows: List<ScheduleRow>, timeOff: List<TimeOffEntity>, marginMinutes: Long): AgendaState {
        val bookings = rows.toBookings()
        val unavailable = timeOff.toUnavailabilities()
        val detector = ConflictDetector(Duration.ofMinutes(marginMinutes))
        return AgendaState(bookings.map { AgendaItem(it, detector.conflictsFor(it, bookings, unavailable)) }, unavailable, marginMinutes)
    }

    /**
     * Pré-visualização no formulário, com a agenda carregada. É só indicativa: a verificação
     * definitiva repete-se na transação de gravação (que também cobre datas fora do intervalo carregado).
     */
    fun previewConflicts(jobId: String, siteId: String?, label: String, workerIds: Set<String>, slots: List<TimeSlot>): List<Conflict> {
        val state = agenda.value ?: return emptyList()
        val detector = ConflictDetector(Duration.ofMinutes(state.marginMinutes))
        val existing = state.items.map { it.booking }
        return slots.flatMap { detector.conflictsFor(Booking("preview", jobId, siteId, label, workerIds, it), existing, state.timeOff) }
    }

    fun suggestDays(workerIds: Set<String>, siteId: String?, slotFor: (LocalDate) -> TimeSlot, from: LocalDate): List<LocalDate> {
        val state = agenda.value ?: return emptyList()
        val finder = SlotFinder(ConflictDetector(Duration.ofMinutes(state.marginMinutes)))
        return finder.firstFreeDays(workerIds, slotFor, from, state.items.map { it.booking }, state.timeOff, siteId = siteId, horizonDays = 40)
    }

    fun freeWorkers(candidates: Collection<String>, siteId: String?, slots: List<TimeSlot>): List<String> {
        val state = agenda.value ?: return emptyList()
        val finder = SlotFinder(ConflictDetector(Duration.ofMinutes(state.marginMinutes)))
        val existing = state.items.map { it.booking }
        return candidates.filter { w -> slots.all { finder.freeWorkers(listOf(w), it, existing, state.timeOff, siteId).isNotEmpty() } }
    }

    suspend fun createBooking(input: NewBooking): Pair<SaveResult, List<Conflict>> = runCatching {
        when (val outcome = repo.createBooking(input, agenda.value?.marginMinutes ?: 0)) {
            is BookingOutcome.Saved -> SaveResult(
                true,
                "Marcação gravada (${outcome.days} dia(s))" + if (outcome.jobBecameScheduled) " · obra passou a Agendada" else "",
            ) to emptyList()
            is BookingOutcome.Conflicts -> SaveResult(false, "Há sobreposições: escolha outra opção ou indique o motivo") to outcome.conflicts
            BookingOutcome.Duplicate -> SaveResult(true, "Esta marcação já tinha sido gravada") to emptyList()
        }
    }.getOrElse { SaveResult(false, "Não foi possível gravar: ${it.message}") to emptyList() }

    suspend fun changeStatus(jobId: String, change: StatusChange): SaveResult = runCatching {
        when (val outcome = repo.changeStatus(jobId, change)) {
            is StatusOutcome.Changed -> SaveResult(
                true,
                "Estado: ${change.target.label}" + if (outcome.releasedBookings > 0) " · ${outcome.releasedBookings} reserva(s) futura(s) libertada(s)" else "",
            )
            is StatusOutcome.Rejected -> SaveResult(false, outcome.message)
        }
    }.getOrElse { SaveResult(false, "Não foi possível mudar o estado: ${it.message}") }

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

    suspend fun recordExpense(input: NewExpense): SaveResult = runCatching {
        when (val outcome = repo.recordExpense(input)) {
            is ExpenseOutcome.Saved -> SaveResult(
                true,
                "Gasto registado" + if (outcome.unallocated.isZero) "" else " · ${outcome.unallocated.format()} por classificar",
            )
            is ExpenseOutcome.Duplicate -> SaveResult(
                false,
                outcome.existing?.let { d ->
                    "Este documento já está registado: ${listOfNotNull(d.supplierName, d.docNumber).joinToString(" ")} de ${d.docDate.shortLabel()}, ${Money(d.grossCents).format()}"
                } ?: "Este documento já está registado",
            )
        }
    }.getOrElse { SaveResult(false, "Não foi possível gravar: ${it.message}") }

    suspend fun paySupplier(operationId: String, documentId: String, amount: Money, date: LocalDate, method: PaymentMethod): SaveResult = save {
        when (repo.paySupplier(operationId, documentId, amount, date, method)) {
            SaveOutcome.Saved -> "Pagamento ao fornecedor registado"
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
