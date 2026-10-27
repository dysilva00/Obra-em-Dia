package pt.obraemdia.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.AttachmentEntity
import pt.obraemdia.app.data.db.AttachmentKind
import pt.obraemdia.app.data.db.TimeEntryKind
import pt.obraemdia.app.data.db.WorkerEntity
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.NewTimeEntries
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.data.repo.Settings
import pt.obraemdia.app.data.repo.newId
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.Priority
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Backup → trocar de telemóvel (pasta vazia) → restauro, e pacotes que têm de ser rejeitados. */
@RunWith(AndroidJUnit4::class)
class BackupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "backup-test")
    private val paths = BackupPaths(File(root, "databases/obra.db"), File(root, "files"), File(root, "work"))
    private val manager = BackupManager(paths) { AppDatabase.build(context, it.absolutePath) }
    private val day = LocalDate.of(2030, 3, 4)

    @Before
    fun setUp() {
        root.deleteRecursively()
        paths.databaseFile.parentFile!!.mkdirs()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun open() = AppDatabase.build(context, paths.databaseFile.absolutePath)

    /** Base com uma obra, horas e uma foto; devolve o backup em memória. */
    private fun seedAndExport(): ByteArray = runBlocking {
        val db = open()
        val repo = ObraRepository(db)
        db.workerDao().upsert(WorkerEntity("w1", "Pessoa 1", 2500, 1500, 0, 0, 0))
        val job = repo.createJob(NewJob("Cozinha", null, null, BillingMode.HOURLY, MaterialsPolicy.INCLUDED, null, 0, Priority.NORMAL, null, null))
        repo.recordTime(NewTimeEntries(newId(), job, listOf("w1"), day, 240, TimeEntryKind.WORK, true, null))
        val photo = File(paths.attachmentsDir, "foto.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(5000) { it.toByte() }) }
        db.mediaDao().insertAttachment(
            AttachmentEntity(newId(), job, null, AttachmentKind.DURING, "attachments/foto.jpg", "image/jpeg", photo.length(), "x", null, day, 0),
        )
        val out = ByteArrayOutputStream()
        val manifest = manager.export(db, "test", 1_900_000_000_000, out)
        db.close()
        assertEquals(1L, manifest.counts["obras"])
        assertEquals(2, manifest.files.size)
        out.toByteArray()
    }

    @Test
    fun exportar_e_restaurar_noutro_telemovel() = runBlocking {
        val zip = seedAndExport()
        // "Telemóvel novo": sem base nem anexos.
        File(root, "databases").deleteRecursively()
        paths.attachmentsDir.deleteRecursively()
        paths.databaseFile.parentFile!!.mkdirs()

        val staged = manager.prepare(ByteArrayInputStream(zip))
        assertEquals(1L, staged.manifest.counts["horas"])
        manager.schedule(staged)
        manager.onStartup()
        assertTrue(manager.consumeResult()!!.startsWith("Dados restaurados"))

        val db = open()
        val snap = ObraRepository(db).snapshot.first()
        assertEquals(listOf("Cozinha"), snap.summaries.map { it.job.title })
        assertEquals(240, snap.timeEntries.single().minutes)
        assertEquals("1900000000000", snap.settings[Settings.LAST_BACKUP_AT])
        assertEquals(5000L, File(paths.filesDir, "attachments/foto.jpg").length())
        db.close()
    }

    @Test
    fun restauro_substitui_dados_e_guarda_os_anteriores() = runBlocking {
        val zip = seedAndExport()
        // Depois da cópia, mais uma obra e outra foto que não estão no backup.
        open().let { db ->
            ObraRepository(db).createJob(NewJob("Telhado", null, null, BillingMode.HOURLY, MaterialsPolicy.INCLUDED, null, 0, Priority.NORMAL, null, null))
            db.close()
        }
        File(paths.attachmentsDir, "nova.jpg").writeText("nova")

        manager.schedule(manager.prepare(ByteArrayInputStream(zip)))
        manager.onStartup()

        val db = open()
        assertEquals(listOf("Cozinha"), ObraRepository(db).snapshot.first().summaries.map { it.job.title })
        db.close()
        assertFalse(File(paths.attachmentsDir, "nova.jpg").exists())
        assertTrue(File(paths.rollbackDir, "attachments/nova.jpg").exists())
    }

    @Test
    fun restauro_interrompido_repoe_dados_atuais() = runBlocking {
        val zip = seedAndExport()
        open().let { db ->
            ObraRepository(db).createJob(NewJob("Telhado", null, null, BillingMode.HOURLY, MaterialsPolicy.INCLUDED, null, 0, Priority.NORMAL, null, null))
            db.close()
        }
        val staged = manager.prepare(ByteArrayInputStream(zip))
        // Simula o processo a morrer depois de os dados atuais saírem e o backup entrar,
        // mas antes de o journal ser apagado.
        manager.apply(staged)
        paths.journal.writeText("in")
        File(paths.rollbackDir, paths.databaseFile.name).exists().let { assertTrue(it) }

        manager.onStartup()
        assertTrue(manager.consumeResult()!!.contains("interrompido"))
        val db = open()
        assertEquals(setOf("Cozinha", "Telhado"), ObraRepository(db).snapshot.first().summaries.map { it.job.title }.toSet())
        db.close()
    }

    @Test
    fun rejeita_pacotes_invalidos_sem_tocar_nos_dados() {
        val zip = seedAndExport()
        val before = paths.databaseFile.readBytes().size

        fun rejected(bytes: ByteArray, vararg expected: String) {
            try {
                manager.prepare(ByteArrayInputStream(bytes))
                fail("Devia rejeitar: ${expected.first()}")
            } catch (e: BackupException) {
                assertTrue("'${e.message}' devia conter ${expected.toList()}", expected.any { e.message!!.contains(it) })
            }
        }

        // Ficheiro que não é ZIP.
        rejected("isto não é um zip".toByteArray(), "Não foi possível ler")
        // ZIP sem manifesto.
        rejected(rezip(zip) { name, _ -> if (name == BackupManager.MANIFEST) null else name to null }, "não é um backup")
        // Base alterada: checksum não confere.
        rejected(rezip(zip) { name, data -> name to if (name == BackupManager.DB_ENTRY) data.copyOf().also { it[200] = (it[200] + 1).toByte() } else null }, "corrompido")
        // Zip slip (o próprio Android 14+ já recusa abrir estes ZIP; a app recusa de qualquer forma).
        rejected(rezip(zip, extra = "../../fora.txt" to "x".toByteArray()) { name, _ -> name to null }, "caminhos inválidos", "Não foi possível ler")
        // Versão mais recente do esquema.
        rejected(rezip(zip) { name, data -> name to if (name == BackupManager.MANIFEST) data.decodeToString().replace(Regex("\"schemaVersion\": \\d+"), "\"schemaVersion\": 999").toByteArray() else null }, "mais recente")

        assertEquals(before, paths.databaseFile.readBytes().size)
        assertFalse(File(root, "fora.txt").exists())
    }

    /** Reescreve um ZIP: [transform] devolve (nome, novos bytes ou null para manter) ou null para omitir. */
    private fun rezip(
        zip: ByteArray,
        extra: Pair<String, ByteArray>? = null,
        transform: (String, ByteArray) -> Pair<String, ByteArray?>?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    val data = input.readBytes()
                    val (name, replaced) = transform(e.name, data) ?: continue
                    z.putNextEntry(ZipEntry(name)); z.write(replaced ?: data); z.closeEntry()
                }
            }
            extra?.let { (name, data) -> z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
        }
        return out.toByteArray()
    }
}
