package pt.obraemdia.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import pt.obraemdia.domain.billing.DocumentType

@Dao
interface WorkerDao {
    @Query("SELECT * FROM workers WHERE deletedAt IS NULL ORDER BY sortOrder")
    fun observeAll(): Flow<List<WorkerEntity>>

    @Query("SELECT * FROM workers WHERE deletedAt IS NULL ORDER BY sortOrder")
    suspend fun getAll(): List<WorkerEntity>

    @Upsert suspend fun upsert(worker: WorkerEntity)
}

@Dao
interface ClientDao {
    @Insert suspend fun insert(client: ClientEntity)
    @Insert suspend fun insertSite(site: WorkSiteEntity)

    @Query("SELECT * FROM clients WHERE deletedAt IS NULL ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ClientEntity>>
}

/** Obra com o nome do cliente e a morada do local, para listas e fichas. */
data class JobRow(
    @Embedded val job: JobEntity,
    val clientName: String?,
    val siteAddress: String?,
    val clientPhone: String? = null,
)

@Dao
interface JobDao {
    @Insert suspend fun insert(job: JobEntity)
    @Update suspend fun update(job: JobEntity)

    @Query(
        """
        SELECT jobs.*, clients.name AS clientName, work_sites.address AS siteAddress, clients.phone AS clientPhone
        FROM jobs
        LEFT JOIN clients ON clients.id = jobs.clientId
        LEFT JOIN work_sites ON work_sites.id = jobs.siteId
        WHERE jobs.deletedAt IS NULL
        ORDER BY jobs.updatedAt DESC
        """,
    )
    fun observeAll(): Flow<List<JobRow>>

    @Query("SELECT * FROM jobs WHERE id = :id")
    suspend fun getById(id: String): JobEntity?
}

/** Reserva com as pessoas atribuídas, numa linha por pessoa (agrupada no repositório). */
data class ScheduleRow(
    @Embedded val entry: ScheduleEntryEntity,
    val workerId: String,
    val jobTitle: String,
    val siteId: String?,
)

@Dao
interface ScheduleDao {
    @Insert suspend fun insertEntry(entry: ScheduleEntryEntity)
    @Insert suspend fun insertAssignments(assignments: List<ScheduleAssignmentEntity>)
    @Insert suspend fun insertTimeOff(timeOff: TimeOffEntity)
    @Update suspend fun updateTimeOff(timeOff: TimeOffEntity)

    @Query("SELECT * FROM time_off WHERE id = :id")
    suspend fun getTimeOff(id: String): TimeOffEntity?

    /** Todas as indisponibilidades ativas (para as definições). */
    @Query("SELECT * FROM time_off WHERE deletedAt IS NULL ORDER BY startMs DESC")
    fun observeAllTimeOff(): Flow<List<TimeOffEntity>>

    @Query(
        """
        SELECT schedule_entries.*, a.workerId AS workerId, jobs.title AS jobTitle, jobs.siteId AS siteId
        FROM schedule_entries
        JOIN schedule_assignments a ON a.entryId = schedule_entries.id
        JOIN jobs ON jobs.id = schedule_entries.jobId
        WHERE schedule_entries.deletedAt IS NULL AND schedule_entries.endMs > :fromMs AND schedule_entries.startMs < :toMs
        ORDER BY schedule_entries.startMs
        """,
    )
    fun observeRange(fromMs: Long, toMs: Long): Flow<List<ScheduleRow>>

    @Query("SELECT * FROM time_off WHERE deletedAt IS NULL AND endMs > :fromMs AND startMs < :toMs")
    fun observeTimeOff(fromMs: Long, toMs: Long): Flow<List<TimeOffEntity>>

    /** Leitura direta (não Flow) para verificar conflitos dentro da transação de gravação. */
    @Query(
        """
        SELECT schedule_entries.*, a.workerId AS workerId, jobs.title AS jobTitle, jobs.siteId AS siteId
        FROM schedule_entries
        JOIN schedule_assignments a ON a.entryId = schedule_entries.id
        JOIN jobs ON jobs.id = schedule_entries.jobId
        WHERE schedule_entries.deletedAt IS NULL AND schedule_entries.endMs > :fromMs AND schedule_entries.startMs < :toMs
        """,
    )
    suspend fun getRange(fromMs: Long, toMs: Long): List<ScheduleRow>

