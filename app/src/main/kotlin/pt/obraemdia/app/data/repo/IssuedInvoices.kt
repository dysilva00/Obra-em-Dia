package pt.obraemdia.app.data.repo

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.AttachmentEntity
import pt.obraemdia.app.data.db.AttachmentKind
import pt.obraemdia.app.data.db.AuditEventEntity
import pt.obraemdia.app.data.db.InvoiceSource
import pt.obraemdia.app.data.db.IssuedInvoiceEntity
import pt.obraemdia.app.data.files.AttachmentStore
import pt.obraemdia.domain.billing.Nif
import pt.obraemdia.domain.fiscalqr.AtQrDocument
import pt.obraemdia.domain.money.Money
import java.time.LocalDate

/** Dados de uma fatura emitida, vindos do QR (câmara ou PDF) ou introduzidos à mão. Valores positivos. */
data class IssuedInvoiceInput(
    val operationId: String,
    val issuerNif: String,
    val buyerNif: String?,
    val buyerName: String?,
    val docType: String,
    val docNumber: String,
    val atcud: String?,
    val issueDate: LocalDate,
    val net: Money,
    val vat: Money,
    val gross: Money,
    val withholding: Money,
    val jobId: String?,
    val source: InvoiceSource,
    val rawQr: String?,
    /** PDF a guardar como anexo (copiado para a pasta privada). */
    val pdf: Uri?,
    val note: String?,
) {
    companion object {
        fun fromQr(operationId: String, d: AtQrDocument, source: InvoiceSource, pdf: Uri?) = IssuedInvoiceInput(
            operationId = operationId,
            issuerNif = d.issuerNif,
            buyerNif = d.buyerNif.takeIf { !d.isFinalConsumer },
            buyerName = null,
            docType = d.docType,
            docNumber = d.docNumber,
            atcud = d.atcud,
            issueDate = d.date,
            net = d.net,
            vat = d.totalTaxes,
            gross = d.gross,
            withholding = d.withholding,
            jobId = null,
            source = source,
            rawQr = d.raw,
            pdf = pdf,
            note = null,
        )
    }
}

sealed interface InvoiceOutcome {
    data object Saved : InvoiceOutcome
    data class Duplicate(val existing: IssuedInvoiceEntity) : InvoiceOutcome
    data class Rejected(val message: String) : InvoiceOutcome
}

/** Totais de um ano para um NIF emitente. Notas de crédito já entram a subtrair. */
data class YearTotals(
    val issuerNif: String,
    val year: Int,
    val net: Money,
    val vat: Money,
    val gross: Money,
    val withholding: Money,
    val count: Int,
)

object InvoiceTotals {
    fun byYearAndIssuer(list: List<IssuedInvoiceEntity>, year: Int): List<YearTotals> =
        list.filter { it.issueDate.year == year }.groupBy { it.issuerNif }.map { (nif, items) ->
            YearTotals(
                issuerNif = nif,
                year = year,
                net = Money(items.sumOf { it.netCents }),
                vat = Money(items.sumOf { it.vatCents }),
                gross = Money(items.sumOf { it.grossCents }),
                withholding = Money(items.sumOf { it.withholdingCents }),
                count = items.size,
            )
        }.sortedByDescending { it.net }
}

/**
 * Registo das faturas emitidas aos clientes. Serve para controlar o valor faturado por ano e por NIF;
 * não substitui o Portal das Finanças nem o programa de faturação.
 */
