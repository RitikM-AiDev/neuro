package com.example.neuro_apps

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.lang.ref.WeakReference

class NeuroAccessibilityService : AccessibilityService() {

    private data class PendingReelsRequest(
        val startedAtMs: Long,
        val timeoutMs: Long,
        val onResult: (Boolean, String) -> Unit,
        var instagramSeenInForeground: Boolean = false,
        var clickAttempts: Int = 0,
        var lastClickAtMs: Long = 0L,
        var clickSucceededAtLeastOnce: Boolean = false,
        var lastTreeLogAtMs: Long = 0L,
        var completed: Boolean = false,
    )

    private data class PendingToggleRequest(
        val target: String,
        val desiredOn: Boolean,
        val startedAtMs: Long,
        val timeoutMs: Long,
        val onResult: (Boolean, String) -> Unit,
        var clickAttempts: Int = 0,
        var lastClickAtMs: Long = 0L,
        var fallbackLaunched: Boolean = false,
        var completed: Boolean = false,
    )

    private data class TargetCandidate(
        val clickableNode: AccessibilityNodeInfo,
        val matchedNode: AccessibilityNodeInfo,
        val score: Int,
        val reason: String,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var powerKeyTriggered = false

    private val powerLaunchRunnable = Runnable {
        if (!powerKeyTriggered) {
            powerKeyTriggered = true
            launchNeuro()
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val toggleReq = pendingToggleRequest
            if (toggleReq != null && !toggleReq.completed) {
                val elapsed = SystemClock.elapsedRealtime() - toggleReq.startedAtMs
                if (elapsed >= toggleReq.timeoutMs) {
                    completeToggleRequest(
                        toggleReq,
                        false,
                        "Timed out trying to toggle ${toggleReq.target} in Settings panel.",
                    )
                } else {
                    inspectAndTogglePanel(toggleReq, "poll")
                    if (pendingToggleRequest != null && !toggleReq.completed) {
                        mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
                return
            }

            val request = pendingRequest ?: return
            if (request.completed) return

            val elapsed = SystemClock.elapsedRealtime() - request.startedAtMs
            if (elapsed >= request.timeoutMs) {
                handleTimeout(request)
                return
            }

            inspectAndNavigate("poll")
            if (pendingRequest != null && !request.completed) {
                mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
        Log.d(TAG, "NeuroAccessibilityService connected and ready.")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_POWER) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    powerKeyTriggered = false
                    mainHandler.postDelayed(powerLaunchRunnable, POWER_HOLD_MS)
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                mainHandler.removeCallbacks(powerLaunchRunnable)
                powerKeyTriggered = false
            }
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val eventPkg = event.packageName?.toString().orEmpty()
        val className = event.className?.toString().orEmpty()

        // Handle 3-sec power menu / global actions dialog trigger.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (className.contains("GlobalActions", ignoreCase = true) ||
                className.contains("PowerDialog", ignoreCase = true) ||
                (eventPkg == "com.android.systemui" && className.contains("Dialog", ignoreCase = true)))
        ) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            launchNeuro()
            return
        }

        val toggleReq = pendingToggleRequest
        if (toggleReq != null && !toggleReq.completed) {
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                inspectAndTogglePanel(toggleReq, "event")
            }
            return
        }

        val request = pendingRequest
        if (request == null || request.completed) return

        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        val rootPkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (eventPkg != INSTAGRAM_PACKAGE && rootPkg != INSTAGRAM_PACKAGE) {
            return
        }

