# Ping Test — Handshake-Latency Probe per Server and Group

Maintainer reference for the ping-test feature: per-server and per-group latency probes that measure an
end-to-end proxied HTTP 204 handshake and display the result inline in the server list.

## Why end-to-end 204, not a raw TCP/TLS probe

A raw Kotlin TCP+TLS connection to a server's host and port is not a faithful measurement for two
reasons rooted in how this app's supported protocols behave:

1. **REALITY camouflage-fallback.** When the REALITY public key or shortId is wrong, the server does
   not reject the connection — it silently forwards the client to the camouflage site it fronts. A raw
   TLS handshake therefore *succeeds* against the camouflage certificate and would report a misleading
   green latency. The only way to confirm that traffic actually egressed *through* the proxy is to send
   a real request through it and verify the expected response.
2. **Hysteria2 is QUIC/UDP.** The JVM cannot speak QUIC natively; only xray-core can dial a Hysteria2
   outbound.

The default target is `http://cp.cloudflare.com/generate_204`. Success is defined as: **dial the
configured plaintext HTTP target through the outbound, receive HTTP 204, and measure the round-trip
time.** This is the same "real delay" definition used by v2rayNG and NekoBox, and it is the only
definition that catches the REALITY camouflage-fallback case. Any failure — dial error, non-204
status, timeout, or unbuildable config — maps to `N/A`.

### The `http://` constraint

The editable preference accepts any nonempty value beginning with `http://`. The default target omits
its port, so the Go probe uses port 80; an explicit numeric port in the URL is also supported. The
probe connects raw TCP to that host and port via `core.Dial`, then writes a plain HTTP/1.1 `GET` and
requires a 204 response. There is no TLS layer on the target connection, so `https://` is rejected at
the preference layer. The proxy outbound itself may be TLS/QUIC; the final hop to the configured
HTTP/204 endpoint is plaintext on purpose.

`PingPreferences.isValidTarget` is deliberately **minimal by design** (Plan-3 R7, Option A — locked):
after trimming, it accepts any string starting with `http://` (case-insensitive) with a non-empty
remainder, and validates nothing else — not host, port range, embedded credentials, or path. So
`http://user:pass@example.com/`, `http://example.com:99999/`, and `http://???` all pass the UI gate and
fail harmlessly downstream in Go as `N/A` (never a crash or leak). The permissive boundary is pinned by
`PingTargetValidationTest.accepts_malformed_targets_scheme_gate_is_deliberately_minimal`; tightening it
needs a new maintainer decision, and the optional Go port range-check (R7 Option B) was deliberately not
taken.

## Transient instance and global `protect()` interaction

`MeasureLatency` in [`xray-go/xray_bridge.go`](../../xray-go/xray_bridge.go) builds a throwaway
`core.Instance` using `core.New` + `Start`, dials via `core.Dial`, and closes the instance when done.
It **never locks `mu` and never reads or writes the global `instance`** that `StartXray`/`StopXray`
manage, so a probe runs safely alongside a live tunnel without disturbing it.

Socket protection is free: 2A's `RegisterProtector` installs **one process-global**
`internet.RegisterDialerController` callback under `sync.Once` (see
[`docs/features/failclosed-startup.md`](failclosed-startup.md)). That controller covers every socket
dialed by xray-core's default dialer — including the throwaway probe instance. Concretely:

- **Tunnel up:** the probe's sockets are automatically carved out of the tun by the already-registered
  `protect()` callback and egress directly to the proxy server. No new protection wiring is needed.
- **Before any registration in this process:** `currentProtector` is `nil`, so the installed
  controller (if present) is a no-op.
- **After registration:** `currentProtector` remains the most recently registered callback until a
  later `RegisterProtector` call replaces it or the process dies. `StopXray` and VpnService teardown
  do not clear it, so do not document a post-service `nil` transition that the bridge does not
  implement.

This interaction is a **must-verify-on-device point**: run a group test while connected to confirm
probes return results without disturbing the live tunnel (see Testing below).

## Data flow

