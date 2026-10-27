package pt.obraemdia.app.demo

import androidx.room.Dao
import androidx.room.Query
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.db.CashMovementEntity
import pt.obraemdia.app.data.db.ClientEntity
import pt.obraemdia.app.data.db.JobEntity
import pt.obraemdia.app.data.db.PaymentMethod
import pt.obraemdia.app.data.db.ScheduleAssignmentEntity
import pt.obraemdia.app.data.db.ScheduleEntryEntity
import pt.obraemdia.app.data.db.SettingEntity
import pt.obraemdia.app.data.db.TimeEntryEntity
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.app.data.db.WorkSiteEntity
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.Settings
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.HoldReason
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.DayPart
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalDate

/** Remove em bloco tudo o que foi criado como demonstração (nunca toca em registos reais). */
@Dao
interface DemoDao {
    @Query("DELETE FROM schedule_entries WHERE isDemo = 1") suspend fun deleteSchedule()
    @Query("DELETE FROM time_off WHERE isDemo = 1") suspend fun deleteTimeOff()
    @Query("DELETE FROM time_entries WHERE isDemo = 1") suspend fun deleteTimeEntries()
    @Query("DELETE FROM cash_movements WHERE isDemo = 1") suspend fun deleteCash()
    @Query("DELETE FROM jobs WHERE isDemo = 1") suspend fun deleteJobs()
    @Query("DELETE FROM work_sites WHERE isDemo = 1") suspend fun deleteSites()
    @Query("DELETE FROM clients WHERE isDemo = 1") suspend fun deleteClients()
    @Query("SELECT COUNT(*) FROM workers") suspend fun workerCount(): Int
}

/**
 * Primeiro arranque: cria as duas pessoas com nomes e taxas PROVISÓRIOS (a confirmar, spec §20)
 * e um conjunto de dados fictícios marcado com isDemo, relativo à data de hoje.
 */
class DemoSeeder(private val db: AppDatabase, private val repo: ObraRepository) {

