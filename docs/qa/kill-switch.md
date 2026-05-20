# Kill-on-Foreground QA Checklist

These scenarios must pass on a physical Android 13+ device before the feature is considered shipped. Automated unit tests cover the monitor's state machine; this checklist covers end-to-end behavior that requires real apps, real foreground transitions, and real VPN traffic.

## Setup

1. Build and install: `./gradlew.bat :app:installDebug`
2. Grant Usage Access: `Settings → Apps → Special access → Usage access → XTLS_CORE_PROXY → Allow`
3. Install test apps on the device:
   - **MegaFon** (Russian telecom) — shows a prominent "VPN detected" banner on launch
   - **Вкусно — и точка** — same behavior
4. Add both apps to the kill-list via `Kill-on-foreground apps` settings.
5. Connect VPN with a working profile.

## Scenarios

| # | Scenario | Expected |
|---|---|---|
| 1 | Open MegaFon while VPN is connected | VPN's banner does NOT appear in MegaFon. Foreground notification flips to "Paused: …" within ~1–2s. |
| 2 | Open Вкусно — и точка while VPN is connected | Same as above — banner absent, notification flips to Paused. |
| 3 | Press home from a controlled app | Notification flips back to "Connected" within ~1–2s. Browser through VPN works. |
| 4 | Lock phone while paused | Polling stops (no battery drain). State stays Paused. |
| 5 | Unlock to launcher (no controlled app foreground) | Polling resumes. State flips to Connected within ~1s if a controlled app was foreground at lock time and is no longer foreground at unlock; otherwise stays as-was. |
| 6 | Revoke Usage Access mid-session | Kill-switch silently disables. Existing tunnel state preserved. `adb logcat` shows the warning. |
| 7 | Edit kill-list while a controlled app is foreground | Add → kill fires immediately. Remove → revive fires immediately. |
| 8 | Synthetic revive failure (delete the profile from DB while paused, then press home) | Separate "VPN Errors" notification appears with the error text. Service stops. Tapping the notification opens MainActivity. |
| 9 | Toggle between two controlled apps | Stays paused, no churn (verify in logcat — only one kill log line). |
| 10 | Manual stop VPN while paused | Service stops cleanly. No auto-revive. |

## Why MegaFon and Вкусно — и точка

Both show prominent on-launch banners when a VPN is detected. Success criterion = banner is absent. Unambiguous visual signal, no guessing whether the detection-side worked.

## Known limitations

**Split-screen multi-window:** When the controlled app and our app run side-by-side in split-screen, switching focus between them does NOT fire `ACTIVITY_RESUMED` in `UsageStatsManager` — both activities are simultaneously in the `RESUMED` lifecycle state per Android's multi-window model. Result: the kill/revive cycle reflects whichever activity was most recently transitioned to `RESUMED`, not whichever has window focus right now. Editing the kill-list while both apps are visible still works because that path uses the repository observer, not new lifecycle events.

Fixing this requires a focus-event source (Accessibility's `TYPE_WINDOW_STATE_CHANGED` / `TYPE_VIEW_FOCUSED`), which is intentionally deferred. The `ForegroundAppMonitor` interface is shaped so an Accessibility-based implementation can replace the UsageStats one without service-side changes.

## Reporting

Record outcomes (pass / fail / skipped) in the PR description. For failures, attach logcat output (`adb logcat | Select-String "KillSwitch|XrayVpnService"`) and a screenshot.
