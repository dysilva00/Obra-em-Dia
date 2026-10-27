package pt.obraemdia.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import pt.obraemdia.app.container
import pt.obraemdia.app.ui.AppViewModel
import pt.obraemdia.app.ui.accounts.AccountsScreen
import pt.obraemdia.app.ui.agenda.AgendaScreen
import pt.obraemdia.app.ui.components.DemoBanner
import pt.obraemdia.app.ui.forms.NewJobScreen
import pt.obraemdia.app.ui.forms.RecordPaymentScreen
import pt.obraemdia.app.ui.forms.RecordTimeScreen
import pt.obraemdia.app.ui.home.HomeScreen
import pt.obraemdia.app.ui.jobs.JobDetailScreen
import pt.obraemdia.app.ui.jobs.JobsScreen
import kotlin.reflect.KClass

@Serializable data object HomeRoute
@Serializable data object AgendaRoute
@Serializable data object JobsRoute
@Serializable data object AccountsRoute
@Serializable data class JobDetailRoute(val jobId: String)
@Serializable data object NewJobRoute
@Serializable data class RecordTimeRoute(val jobId: String? = null)
@Serializable data class RecordPaymentRoute(val jobId: String? = null)

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, "Início", Icons.Filled.Home),
    Tab(AgendaRoute, AgendaRoute::class, "Agenda", Icons.Filled.CalendarMonth),
    Tab(JobsRoute, JobsRoute::class, "Obras", Icons.Filled.Construction),
    Tab(AccountsRoute, AccountsRoute::class, "Contas", Icons.Filled.AccountBalanceWallet),
)

private fun NavDestination?.title(): String = when {
    this == null -> ""
    hasRoute(NewJobRoute::class) -> "Nova obra"
    hasRoute(RecordTimeRoute::class) -> "Registar horas"
    hasRoute(RecordPaymentRoute::class) -> "Registar pagamento"
    hasRoute(JobDetailRoute::class) -> "Obra"
    else -> tabs.firstOrNull { hasRoute(it.routeClass) }?.label.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val vm: AppViewModel = viewModel { AppViewModel(context.container) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val agenda by vm.agenda.collectAsStateWithLifecycle()

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val currentTab = tabs.firstOrNull { tab -> destination?.hasRoute(tab.routeClass) == true }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val message: (String) -> Unit = { text ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }
    // Funcionalidades das próximas etapas: dizê-lo explicitamente em vez de simular sucesso.
    val notReady: (String) -> Unit = { action -> message("$action — ainda não disponível nesta versão") }
    val openJob: (String) -> Unit = { id -> nav.navigate(JobDetailRoute(id)) }
    val finished: (String) -> Unit = { text -> nav.popBackStack(); message(text) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(destination.title()) },
                    navigationIcon = {
                        if (currentTab == null && destination != null) {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                            }
                        }
                    },
                )
                if (snapshot?.hasDemoData == true) {
                    DemoBanner(onRemove = { scope.launch { message(vm.deleteDemoData().message) } })
                }
            }
        },
        bottomBar = {
            if (currentTab != null) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentTab == tab,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val snap = snapshot
        if (snap == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> {
                HomeScreen(
                    snap, agenda, vm.today, openJob,
                    onRecordTime = { nav.navigate(RecordTimeRoute()) },
                    onRecordPayment = { nav.navigate(RecordPaymentRoute()) },
                    onNotReady = notReady,
                )
            }
            composable<AgendaRoute> { AgendaScreen(snap, agenda, vm.today, openJob, notReady) }
            composable<JobsRoute> { JobsScreen(snap, openJob, onNewJob = { nav.navigate(NewJobRoute) }) }
            composable<AccountsRoute> { AccountsScreen(snap, vm.today, notReady) }
            composable<JobDetailRoute> { entry ->
                val jobId = entry.toRoute<JobDetailRoute>().jobId
                JobDetailScreen(
                    snap, jobId,
                    onRecordTime = { nav.navigate(RecordTimeRoute(jobId)) },
                    onRecordPayment = { nav.navigate(RecordPaymentRoute(jobId)) },
                    onNotReady = notReady,
                )
            }
            composable<NewJobRoute> {
                NewJobScreen(
                    vm,
                    onCreated = { id ->
                        nav.navigate(JobDetailRoute(id)) { popUpTo<NewJobRoute> { inclusive = true } }
                        message("Obra criada")
                    },
                    onError = message,
                )
            }
            composable<RecordTimeRoute> { entry -> RecordTimeScreen(vm, snap, entry.toRoute<RecordTimeRoute>().jobId, finished) }
            composable<RecordPaymentRoute> { entry -> RecordPaymentScreen(vm, snap, entry.toRoute<RecordPaymentRoute>().jobId, finished) }
        }
    }
}