```
User taps speedometer on group header  (or "Ping test" in long-press island dialog — see [profile-actions-menu.md](profile-actions-menu.md))
        │
VpnViewModel.pingTestGroup(profiles)  /  pingTestProfile(profile) = pingTestGroup(listOf(profile))
  ├─ loads PingPreferences fresh at probe admission
  ├─ captures the stable pingCoordinator (a val — never swapped) and passes this run's concurrency in
  ├─ emits PingState.Testing for each freshly-accepted id → rows show a spinner
  └─ launches on viewModelScope; calls pingCoordinator.runGroup(ids, concurrency, onUpdate, probe)
        │
PingCoordinator.runGroup  (single stable admission owner)
  ├─ cross-run de-dup: gate.withLock { ids.filter { inFlight.add(it) } } — an id admitted by ANY
  │  active run is not re-admitted, even across a concurrency change
  ├─ per-run limit: a LOCAL Semaphore(concurrency) built inside this run (a concurrency change cannot
  │  reset the shared inFlight set or spawn a second ceiling)
  └─ for each fresh id: calls injected probe: suspend (Long) -> PingState; streams via onUpdate immediately
     (PingCoordinator never calls ConfigBuilder directly — the probe lambda is probeProfile in VpnViewModel)
        │
ConfigBuilder.toPingTestConfig(storedConfig): String  ← called by probeProfile in VpnViewModel
  └─ calls buildRuntimeConfig (DoH, ForceIP, outbounds preserved)
     then removes the "inbounds" array  ← no tun inbound; probe uses core.Dial
     then stripGeoRoutingRules  ← drops geoip:/geosite:/ext: rules (probe has no geo assets)
        port-53 → dns-out and other non-geo routing rules are kept
        │
probeProfile → pingCoordinator.probeWithBackstop(scope, backstopMs, ctx, onAdmissionRejected, onBackstop, nativeCall)
  ├─ tryAcquire a fixed native slot (ceiling = PingPreferences.CONCURRENCY_MAX); saturated → Unavailable now
  ├─ launches the JNI child on scope; releases the native slot in the child's finally AFTER nativeCall returns
  └─ caller: withTimeoutOrNull(backstopFor(timeout)) — at the backstop returns Unavailable WITHOUT
     freeing the slot (the orphaned native call keeps it until it actually returns)  ← timeout + 5 s
        │
XrayBridge.measureLatency(configJson, targetUrl, timeoutMs): Result<Long>
  └─ reflection facade, same pattern as startXray; parses {"latencyMs":N} | {"error":"..."}
        │
xray-go: MeasureLatency(configJson, targetURL, timeoutMs) string
  └─ throwaway core.Instance → core.Dial(ctx, inst, dest)
     → raw HTTP GET /generate_204 → require 204 → return {"latencyMs":N}
        │
pingStates: StateFlow<Map<Long, PingState>>  (ViewModel, ephemeral)
  └─ each result written immediately (streaming); rows recompose as results arrive
```

### Why results are ephemeral

`pingStates` lives in `VpnViewModel` — in-memory, keyed by profile id, cleared on process death. There
is no DB column and no Room migration. This is intentional: a latency measured on a different network
or at a different time can mislead rather than inform. Fresh state on every app start avoids
stale-trust.

`VpnViewModel.init` also prunes stale entries when the profile set changes (e.g. a subscription
refresh replaces rows with new ids): it collects `profiles` and runs
`_pingStates.update { states -> states.filterKeys { it in ids } }` so orphaned id → `PingState`
mappings do not accumulate. The UI only reads ids present in the current view, so this is
housekeeping rather than user-visible behavior.

## UI entry points

- **Whole-group test:** speedometer icon (`res/drawable/ic_speedometer.xml`) on each group header —
  including "My profiles". While the group is testing the icon shows a spinner; tapping again is a
  no-op (no cancel in v1).
- **Single-server test:** "Ping test" entry in the long-press island dialog (`ProfileActionsDialog` —
  `BasicAlertDialog`, not a bottom sheet; see [profile-actions-menu.md](profile-actions-menu.md)). No
  per-row gauge icon; the result appears as a badge line below the server name (mirroring the
  `sanitizedDns` badge).
- **Connect to fastest:** a *third* caller of the same machinery, in the same long-press dialog. It
  probes the long-pressed profile's failover pool and connects to the fastest responder. See
  "Connect-to-fastest as a coordinator caller" below and
  [auto-failover.md](auto-failover.md) / [profile-actions-menu.md](profile-actions-menu.md).