    @Query("SELECT * FROM time_off WHERE deletedAt IS NULL AND endMs > :fromMs AND startMs < :toMs")
    suspend fun getTimeOff(fromMs: Long, toMs: Long): List<TimeOffEntity>

    /** IGNORE + índice único (operationId, startMs): repetir a marcação não a duplica. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIgnoring(entry: ScheduleEntryEntity): Long

    @Query("SELECT COUNT(*) FROM schedule_entries WHERE jobId = :jobId AND deletedAt IS NULL AND firmness = 'FIRM'")
    suspend fun firmCount(jobId: String): Int

    /** Liberta (eliminação lógica) as reservas futuras de uma obra; devolve quantas foram libertadas. */
    @Query("UPDATE schedule_entries SET deletedAt = :now, updatedAt = :now WHERE jobId = :jobId AND startMs >= :now AND deletedAt IS NULL")
    suspend fun releaseFuture(jobId: String, now: Long): Int
}

@Dao
interface TimeEntryDao {
    @Query("SELECT * FROM time_entries WHERE id = :id")
    suspend fun getById(id: String): TimeEntryEntity?

    @Update suspend fun update(entry: TimeEntryEntity)

    /** IGNORE + índice único (operationId, workerId): repetir a operação não duplica horas. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<TimeEntryEntity>): List<Long>

    @Query("SELECT * FROM time_entries WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<TimeEntryEntity>>

    @Query("SELECT * FROM time_entries WHERE deletedAt IS NULL AND jobId = :jobId AND workerId = :workerId AND date = :date")
    suspend fun findSameDay(jobId: String, workerId: String, date: java.time.LocalDate): List<TimeEntryEntity>
}

@Dao
interface CashDao {
    @Query("SELECT * FROM cash_movements WHERE id = :id")
    suspend fun getById(id: String): CashMovementEntity?

    @Query("SELECT * FROM cash_movements WHERE purchaseDocumentId = :docId")
    suspend fun forDocument(docId: String): List<CashMovementEntity>

    /** IGNORE + operationId único: um duplo toque não regista o pagamento duas vezes. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(movement: CashMovementEntity): Long

    @Query("SELECT * FROM cash_movements ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<CashMovementEntity>>
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(event: AuditEventEntity)

    @Query("SELECT * FROM audit_events ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEventEntity>>
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Upsert suspend fun put(setting: SettingEntity)
}

@Dao
interface PurchaseDao {
    @Update suspend fun update(doc: PurchaseDocumentEntity)

    /** IGNORE: o operationId e as chaves de duplicado são únicos; -1 significa que não foi gravado. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(doc: PurchaseDocumentEntity): Long

    @Insert suspend fun insertAllocations(allocations: List<CostAllocationEntity>)

    @Query("SELECT * FROM purchase_documents WHERE deletedAt IS NULL ORDER BY docDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<PurchaseDocumentEntity>>

    @Query("SELECT a.* FROM cost_allocations a JOIN purchase_documents d ON d.id = a.documentId WHERE d.deletedAt IS NULL")
    fun observeAllocations(): Flow<List<CostAllocationEntity>>

    @Query("SELECT * FROM purchase_documents WHERE id = :id")
    suspend fun getById(id: String): PurchaseDocumentEntity?

    @Query("SELECT * FROM purchase_documents WHERE operationId = :operationId")
    suspend fun getByOperation(operationId: String): PurchaseDocumentEntity?

    /** Documento já registado com a mesma identificação (emitente + tipo + número, ou ATCUD). */
    @Query(
        """
        SELECT * FROM purchase_documents WHERE deletedAt IS NULL AND (
            (:atcud IS NOT NULL AND atcud = :atcud)
            OR (:nif IS NOT NULL AND :number IS NOT NULL AND supplierNif = :nif AND docType = :type AND docNumber = :number)
        ) LIMIT 1
        """,
    )
    suspend fun findDuplicate(nif: String?, type: DocumentType, number: String?, atcud: String?): PurchaseDocumentEntity?

