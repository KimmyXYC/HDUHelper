package moe.nepnep.hduhelper.data.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.nepnep.hduhelper.data.settings.AuthSettings
import okhttp3.HttpUrl

class VerificationSession internal constructor(
    val generation: Long,
    val url: String,
    val cookies: List<StoredCookie>,
)

/** All persisted state changes are IO transactions. Network transactions use private cookie snapshots. */
class AuthRepository(
    private val store: SessionStore,
    private val settings: AuthSettings,
    private val sessions: AuthSessionFactory,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
) : ServiceAuthorizer {
    private val guard = Any()
    private val work = Mutex()
    private var initialized = false
    private var generation = 0L
    private val generationState = MutableStateFlow(0L)
    override val sessionGeneration: StateFlow<Long> = generationState.asStateFlow()
    private var revision = 0L
    private var saved = StoredSession()
    private val invalidationListeners = mutableListOf<() -> Unit>()
    private var pending: VerificationSession? = null
    private val mutableState = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = mutableState.asStateFlow()

    fun onSessionInvalidated(listener: () -> Unit) = synchronized(guard) { invalidationListeners.add(listener); Unit }

    private fun advanceGeneration() {
        generation++
        invalidationListeners.forEach { it() }
        generationState.value = generation
    }

    override fun serviceIdentity(): ServiceIdentity? = synchronized(guard) {
        saved.profile?.let { ServiceIdentity(generation, it.account) }
    }

    override fun isCurrent(identity: ServiceIdentity): Boolean = synchronized(guard) {
        identity.generation == generation && saved.profile?.account == identity.account
    }

    /** Business authorization must test SSO itself, even when the portal session is still valid. */
    override suspend fun authorizeService(identity: ServiceIdentity, service: HttpUrl): String = withContext(io) {
        initialize()
        work.withLock {
            val original = synchronized(guard) {
                if (!isCurrent(identity)) throw AuthException(AuthFailure.CANCELLED, "操作已取消")
                saved
            }
            val session = sessions.create(original.cookies)
            try {
                val ticket = try { session.authorizeService(service) } catch (e: AuthException) {
                    if (e.kind != AuthFailure.EXPIRED) throw e
                    val password = original.password
                    if (!settings.autoLogin || password == null) throw e
                    var failures = original.failureCount.coerceIn(0, 3)
                    var restored = false
                    while (failures < 3 && !restored) {
                        checkGeneration(identity.generation)
                        if (failures > 0) retryDelay(if (failures == 1) 2_000 else 8_000)
                        checkGeneration(identity.generation)
                        try {
                            val profile = session.login(original.account, password)
                            commitProfile(identity.generation, original, session, profile)
                            restored = true
                        } catch (failure: AuthException) {
                            if (failure.kind != AuthFailure.CREDENTIALS) throw failure
                            failures++
                            synchronized(guard) {
                                checkGeneration(identity.generation)
                                val next = saved.copy(failureCount = failures)
                                persist(next)
                                saved = next
                            }
                        }
                    }
                    if (!restored) {
                        expire(identity.generation, "登录已失效，自动登录连续失败，请重新登录")
                        throw AuthException(AuthFailure.EXPIRED, "请重新登录")
                    }
                    session.authorizeService(service)
                }
                synchronized(guard) {
                    checkGeneration(identity.generation)
                    val next = saved.copy(cookies = session.cookies)
                    persist(next)
                    saved = next
                }
                ticket
            } catch (e: AuthException) {
                if (e.kind == AuthFailure.VERIFICATION) synchronized(guard) {
                    checkGeneration(identity.generation)
                    prepareVerification(identity.generation, session, e.message, original.account)
                }
                throw e
            }
        }
    }

    suspend fun initialize() = withContext(io) {
        if (synchronized(guard) { initialized }) return@withContext
        work.withLock {
            synchronized(guard) {
                if (!initialized) {
                    saved = try { store.load() ?: StoredSession() } catch (_: Exception) {
                        runCatching { store.clear() }
                        StoredSession(notice = "无法读取本机登录信息，请重新登录")
                    }
                    if (!settings.autoLogin && saved.password != null) {
                        saved = saved.copy(password = null)
                        persist(saved)
                    }
                    initialized = true
                    publishStable()
                }
            }
        }
    }

    suspend fun login(account: String, password: String, autoLogin: Boolean) = withContext(io) {
        initialize()
        val name = account.trim()
        if (name.isEmpty() || password.isEmpty()) throw AuthException(AuthFailure.CREDENTIALS, "请输入账号和密码")
        val token = synchronized(guard) {
            advanceGeneration()
            revision++
            pending = null
            mutableState.value = AuthState(AuthStatus.SIGNING_IN, saved.profile, name)
            generation
        }
        work.withLock {
            checkGeneration(token)
            // A new explicit login must validate the entered credentials, never silently reuse another account's SSO.
            val session = sessions.create(emptyList())
            try {
                val profile = session.login(name, password)
                if (profile.account != name) throw AuthException(AuthFailure.PROTOCOL, "登录账号不一致，请重新登录")
                synchronized(guard) {
                    checkGeneration(token)
                    settings.setAutoLogin(autoLogin)
                    commit(token, StoredSession(name, password.takeIf { autoLogin }, profile, session.cookies))
                }
            } catch (e: AuthException) {
                synchronized(guard) {
                    checkGeneration(token)
                    if (e.kind == AuthFailure.VERIFICATION) prepareVerification(token, session, e.message, name)
                    else publishStable(e.message)
                }
                throw e
            } catch (e: CancellationException) {
                synchronized(guard) { if (generation == token) publishStable() }
                throw e
            } catch (_: Exception) {
                synchronized(guard) { if (generation == token) publishStable("无法保存登录信息，请重试") }
                throw AuthException(AuthFailure.STORAGE, "无法保存登录信息，请重试")
            }
        }
    }

    suspend fun checkAndRefresh(passive: Boolean = false) {
        initialize()
        val observedRevision = synchronized(guard) { revision }
        refresh(observedRevision, passive)
    }

    private suspend fun refresh(observedRevision: Long, passive: Boolean = false) = withContext(io) {
        work.withLock {
            val captured = synchronized(guard) {
                if (revision != observedRevision || saved.profile == null ||
                    mutableState.value.status in listOf(AuthStatus.SIGNING_IN, AuthStatus.VERIFICATION_REQUIRED)) return@withContext
                mutableState.value = mutableState.value.copy(status = AuthStatus.REFRESHING, message = null)
                generation to saved
            }
            val (token, original) = captured
            val session = sessions.create(original.cookies)
            try {
                val checked = try { session.check() } catch (e: AuthException) {
                    if (e.kind != AuthFailure.EXPIRED) throw e
                    null
                }
                if (checked != null) {
                    commitProfile(token, original, session, checked)
                    return@withContext
                }
                if (!settings.autoLogin) {
                    expire(token, "登录已过期，请重新登录")
                    return@withContext
                }
                val renewed = try { session.renewSso() } catch (e: AuthException) {
                    if (e.kind !in listOf(AuthFailure.EXPIRED, AuthFailure.CREDENTIALS)) throw e
                    null
                }
                if (renewed != null) {
                    commitProfile(token, original, session, renewed)
                    return@withContext
                }
                val password = original.password
                if (password == null) {
                    expire(token, "登录已过期，请重新输入密码")
                    return@withContext
                }
                var failures = original.failureCount.coerceIn(0, 3)
                while (failures < 3) {
                    checkGeneration(token)
                    if (failures > 0) retryDelay(if (failures == 1) 2_000 else 8_000)
                    checkGeneration(token)
                    try {
                        val profile = session.login(original.account, password)
                        commitProfile(token, original, session, profile)
                        return@withContext
                    } catch (e: AuthException) {
                        if (e.kind != AuthFailure.CREDENTIALS) throw e
                        failures++
                        synchronized(guard) {
                            checkGeneration(token)
                            val next = saved.copy(failureCount = failures)
                            persist(next)
                            saved = next
                        }
                    }
                }
                expire(token, "登录已失效，自动登录连续失败，请重新登录")
            } catch (e: AuthException) {
                synchronized(guard) {
                    if (generation != token) return@withContext
                    revision++
                    if (e.kind == AuthFailure.VERIFICATION) prepareVerification(token, session, e.message, original.account)
                    else mutableState.value = mutableState.value.copy(
                        status = AuthStatus.UNAVAILABLE,
                        message = if (passive && e.kind in listOf(AuthFailure.NETWORK, AuthFailure.SERVICE)) null else e.message,
                    )
                }
            } catch (e: CancellationException) {
                synchronized(guard) {
                    if (generation == token) {
                        revision++
                        publishStable()
                    }
                }
                throw e
            } catch (_: Exception) {
                synchronized(guard) {
                    if (generation == token) {
                        revision++
                        mutableState.value = mutableState.value.copy(status = AuthStatus.UNAVAILABLE, message = "无法保存登录状态，请稍后重试")
                    }
                }
            }
        }
    }

    /** Protected GETs retry once after the shared recovery; auth endpoints never call this method. */
    suspend fun authenticatedGet(url: HttpUrl): String {
        initialize()
        val initial = synchronized(guard) {
            if (saved.profile == null) throw AuthException(AuthFailure.EXPIRED, "请先登录")
            Triple(generation, revision, saved)
        }
        suspend fun fetch(token: Long, snapshot: StoredSession): String {
            val session = sessions.create(snapshot.cookies)
            val body = session.get(url)
            withContext(io) {
                synchronized(guard) {
                    checkGeneration(token)
                    // Only update cookies if no newer login/check has replaced this snapshot.
                    if (saved === snapshot) {
                        val next = saved.copy(cookies = session.cookies)
                        persist(next)
                        saved = next
                    }
                }
            }
            return body
        }
        return try { fetch(initial.first, initial.third) } catch (e: AuthException) {
            if (e.kind != AuthFailure.EXPIRED) throw e
            refresh(initial.second)
            val restored = synchronized(guard) {
                checkGeneration(initial.first)
                if (mutableState.value.status != AuthStatus.AUTHENTICATED) {
                    throw AuthException(AuthFailure.EXPIRED, mutableState.value.message ?: "请重新登录")
                }
                saved
            }
            fetch(initial.first, restored)
        }
    }

    fun verificationSession(): VerificationSession? = synchronized(guard) { pending }

    suspend fun startVerification(): VerificationSession = withContext(io) {
        initialize()
        synchronized(guard) {
            pending?.let { return@withContext it }
            advanceGeneration()
            revision++
            val session = sessions.create(saved.cookies)
            prepareVerification(generation, session, "请在官方页面完成验证", saved.account)
            pending!!
        }
    }

    suspend fun completeVerification(token: Long, cookies: List<StoredCookie>): Boolean = withContext(io) {
        work.withLock {
            val old = synchronized(guard) { checkGeneration(token); saved }
            val session = sessions.create(cookies)
            val profile = session.check()
            synchronized(guard) {
                checkGeneration(token)
                // A WebView may log in a different account or use a different password. Never save an unverified form password.
                val password = old.password.takeIf { old.profile?.account == profile.account && settings.autoLogin }
                if (password == null) settings.setAutoLogin(false)
                commit(
                    token,
                    StoredSession(profile.account, password, profile, session.cookies,
                        notice = if (password == null) "网页登录成功；如需自动登录，请在设置中重新验证密码" else null),
                )
            }
            true
        }
    }

    /** Immediate invalidation also prevents a late WebView callback from restoring an abandoned session. */
    fun cancelLogin() = synchronized(guard) {
        advanceGeneration()
        revision++
        pending = null
        publishStable()
    }

    suspend fun disableAutoLogin() = withContext(io) {
        initialize()
        synchronized(guard) {
            advanceGeneration()
            revision++
            pending = null
            // Delete credentials before updating the preference, so a crash can never retain a disabled password.
            val next = saved.copy(password = null, failureCount = 0)
            persist(next)
            saved = next
            settings.setAutoLogin(false)
            publishStable()
        }
    }

    suspend fun logout() = withContext(io) {
        initialize()
        synchronized(guard) {
            advanceGeneration()
            revision++
            pending = null
            val next = StoredSession(account = saved.account)
            // Delete the credential-bearing file even if a subsequent write fails.
            store.clear()
            saved = next
            publishStable()
            persist(next)
        }
    }

    suspend fun acknowledgeNotice(notice: String) = withContext(io) {
        synchronized(guard) {
            if (saved.notice == notice) {
                val next = saved.copy(notice = null)
                persist(next)
                saved = next
                mutableState.value = mutableState.value.copy(notice = null)
            }
        }
    }

    private fun commitProfile(token: Long, original: StoredSession, session: AuthSession, profile: UserProfile) {
        if (profile.account != original.account) throw AuthException(AuthFailure.VERIFICATION, "学校会话账号发生变化，请重新验证")
        synchronized(guard) { commit(token, saved.copy(profile = profile, cookies = session.cookies, failureCount = 0)) }
    }

    private fun expire(token: Long, notice: String) = synchronized(guard) {
        checkGeneration(token)
        advanceGeneration()
        revision++
        pending = null
        val next = StoredSession(account = saved.account, notice = notice)
        saved = next
        publishStable()
        store.clear()
        persist(next)
    }

    private fun prepareVerification(token: Long, session: AuthSession, message: String, account: String) {
        checkGeneration(token)
        pending = VerificationSession(token, session.verificationUrl, session.cookies)
        mutableState.value = AuthState(AuthStatus.VERIFICATION_REQUIRED, saved.profile, account, message, saved.notice)
    }

    private fun commit(token: Long, next: StoredSession) {
        checkGeneration(token)
        persist(next)
        saved = next
        revision++
        pending = null
        publishStable()
    }

    private fun persist(next: StoredSession) {
        try { store.save(next) } catch (_: Exception) {
            throw AuthException(AuthFailure.STORAGE, "无法保存本机登录信息，请重试")
        }
    }

    private fun publishStable(message: String? = null) {
        mutableState.value = AuthState(
            if (saved.profile != null) AuthStatus.AUTHENTICATED else AuthStatus.SIGNED_OUT,
            saved.profile, saved.account, message, saved.notice,
        )
    }

    private fun checkGeneration(token: Long) = synchronized(guard) {
        if (token != generation) throw AuthException(AuthFailure.CANCELLED, "操作已取消")
    }
}
