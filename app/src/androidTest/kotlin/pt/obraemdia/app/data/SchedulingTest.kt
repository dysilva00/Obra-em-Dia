package pt.obraemdia.app.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.TimeOffEntity
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.BookingOutcome
import pt.obraemdia.app.data.repo.NewBooking
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.StatusOutcome
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.HoldReason
import pt.obraemdia.domain.job.JobStatus
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.job.StatusChange
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.DayPart
import pt.obraemdia.domain.schedule.TimeSlot
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SchedulingTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private val hours = WorkHours()
    private val day = LocalDate.of(2030, 3, 4) // segunda-feira, longe de "hoje"
    // Relógio fixo antes de [day], para "reservas futuras" serem previsíveis.
    private val now = day.minusDays(10).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db) { now }
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
        db.workerDao().upsert(WorkerEntity("w2", "Pessoa 2", 2000, 1200, 1, 0, 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun job(title: String = "Obra") =
        repo.createJob(NewJob(title, null, null, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, Money.euros(1000), 0, Priority.NORMAL, null, null))

    private fun booking(jobId: String, workers: Set<String>, vararg days: LocalDate, reason: String? = null, op: String = newId()) =
        NewBooking(op, jobId, workers, days.map { hours.slotFor(it, DayPart.FULL_DAY) }, BookingFirmness.FIRM, reason, null)

    @Test
    fun marcar_ambos_dois_dias_e_passar_a_agendada() = runTest {
        val a = job()
        val outcome = repo.createBooking(booking(a, setOf("w1", "w2"), day, day.plusDays(1)))
        assertEquals(BookingOutcome.Saved(2, jobBecameScheduled = true), outcome)
        assertEquals(JobStatus.SCHEDULED, db.jobDao().getById(a)!!.status)
    }

    @Test
    fun conflito_sem_motivo_nao_grava_e_com_motivo_grava() = runTest {
        val a = job("A")
        val b = job("B")
        repo.createBooking(booking(a, setOf("w1"), day))

        val blocked = repo.createBooking(booking(b, setOf("w1", "w2"), day))
        assertTrue(blocked is BookingOutcome.Conflicts)
        assertEquals("w1", (blocked as BookingOutcome.Conflicts).conflicts.single().workerId)
        assertEquals(1, db.scheduleDao().getRange(0, Long.MAX_VALUE).map { it.entry.id }.distinct().size)

        val kept = repo.createBooking(booking(b, setOf("w1", "w2"), day, reason = "Cliente pediu os dois"))
        assertTrue(kept is BookingOutcome.Saved)
        val saved = db.scheduleDao().getRange(0, Long.MAX_VALUE).first { it.entry.jobId == b }
        assertEquals("Cliente pediu os dois", saved.entry.overlapReason)
    }

    @Test
    fun obras_em_paralelo_com_pessoas_diferentes_sao_permitidas() = runTest {
        val a = job("A")
        val b = job("B")
        assertTrue(repo.createBooking(booking(a, setOf("w1"), day)) is BookingOutcome.Saved)
        assertTrue(repo.createBooking(booking(b, setOf("w2"), day)) is BookingOutcome.Saved)
    }

    @Test
    fun reserva_para_ambos_conflita_com_indisponibilidade_de_um() = runTest {
        val a = job()
        val off = TimeSlot.wholeDay(day)
        db.scheduleDao().insertTimeOff(TimeOffEntity("t1", "w2", off.start.toEpochMilli(), off.end.toEpochMilli(), "Férias", 0, 0))
        val outcome = repo.createBooking(booking(a, setOf("w1", "w2"), day))
        assertTrue(outcome is BookingOutcome.Conflicts)
    }

    @Test
    fun repetir_a_mesma_marcacao_nao_duplica_nem_conflitua_consigo() = runTest {
        val a = job()
        val input = booking(a, setOf("w1", "w2"), day, day.plusDays(1))
        repo.createBooking(input)
        assertEquals(BookingOutcome.Duplicate, repo.createBooking(input))
        assertEquals(2, db.scheduleDao().getRange(0, Long.MAX_VALUE).map { it.entry.id }.distinct().size)
    }

    @Test
    fun agendada_exige_reserva_e_espera_exige_motivo() = runTest {
        val a = job()
        assertTrue(repo.changeStatus(a, StatusChange(JobStatus.SCHEDULED)) is StatusOutcome.Rejected)
        assertTrue(repo.changeStatus(a, StatusChange(JobStatus.ON_HOLD)) is StatusOutcome.Rejected)
        assertTrue(repo.changeStatus(a, StatusChange(JobStatus.ON_HOLD, HoldReason.WEATHER, null, day)) is StatusOutcome.Changed)
        val held = db.jobDao().getById(a)!!
        assertEquals(HoldReason.WEATHER, held.holdReason)
        assertEquals(day, held.holdReviewDate)

        // Sair da espera limpa o motivo.
        repo.changeStatus(a, StatusChange(JobStatus.IN_PROGRESS))
        assertEquals(null, db.jobDao().getById(a)!!.holdReason)
    }

    @Test
    fun cancelar_liberta_so_reservas_futuras() = runTest {
        val a = job()
        // Uma reserva antes de "agora" (passado) e duas depois (futuro).
        repo.createBooking(booking(a, setOf("w1"), day.minusDays(20)))
        repo.createBooking(booking(a, setOf("w1"), day, day.plusDays(1)))

        val outcome = repo.changeStatus(a, StatusChange(JobStatus.CANCELLED))

        assertEquals(StatusOutcome.Changed(releasedBookings = 2), outcome)
        val remaining = db.scheduleDao().getRange(0, Long.MAX_VALUE)
        assertEquals(1, remaining.size)
        assertTrue(remaining.single().entry.startMs < now)
        assertEquals(JobStatus.CANCELLED, repo.snapshot.first().summary(a)!!.job.status)
    }
}
