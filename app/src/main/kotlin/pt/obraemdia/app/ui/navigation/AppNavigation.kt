package pt.obraemdia.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import pt.obraemdia.app.ui.accounts.AccountsScreen
import pt.obraemdia.app.ui.agenda.AgendaScreen
import pt.obraemdia.app.ui.components.DemoBanner
import pt.obraemdia.app.ui.home.HomeScreen
import pt.obraemdia.app.ui.jobs.JobDetailScreen
import pt.obraemdia.app.ui.jobs.JobsScreen
import kotlin.reflect.KClass

@Serializable data object HomeRoute
@Serializable data object AgendaRoute
@Serializable data object JobsRoute
@Serializable data object AccountsRoute
@Serializable data class JobDetailRoute(val jobId: String)

private data class Tab(val route: Any, val routeClass: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, HomeRoute::class, "Início", Icons.Filled.Home),
    Tab(AgendaRoute, AgendaRoute::class, "Agenda", Icons.Filled.CalendarMonth),
    Tab(JobsRoute, JobsRoute::class, "Obras", Icons.Filled.Construction),
    Tab(AccountsRoute, AccountsRoute::class, "Contas", Icons.Filled.AccountBalanceWallet),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val currentTab = tabs.firstOrNull { tab -> destination?.hasRoute(tab.routeClass) == true }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // No protótipo as ações ainda não gravam nada; dizê-lo explicitamente em vez de simular sucesso.
    val notReady: (String) -> Unit = { action ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar("$action — ainda não disponível neste protótipo")
        }
    }
    val openJob: (String) -> Unit = { id -> nav.navigate(JobDetailRoute(id)) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(currentTab?.label ?: "Obra") },
                    navigationIcon = {
                        if (currentTab == null) {
                            IconButton(onClick = { nav.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                            }
                        }
                    },
                )
                DemoBanner()
            }
        },
        bottomBar = {
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
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(padding)) {
            composable<HomeRoute> { HomeScreen(openJob, notReady) }
            composable<AgendaRoute> { AgendaScreen(openJob, notReady) }
            composable<JobsRoute> { JobsScreen(openJob, notReady) }
            composable<AccountsRoute> { AccountsScreen(notReady) }
            composable<JobDetailRoute> { entry -> JobDetailScreen(entry.toRoute<JobDetailRoute>().jobId, notReady) }
        }
    }
}
