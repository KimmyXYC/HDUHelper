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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
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
import moe.nepnep.hduhelper.ui.components.AppTopBarIconButton
import moe.nepnep.hduhelper.ui.screens.AboutScreen
import moe.nepnep.hduhelper.ui.screens.AppearanceScreen
import moe.nepnep.hduhelper.ui.screens.ApplicationsScreen
import moe.nepnep.hduhelper.ui.screens.CampusCodeScreen
import moe.nepnep.hduhelper.ui.screens.LoginScreen
import moe.nepnep.hduhelper.ui.screens.ProfileScreen
import moe.nepnep.hduhelper.ui.screens.ScheduleEditorScreen
import moe.nepnep.hduhelper.ui.screens.ScheduleTopBar
import moe.nepnep.hduhelper.ui.screens.ScheduleRepeatScreen
import moe.nepnep.hduhelper.ui.screens.ScheduleReminderScreen
import moe.nepnep.hduhelper.ui.screens.ScheduleScreen
import moe.nepnep.hduhelper.ui.screens.TimetableScreen
import moe.nepnep.hduhelper.ui.screens.TimetableTopBar
import moe.nepnep.hduhelper.ui.screens.TimetableSettingsScreen
import moe.nepnep.hduhelper.ui.screens.VerificationCookies
import moe.nepnep.hduhelper.ui.screens.VerificationScreen
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

