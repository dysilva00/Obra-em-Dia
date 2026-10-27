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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.BusinessSettings
import pt.obraemdia.app.data.repo.HourChecks
import pt.obraemdia.app.data.repo.HourWarningsException
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.SaveOutcome
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.app.ui.forms.parseClock
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.Priority
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class TimeTrackingTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private val day = LocalDate.of(2030, 3, 4)

    @Before
    fun setUp() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
        db.workerDao().upsert(WorkerEntity("w2", "Pessoa 2", 2000, 1500, 1, 0, 0))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun job(title: String = "Obra", phone: String? = null) =
        repo.createJob(NewJob(title, "Cliente", null, BillingMode.HOURLY, MaterialsPolicy.INCLUDED, null, 0, Priority.NORMAL, null, null, clientPhone = phone))

    private fun times(op: String, job: String, who: List<String>, start: String, end: String, pause: Int, confirm: Boolean = false, timer: String? = null) =
        NewTimeEntries(op, job, who, day, 0, TimeEntryKind.WORK, true, null, parseClock(start), parseClock(end), pause, confirm, timer)

    @Test
    fun inicio_fim_e_pausa_calculam_a_duracao() = runTest {
        val a = job()
        assertEquals(SaveOutcome.Saved, repo.recordTime(times(newId(), a, listOf("w1"), "8:00", "17:30", 60)))
        val e = repo.snapshot.first().timeEntries.single()
        assertEquals(510, e.minutes)
        assertEquals(60, e.breakMinutes)
        assertEquals(HourChecks.instant(day, LocalTime.of(8, 0)), e.startMs)

        try {
            repo.recordTime(times(newId(), a, listOf("w1"), "17:00", "8:00", 0))
            fail("Fim antes do início devia falhar")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun sobreposicao_pede_confirmacao_e_repetir_nao_se_acusa() = runTest {
        val a = job("Cozinha")
        val b = job("Telhado")
        val op = newId()
        repo.recordTime(times(op, a, listOf("w1"), "8:00", "12:00", 0))
        // Repetir a mesma operação: é duplicado, não sobreposição.
        assertEquals(SaveOutcome.Duplicate, repo.recordTime(times(op, a, listOf("w1"), "8:00", "12:00", 0)))

        try {
            repo.recordTime(times(newId(), b, listOf("w1", "w2"), "11:00", "13:00", 0))
            fail("Devia avisar da sobreposição")
        } catch (e: HourWarningsException) {
            assertEquals(listOf("Pessoa 1 já tem horas das 08:00 às 12:00 neste dia"), e.warnings)
        }
        // A outra pessoa não tem conflito; e com confirmação grava-se para as duas.
        assertEquals(SaveOutcome.Saved, repo.recordTime(times(newId(), b, listOf("w2"), "11:00", "13:00", 0)))
        assertEquals(SaveOutcome.Saved, repo.recordTime(times(newId(), b, listOf("w1"), "11:00", "13:00", 0, confirm = true)))
        assertEquals(3, repo.snapshot.first().timeEntries.size)
    }

    @Test
    fun cronometro_um_por_pessoa_e_parar_regista() = runTest {
        val a = job()
        val group = repo.startTimer(a, listOf("w1", "w2"))
        try {
            repo.startTimer(a, listOf("w2"))
            fail("Só um cronómetro por pessoa")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Pessoa 2"))
        }
        // Não se remove uma pessoa com o cronómetro a contar.
        try {
            BusinessSettings(db, repo, System::currentTimeMillis).removeWorker("w2")
            fail("Tem cronómetro a contar")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("cronómetro"))
        }

        repo.recordTime(times(newId(), a, listOf("w1", "w2"), "9:00", "11:15", 0, timer = group))
        assertTrue(repo.timers.first().isEmpty())
        assertEquals(listOf(135, 135), repo.snapshot.first().timeEntries.map { it.minutes })

        val other = repo.startTimer(a, listOf("w1"))
        repo.discardTimer(other)
        assertTrue(repo.timers.first().isEmpty())
        assertEquals(2, repo.snapshot.first().timeEntries.size)
    }

    @Test
    fun telefone_do_cliente_fica_na_obra_nova() = runTest {
        job(phone = " 912 345 678 ")
        assertEquals("912 345 678", db.clientDao().observeAll().first().single().phone)
    }

    @Test
    fun ler_horas_escritas_de_varias_formas() {
        assertEquals(LocalTime.of(8, 0), parseClock("8"))
        assertEquals(LocalTime.of(8, 30), parseClock("8:30"))
        assertEquals(LocalTime.of(8, 30), parseClock("8h30"))
        assertEquals(LocalTime.of(8, 5), parseClock("0805"))
        assertEquals(LocalTime.of(17, 0), parseClock("17h"))
        assertNull(parseClock("25:00"))
        assertNull(parseClock("abc"))
    }
}
