package com.example.neuro_apps.registry

import com.example.neuro_apps.capabilities.*
import java.util.concurrent.ConcurrentHashMap

class CapabilityRegistry {
    private val capabilities = ConcurrentHashMap<String, DeviceCapability>()

    fun register(capability: DeviceCapability) {
        capabilities[capability.name.lowercase()] = capability
    }

    fun get(name: String): DeviceCapability? =
        capabilities[name.trim().lowercase()]

    fun all(): List<DeviceCapability> = capabilities.values.sortedBy { it.name }

    companion object {
        fun createDefault(): CapabilityRegistry {
            val registry = CapabilityRegistry()
            registry.register(FlashlightCapability())
            registry.register(BluetoothCapability())
            registry.register(WifiCapability())
            registry.register(BrightnessCapability())
            registry.register(AutoBrightnessCapability())
            registry.register(VolumeCapability())
            registry.register(VibrationCapability())
            registry.register(SilentModeCapability())
            registry.register(DoNotDisturbCapability())
            registry.register(DarkModeCapability())
            registry.register(LocationCapability())
            registry.register(AirplaneModeCapability())
            registry.register(MobileDataCapability())
            registry.register(HotspotCapability())
            registry.register(ScreenshotCapability())
            registry.register(BatterySaverCapability())
            registry.register(ScreenTimeoutCapability())
            return registry
        }
    }
}
