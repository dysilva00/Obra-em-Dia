package pt.obraemdia.app.data.repo

import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.db.CashMovementEntity
import pt.obraemdia.app.data.db.JobRow
import pt.obraemdia.app.data.db.SettingEntity
import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.ClientBalance
import pt.obraemdia.domain.billing.JobResult
import pt.obraemdia.domain.billing.SaleCalculator
import pt.obraemdia.domain.billing.SaleInputs
import pt.obraemdia.domain.billing.TimeEntryFacts
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat
import pt.obraemdia.domain.money.sum
import pt.obraemdia.domain.money.sumMoney
import java.time.LocalDate
import java.time.YearMonth

/** Fotografia coerente dos dados usados pelos ecrãs de Início, Obras e Contas. */
data class Snapshot(
    val workers: List<WorkerEntity>,
    val jobs: List<JobRow>,
    val timeEntries: List<TimeEntryEntity>,
    val movements: List<CashMovementEntity>,
    val settings: Map<String, String>,
) {
    val hasDemoData: Boolean
        get() = jobs.any { it.job.isDemo } || movements.any { it.isDemo } || timeEntries.any { it.isDemo }

    val openingBalance: Money get() = Money(settings[Settings.OPENING_BALANCE_CENTS]?.toLongOrNull() ?: 0)

    fun workerName(id: String) = workers.firstOrNull { it.id == id }?.name ?: "?"

    val summaries: List<JobSummary> by lazy {
        val entriesByJob = timeEntries.groupBy { it.jobId }
        val cashByJob = movements.filter { it.jobId != null }.groupBy { it.jobId }
        jobs.map { JobSummary.of(it, entriesByJob[it.job.id].orEmpty(), cashByJob[it.job.id].orEmpty()) }
    }

    fun summary(jobId: String) = summaries.firstOrNull { it.row.job.id == jobId }

    fun movementsIn(month: YearMonth) = movements.filter { YearMonth.from(it.date) == month }

    /** Saldo acumulado: saldo inicial + todos os movimentos até [date], inclusive. */
    fun accumulatedBalance(date: LocalDate): Money =
        openingBalance + movements.filter { it.date <= date }.sumMoney { Money(it.signedCents) }
}

/** Resumo de uma obra derivado dos registos; nada disto é guardado na base. */
data class JobSummary(
    val row: JobRow,
    val entries: List<TimeEntryEntity>,
    val saleNet: Money?,
    /** Null quando falta a taxa de IVA: o total devido não é apresentado como certo. */
    val dueGross: Money?,
    val balance: ClientBalance?,
    val received: Money,
    val result: JobResult?,
) {
    val job get() = row.job
    val minutes get() = entries.sumOf { it.minutes }

    companion object {
        fun of(row: JobRow, entries: List<TimeEntryEntity>, cash: List<CashMovementEntity>): JobSummary {
            val job = row.job
            val facts = entries.map {
                TimeEntryFacts(it.workerId, it.minutes, it.billable, HourlyRate(it.saleRateCents), HourlyRate(it.costRateCents))
            }
            // Preço fixo sem valor aprovado = venda desconhecida (não é zero).
            val saleNet = if (job.billingMode == BillingMode.FIXED_PRICE && job.fixedPriceNetCents == null) {
                null
            } else {
                SaleCalculator.saleValue(
                    SaleInputs(
                        mode = job.billingMode,
                        materialsPolicy = job.materialsPolicy,
                        approvedFixedPrice = Money(job.fixedPriceNetCents ?: 0),
                        timeEntries = facts,
                    ),
                )
            }
            val dueGross = if (saleNet != null && job.vatRateBp != null) Vat.gross(saleNet, job.vatRateBp) else null
            val receipts = cash.filter { it.kind == CashKind.CLIENT_RECEIPT }.map { Money(it.amountCents) }
            val refunds = cash.filter { it.kind == CashKind.CLIENT_REFUND }.map { Money(it.amountCents) }
            val balance = dueGross?.let { ClientBalance(it, receipts, refunds) }
            val received = receipts.sum() - refunds.sum()
            // Despesas ainda não existem nesta versão do esquema: custos = 0 é sinalizado na interface.
            val result = saleNet?.let { JobResult.of(it, Money.ZERO, facts) }
            return JobSummary(row, entries, saleNet, dueGross, balance, received, result)
        }
    }
}

object Settings {
    const val OPENING_BALANCE_CENTS = "opening_balance_cents"
    const val DEFAULT_VAT_BP = "default_vat_bp"
    const val TRAVEL_MARGIN_MINUTES = "travel_margin_minutes"

    fun toMap(list: List<SettingEntity>) = list.associate { it.key to it.value }
}
