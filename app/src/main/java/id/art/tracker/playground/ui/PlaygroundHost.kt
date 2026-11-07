package id.art.tracker.playground.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import id.art.tracker.ARTracker
import id.art.tracker.BuildConfig
import id.art.tracker.R
import id.art.tracker.playground.ProcessRestarter
import id.art.tracker.playground.boot.BootState
import id.art.tracker.playground.boot.EndpointBadge
import id.art.tracker.playground.boot.SharedPrefsBackendGrantStore
import id.art.tracker.playground.inspector.ItemSource
import id.art.tracker.playground.labs.LabRegistry
import id.art.tracker.playground.labs.LabSection
import id.art.tracker.playground.settings.EndpointProfile
import id.art.tracker.playground.settings.SharedPrefsSettingsStore
import id.art.tracker.playground.labs.CrashNote
import id.art.tracker.playground.scenario.ActivityHolder
import id.art.tracker.playground.scenario.AppScenarioContext
import id.art.tracker.playground.scenario.ScenarioHost
import id.art.tracker.playground.scenario.shop.Cart
import id.art.tracker.playground.scenario.shop.Checkout
import id.art.tracker.playground.scenario.shop.OnboardingScreen
import id.art.tracker.playground.scenario.shop.PaymentResult
import id.art.tracker.playground.scenario.shop.ProductDetail
import id.art.tracker.playground.scenario.shop.ProductList
import id.art.tracker.playground.scenario.shop.ShopHome
import id.art.tracker.playground.scenario.shop.ShopRoutes
import id.art.tracker.playground.scenario.shop.StuckScreen
import id.art.tracker.playground.labs.SharedPrefsNoteStore
import id.art.tracker.playground.status.PlaygroundControls
import id.art.tracker.playground.status.PlaygroundSessions
import id.art.tracker.playground.status.SdkStatus
import id.art.tracker.playground.status.SdkStatusRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Tab(val route: Route, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Route.Skenario, R.string.tab_skenario, Icons.Filled.PlayArrow),
    Tab(Route.Lab, R.string.tab_lab, Icons.Filled.Build),
    Tab(Route.Inspector, R.string.tab_inspector, Icons.Filled.Search),
    Tab(Route.Pengaturan, R.string.tab_pengaturan, Icons.Filled.Settings),
)

