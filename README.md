# XTLS Core Proxy (Android MVP)

Android 13+ MVP VPN client that uses Xray-core `proxy/tun` directly.

## What this app does

- Uses `VpnService` to create a TUN interface.
- Passes TUN fd to Xray through `xray.tun.fd` / `XRAY_TUN_FD`.
- Starts Xray-core with a `tun` inbound.
- Accepts either:
  - a `vless://` URI, or
  - raw Xray JSON (normalized to a single `tun` inbound).
- Does not run `tun2socks`.
- Rejects local `socks` and `http` inbounds in MVP mode.

## Project layout

- `app/` Android app
- `xray-go/` Go bridge used by `gomobile bind`
- `scripts/build-xray-aar.ps1` command to build `app/libs/xray.aar`

## Prerequisites

- Android Studio + Android SDK
- Go 1.26+ (required by upstream Xray-core main branch)
- `gomobile`

Install gomobile:

```powershell
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

## Build the Xray AAR

From project root:

```powershell
./scripts/build-xray-aar.ps1
```

Linux/macOS:

```bash
./scripts/build-xray-aar.bash
```

Optional override (default is `main`):

```powershell
$env:XRAY_CORE_REF = "main"
./scripts/build-xray-aar.ps1
```

This generates:

- `app/libs/xray.aar`

## Canonical build pipeline

1. Build AAR: `scripts/build-xray-aar.ps1` (Windows) or `scripts/build-xray-aar.bash` (Linux/macOS).
2. Build app: `./gradlew.bat :app:assembleDebug` (or `./gradlew :app:assembleDebug` on Unix).
3. Quality checks: run tests and lint.

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
```

`app/build.gradle.kts` now wires AAR generation into `preBuild`, so app builds fail fast if `app/libs/xray.aar` cannot be produced.

## Build and run app

```powershell
./gradlew.bat :app:assembleDebug
```

Run on a real Android device (VPN/TUN behavior is not reliable in emulator-only testing).

## Runtime usage

1. Launch app.
2. Paste a `vless://` URI or Xray JSON.
3. Tap **Connect**.
4. Grant notification permission (Android 13+) and VPN consent when prompted.
5. Observe logs in the app.
6. Tap **Disconnect** to stop tunnel and core.

## Validation checklist

- Connects successfully with a known-good VLESS endpoint.
- App logs show TUN fd established and Xray started.
- No localhost SOCKS/HTTP listener is configured in runtime config.
- Traffic works when connected, stops when disconnected.
- Reconnect after disconnect works without app restart.

## Troubleshooting

- `Xray bridge class not found`: build `app/libs/xray.aar` first.
- `unknown inbound protocol: tun`: ensure Go bridge imports `github.com/xtls/xray-core/main/distro/all`.
- Start loop/failure: verify server config and ensure input is valid VLESS/JSON.
- `gomobile`/`gobind` mismatch: rerun the AAR script; it installs both tools at the exact `golang.org/x/mobile` version in `go.mod`.
- `gomobile init` or Android toolchain errors: ensure Android SDK/NDK are installed and `ANDROID_SDK_ROOT`/`ANDROID_NDK_HOME` are set.
- Build pulls wrong `xray-core` revision: check `XRAY_CORE_REF` (defaults to `main` when unset).
