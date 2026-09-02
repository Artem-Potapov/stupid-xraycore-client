# Project Overview

XTLS Core Proxy is an MVP Android VPN client (minSdk 29 / Android 10+, targets API 36) that runs Xray-core in tun-only mode without `tun2socks`, using an Android `VpnService` TUN interface passed into Xray through a gomobile-built Go bridge (`app/libs/xray.aar`). The app accepts `vless://` URIs, `hysteria2://` / `hy2://` URIs, or raw Xray JSON, normalizes runtime config to a single `tun` inbound, and manages tunnel lifecycle from a Jetpack Compose UI.

**Mission**: Help people avoid Internet blockings by the Russian Government and access the free internet.

## Documentation Index

This file is the main entry point. For detailed information, see:

- **[AGENTS-ARCHITECTURE.md](AGENTS-ARCHITECTURE.md)** — Repository structure, architecture diagrams, fail-closed security guarantees, dormant features
- **[AGENTS-DEVELOPMENT.md](AGENTS-DEVELOPMENT.md)** — Testing strategy, release builds, extensibility hooks, environment variables

**Per-feature maintainer docs**: `docs/features/` — check there before grepping for `tile/`, `i18n/`, `killswitch/`, etc.

## Quick Start

### Prerequisites

Install Go mobile tools (from `README.md`):

```powershell
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

### Essential Commands

| Task | Command |
|------|---------|
| **Build Xray AAR (Windows)** | `./scripts/build-xray-aar.ps1` |
| **Build Xray AAR (Linux/macOS)** | `./scripts/build-xray-aar.bash` |
| **Build debug APK** | `./gradlew.bat :app:assembleDebug` |
| **Install on device** | `./gradlew.bat :app:installDebug` |
| **Run unit tests** | `./gradlew.bat :app:testDebugUnitTest` |
| **Run instrumented tests** | `./gradlew.bat :app:connectedDebugAndroidTest` |
| **Lint** | `./gradlew.bat :app:lintDebug` |
| **Full verification** | `./gradlew.bat :app:check` |
| **Compile without packaging** | `./gradlew.bat :app:compileDebugKotlin` |
| **Release build** | `./gradlew.bat :app:assembleRelease` |
| **Release bundle** | `./gradlew.bat :app:bundleRelease` |

### Runtime Logs

```powershell
adb logcat
```

## Code Style & Conventions

- **Kotlin style**: `official` (`gradle.properties`), 4-space indentation, standard Kotlin/AGP defaults
- **Naming**:
  - `PascalCase` for classes/objects/composables
  - `camelCase` for methods/properties
  - `UPPER_SNAKE_CASE` for constants
  - Package names lowercase (e.g., `com.justme.xtls_core_proxy.*`)
- **VPN/runtime code**: Keep explicit about failure states; surface user-visible errors through `LogRepository`/`VpnViewModel`
- **Linting**: Android Gradle lint tasks (`:app:lint*`); no dedicated `ktlint`/`detekt` config

### Compose `getString` Gotcha

Inside callbacks/lambdas (e.g., button `onClick`), use:
```kotlin
LocalResources.current.getString(...)  // ✓ Invalidates on Configuration changes
```

Not:
```kotlin
LocalContext.current.getString(...)    // ✗ Flagged by lint as LocalContextGetResourceValueCall
```

Symbol lives at `androidx.compose.ui.platform.LocalResources`, **not** `androidx.compose.ui.res.LocalResources`.

### Commit Message Template

```text
<Short imperative summary>

