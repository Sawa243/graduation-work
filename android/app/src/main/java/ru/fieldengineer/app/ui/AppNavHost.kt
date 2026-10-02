package ru.fieldengineer.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import ru.fieldengineer.app.AppContainer
import ru.fieldengineer.app.data.repo.RequestRepository
import ru.fieldengineer.app.ui.act.ActScreen
import ru.fieldengineer.app.ui.act.ActViewModel
import ru.fieldengineer.app.ui.card.RequestCardScreen
import ru.fieldengineer.app.ui.card.RequestCardViewModel
import ru.fieldengineer.app.ui.checklist.ChecklistScreen
import ru.fieldengineer.app.ui.checklist.ChecklistViewModel
import ru.fieldengineer.app.ui.lock.PinSetupScreen
import ru.fieldengineer.app.ui.lock.PinSetupViewModel
import ru.fieldengineer.app.ui.login.LoginScreen
import ru.fieldengineer.app.ui.login.LoginViewModel
import ru.fieldengineer.app.ui.photo.PhotoScreen
import ru.fieldengineer.app.ui.photo.PhotoViewModel
import ru.fieldengineer.app.ui.requests.RequestListScreen
import ru.fieldengineer.app.ui.requests.RequestListViewModel
import ru.fieldengineer.app.ui.scanner.ScannerScreen
import ru.fieldengineer.app.ui.scanner.ScannerViewModel
import ru.fieldengineer.app.ui.signature.SignatureScreen
import ru.fieldengineer.app.ui.signature.SignatureViewModel
import ru.fieldengineer.app.ui.stats.StatsScreen
import ru.fieldengineer.app.ui.stats.StatsViewModel
import ru.fieldengineer.app.ui.sync.SyncScreen
import ru.fieldengineer.app.ui.sync.SyncViewModel

// Маршруты экранов (номера — по сценарию диалога в проекте системы, раздел 6)
@Serializable object LoginRoute                           // Э1
@Serializable object PinSetupRoute                        // шаг после Э1: «Задайте PIN»
@Serializable object RequestListRoute                     // Э2
@Serializable data class RequestCardRoute(val id: Int)    // Э3
@Serializable data class ChecklistRoute(val id: Int)      // Э4
@Serializable data class ScannerRoute(val id: Int)        // Э5
/** Э6: [itemId] = -1 — все фото работ заявки; [kind] 1 — фото работ, 2 — бумажный акт. */
@Serializable data class PhotosRoute(val id: Int, val itemId: Int = -1, val kind: Int = 1)
@Serializable data class SignatureRoute(val id: Int)      // Э7
@Serializable data class ActRoute(val id: Int)            // Э8
@Serializable object StatsRoute                           // Э9
@Serializable object SyncRoute                            // Э10