/** Shell: four tabs, a back button on detail screens, the always-visible status strip and a feedback snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaygroundHost() {
    val context = LocalContext.current
    val navController = rememberNavController()
    // Records a screen_view for every destination change (no-op until ARTracker.init succeeded).
    LaunchedEffect(navController) { ARTracker.trackNavController(navController) }

    val repo = remember {
        SdkStatusRepository(
            controls = PlaygroundControls.get(context),
        )
    }
    var status by remember { mutableStateOf<SdkStatus?>(null) }
    // diagnostics() reads the database: never on the main thread.
    LaunchedEffect(Unit) {
        while (isActive) {
            status = withContext(Dispatchers.IO) { repo.poll() }
            PlaygroundSessions.history.observe(status?.sessionId)
            delay(1_000)
        }
    }

    // Crash items are only sent after a relaunch: show the note the Crash lab left, once.
    var crashNote by rememberSaveable { mutableStateOf(CrashNote(SharedPrefsNoteStore(context)).consume()) }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val feedback = remember {
        Feedback { message ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(message)
            }
        }
    }

    val controls = PlaygroundControls.get(context)
    val replayFps = remember { SharedPrefsSettingsStore(context).load().replayFps }
    val scenarioHost = remember {
        ScenarioHost(
            scope = scope,
            contextFor = { scenario, gate ->
                AppScenarioContext(
                    proves = scenario.proves,
                    navigateTo = { navController.navigate(it) },
                    activity = { ActivityHolder.resumed },
                    replayOn = { controls.replayOn },
                    replayFps = { replayFps },
                    userGate = gate,
                    notes = { feedback.show(it) },
                )
            },
        )
    }

    val resolved = BootState.resolved
    val badge = resolved?.badge ?: EndpointBadge(EndpointProfile.SINK, "?", remote = false)

    val entry by navController.currentBackStackEntryAsState()
    val currentPath = entry?.destination?.route
    val isTopLevel = Route.topLevel.any { it.path == currentPath } || currentPath == null
    val detailTitle = if (currentPath == Route.LabDetail.path) {
        LabRegistry.byId(entry?.arguments?.getString(Route.LabDetail.ARG))?.titleRes?.let { stringResource(it) }
    } else {
        null
    }

    CompositionLocalProvider(LocalFeedback provides feedback) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(detailTitle ?: stringResource(R.string.app_name)) },
                    navigationIcon = {
                        if (!isTopLevel) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentPath == tab.route.path || (tab.route == Route.Lab && currentPath == Route.LabDetail.path),
                            onClick = {
                                navController.navigate(tab.route.path) {
                                    popUpTo(Route.Skenario.path) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                StatusStrip(status, badge, resolved?.notice)
                crashNote?.let { note ->
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.crash_note, note), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { crashNote = null }) { Text(stringResource(R.string.action_close)) }
                        }
                    }
                }
                NavHost(navController, startDestination = Route.Skenario.path, modifier = Modifier.weight(1f)) {
                    composable(Route.Skenario.path) { ScenarioScreen(scenarioHost) }
                    composable(ShopRoutes.HOME) { ShopHome(onGo = { navController.navigate(it) }) }
                    composable(ShopRoutes.PRODUCTS) { ProductList(onGo = { navController.navigate(it) }) }
                    composable(ShopRoutes.PRODUCT) { ProductDetail(onGo = { navController.navigate(it) }) }
                    composable(ShopRoutes.CART) { Cart(onGo = { navController.navigate(it) }) }
                    composable(ShopRoutes.CHECKOUT) { Checkout(onGo = { navController.navigate(it) }) }
                    composable(ShopRoutes.RESULT) { PaymentResult() }
                    composable(ShopRoutes.ONBOARDING) { OnboardingScreen(onDone = { navController.popBackStack() }) }
                    composable(ShopRoutes.STUCK) { StuckScreen() }
                    composable(Route.Lab.path) { LabList(onOpen = { navController.navigate(Route.LabDetail.of(it)) }) }
                    composable(Route.Inspector.path) {
                        InspectorScreen(
                            mode = if (badge.profile == EndpointProfile.SINK) ItemSource.SENT_PAYLOAD else ItemSource.METADATA_ONLY,
                        )
                    }
                    composable(Route.Pengaturan.path) {
                        val settingsStore = remember { SharedPrefsSettingsStore(context) }
                        SettingsScreen(
                            initial = remember { settingsStore.load() },
                            store = settingsStore,
                            grants = remember { SharedPrefsBackendGrantStore(context) },
                            buildEndpoint = BuildConfig.ARTRACKER_ENDPOINT,
                            realKeyPresent = BuildConfig.ARTRACKER_KEY.isNotBlank(),
                            onRestart = { ProcessRestarter.restart(context) },
                        )
                    }
                    composable(Route.LabDetail.path) { backStack ->
                        LabRegistry.byId(backStack.arguments?.getString(Route.LabDetail.ARG))?.content?.invoke()
                    }
                }
            }
        }
    }
}

@Composable
private fun ComingSoon() {
    Box(Modifier.fillMaxSize()) { Text(stringResource(R.string.coming_soon), Modifier.padding(16.dp)) }
}

@Composable
private fun LabList(onOpen: (String) -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        LabSection.values().forEach { section ->
            val entries = LabRegistry.entries.filter { it.section == section }
            if (entries.isEmpty()) return@forEach
            item {
                Text(
                    stringResource(if (section == LabSection.LAB) R.string.section_lab else R.string.section_legacy),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(entries) { e ->
                OutlinedCard(
                    onClick = { e.onOpen?.invoke(context) ?: onOpen(e.id) },
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(e.titleRes), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(e.descriptionRes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