Why (optional):
- <reason 1>
- <reason 2>
```

## Agent Guardrails

### What Not to Touch

- Never edit or commit generated/local artifacts: `app/libs/*.aar`, geo data assets, `local.properties`, machine-local IDE state (unless explicitly requested)
- Treat `.idea/` changes as opt-in; require maintainer confirmation before modifying IDE configs
- Never commit secrets or personal endpoints; treat pasted `vless://` links, UUIDs, REALITY keys as sensitive

### Code Review Requirements

Require human review for changes to:
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/`
- `app/src/main/java/com/justme/xtls_core_proxy/bridge/`
- `xray-go/`
- Build scripts

### Search & Indexing

When searching or indexing the codebase, run a subagent with specifically defined File Searcher mode.

### Resource Management

Run heavyweight commands serially (one `gradlew` or one `gomobile bind` at a time) to avoid resource contention and inconsistent outputs.

### Nested Instructions

When nested `AGENTS.md` files exist, apply root instructions first, then append nested instructions from root toward working directory.

### Branch Lifecycle

**When finishing a full feature** (not small task or one-off fix):

1. **Run Release build** (`./gradlew.bat :app:assembleRelease`) before finishing/merging — stricter release pipeline (R8 minification, lint-vital, signing) surfaces problems debug builds hide
2. **Update maintainer docs** before merge (not as later chore):
   - Add `docs/features/<feature>.md` for each new feature
   - **Correct any existing `docs/features/` doc whose behavior the change altered** — doc drifting from code is worse than no doc (reads as authoritative but wrong)
   - Update this `AGENTS.md` where change invalidated it — `docs/features/` index, Architecture Notes, Security & Compliance, Extensibility Hooks

For small, low-risk changes: debug build + `:app:testDebugUnitTest` is enough.

### Deploy/Release

Do not perform deploy/release publication steps without explicit maintainer approval.

## Repository Quick Reference

```
.
├── .github/workflows/build.yml     CI pipeline
├── app/
│   ├── build.gradle.kts            App module build config
│   ├── proguard-rules.pro          R8 keep rules
│   ├── libs/xray.aar              Generated (gitignored)
│   └── src/
│       ├── main/java/com/justme/xtls_core_proxy/
│       │   ├── MainActivity.kt, XtlsApplication.kt
│       │   ├── bridge/            XrayBridge — reflection facade
│       │   ├── config/            ConfigBuilder, ConfigSanitizer, codecs
│       │   ├── db/                Room database
│       │   ├── failover/          Auto-failover engine
│       │   ├── state/             VpnViewModel, ReconnectFlow, PingCoordinator
│       │   ├── vpn/               XrayVpnService, SessionLifecycleDecision
│       │   └── [other packages]   See AGENTS-ARCHITECTURE.md
│       ├── androidTest/           Instrumented tests
│       └── test/                  JVM unit tests
├── docs/features/                 Per-feature maintainer reference
├── scripts/
│   ├── build-xray-aar.bash        Linux/macOS AAR build
│   └── build-xray-aar.ps1         Windows AAR build
├── xray-go/
│   └── xray_bridge.go             Go entry points
├── build.gradle.kts, settings.gradle.kts
├── AGENTS.md (this file)
├── AGENTS-ARCHITECTURE.md         Architecture & security reference
├── AGENTS-DEVELOPMENT.md          Testing & extensibility reference
└── LICENSE                        GNU AGPL v3
```

For detailed structure with package descriptions, see [AGENTS-ARCHITECTURE.md](AGENTS-ARCHITECTURE.md).

## Critical Architecture Concepts

### ConfigBuilder: The Mandatory Chokepoint

`config/ConfigBuilder.kt` is the single point enforcing:
- Tun-only inbound (rewrites any config)
- Secure-DNS enforcement (DoH-only, port-53 → `dns-out`, `ForceIP` bootstrap)
- Forced log object (prevents redirect of Xray writes)
- Ordered overlay pipeline: fragmentation → mux → DNS → routing → core

### Fail-Closed Security Model

Four guarantees prevent leaks:
1. **DNS leak enforcement**: `ConfigBuilder` normalizes every config to secure-DNS shape
2. **Loop-avoidance**: Socket-level via `VpnService.protect()`, whole app tunneled
3. **Runtime backstops**: Routing/XRAY/DNS IPv6 degradation at chokepoint
4. **Auto-failover give-up**: Blackhole TUN when all servers dead (three outcomes: live tunnel / blackhole / unprotected)

### Three Settings Rails

1. **Session-captured (TuningSettings)**: Global overlays captured once per connection, reused for kill-switch revives
2. **Per-probe (PingPreferences)**: Loaded fresh for each probe
3. **Live-observed (FailoverPreferences)**: Service seeds then observes for session

See [AGENTS-ARCHITECTURE.md](AGENTS-ARCHITECTURE.md) for complete architecture and security details.

## Quick Extension Points

| What | File | Key Method |
|------|------|------------|
| Runtime config pipeline | `config/ConfigBuilder.kt` | `buildRuntimeConfig()` |
| Auto-failover pool | `failover/FailoverPoolResolver.kt` | `resolve()` |
| Service lifecycle rules | `vpn/SessionLifecycleDecision.kt` | All decision functions |
| Health probe | `failover/HealthProbe.kt` | `probe()` |
| Protocol codecs | `config/ProfileConfigCodec.kt`, `config/Hysteria2ConfigCodec.kt` | Codec methods |
| Bridge surface | `bridge/XrayBridge.kt`, `xray-go/xray_bridge.go` | Reflection + Go entry points |

See [AGENTS-DEVELOPMENT.md](AGENTS-DEVELOPMENT.md) for complete extensibility reference with constraints.

## Known Limitations & TODOs

Tracked in code:
- Geo files must be obtained locally (see `app/src/main/assets/WHERE_TO_GET_GEOFILES.md`)
- InspectionRuns (IDE linter results, untracked, machine-local) — ignore unless user specifically asked for review

## License

GNU AGPL v3 — see [LICENSE](LICENSE)
