package pt.obraemdia.app.demo

import androidx.room.Dao
import androidx.room.Query
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.CashKind
import pt.obraemdia.app.data.db.CashMovementEntity
import pt.obraemdia.app.data.db.ClientEntity
import pt.obraemdia.app.data.db.CostAllocationEntity
import pt.obraemdia.app.data.db.ExpenseCategory
import pt.obraemdia.app.data.db.InstallmentEntity
import pt.obraemdia.app.data.db.PurchaseDocumentEntity
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
import pt.obraemdia.domain.billing.DocumentType
import pt.obraemdia.domain.billing.VatTreatment
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.HoldReason
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.Allocation
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.money.Vat
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.DayPart
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalDate

/** Remove em bloco tudo o que foi criado como demonstração (nunca toca em registos reais). */
@Dao
interface DemoDao {
    // Tudo o que está ligado a uma obra fictícia é fictício e sai com ela. Registos de obras reais nunca são tocados.
    @Query("DELETE FROM schedule_entries WHERE isDemo = 1 OR jobId IN (SELECT id FROM jobs WHERE isDemo = 1)") suspend fun deleteSchedule()
    @Query("DELETE FROM time_off WHERE isDemo = 1") suspend fun deleteTimeOff()
    @Query("DELETE FROM time_entries WHERE isDemo = 1 OR jobId IN (SELECT id FROM jobs WHERE isDemo = 1)") suspend fun deleteTimeEntries()

    /**
     * Compras a remover: fictícias, ou só atribuídas a obras fictícias. Uma compra repartida também
     * por uma obra real fica; só a parcela da obra fictícia sai (passa a "por classificar").
     */
    @Query(
        """
        DELETE FROM cash_movements WHERE isDemo = 1
            OR jobId IN (SELECT id FROM jobs WHERE isDemo = 1)
            OR purchaseDocumentId IN ($DEMO_ONLY_PURCHASES)
        """,
    )
    suspend fun deleteCash()

    // As parcelas (cost_allocations) são apagadas em cascata com o documento.
    @Query("DELETE FROM purchase_documents WHERE id IN ($DEMO_ONLY_PURCHASES)")
    suspend fun deletePurchases()

    @Query("DELETE FROM cost_allocations WHERE jobId IN (SELECT id FROM jobs WHERE isDemo = 1)")
    suspend fun deleteDemoAllocations()

    @Query("DELETE FROM job_charges WHERE isDemo = 1 OR jobId IN (SELECT id FROM jobs WHERE isDemo = 1)") suspend fun deleteCharges()
    @Query("DELETE FROM price_revisions WHERE jobId IN (SELECT id FROM jobs WHERE isDemo = 1)") suspend fun deleteRevisions()
    @Query("DELETE FROM installments WHERE isDemo = 1 OR jobId IN (SELECT id FROM jobs WHERE isDemo = 1)") suspend fun deleteInstallments()
    @Query("DELETE FROM job_closures WHERE jobId IN (SELECT id FROM jobs WHERE isDemo = 1)") suspend fun deleteClosures()
    @Query("DELETE FROM jobs WHERE isDemo = 1") suspend fun deleteJobs()

    // Clientes e locais fictícios que uma obra real passou a usar ficam (deixam de ser "demo").
    @Query("DELETE FROM work_sites WHERE isDemo = 1 AND id NOT IN (SELECT siteId FROM jobs WHERE siteId IS NOT NULL)") suspend fun deleteSites()
    @Query("UPDATE work_sites SET isDemo = 0 WHERE isDemo = 1") suspend fun keepUsedSites()
    @Query(
        "DELETE FROM clients WHERE isDemo = 1 AND id NOT IN (SELECT clientId FROM jobs WHERE clientId IS NOT NULL) AND id NOT IN (SELECT clientId FROM work_sites WHERE clientId IS NOT NULL)",
    )
    suspend fun deleteClients()
    @Query("UPDATE clients SET isDemo = 0 WHERE isDemo = 1") suspend fun keepUsedClients()

    @Query("SELECT COUNT(*) FROM workers") suspend fun workerCount(): Int
}