- **"My profiles" group:** manually-added profiles (`subscriptionId == null`) are gathered into a
  synthetic expandable group. It has no DB row; the expand-state key is the string literal `"manual"`.
  The group has no refresh action and no "last seen" subtitle, but it does have the speedometer and is
  testable exactly like a real subscription group.
- **Auto-ping on open:** once the profile set loads, `MainActivity` probes
  `VpnViewModel.autoPingProfiles` — the full server set taken straight from the single flat `profiles`
  Room query (`autoPingServers`), **not** the subscription-grouped render union. If the fresh
  preference enables auto-ping and the process-scoped `AutoPingLatch` is unconsumed,
  `AutoPingLatch.consume()` (an atomic compare-and-set) claims the latch and only the winner probes
  the set. **Sourcing from the flat query is load-bearing:** the grouped view folds subscription
  servers in only after the *separate* `subscriptions` Room query loads, so an auto-ping keyed on that
  union raced the second query and skipped every subscription server ~30% of launches (a
  nondeterministic "sometimes works" flake). The flat `profiles` query is atomic — it already carries
  manual and subscription-imported rows in one emission — and, via the `Profile` CASCADE foreign key,
  is free of orphans, so it equals the rendered union with no dependency on the subscriptions query.
  `autoPingServers` orders the set to match the list UI — manual ("My profiles") servers first, then
  subscription servers — using only per-row fields (`subscriptionId`, `id`), never the `subscriptions`
  list, so the probe/spinner order can't flake on that list's load timing either.
  Because the latch is a top-level `object` (JVM process lifetime), it runs **once per app launch**:
  Activity recreation (rotation/theme), a new `VpnViewModel`, ordinary navigation, resume, and
  recomposition all observe the consumed bit and do not repeat it. Only process death re-arms it.

## Connect-to-fastest as a coordinator caller

[`failover/FastestConnectRunner`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestConnectRunner.kt)
is a third caller of `PingCoordinator.runGroup`. **`PingCoordinator`, `PingTester` and `AutoPingLatch`
are UNMODIFIED by it** — it composes on top of the existing API rather than changing it. Two
consequences a maintainer needs to know:

- **It introduces cancellation, which this feature previously had none of.** Group and single tests
  are fire-and-forget (tapping again is a no-op, no cancel in v1). A connect-fastest run can last
  `timeout × ceil(n / concurrency)` — minutes at the preference bounds — so it has a visible Cancel.
  An explicit Cancel, or a new request for a **different** `FailoverPoolResolver` partition,
  supersedes (cancels) the active run. A request for the **same** partition coalesces onto it
  instead: cancelling its waiters while its uninterruptible native probes still own slots would make
  the immediately-restarted equivalent pool report prompt `N/A`. The existing run produces the one
  eventual winner. `runGroup` rethrows a caller-cancellation `CancellationException` from inside its
  per-id `finally` **before** calling `onUpdate` for that id, so a cancelled in-flight id never
  receives a terminal `PingState` of its own and its row would spin on `Testing` forever.
  `failover/FastestPick.clearStaleTesting(states, ids)` (pure, unit-tested) resets exactly *that
  run's* ids from `Testing` back to `Idle` in the runner's cleanup. It is scoped to this run's
  resolved pool, so it never touches a row outside it — but note the limit of that guarantee: the
  pool includes ids `runGroup` deduped and never admitted, so an overlapping concurrent group ping
  can briefly have one of its own `Testing` rows reset to `Idle` here. Self-healing — that run writes
  its own terminal state when it finishes.
- **`inFlight` and the native-slot accounting are deliberately left alone on cancel.** The orphaned
  probe's own `finally` releases the native slot when the JNI call actually returns; `inFlight` is
  released by `runGroup`'s uncontended `Mutex` fast path, which completes even on a cancelled
  coroutine.

