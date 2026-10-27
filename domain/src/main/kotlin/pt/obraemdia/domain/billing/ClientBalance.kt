package pt.obraemdia.domain.billing

import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.sum
import java.time.LocalDate

enum class PaymentSituation { NO_PAYMENTS, PARTIAL, SETTLED, TO_REGULARIZE }

data class Installment(val id: String, val dueDate: LocalDate, val amount: Money)

/**
 * Saldo do cliente de uma obra, derivado do valor devido e dos movimentos.
 * [totalDueGross]: total final devido COM impostos, após descontos/créditos.
 * Valores negativos (crédito/excesso) nunca são escondidos.
 */
data class ClientBalance(
    val totalDueGross: Money,
    val receipts: List<Money>,
    val refunds: List<Money> = emptyList(),
) {
    val netReceived: Money get() = receipts.sum() - refunds.sum()

    /** Positivo: por receber. Zero: liquidado. Negativo: crédito a regularizar. */
    val balance: Money get() = totalDueGross - netReceived

    val situation: PaymentSituation
        get() = when {
            balance.isNegative -> PaymentSituation.TO_REGULARIZE
            balance.isZero -> PaymentSituation.SETTLED
            receipts.isEmpty() -> PaymentSituation.NO_PAYMENTS
            else -> PaymentSituation.PARTIAL
        }

    /**
     * Prestações vencidas e ainda não cobertas pelo recebido, alocado por ordem de vencimento.
     * O atraso é um indicador adicional e pode coexistir com [PaymentSituation.PARTIAL].
     */
    fun overdueInstallments(installments: List<Installment>, today: LocalDate): List<Pair<Installment, Money>> {
        var available = netReceived
        val result = mutableListOf<Pair<Installment, Money>>()
        for (inst in installments.sortedWith(compareBy({ it.dueDate }, { it.id }))) {
            val covered = minOf(inst.amount, maxOf(available, Money.ZERO))
            available -= covered
            val missing = inst.amount - covered
            if (missing.isPositive && inst.dueDate < today) result += inst to missing
        }
        return result
    }
}
