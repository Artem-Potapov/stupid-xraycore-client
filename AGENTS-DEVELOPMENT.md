# Development Reference

This document covers testing, release builds, and extensibility points for modifying the XTLS Core Proxy codebase.

## Testing Strategy

### Unit Tests
JUnit4 tests under `app/src/test` (e.g., `ConfigBuilderTest`) validate runtime config generation and input rejection logic.

### Integration/Device Tests
Android instrumented tests under `app/src/androidTest` run with `AndroidJUnit4` on connected hardware/emulator.

### Local Test Commands

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:connectedDebugAndroidTest
./gradlew.bat :app:check
```

### CI Pipeline
`.github/workflows/build.yml` runs on pushes to `main` and on pull requests:
- Builds `xray.aar` from source (`scripts/build-xray-aar.bash` with `XRAY_CORE_REF: main`, NDK 28.2.13676358)
- Runs `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
- Uploads AAR, debug APKs, test/lint reports as artifacts
- Device tests (`connectedDebugAndroidTest`) **not** in CI — run them locally

### Test Input Declaration
**A few unit tests read source files off the filesystem, and `app/build.gradle.kts` declares those files as task inputs** (`tasks.withType<Test> { inputs.file(...) }` for `AndroidManifest.xml` and `res/xml/network_security_config.xml`).

Gradle cannot infer them: `HealthProbeSchemeTest` parses both to pin health probe's cleartext exemption. Without declaration, editing either file alone left `testDebugUnitTest` **`UP-TO-DATE`** → guard silently didn't run. If you add a test that reads a file rather than a class, declare it there too — or it will pass by not executing.

### Instrumented Test Guidelines
**Instrumented tests welcome for regression checks.** Running `:app:connectedDebugAndroidTest` against connected device/emulator encouraged whenever change could affect UI, ViewModel/state, bridge, or lifecycle.

**On One UI (Samsung) devices**, prep first to avoid intermittent "No compose hierarchies found" flake (activity RESUMED→PAUSED when screen sleeps):
```bash
adb shell input keyevent KEYCODE_WAKEUP
adb shell svc power stayon true
# Zero the three animation scales:
# window_animation_scale, transition_animation_scale, animator_duration_scale
```

To target single class, use `-Pandroid.testInstrumentationRunnerArguments.class=<FQN>` (**not** `--tests`, which instrumentation runner ignores).

### Connected Test App Preservation
**`connectedAndroidTest` no longer wipes the app — uninstall is OPT-IN.** AGP's connected-test task uninstalls app-under-test after run by default, deleting `/data/data/<pkg>` (Room DB + SharedPreferences). On device holding real user data (imported profiles/subscriptions) that's destructive wipe.

`gradle.properties` now sets `android.injected.androidTest.leaveApksInstalledAfterRun=true` → app **kept installed** after run (verified against AGP 9.1.1: `DeviceProviderInstrumentTestTask` uninstalls iff `!testRunnerFactory.keepInstalledApks`).

To restore destructive uninstall, opt in with:
```powershell
./gradlew.bat :app:connectedDebugAndroidTest -PuninstallAfterTest
```
Or raw: `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=false`

Both paths **fail safe**: if flag wiring ever no-ops, app stays installed (never unexpected wipe).

## Release Builds & Minification

### R8 Configuration
- Release builds set `isMinifyEnabled = true` **and** `isShrinkResources = true`
- R8 **shrinks, optimizes, and obfuscates**: `-dontobfuscate` line in `app/proguard-rules.pro` is **commented out** (deliberately, to hinder code injection), so release names ARE renamed
- Write keep rules as `-keep` (not merely no-obfuscate) — they must hold under obfuscation

### Keep-Rule Rule
**If your change reaches code R8's static analysis cannot see, add matching `-keep` in `app/proguard-rules.pro`.**

That includes:
- Reflection (`Class.forName`, `Method.invoke`, `getDeclaredMethod`)
- JNI / `native` methods
- Dynamic class loading
- Anything across gomobile `xray.aar` bridge

R8 will otherwise strip or rename it; breakage shows up **only at runtime** (e.g., `bridge/XrayBridge.kt` → `xraybridge.**`, `go.**`). Green build doesn't prove safety — install release APK and exercise the path.