class IssuedInvoices(
    private val db: AppDatabase,
    private val repo: ObraRepository,
    private val store: AttachmentStore,
    private val clock: () -> Long,
) {
    val all: Flow<List<IssuedInvoiceEntity>> = db.invoiceDao().observeAll()

    suspend fun record(input: IssuedInvoiceInput): InvoiceOutcome {
        val type = input.docType.uppercase()
        if (type !in COUNTED_TYPES) {
            return InvoiceOutcome.Rejected("Documentos do tipo $type não são faturas emitidas (só FT, FS, FR, NC e ND contam).")
        }
        val nif = input.issuerNif.filter(Char::isDigit)
        if (!Nif.isValid(nif)) return InvoiceOutcome.Rejected("O NIF do emitente ($nif) não é válido.")
        if (input.docNumber.isBlank()) return InvoiceOutcome.Rejected("Falta o número do documento.")
        if (!input.gross.isPositive && type != "NC") return InvoiceOutcome.Rejected("O total tem de ser positivo.")
        if (input.net + input.vat != input.gross) {
            return InvoiceOutcome.Rejected("Valor sem IVA + impostos tem de ser igual ao total (${input.gross.format()}).")
        }

        // Duplicado antes de copiar o PDF, para não deixar ficheiros órfãos.
        db.invoiceDao().findDuplicate(nif, type, input.docNumber.trim(), input.atcud)?.let { return InvoiceOutcome.Duplicate(it) }

        val stored = input.pdf?.let { withContext(Dispatchers.IO) { store.import(it) } }
        try {
            // Nota de crédito diminui o valor faturado.
            val sign = if (type == "NC") -1 else 1
            return repo.transaction {
                val now = clock()
                val attachmentId = stored?.let { s ->
                    newId().also { id ->
                        db.mediaDao().insertAttachment(
                            AttachmentEntity(
                                id = id, jobId = input.jobId, kind = AttachmentKind.DOCUMENT, relativePath = s.relativePath,
                                mimeType = s.mimeType, sizeBytes = s.sizeBytes, sha256 = s.sha256,
                                caption = "${type} ${input.docNumber}", takenOn = input.issueDate, createdAt = now,
                            ),
                        )
                    }
                }
                val id = newId()
                val row = db.invoiceDao().insert(
                    IssuedInvoiceEntity(
                        id = id,
                        operationId = input.operationId,
                        issuerNif = nif,
                        buyerNif = input.buyerNif?.filter(Char::isDigit)?.takeIf { it.isNotEmpty() },
                        buyerName = input.buyerName?.takeIf { it.isNotBlank() },
                        docType = type,
                        docNumber = input.docNumber.trim(),
                        atcud = input.atcud?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
                        issueDate = input.issueDate,
                        netCents = sign * kotlin.math.abs(input.net.cents),
                        vatCents = sign * kotlin.math.abs(input.vat.cents),
                        grossCents = sign * kotlin.math.abs(input.gross.cents),
                        withholdingCents = sign * kotlin.math.abs(input.withholding.cents),
                        jobId = input.jobId,
                        attachmentId = attachmentId,
                        source = input.source,
                        rawQr = input.rawQr,
                        note = input.note?.takeIf { it.isNotBlank() },
                        createdAt = now,
                    ),
                )
                // Corrida rara (duas gravações em simultâneo): a restrição única protege; desfaz o anexo.
                check(row != -1L) { "Esta fatura já está registada" }
                db.auditDao().insert(AuditEventEntity(newId(), "issued_invoice", id, "create", "${input.docNumber} · ${input.gross.format()}", now))
                InvoiceOutcome.Saved
            }
        } catch (e: Exception) {
            stored?.let { store.delete(it.relativePath) }
            throw e
        }
    }

    /** Anular um registo errado (ex.: fatura anulada no programa de faturação). Fica no histórico. */
    suspend fun cancel(id: String, reason: String) {
        require(reason.isNotBlank()) { "Indique o motivo" }
        repo.transaction {
            val i = requireNotNull(db.invoiceDao().get(id)) { "Fatura não encontrada" }
            db.invoiceDao().update(i.copy(deletedAt = clock(), deleteReason = reason.trim()))
            db.auditDao().insert(AuditEventEntity(newId(), "issued_invoice", id, "cancel", reason, clock()))
        }
    }

    fun attachmentFile(relativePath: String) = store.file(relativePath)

    companion object {
        val COUNTED_TYPES = setOf("FT", "FS", "FR", "NC", "ND")
    }
}
