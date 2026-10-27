package pt.obraemdia.app.data.repo

import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.db.CashMovementEntity
import pt.obraemdia.app.data.db.CostAllocationEntity
import pt.obraemdia.app.data.db.ChargeKind
import pt.obraemdia.app.data.db.InstallmentEntity
import pt.obraemdia.app.data.db.JobChargeEntity
import pt.obraemdia.app.data.db.JobClosureEntity
import pt.obraemdia.app.data.db.PriceRevisionEntity
import pt.obraemdia.app.data.db.PurchaseDocumentEntity
import pt.obraemdia.app.data.db.JobRow
import pt.obraemdia.app.data.db.SettingEntity
import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.ClientBalance
import pt.obraemdia.domain.billing.Costs
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.billing.JobResult
import pt.obraemdia.domain.billing.SaleCalculator
import pt.obraemdia.domain.billing.SaleInputs
import pt.obraemdia.domain.billing.TimeEntryFacts
import pt.obraemdia.domain.money.DocumentAmounts
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat
import pt.obraemdia.domain.money.sum
import pt.obraemdia.domain.money.sumMoney
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalTime
import java.time.LocalDate
import java.time.YearMonth

/** Fotografia coerente dos dados usados pelos ecrãs de Início, Obras e Contas. */
data class Snapshot(
    val workers: List<WorkerEntity>,
    val jobs: List<JobRow>,
    val timeEntries: List<TimeEntryEntity>,
    val movements: List<CashMovementEntity>,
    val settings: Map<String, String>,
    val purchases: List<PurchaseDocumentEntity> = emptyList(),
    val allocations: List<CostAllocationEntity> = emptyList(),
    val closures: List<JobClosureEntity> = emptyList(),
    val charges: List<JobChargeEntity> = emptyList(),
    val priceRevisions: List<PriceRevisionEntity> = emptyList(),
    val installments: List<InstallmentEntity> = emptyList(),
) {
    fun installmentsOf(jobId: String) = installments.filter { it.jobId == jobId }.sortedBy { it.dueDate }

    /** Ids de movimentos que já foram estornados. */
    val reversedIds: Set<String> by lazy { movements.mapNotNull { it.reversalOfId }.toSet() }

    /** Última revisão de encerramento da obra, se existir. */
    fun closureOf(jobId: String): JobClosureEntity? = closures.filter { it.jobId == jobId }.maxByOrNull { it.revision }

    /** Encerrada = última revisão sem reabertura. */
    fun isClosed(jobId: String): Boolean = closureOf(jobId)?.let { it.reopenedAt == null } == true

    /** Recebido no mês, líquido de estornos. */
    fun monthReceived(month: YearMonth): Money = movementsIn(month).filter { it.kind.inflow }.sumMoney { Money(it.signedCents) }

    /** Pago no mês, líquido de estornos. */
    fun monthPaid(month: YearMonth): Money = -movementsIn(month).filter { !it.kind.inflow }.sumMoney { Money(it.signedCents) }
    val hasDemoData: Boolean
        get() = jobs.any { it.job.isDemo } || movements.any { it.isDemo } || timeEntries.any { it.isDemo } || purchases.any { it.isDemo }

    private val docsById by lazy { purchases.associateBy { it.id } }

    /** Documentos com o que falta pagar ao fornecedor (só faturas; notas de crédito não). */
    val purchaseStatus: List<PurchaseStatus> by lazy {
        // Pagamentos líquidos de estornos (signedCents é negativo num pagamento e positivo no seu estorno).
        val paid = movements.filter { it.purchaseDocumentId != null && it.kind == CashKind.SUPPLIER_PAYMENT }
            .groupBy { it.purchaseDocumentId }
            .mapValues { (_, list) -> -list.sumMoney { Money(it.signedCents) } }
        val allocated = allocations.groupBy { it.documentId }
        purchases.map { doc ->
            val lines = allocated[doc.id].orEmpty()
            PurchaseStatus(
                doc = doc,
                allocations = lines,
                paid = paid[doc.id] ?: Money.ZERO,
                unallocated = Money(doc.grossCents) - lines.sumMoney { Money(it.grossCents) },
            )
        }
    }

    val toPaySuppliers: Money get() = purchaseStatus.sumMoney { it.outstanding }

    val workHours: WorkHours get() = Settings.workHours(settings)

    val openingBalance: Money get() = Money(settings[Settings.OPENING_BALANCE_CENTS]?.toLongOrNull() ?: 0)

    fun workerName(id: String) = workers.firstOrNull { it.id == id }?.name ?: "?"

    val summaries: List<JobSummary> by lazy {
        val entriesByJob = timeEntries.groupBy { it.jobId }
        val cashByJob = movements.filter { it.jobId != null }.groupBy { it.jobId }
        val costsByJob = allocations.filter { it.jobId != null }.groupBy { it.jobId }
        val chargesByJob = charges.filter { it.deletedAt == null }.groupBy { it.jobId }
        jobs.map { row ->
            val costLines = costsByJob[row.job.id].orEmpty().mapNotNull { a -> docsById[a.documentId]?.let { a to it } }
            JobSummary.of(row, entriesByJob[row.job.id].orEmpty(), cashByJob[row.job.id].orEmpty(), costLines, chargesByJob[row.job.id].orEmpty())
        }
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
    val costs: Money = Money.ZERO,
    /** Há despesas com IVA por classificar: o resultado inclui esse IVA como custo e é sinalizado. */
    val costsWithUnknownVat: Boolean = false,
    val costDocuments: Int = 0,
) {
    val job get() = row.job
    val minutes get() = entries.sumOf { it.minutes }

    companion object {
        fun of(
            row: JobRow,
            entries: List<TimeEntryEntity>,
            cash: List<CashMovementEntity>,
            costLines: List<Pair<CostAllocationEntity, PurchaseDocumentEntity>> = emptyList(),
            /** Extras e materiais ativos (não anulados). */
            charges: List<JobChargeEntity> = emptyList(),
        ): JobSummary {
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
                        // Extras e materiais já incluídos no preço não somam outra vez.
                        extrasNotIncluded = charges.filter { it.kind == ChargeKind.EXTRA && !it.includedInPrice }.map { Money(it.amountNetCents) },
                        materialsBilledToClient = charges.filter { it.kind == ChargeKind.MATERIAL && !it.includedInPrice }.map { Money(it.amountNetCents) },
                        timeEntries = facts,
                    ),
                )
            }
            val dueGross = if (saleNet != null && job.vatRateBp != null) Vat.gross(saleNet, job.vatRateBp) else null
            // Um estorno de recebimento conta como devolução, e vice-versa: o saldo fica sempre certo.
            val receipts = cash.filter { (it.kind == CashKind.CLIENT_RECEIPT && !it.isReversal) || (it.kind == CashKind.CLIENT_REFUND && it.isReversal) }
                .map { Money(it.amountCents) }
            val refunds = cash.filter { (it.kind == CashKind.CLIENT_REFUND && !it.isReversal) || (it.kind == CashKind.CLIENT_RECEIPT && it.isReversal) }
                .map { Money(it.amountCents) }
            val balance = dueGross?.let { ClientBalance(it, receipts, refunds) }
            val received = receipts.sum() - refunds.sum()
            // Custos conhecidos entram no resultado mesmo que ainda não estejam pagos.
            val costs = costLines.sumMoney { (a, doc) ->
                Costs.managementCost(DocumentAmounts(Money(a.netCents), Money(a.vatCents), Money(a.grossCents)), doc.vatTreatment)
            }
            val unknownVat = costLines.any { it.second.vatTreatment == VatTreatment.UNKNOWN }
            val result = saleNet?.let { JobResult.of(it, costs, facts) }
            return JobSummary(
                row, entries, saleNet, dueGross, balance, received, result,
                costs, unknownVat, costLines.map { it.second.id }.distinct().size,
            )
        }
    }
}

