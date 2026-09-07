package moe.nepnep.hduhelper.data.background

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** libxposed supports one listener; share it between island and background capabilities. */
object XposedFramework {
    private val serviceState = MutableStateFlow<XposedService?>(null)
    val service = serviceState.asStateFlow()
    init {
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) { serviceState.value = service }
            override fun onServiceDied(service: XposedService) {
                if (serviceState.value === service) serviceState.value = null
            }
        })
    }
}
