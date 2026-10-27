package pt.obraemdia.domain.fiscalqr

import pt.obraemdia.domain.money.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Código QR dos documentos fiscais portugueses, segundo as Especificações Técnicas da AT,
 * versão 1.1 (outubro de 2020, Portaria n.º 195/2020). Verificada em 27/10/2026.
 *
 * Formato: campos "código:valor" separados por "*"; montantes em euros com "." decimal e 2 casas.
 * A leitura é um registo interno: não autentica o documento nem comunica nada à AT.
 */
data class AtQrDocument(
    /** A — NIF do emitente (sem prefixo de país). */
    val issuerNif: String,
    /** B — NIF do adquirente; 999999990 = consumidor final. */
    val buyerNif: String,
    /** C — país do adquirente. */
    val buyerCountry: String,
    /** D — tipo (FT, FS, FR, NC, ND, PF, GT, ...). */
    val docType: String,
    /** E — estado (N normal, A anulado, ...). */
    val status: String,
    /** F — data do documento. */
    val date: LocalDate,
    /** G — identificação única (ex.: "FT AB2019/0035"). */
    val docNumber: String,
    /** H — ATCUD (pode ser "0" em documentos antigos). */
    val atcud: String?,
    /** Bases e IVA por espaço fiscal (I, J, K), já somados. */
    val taxableBase: Money,
    val vat: Money,
    /** L — não sujeito / não tributável / outras situações. */
    val notSubject: Money,
    /** M — imposto do selo. */
    val stampDuty: Money,
    /** N — total de impostos (IVA + selo). */
    val totalTaxes: Money,
    /** O — total do documento com impostos. */
    val gross: Money,
    /** P — retenções na fonte. */
    val withholding: Money,
    /** R — n.º do certificado do programa de faturação. */
    val certificate: String?,
    /** Conteúdo original, guardado para auditoria. */
    val raw: String,
) {
    /** Valor sem impostos: total − impostos (inclui bases isentas e não sujeitas). */
    val net: Money get() = gross - totalTaxes

    val isCancelled get() = status.equals("A", ignoreCase = true)
    val isFinalConsumer get() = buyerNif == FINAL_CONSUMER

    /** Tipos que representam vendas/prestações faturadas (contam para o valor faturado). */
    val isInvoice get() = docType in INVOICE_TYPES
    val isCreditNote get() = docType == "NC"
    val isDebitNote get() = docType == "ND"

    companion object {
        const val FINAL_CONSUMER = "999999990"
        val INVOICE_TYPES = setOf("FT", "FS", "FR")
    }
}

sealed interface AtQrResult {
    data class Ok(val document: AtQrDocument) : AtQrResult
    /** [message] em português, para mostrar ao utilizador com alternativa manual. */
    data class Invalid(val message: String) : AtQrResult
}

object AtQrParser {

    private val date = DateTimeFormatter.BASIC_ISO_DATE // YYYYMMDD
    private val required = listOf("A", "B", "C", "D", "E", "F", "G", "H", "N", "O")
    private val baseFields = listOf("2", "3", "5", "7")
    private val vatFields = listOf("4", "6", "8")

    fun parse(text: String): AtQrResult {
        val raw = text.trim()
        if (!raw.startsWith("A:")) return AtQrResult.Invalid("Este código QR não é de uma fatura portuguesa.")
        val fields = mutableMapOf<String, String>()
        for (part in raw.split('*')) {
            val sep = part.indexOf(':')
            if (sep <= 0) continue
            fields[part.substring(0, sep).trim()] = part.substring(sep + 1).trim()
        }
        val missing = required.filter { fields[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) return AtQrResult.Invalid("Código QR incompleto (faltam os campos ${missing.joinToString(", ")}).")

        return try {
            fun money(code: String): Money = fields[code]?.let { parseAmount(it) ?: throw BadAmount(code) } ?: Money.ZERO
            val regions = listOf("I", "J", "K")
            val base = regions.flatMap { r -> baseFields.map { "$r$it" } }.fold(Money.ZERO) { acc, c -> acc + money(c) }
            val vat = regions.flatMap { r -> vatFields.map { "$r$it" } }.fold(Money.ZERO) { acc, c -> acc + money(c) }
            val doc = AtQrDocument(
                issuerNif = fields.getValue("A"),
                buyerNif = fields.getValue("B"),
                buyerCountry = fields.getValue("C"),
                docType = fields.getValue("D").uppercase(),
                status = fields.getValue("E").uppercase(),
                date = runCatching { LocalDate.parse(fields.getValue("F"), date) }.getOrNull()
                    ?: return AtQrResult.Invalid("Data do documento inválida (${fields["F"]})."),
                docNumber = fields.getValue("G"),
                atcud = fields["H"]?.takeIf { it != "0" },
                taxableBase = base,
                vat = vat,
                notSubject = money("L"),
                stampDuty = money("M"),
                totalTaxes = money("N"),
                gross = money("O"),
                withholding = money("P"),
                certificate = fields["R"],
                raw = raw,
            )
            AtQrResult.Ok(doc)
        } catch (e: BadAmount) {
            AtQrResult.Invalid("Valor inválido no campo ${e.code}.")
        }
    }

    /** "1234.50" → 1234,50 €. Exige ponto decimal e 2 casas, como a especificação define. */
    private fun parseAmount(s: String): Money? {
        if (!Regex("""-?\d+\.\d{2}""").matches(s)) return null
        return Money.of(BigDecimal(s))
    }

    private class BadAmount(val code: String) : Exception()
}
