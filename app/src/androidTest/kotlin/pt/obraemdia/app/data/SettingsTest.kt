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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.BookingOutcome
import pt.obraemdia.app.data.repo.BusinessSettings
import pt.obraemdia.app.data.repo.NewBooking
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.HourlyRate
import pt.obraemdia.domain.money.Money
import pt.obraemdia.domain.schedule.BookingFirmness
import pt.obraemdia.domain.schedule.DayPart
import pt.obraemdia.domain.schedule.WorkHours
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class SettingsTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private lateinit var settings: BusinessSettings
    private val day = LocalDate.of(2030, 3, 4)

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        settings = BusinessSettings(db, repo, System::currentTimeMillis)
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun job() =
        repo.createJob(NewJob("Obra", null, null, BillingMode.HOURLY, MaterialsPolicy.INCLUDED, null, 0, Priority.NORMAL, null, null))

    @Test
    fun mudar_nome_e_taxas_nao_altera_horas_ja_registadas() = runTest {
        val a = job()
        repo.recordTime(NewTimeEntries(newId(), a, listOf("w1"), day, 60, TimeEntryKind.WORK, true, null))
        settings.updateWorker("w1", "Joaquim", HourlyRate.euros(30), HourlyRate.euros(18))
        repo.recordTime(NewTimeEntries(newId(), a, listOf("w1"), day.plusDays(1), 60, TimeEntryKind.WORK, true, null))

        val snap = repo.snapshot.first()
        assertEquals("Joaquim", snap.workerName("w1"))
        assertEquals(listOf(2500L, 3000L), snap.timeEntries.sortedBy { it.date }.map { it.saleRateCents })
        assertEquals(Money.euros(55), snap.summary(a)!!.saleNet)
    }

    @Test
    fun saldo_inicial_margem_e_horario_ficam_gravados() = runTest {
        settings.saveBusiness(Money.euros(1234), 30, WorkHours(LocalTime.of(7, 30), LocalTime.of(12, 0), LocalTime.of(13, 0), LocalTime.of(16, 30)))
        val snap = repo.snapshot.first()
        assertEquals(Money.euros(1234), snap.openingBalance)
        assertEquals("30", snap.settings[pt.obraemdia.app.data.repo.Settings.TRAVEL_MARGIN_MINUTES])
        assertEquals(LocalTime.of(7, 30), snap.workHours.morningStart)
        assertEquals(java.time.Duration.ofHours(9), snap.workHours.slotFor(day, DayPart.FULL_DAY).duration)
        try {
            settings.saveBusiness(Money.ZERO, 999, snap.workHours)
            fail("Margem inválida devia falhar")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun indisponibilidade_conta_nos_conflitos_e_pode_ser_removida() = runTest {
        val a = job()
        val hours = WorkHours()
        repo.createBooking(NewBooking(newId(), a, setOf("w1"), listOf(hours.slotFor(day, DayPart.FULL_DAY)), BookingFirmness.FIRM, null, null))

        // Avisa da marcação que fica em conflito, sem a mover.
        assertEquals(1, settings.addTimeOff("w1", day, day.plusDays(2), "Férias"))
        val blocked = repo.createBooking(
            NewBooking(newId(), a, setOf("w1"), listOf(hours.slotFor(day.plusDays(1), DayPart.MORNING)), BookingFirmness.FIRM, null, null),
        )
        assertTrue(blocked is BookingOutcome.Conflicts)

        val off = settings.timeOff.first().single()
        settings.removeTimeOff(off.id)
        assertTrue(settings.timeOff.first().isEmpty())
        val ok = repo.createBooking(
            NewBooking(newId(), a, setOf("w1"), listOf(hours.slotFor(day.plusDays(1), DayPart.MORNING)), BookingFirmness.FIRM, null, null),
        )
        assertTrue(ok is BookingOutcome.Saved)
    }
}
