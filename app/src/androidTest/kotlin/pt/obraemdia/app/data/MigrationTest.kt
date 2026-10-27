package pt.obraemdia.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