**Known limitation — cross-run dedup can produce a stale winner.** `runGroup`'s cross-run de-dup skips
ids already in flight from another run (launch-time auto-ping probes *every* profile, so this is easy
to hit). Those ids get no fresh probe, and `pickFastest` reads whatever is in `pingStates` — which may
be a **stale `Success` from an earlier run**, producing a winner chosen from non-fresh data with **no
message to the user**. The runner detects "at least one pool id was already `Testing` when I started"
and reports `FastestConnectOutcome.BUSY` instead of `NO_RESPONSE` when *nothing* wins, but that
heuristic does not cover the stale-success case. Not fixed here: the fix belongs in `PingCoordinator`
(e.g. letting a caller await another run's result for a deduped id), which Task 10 was forbidden to
modify.

## Preferences and bounds

`PingPreferences` stores four global values in `xray_prefs` and is read **fresh when a probe is
accepted**; unlike VPN connection tuning, ping settings have no session-capture rail.

**The target URL is ping-only.** It used to have a second consumer —
[auto-failover](auto-failover.md)'s tunnel health probe read `PingPreferences.load(...).targetUrl` in
`applyFailoverPreferences` — and that coupling has been **removed**. The health probe now uses the
fixed `ConfigBuilder.HEALTH_PROBE_TARGET_URL`, because `ConfigBuilder` carves that exact host through
the proxy in every routing mode (a static routing rule cannot cover an editable target) and because
`isValidTarget`'s deliberately minimal prefix check let a non-204 URL fail every health probe forever,
driving a rotation storm over healthy servers. Editing the target here now affects the Ping Test only.

| Preference | Default | Validation / meaning |
|---|---|---|
| Target URL | `http://cp.cloudflare.com/generate_204` | Must start with `http://` and contain a value after the scheme. It is a plaintext HTTP 204 target by design; `https://` is invalid. |
| Timeout | `10_000 ms` | Clamped and UI-validated to `1_000..30_000 ms`; passed to Go for dial + request. |
| Concurrency | `3` | Clamped and UI-validated to `1..5`; maximum simultaneous group probes. |
| Auto-ping on open | off | Once per app launch (process-scoped `AutoPingLatch`), after profiles load; re-arms only on process death. |

`PingTester.backstopFor(timeoutMs)` derives the Kotlin wall-clock backstop as the selected timeout plus
`BACKSTOP_MARGIN_MS` (5 seconds), preserving the invariant that the backstop is strictly above the Go
deadline. `VpnViewModel` holds one `PingCoordinator` as a stable `val` and **never** reconstructs it:
each run passes its freshly-loaded concurrency into `runGroup`, so the coordinator's cross-run dedup set
and its fixed native-slot ceiling survive concurrency changes. `PingTester` itself is now just a
constants/`backstopFor` holder (`object`); the old `PING_BACKSTOP_MS` constant was removed once the live
caller migrated to `backstopFor`.

## Components

| File | Responsibility |
|---|---|
| [`state/PingState.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingState.kt) | Sealed UI state: `Idle`, `Testing`, `Success(latencyMs: Long)`, `Unavailable`. `fromResult(Result<Long>)` maps `runCatching` outcomes. |
| [`state/PingTester.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingTester.kt) | Constants/`backstopFor` holder (`object`): `DEFAULT_PING_CONCURRENCY`, `PING_TIMEOUT_MS`, `PING_TEST_TARGET`, `BACKSTOP_MARGIN_MS`, and `backstopFor(timeoutMs)`. Orchestration moved to `PingCoordinator`; the obsolete `PING_BACKSTOP_MS` was removed. |
| [`state/PingCoordinator.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingCoordinator.kt) | Single stable admission owner: `runGroup(ids, concurrency, onUpdate, probe)` with a `Mutex`-guarded cross-run `inFlight` de-dup set + a per-run local `Semaphore`; `probeWithBackstop(...)` bounds orphaned JNI probes with a fixed `nativeSlots` ceiling (`= PingPreferences.CONCURRENCY_MAX`) released only in the child's `finally` after the native call returns. `CancellationException` propagates; other `Throwable` → `Unavailable`. |
| [`state/AutoPingLatch.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/AutoPingLatch.kt) | Process-scoped once-per-launch latch (`object` + `AtomicBoolean`): `consume()` = atomic compare-and-set, `isConsumed`, non-persisted `resetForTest()`. Re-arms only on process death. |
| [`state/PingPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/PingPreferences.kt) | `xray_prefs` persistence, `http://` target validation, timeout/concurrency bounds, and defaults tied to `PingTester`. |
| [`state/VpnViewModel.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) | `pingStates` (ephemeral); fresh-at-probe preferences; one stable `pingCoordinator` `val`; `pingTestGroup`/`pingTestProfile` → `runGroup`; `probeProfile(...)` → `probeWithBackstop` with the derived backstop. `autoPingProfiles` (the race-free launch-time server set, via the top-level `autoPingServers`) sources auto-ping from the flat `profiles` query, not the subscription-grouped view. Auto-ping is gated by the process-scoped `AutoPingLatch`, not a ViewModel field. |
| [`config/ConfigBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt) | `toPingTestConfig(stored: String): String` — calls `buildRuntimeConfig` (DoH, ForceIP, outbounds preserved) then removes the `inbounds` array and `stripGeoRoutingRules` (drops `geoip:`/`geosite:`/`ext:` rules that fail in the geo-asset-less throwaway instance; keeps port-53 → `dns-out` and other non-geo rules). |
| [`MainActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt) | Owns `VpnViewModel` through Activity `viewModels()`, renders speedometer/results, wires manual probes, and triggers auto-ping off `viewModel.autoPingProfiles` (the flat profile query — race-free vs. the subscription groups) once that set becomes non-empty. |
| [`ProfileActionsDialog.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/ProfileActionsDialog.kt) | Long-press island menu (`BasicAlertDialog`) with a "Ping test" row (`ic_speedometer`); see [profile-actions-menu.md](profile-actions-menu.md). |
| [`settings/PingTestSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/settings/PingTestSettingsActivity.kt) | Target, timeout, concurrency, and auto-on-open editor with exact validation bounds. |
| [`bridge/XrayBridge.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt) | `measureLatency(configJson, targetUrl, timeoutMs: Long): Result<Long>` — reflects `MeasureLatency`/`measureLatency` (3 params); handles gomobile's int/long type variance for the timeout arg; parses the JSON result; logs failures to `LogRepository`. |
| [`xray-go/xray_bridge.go`](../../xray-go/xray_bridge.go) | `MeasureLatency(configJson, targetURL string, timeoutMs int) string` — throwaway `core.Instance`; `core.Dial` to target; raw HTTP 1.1 GET; requires status 204; returns `{"latencyMs":N}` or `{"error":"..."}`. Never locks `mu`; never touches global `instance`. |
| [`res/drawable/ic_speedometer.xml`](../../app/src/main/res/drawable/ic_speedometer.xml) | Custom vector drawable for the group-header speedometer / spinner affordance. `material-icons-extended` was not added for a single glyph. |

## Error handling

| Failure mode | Outcome |
|---|---|
| `toPingTestConfig` throws (bad stored config) | Caught in `probeProfile`; `PingState.Unavailable` + `LogRepository` message. |
| Go returns `{"error":"..."}` (dial fail, write/read fail, non-204, timeout) | `XrayBridge.measureLatency` wraps as `Result.failure`; `PingState.Unavailable`. |
| `MeasureLatency` called with empty config | Returns `{"error":"empty config"}` immediately. |
| `url.Parse` fails or hostname is empty | Returns `{"error":"bad target url: ..."}` immediately → `N/A`. The preference validator checks only the nonempty `http://` prefix, so some malformed values can reach this guard. |
| Target port is non-numeric | Returns `{"error":"bad target port: ..."}` immediately → `N/A`. Explicit numeric ports are supported; malformed port text can pass the preference-layer prefix check. |
| `probeProfile` exceeds `backstopFor(configuredTimeout)` waiting on JNI | `withTimeoutOrNull` returns null; row → `N/A` + `LogRepository` backstop message. The orphaned `async` probe may still finish later; its result is discarded. |
| Persisted target has an invalid scheme or nothing after `http://` | `PingPreferences.load` falls back to the default plaintext `http://` HTTP-204 endpoint. Other malformed `http://` values can pass this layer and fail in Go as described above. |
| REALITY wrong key — camouflage-fallback HTML page | HTTP status is 200 (or similar), not 204 → `{"error":"unexpected status N"}` → `N/A`. This is the key correctness guarantee. |
| Profile already `Testing` when a re-test arrives | `PingTester` de-duplicates in-flight ids; the duplicate is silently skipped. |

## R8 / minification note

`MeasureLatency` is reached by reflection from `XrayBridge.measureLatency`. It is covered by the
existing `-keep class xraybridge.**` in `app/proguard-rules.pro`. No new keep rule is required, but
**verify on a release build**: run `:app:assembleRelease`, `javap` the generated bridge class to
confirm `MeasureLatency` is present, then exercise the ping path on the release APK. A green debug
build does not prove the release path.

## Testing

- **`PingCoordinatorTest`** (JVM, `kotlinx-coroutines-test`): per-run concurrency bound; cross-run
  de-dup survives a concurrency change (no re-admit of an in-flight id, no second ceiling); a completed
  id becomes re-eligible; `probeWithBackstop` holds the native slot past the backstop and releases it
  only when the fake native call returns; a saturated ceiling returns `Unavailable`; cancellation does
  not leak a slot. Subsumes the deleted `PingTesterTest`/`PingTesterRebuildDecisionTest`.
- **`PingTesterBackstopTest`** (JVM): `backstopFor` is the configured timeout + `BACKSTOP_MARGIN_MS`.
- **`FastestConnectRunnerTest` / `FastestPickTest` / `ClearStaleTestingTest`** (JVM): the
  connect-to-fastest caller, driven against a **real** (not faked) `PingCoordinator` — supersede
  semantics with disjoint *and* identical pools, cancel-resets-`Testing` scoped to the run's own ids,
  and the `BUSY` vs `NO_RESPONSE` distinction. Listed in full in
  [profile-actions-menu.md](profile-actions-menu.md#testing).
- **`AutoPingLatchTest`** (JVM): `consume()` wins exactly once; a fresh facade still sees `isConsumed`;
  `resetForTest()` persists nothing.
- **`AutoPingServersTest`** (JVM): `autoPingServers` includes subscription-imported servers even when
  the `subscriptions` query has not loaded yet — the regression guard for the ~30% "subscriptions
  skipped" launch race (QA §4 P10) — and orders manual ("My profiles") servers before subscription
  servers to match the list UI (independent of the subscriptions query).
- **`PingPreferencesTest` / `AutoPingTest` / `PingTargetValidationTest`** (JVM): defaults match live
  constants; persistence keys round-trip; invalid target fallback; timeout `1_000..30_000` and
  concurrency `1..5` clamp on load/save; the `shouldAutoPing` truth table (enabled + unconsumed → run);
  and the deliberately-permissive `http://` target boundary.
- **`ConfigBuilderPingTest`** (JVM): `toPingTestConfig_fromVless_hasOutboundsAndNoInbounds` and
  `toPingTestConfig_fromJson_stripsInbounds` (no `inbounds`, outbounds + DNS kept);
  `toPingTestConfig_stripsGeoRoutingRulesButKeepsDnsRoute` (no `geoip:`/`geosite:`/`ext:` rules, port-53 →
  `dns-out` preserved). No Hysteria2-specific `toPingTestConfig` test — Hysteria2 coverage is via the
  on-device matrix.
- **Go / bridge** — no pure unit test is feasible (gomobile + JNI). The AAR build, `javap` inspection
  of the generated class for `MeasureLatency`, and Gradle build checks are complete. No item in the
  device matrix has been verified; all remain manual and outstanding.
- **On-device matrix (manual, outstanding):**
  1. Known-good server → `N ms` displayed.
  2. Deliberately wrong REALITY key/shortId → `N/A` (proves camouflage-fallback detection).
  3. Hysteria2 server → `N ms`.
  4. Test **while connected** to a different server → probes return, live tunnel undisturbed.
  5. Test **while disconnected** → probes return (direct dial).
  6. Whole subscription group → results stream in, no more than the configured `1..5` concurrent dials.
  7. "My profiles" group expands/collapses and runs its group test.
  8. Enable auto-ping and confirm one union probe starts only after profiles load; navigation, resume,
     and Activity recreation (rotation/theme) must **not** trigger a second run in the same process.
     Only a full process death + relaunch re-arms the latch and permits another automatic run.
