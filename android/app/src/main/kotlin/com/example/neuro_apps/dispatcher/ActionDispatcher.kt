package com.example.neuro_apps.dispatcher

import android.content.Context
import android.util.Log
import com.example.neuro_apps.capabilities.CapabilityResult
import com.example.neuro_apps.capabilities.ExecutionMode
import com.example.neuro_apps.permissions.PermissionManager
import com.example.neuro_apps.registry.CapabilityRegistry

class ActionDispatcher(
    private val registry: CapabilityRegistry,
    private val permissionManager: PermissionManager,
) {
    fun dispatch(
        context: Context,
        request: Map<String, Any?>,
        onComplete: (CapabilityResult) -> Unit,
    ) {
        val capabilityName = (request["capability"] as? String)?.trim().orEmpty()
        val action = (request["action"] as? String)?.trim()?.lowercase().orEmpty()
        @Suppress("UNCHECKED_CAST")
        val parameters = (request["parameters"] as? Map<String, Any?>) ?: emptyMap()

        if (capabilityName.isEmpty() || action.isEmpty()) {
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = capabilityName,
                    action = action,
                    message = "Invalid request: 'capability' and 'action' are required.",
                    executionMode = ExecutionMode.DIRECT,
                    errorCode = "INVALID_REQUEST",
                ),
            )
            return
        }

        val capability = registry.get(capabilityName)
        if (capability == null) {
            Log.w(TAG, "Unsupported capability requested: '$capabilityName'")
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = capabilityName,
                    action = action,
                    message = "Unsupported capability '$capabilityName'.",
                    executionMode = ExecutionMode.DIRECT,
                    errorCode = "UNSUPPORTED_CAPABILITY",
                ),
            )
            return
        }

        if (action !in capability.supportedActions) {
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = capability.name,
                    action = action,
                    message = "Action '$action' is not supported by '${capability.name}'. Supported: ${capability.supportedActions}",
                    executionMode = capability.executionMode,
                    errorCode = "UNSUPPORTED_ACTION",
                ),
            )
            return
        }

        if (action == "get") {
            val state = try {
                capability.getState(context)
            } catch (e: Exception) {
                Log.e(TAG, "getState error on ${capability.name}: ${e.message}", e)
                null
            }
            onComplete(
                CapabilityResult(
                    success = true,
                    capability = capability.name,
                    action = action,
                    state = state,
                    message = "Retrieved ${capability.name} state.",
                    executionMode = capability.executionMode,
                ),
            )
            return
        }

        val auth = permissionManager.ensureAuthorized(context, capability, action)
        if (!auth.authorized) {
            onComplete(auth.blockedResult!!)
            return
        }

        Log.d(TAG, "Dispatching capability=${capability.name} action=$action params=$parameters")
        try {
            capability.execute(context, action, parameters, onComplete)
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed for ${capability.name}.$action: ${e.message}", e)
            onComplete(
                CapabilityResult(
                    success = false,
                    capability = capability.name,
                    action = action,
                    message = e.message ?: "Execution error in ${capability.name}.",
                    executionMode = capability.executionMode,
                    errorCode = "EXECUTION_ERROR",
                ),
            )
        }
    }

    fun listCapabilities(context: Context): List<Map<String, Any?>> =
        registry.all().map { cap ->
            mapOf(
                "capability" to cap.name,
                "supported_actions" to cap.supportedActions.toList(),
                "execution_mode" to cap.executionMode.wireName,
                "available" to cap.isAvailable(context),
                "state" to try { cap.getState(context) } catch (_: Exception) { null },
            )
        }

    companion object {
        private const val TAG = "NeuroCapabilities"
    }
}