@Composable
fun HDUHelperApp(
    model: AppViewModel,
    campusModel: CampusCodeViewModel,
    timetableModel: TimetableViewModel,
    scheduleModel: ScheduleViewModel,
    examsModel: ExamsViewModel,
    modifier: Modifier = Modifier,
    scheduleLink: android.net.Uri? = null,
    onScheduleLinkConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "main"
    val auth by model.authState.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val form by model.loginForm.collectAsStateWithLifecycle()
    val verificationError by model.verificationError.collectAsStateWithLifecycle()
    val actionError by model.actionError.collectAsStateWithLifecycle()
    val campusState by campusModel.state.collectAsStateWithLifecycle()
    val timetableState by timetableModel.state.collectAsStateWithLifecycle()
    val scheduleState by scheduleModel.state.collectAsStateWithLifecycle()
    val scheduleEditor by scheduleModel.editor.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val gradesModel: GradesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = GradesViewModel.factory((context.applicationContext as moe.nepnep.hduhelper.HDUHelperApplication).container))
    val electricModel: ElectricViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = ElectricViewModel.factory((context.applicationContext as moe.nepnep.hduhelper.HDUHelperApplication).container))
    val electricState by electricModel.state.collectAsStateWithLifecycle()
    val gradesState by gradesModel.state.collectAsStateWithLifecycle()
    val examsState by examsModel.state.collectAsStateWithLifecycle()
    val notificationModel: NotificationSettingsViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = NotificationSettingsViewModel.factory((context.applicationContext as moe.nepnep.hduhelper.HDUHelperApplication).container))
    val notificationStatus by notificationModel.status.collectAsStateWithLifecycle()
    val backgroundStatus by notificationModel.background.collectAsStateWithLifecycle()
    val updateModel: UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = UpdateViewModel.factory((context.applicationContext as moe.nepnep.hduhelper.HDUHelperApplication).container))
    val updateState by updateModel.state.collectAsStateWithLifecycle()
    val updateUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    moe.nepnep.hduhelper.ui.components.UpdateDialog(updateState, updateModel::dismiss) { url ->
        try { updateUriHandler.openUri(url); updateModel.dismiss() }
        catch (_: android.content.ActivityNotFoundException) { updateModel.browserFailed() }
        catch (_: IllegalArgumentException) { updateModel.browserFailed() }
        catch (_: SecurityException) { updateModel.browserFailed() }
    }
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var destination by rememberSaveable { mutableStateOf(AppDestination.SCHEDULE) }
    var loginReturnDestination by rememberSaveable { mutableStateOf(AppDestination.PROFILE) }
    var loginReturnToTimetableSettings by rememberSaveable { mutableStateOf(false) }
    var loginReturnToElectric by rememberSaveable { mutableStateOf(false) }
    var loginReturnToGrades by rememberSaveable { mutableStateOf(false) }
    var loginReturnToExams by rememberSaveable { mutableStateOf(false) }
    val pageStateHolder = rememberSaveableStateHolder()
    val sensitive = route == "login" || route == "verification"

    LifecycleStartEffect(Unit) {
        updateModel.setForeground(true)
        model.onForeground()
        notificationModel.foreground()
        scheduleModel.foreground()
        onStopOrDispose { model.onBackground(); updateModel.setForeground(false) }
    }
    LifecycleStartEffect(route, destination) {
        campusModel.setVisible(route == "main" && destination == AppDestination.CAMPUS_CODE)
        onStopOrDispose { campusModel.setVisible(false) }
    }
    LifecycleStartEffect(route, destination) {
        timetableModel.setVisible(route == "timetable_settings" || route == "main" && destination == AppDestination.TIMETABLE)
        onStopOrDispose { timetableModel.setVisible(false) }
    }
    LifecycleStartEffect(route, destination) {
        scheduleModel.setVisible(route == "main" && destination == AppDestination.SCHEDULE)
        onStopOrDispose { scheduleModel.setVisible(false) }
    }
    LifecycleStartEffect(route) {
        examsModel.setVisible(route == "exams")
        onStopOrDispose { examsModel.setVisible(false) }
    }
    LifecycleStartEffect(route) {
        gradesModel.setVisible(route == "grades")
        onStopOrDispose { gradesModel.setVisible(false) }
    }
    LifecycleStartEffect(route) {
        electricModel.setVisible(route == "electric")
        onStopOrDispose { electricModel.setVisible(false) }
    }
    LaunchedEffect(scheduleLink) {
        scheduleLink?.let { link ->
            val parts = link.pathSegments
            if (link.scheme == "hduhelper" && link.host == "schedule" && parts.size == 3) {
                val original = runCatching { java.time.LocalDate.parse(parts[1]) }.getOrNull()
                val date = runCatching { java.time.LocalDate.parse(parts[2]) }.getOrNull()
                if (original != null && date != null) {
                    destination = AppDestination.SCHEDULE
                    if (sensitive) model.cancelLogin()
                    nav.popBackStack("main", false)
                    scheduleModel.openNotification(parts[0], original, date)
                }
            }
            if (link.scheme == "hduhelper" && link.host in setOf("course", "exam") && parts.size == 4) {
                val date = runCatching { java.time.LocalDate.parse(parts[3]) }.getOrNull()
                if (date != null) {
                    destination = AppDestination.SCHEDULE
                    if (sensitive) model.cancelLogin()
                    nav.popBackStack("main", false)
                    scheduleModel.openCourseNotification(parts[0], parts[1], parts[2], date, exam = link.host == "exam")
                }
            }
            if (link.scheme == "hduhelper" && link.host == "agenda" && parts.size == 1) {
                runCatching { java.time.LocalDate.parse(parts[0]) }.getOrNull()?.let { date ->
                    destination = AppDestination.SCHEDULE
                    if (sensitive) model.cancelLogin()
                    nav.popBackStack("main", false)
                    scheduleModel.showDetail(null)
                    scheduleModel.selectDate(date)
                }
            }
            onScheduleLinkConsumed()
        }
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
                    AppEvent.LoggedIn -> {
                        destination = loginReturnDestination
                        when {
                            loginReturnToElectric && nav.popBackStack("electric", false) -> Unit
                            loginReturnToGrades && nav.popBackStack("grades", false) -> Unit
                            loginReturnToExams && nav.popBackStack("exams", false) -> Unit
                            loginReturnToTimetableSettings && nav.popBackStack("timetable_settings", false) -> Unit
                            else -> nav.popBackStack("main", false)
                        }
                        loginReturnToTimetableSettings = false
                        loginReturnToExams = false
                        loginReturnToGrades = false; loginReturnToElectric = false
                    }
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
        if (sensitive) { model.cancelLogin(); loginReturnToTimetableSettings = false; loginReturnToExams = false; loginReturnToGrades = false; loginReturnToElectric = false }
        nav.popBackStack()
        Unit
    }
    BackHandler(route == "main" && destination != AppDestination.SCHEDULE) { destination = AppDestination.SCHEDULE }

    NavHost(
        navController = nav, startDestination = "main", modifier = modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
        enterTransition = { slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } },
        exitTransition = { slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } },
        popEnterTransition = { slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 3 } },
        popExitTransition = { slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } },
    ) {
        composable("main") {
            Scaffold(
                topBar = {
                    if (destination == AppDestination.TIMETABLE) TimetableTopBar(timetableState, timetableModel::goToDefaultWeek, timetableModel::selectTerm, timetableModel::selectWeek)
                    else if (destination == AppDestination.SCHEDULE) ScheduleTopBar {
                        scheduleModel.add(); nav.navigate("schedule_edit") { launchSingleTop = true }
                    }
                    else TopAppBar(title = stringResource(destination.labelRes))
                },
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
                            AppDestination.SCHEDULE -> ScheduleScreen(scheduleState, scheduleModel::selectDate, scheduleModel::today,
                                onDetail = scheduleModel::showDetail,
                                onEdit = { occurrence, onlyThis -> scheduleModel.edit(occurrence, onlyThis); nav.navigate("schedule_edit") { launchSingleTop = true } },
                                onDelete = scheduleModel::delete, onRefresh = scheduleModel::refreshCourses, onRetryStorage = scheduleModel::retryStorage,
                                modifier = Modifier.fillMaxSize())
                            AppDestination.TIMETABLE -> TimetableScreen(timetableState, timetableModel::refresh, timetableModel::selectWeek,
                                onLogin = { loginReturnDestination = AppDestination.TIMETABLE; model.openLogin(); nav.navigate("login") { launchSingleTop = true } },
                                onVerify = { loginReturnDestination = AppDestination.TIMETABLE; model.openVerification() }, modifier = Modifier.fillMaxSize())
                            AppDestination.CAMPUS_CODE -> CampusCodeScreen(campusState, { campusModel.refresh() },
                                onLogin = { loginReturnDestination = AppDestination.CAMPUS_CODE; model.openLogin(); nav.navigate("login") { launchSingleTop = true } },
                                onVerify = { loginReturnDestination = AppDestination.CAMPUS_CODE; model.openVerification() }, modifier = Modifier.fillMaxSize())
                            AppDestination.APPLICATIONS -> ApplicationsScreen(Modifier.fillMaxSize(), onExams = {
                                nav.navigate("exams") { launchSingleTop = true }
                            }, onGrades = { nav.navigate("grades") { launchSingleTop = true } },
                                onElectric = {
                                    nav.navigate("electric") { launchSingleTop = true }
                                    if (auth.profile == null && auth.status != moe.nepnep.hduhelper.data.auth.AuthStatus.LOADING) {
                                        loginReturnDestination = AppDestination.APPLICATIONS; loginReturnToElectric = true
                                        model.openLogin(); nav.navigate("login") { launchSingleTop = true }
                                    }
                                })
                            AppDestination.PROFILE -> ProfileScreen(
                                auth,
                                onLogin = { loginReturnDestination = AppDestination.PROFILE; model.openLogin(); nav.navigate("login") { launchSingleTop = true } },
                                onAppearance = { nav.navigate("appearance") { launchSingleTop = true } },
                                onTimetableSettings = { nav.navigate("timetable_settings") { launchSingleTop = true } },
                                onNotificationSettings = { nav.navigate("notification_settings") { launchSingleTop = true } },
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
        composable("schedule_edit") { editorEntry ->
            // Clearing the draft must not remove the outgoing page before its slide finishes.
            // This snapshot belongs only to this entry and is released with the page.
            var lastEditorState by remember(editorEntry) { mutableStateOf(scheduleEditor) }
            SideEffect { scheduleEditor?.let { lastEditorState = it } }
            val editorState = scheduleEditor ?: lastEditorState
            if (scheduleEditor == null) LaunchedEffect(Unit) {
                // Navigation retains the outgoing editor during its exit animation.
                // A completed save has already popped it; never pop the main page again.
                if (nav.currentDestination?.route == "schedule_edit") nav.popBackStack()
            }
            if (editorState != null) ScheduleEditorScreen(editorState, scheduleModel::updateDraft,
                onSave = { reset -> scheduleModel.save(reset) { nav.popBackStack() } },
                needsExceptionReset = scheduleModel::needsExceptionReset,
                onBack = { scheduleModel.discardEditor(); nav.popBackStack() },
                reminderStatus = scheduleState.reminderStatus, onPermissionsChanged = scheduleModel::foreground,
                onChooseRepeat = { nav.navigate("schedule_repeat") { launchSingleTop = true } },
                onChooseReminder = { nav.navigate("schedule_reminder") { launchSingleTop = true } }, active = route == "schedule_edit")
        }
        for (choice in listOf("schedule_repeat", "schedule_reminder")) composable(choice) {
            val editorState = scheduleEditor
            if (editorState == null) LaunchedEffect(Unit) {
                if (nav.currentDestination?.route == choice) nav.popBackStack("main", false)
            } else if (choice == "schedule_repeat") ScheduleRepeatScreen(editorState.draft.repeat, { selected ->
                scheduleModel.updateDraft(editorState.draft.copy(repeat = selected)); nav.popBackStack()
            }, { nav.popBackStack() })
            else ScheduleReminderScreen(editorState.draft.reminderMinutes, { selected ->
                scheduleModel.updateDraft(editorState.draft.copy(reminderMinutes = selected)); nav.popBackStack()
            }, { nav.popBackStack() })
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
        composable("notification_settings") {
            SecondaryPage("通知设置", back) { pageModifier ->
                moe.nepnep.hduhelper.ui.screens.NotificationSettingsPage(settings.notifications, notificationStatus,
                    notificationModel::update, notificationModel::foreground, notificationModel::claimPermissionPrompt, pageModifier, notificationModel::testNotification,
                    backgroundStatus, settings.backgroundEnhancement, notificationModel::updateBackground)
            }
        }
        composable("appearance") {
            SecondaryPage("外观设置", back) { AppearanceScreen(settings.theme, model::setTheme, it) }
        }
        composable("timetable_settings") {
            SecondaryPage("课表设置", back) {
                TimetableSettingsScreen(timetableState, timetableModel::setSettings, timetableModel::setCampus, it,
                    onRefresh = timetableModel::refresh,
                    onLogin = {
                        loginReturnDestination = AppDestination.PROFILE; loginReturnToTimetableSettings = true
                        model.openLogin(); nav.navigate("login") { launchSingleTop = true }
                    },
                    onVerify = {
                        loginReturnDestination = AppDestination.PROFILE; loginReturnToTimetableSettings = true
                        model.openVerification()
                    })
            }
        }
        composable("exams") {
            moe.nepnep.hduhelper.ui.theme.ExamPageTheme {
                SecondaryPage("考试安排", back) {
                    moe.nepnep.hduhelper.ui.screens.ExamsScreen(examsState, examsModel::selectTerm, examsModel::refresh,
                        onLogin = {
                            loginReturnDestination = AppDestination.APPLICATIONS; loginReturnToExams = true
                            model.openLogin(); nav.navigate("login") { launchSingleTop = true }
                        },
                        onVerify = {
                            loginReturnDestination = AppDestination.APPLICATIONS; loginReturnToExams = true
                            model.openVerification()
                        }, modifier = it)
                }
            }
        }
        composable("grades") {
            moe.nepnep.hduhelper.ui.theme.ExamPageTheme {
                SecondaryPage("考试成绩", back) {
                    moe.nepnep.hduhelper.ui.screens.GradesScreen(gradesState, gradesModel::selectTerm, gradesModel::refresh,
                        onLogin = {
                            loginReturnDestination = AppDestination.APPLICATIONS; loginReturnToGrades = true
                            model.openLogin(); nav.navigate("login") { launchSingleTop = true }
                        },
                        onVerify = {
                            loginReturnDestination = AppDestination.APPLICATIONS; loginReturnToGrades = true
                            model.openVerification()
                        }, modifier = it)
                }
            }
        }
        composable("electric") {
            SecondaryPage("电费查询", back) { padding ->
                moe.nepnep.hduhelper.ui.screens.ElectricScreen(electricState, electricModel::refresh,
                    electricModel::edit, electricModel::closeEditor, electricModel::building, electricModel::floor,
                    electricModel::room, electricModel::bind, electricModel::unbind,
                    onLogin = {
                        loginReturnDestination = AppDestination.APPLICATIONS; loginReturnToElectric = true
                        model.openLogin(); nav.navigate("login") { launchSingleTop = true }
                    }, onVerify = {
                        loginReturnDestination = AppDestination.APPLICATIONS; loginReturnToElectric = true
                        model.openVerification()
                    }, modifier = padding)
            }
        }
        composable("about") { SecondaryPage("关于应用", back) { AboutScreen(it, updateState, updateModel::check) } }
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
                AppTopBarIconButton(onClick = onBack) { Icon(MiuixIcons.Back, contentDescription = "返回", modifier = it) }
            })
        },
    ) { padding -> content(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) }
}
