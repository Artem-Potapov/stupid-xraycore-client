# Changelog

All notable changes to XTLS Core Proxy are documented here. The format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions track the app's `versionName`.

## [3.0.0PRE] — 2026-09-02

Tag: `3.0.0-PreRelease` (pending). Major release.

## Auto-failover: a live-tunnel health watchdog that switches servers without leaking,
plus Connect-to-fastest and honest status on every surface.

### Added
- **Auto-failover** (Settings → Tunnel → Auto-failover). **Off by default.** While connected, the
  app probes the *live tunnel* (not a throwaway ping) and, if the current server stops passing
  traffic, switches to another server from the same subscription — or to another server you added
  yourself. Probe interval, timeout, consecutive-failure threshold, and a sliding "max switches per
  window" cap are editable; each control autosaves on its own, same house style as the XRAY / Ping
  screens. Enable/disable takes effect on the running session; the first probe after a connect waits
  for the new tunnel to settle so a just-started server is not immediately blamed. Auto-failover
  picks the next server in list order ("any working server"); latency ordering is the manual path
  below.
- **Connect to fastest** on the profile long-press menu. Probes that server's pool — the same pool
  auto-failover would rotate through — and connects to whichever answered first. A second tap on the
  same pool joins the in-flight run instead of colliding with it; Cancel still aborts. Returning to
  the app after connecting some other way (e.g. the QS tile) no longer silently applies a stale
  winner.
- **Reconnect from a give-up.** When auto-failover has exhausted the pool, Connect is no longer a
  dead button: it reads **Reconnect** and does a stop-then-start onto the server you pick. The Quick
  Settings tile remains a Stop in that state (the session is still holding a tunnel). Turning
  auto-failover off mid-give-up stops the search and keeps the paused / still-proxying posture, with
  Reconnect as the way back on.
- **Two notification channels.** A quiet "Switched server" notice (from → to) after a successful
  rotation, and a high-importance "Connection problems" channel whose three alerts never share copy:
  paused-to-protect, still-proxying-but-pages-may-not-load, and not-protected.

### Changed
- **Home, the ongoing VPN line, and the QS tile tell the truth** on every failover state.
  Switching shows "Switching to another server…"; a contained give-up is either "Server is not
  responding" (tunnel still up) or "No server connection — paused" (traffic held); an *uncontained*
  give-up is **"Not protected"** on home and the tile — never the generic "Error", and never the
  reassuring paused copy. No user-visible string in either locale says "blackhole".
- **The QS tile is stoppable in every running state**, including the new give-up state and Error —
  tapping it no longer tries to start a second tunnel over a live session.
- **Config Sanitization** now reports the health-probe carve-out (one hostname forced through the
  proxy so the watchdog measures the tunnel, not the clear network) and the imported-balancer
  rewrites (selector expansion, stripped `fallbackTag: direct`, inbound-tag retarget / drop).
- **Imported balancer configs** are normalized at the same chokepoint as tun / DNS: prefix selectors
  expand to exact proxy outbounds, a `freedom` / `direct` fallback is stripped, and inbound-tag rules
  that would have gone silent after the tun rewrite are retargeted onto `tun-in` (toward the proxy)
  or dropped (toward direct). A balancer that was never a proxy path is left alone, so a
  country-direct rule cannot be silently proxied.

### Fixed
- **A refused start no longer points the UI at the wrong server.** "VPN already running" used to
  leave the active-profile id on the refused request, so home and the tile could name server B while
  traffic still flowed through A.
- **Disconnect and Stop win against an in-flight Reconnect.** In-app Disconnect, the tile, and the
  notification Stop all abort a reconnect that hasn't settled; deleting a subscription mid-reconnect
  is coordinated the same way. A parked permission dialog keeps the request that opened it — a later
  tap cannot silently substitute a different server.
- **Kill-switch + failover coexistence.** A kill that lands during a rotation is deferred and
  replayed if the rotation succeeds. If the listed app *leaves* before that rotation (or revive)
  commits, the deferred kill is withdrawn instead of parking the session paused for an app that is
  gone. If rotation *gives up* with the listed app still in the foreground, the deferred kill is
  dropped and a "VPN is still on" notice names the app — silently dropping it would leave the
  kill-switch quietly non-functioning.