        inspectAndNavigate("event:${AccessibilityEvent.eventTypeToString(eventType)}")
    }

    private fun launchNeuro() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG, "NeuroAccessibilityService interrupted.")
        mainHandler.removeCallbacks(powerLaunchRunnable)
        cancelActiveRequest("Accessibility service was interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(powerLaunchRunnable)
        cancelActiveRequest("Accessibility service destroyed.")
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        Log.d(TAG, "NeuroAccessibilityService destroyed.")
    }

    private fun startPanelToggle(
        target: String,
        desiredOn: Boolean,
        timeoutMs: Long,
        onResult: (Boolean, String) -> Unit,
    ) {
        mainHandler.removeCallbacks(pollRunnable)
        pendingToggleRequest?.let { prior ->
            if (!prior.completed) {
                prior.completed = true
                prior.onResult(false, "Superseded by a new toggle request.")
            }
        }

        val request = PendingToggleRequest(
            target = target,
            desiredOn = desiredOn,
            startedAtMs = SystemClock.elapsedRealtime(),
            timeoutMs = timeoutMs,
            onResult = onResult,
        )
        pendingToggleRequest = request
        Log.d(TAG, "Started panel toggle: target=$target, desiredOn=$desiredOn")
        mainHandler.postDelayed(pollRunnable, 350L)
    }

    private fun inspectAndTogglePanel(request: PendingToggleRequest, trigger: String) {
        if (request.completed) return

        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            for (window in windows) {
                window.root?.let { root ->
                    if (roots.none { it == root }) roots.add(root)
                }
            }
        } catch (_: Exception) {}

        val nonNeuroRoots = roots.filter {
            val pkg = it.packageName?.toString().orEmpty()
            pkg.isNotEmpty() && pkg != packageName
        }
        if (nonNeuroRoots.isEmpty()) return

        val nodes = nonNeuroRoots.flatMap { collectVisibleNodes(it) }
        val match = findPanelSwitch(nodes, request.target)
        if (match == null) {
            val elapsed = SystemClock.elapsedRealtime() - request.startedAtMs
            if (!request.fallbackLaunched && elapsed >= 2200L) {
                val fallbackIntentAction = when (request.target) {
                    "internet" -> android.provider.Settings.ACTION_DATA_ROAMING_SETTINGS
                    "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
                    "battery_saver" -> Intent.ACTION_POWER_USAGE_SUMMARY
                    "hotspot" -> android.provider.Settings.ACTION_WIRELESS_SETTINGS
                    else -> null
                }
                if (fallbackIntentAction != null) {
                    request.fallbackLaunched = true
                    Log.d(TAG, "No switch found for ${request.target}; opening $fallbackIntentAction fallback.")
                    try {
                        startActivity(
                            Intent(fallbackIntentAction).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                    } catch (_: Exception) {}
                    return
                }
            }
            Log.d(
                TAG,
                "[$trigger] No matching switch for ${request.target} in packages=${nonNeuroRoots.map { it.packageName }} (nodes=${nodes.size})",
            )
            return
        }
        val (switchNode, clickableNode) = match

        if (switchNode.isChecked == request.desiredOn) {
            Log.d(TAG, "[$trigger] ${request.target} is now ${if (request.desiredOn) "ON" else "OFF"}; closing panel.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            completeToggleRequest(
                request,
                true,
                "${request.target} set to ${if (request.desiredOn) "on" else "off"}.",
            )
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (request.clickAttempts > 0 && now - request.lastClickAtMs < 700L) {
            return
        }
        if (request.clickAttempts >= 3) {
            completeToggleRequest(
                request,
                false,
                "Clicked ${request.target} switch 3 times but state did not change.",
            )
            return
        }

        request.clickAttempts++
        request.lastClickAtMs = now
        var clicked = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked && switchNode !== clickableNode && switchNode.isEnabled) {
            clicked = switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        Log.d(TAG, "[$trigger] Clicked ${request.target} switch (attempt #${request.clickAttempts}) -> $clicked")
    }

    private fun findPanelSwitch(
        nodes: List<AccessibilityNodeInfo>,
        target: String,
    ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        val keywords = when (target) {
            "wifi" -> listOf("wi-fi", "wifi", "wlan")
            "dark_mode" -> listOf("dark theme", "dark mode", "dark appearance", "night mode", "dark")
            "location" -> listOf("use location", "location", "gps")
            "airplane_mode" -> listOf("airplane mode", "flight mode", "aeroplane mode")
            "battery_saver" -> listOf("use battery saver", "battery saver", "power saving", "power mode", "low power")
            "hotspot" -> listOf("use wi-fi hotspot", "wi-fi hotspot", "personal hotspot", "mobile hotspot", "portable hotspot", "hotspot")
            else -> listOf(
                "mobile data",
                "cellular data",
                "data connection",
                "mobile network",
                "internet",
                "sim",
                "5g",
                "4g",
                "lte",
                "data",
            )
        }

        var bestScore = 0
        var bestPair: Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? = null
        val allSwitches = mutableListOf<Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>>()

        for (node in nodes) {
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val className = node.className?.toString()?.lowercase().orEmpty()
            val isSwitch = node.isCheckable ||
                className.contains("switch") ||
                className.contains("toggle") ||
                viewId.contains("switch")
            if (!isSwitch) continue

            val clickable = if (node.isClickable && node.isEnabled) {
                node
            } else {
                findClickableAncestor(node)
            } ?: continue

            allSwitches.add(Pair(node, clickable))

            val labelText = collectRowText(node, clickable)
            Log.d(TAG, "Switch candidate: checked=${node.isChecked}, id=$viewId, label=\"$labelText\"")

            if (target == "internet" && (
                    labelText.contains("wi-fi") ||
                    labelText.contains("wifi") ||
                    labelText.contains("wlan") ||
                    labelText.contains("roaming") ||
                    labelText.contains("calling") ||
                    labelText.contains("hotspot")
                )
            ) {
                continue
            }
            if (target == "dark_mode" && (
                    labelText.contains("schedule") ||
                    labelText.contains("sunset") ||
                    labelText.contains("auto")
                )
            ) {
                continue
            }

            var score = 0
            for ((index, kw) in keywords.withIndex()) {
                if (labelText.contains(kw)) {
                    score = (keywords.size - index) * 10
                    break
                }
            }
            if (score == 0) continue

            if (score > bestScore) {
                bestScore = score
                bestPair = Pair(node, clickable)
            }
        }

        // In dedicated single-switch panels (e.g. Wi-Fi panel), fall back to the first switch if unlabeled.
        if (bestPair == null && target == "wifi" && allSwitches.size == 1) {
            return allSwitches.first()
        }
        return bestPair
    }

    private fun collectRowText(
        switchNode: AccessibilityNodeInfo,
        clickableNode: AccessibilityNodeInfo,
    ): String {
        val parts = mutableListOf<String>()
        val container = if (clickableNode !== switchNode) {
            clickableNode
        } else {
            switchNode.parent?.parent?.parent ?: switchNode.parent?.parent ?: switchNode.parent ?: switchNode
        }

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(container)
        var visited = 0
        while (stack.isNotEmpty() && visited < 40) {
            val current = stack.removeLast()
            visited++
            current.contentDescription?.toString()?.let { if (it.isNotBlank()) parts.add(it) }
            current.text?.toString()?.let { if (it.isNotBlank()) parts.add(it) }
            for (i in current.childCount - 1 downTo 0) {
                current.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return parts.joinToString(" ").lowercase()
    }

    private fun completeToggleRequest(
        request: PendingToggleRequest,
        success: Boolean,
        message: String,
    ) {
        if (request.completed) return
        request.completed = true
        mainHandler.removeCallbacks(pollRunnable)
        if (pendingToggleRequest === request) {
            pendingToggleRequest = null
        }
        mainHandler.post {
            request.onResult(success, message)
        }
    }

    private fun startNavigation(timeoutMs: Long, onResult: (Boolean, String) -> Unit) {
        mainHandler.removeCallbacks(pollRunnable)
        pendingRequest?.let { prior ->
            if (!prior.completed) {
                prior.completed = true
                Log.w(TAG, "Resetting previous pending Reels request for new request.")
                prior.onResult(false, "Superseded by a new Reels navigation request.")
            }
        }

        val request = PendingReelsRequest(
            startedAtMs = SystemClock.elapsedRealtime(),
            timeoutMs = timeoutMs,
            onResult = onResult,
        )
        pendingRequest = request
        Log.d(TAG, "Started Reels navigation request (timeout=${timeoutMs}ms).")

        // Poll periodically in case Instagram finishes loading without emitting new events.
        mainHandler.postDelayed(pollRunnable, INITIAL_INSPECT_DELAY_MS)
    }

    private fun inspectAndNavigate(trigger: String) {
        val request = pendingRequest ?: return
        if (request.completed) return

        val elapsed = SystemClock.elapsedRealtime() - request.startedAtMs
        if (elapsed >= request.timeoutMs) {
            handleTimeout(request)
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            Log.d(TAG, "[$trigger] rootInActiveWindow is null; waiting for UI.")
            return
        }

        val rootPkg = root.packageName?.toString().orEmpty()
        if (rootPkg != INSTAGRAM_PACKAGE) {
            return
        }

        if (!request.instagramSeenInForeground) {
            request.instagramSeenInForeground = true
            Log.d(TAG, "Instagram detected in foreground (trigger=$trigger).")
        }

        val nodes = collectVisibleNodes(root)
        if (nodes.isEmpty()) {
            Log.d(TAG, "[$trigger] Accessibility tree has no visible nodes yet.")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - request.lastTreeLogAtMs >= TREE_LOG_THROTTLE_MS) {
            request.lastTreeLogAtMs = now
            Log.d(TAG, "[$trigger] Inspecting ${nodes.size} visible nodes in Instagram window.")
        }

        // Step 1: Verify if Reels is already active.
        val verificationReason = verifyReelsScreen(nodes)
        if (verificationReason != null) {
            Log.d(TAG, "Reels navigation verified: $verificationReason")
            completeRequest(request, true, verificationReason)
            return
        }

        // Step 2: Throttle clicks so Instagram has time to transition after a click.
        if (request.clickAttempts > 0 && now - request.lastClickAtMs < CLICK_COOLDOWN_MS) {
            return
        }

        if (request.clickAttempts >= MAX_CLICK_ATTEMPTS) {
            Log.w(TAG, "Max click attempts ($MAX_CLICK_ATTEMPTS) reached; waiting for verification or timeout.")
            return
        }

        // Step 3: Find the best Reels tab candidate in the accessibility tree.
        val candidate = findBestReelsCandidate(nodes)
        if (candidate == null) {
            Log.d(TAG, "[$trigger] Reels navigation button not found yet; will retry.")
            return
        }

        val bounds = Rect()
        candidate.clickableNode.getBoundsInScreen(bounds)
        Log.d(
            TAG,
            "Identified Reels target (score=${candidate.score}, reason=${candidate.reason}, " +
                "id=${candidate.matchedNode.viewIdResourceName}, " +
                "desc=${candidate.matchedNode.contentDescription}, " +
                "text=${candidate.matchedNode.text}, bounds=$bounds)",
        )

        request.clickAttempts++
        request.lastClickAtMs = now
        val clicked = candidate.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(
            TAG,
            "Click attempt #${request.clickAttempts}/$MAX_CLICK_ATTEMPTS -> success=$clicked",
        )

        if (clicked) {
            request.clickSucceededAtLeastOnce = true
        } else {
            Log.w(TAG, "ACTION_CLICK returned false on target node; will retry if UI updates.")
        }
    }

    private fun verifyReelsScreen(nodes: List<AccessibilityNodeInfo>): String? {
        var hasClipsViewerId = false
        var hasSelectedReelsTab = false
        var hasClipsControls = 0

        for (node in nodes) {
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()

            if (viewId.contains("clips_viewer") ||
                viewId.contains("clips_video_container") ||
                viewId.contains("clips_media_container") ||
                viewId.contains("clips_swipe_refresh")
            ) {
                hasClipsViewerId = true
            }

            if (viewId.contains("clips_author") ||
                viewId.contains("clips_audio") ||
                viewId.contains("clips_like") ||
                viewId.contains("clips_comment")
            ) {
                hasClipsControls++
            }

            val isReelsTabNode = viewId.contains("clips_tab") ||
                viewId.contains("reels_tab") ||
                desc == "reels" ||
                desc == "reels tab" ||
                text == "reels"

            if (isReelsTabNode && node.isSelected) {
                hasSelectedReelsTab = true
            }
        }

        return when {
            hasSelectedReelsTab && (hasClipsViewerId || hasClipsControls > 0) ->
                "Reels tab is selected and Clips viewer UI is visible."
            hasSelectedReelsTab ->
                "Reels navigation tab is selected."
            hasClipsViewerId ->
                "Instagram Clips/Reels viewer container detected."
            hasClipsControls >= 2 ->
                "Instagram Clips/Reels player controls detected."
            else -> null
        }
    }

    private fun findBestReelsCandidate(nodes: List<AccessibilityNodeInfo>): TargetCandidate? {
        val screenHeight = resources.displayMetrics.heightPixels
        val bottomBarTopY = (screenHeight * 0.72f).toInt()

        var best: TargetCandidate? = null
        val bounds = Rect()

        for (node in nodes) {
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty()

            var score = 0
            val reasons = mutableListOf<String>()

            if (viewId.contains("clips_tab") ||
                viewId.contains("reels_tab") ||
                viewId.contains("tab_reels")
            ) {
                score += 100
                reasons.add("viewId")
            }

            if (desc == "reels" || desc == "reels tab" || text == "reels") {
                score += 80
                reasons.add("exactLabel")
            } else if (desc.startsWith("reels") || text.startsWith("reels")) {
                score += 60
                reasons.add("prefixLabel")
            } else if (desc.contains("reels") || text.contains("reels") || desc.contains("clips")) {
                // Penalize feed/overlay prompts like "watch more reels" so bottom tab wins.
                if (desc.contains("watch") || desc.contains("more") || text.contains("watch")) {
                    score += 10
                } else {
                    score += 30
                }
                reasons.add("partialLabel")
            }

            if (score == 0) continue

            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) continue

            if (bounds.top >= bottomBarTopY) {
                score += 40
                reasons.add("bottomBarBounds")
            }

            if (node.isSelected) {
                score += 10
            }

            val clickable = findClickableAncestor(node) ?: continue
            if (score < MIN_CANDIDATE_SCORE) continue

            if (best == null || score > best.score) {
                best = TargetCandidate(
                    clickableNode = clickable,
                    matchedNode = node,
                    score = score,
                    reason = reasons.joinToString("+"),
                )
            }
        }

        return best
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 4) {
            if (current.isClickable && current.isEnabled) {
                return current
            }
            current = current.parent
            depth++
        }
        return null
    }

    private fun collectVisibleNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val collected = ArrayList<AccessibilityNodeInfo>(64)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)

        while (stack.isNotEmpty() && collected.size < MAX_NODES_TO_INSPECT) {
            val node = stack.removeLast()
            if (node.isVisibleToUser) {
                collected.add(node)
            }
            val childCount = node.childCount
            for (i in childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return collected
    }

    private fun handleTimeout(request: PendingReelsRequest) {
        val reason = when {
            !request.instagramSeenInForeground ->
                "Timed out waiting for Instagram to enter the foreground."
            request.clickAttempts == 0 ->
                "Instagram opened, but the Reels tab was not exposed or found in the accessibility tree."
            !request.clickSucceededAtLeastOnce ->
                "Found a Reels candidate, but ACTION_CLICK failed on all ${request.clickAttempts} attempts."
            else ->
                "Clicked Reels target (${request.clickAttempts}x), but could not verify Reels screen within ${request.timeoutMs}ms."
        }
        Log.e(TAG, "Reels navigation failed: $reason")
        completeRequest(request, false, reason)
    }

    private fun completeRequest(
        request: PendingReelsRequest,
        success: Boolean,
        message: String,
    ) {
        if (request.completed) return
        request.completed = true
        mainHandler.removeCallbacks(pollRunnable)
        if (pendingRequest === request) {
            pendingRequest = null
        }
        mainHandler.post {
            request.onResult(success, message)
        }
    }

    private fun cancelActiveRequest(reason: String) {
        val request = pendingRequest ?: return
        completeRequest(request, false, reason)
    }

    companion object {
        private const val TAG = "NeuroAccessibility"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val POWER_HOLD_MS = 3000L

        private const val INITIAL_INSPECT_DELAY_MS = 450L
        private const val POLL_INTERVAL_MS = 450L
        private const val CLICK_COOLDOWN_MS = 900L
        private const val TREE_LOG_THROTTLE_MS = 1800L
        private const val MAX_CLICK_ATTEMPTS = 3
        private const val MIN_CANDIDATE_SCORE = 50
        private const val MAX_NODES_TO_INSPECT = 250

        @Volatile
        private var instanceRef: WeakReference<NeuroAccessibilityService>? = null

        @Volatile
        private var pendingRequest: PendingReelsRequest? = null

        @Volatile
        private var pendingToggleRequest: PendingToggleRequest? = null

        fun isServiceConnected(): Boolean = instanceRef?.get() != null

        fun requestPanelToggle(
            target: String,
            desiredOn: Boolean,
            timeoutMs: Long = 6_000L,
            onResult: (Boolean, String) -> Unit,
        ) {
            val service = instanceRef?.get()
            if (service == null) {
                onResult(false, "NeuroAccessibilityService is not enabled or connected.")
                return
            }
            service.startPanelToggle(target, desiredOn, timeoutMs, onResult)
        }

        fun takeScreenshot(): Boolean {
            val service = instanceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return service.performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
            return false
        }

        fun requestReelsNavigation(
            timeoutMs: Long = 12_000L,
            onResult: (Boolean, String) -> Unit,
        ) {
            val service = instanceRef?.get()
            if (service == null) {
                Log.w(TAG, "Cannot start Reels navigation: NeuroAccessibilityService is not connected.")
                onResult(false, "NeuroAccessibilityService is not enabled or connected.")
                return
            }
            service.startNavigation(timeoutMs, onResult)
        }
    }
}
