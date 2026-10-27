package pt.obraemdia.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pt.obraemdia.app.backup.BackupManager
import pt.obraemdia.app.backup.BackupPaths
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.files.AttachmentStore
import pt.obraemdia.app.data.repo.Agreement
import pt.obraemdia.app.data.repo.IssuedInvoices
import pt.obraemdia.app.data.repo.Media
import pt.obraemdia.app.data.repo.BusinessSettings
import pt.obraemdia.app.data.repo.Corrections
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.demo.DemoSeeder

/**
 * Dependências da app, criadas uma vez. Injeção manual em vez de Hilt: a app é pequena
 * e assim não há geração de código adicional (ver docs/DECISIONS.md).
 */
class AppContainer(context: Context, val backup: BackupManager) {
    val database: AppDatabase = AppDatabase.build(context)
    val repository = ObraRepository(database)
    val corrections = Corrections(database, repository, System::currentTimeMillis)
    val agreement = Agreement(database, repository, System::currentTimeMillis)
    val settings = BusinessSettings(database, repository, System::currentTimeMillis)
    val attachments = AttachmentStore(context)
    val media = Media(database, repository, attachments, System::currentTimeMillis)
    val invoices = IssuedInvoices(database, repository, attachments, System::currentTimeMillis)
    val demoSeeder = DemoSeeder(database, repository) { paths -> paths.forEach(attachments::delete) }
    val appScope = CoroutineScope(SupervisorJob())
    val appVersion: String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}

class ObraEmDiaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val backup = BackupManager(backupPaths(this)) { file -> AppDatabase.build(this, file.absolutePath) }
        // Antes de abrir a base: desfazer um restauro interrompido ou aplicar um pendente.
        backup.onStartup()
        container = AppContainer(this, backup)
        container.appScope.launch {
            container.demoSeeder.seedIfFirstRun()
            // Garante o lembrete diário agendado (ex.: depois de atualizar a app).
            val settings = pt.obraemdia.app.data.repo.Settings.toMap(container.database.settingsDao().observeAll().first())
            pt.obraemdia.app.reminders.ReminderWorker.schedule(this@ObraEmDiaApp, pt.obraemdia.app.reminders.ReminderPrefs.from(settings))
        }
    }
}

fun backupPaths(context: Context) = BackupPaths(
    databaseFile = context.getDatabasePath(AppDatabase.FILE_NAME),
    filesDir = context.filesDir,
    workDir = java.io.File(context.noBackupFilesDir, "backup"),
)

val Context.container: AppContainer get() = (applicationContext as ObraEmDiaApp).container
