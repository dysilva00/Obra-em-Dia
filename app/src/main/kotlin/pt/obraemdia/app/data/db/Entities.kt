package pt.obraemdia.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.DocumentType
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.HoldReason
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.schedule.BookingFirmness
import java.time.LocalDate

/*
 * Convenções do esquema (ver docs/DECISIONS.md):
 * - PK texto com UUID gerado no aparelho; instantes em epoch millis UTC; datas civis em ISO (yyyy-MM-dd).
 * - Montantes em cêntimos (Long). O nome indica se têm IVA quando isso importa.
 * - deletedAt: eliminação lógica nas entidades de negócio. isDemo: dados fictícios, removíveis em bloco.
 * - Movimentos financeiros nunca são apagados: corrigem-se com um movimento de estorno (reversalOfId).
 */

@Entity(tableName = "workers")
data class WorkerEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Taxas habituais: só servem para pré-preencher; cada registo de horas guarda as suas. */
    val defaultSaleRateCents: Long,
    val defaultCostRateCents: Long,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

@Entity(tableName = "clients")
data class ClientEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String? = null,
    val email: String? = null,
    val nif: String? = null,
    val address: String? = null,
    val notes: String? = null,
    /** Chave do contacto Android, se importado. É local ao aparelho e nunca substitui o id próprio. */
    val androidLookupKey: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

