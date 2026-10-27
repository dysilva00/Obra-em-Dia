package pt.obraemdia.app.export

import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.db.ChargeKind
import pt.obraemdia.app.data.repo.Snapshot
import pt.obraemdia.app.ui.formatMinutes
import pt.obraemdia.app.ui.label
import pt.obraemdia.app.ui.shortLabel
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.Costs
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.label
import pt.obraemdia.domain.money.DocumentAmounts
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat
import pt.obraemdia.domain.money.sumMoney
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Bloco de conteúdo de um relatório; o desenho em PDF fica no [PdfWriter]. */
sealed interface Block {
    data class Heading(val text: String) : Block
    data class Line(val label: String, val value: String, val bold: Boolean = false) : Block
    /** Linha de tabela: a primeira coluna ocupa o espaço livre, as restantes alinham à direita. */
    data class Row(val cells: List<String>, val header: Boolean = false) : Block
    data class Text(val text: String, val small: Boolean = false) : Block
    data object Gap : Block
}

data class Report(val fileName: String, val title: String, val subtitle: String, val blocks: List<Block>)

/** Aviso obrigatório: documentos exportados não fingem ser faturas ou recibos fiscais. */
const val NOT_AN_INVOICE = "Documento de gestão, sem valor fiscal. Não é fatura nem recibo."

private val dmy = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private fun LocalDate.dmy() = format(dmy)
private fun safe(name: String) = name.replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-').take(40)

object Reports {

    /** Balanço interno da obra: inclui custos, custo interno e resultado. */
    fun jobBalance(s: Snapshot, jobId: String, today: LocalDate): Report {
        val sum = requireNotNull(s.summary(jobId)) { "Obra não encontrada" }
        val job = sum.job
        val charges = s.charges.filter { it.jobId == jobId && it.deletedAt == null }
        val blocks = mutableListOf<Block>()

        blocks += Block.Heading("Obra")
        blocks += Block.Line("Cliente", sum.row.clientName ?: "Sem cliente")
        sum.row.siteAddress?.let { blocks += Block.Line("Local", it) }
        blocks += Block.Line("Estado", job.status.label + if (s.isClosed(jobId)) " · encerrada" else "")
        blocks += Block.Line("Cobrança", if (job.billingMode == BillingMode.FIXED_PRICE) "Preço fixo" else "À hora")
        blocks += Block.Line("Materiais", when (job.materialsPolicy) {
            MaterialsPolicy.INCLUDED -> "Incluídos no preço"
            MaterialsPolicy.BILLED_SEPARATELY -> "Cobrados à parte"
            MaterialsPolicy.SUPPLIED_BY_CLIENT -> "Fornecidos pelo cliente"
        })

        blocks += Block.Heading("Valor da obra (sem IVA)")
        if (job.billingMode == BillingMode.FIXED_PRICE) blocks += Block.Line("Preço acordado", job.fixedPriceNetCents?.let { Money(it).format() } ?: "Por definir")
        charges.forEach { c ->
            val tag = if (c.kind == ChargeKind.EXTRA) "Extra" else "Material"
            val note = if (c.includedInPrice) " (incluído no preço)" else ""
            blocks += Block.Line("$tag: ${c.description}$note", Money(c.amountNetCents).format())
        }
        blocks += Block.Line("Valor de venda sem IVA", sum.saleNet?.format() ?: "Por definir", bold = true)
        blocks += Block.Line("IVA", job.vatRateBp?.let { Vat.label(it) } ?: "Por definir")
        blocks += Block.Line("Total devido com IVA", sum.dueGross?.format() ?: "Por definir", bold = true)

        blocks += Block.Heading("Recebimentos")
        val cash = s.movements.filter { it.jobId == jobId && (it.kind == CashKind.CLIENT_RECEIPT || it.kind == CashKind.CLIENT_REFUND) }
        if (cash.isEmpty()) blocks += Block.Text("Sem recebimentos registados.")
        cash.sortedBy { it.date }.forEach { m ->
            val what = (if (m.isReversal) "Estorno · " else "") + if (m.kind == CashKind.CLIENT_REFUND) "Devolução" else "Recebimento"
            blocks += Block.Row(listOf("${m.date.dmy()} · $what", Money(m.signedCents).format()))
        }
        blocks += Block.Line("Recebido (líquido)", sum.received.format(), bold = true)
        blocks += Block.Line(
            if (sum.balance?.balance?.isNegative == true) "A regularizar com o cliente" else "Falta receber",
            sum.balance?.balance?.format() ?: "Por definir",
            bold = true,
        )

        blocks += Block.Heading("Horas")
        blocks += Block.Row(listOf("Pessoa", "Horas", "Custo interno"), header = true)
        s.workers.forEach { w ->
            val mine = sum.entries.filter { it.workerId == w.id }
            if (mine.isNotEmpty()) {
                val cost = mine.sumMoney { HourlyRate(it.costRateCents).amountFor(it.minutes) }
                blocks += Block.Row(listOf(w.name, formatMinutes(mine.sumOf { it.minutes }), cost.format()))
            }
        }
        blocks += Block.Line("Total horas-pessoa", formatMinutes(sum.minutes), bold = true)

        blocks += Block.Heading("Materiais e despesas")
        val docs = s.purchases.associateBy { it.id }
        val lines = s.allocations.filter { it.jobId == jobId }.mapNotNull { a -> docs[a.documentId]?.let { a to it } }
        if (lines.isEmpty()) blocks += Block.Text("Sem despesas registadas.")
        lines.sortedBy { it.second.docDate }.forEach { (a, d) ->
            val cost = Costs.managementCost(DocumentAmounts(Money(a.netCents), Money(a.vatCents), Money(a.grossCents)), d.vatTreatment)
            blocks += Block.Row(listOf("${d.docDate.dmy()} · ${d.supplierName ?: "Fornecedor"}", cost.format()))
        }
        blocks += Block.Line("Total de custos", sum.costs.format(), bold = true)
        if (sum.costsWithUnknownVat) blocks += Block.Text("Há despesas com IVA por classificar: o IVA foi contado como custo.", small = true)

        blocks += Block.Heading("Resultado de gestão")
        val r = sum.result
        if (r == null) {
            blocks += Block.Text("Sem valor de venda definido: resultado por calcular.")
        } else {
            blocks += Block.Line("Venda sem IVA", r.saleNet.format())
            blocks += Block.Line("Materiais e despesas", "−${r.costsNetOfRecoverableVat.format()}")
            blocks += Block.Line("Custo interno de mão de obra", "−${r.internalLaborCost.format()}")
            blocks += Block.Line("Resultado", r.result.format(), bold = true)
            blocks += Block.Line("Margem", r.marginPercent?.let { it.toPlainString().replace('.', ',') + " %" } ?: "—")
            blocks += Block.Text("Antes de impostos sobre o rendimento e encargos não registados.", small = true)
        }
        s.closureOf(jobId)?.let { c ->
            blocks += Block.Heading("Encerramento")
            blocks += Block.Line("Revisão", c.revision.toString())
            if (c.reopenedAt != null) blocks += Block.Text("Reaberta: ${c.reopenReason}")
            c.notes?.let { blocks += Block.Text("Notas: $it") }
        }
        return Report(
            fileName = "balanco-${safe(job.title)}-${today}.pdf",
            title = job.title,
            subtitle = "Balanço da obra · uso interno · ${today.dmy()}",
            blocks = blocks,
        )
    }

