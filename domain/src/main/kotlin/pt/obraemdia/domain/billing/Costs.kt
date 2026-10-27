package pt.obraemdia.domain.billing

import pt.obraemdia.domain.money.DocumentAmounts
import pt.obraemdia.domain.money.Money

/** Se o IVA pago a fornecedores pode ser recuperado. Nunca é assumido (spec §8.1). */
enum class VatTreatment { RECOVERABLE, NOT_RECOVERABLE, UNKNOWN }

enum class DocumentType(val createsExpense: Boolean, val sign: Int) {
    INVOICE(true, 1),
    INVOICE_RECEIPT(true, 1),
    SIMPLIFIED_INVOICE(true, 1),
    /** Nota de crédito: reduz custos; não pressupõe que o dinheiro já foi devolvido. */
    CREDIT_NOTE(true, -1),
    /** Recibo de uma fatura já registada: prova pagamento, não é uma segunda despesa. */
    RECEIPT(false, 1),
    /** Guias e pró-formas não criam despesa. */
    DELIVERY_NOTE(false, 1),
    PRO_FORMA(false, 1),
    OTHER(true, 1),
}

object Costs {

    /**
     * Custo de gestão de uma parcela: base sem IVA quando o IVA é recuperável; caso contrário
     * (não recuperável ou por classificar) o IVA integra o custo. Por classificar é sinalizado à parte.
     */
    fun managementCost(line: DocumentAmounts, vat: VatTreatment): Money = when (vat) {
        VatTreatment.RECOVERABLE -> line.net
        VatTreatment.NOT_RECOVERABLE, VatTreatment.UNKNOWN -> line.gross
    }

    /**
     * Valores com sinal do documento: nota de crédito fica negativa. Os valores introduzidos
     * são sempre positivos (como aparecem no papel); o sinal vem do tipo.
     */
    fun signed(type: DocumentType, amounts: DocumentAmounts): DocumentAmounts =
        if (type.sign < 0) DocumentAmounts(-amounts.net, -amounts.vat, -amounts.gross) else amounts
}

/** Validação do NIF português (9 dígitos, dígito de controlo módulo 11). */
object Nif {
    fun isValid(nif: String): Boolean {
        val digits = nif.filter { !it.isWhitespace() }
        if (digits.length != 9 || !digits.all(Char::isDigit)) return false
        val sum = (0 until 8).sumOf { (digits[it] - '0') * (9 - it) }
        val check = 11 - sum % 11
        return (digits[8] - '0') == if (check >= 10) 0 else check
    }
}