@Entity(
    tableName = "work_sites",
    foreignKeys = [ForeignKey(ClientEntity::class, ["id"], ["clientId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("clientId")],
)
data class WorkSiteEntity(
    @PrimaryKey val id: String,
    val clientId: String?,
    val label: String,
    val address: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

@Entity(
    tableName = "jobs",
    foreignKeys = [
        ForeignKey(ClientEntity::class, ["id"], ["clientId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(WorkSiteEntity::class, ["id"], ["siteId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("clientId"), Index("siteId"), Index("status")],
)
data class JobEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String? = null,
    val clientId: String? = null,
    val siteId: String? = null,
    val billingMode: BillingMode,
    val materialsPolicy: MaterialsPolicy,
    /** Preço fixo aprovado, SEM IVA. Null = ainda sem preço (sinalizado nas projeções). */
    val fixedPriceNetCents: Long? = null,
    /** Taxa de IVA a repercutir ao cliente, em pontos-base (2300 = 23 %). Null = por configurar. */
    val vatRateBp: Int? = null,
    val status: JobStatus,
    val holdReason: HoldReason? = null,
    val holdNote: String? = null,
    val holdReviewDate: LocalDate? = null,
    val priority: Priority,
    val urgentReason: String? = null,
    val estimatedMinutes: Int? = null,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

@Entity(
    tableName = "schedule_entries",
    foreignKeys = [ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT)],
    indices = [
        Index("jobId"), Index("startMs"),
        // Idempotência: uma marcação de vários dias grava uma linha por dia com o mesmo operationId.
        Index(value = ["operationId", "startMs"], unique = true),
    ],
)
data class ScheduleEntryEntity(
    @PrimaryKey val id: String,
    /** Null nas reservas criadas antes do esquema v2. */
    val operationId: String? = null,
    val jobId: String,
    /** Intervalo semiaberto [startMs, endMs). */
    val startMs: Long,
    val endMs: Long,
    val firmness: BookingFirmness,
    /** Preenchido quando o utilizador mantém uma sobreposição de propósito. */
    val overlapReason: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

@Entity(
    tableName = "schedule_assignments",
    primaryKeys = ["entryId", "workerId"],
    foreignKeys = [
        ForeignKey(ScheduleEntryEntity::class, ["id"], ["entryId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(WorkerEntity::class, ["id"], ["workerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("workerId")],
)
data class ScheduleAssignmentEntity(val entryId: String, val workerId: String)

@Entity(
    tableName = "time_off",
    foreignKeys = [ForeignKey(WorkerEntity::class, ["id"], ["workerId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("workerId")],
)
data class TimeOffEntity(
    @PrimaryKey val id: String,
    val workerId: String,
    val startMs: Long,
    val endMs: Long,
    val reason: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

enum class TimeEntryKind { WORK, TRAVEL, OTHER }

@Entity(
    tableName = "time_entries",
    foreignKeys = [
        ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(WorkerEntity::class, ["id"], ["workerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("jobId"), Index("workerId"), Index("date"),
        // Idempotência: repetir a mesma operação (duplo toque, nova tentativa) não cria um segundo registo.
        Index(value = ["operationId", "workerId"], unique = true),
    ],
)
data class TimeEntryEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val jobId: String,
    val workerId: String,
    val date: LocalDate,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val breakMinutes: Int = 0,
    val minutes: Int,
    val kind: TimeEntryKind,
    val billable: Boolean,
    /** Taxas copiadas no momento do registo; alterar a taxa habitual não as muda. */
    val saleRateCents: Long,
    val costRateCents: Long,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

/** O sinal de caixa resulta do tipo: entradas somam, saídas subtraem. */
enum class CashKind(val inflow: Boolean) {
    CLIENT_RECEIPT(true),
    CLIENT_REFUND(false),
    SUPPLIER_PAYMENT(false),
    CAPITAL_IN(true),
    WITHDRAWAL(false),
    ADJUSTMENT_IN(true),
    ADJUSTMENT_OUT(false),
}

enum class PaymentMethod { CASH, TRANSFER, MBWAY, OTHER }

@Entity(
    tableName = "cash_movements",
    foreignKeys = [
        ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(CashMovementEntity::class, ["id"], ["reversalOfId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(PurchaseDocumentEntity::class, ["id"], ["purchaseDocumentId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("jobId"), Index("date"), Index("purchaseDocumentId"),
        Index(value = ["operationId"], unique = true),
        // Um movimento só pode ser estornado uma vez.
        Index(value = ["reversalOfId"], unique = true),
    ],
)
data class CashMovementEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val date: LocalDate,
    val kind: CashKind,
    /** Valor de caixa (com IVA), sempre positivo; a direção vem de [kind]. */
    @ColumnInfo(name = "amountCents") val amountCents: Long,
    val jobId: String? = null,
    val method: PaymentMethod? = null,
    val counterparty: String? = null,
    val note: String? = null,
    /** Se preenchido, este movimento anula o indicado. */
    val reversalOfId: String? = null,
    /** Pagamento (total ou parcial) de um documento de compra. */
    val purchaseDocumentId: String? = null,
    val createdAt: Long,
    val isDemo: Boolean = false,
) {
    /** Efeito na caixa: entradas somam, saídas subtraem; um estorno tem o efeito contrário do original. */
    val signedCents: Long
        get() {
            val base = if (kind.inflow) amountCents else -amountCents
            return if (reversalOfId != null) -base else base
        }

    val isReversal get() = reversalOfId != null
}

@Entity(tableName = "audit_events", indices = [Index("entityId"), Index("at")])
data class AuditEventEntity(
    @PrimaryKey val id: String,
    val entity: String,
    val entityId: String,
    val action: String,
    /** Resumo legível da alteração; sem identificar o operador (não há contas nesta versão). */
    val detail: String?,
    val at: Long,
)

/** Configuração da atividade em pares chave/valor (saldo inicial, margem de deslocação, horário…). */
@Entity(tableName = "settings")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

enum class ExpenseCategory { MATERIAL, TRAVEL, RENTAL, TOOL, SUBCONTRACT, OTHER }

/**
 * Documento de compra original, guardado uma única vez. Valores COM SINAL: numa nota de crédito
 * são negativos. A distribuição por obras está em [CostAllocationEntity]; os pagamentos ao
 * fornecedor são movimentos de caixa ligados por purchaseDocumentId (uma fatura não prova pagamento).
 */
@Entity(
    tableName = "purchase_documents",
    foreignKeys = [
        ForeignKey(PurchaseDocumentEntity::class, ["id"], ["originalDocumentId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(WorkerEntity::class, ["id"], ["paidByWorkerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("docDate"), Index("originalDocumentId"), Index("paidByWorkerId"),
        Index(value = ["operationId"], unique = true),
        // Duplicados: o mesmo documento do mesmo emitente só pode existir uma vez (NULLs não colidem).
        Index(value = ["supplierNif", "docType", "docNumber"], unique = true),
        Index(value = ["atcud"], unique = true),
        Index(value = ["contentHash"], unique = true),
    ],
)
data class PurchaseDocumentEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val supplierName: String?,
    val supplierNif: String?,
    val docType: DocumentType,
    val docNumber: String?,
    val atcud: String?,
    val docDate: LocalDate,
    val dueDate: LocalDate? = null,
    val category: ExpenseCategory,
    val netCents: Long,
    val vatCents: Long,
    val grossCents: Long,
    val vatTreatment: VatTreatment,
    /** Nota de crédito: documento original a que se refere. */
    val originalDocumentId: String? = null,
    /** Quem pagou (indicação, não autenticação). */
    val paidByWorkerId: String? = null,
    /** Conteúdo original lido do QR, para auditoria. */
    val rawQr: String? = null,
    val contentHash: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

/** Parcela de um documento atribuída a uma obra ou, com jobId nulo, a despesas gerais. */
@Entity(
    tableName = "cost_allocations",
    foreignKeys = [
        ForeignKey(PurchaseDocumentEntity::class, ["id"], ["documentId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("documentId"), Index("jobId")],
)
data class CostAllocationEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val jobId: String?,
    val netCents: Long,
    val vatCents: Long,
    val grossCents: Long,
)

/**
 * Revisão versionada do encerramento de uma obra. Reabrir preenche [reopenedAt]/[reopenReason];
 * encerrar de novo cria a revisão seguinte. Nada é apagado.
 */
@Entity(
    tableName = "job_closures",
    foreignKeys = [
        ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(WorkerEntity::class, ["id"], ["reviewerWorkerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["jobId", "revision"], unique = true), Index("reviewerWorkerId")],
)
data class JobClosureEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val revision: Int,
    val closedAt: Long,
    /** Pessoa indicada como responsável pela revisão (opcional; sem login não prova autoria). */
    val reviewerWorkerId: String?,
    val notes: String?,
    /** Balanço no momento do fecho, em JSON (valores em cêntimos). */
    val balanceJson: String,
    val reopenedAt: Long? = null,
    val reopenReason: String? = null,
)

/**
 * Histórico do acordo de preço de uma obra. Cada alteração (preço, IVA, modalidade) guarda
 * os valores anteriores e os novos; o acordo anterior nunca é apagado.
 */
@Entity(
    tableName = "price_revisions",
    foreignKeys = [ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["jobId", "revision"], unique = true)],
)
data class PriceRevisionEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val revision: Int,
    val oldBillingMode: BillingMode,
    val newBillingMode: BillingMode,
    val oldFixedPriceNetCents: Long?,
    val newFixedPriceNetCents: Long?,
    val oldVatRateBp: Int?,
    val newVatRateBp: Int?,
    val approvedOn: LocalDate,
    val reason: String,
    val createdAt: Long,
)

enum class ChargeKind { EXTRA, MATERIAL }

/**
 * Valor a cobrar ao cliente para além do preço base: extra aprovado ou material cobrado à parte.
 * Valores SEM IVA. Anular preenche deletedAt e o motivo (fica no histórico).
 */
@Entity(
    tableName = "job_charges",
    foreignKeys = [ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("jobId"), Index(value = ["operationId"], unique = true)],
)
data class JobChargeEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val jobId: String,
    val kind: ChargeKind,
    val description: String,
    val amountNetCents: Long,
    val approvedOn: LocalDate,
    /** Evidência/nota da aprovação (ex.: "aprovado por SMS a 12/10"). */
    val evidence: String? = null,
    /** Já incluído no preço acordado: não soma outra vez ao valor da obra. */
    val includedInPrice: Boolean = false,
    val createdAt: Long,
    val deletedAt: Long? = null,
    val deleteReason: String? = null,
    val isDemo: Boolean = false,
)

/** Prestação acordada com o cliente: valor COM IVA e data prevista de recebimento. */
@Entity(
    tableName = "installments",
    foreignKeys = [ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("jobId"), Index("dueDate")],
)
data class InstallmentEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val dueDate: LocalDate,
    val amountGrossCents: Long,
    val note: String? = null,
    val createdAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

enum class AttachmentKind { BEFORE, DURING, AFTER, DOCUMENT }

/**
 * Ficheiro copiado para a pasta privada da app (filesDir/attachments). [relativePath] é relativo
 * a filesDir: nunca um caminho absoluto nem um URI temporário (sobrevive a reinstalação via backup).
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(PurchaseDocumentEntity::class, ["id"], ["purchaseDocumentId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("jobId"), Index("purchaseDocumentId"), Index(value = ["relativePath"], unique = true)],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val jobId: String?,
    val purchaseDocumentId: String? = null,
    val kind: AttachmentKind,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** SHA-256 do ficheiro guardado, para validar backups. */
    val sha256: String,
    val caption: String? = null,
    val takenOn: LocalDate,
    val createdAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

@Entity(
    tableName = "notes",
    foreignKeys = [ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("jobId")],
)
data class NoteEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

enum class ChecklistKind { TASK, SHOPPING }

@Entity(
    tableName = "checklist_items",
    foreignKeys = [ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("jobId")],
)
data class ChecklistItemEntity(
    @PrimaryKey val id: String,
    val jobId: String,
    val kind: ChecklistKind,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isDemo: Boolean = false,
)

enum class InvoiceSource { QR_CAMERA, PDF, MANUAL }

/**
 * Fatura emitida por um dos trabalhadores a um cliente, para controlar o valor faturado no ano.
 * Valores COM SINAL (nota de crédito negativa). Não é um recebimento: o dinheiro entra em cash_movements.
 */
@Entity(
    tableName = "issued_invoices",
    foreignKeys = [
        ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AttachmentEntity::class, ["id"], ["attachmentId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("jobId"), Index("attachmentId"), Index("issueDate"),
        Index(value = ["operationId"], unique = true),
        // Duplicados: o mesmo documento do mesmo emitente só existe uma vez (NULLs não colidem).
        Index(value = ["issuerNif", "docType", "docNumber"], unique = true),
        Index(value = ["atcud"], unique = true),
    ],
)
data class IssuedInvoiceEntity(
    @PrimaryKey val id: String,
    val operationId: String,
    val issuerNif: String,
    val buyerNif: String?,
    val buyerName: String?,
    /** FT, FS, FR, NC ou ND. */
    val docType: String,
    val docNumber: String,
    val atcud: String?,
    val issueDate: LocalDate,
    /** Sem impostos (O − N do QR). */
    val netCents: Long,
    val vatCents: Long,
    val grossCents: Long,
    val withholdingCents: Long = 0,
    val jobId: String? = null,
    val attachmentId: String? = null,
    val source: InvoiceSource,
    val rawQr: String? = null,
    val note: String? = null,
    val createdAt: Long,
    val deletedAt: Long? = null,
    val deleteReason: String? = null,
    val isDemo: Boolean = false,
)

/**
 * Cronómetro a contar. A chave é a pessoa: no máximo um ativo por pessoa. Iniciar para várias
 * pessoas cria uma linha por pessoa com o mesmo [groupId]; parar regista as horas do grupo.
 */
@Entity(
    tableName = "active_timers",
    foreignKeys = [
        ForeignKey(WorkerEntity::class, ["id"], ["workerId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(JobEntity::class, ["id"], ["jobId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("groupId"), Index("jobId")],
)
data class ActiveTimerEntity(
    @PrimaryKey val workerId: String,
    val groupId: String,
    val jobId: String,
    val startedAt: Long,
)