    /** Resumo para o cliente: sem custos, custo interno, resultado nem dados de outras obras. */
    fun clientSummary(s: Snapshot, jobId: String, today: LocalDate): Report {
        val sum = requireNotNull(s.summary(jobId)) { "Obra não encontrada" }
        val job = sum.job
        val blocks = mutableListOf<Block>()
        sum.row.clientName?.let { blocks += Block.Line("Cliente", it) }
        sum.row.siteAddress?.let { blocks += Block.Line("Local", it) }

        blocks += Block.Heading("Trabalho")
        if (job.billingMode == BillingMode.FIXED_PRICE) {
            blocks += Block.Line("Preço acordado (sem IVA)", job.fixedPriceNetCents?.let { Money(it).format() } ?: "Por definir")
        } else {
            // À hora: o cliente vê as horas faturáveis e o preço cobrado, nunca o custo interno.
            blocks += Block.Row(listOf("Data", "Horas", "Valor"), header = true)
            sum.entries.filter { it.billable }.groupBy { it.date }.toSortedMap().forEach { (d, list) ->
                val value = list.sumMoney { HourlyRate(it.saleRateCents).amountFor(it.minutes) }
                blocks += Block.Row(listOf(d.dmy(), formatMinutes(list.sumOf { it.minutes }), value.format()))
            }
        }
        s.charges.filter { it.jobId == jobId && it.deletedAt == null && !it.includedInPrice }
            .filter { it.kind == ChargeKind.EXTRA || job.materialsPolicy == MaterialsPolicy.BILLED_SEPARATELY }
            .forEach { c ->
                val tag = if (c.kind == ChargeKind.EXTRA) "Extra aprovado a ${c.approvedOn.dmy()}" else "Material"
                blocks += Block.Line("$tag: ${c.description}", Money(c.amountNetCents).format())
            }
        blocks += Block.Line("Total sem IVA", sum.saleNet?.format() ?: "Por definir", bold = true)
        blocks += Block.Line("IVA ${job.vatRateBp?.let { Vat.label(it) } ?: ""}".trim(), sum.dueGross?.let { d -> sum.saleNet?.let { (d - it).format() } } ?: "Por definir")
        blocks += Block.Line("Total com IVA", sum.dueGross?.format() ?: "Por definir", bold = true)

        blocks += Block.Heading("Pagamentos recebidos")
        val cash = s.movements.filter { it.jobId == jobId && (it.kind == CashKind.CLIENT_RECEIPT || it.kind == CashKind.CLIENT_REFUND) }
        if (cash.isEmpty()) blocks += Block.Text("Ainda sem pagamentos.")
        cash.sortedBy { it.date }.forEach { m ->
            val what = if (m.isReversal) "Correção" else if (m.kind == CashKind.CLIENT_REFUND) "Devolução" else "Pagamento"
            blocks += Block.Row(listOf("${m.date.dmy()} · $what", Money(m.signedCents).format()))
        }
        blocks += Block.Line("Total pago", sum.received.format(), bold = true)
        val balance = sum.balance?.balance
        blocks += Block.Line(
            when {
                balance == null -> "Valor em falta"
                balance.isNegative -> "A devolver"
                else -> "Valor em falta"
            },
            balance?.let { if (it.isNegative) (-it).format() else it.format() } ?: "Por definir",
            bold = true,
        )

        // Prestações ainda por pagar (o recebido cobre primeiro as mais antigas).
        var available = sum.received
        val open = s.installmentsOf(jobId).mapNotNull { i ->
            val amount = Money(i.amountGrossCents)
            val covered = minOf(amount, maxOf(available, Money.ZERO))
            available -= covered
            (amount - covered).takeIf { it.isPositive }?.let { i.dueDate to it }
        }
        if (open.isNotEmpty()) {
            blocks += Block.Heading("Próximos pagamentos")
            open.forEach { (d, m) -> blocks += Block.Row(listOf(d.dmy(), m.format())) }
        }
        return Report(
            fileName = "resumo-${safe(job.title)}-${today}.pdf",
            title = job.title,
            subtitle = "Resumo para o cliente · ${today.dmy()}",
            blocks = blocks,
        )
    }

