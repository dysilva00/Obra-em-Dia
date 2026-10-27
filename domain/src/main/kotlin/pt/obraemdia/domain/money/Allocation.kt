package pt.obraemdia.domain.money

/**
 * Repartição de um documento de compra por obras/despesas gerais.
 * O documento é contado uma única vez; as parcelas apenas o distribuem.
 */
object Allocation {

    /**
     * Divide [total] proporcionalmente a [weights] pelo método do maior resto.
     * Os cêntimos residuais vão para as parcelas com maior resto; em empate, para a de menor índice.
     * A soma do resultado é sempre exatamente [total].
     */
    fun proportional(total: Money, weights: List<Long>): List<Money> {
        require(weights.isNotEmpty()) { "É preciso pelo menos uma parcela" }
        require(weights.all { it >= 0 }) { "Os pesos não podem ser negativos" }
        val weightSum = weights.sum()
        require(weightSum > 0) { "A soma dos pesos tem de ser positiva" }

        val sign = if (total.cents < 0) -1L else 1L
        val absTotal = Math.absExact(total.cents)
        val base = LongArray(weights.size)
        val remainders = LongArray(weights.size)
        weights.forEachIndexed { i, w ->
            val product = Math.multiplyExact(absTotal, w)
            base[i] = product / weightSum
            remainders[i] = product % weightSum
        }
        var leftover = absTotal - base.sum()
        weights.indices
            .sortedWith(compareByDescending<Int> { remainders[it] }.thenBy { it })
            .forEach { i -> if (leftover > 0) { base[i]++; leftover-- } }
        return base.map { Money(it * sign) }
    }

    /**
     * Reparte um documento (base, IVA, total) segundo os totais com IVA atribuídos a cada parcela.
     * A base é repartida na mesma proporção e o IVA de cada parcela é a diferença,
     * para que base + IVA = total em cada linha e as colunas somem os valores do documento.
     */
    fun splitDocument(document: DocumentAmounts, grossParts: List<Money>): AllocationResult {
        val unallocated = document.gross - grossParts.sum()
        val all = grossParts + unallocated
        // Numa nota de crédito tudo é negativo; parcelas com outro sinal significam que excedem o total.
        require(all.all { it.isZero || it.isNegative == document.gross.isNegative }) {
            "As parcelas excedem o total do documento"
        }
        if (document.gross.isZero) {
            return AllocationResult(grossParts.map { DocumentAmounts(Money.ZERO, Money.ZERO, Money.ZERO) }, unallocated)
        }
        val nets = proportional(document.net, all.map { Math.abs(it.cents) })
        val lines = grossParts.mapIndexed { i, gross -> DocumentAmounts(nets[i], gross - nets[i], gross) }
        return AllocationResult(lines, unallocated)
    }
}

/** Valores de um documento: [net] sem IVA, [vat] IVA, [gross] total com IVA. */
data class DocumentAmounts(val net: Money, val vat: Money, val gross: Money) {
    init {
        require(net + vat == gross) { "Base + IVA tem de ser igual ao total" }
    }
}

/** [unallocated] é a parcela por classificar, que tem de ficar visível na interface. */
data class AllocationResult(val lines: List<DocumentAmounts>, val unallocated: Money) {
    val isComplete get() = unallocated.isZero
}
