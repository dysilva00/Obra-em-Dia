package pt.obraemdia.domain.job

import pt.obraemdia.domain.money.Money

/** Factos necessários para decidir se uma obra pode ser encerrada. */
data class ClosureFacts(
    val status: JobStatus,
    val finalPriceConfirmed: Boolean,
    val extrasReviewed: Boolean,
    val clientBalance: Money,
    val hoursAndCostsReviewed: Boolean,
    val unpaidSupplierAmount: Money,
)

sealed interface ClosureIssue {
    /** Impede o encerramento. */
    data class Blocker(val message: String) : ClosureIssue

    /** Não impede, mas tem de ser mostrado na revisão. */
    data class Warning(val message: String) : ClosureIssue
}

object ClosureCheck {

    fun issues(facts: ClosureFacts): List<ClosureIssue> = buildList {
        if (facts.status != JobStatus.COMPLETED) add(ClosureIssue.Blocker("O trabalho ainda não está concluído"))
        if (!facts.finalPriceConfirmed) add(ClosureIssue.Blocker("Confirme o preço final"))
        if (!facts.extrasReviewed) add(ClosureIssue.Blocker("Reveja os extras"))
        if (facts.clientBalance.isPositive) {
            add(ClosureIssue.Blocker("Falta receber ${facts.clientBalance.format()} do cliente"))
        }
        if (facts.clientBalance.isNegative) {
            add(ClosureIssue.Blocker("Há ${(-facts.clientBalance).format()} a devolver ou regularizar com o cliente"))
        }
        if (!facts.hoursAndCostsReviewed) add(ClosureIssue.Blocker("Reveja as horas e as despesas"))
        // Um fornecedor por pagar não impede o fecho: o custo entra no resultado e a dívida continua em contas a pagar.
        if (facts.unpaidSupplierAmount.isPositive) {
            add(ClosureIssue.Warning("Fica por pagar ${facts.unpaidSupplierAmount.format()} a fornecedores"))
        }
    }

    fun canClose(facts: ClosureFacts) = issues(facts).none { it is ClosureIssue.Blocker }
}