    suspend fun seedIfFirstRun(today: LocalDate = LocalDate.now(AGENDA_ZONE)) = repo.transaction {
        if (db.demoDao().workerCount() > 0) return@transaction
        val now = System.currentTimeMillis()
        val p1 = WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, now, now)
        val p2 = WorkerEntity("w2", "Pessoa 2", 2200, 1500, 1, now, now)
        listOf(p1, p2).forEach { db.workerDao().upsert(it) }
        db.settingsDao().put(SettingEntity(Settings.OPENING_BALANCE_CENTS, "250000"))
        seedDemo(today, now, p1, p2)
    }

    suspend fun deleteDemoData() = repo.transaction {
        val d = db.demoDao()
        // Ordem inversa das dependências (chaves estrangeiras RESTRICT).
        d.deleteSchedule(); d.deleteTimeOff(); d.deleteTimeEntries(); d.deleteCash()
        d.deleteJobs(); d.deleteSites(); d.deleteClients()
        db.settingsDao().put(SettingEntity(Settings.OPENING_BALANCE_CENTS, "0"))
    }

    private suspend fun seedDemo(today: LocalDate, now: Long, p1: WorkerEntity, p2: WorkerEntity) {
        val hours = WorkHours()

        suspend fun client(name: String, address: String): Pair<String, String> {
            val c = newId(); val s = newId()
            db.clientDao().insert(ClientEntity(c, name, createdAt = now, updatedAt = now, isDemo = true))
            db.clientDao().insertSite(WorkSiteEntity(s, c, address, address, now, now, isDemo = true))
            return c to s
        }

        suspend fun job(
            title: String, place: Pair<String, String>?, status: JobStatus, priority: Priority,
            mode: BillingMode, materials: MaterialsPolicy, priceEuros: Long?,
            hold: HoldReason? = null, urgent: String? = null,
        ): String {
            val id = newId()
            db.jobDao().insert(
                JobEntity(
                    id = id, title = title, clientId = place?.first, siteId = place?.second,
                    billingMode = mode, materialsPolicy = materials,
                    fixedPriceNetCents = priceEuros?.let { it * 100 }, vatRateBp = 0,
                    status = status, holdReason = hold, priority = priority, urgentReason = urgent,
                    createdAt = now, updatedAt = now, isDemo = true,
                ),
            )
            return id
        }

        suspend fun book(jobId: String, workers: List<WorkerEntity>, slot: TimeSlot, firm: BookingFirmness = BookingFirmness.FIRM) {
            val id = newId()
            db.scheduleDao().insertEntry(ScheduleEntryEntity(id, jobId, slot.start.toEpochMilli(), slot.end.toEpochMilli(), firm, createdAt = now, updatedAt = now, isDemo = true))
            db.scheduleDao().insertAssignments(workers.map { ScheduleAssignmentEntity(id, it.id) })
        }

        suspend fun work(jobId: String, worker: WorkerEntity, date: LocalDate, minutes: Int = 480) {
            db.timeEntryDao().insertAll(
                listOf(
                    TimeEntryEntity(
                        newId(), newId(), jobId, worker.id, date, minutes = minutes, kind = TimeEntryKind.WORK, billable = true,
                        saleRateCents = worker.defaultSaleRateCents, costRateCents = worker.defaultCostRateCents,
                        createdAt = now, updatedAt = now, isDemo = true,
                    ),
                ),
            )
        }

        suspend fun cash(date: LocalDate, kind: CashKind, euros: Long, jobId: String?, who: String?) {
            db.cashDao().insert(
                CashMovementEntity(newId(), newId(), date, kind, euros * 100, jobId, PaymentMethod.TRANSFER, who, createdAt = now, isDemo = true),
            )
        }

        val ana = client("Ana (exemplo)", "Rua Exemplo 12, Lisboa")
        val maria = client("Maria (exemplo)", "Avenida Teste 45, Odivelas")
        val sr = client("Sr. Exemplo", "Largo Inventado 7, Sintra")

        val bath = job("Casa de banho — Casa da Ana", ana, JobStatus.IN_PROGRESS, Priority.HIGH, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, 3000)
        val wall = job("Pintura de muro", null, JobStatus.SCHEDULED, Priority.NORMAL, BillingMode.HOURLY, MaterialsPolicy.BILLED_SEPARATELY, null)
        val roof = job("Reparação de telhado", sr, JobStatus.ON_HOLD, Priority.URGENT, BillingMode.FIXED_PRICE, MaterialsPolicy.BILLED_SEPARATELY, 1800, HoldReason.WEATHER, "Infiltração na sala")
        val kitchen = job("Assentar mosaico — cozinha", maria, JobStatus.COMPLETED, Priority.NORMAL, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, 1200)
        job("Orçamento de varanda", null, JobStatus.TO_CONFIRM, Priority.LOW, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, null)

        book(bath, listOf(p1, p2), hours.slotFor(today, DayPart.FULL_DAY))
        book(bath, listOf(p1, p2), hours.slotFor(today.plusDays(1), DayPart.MORNING))
        book(wall, listOf(p2), hours.slotFor(today.plusDays(1), DayPart.MORNING), BookingFirmness.TENTATIVE)
        book(wall, listOf(p2), hours.slotFor(today.plusDays(2), DayPart.FULL_DAY))
        book(bath, listOf(p1), hours.slotFor(today.plusDays(2), DayPart.AFTERNOON))
        val off = TimeSlot.wholeDay(today.plusDays(3))
        db.scheduleDao().insertTimeOff(TimeOffEntity(newId(), p1.id, off.start.toEpochMilli(), off.end.toEpochMilli(), "Consulta médica", now, now, isDemo = true))

        listOf(today.minusDays(2), today.minusDays(1)).forEach { d -> work(bath, p1, d); work(bath, p2, d) }
        work(kitchen, p1, today.minusDays(9)); work(kitchen, p2, today.minusDays(9))
        work(wall, p2, today.minusDays(4), 240)

        cash(today.minusDays(12), CashKind.CLIENT_RECEIPT, 1000, bath, "Ana (exemplo)")
        cash(today.minusDays(10), CashKind.SUPPLIER_PAYMENT, 450, bath, "Loja de materiais (exemplo)")
        cash(today.minusDays(8), CashKind.CLIENT_RECEIPT, 500, roof, "Sr. Exemplo")
        cash(today.minusDays(6), CashKind.SUPPLIER_PAYMENT, 65, null, "Combustível")
        cash(today.minusDays(5), CashKind.CLIENT_RECEIPT, 600, kitchen, "Maria (exemplo)")
    }
}
