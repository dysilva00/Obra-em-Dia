package pt.obraemdia.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.db.AttachmentKind
import pt.obraemdia.app.data.db.ChecklistKind
import pt.obraemdia.app.data.files.AttachmentStore
import pt.obraemdia.app.data.repo.Media
import pt.obraemdia.app.data.repo.NewJob
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.demo.DemoSeeder
import pt.obraemdia.domain.billing.BillingMode
import pt.obraemdia.domain.billing.MaterialsPolicy
import pt.obraemdia.domain.job.Priority
import pt.obraemdia.domain.money.Money
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class MediaTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var repo: ObraRepository
    private lateinit var store: AttachmentStore
    private lateinit var media: Media
    private val day = LocalDate.of(2026, 10, 27)
    private val attachmentsDir get() = File(context.filesDir, AttachmentStore.DIR)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder<AppDatabase>(context)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        repo = ObraRepository(db)
        store = AttachmentStore(context)
        media = Media(db, repo, store, System::currentTimeMillis)
        attachmentsDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        attachmentsDir.deleteRecursively()
    }

    private suspend fun job() =
        repo.createJob(NewJob("Obra", null, null, BillingMode.FIXED_PRICE, MaterialsPolicy.INCLUDED, Money.euros(100), 0, Priority.NORMAL, null, null))

    /** Imagem de teste na pasta da câmara, partilhada por FileProvider como faria a câmara. */
    private fun cameraPhoto(w: Int, h: Int): android.net.Uri {
        val file = File(File(context.cacheDir, "camera").apply { mkdirs() }, "teste-$w-$h.jpg")
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(11, 93, 90)) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    @Test
    fun foto_e_copiada_reduzida_e_com_checksum() = runTest {
        val a = job()
        media.addAttachment(a, cameraPhoto(4000, 3000), AttachmentKind.BEFORE, "Antes", day)

        val saved = media.attachments(a).first().single()
        val file = store.file(saved.relativePath)
        assertTrue(saved.relativePath.startsWith("attachments/"))
        assertTrue(file.exists())
        assertEquals("image/jpeg", saved.mimeType)
        assertEquals(AttachmentStore.sha256(file), saved.sha256)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assertTrue("lado maior ≤ 2048", maxOf(bounds.outWidth, bounds.outHeight) <= 2048)
    }

    @Test
    fun se_a_gravacao_falha_o_ficheiro_copiado_e_apagado() = runTest {
        try {
            media.addAttachment("obra-inexistente", cameraPhoto(100, 100), AttachmentKind.DURING, null, day)
            fail("A chave estrangeira devia falhar")
        } catch (e: Exception) {
            // Tem de falhar na gravação (chave estrangeira), não na leitura da imagem.
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
        }
        assertTrue(attachmentsDir.listFiles().orEmpty().none { !it.name.startsWith(".") })
    }

    @Test
    fun notas_e_checklist() = runTest {
        val a = job()
        media.addNote(a, "Confirmar cor das juntas")
        val note = media.notes(a).first().single()
        media.updateNote(note.id, "Juntas cinzentas")
        assertEquals("Juntas cinzentas", media.notes(a).first().single().text)

        media.addItem(a, ChecklistKind.TASK, "Impermeabilizar")
        media.addItem(a, ChecklistKind.SHOPPING, "Silicone")
        val task = media.checklist(a).first().first { it.kind == ChecklistKind.TASK }
        media.toggleItem(task.id)
        assertTrue(media.checklist(a).first().first { it.id == task.id }.done)
        media.removeItem(task.id)
        assertEquals(listOf("Silicone"), media.checklist(a).first().map { it.text })

        media.removeNote(note.id)
        assertTrue(media.notes(a).first().isEmpty())
    }

    @Test
    fun remover_demonstracao_apaga_fotos_e_ficheiros_das_obras_ficticias() = runTest {
        val removed = mutableListOf<String>()
        val seeder = DemoSeeder(db, repo) { paths -> removed += paths; paths.forEach(store::delete) }
        seeder.seedIfFirstRun(day)
        val demoJob = repo.snapshot.first().jobs.first { it.job.isDemo }.job.id
        media.addAttachment(demoJob, cameraPhoto(200, 200), AttachmentKind.AFTER, null, day)
        val path = media.attachments(demoJob).first().single().relativePath

        seeder.deleteDemoData()

        assertEquals(listOf(path), removed)
        assertFalse(store.file(path).exists())
    }
}
