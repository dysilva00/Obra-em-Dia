package pt.obraemdia.domain.cash

import pt.obraemdia.domain.billing.Installment
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.sum
import pt.obraemdia.domain.money.sumMoney
import java.time.LocalDate
import java.time.YearMonth

enum class Scenario { CONFIRMED, PROPOSAL }

/**
 * Entrada (+) ou saída (−) de caixa prevista e ainda em aberto. [date] nulo = sem data conhecida
 * (não entra nos horizontes e é sinalizada). [assumption] explica qualquer hipótese usada.
 */
data class ForecastItem(
    val id: String,
    val label: String,
    val amount: Money,
    val date: LocalDate?,
    val scenario: Scenario = Scenario.CONFIRMED,
    val assumption: String? = null,
    val overdue: Boolean = false,
)

data class MonthFlow(val month: YearMonth, val inflow: Money, val outflow: Money) {
    val net get() = inflow - outflow
}

data class ForecastResult(
    val currentBalance: Money,
    val horizonEnd: LocalDate,
    val included: List<ForecastItem>,
    val inflow: Money,
    val outflow: Money,
    val months: List<MonthFlow>,
    /** Itens sem data: não estão no saldo previsto. */
    val undated: List<ForecastItem>,
    /** Valores desconhecidos (ex.: obra sem preço): a previsão está incompleta. */
    val unknowns: List<String>,
    /** Ponto mais baixo do saldo dentro do horizonte, e a data. */
    val lowest: Pair<LocalDate, Money>,
) {
    val endBalance get() = currentBalance + inflow - outflow
    val isIncomplete get() = undated.isNotEmpty() || unknowns.isNotEmpty()
}

object Forecast {

    /**
     * Projeta a caixa de [today] até [today] + [horizonDays].
     * Itens vencidos e não pagos contam como hoje (e ficam marcados). Propostas só entram se [includeProposals].
     * [postponed] simula adiar itens (id → nova data), sem alterar dados.
     */
    fun project(
        today: LocalDate,
        horizonDays: Long,
        currentBalance: Money,
        items: List<ForecastItem>,
        includeProposals: Boolean,
        unknowns: List<String> = emptyList(),
        postponed: Map<String, LocalDate> = emptyMap(),
    ): ForecastResult {
        val end = today.plusDays(horizonDays)
        val scoped = items
            .filter { includeProposals || it.scenario == Scenario.CONFIRMED }
            .map { item ->
                val moved = postponed[item.id]
                when {
                    moved != null -> item.copy(date = moved, assumption = listOfNotNull(item.assumption, "adiamento simulado").joinToString(" · "))
                    item.date != null && item.date < today -> item.copy(date = today, overdue = true)
                    else -> item
                }
            }
        val dated = scoped.filter { it.date != null }
        val included = dated.filter { it.date!! <= end }.sortedBy { it.date }
        val inflow = included.filter { it.amount.isPositive }.sumMoney { it.amount }
        val outflow = -included.filter { it.amount.isNegative }.sumMoney { it.amount }

        val months = included.groupBy { YearMonth.from(it.date!!) }.toSortedMap().map { (m, list) ->
            MonthFlow(m, list.filter { it.amount.isPositive }.sumMoney { it.amount }, -list.filter { it.amount.isNegative }.sumMoney { it.amount })
        }

        var running = currentBalance
        var lowest = today to currentBalance
        included.groupBy { it.date!! }.toSortedMap().forEach { (d, list) ->
            running += list.map { it.amount }.sum()
            if (running < lowest.second) lowest = d to running
        }
        return ForecastResult(
            currentBalance = currentBalance,
            horizonEnd = end,
            included = included,
            inflow = inflow,
            outflow = outflow,
            months = months,
            undated = scoped.filter { it.date == null },
            unknowns = unknowns,
            lowest = lowest,
        )
    }

    /**
     * Recebimentos previstos de uma obra: o que já foi recebido cobre primeiro as prestações mais antigas
     * (um adiantamento não volta a aparecer); o saldo que nenhuma prestação cobre usa [fallbackDate]
     * como hipótese explícita (ex.: último dia marcado da obra) ou fica sem data.
     */
    fun receivables(
        jobId: String,
        label: String,
        balance: Money,
        received: Money,
        installments: List<Installment>,
        fallbackDate: LocalDate?,
        scenario: Scenario,
    ): List<ForecastItem> {
        if (!balance.isPositive) return emptyList()
        var available = received
        val items = mutableListOf<ForecastItem>()
        var planned = Money.ZERO
        for (inst in installments.sortedWith(compareBy({ it.dueDate }, { it.id }))) {
            val covered = minOf(inst.amount, maxOf(available, Money.ZERO))
            available -= covered
            val missing = inst.amount - covered
            if (missing.isPositive) {
                // Nunca prever mais do que o saldo em dívida.
                val amount = minOf(missing, balance - planned)
                if (amount.isPositive) {
                    items += ForecastItem("$jobId:${inst.id}", "$label · prestação", amount, inst.dueDate, scenario)
                    planned += amount
                }
            }
        }
        val rest = balance - planned
        if (rest.isPositive) {
            items += ForecastItem(
                "$jobId:rest",
                label,
                rest,
                fallbackDate,
                scenario,
                assumption = if (fallbackDate != null) "sem data de recebimento: assume o fim da obra" else "sem data de recebimento nem marcações",
            )
        }
        return items
    }
}
