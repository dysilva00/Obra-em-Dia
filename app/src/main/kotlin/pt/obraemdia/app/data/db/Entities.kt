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
        Index("jobId"), Index("date"), Index("reversalOfId"), Index("purchaseDocumentId"),
        Index(value = ["operationId"], unique = true),
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
    val signedCents get() = if (kind.inflow) amountCents else -amountCents
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
