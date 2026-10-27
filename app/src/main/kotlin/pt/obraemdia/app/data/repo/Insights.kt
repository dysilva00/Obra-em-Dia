package pt.obraemdia.app.data.repo

import pt.obraemdia.domain.billing.ClientBalance
import pt.obraemdia.domain.billing.Installment
import pt.obraemdia.domain.cash.ForecastItem
import pt.obraemdia.domain.cash.Scenario
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.Booking
import pt.obraemdia.domain.schedule.Conflict
import java.time.LocalDate

/** Itens e lacunas para a previsão de caixa, derivados dos registos (nada é guardado). */
data class ForecastInputs(val items: List<ForecastItem>, val unknowns: List<String>, val assumptions: List<String>)

/** Agenda simplificada para as previsões e alertas: reservas e respetivos conflitos. */
data class AgendaFacts(val bookings: List<Pair<Booking, List<Conflict>>>)

object Insights {

    fun forecastInputs(snapshot: Snapshot, agenda: AgendaFacts, today: LocalDate): ForecastInputs {
        val items = mutableListOf<ForecastItem>()
        val unknowns = mutableListOf<String>()
        val lastDay = agenda.bookings.groupBy { it.first.jobId }
            .mapValues { (_, list) -> list.maxOf { it.first.slot.end.atZone(AGENDA_ZONE).toLocalDate() } }

        snapshot.summaries.filter { it.job.status != JobStatus.CANCELLED && !it.job.archived }.forEach { s ->
            val scenario = if (s.job.status == JobStatus.TO_CONFIRM) Scenario.PROPOSAL else Scenario.CONFIRMED
            val balance = s.balance
            when {
                balance == null -> unknowns += "${s.job.title}: preço ou IVA por definir" + if (scenario == Scenario.PROPOSAL) " (proposta)" else ""
                balance.balance.isNegative -> items += ForecastItem(
                    "${s.job.id}:refund", "${s.job.title} · a devolver ao cliente", balance.balance, null, scenario,
                    assumption = "crédito do cliente sem data de regularização",
                )
                else -> items += pt.obraemdia.domain.cash.Forecast.receivables(
                    jobId = s.job.id,
                    label = s.job.title,
                    balance = balance.balance,
                    received = s.received,
                    installments = snapshot.installmentsOf(s.job.id).map { Installment(it.id, it.dueDate, Money(it.amountGrossCents)) },
                    // O fim da obra só serve de hipótese se for hoje ou depois.
                    fallbackDate = lastDay[s.job.id]?.takeIf { !it.isBefore(today) },
                    scenario = scenario,
                )
            }
        }

        snapshot.purchaseStatus.filter { it.outstanding.isPositive }.forEach { st ->
            items += ForecastItem(
                "doc:${st.doc.id}",
                "${st.doc.supplierName ?: "Fornecedor"} · por pagar",
                -st.outstanding,
                st.doc.dueDate ?: today,
                Scenario.CONFIRMED,
                assumption = if (st.doc.dueDate == null) "sem vencimento: considerado imediato" else null,
            )
        }

        val assumptions = listOf(
            "Saldo atual = saldo inicial + movimentos registados (não é o saldo bancário).",
            "Custos futuros ainda não comprados não estão incluídos (não há estimativas registadas).",
            "Recebimentos sem prestação usam o último dia marcado da obra, quando existe.",
        )
        return ForecastInputs(items, unknowns, assumptions)
    }

    fun alerts(snapshot: Snapshot, agenda: AgendaFacts, today: LocalDate): List<Alert> = buildList {
        // Sobreposições futuras na agenda.
        agenda.bookings.filter { (b, c) -> c.isNotEmpty() && !b.slot.end.atZone(AGENDA_ZONE).toLocalDate().isBefore(today) }
            .groupBy { it.first.jobId }
            .forEach { (jobId, list) ->
                val first = list.minOf { it.first.slot.start.atZone(AGENDA_ZONE).toLocalDate() }
                add(Alert(AlertKind.CONFLICT, "Sobreposição na agenda: ${list.first().first.label} (a partir de ${first.dayOfMonth}/${first.monthValue})", jobId))
            }

        snapshot.summaries.filter { it.job.status != JobStatus.CANCELLED }.forEach { s ->
            val balance = s.balance
            // Prestações vencidas e não cobertas pelo recebido.
            if (balance != null) {
                val inst = snapshot.installmentsOf(s.job.id).map { Installment(it.id, it.dueDate, Money(it.amountGrossCents)) }
                ClientBalance(balance.totalDueGross, balance.receipts, balance.refunds).overdueInstallments(inst, today).forEach { (i, missing) ->
                    add(Alert(AlertKind.OVERDUE_RECEIVABLE, "Prestação vencida a ${i.dueDate.dayOfMonth}/${i.dueDate.monthValue}: faltam ${missing.format()} · ${s.job.title}", s.job.id))
                }
            }
            if (s.job.status == JobStatus.ON_HOLD) {
                val review = s.job.holdReviewDate
                if (review != null && !review.isAfter(today)) add(Alert(AlertKind.HOLD_REVIEW, "Rever obra em espera: ${s.job.title}", s.job.id))
            }
            if (balance == null && s.job.status != JobStatus.TO_CONFIRM && !snapshot.isClosed(s.job.id)) {
                add(Alert(AlertKind.MISSING_DATA, "Sem preço ou IVA definido: ${s.job.title}", s.job.id))
            }
        }

        snapshot.purchaseStatus.filter { it.outstanding.isPositive && it.doc.dueDate?.isBefore(today) == true }.forEach { st ->
            add(Alert(AlertKind.OVERDUE_PAYABLE, "Fatura vencida: ${st.doc.supplierName ?: "fornecedor"} · ${st.outstanding.format()}", null))
        }
        snapshot.purchaseStatus.filter { !it.unallocated.isZero }.takeIf { it.isNotEmpty() }?.let {
            add(Alert(AlertKind.MISSING_DATA, "${it.size} compra(s) com valor por classificar (Contas)", null))
        }
    }.sortedBy { it.kind.ordinal }
}

enum class AlertKind { OVERDUE_RECEIVABLE, OVERDUE_PAYABLE, CONFLICT, HOLD_REVIEW, MISSING_DATA }

data class Alert(val kind: AlertKind, val text: String, val jobId: String?)