    @Query("SELECT COALESCE(SUM(CASE WHEN reversalOfId IS NULL THEN amountCents ELSE -amountCents END), 0) FROM cash_movements WHERE purchaseDocumentId = :docId AND kind = 'SUPPLIER_PAYMENT'")
    suspend fun paidCents(docId: String): Long

    @Query("SELECT * FROM cost_allocations WHERE documentId = :docId")
    suspend fun allocationsFor(docId: String): List<CostAllocationEntity>
}

@Dao
interface ClosureDao {
    @Insert suspend fun insert(closure: JobClosureEntity)
    @Update suspend fun update(closure: JobClosureEntity)

    @Query("SELECT * FROM job_closures ORDER BY jobId, revision")
    fun observeAll(): Flow<List<JobClosureEntity>>

    @Query("SELECT * FROM job_closures WHERE jobId = :jobId ORDER BY revision DESC LIMIT 1")
    suspend fun latest(jobId: String): JobClosureEntity?
}

/** Leituras diretas para recalcular o balanço de uma obra dentro de uma transação. */
@Dao
interface JobFactsDao {
    @Query(
        """
        SELECT jobs.*, clients.name AS clientName, work_sites.address AS siteAddress, clients.phone AS clientPhone
        FROM jobs LEFT JOIN clients ON clients.id = jobs.clientId LEFT JOIN work_sites ON work_sites.id = jobs.siteId
        WHERE jobs.id = :jobId
        """,
    )
    suspend fun row(jobId: String): JobRow?

    @Query("SELECT * FROM time_entries WHERE jobId = :jobId AND deletedAt IS NULL")
    suspend fun timeEntries(jobId: String): List<TimeEntryEntity>

    @Query("SELECT * FROM cash_movements WHERE jobId = :jobId")
    suspend fun cash(jobId: String): List<CashMovementEntity>

    @Query(
        """
        SELECT a.* FROM cost_allocations a JOIN purchase_documents d ON d.id = a.documentId
        WHERE a.jobId = :jobId AND d.deletedAt IS NULL
        """,
    )
    suspend fun allocations(jobId: String): List<CostAllocationEntity>

    @Query(
        """
        SELECT DISTINCT d.* FROM purchase_documents d JOIN cost_allocations a ON a.documentId = d.id
        WHERE a.jobId = :jobId AND d.deletedAt IS NULL
        """,
    )
    suspend fun documents(jobId: String): List<PurchaseDocumentEntity>
}

@Dao
interface AgreementDao {
    @Insert suspend fun insertInstallment(i: InstallmentEntity)
    @Update suspend fun updateInstallment(i: InstallmentEntity)

    @Query("SELECT * FROM installments WHERE id = :id")
    suspend fun getInstallment(id: String): InstallmentEntity?

    @Query("SELECT * FROM installments WHERE deletedAt IS NULL ORDER BY dueDate")
    fun observeInstallments(): Flow<List<InstallmentEntity>>

    @Insert suspend fun insertRevision(revision: PriceRevisionEntity)

    @Query("SELECT COALESCE(MAX(revision), 0) FROM price_revisions WHERE jobId = :jobId")
    suspend fun lastRevision(jobId: String): Int

    @Query("SELECT * FROM price_revisions ORDER BY jobId, revision")
    fun observeRevisions(): Flow<List<PriceRevisionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCharge(charge: JobChargeEntity): Long

    @Update suspend fun updateCharge(charge: JobChargeEntity)

    @Query("SELECT * FROM job_charges WHERE id = :id")
    suspend fun getCharge(id: String): JobChargeEntity?

    /** Inclui as anuladas, para o histórico; os cálculos filtram deletedAt. */
    @Query("SELECT * FROM job_charges ORDER BY approvedOn, createdAt")
    fun observeCharges(): Flow<List<JobChargeEntity>>

