package pt.obraemdia.domain.billing

import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.sum
import pt.obraemdia.domain.money.sumMoney
import java.math.BigDecimal
import java.math.RoundingMode

enum class BillingMode { FIXED_PRICE, HOURLY }

enum class MaterialsPolicy { INCLUDED, BILLED_SEPARATELY, SUPPLIED_BY_CLIENT }

/** Registo de horas com as taxas históricas guardadas no momento do registo. */
data class TimeEntryFacts(
    val workerId: String,
    val minutes: Int,
    val billable: Boolean,
    val saleRate: HourlyRate,
    val costRate: HourlyRate,
) {
    val saleAmount get() = if (billable) saleRate.amountFor(minutes) else Money.ZERO
    val internalCost get() = costRate.amountFor(minutes)
}

/**
 * Componentes de venda de uma obra. Todos os valores são SEM IVA repercutido;
 * o IVA é tratado à parte na apresentação ao cliente.
 * [extrasNotIncluded] são os extras aprovados que ainda não fazem parte do preço/horas.
 */
data class SaleInputs(
    val mode: BillingMode,
    val materialsPolicy: MaterialsPolicy,
    val approvedFixedPrice: Money = Money.ZERO,
    val extrasNotIncluded: List<Money> = emptyList(),
    val materialsBilledToClient: List<Money> = emptyList(),
    val timeEntries: List<TimeEntryFacts> = emptyList(),
)

object SaleCalculator {

    /**
     * Valor de venda sem IVA.
     * Preço fixo: preço + extras + materiais cobrados à parte. As horas NÃO entram (servem para custos).
     * À hora: horas faturáveis × taxa histórica de cada registo + materiais cobrados à parte + extras.
     */
    fun saleValue(input: SaleInputs): Money {
        val materials = if (input.materialsPolicy == MaterialsPolicy.BILLED_SEPARATELY) {
            input.materialsBilledToClient.sum()
        } else {
            Money.ZERO
        }
        val base = when (input.mode) {
            BillingMode.FIXED_PRICE -> input.approvedFixedPrice
            BillingMode.HOURLY -> input.timeEntries.sumMoney { it.saleAmount }
        }
        return base + input.extrasNotIncluded.sum() + materials
    }
}

/**
 * Resultado de gestão de uma obra, antes de impostos sobre o rendimento.
 * [costsNetOfRecoverableVat]: materiais e outras despesas, incluindo IVA não recuperável
 * e custos conhecidos ainda não pagos.
 */
data class JobResult(val saleNet: Money, val costsNetOfRecoverableVat: Money, val internalLaborCost: Money) {

    val result: Money get() = saleNet - costsNetOfRecoverableVat - internalLaborCost

    /** Margem em %, com uma casa decimal; null quando a venda não é positiva. */
    val marginPercent: BigDecimal?
        get() = if (!saleNet.isPositive) null else
            result.toBigDecimal().multiply(BigDecimal(100)).divide(saleNet.toBigDecimal(), 1, RoundingMode.HALF_UP)

    companion object {
        fun of(saleNet: Money, costs: Money, timeEntries: List<TimeEntryFacts>) =
            JobResult(saleNet, costs, timeEntries.sumMoney { it.internalCost })
    }
}
