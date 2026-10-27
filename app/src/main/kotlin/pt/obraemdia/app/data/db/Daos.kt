package pt.obraemdia.app.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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
)

@Dao
interface JobDao {
    @Insert suspend fun insert(job: JobEntity)
    @Update suspend fun update(job: JobEntity)

    @Query(
        """
        SELECT jobs.*, clients.name AS clientName, work_sites.address AS siteAddress
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
}

@Dao
interface TimeEntryDao {
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
    /** IGNORE + operationId único: um duplo toque não regista o pagamento duas vezes. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(movement: CashMovementEntity): Long

    @Query("SELECT * FROM cash_movements ORDER BY date DESC, createdAt DESC")
    fun observeAll(): Flow<List<CashMovementEntity>>
}

@Dao
interface AuditDao {
    @Insert suspend fun insert(event: AuditEventEntity)
}

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingEntity>>

    @Upsert suspend fun put(setting: SettingEntity)
}