@Composable
fun AppNavHost(container: AppContainer) {
    val nav = rememberNavController()
    val auth = container.authRepository
    // вошёл, но PIN ещё не задан (приложение закрыли на этом шаге) — снова «Задайте PIN»
    val start: Any = when {
        !auth.isLoggedIn -> LoginRoute
        auth.isPinRequired -> PinSetupRoute
        else -> RequestListRoute
    }

    // выход (кнопкой на Э10 или потому, что сервер завершил сессию) — на Э1, стек экранов очищается
    val loggedIn by auth.loggedIn.collectAsStateWithLifecycle()
    LaunchedEffect(loggedIn) {
        val current = nav.currentDestination ?: return@LaunchedEffect
        if (!loggedIn && !current.hasRoute<LoginRoute>()) {
            nav.navigate(LoginRoute) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable<LoginRoute> {
            val vm = viewModel { LoginViewModel(auth) }
            // после входа по паролю — всегда задать PIN, и только потом список заявок
            LoginScreen(vm, onLoggedIn = {
                nav.navigate(PinSetupRoute) { popUpTo(LoginRoute) { inclusive = true } }
            })
        }
        composable<PinSetupRoute> {
            val vm = viewModel { PinSetupViewModel(auth) }
            PinSetupScreen(vm, onDone = {
                nav.navigate(RequestListRoute) { popUpTo(PinSetupRoute) { inclusive = true } }
            })
        }
        composable<RequestListRoute> {
            val vm = viewModel { RequestListViewModel(auth, container.requestRepository, container.trustedClock) }
            RequestListScreen(
                vm,
                onOpen = { nav.navigate(RequestCardRoute(it)) },
                onStats = { nav.navigate(StatsRoute) },
                onSync = { nav.navigate(SyncRoute) },
            )
        }
        composable<RequestCardRoute> { entry ->
            val id = entry.toRoute<RequestCardRoute>().id
            val vm = viewModel {
                RequestCardViewModel(id, auth.currentEmployeeId, container.requestRepository, container.trustedClock)
            }
            RequestCardScreen(
                vm, onBack = { nav.popBackStack() },
                onChecklist = { nav.navigate(ChecklistRoute(id)) },
                onScanner = { nav.navigate(ScannerRoute(id)) },
                onPhotos = { nav.navigate(PhotosRoute(id)) },
                onAct = { nav.navigate(ActRoute(id)) },
            )
        }
        composable<ChecklistRoute> { entry ->
            val id = entry.toRoute<ChecklistRoute>().id
            val vm = viewModel { ChecklistViewModel(id, container.requestRepository) }
            ChecklistScreen(vm, onBack = { nav.popBackStack() }, onPhotos = { item -> nav.navigate(PhotosRoute(id, item)) })
        }
        composable<ScannerRoute> { entry ->
            val id = entry.toRoute<ScannerRoute>().id
            val vm = viewModel { ScannerViewModel(id, container.requestRepository) }
            ScannerScreen(vm, onBack = { nav.popBackStack() })
        }
        composable<PhotosRoute> { entry ->
            val route = entry.toRoute<PhotosRoute>()
            val vm = viewModel {
                PhotoViewModel(route.id, route.itemId.takeIf { it > 0 }, route.kind, container.requestRepository,
                    container.fileStore, createSavedStateHandle())
            }
            PhotoScreen(vm, onBack = { nav.popBackStack() })
        }
        composable<SignatureRoute> { entry ->
            val id = entry.toRoute<SignatureRoute>().id
            val vm = viewModel {
                SignatureViewModel(id, auth.currentEmployeeId, container.requestRepository, container.fileStore,
                    container.pinStore, container.actPdfWriter, lockOut = auth::lockOut)
            }
            SignatureScreen(vm, onBack = { nav.popBackStack() }, onSigned = { nav.popBackStack() })
        }
        composable<ActRoute> { entry ->
            val id = entry.toRoute<ActRoute>().id
            val vm = viewModel {
                ActViewModel(id, auth.currentEmployeeId, container.requestRepository, container.fileStore, container.actPdfWriter)
            }
            ActScreen(
                vm, container.fileStore,
                onBack = { nav.popBackStack() },
                onSign = { nav.navigate(SignatureRoute(id)) },
                onPaperPhoto = { nav.navigate(PhotosRoute(id, kind = RequestRepository.PHOTO_PAPER_ACT)) },
                // после закрытия — обратно к списку заявок (сценарий диалога: Э8 → Э2)
                onClosed = { nav.popBackStack(RequestListRoute, inclusive = false) },
            )
        }
        composable<StatsRoute> {
            val vm = viewModel { StatsViewModel(auth.currentEmployeeId, container.requestRepository, container.trustedClock) }
            StatsScreen(vm, onBack = { nav.popBackStack() })
        }
        composable<SyncRoute> {
            val vm = viewModel {
                SyncViewModel(auth, container.syncScheduler, container.syncRepository, container.syncStore,
                    container.database.syncDao().observeQueue())
            }
            SyncScreen(vm, onBack = { nav.popBackStack() })
        }
    }
}