### Release Signing
- Release signing reads `key.properties` (gitignored) into `signingConfigs("release")`
- Keystore key must be **RSA or EC**; Android's APK Signature Scheme v2/v3 rejects **EdDSA/Ed25519**
- R8 `mapping.txt` (`app/build/outputs/mapping/release/`) deobfuscates production stack traces

### Release APK Output
`assembleRelease` emits:
- Per-ABI split APKs (arm64-v8a, armeabi-v7a) plus universal fallback
- Renamed to `boykisser-<abi>-<buildType>-<version>.apk`
- `.sha256sum` file next to each release APK (`sha256ReleaseApks` task)

AAB path (`bundleRelease`) splits per-ABI itself but keeps every locale in base APK (`language { enableSplit = false }` — required by in-app language picker).

## Extensibility Hooks

### Runtime Config Extension

| What | File | Key Entry Point | Critical Constraints |
|------|------|-----------------|---------------------|
| **Runtime Config Pipeline** | `config/ConfigBuilder.kt` | `buildRuntimeConfig(input, log, tuning)` | First obtains protocol-specific secure base, then runs ordered pipeline: `forceLog → applyFragmentation → applyMux → applyDns → applyRouting → applyCoreSettings`. DNS must precede routing (so `BLOCKED_ONLY` DoH guard sees effective resolver); core settings run last (so IPv6-off and forced sniffing win). |
| **Pasted JSON Secure Base** | `config/ConfigBuilder.kt` | `replaceJsonInboundsWithTun()` | Ordered pair: `sanitizeProxyBalancers` (validate/rewrite/remove balancers **and drop rules naming removed one**) then `reconcileInboundTagRules`. Order is load-bearing: reconciliation asks `safeBalancerTags` which balancers survived. |
| **Tuning Settings** | `config/TuningSettings.kt` | Immutable snapshot | Extend with matching model/preferences; preserve pipeline order. See `docs/features/{fragmentation,mux,dns,routing-rules,xray-settings}.md`. |

### Runtime Diagnostic Extension