### Security
- **Fail-closed give-up — the fourth leak-proofing guarantee.** When no server works, the app does
  not "just keep the dead tunnel" and does not drop you onto the clear network by accident. Three
  outcomes, one funnel: (1) keep the still-proxying tunnel if nothing was torn down, (2) hold a
  drop-only TUN (same capture as a real tunnel — split-out apps stay out, everything else is
  dropped, including the system resolver and Private DNS) when the live tunnel is gone, (3) admit
  "Not protected" only when even that containment could not be established. Those three never share
  a message. The uncontained outcome is the one that also reaches the in-app error banner; the Logs
  screen is not where a user learns their traffic just went clear.
- **No clear-network window during a switch.** Rotation used to tear the dead TUN down, then spend
  seconds building the next one with *no* VPN interface — every tunneled app emitting cleartext to
  DPI. A bridge TUN (unread fd, same capture plan) now covers that gap: apps briefly lose
  connectivity instead of briefly leaking. A give-up mid-gap adopts the bridge rather than leaving a
  second interface stranded.
- **The health probe cannot lie.** It is a Kotlin HTTP 204 through the live tunnel, so it answers
  "is *this* tunnel passing traffic", not "can a throwaway instance reach the server". Two routing
  rules — domain *and* IP — carve `cp.cloudflare.com` through the proxy in **every** routing mode,
  including "proxy only blocked sites" and "except country", so a 204 with the proxy dead cannot
  count as healthy. Under "except country" that is a deliberate one-hostname override of
  country-direct (reported on the Config Sanitization screen). The probe is cleartext on purpose (it
  looks like Android's own captive-portal check); a domain-scoped network-security carve-out permits
  that one host and leaves the rest of the app on the platform default — no app-wide cleartext.
  Airplane mode / lost signal is skipped, not blamed on servers.

## [2.3.0R] — 2026-07-26

Tag: `2.3.0-Release`. Minor release.

## Device identity (HWID) for subscription fetches, under a new Privacy settings screen.

### Added
- **Device identity (HWID) on subscription fetches** (Settings → Privacy). Subscription requests now
  carry Happ-parity `x-hwid` plus device headers (OS, model, locale) so panels that gate on a device
  identifier or count device slots work as they do with the Happ client. The HWID is a **random,
  minted 16-hex value** (Android-ID *shape*, never the real `Settings.Secure.ANDROID_ID`), stored once
  and kept **stable across every identity mode** so switching modes doesn't burn a panel device slot.
- **Identity modes:** Real device, Android spoof, iPhone spoof (pair-plausible model/OS tables), and a
  fully custom OS/version/model/locale. **User-Agent** is independently selectable (Default ·
  Happ-like) and works regardless of whether the HWID header is sent. A **live preview** shows the
  exact headers + wire UA the next fetch will send, and **Reset HWID** re-rolls the identifier.
- **Panel HWID-rejection handling.** A fetch rejected for a missing/duplicate device ID surfaces a
  specific message ("device limit reached" / "requires a device ID"), and an app-filtering panel
  (403, or a 2xx that parses to zero servers) appends a "try a Happ User-Agent" hint.

### Fixed
- **HWID mint race.** On first launch, concurrent subscription refreshes could each mint a *different*
  HWID and burn several panel device slots at once; the lazy first-read mint is now serialized so all
  callers agree on one identifier.
- **User-Agent hint no longer hides the parse-error count.** When a filtering panel returns an
  unparseable 2xx body, the "N lines failed to parse" detail and the "try Happ UA" hint are reported
  together instead of one shadowing the other.
- **Per-control autosave on the XRAY and Ping settings screens.** An out-of-range numeric (e.g. a
  mid-edit MTU) no longer vetoes the *whole* screen's save — each control holds its own last-good
  value — so toggling IPv6 (or auto-ping) while a number field is invalid can no longer be silently
  dropped.

### Security
- **HWID is never a real hardware identifier.** No `ANDROID_ID` / `Build.SERIAL` is read anywhere, and
  no identity-bearing value reaches the log surface. Outbound header values are sanitized (CR/LF and
  control characters stripped) to prevent header injection.

## [2.2.1R] — 2026-07-16

Tag: `2.2.1-Release`. Minor release.

### Fixed
- DNS enforcement and bootstrapping follow-ups from the 2.2.0PRE cycle.
- VPN lifecycle concurrency hardening: stale-callback gating, kill-switch mid-revive
  replay, and user-stop path moved off the lifecycle lock to prevent ANR.

### Changed
- The main screen no longer hosts a log panel; profiles list fills the reclaimed space.
- `ConfigBuilder` forces the `log` object on every runtime config (level + app-private
  error-file path), overwriting rather than merging.

## [2.2.0PRE] — 2026-07-14

Tag: `2.2.0-PreRelease` (pending). Major release.

## Dedicated Logs screen + a HAPP/Hiddify-style Settings hub, VPN-lifecycle concurrency hardening,
and a crash fix for sharing large logs.

### Added
- **Dedicated Logs screen** (Settings → Diagnostics → Logs). Xray-core now writes its own error log to
  an app-private file, which the app tails into the on-screen buffer — previously only app-authored
  lines were visible and diagnosing a failed connection meant reaching for `adb logcat`. The screen
  offers a **session-stable log level** (Debug / Info / Warning / Error; captioned "Applies from the
  next connection", so a running tunnel's verbosity can't shift mid-flight) and a **live
  log-buffer-size** picker (1 000 / 2 000 / 5 000 / 10 000 lines) that trims the on-screen list
  immediately, plus **Copy / Share / Export** actions.
- **Sectioned Settings hub** (inspired by HAPP / Hiddify): UI, Tunnel, Advanced, Diagnostics, and About
  sections built from reusable `SettingsSectionHeader` / `SettingsRow` components. Debug builds show
  greyed placeholder rows for planned settings; release builds hide them — and the whole Advanced
  section — entirely.
- **About screen**: app version, purpose, a GitHub source-code link, and license / acknowledgements.

### Changed
- **The main screen no longer hosts a log panel** — the profiles list fills the reclaimed space; logs
  moved to their dedicated screen.
- **`ConfigBuilder` now forces the `log` object** on every runtime config (level + app-private
  error-file path), overwriting rather than merging, so a pasted or subscription-sourced config cannot
  redirect Xray's own log writes elsewhere. This joins secure-DNS and inbound sanitization as a third
  fail-closed normalization on the same chokepoint.

### Fixed
- **Sharing or copying a large log no longer crashes the app or drops the VPN.** Copy and Share inlined
  the whole buffer through a single ~1 MB Binder transaction; at the 10 000-line preset this threw
  `TransactionTooLargeException`, and because the VPN service shares the app process the uncaught throw
  killed the process — dropping the tunnel and wiping the log buffer. Copy/Share are now byte-bounded to
  the newest lines that fit (with a "log is large" explainer offering to include just the recent tail),
  while **Export streams the full log unbounded**. A defensive guard keeps any unexpected share failure
  from taking down the tunnel.
- **VPN kill-switch / lifecycle concurrency hardening.** Async lifecycle callbacks are gated on a
  monotonic session epoch, so a stale callback from a torn-down session can't publish or tear down a
  newer session's tunnel. A kill-switch event that lands mid-revive is now deferred and replayed instead
  of being lost by the edge-triggered monitor; disabling the kill switch mid-revive no longer strands
  the tunnel paused; and a queued kill is ignored once the feature is turned off. The user-stop path was
  moved off the lifecycle lock to prevent a UI freeze / ANR when disconnecting during connect.

### Security
- **Log redaction boundary.** The on-disk `xray-core.log` is raw and app-private; **every** user-facing
  surface — the on-screen list, Copy, Share, and Export — reads exclusively from the in-memory
  `LogRepository` buffer, which redacts UUID / `publicKey` / `shortId` values before a line is ever
  shown or shared. No code path reads or shares the raw file directly, and the large-log fix narrows the
  Copy/Share payload without changing that source.
- **Known follow-up (tracked in `docs/features/logs-screen.md`):** `sanitize()` currently recognizes the
  app's own secret shapes; broaden it to cover non-UUID credentials (e.g. a Hysteria2 password) that raw
  core output could surface at Debug before wide release.

## [2.0.1R] - 2026-06-25

Tag: `2.0.1Release`. Minor release.

### Fixed
When connecting to a domain as the address, ForceIP option told XRAY the address must be
resolved first, but because of the new DNS-proofing the only option is the XRAY's DNS,
which.. required an active tunnel.
Now the domain is 'surgically extracted' and is being bootstrapped (the only thing that
doesn't go through the XRAY's DNS) via DoH. 

## [2.0.0R] - 2026-06-21

No bugs found, exactly the same as 2.0.0R, except for the tag.
Tag: `2.0.0-Release`.

## [2.0.0PRE] — 2026-06-20

Tag: `2.0.0-PreRelease`. Major release.

## First-class Hysteria2 support + fail-closed leak-proofing overhaul 
(secure DNS + socket-level loop-avoidance) and a batch of import/UX/packaging improvements.

### Added
- **First-class Hysteria2 (HY2) support.** Import `hysteria2://` / `hy2://` share links from the
  clipboard, manual paste, or subscriptions, or paste raw Xray JSON whose proxy outbound is
  `protocol: "hysteria"` (version 2). A protocol-aware simple editor exposes host, UDP port,
  auth/password, SNI, ALPN, allow-insecure, and pinned certificate SHA-256, plus **Salamander**
  obfuscation and common **FinalMask** QUIC controls (congestion, upload/download bandwidth, UDP
  port-hopping, hop interval) with a raw FinalMask JSON escape hatch. Standard multi-port / port-hop
  authorities are supported. Confirmed connecting on-device against a real Hysteria2 server.
- **Subscription import improvements.** Whole-document JSON subscription bodies (single object or array)
  are parsed intact instead of being shattered line-by-line; base64-wrapped bodies and per-line base64
  are handled; display names prefer the URI fragment, then `host:port`, then a config's top-level
  `remarks`.
- **Kill-on-foreground: consent gate + exposed-state alert.** Enabling the kill switch now requires an
  explicit consent dialog, and while the tunnel is paused/exposed a high-importance notification
  surfaces that state.
- **Per-ABI release packaging.** Release APKs are split per ABI (x86/x86_64 dropped for release;
  emulator x86_64 support retained), named `boykisser-<abi>-<buildType>-<version>.apk`, with
  `.sha256sum` files emitted. Windows AAR build fixed (`checklinkname`) and xray-core bumped.

### Changed
- **Accepted inputs widened** to VLESS, Hysteria2, or raw Xray JSON (previously VLESS + JSON).
- **Foreign inbounds are sanitized into the canonical `tun` inbound** rather than rejected, so
  real-world panel exports carrying local `socks`/`http`/`mixed`/`dokodemo` inbounds now import instead
  of erroring. Applied at **both** storage and connect, so the stored config is already canonical.
- **TUN MTU lowered 1500 → 1400**, via a single shared constant for both the OS TUN interface and the
  Xray tun inbound, leaving headroom for outbound encapsulation (notably Hysteria2 QUIC/UDP +
  Salamander) so inner packets stop fragmenting under DF.

### Fixed
- **Hysteria2 links with unencoded spaces/emoji in the `#name` fragment** are no longer rejected on
  clipboard add, silently dropped on subscription import, or forced into Advanced mode in the editor.
- **`obfs=salamander` without an `obfs-password`** is now a hard validation failure instead of silently
  building a no-obfs config that cannot connect.
- **Protocol-aware editor detection** now matches the codecs: VLESS/Hysteria2 JSON whose proxy outbound
  is not listed first is still editable, and malformed / non-v2 Hysteria2 JSON opens Advanced mode
  instead of a blank simple form.
- **Hysteria2 simple-editor saves** preserve `sockopt` (the secure-DNS `ForceIP`) and unknown
  `streamSettings` / FinalMask keys when merging edits back into JSON.
- VPN foreground-service notification shows immediately; re-posts on Android 14+ swipe-dismissal; a
  dismiss/re-post race is serialized; the notification icon is unified.

### Security
- **Fail-closed secure DNS (DNS-leak enforcement).** Every config is normalized to a DoH-only resolver
  (Cloudflare `1.1.1.1` + `1.0.0.1`, bootstrap-free, injected only when no secure resolver survives),
  all port-53 traffic is hijacked into Xray's DNS module (`dns-out`, rule placed first), and
  `sockopt.domainStrategy: ForceIP` is forced onto the proxy outbound so the server's own hostname
  resolves over DoH and fails closed if it can't. A warn-and-fix dialog flags configs that ship
  actively-leaking DNS on paste; subscriptions auto-fix and badge them. Adds a `Profile.sanitizedDns`
  column (Room migration 2 → 3).
- **Socket-level loop-avoidance via `VpnService.protect()`.** A single global Xray dial controller (Go
  bridge) carves Xray's own sockets out of the tun, so the **whole app** is tunneled — subscription
  fetches and update checks no longer bypass the tunnel in cleartext. Confirmed on-device to also cover
  Hysteria2's QUIC/UDP sockets.
- **Resilient, fail-closed startup.** `onStartCommand` returns `START_REDELIVER_INTENT` and reconnects
  the active profile after a process crash or always-on boot; routing is a total decision so an
  Android 14+ notification dismissal can't trigger a spurious auto-connect.

## [1.0.2R] and earlier

Last published release before the 2.0.0 line. See the git history for details; this changelog begins
tracking notable changes at 2.0.0PRE.
