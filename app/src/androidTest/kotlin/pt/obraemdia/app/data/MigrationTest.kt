package pt.obraemdia.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pt.obraemdia.app.data.db.AppDatabase

/**
 * Cada nova versão do esquema acrescenta aqui um teste: os dados de uma base antiga
 * têm de sobreviver à migração. Nunca se apagam dados para resolver alterações de esquema.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = instrumentation.targetContext.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(instrumentation, file, BundledSQLiteDriver(), AppDatabase::class)

    // Cada teste parte de um ficheiro limpo (o helper com driver não o apaga entre testes).
    @Before
    fun clean() {
        listOf("", "-wal", "-shm", ".lck").forEach { java.io.File(file.path + it).delete() }
    }

    @Test
    fun v1_para_v2_preserva_obras_e_reservas() {
        helper.createDatabase(1).use { db ->
            db.execSQL("INSERT INTO workers (id, name, defaultSaleRateCents, defaultCostRateCents, sortOrder, createdAt, updatedAt, isDemo) VALUES ('w1', 'Pessoa 1', 2500, 1500, 0, 0, 0, 0)")
            db.execSQL(
                "INSERT INTO jobs (id, title, billingMode, materialsPolicy, status, priority, archived, createdAt, updatedAt, isDemo) " +
                    "VALUES ('j1', 'Obra antiga', 'FIXED_PRICE', 'INCLUDED', 'SCHEDULED', 'NORMAL', 0, 0, 0, 0)",
            )
            db.execSQL("INSERT INTO schedule_entries (id, jobId, startMs, endMs, firmness, createdAt, updatedAt, isDemo) VALUES ('e1', 'j1', 1000, 2000, 'FIRM', 0, 0, 0)")
            db.execSQL("INSERT INTO schedule_assignments (entryId, workerId) VALUES ('e1', 'w1')")
        }

        helper.runMigrationsAndValidate(2).use { db ->
            db.prepare("SELECT title FROM jobs WHERE id = 'j1'").use { st ->
                assertTrue(st.step())
                assertEquals("Obra antiga", st.getText(0))
            }
            db.prepare("SELECT jobId, operationId FROM schedule_entries WHERE id = 'e1'").use { st ->
                assertTrue(st.step())
                assertEquals("j1", st.getText(0))
                assertTrue("Reservas antigas ficam sem operationId", st.isNull(1))
            }
        }
    }

    @Test
    fun v3_para_v4_cria_revisoes_de_encerramento() {
        helper.createDatabase(3).close()
        helper.runMigrationsAndValidate(4).use { db ->
            db.prepare("SELECT COUNT(*) FROM job_closures").use { st ->
                assertTrue(st.step())
                assertEquals(0L, st.getLong(0))
            }
        }
    }

    @Test
    fun v2_para_v3_preserva_movimentos_e_cria_tabelas_de_compras() {
        helper.createDatabase(2).use { db ->
            db.execSQL(
                "INSERT INTO cash_movements (id, operationId, date, kind, amountCents, createdAt, isDemo) " +
                    "VALUES ('m1', 'op1', '2026-10-01', 'SUPPLIER_PAYMENT', 4500, 0, 0)",
            )
        }
        helper.runMigrationsAndValidate(3).use { db ->
            db.prepare("SELECT amountCents, purchaseDocumentId FROM cash_movements WHERE id = 'm1'").use { st ->
                assertTrue(st.step())
                assertEquals(4500L, st.getLong(0))
                assertTrue(st.isNull(1))
            }
            db.prepare("SELECT COUNT(*) FROM purchase_documents").use { st ->
                assertTrue(st.step())
                assertEquals(0L, st.getLong(0))
            }
        }
    }
}
