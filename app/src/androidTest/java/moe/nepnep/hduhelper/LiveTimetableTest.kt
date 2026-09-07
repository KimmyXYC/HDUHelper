package moe.nepnep.hduhelper

import android.net.LocalServerSocket
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.settings.AppSettings
import moe.nepnep.hduhelper.data.settings.AuthSettings
import moe.nepnep.hduhelper.data.timetable.*
import moe.nepnep.hduhelper.ui.TimetableViewModel
import moe.nepnep.hduhelper.ui.TimetableStatus
import moe.nepnep.hduhelper.ui.screens.TimetableScreen
import moe.nepnep.hduhelper.ui.screens.TimetableTopBar
import moe.nepnep.hduhelper.ui.theme.HDUHelperTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test

/** Opt-in success-path check. Real credentials and timetable data remain in this test's memory. */
class LiveTimetableTest {
    @get:Rule val compose=createComposeRule()
    @Test(timeout=300_000)
    fun nativeSsoTimetableCacheWeeksDetailsAndLogout()=runBlocking {
        val name=InstrumentationRegistry.getArguments().getString("liveAuthSocket")
        assumeNotNull(name)
        LocalServerSocket(name!!).use { server -> server.accept().use { socket ->
            socket.soTimeout=30_000
            val input=DataInputStream(socket.inputStream);val output=DataOutputStream(socket.outputStream)
            fun stage(message:String) {output.writeUTF(message);output.flush()}
            val size=input.readInt();require(size in 1..4096)
            val bytes=ByteArray(size).also {input.readFully(it)}
            val credentials=JSONObject(bytes.toString(Charsets.UTF_8));bytes.fill(0)
            val sessionStore=object:SessionStore {
                var value:StoredSession?=null
                override fun load()=value
                override fun save(session:StoredSession) {value=session}
                override fun clear() {value=null}
            }
            val authSettings=object:AuthSettings {
                override var autoLogin=true
                    private set
                override fun setAutoLogin(enabled:Boolean) {autoLogin=enabled}
            }
            var passwordLogins=0
            val api=HduAuthApi()
            val auth=AuthRepository(sessionStore,authSettings,AuthSessionFactory { cookies ->
                val session=api.create(cookies)
                object:AuthSession by session {
                    override suspend fun login(account:String,password:String):UserProfile {passwordLogins++;return session.login(account,password)}
                }
            })
            val cache=object:TimetableStore {
                val entries=mutableMapOf<String,TimetableData>()
                @Synchronized override fun load(account:String,term:AcademicTerm?)=entries.values.filter {it.account==account&&(term==null||it.term.key==term.key)}.maxByOrNull {it.updatedAt}
                @Synchronized override fun save(data:TimetableData) {entries[data.term.key]=data}
                @Synchronized override fun clear() {entries.clear()}
            }
            val source=TimetableRepository(auth,cache)
            auth.onSessionInvalidated(source::clear)
            val owners=ViewModelStore()
            try {
                auth.login(credentials.getString("account"),credentials.getString("password"),true)
                credentials.remove("account");credentials.remove("password")
                stage("原生登录成功，开始复用 SSO 建立教务会话")
                val net=MutableStateFlow(true);val prefs=MutableStateFlow(AppSettings())
                val model=withContext(Dispatchers.Main) {
                    TimetableViewModel(source,auth.state,auth.sessionGeneration,net,prefs,{_,_->null},{prefs.value=prefs.value.copy(timetable=it)},{_,_,_->}).also {owners.put("timetable",it)}
                }
                var dark by mutableStateOf(true)
                compose.setContent {
                    val state by model.state.collectAsStateWithLifecycle()
                    val activity=LocalActivity.current
                    DisposableEffect(activity) {
                        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        onDispose {activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
                    }
                    HDUHelperTheme(darkTheme=dark) {Scaffold(topBar={TimetableTopBar(state,model::goToDefaultWeek,model::selectTerm)}) {padding->
                        TimetableScreen(state,model::refresh,model::selectWeek,{}, {},Modifier.fillMaxSize().padding(padding))
                    }}
                }
                withContext(Dispatchers.Main) {model.setVisible(true)}
                fun ready()=compose.waitUntil(90_000) {
                    val state=model.state.value
                    if(!state.refreshing && state.status in listOf(TimetableStatus.ERROR,TimetableStatus.LOGIN_REQUIRED,TimetableStatus.VERIFICATION_REQUIRED))throw AssertionError(state.message ?: "Timetable unavailable")
                    state.status==TimetableStatus.READY && !state.refreshing
                }
                ready()
                val data=model.state.value.data!!
                assertEquals(1,passwordLogins)
                assertTrue(data.account==auth.state.value.profile!!.account)
                assertTrue(data.meetings.isNotEmpty());assertTrue(data.weeks.isNotEmpty())
                assertTrue(data.clocks.isNotEmpty());assertTrue(data.clocks.all {it.periods.isNotEmpty()})
                assertTrue(data.meetings.all {it.sections.isNotEmpty()&&it.weeks.isNotEmpty()})
                stage("真实整学期课表、校历和作息解析通过；未再次提交密码")
                withContext(Dispatchers.Main) {model.selectWeek(data.weeks.first().week)}
                compose.waitForIdle()
                val shown=TimetableRules.cards(data,data.weeks.first().week,model.state.value.settings).first()
                val tag="course_${shown.meeting.id}_${shown.meeting.sections.first()}"
                compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("timetable_grid_${data.weeks.first().week}"))).performScrollTo().performClick()
                compose.onNodeWithTag("course_details").assertIsDisplayed()
                // Close through Android Back; no course text or personal data is placed in assertion diagnostics.
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                compose.waitForIdle()
                withContext(Dispatchers.Main) {dark=false;model.refresh()}
                ready();assertEquals(1,passwordLogins)
                stage("真实课程详情、浅深主题及手动刷新通过")
                withContext(Dispatchers.Main) {net.value=false}
                compose.waitUntil {model.state.value.offline}
                assertNotNull(model.state.value.data)
                withContext(Dispatchers.Main) {model.setVisible(false);model.setVisible(true)}
                compose.waitUntil {!model.state.value.refreshing && model.state.value.data!=null}
                assertEquals(1,passwordLogins)
                stage("离线缓存与返回默认周通过")
                withContext(Dispatchers.Main) {net.value=true}
                ready()
                val previousYear=data.catalog.years.getOrNull(data.catalog.years.indexOf(data.term.year)+1)
                if(previousYear!=null) {
                    withContext(Dispatchers.Main) {model.selectTerm(AcademicTerm(previousYear,"12","2"))}
                    ready()
                    assertTrue(model.state.value.data!!.term.year==previousYear)
                    assertTrue(model.state.value.data!!.meetings.isNotEmpty())
                    stage("往期学期切换和课表读取通过")
                }
                auth.logout()
                compose.waitUntil {model.state.value.status==TimetableStatus.SIGNED_OUT && model.state.value.data==null}
                assertTrue(cache.entries.isEmpty())
                stage("退出后课表缓存与显示清理通过")
            }finally {withContext(Dispatchers.Main) {owners.clear()};auth.logout()}
        }}
    }
}
