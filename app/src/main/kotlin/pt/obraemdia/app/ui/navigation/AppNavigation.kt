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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.TopAppBarDefaults
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
import pt.obraemdia.app.ui.accounts.ForecastScreen
import pt.obraemdia.app.ui.agenda.AgendaScreen
import pt.obraemdia.app.ui.components.DemoBanner
import pt.obraemdia.app.ui.forms.BookingScreen
import pt.obraemdia.app.ui.forms.ClosureScreen
import pt.obraemdia.app.ui.forms.EditJobScreen
import pt.obraemdia.app.ui.forms.ExpenseScreen
import pt.obraemdia.app.ui.forms.NewJobScreen
import pt.obraemdia.app.ui.forms.RecordPaymentScreen
import pt.obraemdia.app.ui.forms.RecordTimeScreen
import pt.obraemdia.app.ui.home.HomeScreen
import pt.obraemdia.app.ui.jobs.JobDetailScreen
import pt.obraemdia.app.ui.jobs.JobsScreen
import pt.obraemdia.app.ui.settings.SettingsScreen
import kotlin.reflect.KClass

@Serializable data object HomeRoute
@Serializable data object AgendaRoute
@Serializable data object JobsRoute
@Serializable data object AccountsRoute
@Serializable data class JobDetailRoute(val jobId: String)
@Serializable data object NewJobRoute
@Serializable data class RecordTimeRoute(val jobId: String? = null, val timerGroupId: String? = null)
@Serializable data class RecordPaymentRoute(val jobId: String? = null)
@Serializable data class BookingRoute(val jobId: String? = null, val date: String? = null)
@Serializable data class ExpenseRoute(val jobId: String? = null)
@Serializable data class ClosureRoute(val jobId: String)
@Serializable data class EditJobRoute(val jobId: String)
@Serializable data object SettingsRoute
@Serializable data object ForecastRoute
@Serializable data object InvoicesRoute
@Serializable data object HistoryRoute
@Serializable data object BackupRoute
@Serializable data object QrScanRoute
@Serializable data class JobNotesRoute(val jobId: String)

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
    hasRoute(BookingRoute::class) -> "Agendar obra"
    hasRoute(ExpenseRoute::class) -> "Adicionar gasto"
    hasRoute(ClosureRoute::class) -> "Encerramento"
    hasRoute(EditJobRoute::class) -> "Editar obra"
    hasRoute(SettingsRoute::class) -> "Definições"
    hasRoute(ForecastRoute::class) -> "Projeções"
    hasRoute(InvoicesRoute::class) -> "Faturas emitidas"
    hasRoute(HistoryRoute::class) -> "Histórico de alterações"
    hasRoute(BackupRoute::class) -> "Cópia de segurança"
    hasRoute(QrScanRoute::class) -> "Ler código QR"
    hasRoute(JobNotesRoute::class) -> "Notas, fotos e checklist"
    hasRoute(JobDetailRoute::class) -> "Obra"
    else -> tabs.firstOrNull { hasRoute(it.routeClass) }?.label.orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppNavigation() {
    val context = LocalContext.current
    val vm: AppViewModel = viewModel { AppViewModel(context.container) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val agenda by vm.agenda.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val mediaCounts by vm.mediaCounts.collectAsStateWithLifecycle()
    val issuedInvoices by vm.issuedInvoices.collectAsStateWithLifecycle()
    val timers by vm.timers.collectAsStateWithLifecycle()

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
    // Resultado de um restauro aplicado no arranque.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.restoreResult?.let { snackbar.showSnackbar(it, withDismissAction = true) } }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(destination.title(), style = if (currentTab != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        if (currentTab == null && destination != null) {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                            }
                        }
                    },
                    actions = {
                        if (currentTab != null) {
                            IconButton(onClick = { nav.navigate(SettingsRoute) }) {
                                Icon(Icons.Filled.Settings, contentDescription = "Definições")
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
                ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    tabs.forEach { tab ->
                        ShortNavigationBarItem(
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
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@Scaffold
        }
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> {
                HomeScreen(
                    snap, agenda, vm.today, openJob,
                    onRecordTime = { nav.navigate(RecordTimeRoute()) },
                    onRecordPayment = { nav.navigate(RecordPaymentRoute()) },
                    onAddExpense = { nav.navigate(ExpenseRoute()) },
                    alerts = alerts,
                    onForecast = { nav.navigate(ForecastRoute) },
                    onBackup = { nav.navigate(BackupRoute) },
                    timers = timers,
                    onStopTimer = { group -> nav.navigate(RecordTimeRoute(timerGroupId = group)) },
                    onDiscardTimer = { group -> scope.launch { message(vm.discardTimer(group).message) } },
                )
            }
            composable<AgendaRoute> {
                AgendaScreen(snap, agenda, vm.today, openJob, onSchedule = { date -> nav.navigate(BookingRoute(date = date?.toString())) })
            }
            composable<JobsRoute> { JobsScreen(snap, openJob, onNewJob = { nav.navigate(NewJobRoute) }) }
            composable<AccountsRoute> {
                AccountsScreen(
                    snap, vm.today,
                    onAddExpense = { nav.navigate(ExpenseRoute()) },
                    onPaySupplier = vm::paySupplier,
                    onReverse = vm::reverseMovement,
                    onForecast = { nav.navigate(ForecastRoute) },
                    onInvoices = { nav.navigate(InvoicesRoute) },
                    invoicesNetThisYear = pt.obraemdia.domain.money.Money(issuedInvoices.filter { it.issueDate.year == vm.today.year }.sumOf { it.netCents }),
                    onVoidPurchase = vm::voidPurchase,
                    onMessage = message,
                    onNotReady = notReady,
                )
            }
            composable<JobDetailRoute> { entry ->
                val jobId = entry.toRoute<JobDetailRoute>().jobId
                JobDetailScreen(
                    snap, agenda, vm.today, jobId,
                    onRecordTime = { nav.navigate(RecordTimeRoute(jobId)) },
                    onRecordPayment = { nav.navigate(RecordPaymentRoute(jobId)) },
                    onSchedule = { nav.navigate(BookingRoute(jobId)) },
                    onAddExpense = { nav.navigate(ExpenseRoute(jobId)) },
                    onChangeStatus = { change -> vm.changeStatus(jobId, change) },
                    onReviewClosure = { nav.navigate(ClosureRoute(jobId)) },
                    onEdit = { nav.navigate(EditJobRoute(jobId)) },
                    onChangeAgreement = { c -> vm.changeAgreement(jobId, c) },
                    onAddCharge = vm::addCharge,
                    onCancelCharge = vm::cancelCharge,
                    onNotes = { nav.navigate(JobNotesRoute(jobId)) },
                    mediaCounts = mediaCounts[jobId],
                    onAddInstallment = { d, a, n -> vm.addInstallment(jobId, d, a, n) },
                    onRemoveInstallment = vm::removeInstallment,
                    onUpdateTime = vm::updateTimeEntry,
                    onDeleteTime = vm::deleteTimeEntry,
                    onMessage = message,
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
            composable<RecordTimeRoute> { entry ->
                val route = entry.toRoute<RecordTimeRoute>()
                RecordTimeScreen(vm, snap, route.jobId, route.timerGroupId, timers, finished)
            }
            composable<RecordPaymentRoute> { entry -> RecordPaymentScreen(vm, snap, entry.toRoute<RecordPaymentRoute>().jobId, finished) }
            composable<EditJobRoute> { entry ->
                val id = entry.toRoute<EditJobRoute>().jobId
                val clients by vm.clients.collectAsStateWithLifecycle()
                snap.summary(id)?.let { EditJobScreen(vm, it, clients, snap.isClosed(id), finished) }
            }
            composable<JobNotesRoute> { entry -> pt.obraemdia.app.ui.jobs.JobNotesScreen(vm.media, entry.toRoute<JobNotesRoute>().jobId, vm.today, message) }
            composable<BackupRoute> { pt.obraemdia.app.ui.settings.BackupScreen(vm, snap, message) }
            composable<HistoryRoute> {
                val events by vm.history.collectAsStateWithLifecycle()
                pt.obraemdia.app.ui.settings.HistoryScreen(events)
            }
            composable<InvoicesRoute> {
                val invoices by vm.issuedInvoices.collectAsStateWithLifecycle()
                val paths by vm.invoiceAttachments.collectAsStateWithLifecycle()
                val scanned by vm.scannedQr.collectAsStateWithLifecycle()
                pt.obraemdia.app.ui.invoices.InvoicesScreen(
                    vm, snap, invoices, paths, scanned,
                    onScanConsumed = { vm.scannedQr.value = null },
                    onScan = { nav.navigate(QrScanRoute) },
                    onMessage = message,
                )
            }
            composable<QrScanRoute> {
                pt.obraemdia.app.fiscal.QrScannerScreen(
                    onResult = { doc -> vm.scannedQr.value = doc; nav.popBackStack() },
                    onCancel = { nav.popBackStack() },
                )
            }
            composable<ForecastRoute> { ForecastScreen(vm, snap) }
            composable<SettingsRoute> {
                val timeOff by vm.timeOff.collectAsStateWithLifecycle()
                SettingsScreen(vm, snap, timeOff, message, onHistory = { nav.navigate(HistoryRoute) }, onBackup = { nav.navigate(BackupRoute) })
            }
            composable<ClosureRoute> { entry -> ClosureScreen(vm, snap, entry.toRoute<ClosureRoute>().jobId, message, finished) }
            composable<ExpenseRoute> { entry -> ExpenseScreen(vm, snap, entry.toRoute<ExpenseRoute>().jobId, onScan = { nav.navigate(QrScanRoute) }, onDone = finished) }
            composable<BookingRoute> { entry ->
                val route = entry.toRoute<BookingRoute>()
                BookingScreen(vm, snap, route.jobId, route.date?.let(java.time.LocalDate::parse), finished)
            }
        }
    }
}
