package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.failover.FailoverPreferences
import com.justme.xtls_core_proxy.failover.FailoverSettings
import com.justme.xtls_core_proxy.log.GiveUpOngoingLine
import com.justme.xtls_core_proxy.log.VpnConnectionState

/**
 * Returns whether an asynchronous lifecycle callback still owns the currently running session.
 *
 * A `running` flag alone is insufficient: a full stop can be followed by a new start before an
 * old background callback arrives. The callback's epoch must therefore match the active epoch.
 */
internal fun acceptsSessionLifecycleCallback(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
): Boolean = running && activeSessionEpoch == callbackSessionEpoch

/** Per-session tunnel ownership states, mutated only while the VPN lifecycle lock is held. */
internal enum class SessionTunnelState {
    STARTING,
    CONNECTED,
    PAUSED,
    REVIVING,
    ROTATING,
    STOPPED,
}

internal fun ownsTunnelTransition(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
    expectedState: SessionTunnelState,
): Boolean =
    acceptsSessionLifecycleCallback(running, activeSessionEpoch, callbackSessionEpoch) &&
        tunnelState == expectedState

/** A paused session may reserve exactly one asynchronous revive transition. */
internal fun canReserveRevive(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean = ownsTunnelTransition(
    running = running,
    activeSessionEpoch = activeSessionEpoch,
    callbackSessionEpoch = callbackSessionEpoch,
    tunnelState = tunnelState,
    expectedState = SessionTunnelState.PAUSED,
)

/**
 * A CONNECTED session may reserve exactly one asynchronous failover rotation.
 *
 * Deliberately NOT expressed via [canReserveRevive]: revive reserves from PAUSED, rotation from
 * CONNECTED. Sharing one state would let a kill-switch revive and a failover rotation each believe
 * they own the same transition.
 *
 * [failoverEnabled] is the same shape as [shouldFireFailoverRetry]'s veto: the disable branch clears
 * the re-arm timer and thrash window, but work already queued on `tunnelOpScope` still reaches this
 * reservation. Without the enabled check it would admit with a fresh budget after the user switched
 * the feature off. The timer vetoes at fire; queued rotations veto here.
 */
internal fun canReserveRotation(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
    failoverEnabled: Boolean,
): Boolean = failoverEnabled && ownsTunnelTransition(
    running = running,
    activeSessionEpoch = activeSessionEpoch,
    callbackSessionEpoch = callbackSessionEpoch,
    tunnelState = tunnelState,
    expectedState = SessionTunnelState.CONNECTED,
)

/**
 * **The single source of failover settings for anything the service DECIDES.** Impure by design,
 * like [canReserveRotationFromAuthoritativeState] below, which now delegates to it.
 *
 * ### Why there is a rule here at all
 * `FailoverPreferences.save()` and `load()` publish into the process-global `state` **synchronously**.
 * `XrayVpnService` also observes that flow, but its collector runs **asynchronously** on
 * `serviceScope`, so any field it caches can be a whole tuple behind the value the user just saved.
 * That asymmetry is why this seam exists.
 *
 * The service used to answer "what are the current settings" two ways in one file: the enable veto
 * read the flow, while the thrash cap (`maxRotations` / `rotationWindowMs`), the rotation-success
 * `applyFailoverPreferences`, the re-arm delay and the `UNPROTECTED` stop deadline all read the
 * collected cache. The consequence was never exposure — it is watchdog *timing* — but the last of
 * those decides whether the service **stops itself**, and one question with two answers in one file
 * is how a maintainer picks the wrong one. Read everything here.
 *
 * ### What may still legitimately be cached
 * Exactly one thing, and it is a different question: `XrayVpnService.failoverMonitorSettings` — the
 * settings the live `TunnelHealthMonitor` was **constructed from**. That is a record of the past,
 * which is precisely what `failoverMonitorNeedsRebuild` needs in order to tell a real timing edit
 * from the settings flow's no-op re-emission. It must NOT be replaced by this call. The general
 * "current settings" cache it sat beside had no such justification and no longer exists.
 */
internal fun authoritativeFailoverSettings(): FailoverSettings = FailoverPreferences.state.value

/**
 * Reservation boundary used by [XrayVpnService]. Unlike [canReserveRotation], this seam reads the
 * process-authoritative settings source at the instant the queued operation reserves its transition.
 */
internal fun canReserveRotationFromAuthoritativeState(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean = canReserveRotation(
    running = running,
    activeSessionEpoch = activeSessionEpoch,
    callbackSessionEpoch = callbackSessionEpoch,
    tunnelState = tunnelState,
    failoverEnabled = authoritativeFailoverSettings().enabled,
)

/**
 * Whether a refused rotation reservation must enter the give-up funnel instead of releasing the
 * bridge it is currently holding.
 *
 * A failed candidate returns the episode to CONNECTED with no live TUN and an unread bridge held
 * across the queued retry. If that retry loses the reservation race (most importantly because the
 * user disabled failover), the bridge is the sole containment and must be adopted by give-up.
 * Stale callbacks and every state with another lifecycle owner remain outside this rule.
 */
internal fun shouldFunnelRotationReservationRefusal(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
    hasTunnel: Boolean,
    hasRotationBridge: Boolean,
): Boolean = acceptsSessionLifecycleCallback(
    running = running,
    activeSessionEpoch = activeSessionEpoch,
    callbackSessionEpoch = callbackSessionEpoch,
) &&
    tunnelState == SessionTunnelState.CONNECTED &&
    !hasTunnel &&
    hasRotationBridge

/**
 * Whether a kill-switch event landing mid-transition must be DEFERRED (recorded and replayed once
 * the transition commits) rather than dropped.
 *
 * A kill can only tear down a CONNECTED tunnel. Both transitional states take the tunnel down and
 * bring it back up — the kill-switch's own revive (`REVIVING`) and a failover rotation (`ROTATING`)
 * — so a kill arriving in either window would otherwise be silently and permanently lost, because
 * the foreground monitor is edge-triggered and never re-fires it, leaving the tunnel CONNECTED with
 * a kill-listed app in the foreground. True only for the CURRENT session: a CONNECTED session kills
 * immediately, and PAUSED/STARTING/STOPPED/stale/stopped states have nothing to defer to.
 *
 * This is the ONE home for the rule. A `REVIVING`-only variant existed alongside it for a while,
 * production-dead but carrying the rule's entire test coverage — so the tests read as thorough while
 * exercising a function nothing called. Do not reintroduce a second copy.
 */
internal fun shouldDeferKillDuringTransition(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
): Boolean =
    acceptsSessionLifecycleCallback(running, activeSessionEpoch, callbackSessionEpoch) &&
        (tunnelState == SessionTunnelState.REVIVING || tunnelState == SessionTunnelState.ROTATING)

/**
 * The label of a kill parked by [shouldDeferKillDuringTransition] that a controlled-app
 * LEFT-foreground callback must now WITHDRAW — or null when there is nothing to withdraw.
 *
 * The enter edge CREATES the deferral; the leave edge is the signal that the condition it was
 * deferred for has ENDED. Dropping the leave instead is what left the tunnel parked in `PAUSED` for
 * an app that is no longer in the foreground: `PAUSED` means no TUN at all, the foreground monitor
 * is edge-triggered so it never re-fires that leave, and nothing else recovers it.
 *
 * ## The tunnel state is deliberately NOT an input, and this rule is deliberately WIDER than the
 * one it mirrors
 *
 * The obvious shape is the exact mirror of [shouldDeferKillDuringTransition] — withdraw only in the
 * same `{REVIVING, ROTATING}` window that defers. That shape would look right and barely fire.
 * `tunnelOpScope` is `Dispatchers.IO.limitedParallelism(1)`, and `bringUpTunnel` is NOT a `suspend`
 * function: it holds that single slot for its whole blocking span (config build, geo-asset prep,
 * `establish()`, `startXray`). A leave arriving in that span queues behind the transition and does
 * not execute until it has already committed `CONNECTED`. A rotation episode also returns to
 * `CONNECTED` *between* two failed candidates with the marker still armed. So the states in which a
 * withdrawal actually gets to run are mostly NOT the states a kill can be deferred in.
 *
 * The marker itself, not the tunnel state, is therefore what "a kill is queued for replay" means —
 * and a leave edge means "no queued kill is wanted", in every state. There is no state in which the
 * app has left and pausing the tunnel for it is still correct.
 *
 * Not enumerating `{REVIVING, ROTATING}` a second time is the other half of the point: that set has
 * exactly one home, in [shouldDeferKillDuringTransition]. A duplicate enumeration is this branch's
 * most repeated defect, and a rule with no state parameter cannot acquire one by drift.
 *
 * ## What the session check buys, and why it is the ONLY refusal
 *
 * Of the reasons `canReserveRevive` can refuse a leave callback — stale epoch, stopped service,
 * already `CONNECTED`, mid-transition — only the first two may block a withdrawal. Withdrawal
 * cancels a SAFETY event, so a callback belonging to a session that has already been replaced must
 * never reach the marker a live session armed. The other two must NOT block it, per the derivation
 * above.
 *
 * ## Pairs with the replay reading the marker at execution time
 *
 * This rule only closes the defect because the commit that dispatches a replay leaves the marker
 * ARMED and the replay coroutine resolves it when it runs. Both the withdrawal and the replay are
 * queued on the one serialized `tunnelOpScope`, so FIFO settles every ordering: a leave queued
 * before the replay withdraws the marker and the replay finds nothing; a leave queued after it runs
 * against a tunnel the replay has already paused, where it revives normally. Capture the label at
 * the commit instead and the earlier leave has nothing left to clear.
 */
internal fun deferredKillToWithdraw(
    pendingKillLabel: String?,
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
): String? = pendingKillLabel?.takeIf {
    acceptsSessionLifecycleCallback(running, activeSessionEpoch, callbackSessionEpoch)
}

/**
 * The app label to announce when a failover give-up DISCHARGES a kill-switch event that was
 * deferred by [shouldDeferKillDuringTransition] — or null when nothing may be announced.
 *
 * A rotation has three exits, not two. The two that commit CONNECTED replay the deferred kill;
 * the give-up funnel is the third, and it must drop the event instead: replaying it up to
 * `rotationWindowMs` later would tear down a just-restored tunnel and blame an app that closed
 * long ago. Dropping it silently is not acceptable either — the user asked for the VPN to be off
 * for that app and it is not — hence this notice.
 *
 * [tunnelStillUp] is what keeps the notice honest. It is the give-up's own fd state, so it covers
 * every case in one rule: the two CONTAINED outcomes still own a TUN (a live one, or the blackhole
 * — either way the listed app still sees a VPN, which is exactly what the kill-switch exists to
 * prevent), while UNPROTECTED and the give-up that stops the service own no TUN at all. In those
 * the app is NOT behind a VPN, so telling the user it still is would be the opposite of the truth,
 * and both already report themselves on their own surfaces.
 */
internal fun deferredKillNoticeLabel(
    pendingKillLabel: String?,
    tunnelStillUp: Boolean,
): String? = pendingKillLabel?.takeIf { tunnelStillUp }

/**
 * The screen on/off receiver is SHARED by the kill-switch and failover monitors: hold it while
 * EITHER is live, release it only when NEITHER is.
 *
 * Previously the receiver's whole lifecycle belonged to the kill-switch, which failed failover two
 * ways: with failover on and the kill-switch off (the default pairing) no receiver existed at all,
 * and turning the kill-switch off mid-session tore the receiver out from under a running failover
 * monitor.
 */
internal fun shouldHoldScreenReceiver(killSwitchLive: Boolean, failoverLive: Boolean): Boolean =
    killSwitchLive || failoverLive

/**
 * The health monitor is meaningful only against a live tunnel in the current session.
 *
 * `CONNECTED` only, deliberately: in `PAUSED` the kill-switch has torn the tunnel down on purpose,
 * so every probe would fail and the engine would "rotate" a tunnel nobody wants back yet; in
 * `ROTATING`/`REVIVING`/`STARTING` another owner is mid-transition and will re-apply afterwards.
 */
internal fun shouldRunFailoverMonitor(
    enabled: Boolean,
    running: Boolean,
    tunnelState: SessionTunnelState,
): Boolean = enabled && running && tunnelState == SessionTunnelState.CONNECTED

/**
 * Whether a settings emission changes an input the LIVE monitor already baked in, and therefore
 * requires stopping and rebuilding it.
 *
 * Only the three timing fields qualify — they are constructor arguments of `TunnelHealthMonitor` /
 * `Http204HealthProbe` and cannot be changed on a running instance. `enabled` is handled by
 * [shouldRunFailoverMonitor]; `maxRotations` / `rotationWindowMs` are read fresh at rotation time.
 * An unchanged emission MUST return false: the settings StateFlow re-emits on every save, and
 * rebuilding on each one would restart the poll cycle continuously so the tunnel is never observed.
 * A null [builtFrom] means we have no record of what the live monitor was built from, so rebuild.
 */
internal fun failoverMonitorNeedsRebuild(
    builtFrom: FailoverSettings?,
    next: FailoverSettings,
): Boolean = builtFrom == null ||
    builtFrom.probeIntervalMs != next.probeIntervalMs ||
    builtFrom.probeTimeoutMs != next.probeTimeoutMs ||
    builtFrom.failureThreshold != next.failureThreshold

/**
 * What a give-up must do to end up owning an unread TUN — an fd nobody reads, so packets are dropped
 * instead of falling back to the clear network.
 *
 * The all-servers-dead path tears the TUN down before bring-up fails, so a give-up can otherwise end
 * with no fd at all, and whether the user is exposed would depend on where bring-up died. That is
 * worse than either consistent answer, hence the containment.
 */
internal enum class GiveUpContainment {
    /** Nothing to do: a live tunnel already holds the traffic, or another owner drives it. */
    NONE,

    /** A rotation bridge is held — take it over. It is already exactly the TUN a give-up wants. */
    ADOPT_ROTATION_BRIDGE,

    /** Nothing is held — build a fresh unread TUN. */
    ESTABLISH_BLACKHOLE,
}

/**
 * The single ordered decision behind a give-up's containment step. Replaced the two-valued
 * `shouldEstablishBlackholeTunnel` when [shouldEstablishRotationBridge] gave containment a SECOND
 * source, because the two rules must be evaluated in a fixed order and a pair of independent
 * booleans cannot express that: check "build a blackhole" first and it fires while a bridge is held,
 * stranding the bridge fd and leaving the process holding two VPN interfaces, with no compile-time
 * or runtime signal. An exhaustive `when` over this enum makes that ordering unrepresentable.
 *
 * [hasTunnel] wins outright: an fd already in `tunInterface` (live proxy OR unread containment) is
 * already containing traffic and both other arms would overwrite the field holding it. Whether that
 * fd is still proxying is [classifyGiveUpOutcome]'s job via [TunInterfaceKind], not this one's.
 *
 * `CONNECTED` only, for the reason the blackhole predicate always had: `PAUSED` is the kill-switch's
 * deliberate no-tunnel state and its compliance contract is "no tunnel must exist", so ADOPTING one
 * there breaks it exactly as surely as building one would. Every other state has a different owner
 * mid-transition who will establish (or tear down) itself.
 *
 * Note what this does NOT decide: whether the containment succeeded, or which give-up outcome to
 * report. Both acting arms report success back to [classifyGiveUpOutcome] as `blackholeEstablished`,
 * and an adopted bridge is honestly a blackhole — same builder, same captured apps, no protector, no
 * Xray. That distinction only survives because the caller captures [TunInterfaceKind] BEFORE the
 * adoption (and keeps the kind honest as UNREAD_CONTAINMENT after writing the fd into
 * `tunInterface`), not after a bare `!= null` read.
 */
internal fun containmentForGiveUp(
    hasTunnel: Boolean,
    hasRotationBridge: Boolean,
    tunnelState: SessionTunnelState,
): GiveUpContainment = when {
    hasTunnel -> GiveUpContainment.NONE
    tunnelState != SessionTunnelState.CONNECTED -> GiveUpContainment.NONE
    hasRotationBridge -> GiveUpContainment.ADOPT_ROTATION_BRIDGE
    else -> GiveUpContainment.ESTABLISH_BLACKHOLE
}

/**
 * Whether a rotation must open a **bridge TUN** across the window in which it owns no interface.
 *
 * A rotation tears the old TUN down under `lock` and then does `buildRuntimeConfig`, geo-asset prep
 * and the split-tunnel read OFF-lock before it reaches `establish()`. For that whole span — seconds,
 * on every routine rotation, and once per dead candidate while a pool is exhausted — no VPN
 * interface exists, so every tunneled app emits cleartext on the underlying network. The bridge is
 * the give-up blackhole re-used as a stop-gap: same builder, same captured apps, no protector and no
 * Xray, so apps briefly lose connectivity instead of briefly leaking. That is the intended trade.
 *
 * [hasRotationBridge] is what makes this idempotent, and it is load-bearing rather than defensive:
 * one rotation episode walks N dead candidates through the same reserved transition, re-entering the
 * teardown path each time. A second establish would strand the first fd — a leaked VPN interface,
 * held until the process dies.
 *
 * [hasTunnel] must be false for the same reason [containmentForGiveUp] refuses to act on a live
 * tunnel: `establish()` replaces the process's active interface, so opening a bridge over a working
 * tunnel would silently turn it into a blackhole.
 *
 * `ROTATING` only. An initial connect has no prior tunnel to bridge from, and a `reviveTunnel`
 * starts from `PAUSED`, where the absence of a TUN is the kill-switch's deliberate intent.
 */
internal fun shouldEstablishRotationBridge(
    hasTunnel: Boolean,
    hasRotationBridge: Boolean,
    tunnelState: SessionTunnelState,
): Boolean = !hasTunnel &&
    !hasRotationBridge &&
    tunnelState == SessionTunnelState.ROTATING

/**
 * Whether a rotation that needed a bridge must abort rather than rebuild with no VPN interface.
 *
 * The caller has already torn the live TUN down under `lock`. Proceeding without a bridge reopens
 * the clear-network window for the whole off-lock rebuild. Aborting funnels into give-up, which
 * tries blackhole containment (or reports UNPROTECTED honestly if that also fails).
 *
 * [bridgeRequired] is the value of [shouldEstablishRotationBridge] just checked; [bridgeHeld] is
 * whether [establishRotationBridge] actually left an fd in `rotationBridgeInterface`.
 */
internal fun shouldAbortRotationForMissingBridge(
    bridgeRequired: Boolean,
    bridgeHeld: Boolean,
): Boolean = bridgeRequired && !bridgeHeld

/**
 * What a failover give-up actually left behind. Three physically different situations that must
 * never share one message to the user.
 */
internal enum class FailoverGiveUpOutcome {
    /** No tunnel existed and a blackhole was established: traffic is deliberately dropped. */
    CONTAINED_BY_BLACKHOLE,

    /**
     * The current profile's tunnel is still up and still proxying — the no-candidate and thrash-cap
     * give-ups both run BEFORE any teardown. Nothing was blocked; there was simply nowhere to
     * rotate to. Telling this user "your traffic is blocked" would be false.
     */
    CONTAINED_BY_LIVE_TUNNEL,

    /** No tunnel and the blackhole could not be established: the user IS on the clear network. */
    UNPROTECTED,
}

/**
 * What [com.justme.xtls_core_proxy.vpn.XrayVpnService]'s `tunInterface` currently holds — not merely
 * whether an fd exists. A blackhole give-up (or an adopted rotation bridge) writes an unread fd into
 * that field; treating "fd present" as "still proxying" is the across-give-ups misclassification.
 */
internal enum class TunInterfaceKind {
    /** No fd in `tunInterface`. */
    NONE,

    /** Live, still-proxying tunnel (protector + Xray behind the fd). */
    LIVE_PROXY,

    /** Unread containment fd — give-up blackhole or adopted rotation bridge. */
    UNREAD_CONTAINMENT,
}

/**
 * Classifies a give-up from the kind of TUN held *before* the containment step, and whether a
 * blackhole (or bridge adoption) then succeeded.
 *
 * [TunInterfaceKind.LIVE_PROXY] and [TunInterfaceKind.UNREAD_CONTAINMENT] both win outright — an fd
 * is already containing traffic, so [blackholeEstablished] carries no information in those cases.
 * They must not share one outcome: unread containment is drop-only, never "still proxying".
 *
 * Within one give-up, [heldKind] is captured before adoption/establish writes the unread fd into
 * `tunInterface`. Across give-ups, the service must keep [TunInterfaceKind] honest after that write
 * — a boolean `tunInterface != null` is not enough.
 */
internal fun classifyGiveUpOutcome(
    heldKind: TunInterfaceKind,
    blackholeEstablished: Boolean,
): FailoverGiveUpOutcome = when (heldKind) {
    TunInterfaceKind.LIVE_PROXY -> FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL
    TunInterfaceKind.UNREAD_CONTAINMENT -> FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE
    TunInterfaceKind.NONE -> when {
        blackholeEstablished -> FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE
        else -> FailoverGiveUpOutcome.UNPROTECTED
    }
}

/**
 * The user-facing connection state a give-up outcome produces.
 *
 * Extracted from the service so the branch cannot be inverted silently: this is the ONLY runtime
 * producer of [VpnConnectionState.BLACKHOLED], and BLACKHOLED is a live, stoppable state whereas
 * ERROR is not. It was extracted because its three pure siblings ([classifyGiveUpOutcome],
 * [shouldStopServiceOnGiveUp], [shouldRestartForRecovery]) already had tests while this branch was
 * still inline in the service and had none. It is covered now — `SessionLifecycleDecisionTest`'s
 * `onlyTheUncontainedGiveUpMapsToError` pins all three outcomes.
 */
internal fun connectionStateForGiveUp(outcome: FailoverGiveUpOutcome): VpnConnectionState =
    when (outcome) {
        FailoverGiveUpOutcome.UNPROTECTED -> VpnConnectionState.ERROR
        FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE,
        FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> VpnConnectionState.BLACKHOLED
    }

/**
 * The 1101 / home / tile line a give-up must show, from the outcome that produced it — or null when
 * no give-up describes this session.
 *
 * The service records the ANSWER, not the input: `repostOngoingNotification` used to re-derive this
 * from the live `giveUpOutcome` field, and the disable branch clears that field while
 * DELIBERATELY leaving the connection state BLACKHOLED. A user swipe plus repost after such a
 * release therefore fell through to the blackhole copy and relabelled a still-proxying tunnel
 * "your traffic is being held" — or, in the other direction, would tell a user behind a blackhole
 * that their server merely stopped responding. Restoring `giveUpOutcome` is not the fix: that field
 * is the "an automatic recovery is still owed" marker `shouldRestartForRecovery` keys off, and its
 * release is load-bearing. The same recorded answer is published on [LogRepository.giveUpLine]
 * so home/tile cannot share one string across opposite packet truths.
 *
 * **It covers all three outcomes, including the one that does NOT render as BLACKHOLED.** It was
 * once null for [FailoverGiveUpOutcome.UNPROTECTED], scoped to the BLACKHOLED state on the grounds
 * that `connectionStateForGiveUp` sends UNPROTECTED to `ERROR`. The consequence was that home and
 * the QS tile — the two most-looked-at surfaces — labelled an uncontained give-up with the generic
 * "Error", the same string an ordinary failed connection shows, while the banner, 1101 and 1105 all
 * said "not protected". `ERROR` cannot tell the two apart on its own, so the outcome has to reach
 * the UI, and this is the mechanism that already carries one. Three outcomes, three lines, no two
 * shared — [connectionStateForGiveUp] decides which STATE hosts each line, never whether one exists.
 */
internal fun giveUpOngoingLine(
    containment: FailoverGiveUpOutcome?,
): GiveUpOngoingLine? = when (containment) {
    FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> GiveUpOngoingLine.STILL_PROXYING
    FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE -> GiveUpOngoingLine.TRAFFIC_HELD
    FailoverGiveUpOutcome.UNPROTECTED -> GiveUpOngoingLine.UNPROTECTED
    null -> null
}

/**
 * "Disconnect now, stop if the re-arm fails": whether a give-up should switch the service off
 * rather than schedule another re-arm.
 *
 * Only [FailoverGiveUpOutcome.UNPROTECTED] can ever stop the service, and only once its single
 * automatic recovery attempt has already been spent ([unprotectedRetryConsumed]). The first
 * unprotected give-up must NOT stop: forfeiting the re-arm would switch the VPN off without the
 * user asking. The two contained outcomes never stop at all — traffic is held in a tunnel either
 * way, so there is nothing to be honest about turning off.
 */
internal fun shouldStopServiceOnGiveUp(
    outcome: FailoverGiveUpOutcome,
    unprotectedRetryConsumed: Boolean,
): Boolean = outcome == FailoverGiveUpOutcome.UNPROTECTED && unprotectedRetryConsumed

/**
 * What the re-arm timer of an UNPROTECTED give-up must do when it fires. See
 * [unprotectedRetryAction].
 */
internal enum class UnprotectedRetryAction {
    /** Reserve a rotation now. This is what SPENDS the single automatic recovery. */
    ATTEMPT,

    /** Nothing can be attempted yet; re-arm the timer WITHOUT spending the recovery. */
    DEFER,

    /** Deferring has run out of time: stop a service that is running and protecting nothing. */
    STOP_SERVICE,
}

/**
 * How long deferring may go on, expressed in rotation windows rather than as a fixed duration, so
 * a user who asked for hour-long windows is never stopped before their first window has elapsed.
 */
internal const val UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS = 3

/**
 * What an UNPROTECTED give-up's re-arm timer must do at the moment it fires.
 *
 * The single automatic recovery an UNPROTECTED give-up is granted must be spent by an ATTEMPT, not
 * by the decision to schedule one. It used to be marked consumed at SCHEDULE time, which broke the
 * feature's absolute bound in one specific, entirely reachable way: a kill-switch pause landing
 * before the timer fired left `rotateTunnel` bailing at `canReserveRotation`, so no second give-up
 * ever happened, so [shouldStopServiceOnGiveUp] could never fire — and the foreground service went
 * on running with NO TUN until the user intervened.
 *
 * The three arms are the three halves of that bound:
 *  - `CONNECTED` is exactly [canReserveRotation]'s requirement, so it is the ONLY state in which a
 *    dispatched rotation can do anything. Acting beats the deadline: the attempt is itself a
 *    terminating path (it restores a tunnel, or produces the second give-up that stops the
 *    service), so an over-deadline session that has become actionable must still attempt.
 *  - Any other state has another owner mid-transition, or is the kill-switch's deliberate `PAUSED`.
 *    A recovery that could not even be attempted must NOT silently forfeit the bound, so re-arm.
 *  - Deferring cannot repeat forever, or the bound is gone again by a different route. Once the
 *    unprotected episode has outlasted [UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS] windows without
 *    once becoming actionable, land in an honest OFF state.
 *
 * [now] is a parameter, never read from a clock here, so every one of those thresholds is testable
 * without waiting — the same discipline `FailoverDecision.admitRotation` follows.
 */
internal fun unprotectedRetryAction(
    tunnelState: SessionTunnelState,
    unprotectedSinceMs: Long,
    now: Long,
    rotationWindowMs: Long,
): UnprotectedRetryAction = when {
    tunnelState == SessionTunnelState.CONNECTED -> UnprotectedRetryAction.ATTEMPT
    now - unprotectedSinceMs < rotationWindowMs * UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS ->
        UnprotectedRetryAction.DEFER
    else -> UnprotectedRetryAction.STOP_SERVICE
}

/**
 * Whether a give-up's re-arm timer may still act when it finally fires.
 *
 * The timer is scheduled under one set of preferences and fires up to an hour later under another,
 * so it must re-check [failoverEnabled] at the firing point rather than trusting the settings that
 * authorised it. Without this a user who turned auto-failover OFF could still be handed an
 * automatic server rotation — and, on the unprotected retry path, an automatic VPN shutdown.
 * Cancelling the job on disable is the root fix; this is the backstop for a timer that fires
 * concurrently with the settings edit.
 */
internal fun shouldFireFailoverRetry(
    failoverEnabled: Boolean,
    isCurrentSession: Boolean,
): Boolean = failoverEnabled && isCurrentSession

/**
 * Whether re-enabling auto-failover must reschedule the UNPROTECTED recovery re-arm.
 *
 * Disable cancels the pending re-arm job — correct for the disable half (see
 * [shouldFireFailoverRetry]). That reasoning does **not** cover re-enable: without a restore, the
 * health monitor may start and probe the clear network forever while no automatic recovery or stop
 * ever runs.
 */
internal fun shouldRestoreUnprotectedRearm(
    failoverEnabled: Boolean,
    giveUpOutcome: FailoverGiveUpOutcome?,
    hasTunnel: Boolean,
    rearmJobActive: Boolean,
): Boolean = failoverEnabled &&
    giveUpOutcome == FailoverGiveUpOutcome.UNPROTECTED &&
    !hasTunnel &&
    !rearmJobActive

/**
 * Whether an incoming start request should RESTART the running session instead of taking
 * `startVpn`'s "VPN already running" early return.
 *
 * Deliberately narrow: only the UNPROTECTED give-up, where the service is running but owns no
 * tunnel and traffic is not contained, so a start is the user acting on copy that told them to turn
 * the VPN off and on again or pick another server. Everything else keeps the early return, because
 * the tile, `START_REDELIVER_INTENT` crash recovery and stray/duplicated intents all depend on
 * "start while running" being idempotent — a general restart would let a stray intent bounce a
 * perfectly healthy tunnel.
 *
 * ## NEVER widen this beyond UNPROTECTED — and the two contained outcomes are excluded for
 * DIFFERENT reasons
 *
 * They are stated separately on purpose. Collapsing them into the weaker, shared one ("they still
 * hold a TUN") is precisely the rationale a maintainer would feel safe widening, and widening it
 * deadlocks a VPN service on the main thread.
 *
 * - **`CONTAINED_BY_LIVE_TUNNEL` holds a RUNNING XRAY CORE.** This predicate unlocks a
 *   `stopVpn(stopService = false)` that runs on the **main thread**, inside `onStartCommand`'s held
 *   admission block. That call is a fast no-op today only because UNPROTECTED implies an
 *   already-stopped core (`StopXray` returns immediately when `instance == nil`). Admitting this
 *   outcome turns it into a real `instance.Close()` plus an fd close on the main thread. **That is
 *   RISK-1**, documented in `AGENTS.md` and in `docs/features/auto-failover.md`. It is also why the
 *   sibling rule holds: never add anything that awaits to `stopVpn`.
 * - **`CONTAINED_BY_BLACKHOLE` holds no running core** — it is classified with
 *   [TunInterfaceKind.NONE] (or already-[TunInterfaceKind.UNREAD_CONTAINMENT] on a later give-up),
 *   i.e. after `tearDownTunnelLocked()` has already called `stopXray()`, and the blackhole builder
 *   starts none. RISK-1 therefore does not apply to it. It is excluded for the *other* reason: it
 *   **still holds a TUN, so there is nothing for a restart to rescue** — plus the general one above
 *   that keeps "start while running" idempotent.
 *
 * Both contained outcomes reach a live tunnel again through `state/ReconnectFlow` instead, whose
 * marked `ACTION_STOP` is already marshalled onto the service's `tunnelOpScope` and calls
 * `stopVpn(stopService = false)`. No blocking call lands on the main thread, while explicit user
 * Stops retain their normal `stopSelf()` semantics.
 */
internal fun shouldRestartForRecovery(
    running: Boolean,
    giveUpOutcome: FailoverGiveUpOutcome?,
): Boolean = running && giveUpOutcome == FailoverGiveUpOutcome.UNPROTECTED

/**
 * Which profile id must be written back to `ActiveProfileRepository` when `startVpn` takes its
 * "VPN already running" early return — or `null` when nothing should be written.
 *
 * Every connect path (the per-server rows, the profile-actions dialog, the QS tile, always-on via
 * `resolveActiveAndStart`, connect-to-fastest) records the requested profile as active BEFORE
 * dispatching the start. When the start is refused, traffic keeps flowing through
 * [currentProfileId] while the UI and the tile would go on labelling [requestedProfileId] as the
 * connected server — the one fact a VPN client must never get wrong.
 *
 * Applies ONLY to the refusal; the UNPROTECTED recovery restart really does go on to start
 * [requestedProfileId], so it must keep the caller's write. `null` covers the two cases where a
 * write would be wrong or pointless: the request already matches the running session, and no real
 * session profile exists (the field carries -1L when unset and Room never issues a non-positive id,
 * so restoring one would point the app at nothing).
 */
internal fun activeProfileIdToRestoreOnRefusedStart(
    requestedProfileId: Long,
    currentProfileId: Long,
): Long? = currentProfileId.takeIf { it > 0L && it != requestedProfileId }

/**
 * Whether switching auto-failover OFF must also release a give-up state it left behind.
 *
 * Disabling cancels the re-arm job, which is the ONLY automatic recovery from a **contained**
 * give-up. Leaving `giveUpOutcome` set would therefore strand the user behind a blackhole TUN as
 * a direct result of turning the feature off — the most natural reaction to it having gone wrong.
 *
 * [FailoverGiveUpOutcome.UNPROTECTED] is DELIBERATELY EXCLUDED, and the exclusion is the whole
 * point of this predicate being three-valued rather than a null check. That outcome has no re-arm
 * to be stranded behind: it owns no TUN, and its recovery is the `startVpn` restart that
 * [shouldRestartForRecovery] unlocks — keyed off this very marker. Releasing it would leave the
 * service RUNNING, holding no tunnel, with the user on the clear network and every Connect surface
 * taking `startVpn`'s "VPN already running" early return; with the monitor stopped no second
 * give-up could fire [shouldStopServiceOnGiveUp] either, so nothing would ever end that state.
 * Only Disconnect would work, and the loudest warning would have been retracted on the way in.
 */
internal fun shouldReleaseGiveUpOnDisable(
    enabled: Boolean,
    giveUpOutcome: FailoverGiveUpOutcome?,
): Boolean = !enabled &&
    giveUpOutcome != null &&
    giveUpOutcome != FailoverGiveUpOutcome.UNPROTECTED

/**
 * Whether an incoming start request may claim the single `pendingProfileId` slot.
 *
 * THREE callers write that slot: the manual Connect lambda, connect-fastest's winner delivery, and
 * the QS-tile hand-off. A system permission dialog can park any of them in it for minutes, and that
 * dialog is a modal CONTINUATION of the request that raised it — so answering it must complete THAT
 * request. Hence one uniform rule, "whichever request parked first wins", rather than a per-source
 * hierarchy: no refusal occurs on an overwrite, so nothing downstream would ever notice the
 * substitution, and a rule that applied to only some of the three writers would be an asymmetry the
 * user has no way to observe or predict.
 */
internal fun shouldOverwritePendingConnect(pending: Long, incoming: Long): Boolean =
    pending == -1L || pending == incoming
