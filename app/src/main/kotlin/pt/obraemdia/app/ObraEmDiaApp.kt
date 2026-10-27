package pt.obraemdia.app

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pt.obraemdia.app.data.db.AppDatabase
import pt.obraemdia.app.data.repo.ObraRepository
import pt.obraemdia.app.demo.DemoSeeder

/**
 * Dependências da app, criadas uma vez. Injeção manual em vez de Hilt: a app é pequena
 * e assim não há geração de código adicional (ver docs/DECISIONS.md).
 */
class AppContainer(context: Context) {
    val database: AppDatabase = AppDatabase.build(context)
    val repository = ObraRepository(database)
    val demoSeeder = DemoSeeder(database, repository)
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
