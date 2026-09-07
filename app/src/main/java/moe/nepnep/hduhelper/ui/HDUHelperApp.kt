package moe.nepnep.hduhelper.ui

import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import moe.nepnep.hduhelper.ui.navigation.AppDestination
import moe.nepnep.hduhelper.ui.screens.AboutScreen
import moe.nepnep.hduhelper.ui.screens.AppearanceScreen
import moe.nepnep.hduhelper.ui.screens.ApplicationsScreen
import moe.nepnep.hduhelper.ui.screens.CampusCodeScreen
import moe.nepnep.hduhelper.ui.screens.LoginScreen
import moe.nepnep.hduhelper.ui.screens.ProfileScreen
import moe.nepnep.hduhelper.ui.screens.ScheduleScreen
import moe.nepnep.hduhelper.ui.screens.TimetableScreen
import moe.nepnep.hduhelper.ui.screens.VerificationCookies
import moe.nepnep.hduhelper.ui.screens.VerificationScreen
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

@Composable
fun HDUHelperApp(model: AppViewModel, campusModel: CampusCodeViewModel, modifier: Modifier = Modifier) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "main"
    val auth by model.authState.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val form by model.loginForm.collectAsStateWithLifecycle()
    val verificationError by model.verificationError.collectAsStateWithLifecycle()
    val actionError by model.actionError.collectAsStateWithLifecycle()
    val campusState by campusModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var destination by rememberSaveable { mutableStateOf(AppDestination.SCHEDULE) }
    var loginReturnDestination by rememberSaveable { mutableStateOf(AppDestination.PROFILE) }
    val pageStateHolder = rememberSaveableStateHolder()
    val sensitive = route == "login" || route == "verification"

    LifecycleStartEffect(Unit) {
        model.onForeground()
        onStopOrDispose { model.onBackground() }
    }
    LifecycleStartEffect(route, destination) {
        campusModel.setVisible(route == "main" && destination == AppDestination.CAMPUS_CODE)
        onStopOrDispose { campusModel.setVisible(false) }
    }
    moe.nepnep.hduhelper.ui.components.CampusCodeBrightness(route == "main" && destination == AppDestination.CAMPUS_CODE)
    DisposableEffect(sensitive, activity) {
        val window = activity?.window
        val previouslySecure = (window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0
        if (sensitive) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (sensitive && !previouslySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(model) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.events.collect { event ->
                when (event) {
                    AppEvent.LoggedIn -> { destination = loginReturnDestination; nav.popBackStack("main", false) }
                    AppEvent.OpenVerification -> nav.navigate("verification") { launchSingleTop = true }
                    AppEvent.ClearWebSession -> VerificationCookies.clear()
                }
            }
        }
    }
    LaunchedEffect(auth.notice) {
        val notice = auth.notice ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
            model.acknowledgeNotice(notice)
        }
    }
    val back = {
        if (sensitive) model.cancelLogin()
        nav.popBackStack()
        Unit
    }
    BackHandler(route == "main" && destination != AppDestination.SCHEDULE) { destination = AppDestination.SCHEDULE }

    NavHost(
        navController = nav, startDestination = "main", modifier = modifier.fillMaxSize(),
        enterTransition = { slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } },
        exitTransition = { slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } },
        popEnterTransition = { slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } },
        popExitTransition = { slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } },
    ) {
        composable("main") {
            Scaffold(
                topBar = { TopAppBar(title = stringResource(destination.labelRes)) },
                bottomBar = {
                    NavigationBar {
                        AppDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item, onClick = { destination = item },
                                icon = item.icon, label = stringResource(item.labelRes),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                val pageModifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)
                AnimatedContent(
                    targetState = destination,
                    modifier = pageModifier,
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { it * direction } togetherWith
                            slideOutHorizontally(tween(250, easing = FastOutSlowInEasing)) { -it * direction }
                    },
                    label = "main_tab_transition",
                ) { page ->
                    pageStateHolder.SaveableStateProvider(page.name) {
                        when (page) {
                            AppDestination.SCHEDULE -> ScheduleScreen(Modifier.fillMaxSize())
                            AppDestination.TIMETABLE -> TimetableScreen(Modifier.fillMaxSize())
                            AppDestination.CAMPUS_CODE -> CampusCodeScreen(campusState, { campusModel.refresh() },
                                onLogin = { loginReturnDestination = AppDestination.CAMPUS_CODE; model.openLogin(); nav.navigate("login") { launchSingleTop = true } },
                                onVerify = { loginReturnDestination = AppDestination.CAMPUS_CODE; model.openVerification() }, modifier = Modifier.fillMaxSize())
                            AppDestination.APPLICATIONS -> ApplicationsScreen(Modifier.fillMaxSize())
                            AppDestination.PROFILE -> ProfileScreen(
                                auth,
                                onLogin = { loginReturnDestination = AppDestination.PROFILE; model.openLogin(); nav.navigate("login") { launchSingleTop = true } },
                                onAppearance = { nav.navigate("appearance") { launchSingleTop = true } },
                                onLogout = model::logout,
                                onAbout = { nav.navigate("about") { launchSingleTop = true } },
                                onVerify = { loginReturnDestination = AppDestination.PROFILE; model.openVerification() },
                                modifier = Modifier.fillMaxSize(), actionError = actionError,
                            )
                        }
                    }
                }
            }
        }
        composable("login") {
            SecondaryPage("账号登录", back) { pageModifier ->
                LoginScreen(form, model::setAccount, model::setPassword, model::setLoginAutoLogin, model::login, model::openVerification, pageModifier)
            }
        }
        composable("verification") {
            SecondaryPage("官方登录", back, resizeForIme = false) { pageModifier ->
                VerificationScreen(model.verificationSession(), verificationError, model::verificationFinished, model::verificationFailed, pageModifier)
            }
        }
        composable("appearance") {
            SecondaryPage("外观设置", back) { AppearanceScreen(settings.theme, model::setTheme, it) }
        }
        composable("about") { SecondaryPage("关于应用", back) { AboutScreen(it) } }
    }
}

@Composable
private fun SecondaryPage(title: String, onBack: () -> Unit, resizeForIme: Boolean = true, content: @Composable (Modifier) -> Unit) {
    BackHandler { onBack() }
    val window = LocalActivity.current?.window
    DisposableEffect(window, resizeForIme) {
        val previousMode = window?.attributes?.softInputMode
        if (!resizeForIme) window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        onDispose { if (!resizeForIme && previousMode != null) window.setSoftInputMode(previousMode) }
    }
    Scaffold(
        modifier = if (resizeForIme) Modifier.imePadding() else Modifier,
        topBar = {
            TopAppBar(title, navigationIcon = {
                IconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回") }
            })
        },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) }
}