object Settings {
    const val OPENING_BALANCE_CENTS = "opening_balance_cents"
    const val DEFAULT_VAT_BP = "default_vat_bp"
    const val TRAVEL_MARGIN_MINUTES = "travel_margin_minutes"
    const val MORNING_START = "morning_start"
    const val MORNING_END = "morning_end"
    const val AFTERNOON_START = "afternoon_start"
    const val AFTERNOON_END = "afternoon_end"

    /** Horário configurado; valores em falta ou inválidos usam o horário provisório. */
    fun workHours(map: Map<String, String>): WorkHours {
        val d = WorkHours()
        fun t(key: String, default: LocalTime) = map[key]?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: default
        return runCatching {
            WorkHours(t(MORNING_START, d.morningStart), t(MORNING_END, d.morningEnd), t(AFTERNOON_START, d.afternoonStart), t(AFTERNOON_END, d.afternoonEnd))
        }.getOrDefault(d)
    }

    fun toMap(list: List<SettingEntity>) = list.associate { it.key to it.value }
}

/** Situação de um documento de compra: o que falta pagar e o que falta classificar. */
data class PurchaseStatus(
    val doc: PurchaseDocumentEntity,
    val allocations: List<CostAllocationEntity>,
    val paid: Money,
    /** Parte do total ainda não atribuída a nenhuma obra nem a despesas gerais. */
    val unallocated: Money,
) {
    val gross get() = Money(doc.grossCents)

    /** Notas de crédito não geram dívida a fornecedores. */
    val outstanding: Money get() = if (doc.grossCents > 0) gross - paid else Money.ZERO
}
