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

This generates:

- `app/libs/xray.aar`

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
