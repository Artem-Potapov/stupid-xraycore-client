package com.justme.xtls_core_proxy.vpn

/**
 * Pure, total result of an onStartCommand invocation, decided from the action
 * and the profile id extra. Extracted from XrayVpnService so it can be exercised
 * by fast JVM unit tests, and so every action is enumerated — no catch-all branch
 * can silently turn a non-start action (e.g. a notification swipe) into a connect.
 */
sealed interface StartCommandDecision {
    data class StartProfile(val profileId: Long) : StartCommandDecision

    /** Headless start with no explicit profile (cold always-on / boot). Resolve the active profile. */
    data object StartActiveProfile : StartCommandDecision

    data object Stop : StartCommandDecision

    /** User swiped the ongoing FGS notification (Android 14+); re-post it if still running. */
    data object RepostNotification : StartCommandDecision

    data object RefuseNoProfile : StartCommandDecision

    companion object {
        const val SENTINEL = -1L

        /**
         * A Reconnect tears the tunnel down but must retain its service instance for the following
         * start. Explicit Disconnect surfaces never pass this flag, so they still stop the service.
         */
        fun stopServiceForReconnect(isReconnectStop: Boolean): Boolean = !isReconnectStop

        fun decide(action: String?, profileId: Long): StartCommandDecision = when (action) {
            XrayVpnService.ACTION_START ->
                if (profileId != SENTINEL) StartProfile(profileId) else RefuseNoProfile
            XrayVpnService.ACTION_STOP -> Stop
            XrayVpnService.ACTION_NOTIFICATION_DISMISSED -> RepostNotification
            // null = system-initiated always-on/boot start (no prior intent to redeliver).
            // A START_REDELIVER_INTENT restart re-delivers the last ACTION_START, so it
            // lands in the ACTION_START branch above with the original profile id.
            else -> StartActiveProfile
        }
    }
}