| What | File | Key Entry Point | Critical Constraints |
|------|------|-----------------|---------------------|
| **Config Sanitizer** | `config/ConfigSanitizer.kt` | Read-only inverse view | Runs same pipeline as `ConfigBuilder`. Must continue to delegate proxy selection/applicability/effective routing to `ConfigBuilder` helpers (don't reimplement forward rules). Add `FindingId`, presentation mapping, JVM coverage together. |

### Ping Extension Points

| What | File | Key Entry Point | Critical Constraints |
|------|------|-----------------|---------------------|
| **Ping Test Config** | `config/ConfigBuilder.kt` | `toPingTestConfig()` | Dialer-only probe config: full runtime config minus tun inbound **and** minus `geoip:`/`geosite:`/`ext:` routing rules (fail to build in probe's geo-asset-less throwaway core instance). Forces `LogSettings(NONE, null)`. |
| **Ping Preferences** | `state/PingPreferences.kt` | Loaded fresh at probe admission | Not session-captured. `PingTester.backstopFor(timeoutMs)` must remain strictly above Go timeout. |
| **Ping Coordinator** | `state/PingCoordinator.kt` | Single stable admission owner | `VpnViewModel` holds as `val`, never rebuilds. Owns cross-run `inFlight` de-dup set and fixed native-slot ceiling. Takes per-run concurrency as `runGroup` argument. `probeWithBackstop` releases native slot only in child's `finally` after JNI call returns (saturated ceiling returns `Unavailable` promptly). |
| **Auto-Ping Latch** | `state/AutoPingLatch.kt` | `consume()` | Process-scoped, atomic compare-and-set, once per app launch. |
| **Auto-Ping Source** | `VpnViewModel` | `autoPingProfiles` | Full server set from atomic flat `profiles` query (top-level `autoPingServers`), **not** subscription-grouped view. Grouped union raced separately-loaded `subscriptions` query and skipped every subscription server ~30% of launches. Do not reintroduce that dependency. |

**Extend coordinator/latch rather than reintroducing per-run tester or ViewModel-scoped consumed bit.**

### Auto-Failover Extension Points

See `docs/features/auto-failover.md` for full context.

| What | File | Key Entry Point | Critical Constraints |
|------|------|-----------------|---------------------|
| **Pool Resolver** | `failover/FailoverPoolResolver.kt` | `resolve(dao, current)` | **SPEC 2 SEAM**. Single place that changes when user-curated pools land. Keep signature stable, keep logic there. Both auto-failover's `rotateTunnel` **and** connect-to-fastest's `FastestConnectRunner` resolve through it. Do not add second pool derivation (would diverge with no compile-time signal). |
| **Failover Decision** | `failover/FailoverDecision.kt` | `nextCandidate()`, `admitRotation()` | Pure decision point. No clock, no Android; `now` always passed in (sliding thrash window testable without waiting). Latency-ordered candidate selection belongs here, behind process-scoped ping repository (deferred). |
| **Health Probe** | `failover/HealthProbe.kt` | `probe()` | Any implementation must probe **live tunnel** (not throwaway core), must not throw (except `CancellationException`, which MUST propagate), must stay valid under whole-app tunneling. New probe target is **not** free choice: must be fixed constant with matching `ConfigBuilder` carve-out (probe routed direct returns success with proxy down). Change two together or not at all. |
| **Session Lifecycle** | `vpn/SessionLifecycleDecision.kt` | Every service-side rule | Pure functions: `canReserveRotation`, `shouldDeferKillDuringTransition`, `shouldHoldScreenReceiver`, `shouldRunFailoverMonitor`, `failoverMonitorNeedsRebuild`, `shouldEstablishRotationBridge`, `shouldAbortRotationForMissingBridge`, `shouldFunnelRotationReservationRefusal`, `authoritativeFailoverSettings` + `canReserveRotationFromAuthoritativeState` (**two impure functions**; second delegates to first), `containmentForGiveUp`, `classifyGiveUpOutcome`, `connectionStateForGiveUp`, `shouldStopServiceOnGiveUp`, `giveUpOngoingLine`, `shouldFireFailoverRetry`, `shouldRestoreUnprotectedRearm`, `unprotectedRetryAction`, `shouldRestartForRecovery`, `activeProfileIdToRestoreOnRefusedStart`, `shouldReleaseGiveUpOnDisable`, `shouldOverwritePendingConnect`. Add rules there, not as new inline `when` branches in service. |

**Session Lifecycle Critical Constraints:**

- **`stopVpn` must never gain anything that awaits**: UNPROTECTED recovery restart calls it on main thread; cannot dispatch-and-await without deadlocking.

- **Never widen `shouldRestartForRecovery` beyond `UNPROTECTED`**: Each excluded outcome has own sufficient reason:
  - `CONTAINED_BY_LIVE_TUNNEL` holds **running Xray core** → path would turn `stopVpn`'s main-thread no-op into real `instance.Close()` (RISK-1 hazard)
  - `CONTAINED_BY_BLACKHOLE` does **not** hold running core (classified with `TunInterfaceKind.NONE` / already-`UNREAD_CONTAINMENT` after `tearDownTunnelLocked()` called `stopXray()`; blackhole builder starts none). Excluded because **still holds TUN, so nothing for restart to rescue** — plus general one keeping "start while running" idempotent for tile, `START_REDELIVER_INTENT` recovery, stray intents.

- **`RECONNECT` sequenced by `state/ReconnectFlow`** (stops via `ACTION_STOP`, already marshalled onto `tunnelOpScope`) — one path for both contained outcomes. Sequencing around these rules still untested off-device; recorded follow-up: `vpn/FailoverEngine` behind narrow `TunnelHost` seam.

- **`authoritativeFailoverSettings()`**: Returns `FailoverPreferences.state.value` and is **single source of failover settings for anything service decides** (thrash cap, rotation-success re-apply, re-arm delay, UNPROTECTED stop deadline, enable veto). Exists because `save()`/`load()` publish into process-global StateFlow *synchronously* while service's collector runs *asynchronously* → session cache service used to keep could be whole tuple stale. That cache field **deleted** (no read justified it). `failoverMonitorSettings` is NOT that cache and stays — records what live monitor was *constructed from*, which `failoverMonitorNeedsRebuild` needs. Keep new rules pure; put settings read here.

### Profile Config Extension Points

| What | File | Key Entry Point | Notes |
|------|------|-----------------|-------|
| **VLESS Codec** | `config/ProfileConfigCodec.kt` | `fromVlessUri()`, `toVlessUri()`, `fromJson()`, `toJson()` | VLESS URI/JSON, `ConfigKind` detection |
| **Hysteria2 Codec** | `config/Hysteria2ConfigCodec.kt` | `parseUri()`, `toXrayJson()`, `extractFromJson()`, `toShareLink()` | Hysteria2 model, URI parse, Xray JSON build/extract/merge. `toXrayJson` applies `ConfigBuilder.makeSecureDns` itself. `toShareLink` — inverse of `parseUri`, emits `hy2://` links. |
| **Share Link Reconstruction** | `config/ProfileShareLink.kt` | `fromStoredConfig()` | Reconstructs shareable link from any stored JSON config by dispatching to per-protocol codec. Returns `null` for configs with no vless/hysteria outbound. |

### VPN Lifecycle Extension Point

`app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` for TUN setup, DNS/routes, foreground notification.

- `onStartCommand` routing: pure `vpn/StartCommandDecision.kt`
- Split-tunnel / whole-app self-handling: pure `split/SplitTunnelPlanner.kt`
- Kill / revive / **rotate** transition ownership and failover give-up rules: pure `vpn/SessionLifecycleDecision.kt`

**Extend those rather than inlining new `when` branches in service.**

There are now **three** tunnel operations (`killTunnel`, `reviveTunnel`, `rotateTunnel`) sharing one lock, one epoch scheme, one screen receiver. All three reserve transition under `lock` before any async work; route every escape through single fail path. Preserve that shape.

### Bridge Extension Points

| Layer | File | What |
|-------|------|------|
| **Kotlin Reflection Candidates** | `bridge/XrayBridge.kt` | `classNames` list |
| **Go Surface** | `xray-go/xray_bridge.go` | `StartXray`, `StopXray`, `RegisterProtector` (the `protect()` dial controller), `MeasureLatency` (throwaway-instance latency probe), `XrayVersion` (read-only linked-core version). Latter two never touch `mu`/`instance`. `Protector` reverse-binding interface keep-ruled via `-keep class xraybridge.**`. |
| **Kotlin Reflection Surface** | `bridge/XrayBridge.kt` | `startXray`, `stopXray`, `registerProtector`, `measureLatency` (3-param: configJson, targetUrl, timeoutMs), `xrayVersion` (zero-arg; call off-main because first bridge touch loads gojni). `XrayVersion`/`xrayVersion` covered by existing `xraybridge.**` keep rule but still requires release-device verification. |

## Environment Variables & Script Knobs

| Variable | Where Used | Purpose |
|----------|------------|---------|
| `OUTPUT` | `scripts/build-xray-aar.bash` | Override output AAR path (default `app/libs/xray.aar`) |
| `ANDROID_API` | `scripts/build-xray-aar.bash` | Override gomobile Android API level (default `26`) |
| `XRAY_CORE_REF` | Both AAR scripts; CI sets `main` | Xray-core git ref for `go get` (default `main`) |
| `xray.tun.fd` | `xray-go/xray_bridge.go` | Pass TUN file descriptor into Xray-core runtime |
| `XRAY_TUN_FD` | `xray-go/xray_bridge.go` | Alternate env key for TUN file descriptor |
| `GONOSUMDB` | Set internally by both AAR scripts | Bypass checksum DB for Xray-core module path quirk |

**PowerShell script takes parameters, not env vars** (except `XRAY_CORE_REF` fallback):
- `-Output`, `-AndroidApi`, `-XrayCoreRef` (empty → `XRAY_CORE_REF` env → `main`)
- Plus ABI trims: `-NoArmV7` / `-NoX86` / `-NoAMD64`

Bash script also accepts same three values as positional arguments.

## Key References

- [ConfigBuilder source](app/src/main/java/com/justme/xtls_core_proxy/config/ConfigBuilder.kt)
- [Go bridge module](xray-go/xray_bridge.go)
- [PowerShell AAR build script](scripts/build-xray-aar.ps1)
- [Bash AAR build script](scripts/build-xray-aar.bash)
