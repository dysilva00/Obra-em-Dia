package pt.obraemdia.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import pt.obraemdia.app.AppContainer
import pt.obraemdia.app.data.db.ScheduleRow
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.repo.BookingOutcome
import pt.obraemdia.app.data.repo.AgreementChange
import pt.obraemdia.app.data.repo.JobDetails
import pt.obraemdia.app.data.repo.NewCharge
import pt.obraemdia.app.data.db.ChargeKind
import pt.obraemdia.app.data.repo.ClosureConfirmations
import pt.obraemdia.app.data.repo.ClosureOutcome
import pt.obraemdia.app.data.repo.ClosureReview
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.domain.job.ClosureIssue
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

    companion object {
        /** Limite anual a vigiar (valor sem IVA, por NIF). Vazio = sem limite. Nunca é assumido. */
        const val INVOICE_LIMIT_KEY = "invoice_year_limit_cents"
    }

    private val repo = container.repository

    val today: LocalDate get() = LocalDate.now(AGENDA_ZONE)

    val snapshot: StateFlow<Snapshot?> =
        repo.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val mediaCounts: StateFlow<Map<String, pt.obraemdia.app.data.db.MediaCounts>> =
        container.media.counts.map { list -> list.associateBy { it.jobId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val clients: StateFlow<List<pt.obraemdia.app.data.db.ClientEntity>> =
        repo.clients.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Agenda de ~4 meses para trás a 1 ano para a frente, para o calendário mensal.
     * Com poucas pessoas são poucas centenas de reservas; não justifica paginação.
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

    /** [pdf]: comprovativo a guardar ligado ao documento (só depois de o gasto estar gravado). */
    suspend fun recordExpense(input: NewExpense, pdf: android.net.Uri? = null): SaveResult = runCatching {
        when (val outcome = repo.recordExpense(input)) {
            is ExpenseOutcome.Saved -> {
                val attached = pdf == null || runCatching {
                    container.media.addAttachment(
                        input.lines.map { it.jobId }.distinct().singleOrNull(), pdf, pt.obraemdia.app.data.db.AttachmentKind.DOCUMENT,
                        listOfNotNull(input.supplierName, input.docNumber).joinToString(" "), input.docDate, outcome.documentId,
                    )
                }.isSuccess
                SaveResult(
                    true,
                    "Gasto registado" + (if (outcome.unallocated.isZero) "" else " · ${outcome.unallocated.format()} por classificar") +
                        if (attached) "" else " · o PDF não pôde ser guardado",
                )
            }
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

    private val corrections get() = container.corrections

    suspend fun reverseMovement(id: String, reason: String): SaveResult = save {
        corrections.reverseMovement(id, reason, today)
        "Movimento estornado"
    }

    suspend fun updateTimeEntry(id: String, date: LocalDate, minutes: Int, kind: TimeEntryKind, billable: Boolean, note: String?): SaveResult = save {
        corrections.updateTimeEntry(id, date, minutes, kind, billable, note)
        "Registo de horas corrigido"
    }

    suspend fun deleteTimeEntry(id: String, reason: String): SaveResult = save {
        corrections.deleteTimeEntry(id, reason)
        "Registo de horas anulado"
    }

    suspend fun voidPurchase(id: String, reason: String): SaveResult = save {
        val reversed = corrections.voidPurchase(id, reason, today)
        "Documento anulado" + if (reversed > 0) " · $reversed pagamento(s) estornado(s)" else ""
    }

    suspend fun reviewClosure(jobId: String, c: ClosureConfirmations): ClosureReview? =
        runCatching { container.repository.transaction { corrections.review(jobId, c) } }.getOrNull()

    suspend fun closeJob(jobId: String, c: ClosureConfirmations): SaveResult = runCatching {
        when (val o = corrections.close(jobId, c)) {
            is ClosureOutcome.Closed -> SaveResult(true, "Obra encerrada (revisão ${o.revision})")
            is ClosureOutcome.Blocked -> SaveResult(false, "Ainda não é possível encerrar: " + o.issues.filterIsInstance<ClosureIssue.Blocker>().joinToString("; ") { it.message })
        }
    }.getOrElse { SaveResult(false, "Não foi possível encerrar: ${it.message}") }

    fun decodeBalance(c: pt.obraemdia.app.data.db.JobClosureEntity) = corrections.decodeBalance(c)

    suspend fun reopenJob(jobId: String, reason: String): SaveResult = save {
        corrections.reopen(jobId, reason)
        "Obra reaberta"
    }

    suspend fun updateJobDetails(jobId: String, d: JobDetails): SaveResult = save {
        container.agreement.updateDetails(jobId, d)
        "Obra atualizada"
    }

    suspend fun changeAgreement(jobId: String, c: AgreementChange): SaveResult = save {
        container.agreement.changeAgreement(jobId, c)
        "Acordo atualizado (o anterior fica no histórico)"
    }

    suspend fun addCharge(input: NewCharge): SaveResult = save {
        when (container.agreement.addCharge(input)) {
            SaveOutcome.Saved -> if (input.kind == ChargeKind.EXTRA) "Extra registado" else "Material cobrado registado"
            SaveOutcome.Duplicate -> "Já tinha sido gravado"
        }
    }

    suspend fun cancelCharge(id: String, reason: String): SaveResult = save {
        container.agreement.cancelCharge(id, reason)
        "Anulado (fica no histórico)"
    }

    val timeOff: StateFlow<List<pt.obraemdia.app.data.db.TimeOffEntity>> =
        container.settings.timeOff.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun updateWorker(id: String, name: String, sale: Money, cost: Money): SaveResult = save {
        container.settings.updateWorker(id, name, pt.obraemdia.domain.money.HourlyRate(sale.cents), pt.obraemdia.domain.money.HourlyRate(cost.cents))
        "Pessoa atualizada (as horas já registadas mantêm as taxas)"
    }

    suspend fun saveBusiness(opening: Money, marginMinutes: Int, hours: pt.obraemdia.domain.schedule.WorkHours): SaveResult = save {
        container.settings.saveBusiness(opening, marginMinutes, hours)
        "Definições gravadas"
    }

    suspend fun addWorker(name: String, sale: Money, cost: Money): SaveResult = save {
        container.settings.addWorker(name, pt.obraemdia.domain.money.HourlyRate(sale.cents), pt.obraemdia.domain.money.HourlyRate(cost.cents))
        "${name.trim()} adicionado(a)"
    }

    suspend fun removeWorker(id: String): SaveResult = save {
        container.settings.removeWorker(id)
        "Pessoa removida (os registos antigos mantêm-se)"
    }

    suspend fun saveReminders(prefs: pt.obraemdia.app.reminders.ReminderPrefs, context: android.content.Context): SaveResult = save {
        container.settings.saveReminders(prefs)
        pt.obraemdia.app.reminders.ReminderWorker.schedule(context, prefs)
        if (prefs.enabled) "Lembrete diário às ${prefs.time} ligado" else "Lembretes desligados"
    }

    suspend fun addTimeOff(workerId: String, from: LocalDate, to: LocalDate, reason: String): SaveResult = save {
        val clashes = container.settings.addTimeOff(workerId, from, to, reason)
        "Indisponibilidade registada" + if (clashes > 0) " · atenção: $clashes marcação(ões) ficam em conflito (ver Agenda)" else ""
    }

    suspend fun removeTimeOff(id: String): SaveResult = save {
        container.settings.removeTimeOff(id)
        "Indisponibilidade removida"
    }

    private fun agendaFacts(state: AgendaState?) =
        pt.obraemdia.app.data.repo.AgendaFacts(state?.items.orEmpty().map { it.booking to it.conflicts })

    /** Alertas úteis (sem transformar cada registo numa notificação). */
    val alerts: StateFlow<List<pt.obraemdia.app.data.repo.Alert>> =
        combine(repo.snapshot, agenda) { snap, ag -> pt.obraemdia.app.data.repo.Insights.alerts(snap, agendaFacts(ag), today) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun forecast(
        snapshot: Snapshot,
        horizonDays: Long,
        includeProposals: Boolean,
        postponed: Map<String, LocalDate>,
    ): Pair<pt.obraemdia.domain.cash.ForecastResult, List<String>> {
        val inputs = pt.obraemdia.app.data.repo.Insights.forecastInputs(snapshot, agendaFacts(agenda.value), today)
        val result = pt.obraemdia.domain.cash.Forecast.project(
            today, horizonDays, snapshot.accumulatedBalance(today), inputs.items, includeProposals, inputs.unknowns, postponed,
        )
        return result to inputs.assumptions
    }

    suspend fun addInstallment(jobId: String, due: LocalDate, amount: Money, note: String?): SaveResult = save {
        container.agreement.addInstallment(jobId, due, amount, note)
        "Prestação registada"
    }

    suspend fun removeInstallment(id: String): SaveResult = save {
        container.agreement.removeInstallment(id)
        "Prestação removida (fica no histórico)"
    }

    val media get() = container.media

    val issuedInvoices: StateFlow<List<pt.obraemdia.app.data.db.IssuedInvoiceEntity>> =
        container.invoices.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun recordInvoice(input: pt.obraemdia.app.data.repo.IssuedInvoiceInput): SaveResult = runCatching {
        when (val o = container.invoices.record(input)) {
            pt.obraemdia.app.data.repo.InvoiceOutcome.Saved -> SaveResult(true, "Fatura registada")
            is pt.obraemdia.app.data.repo.InvoiceOutcome.Duplicate ->
                SaveResult(false, "Esta fatura já está registada (${o.existing.docNumber} de ${o.existing.issueDate.shortLabel()})")
            is pt.obraemdia.app.data.repo.InvoiceOutcome.Rejected -> SaveResult(false, o.message)
        }
    }.getOrElse { SaveResult(false, "Não foi possível gravar: ${it.message}") }

    /** id do anexo → caminho relativo, para "Ver PDF". */
    val invoiceAttachments: StateFlow<Map<String, String>> =
        container.database.mediaDao().observeInvoiceAttachments().map { l -> l.associate { it.id to it.relativePath } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun invoiceAttachmentFile(relativePath: String) = container.invoices.attachmentFile(relativePath)

    /** Resultado do leitor de câmara, entregue ao ecrã de faturas. */
    val scannedQr = kotlinx.coroutines.flow.MutableStateFlow<pt.obraemdia.domain.fiscalqr.AtQrDocument?>(null)

    suspend fun cancelInvoice(id: String, reason: String): SaveResult = save {
        container.invoices.cancel(id, reason)
        "Registo anulado (fica no histórico)"
    }

    suspend fun setInvoiceLimit(limit: Money?): SaveResult = save {
        container.repository.transaction {
            container.database.settingsDao().put(
                pt.obraemdia.app.data.db.SettingEntity(INVOICE_LIMIT_KEY, limit?.cents?.toString().orEmpty()),
            )
        }
        if (limit == null) "Limite removido" else "Limite anual de ${limit.format()} gravado"
    }

    val history: StateFlow<List<pt.obraemdia.app.data.db.AuditEventEntity>> =
        container.database.auditDao().observeRecent(500).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun deleteDemoData(): SaveResult =
        runCatching { container.demoSeeder.deleteDemoData() }.fold(
            onSuccess = { SaveResult(true, "Dados de demonstração removidos") },
            // A remoção é atómica: se falhar (p. ex. horas reais numa obra fictícia), nada foi apagado.
            onFailure = { SaveResult(false, "Nada foi removido: ${it.message}") },
        )

    private suspend fun save(block: suspend () -> String): SaveResult =
        runCatching { block() }.fold(
            onSuccess = { SaveResult(true, it) },
            onFailure = { SaveResult(false, "Não foi possível gravar: ${it.message ?: "erro desconhecido"}") },
        )
}