private const val DEMO_ONLY_PURCHASES = """
    SELECT d.id FROM purchase_documents d WHERE d.isDemo = 1
    OR (
        EXISTS (SELECT 1 FROM cost_allocations a JOIN jobs j ON j.id = a.jobId WHERE a.documentId = d.id AND j.isDemo = 1)
        AND NOT EXISTS (SELECT 1 FROM cost_allocations a JOIN jobs j ON j.id = a.jobId WHERE a.documentId = d.id AND j.isDemo = 0)
    )
"""

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
        d.deleteSchedule(); d.deleteTimeOff(); d.deleteTimeEntries(); d.deleteCash(); d.deletePurchases(); d.deleteDemoAllocations()
        d.deleteCharges(); d.deleteRevisions(); d.deleteInstallments(); d.deleteClosures(); d.deleteJobs()
        d.deleteSites(); d.keepUsedSites(); d.deleteClients(); d.keepUsedClients()
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
            db.scheduleDao().insertEntry(ScheduleEntryEntity(id = id, jobId = jobId, startMs = slot.start.toEpochMilli(), endMs = slot.end.toEpochMilli(), firmness = firm, createdAt = now, updatedAt = now, isDemo = true))
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
        cash(today.minusDays(8), CashKind.CLIENT_RECEIPT, 500, roof, "Sr. Exemplo")
        cash(today.minusDays(5), CashKind.CLIENT_RECEIPT, 600, kitchen, "Maria (exemplo)")

        // Compras: o documento conta uma vez; as parcelas distribuem-no; pagar é um movimento à parte.
        suspend fun purchase(
            date: LocalDate, supplier: String, category: ExpenseCategory, grossCents: Long,
            lines: List<Pair<String?, Long>>, paid: Boolean, due: LocalDate? = null,
        ) {
            val docId = newId()
            val amounts = Vat.splitGross(Money(grossCents), 2300)
            db.purchaseDao().insert(
                PurchaseDocumentEntity(
                    id = docId, operationId = newId(), supplierName = supplier, supplierNif = null,
                    docType = DocumentType.INVOICE, docNumber = null, atcud = null, docDate = date, dueDate = due,
                    category = category, netCents = amounts.net.cents, vatCents = amounts.vat.cents, grossCents = grossCents,
                    vatTreatment = VatTreatment.UNKNOWN, createdAt = now, updatedAt = now, isDemo = true,
                ),
            )
            val split = Allocation.splitDocument(amounts, lines.map { Money(it.second) })
            db.purchaseDao().insertAllocations(
                split.lines.mapIndexed { i, l -> CostAllocationEntity(newId(), docId, lines[i].first, l.net.cents, l.vat.cents, l.gross.cents) },
            )
            if (paid) {
                db.cashDao().insert(
                    CashMovementEntity(
                        id = newId(), operationId = newId(), date = date, kind = CashKind.SUPPLIER_PAYMENT, amountCents = grossCents,
                        jobId = lines.singleOrNull()?.first, method = PaymentMethod.CASH, counterparty = supplier,
                        purchaseDocumentId = docId, createdAt = now, isDemo = true,
                    ),
                )
            }
        }

        purchase(today.minusDays(10), "Loja de materiais (exemplo)", ExpenseCategory.MATERIAL, 45000, listOf(bath to 45000L), paid = true)
        purchase(today.minusDays(6), "Combustível (exemplo)", ExpenseCategory.TRAVEL, 6500, listOf(null to 6500L), paid = true)
        purchase(today.minusDays(3), "Drogaria (exemplo)", ExpenseCategory.MATERIAL, 12300, listOf(bath to 8000L, wall to 4300L), paid = true)
        purchase(today.minusDays(9), "Armazém de cerâmica (exemplo)", ExpenseCategory.MATERIAL, 31000, listOf(kitchen to 31000L), paid = false, due = today.plusDays(20))

        // Plano de pagamentos: o adiantamento já recebido cobre a 1.ª prestação; as restantes entram na previsão.
        suspend fun installment(jobId: String, due: LocalDate, euros: Long) =
            db.agreementDao().insertInstallment(InstallmentEntity(newId(), jobId, due, euros * 100, createdAt = now, isDemo = true))
        installment(bath, today.minusDays(12), 1000)
        installment(bath, today.plusDays(10), 1000)
        installment(bath, today.plusDays(35), 1000)
        installment(roof, today.minusDays(3), 650) // vencida: aparece nos alertas
    }
}
