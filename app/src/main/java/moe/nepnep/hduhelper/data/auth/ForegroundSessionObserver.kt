package moe.nepnep.hduhelper.data.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** No checks while backgrounded/offline; transient wake-up failures get bounded, silent retries. */
suspend fun observeForegroundSession(foreground: Flow<Boolean>, online: Flow<Boolean>, auth: AuthRepository) {
    combine(foreground, online) { visible, connected -> visible && connected }
        .distinctUntilChanged().collectLatest { ready ->
            if (!ready) return@collectLatest
            delay(1_000)
            repeat(3) { attempt ->
                auth.checkAndRefresh(passive = true)
                val state = auth.state.value
                if (state.status != AuthStatus.UNAVAILABLE || state.message != null) return@collectLatest
                if (attempt < 2) delay(if (attempt == 0) 2_000 else 8_000)
            }
        }
}