    /** Relatório mensal: movimentos efetivos e horas por pessoa e por obra. */
    fun monthly(s: Snapshot, month: YearMonth, today: LocalDate): Report {
        val blocks = mutableListOf<Block>()
        val movements = s.movementsIn(month).sortedBy { it.date }
        val titles = s.jobs.associate { it.job.id to it.job.title }
        val received = s.monthReceived(month)
        val paid = s.monthPaid(month)

        blocks += Block.Heading("Resumo")
        blocks += Block.Line("Recebido", received.format())
        blocks += Block.Line("Pago", paid.format())
        blocks += Block.Line("Saldo do mês", (received - paid).format(), bold = true)
        blocks += Block.Line("Saldo acumulado no fim do mês", s.accumulatedBalance(month.atEndOfMonth()).format())
        blocks += Block.Text("Saldo dos movimentos registados na app; não é o saldo bancário.", small = true)

        blocks += Block.Heading("Movimentos")
        if (movements.isEmpty()) blocks += Block.Text("Sem movimentos neste mês.")
        blocks += Block.Row(listOf("Data · descrição", "Valor"), header = true)
        movements.forEach { m ->
            val kind = when (m.kind) {
                CashKind.CLIENT_RECEIPT -> "Recebimento"
                CashKind.CLIENT_REFUND -> "Devolução"
                CashKind.SUPPLIER_PAYMENT -> "Pagamento"
                CashKind.CAPITAL_IN -> "Entrada de capital"
                CashKind.WITHDRAWAL -> "Retirada pessoal"
                CashKind.ADJUSTMENT_IN, CashKind.ADJUSTMENT_OUT -> "Ajuste"
            }
            // Pagamentos a fornecedores mostram o fornecedor; o resto mostra a obra.
            val where = (if (m.kind == CashKind.SUPPLIER_PAYMENT) m.counterparty else null) ?: m.jobId?.let(titles::get) ?: m.counterparty ?: "Geral"
            blocks += Block.Row(listOf("${m.date.shortLabel()} · ${if (m.isReversal) "Estorno · " else ""}$kind · $where", Money(m.signedCents).format()))
        }

        blocks += Block.Heading("Horas")
        val entries = s.timeEntries.filter { YearMonth.from(it.date) == month }
        if (entries.isEmpty()) blocks += Block.Text("Sem horas registadas neste mês.")
        s.workers.forEach { w ->
            val mine = entries.filter { it.workerId == w.id }
            if (mine.isEmpty()) return@forEach
            blocks += Block.Row(listOf(w.name, formatMinutes(mine.sumOf { it.minutes })), header = true)
            mine.groupBy { it.jobId }.forEach { (jobId, list) ->
                blocks += Block.Row(listOf("   ${titles[jobId] ?: "?"}", formatMinutes(list.sumOf { it.minutes })))
            }
        }

        blocks += Block.Heading("Posição em ${today.dmy()}")
        blocks += Block.Line("Por receber de clientes", s.summaries.mapNotNull { it.balance?.balance }.filter { it.isPositive }.fold(Money.ZERO, Money::plus).format())
        blocks += Block.Line("Por pagar a fornecedores", s.toPaySuppliers.format())
        return Report(
            fileName = "mes-$month.pdf",
            title = "Relatório de ${month.label()}",
            subtitle = "Gerado a ${today.dmy()}",
            blocks = blocks,
        )
    }
}