    @Query("SELECT * FROM job_charges WHERE jobId = :jobId AND deletedAt IS NULL")
    suspend fun activeCharges(jobId: String): List<JobChargeEntity>

    @Update suspend fun updateClient(client: ClientEntity)
    @Update suspend fun updateSite(site: WorkSiteEntity)

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun client(id: String): ClientEntity?

    @Query("SELECT * FROM work_sites WHERE id = :id")
    suspend fun site(id: String): WorkSiteEntity?
}

@Dao
interface MediaDao {
    @Insert suspend fun insertAttachment(a: AttachmentEntity)
    @Update suspend fun updateAttachment(a: AttachmentEntity)

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun attachment(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE jobId = :jobId AND deletedAt IS NULL ORDER BY takenOn DESC, createdAt DESC")
    fun observeAttachments(jobId: String): Flow<List<AttachmentEntity>>

    @Insert suspend fun insertNote(n: NoteEntity)
    @Update suspend fun updateNote(n: NoteEntity)

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    @Query("SELECT * FROM notes WHERE jobId = :jobId AND deletedAt IS NULL ORDER BY createdAt DESC")
    fun observeNotes(jobId: String): Flow<List<NoteEntity>>

    @Insert suspend fun insertItem(i: ChecklistItemEntity)
    @Update suspend fun updateItem(i: ChecklistItemEntity)

    @Query("SELECT * FROM checklist_items WHERE id = :id")
    suspend fun item(id: String): ChecklistItemEntity?

    @Query("SELECT * FROM checklist_items WHERE jobId = :jobId AND deletedAt IS NULL ORDER BY done, createdAt")
    fun observeChecklist(jobId: String): Flow<List<ChecklistItemEntity>>

    /** Contagens por obra para a ficha (sem carregar ficheiros). */
    @Query(
        """
        SELECT j.id AS jobId,
            (SELECT COUNT(*) FROM attachments a WHERE a.jobId = j.id AND a.deletedAt IS NULL) AS photos,
            (SELECT COUNT(*) FROM notes n WHERE n.jobId = j.id AND n.deletedAt IS NULL) AS notes,
            (SELECT COUNT(*) FROM checklist_items c WHERE c.jobId = j.id AND c.deletedAt IS NULL AND c.done = 0) AS openItems
        FROM jobs j
        """,
    )
    fun observeCounts(): Flow<List<MediaCounts>>

    @Query("SELECT * FROM attachments WHERE deletedAt IS NULL AND id IN (SELECT attachmentId FROM issued_invoices WHERE attachmentId IS NOT NULL)")
    fun observeInvoiceAttachments(): Flow<List<AttachmentEntity>>

    /** Caminhos de anexos de obras fictícias, para apagar os ficheiros depois de remover a demonstração. */
    @Query("SELECT relativePath FROM attachments WHERE isDemo = 1 OR jobId IN (SELECT id FROM jobs WHERE isDemo = 1)")
    suspend fun demoAttachmentPaths(): List<String>
}

data class MediaCounts(val jobId: String, val photos: Int, val notes: Int, val openItems: Int)

@Dao
interface IssuedInvoiceDao {
    /** IGNORE: operationId e as chaves de duplicado são únicos; -1 = não gravado. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(i: IssuedInvoiceEntity): Long

    @Update suspend fun update(i: IssuedInvoiceEntity)

    @Query("SELECT * FROM issued_invoices WHERE id = :id")
    suspend fun get(id: String): IssuedInvoiceEntity?

    @Query("SELECT * FROM issued_invoices WHERE deletedAt IS NULL ORDER BY issueDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<IssuedInvoiceEntity>>

    @Query(
        """
        SELECT * FROM issued_invoices WHERE deletedAt IS NULL AND (
            (:atcud IS NOT NULL AND atcud = :atcud)
            OR (issuerNif = :nif AND docType = :type AND docNumber = :number)
        ) LIMIT 1
        """,
    )
    suspend fun findDuplicate(nif: String, type: String, number: String, atcud: String?): IssuedInvoiceEntity?
}
