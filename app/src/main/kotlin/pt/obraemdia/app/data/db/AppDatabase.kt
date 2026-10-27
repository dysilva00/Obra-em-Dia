package pt.obraemdia.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate

class Converters {
    @TypeConverter fun fromDate(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun toDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}

@Database(
    version = 6,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
    entities = [
        WorkerEntity::class,
        ClientEntity::class,
        WorkSiteEntity::class,
        JobEntity::class,
        ScheduleEntryEntity::class,
        ScheduleAssignmentEntity::class,
        TimeOffEntity::class,
        TimeEntryEntity::class,
        CashMovementEntity::class,
        AuditEventEntity::class,
        SettingEntity::class,
        PurchaseDocumentEntity::class,
        CostAllocationEntity::class,
        JobClosureEntity::class,
        PriceRevisionEntity::class,
        JobChargeEntity::class,
        InstallmentEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun workerDao(): WorkerDao
    abstract fun clientDao(): ClientDao
    abstract fun jobDao(): JobDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun timeEntryDao(): TimeEntryDao
    abstract fun cashDao(): CashDao
    abstract fun auditDao(): AuditDao
    abstract fun settingsDao(): SettingsDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun closureDao(): ClosureDao
    abstract fun jobFactsDao(): JobFactsDao
    abstract fun agreementDao(): AgreementDao
    abstract fun demoDao(): pt.obraemdia.app.demo.DemoDao

    companion object {
        const val FILE_NAME = "obra-em-dia.db"

        /**
         * SQLite incluído na app (mesma versão em todos os aparelhos; permite VACUUM INTO no backup).
         * Sem fallbackToDestructiveMigration: uma migração em falta tem de falhar, nunca apagar dados.
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder<AppDatabase>(context, context.getDatabasePath(FILE_NAME).absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
