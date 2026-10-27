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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.repo.AgendaFacts
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.toBookings
import pt.obraemdia.app.data.repo.toUnavailabilities
import pt.obraemdia.app.demo.DemoSeeder
import pt.obraemdia.app.reminders.ReminderPrefs
import pt.obraemdia.app.reminders.Reminders
import pt.obraemdia.domain.schedule.AGENDA_ZONE
import pt.obraemdia.domain.schedule.ConflictDetector
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class RemindersTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private val today = LocalDate.of(2026, 10, 27)
    private val on = ReminderPrefs(enabled = true)

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        DemoSeeder(db, repo).seedIfFirstRun(today)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun agenda(): AgendaFacts {
        val from = today.minusDays(1).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        val to = today.plusDays(8).atStartOfDay(AGENDA_ZONE).toInstant().toEpochMilli()
        val (rows, off) = repo.schedule(from, to).first()
        val bookings = rows.toBookings()
        return AgendaFacts(bookings.map { it to ConflictDetector().conflictsFor(it, bookings, off.toUnavailabilities()) })
    }

    @Test
    fun resumo_inclui_amanha_vencidos_e_sobreposicoes_mas_nao_dados_em_falta() = runTest {
        val d = Reminders.digest(repo.snapshot.first(), agenda(), today, on)
        assertTrue(d.lines.any { it.startsWith("Amanhã 08:00: Casa de banho") })
        assertTrue(d.lines.any { it.startsWith("Prestação vencida") })
        assertTrue(d.lines.any { it.startsWith("Sobreposição na agenda") })
        assertFalse("dados em falta ficam só na app", d.lines.any { it.contains("Sem preço") })
        assertEquals("Obra em Dia: ${d.lines.size} avisos", d.title)
    }

    @Test
    fun cada_tipo_pode_ser_desligado() = runTest {
        val d = Reminders.digest(repo.snapshot.first(), agenda(), today, on.copy(tomorrow = false, overdue = false, conflicts = false, holdReview = false))
        assertTrue(d.isEmpty)
    }

    @Test
    fun nao_repete_o_mesmo_resumo() = runTest {
        val snap = repo.snapshot.first()
        val d = Reminders.digest(snap, agenda(), today, on)
        assertTrue(Reminders.shouldNotify(d, snap.settings))
        assertFalse(Reminders.shouldNotify(d, snap.settings + (ReminderPrefs.KEY_LAST_DIGEST to d.signature)))
    }

    @Test
    fun preferencias_ida_e_volta() {
        val p = ReminderPrefs(enabled = true, time = LocalTime.of(19, 0), tomorrow = false, overdue = true, holdReview = false, conflicts = true)
        assertEquals(p, ReminderPrefs.from(p.toSettings()))
        assertFalse(ReminderPrefs.from(emptyMap()).enabled) // desligado por omissão
    }
}
