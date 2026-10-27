package pt.obraemdia.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.files.AttachmentStore
import pt.obraemdia.app.data.repo.Agreement
import pt.obraemdia.app.data.repo.Media
import pt.obraemdia.app.data.repo.BusinessSettings
import pt.obraemdia.app.data.repo.Corrections
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.demo.DemoSeeder

/**
 * Dependências da app, criadas uma vez. Injeção manual em vez de Hilt: a app é pequena
 * e assim não há geração de código adicional (ver docs/DECISIONS.md).
 */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.build(context)
    val repository = ObraRepository(database)
    val corrections = Corrections(database, repository, System::currentTimeMillis)
    val agreement = Agreement(database, repository, System::currentTimeMillis)
    val settings = BusinessSettings(database, repository, System::currentTimeMillis)
    val attachments = AttachmentStore(context)
    val media = Media(database, repository, attachments, System::currentTimeMillis)
    val demoSeeder = DemoSeeder(database, repository) { paths -> paths.forEach(attachments::delete) }
    val appScope = CoroutineScope(SupervisorJob())
}

class ObraEmDiaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.demoSeeder.seedIfFirstRun() }
    }
}

val Context.container: AppContainer get() = (applicationContext as ObraEmDiaApp).container
