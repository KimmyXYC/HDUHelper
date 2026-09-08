package moe.nepnep.hduhelper.ui

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import moe.nepnep.hduhelper.data.auth.*
import moe.nepnep.hduhelper.data.electric.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ElectricViewModelTest {
    private class Source : ElectricSource {
        var bound: ElectricBinding? = ElectricBinding(1, "楼", "层", "房")
        var failHistory = false
        var writeFails = false
        var writeCommitted = false
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun binding(): ElectricBinding? { gate?.await(); return bound }
        override suspend fun balance() = ElectricBalance("-2.00", 100)
        override suspend fun history(): ElectricHistory { if (failHistory) throw ElectricException(); return ElectricHistory("1.0", emptyList()) }
        override suspend fun buildings() = listOf(ElectricOption(1, "一号楼"), ElectricOption(2, "二号楼"))
        override suspend fun floors(building: Long) = listOf(ElectricOption(building * 10, "一层"))
        override suspend fun rooms(floor: Long) = listOf(ElectricOption(floor * 10, "101"))
        override suspend fun bind(room: Long) {
            calls++; if (writeCommitted || !writeFails) bound = ElectricBinding(room, "楼", "层", "房")
            if (writeFails) throw ElectricException()
        }
        override suspend fun unbind() { calls++; if (writeFails) throw ElectricException(); bound = null }
    }
    private fun test(block: suspend TestScope.(ElectricViewModel, Source, MutableStateFlow<AuthState>, MutableStateFlow<Long>) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler)); val store = ViewModelStore()
        try {
            val auth = MutableStateFlow(AuthState(AuthStatus.AUTHENTICATED, UserProfile("synthetic", "用户")))
            val generation = MutableStateFlow(1L); val source = Source()
            val model = ElectricViewModel(source, auth, generation); store.put("electric", model)
            runCurrent(); model.setVisible(true); runCurrent(); block(model, source, auth, generation)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
    @Test fun partialFailureRetainsDataAndUnboundClearsIt() = test { model, source, _, _ ->
        assertEquals("-2.00", model.state.value.balance!!.amount)
        source.failHistory = true; model.refresh(); runCurrent()
        assertNotNull(model.state.value.history); assertNotNull(model.state.value.message)
        source.bound = null; model.refresh(); runCurrent()
        assertTrue(model.state.value.bindingKnown); assertNull(model.state.value.balance); assertNull(model.state.value.history)
    }
    @Test fun selectorsResetChildrenAndAmbiguousWriteIsReadBackOnce() = test { model, source, _, _ ->
        model.edit(); runCurrent(); model.building(model.state.value.buildings.first()); runCurrent()
        model.floor(model.state.value.floors.first()); runCurrent(); model.room(model.state.value.rooms.first())
        model.building(model.state.value.buildings.last()); runCurrent()
        assertNull(model.state.value.floor); assertNull(model.state.value.room); assertTrue(model.state.value.rooms.isEmpty())
        model.floor(model.state.value.floors.first()); runCurrent(); model.room(model.state.value.rooms.first())
        source.writeFails = true; source.writeCommitted = true; model.bind(); runCurrent()
        assertEquals(1, source.calls); assertFalse(model.state.value.editing); assertEquals(200L, model.state.value.binding!!.roomId)
        source.writeCommitted = false; model.unbind(); runCurrent()
        assertNotNull(model.state.value.binding); assertNotNull(model.state.value.message)
    }
    @Test fun logoutCancelsLateDataAndClearsSelections() = test { model, source, auth, generation ->
        source.gate = CompletableDeferred(); model.refresh(); runCurrent()
        auth.value = AuthState(AuthStatus.SIGNED_OUT); generation.value++; runCurrent()
        source.gate!!.complete(Unit); runCurrent()
        assertEquals(TimetableStatus.SIGNED_OUT, model.state.value.status)
        assertNull(model.state.value.binding); assertNull(model.state.value.balance)
    }
}
