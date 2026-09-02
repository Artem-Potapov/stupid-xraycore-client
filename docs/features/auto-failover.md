# Auto-Failover: Tunnel Health Watchdog, Rotation Engine & Fail-Closed Give-Up

Maintainer reference for auto-failover: a health watchdog that probes the **live tunnel**, an engine
that rotates to a sibling server when it stops passing traffic — over a **bridge TUN**, so the switch
itself never releases traffic to the clear network — and a give-up posture that is fail-**closed** by
construction rather than by luck. Connect-to-fastest (the manual counterpart that
reuses the same pool and the same ping machinery) is documented here for the failover-side halves and
in [`profile-actions-menu.md`](profile-actions-menu.md) for the menu/UI half.

Read together with [`failclosed-startup.md`](failclosed-startup.md) (whole-app tunneling is what makes
the probe valid), [`kill-on-foreground.md`](kill-on-foreground.md) (the two features share one screen
receiver and one tunnel), and [`ping-test.md`](ping-test.md) (the probe target and the coordinator).

> Everything in this doc is off by default. `FailoverPreferences.DEFAULT.enabled = false`.

## Why this exists

A VLESS/REALITY or Hysteria2 server can stop passing traffic without the tunnel noticing: the TUN fd
is still open, Xray-core is still running, `ConnectivityManager` still reports a VPN, and the app
still says **Connected**. The user sees pages that never load and has to work out for themselves that
the server died and that another one in the same subscription would work.

Auto-failover closes that loop. It also has to answer a second, harder question, which is where most
of the design effort went: **what should happen when nothing works?** The naive answer ("leave the
tunnel up") does not hold on the path that actually matters, and getting it wrong means silently
dropping the user onto the clear network — the exact failure mode a censorship-circumvention client
must never have.

## The health probe: a Kotlin HTTP 204 through the live tunnel

[`failover/Http204HealthProbe.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/Http204HealthProbe.kt)
issues an `HttpURLConnection` GET against a **fixed** target — `ConfigBuilder.HEALTH_PROBE_TARGET_URL`
— and requires HTTP **204**.

### The target is cleartext, and that has a HARD manifest dependency

`HttpURLConnection` is governed by Android's `NetworkSecurityPolicy`, and at `targetSdk = 36`
cleartext is **denied by platform default**. The probe target is `http://`, so it only works because
[`res/xml/network_security_config.xml`](../../app/src/main/res/xml/network_security_config.xml)
carves out `ConfigBuilder.HEALTH_PROBE_HOST` and `<application>` wires it in via
`android:networkSecurityConfig`. **Break any link in that chain — rename the host, delete the file,
drop the attribute — and every probe throws `IOException: Cleartext HTTP traffic ... not permitted`.**
The probe reports a throw as `false`, so the watchdog reads a **healthy** tunnel as dead and answers
with a rotation storm and a give-up over working servers, potentially UNPROTECTED, which stops the
service.

That defect shipped. It was invisible to the unit suite because every test injects a fake `opener`,
and no instrumented test had ever been run. `HealthProbeSchemeTest` now couples the constant, the XML
and the manifest attribute so drift is a unit-test failure. **Device-verified** (SM-S918B / Android
15): app-wide cleartext `false`, `cp.cloudflare.com` `true` → HTTP 204, `example.com` still denied,
and `sub.cp.cloudflare.com` denied (`includeSubdomains="false"`).

**Why cleartext rather than simply moving to `https://`.** HTTPS also fixes the defect and needs no
manifest change, but it costs camouflage, which matters for this app:

- When there is **no tunnel** — the `UNPROTECTED` give-up, or the window between tunnels — the probe
  travels the **clear network**, where DPI can read it. A plaintext `GET /generate_204` to
  `cp.cloudflare.com` is indistinguishable from Android's own captive-portal check, which is
  cleartext *by design* so portals can intercept it. A TLS handshake to that host is the anomaly: it
  marks the device as running something that probes on a schedule.
- Inside the tunnel, a periodic ~5 KB TLS handshake is a more distinctive traffic-analysis signature
  than a ~300 byte plaintext exchange.

`android:usesCleartextTraffic="true"` was rejected for the obvious reason — it re-permits plaintext
app-wide. The domain-scoped config carves exactly one host and leaves everything else on the platform
default; there is deliberately no `<base-config>`, so the file cannot loosen TLS trust as a side
effect.

**Both manifest halves are asserted, and the second one is why the check is DOM-parsed.**
`HealthProbeSchemeTest` reads `AndroidManifest.xml` through `DocumentBuilderFactory` (like the
`network_security_config.xml` half beside it) and asserts (1) `<application>`'s
`android:networkSecurityConfig` equals `@xml/network_security_config`, and (2) **no** element in the
manifest carries `android:usesCleartextTraffic="true"`. The manifest half used to be a `contains`
check, which caught the attribute being *deleted* but not app-wide cleartext being *added* — and the
added case leaves the carve-out attribute in place, so the probe keeps working while every other
plaintext request in the app silently starts working too. Verified by adding the attribute: only the
new assertion fails; the wiring assertion still passes.

**A Go-side probe was considered and rejected.** Go's raw sockets ignore `NetworkSecurityPolicy`
entirely, so a new bridge entry point would need no manifest change at all — but it lands in
human-gated `xray-go/` + `bridge/`, needs an `xray.aar` rebuild, a reflection binding and a keep rule,
and hits Go's Android DNS problem: the pure-Go resolver reads `/etc/resolv.conf`, which Android does
not provide, which is exactly why `MeasureLatency` sidesteps it with `core.Dial`. A new probe would
have to dial `HEALTH_PROBE_IPS` literally, reintroducing the stale-address brittleness the hostname
URL exists to avoid. Strictly heavier than the manifest for the same benefit.

**None of this applies to the Ping Test target**, which is cleartext for an unrelated reason and needs
no exemption: `MeasureLatency` dials it from the Go bridge over raw native sockets. `isValidTarget`
actively rejects `https://` there. Do not "unify" the two — see [ping-test.md](ping-test.md).

**It is deliberately NOT `XrayBridge.measureLatency`.** `MeasureLatency` builds a *throwaway*
`core.Instance` whose sockets are `protect()`'d **out** of the tun by 2A's global dial controller (see
[`ping-test.md`](ping-test.md) and [`failclosed-startup.md`](failclosed-startup.md)). It therefore
answers "can this config reach that server", **not** "is the live tunnel passing traffic" — and those
two diverge in precisely the failure mode this feature targets.

What makes the plain Kotlin request the right probe is 2A's other half: `SplitTunnelPlanner` keeps
**this app inside the tunnel in both split modes** (only `protect()`'d Xray sockets bypass). So the
probe's packets travel `tun → xray → proxy → internet`, the exact path user traffic takes. **If
whole-app tunneling is ever reverted, this probe silently becomes meaningless** — it would start
measuring the clear network and would never fire. Treat the two as coupled.

That coupling is also load-bearing in the other direction: it is what structurally guarantees a probe
through a **blackhole** TUN (an fd nobody reads) can never succeed, which is why
`clearGiveUpStateOnRecovery` can trust a healthy probe (see below).

### Reaching the tun is not enough — the routing table has to cooperate

Entering the tun only gets the probe as far as Xray. **Xray then picks the outbound**, and
`BLOCKED_ONLY` ("proxy only blocked sites") ends its rule list with a `network: tcp,udp → direct`
catch-all. Under that mode the GET went out through `freedom` on a `protect()`'d socket and returned
204 **with the proxy completely dead** — so the watchdog could never rotate, and worse, the healthy
branch would *clear* a legitimate `CONTAINED_BY_LIVE_TUNNEL` give-up on the strength of a request that
never touched the proxy.

Two halves close it, and they are one change:

- **`ConfigBuilder` carves the probe host through the validated proxy path** —
  `healthProbeCarveOutRules(proxyTag, balancerTag)`, **two** rules: `domain: full:cp.cloudflare.com`
  and `ip: <HEALTH_PROBE_IPS>`. Both are emitted in the same position as their `dohGuardRules`
  sibling: right after the forced port-53 rule, **ahead of the LAN and ad-block rules** (a later
  `geosite:category-ads-all → block` match would turn the probe into a permanent failure) and ahead
  of the imported config's preserved rules. When tun traffic uses a balancer, both rules and the DoH
  guard use that same balancer; otherwise they use the first proxy outbound. Like every rule this
  chokepoint emits, they only ever move traffic **toward** a proxy path.
- **Imported balancers are sanitized before inbound-tag revival.** Xray `selector` entries are
  **prefixes**, not exact outbound tags, so each selector is expanded against the actual
  non-helper proxy outbounds and rewritten as the exact matching tags. Fallback tags remain exact
  outbound references; `fallbackTag: direct` (or any freedom, blackhole, DNS, or unknown helper) is
  removed. A balancer with no proxy member is **not** rewritten and **not** removed — it is carried
  through untouched, because xray-core's `Router.Init` hard-fails on an unknown `balancerTag` and the
  core would then never start, and because deleting its rules instead would silently proxy traffic
  the user deliberately sent direct. Do not assume inbound-tag reconciliation would cover those
  rules: it only inspects rules whose `inboundTag` died in the tun rewrite, and the archetype (a
  `geosite:` country rule pointing at a balanced *direct* egress) has no `inboundTag`. Every later
  consumer — `safeBalancerTags`, inbound-tag reconciliation, the health-probe carve-outs, and the
  BLOCKED_ONLY DoH guards — uses only those validated balancer targets. A dead proxy balancer
  therefore cannot make the health probe succeed through `freedom`; it fails closed instead. Full
  rules in [`routing-rules.md`](routing-rules.md).
- **The target is a fixed constant**, `ConfigBuilder.HEALTH_PROBE_TARGET_URL`, no longer
  `PingPreferences.targetUrl`. This is *forced by* the carve-out, not an extra: a static routing rule
  and a user-editable target cannot both be right. It also closes a second hazard — the Ping Test
  target is validated only as an `http://` prefix, so a user pointing it at any ordinary page made
  every health probe fail forever, i.e. a rotation storm and a give-up over healthy servers. The Ping
  Test feature keeps its own editable target and its own default; this is a **separate** constant.

**Every mode gets the rule** — an earlier revision scoped it to `BLOCKED_ONLY` on the claim that the
other modes' only direct rule is the `geoip:private` LAN bypass, which cannot match a public host. That
claim was **false**, so the scoping went with it. Three things can route the probe direct, and only the
first is `BLOCKED_ONLY`-specific:

- `BLOCKED_ONLY`'s direct catch-all;
- `EXCEPT_COUNTRY`'s country **direct** rules (`directTags` — `geosite:category-ru`, `geoip:ru`,
  `ext:geosite_RU.dat:ru-available-only-inside`). `geoip:ru` is the live one: geo datasets do attribute
  anycast prefixes to individual countries, and `cp.cloudflare.com` is anycast;
- a direct rule inside the pasted config, which `applyRouting` preserves in **all three** modes.

Under `EXCEPT_COUNTRY` the carve-out therefore **deliberately overrides the user's country-direct policy
for this one hostname**. That is a real exception to a user-visible setting, and it is the right call:
the probe is the app's own diagnostic traffic, and a probe that bypasses the proxy answers the wrong
question. It is one `full:` domain match, not a policy change.

**Why the carve-out is two rules.** A `domain` rule matches only while sniffing is on: with a tun
inbound the destination is an IP, and nothing supplies a domain to match against unless the inbound
sniffs one. `routingNeedsDomainRules` forces sniffing for `BLOCKED_ONLY`, `EXCEPT_COUNTRY` and
ad-blocking, and the XRAY screen can force it too — but in the **default posture** (`PROXY_ALL`, ads
off, user sniffing off) nothing does, and there the `domain` rule was emitted but **inert**: a preserved
imported direct rule claimed the probe, the 204 came back with the proxy dead, and every surface read
healthy. The `ip` rule over `ConfigBuilder.HEALTH_PROBE_IPS` closes exactly that case — it matches
`Outbound.Target`, which a tun inbound always populates, so it needs no sniffing anywhere.
(`applyCoreSettings` only ever writes `routeOnly: true` sniffing, which leaves `Outbound.Target` an IP,
so the two rules coexist rather than displacing each other.)

`HEALTH_PROBE_IPS` lists **both address families** — with XRAY IPv6 on the in-tunnel resolver may return
the AAAA, and with it off `queryStrategy=UseIPv4` forces the A. The v6 entries are the v4 ones embedded
in Cloudflare's `2606:4700::/32` anycast prefix (`0x6810` = `104.16`), i.e. the same two endpoints.

**Residual, stated rather than papered over: an address change *and* sniffing off.** The addresses are
an optimisation, never a dependency — `HEALTH_PROBE_TARGET_URL` stays a **hostname** URL precisely so
that a stale list degrades to the old `domain`-only behaviour instead of breaking the probe. (Making the
URL an IP literal would invert that: Cloudflare answers a bare-IP request with **403**, so an address
change would break every user's probe at once and manufacture a rotation storm.) Refreshing
`HEALTH_PROBE_IPS` is the fix. Two things that are **not**: forcing sniffing whenever routing applies —
sniffing is a global switch on the single tun inbound with no per-rule scoping, so it would also
activate the pasted config's own `domain` rules, sending thousands of destinations direct from the
user's real IP while the Routing screen still reads "Proxy everything" (that approach was built,
analysed and abandoned) — and a broad Cloudflare CIDR, which would survive address rotation but route a
large share of the web through the proxy and override the user's country-direct policy far beyond one
diagnostic hostname.

**`ConfigSanitizer` DOES report it** — `FindingId.HEALTH_PROBE_CARVEOUT`
(`ConfigSanitizer.kt:287`, presented via `san_health_probe_carveout`), built from
`ConfigBuilder.healthProbeCarveOutInfo` so the diagnostic never re-derives the forward rule. The
detail states the override of country-direct / imported-direct for `HEALTH_PROBE_HOST` and surfaces
the address-list residual when sniffing is not forced.

> An earlier revision of this paragraph argued the opposite — that the carve-out deliberately gets no
> finding, "exactly like `dohGuardRules`, which it mirrors and which is likewise unreported". The
> reasoning was that an always-on internal mechanism with no user-facing knob, which can only make the
> posture *more* proxied, is not policy the user chose. That argument lost: the carve-out **overrides
> a rule the user's own config asked for**, and the maintainer ruling on that config is that a silent
> choice between our normalization and the author's intent is not acceptable. The `dohGuardRules`
> half is still true — it has no finding — and it is now the odd one out rather than the precedent.
> Left here because the claim outlived its own change and was quoted as settled by a later review.

The constant lives in `config/` rather than `failover/` because the carve-out owner is `ConfigBuilder`;
a `config → failover` import for one string would be the wrong direction.

## The monitor loop

[`failover/TunnelHealthMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/TunnelHealthMonitor.kt)
polls on a fixed cadence and fires `onUnhealthy` after `failureThreshold` **consecutive** failures.
Four properties are non-obvious and each one exists because of a defect found in review:

- **Offline guard.** Before every probe it asks
  [`NetworkAvailability`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/NetworkAvailability.kt)
  whether a **non-VPN** network with `NET_CAPABILITY_INTERNET` exists. If not, the tick is skipped
  **and `consecutiveFailures` is reset to 0**. Without this, airplane mode or lost signal would make
  every probe fail and the engine would thrash through the entire server list blaming servers for the
  phone being offline. The reset (not merely a `continue`) matters: returning signal must not trip an
  instant rotation off a stale count.
- **Terminal after firing.** `isStarted = false` and `job = null` are written **before**
  `listener.invoke()`, so the monitor is fully terminal for that `start()` call. Both pause/resume
  orderings were broken before this: if the fire landed first, `resumePolling()` relaunched into a
  loop carrying `reportedUnhealthy = true`, which then polled **forever while permanently deaf**.
  (During the fix round this manifested as a *hung* test rather than a failing one — the deaf loop
  never terminates, so `runTest`'s cleanup never reached an idle scheduler.) Only a fresh `start()`
  revives it — which is exactly what `applyFailoverPreferences` does after a rotation.
- **Nothing thrown escapes.** The monitor's scope has **no** `CoroutineExceptionHandler`, so an escaping
  throw would reach the default handler and kill the process while the VPN is up. `runPollLoop()` is
  wrapped in `try/catch(Throwable)` at its **single** launch site (`launchAndInstallPollLoop`, shared
  by `start()` and `resumePolling()`), the availability check and the probe are each guarded per-tick
  (a throwing availability check is treated as "offline", not as a server failure), and both listener
  invocations are guarded too. `CancellationException` is rethrown at every one of those sites
  — structured concurrency must still work.
- **Both installs into the `job` slot are atomic.** `launchAndInstallPollLoop` ends in
  `tryInstallJob` (CAS + a post-CAS `isStarted` re-check that retires an orphan). `start()` used to
  finish with a plain `job.set(launched)` while its own KDoc claimed the CAS covered creation — a
  claim that was true of half the class. It is unreachable in production (the service calls
  `start`/`stop` under one `lock`), which is exactly why it was a **claim** problem worth closing in
  code rather than documenting as an asymmetry: a caller stepping outside that lock had two orphan
  shapes, a stop landing before the `set` parking a live loop behind `isStarted = false`, and a
  `resumePolling` whose job `start` would silently overwrite rather than cancel. `isStarted = true`
  must stay the **first** statement in `start()` — it is the `stillLive` predicate the install
  re-checks, so moving it below would make every start decline its own job
  (`aFreshStartRevivesATerminalMonitor` pins it).
- **Startup waits one interval, but `resumePolling()` probes immediately.** A normal `start()` gives a
  newly started Xray outbound time to settle before the first health probe; screen-on recovery still
  uses its immediate `firstTick` so it recovers fast at zero idle cost. `pausePolling()` deliberately
  **preserves** `consecutiveFailures` across a screen-off; `stop()` clears it.

`onHealthy` fires **at most once** per `start()`, on the first successful probe. It exists because the
give-up state would otherwise be able to outlive the condition it describes (see
`clearGiveUpStateOnRecovery`). Note its **parameter order**: `start(onHealthy = null, onUnhealthy)` —
`onHealthy` is declared first *specifically* so the mandatory `onUnhealthy` stays last and existing
trailing-lambda callers keep binding to it. Swapping them silently re-binds every such caller to the
optional listener.

## Rotation: the state machine

Rotation is a third tunnel transition alongside the kill-switch's kill and revive, and it keeps their
locking discipline exactly: reserve the transition under `lock` **before** any async work, re-check
ownership before every mutation, route every escape through one fail path.

```
                        monitor fires onUnhealthy
[CONNECTED] ──canReserveRotation (CONNECTED only)──> [ROTATING]
     ▲                                                    │
     │                                    tearDownTunnelLocked()
     │                            + OPEN THE ROTATION BRIDGE (unread TUN)
     │                              + announce CONNECTING / "Switching…"
     │                                                    │
     │                                          bringUpTunnel(next)
     │                              (release the bridge, then establish(),
     │                               adjacent, inside one locked block)
     │                                             │            │
     └────── success: CONNECTED, 1104 notice ──────┘            │
     │       active profile advances, monitor restarted         │
     │                                                          │
     └────── probe failure: episodeFailedIds += current.id
             bring-up failure: episodeFailedIds += next.id,
             tear down the half-built fd,
             RE-OPEN the bridge for the retry,
             currentProfileId rolled back,
             recurse into rotateTunnel (still under the thrash cap)

thrash cap Denied ──┐
no candidate left ──┼──> giveUpRotationLocked()   (the ONE funnel; see below)
rotation error   ───┘
```

Key properties:

- **`ROTATING` is its own `SessionTunnelState`**, deliberately not shared with `REVIVING`. Rotation
  reserves from `CONNECTED`; revive reserves from `PAUSED`. Sharing one state would let a kill-switch
  revive and a failover rotation each believe they own the same transition.
- **The gap is BRIDGED, and also announced.** See [the next section](#the-rotation-bridge-no-clear-network-during-a-switch)
  — it is the reason rotation is safe at all. The announcement remains: the same locked block that
  tears down publishes `CONNECTING` + `vpn_status_switching`, because the bridge *holds* traffic but
  does not *carry* it, so leaving the UI, the ongoing notification and the QS tile saying CONNECTED
  would still claim a working connection the user does not have. Every exit re-announces: success →
  `CONNECTED`, retry → `CONNECTING` again, give-up → `BLACKHOLED`/`ERROR`.
- **A failed candidate's fd is torn down.** `bringUpTunnel` can fail *after* `establish()` (e.g.
  `startXray` threw), leaving a real fd with an indeterminate Xray behind it. The failure arm drops it
  and clears `tunInterfaceKind`, so a give-up cannot mistake that half-built fd for either a working
  tunnel or an unread containment.
- **`currentProfileId` is rolled back on failure.** It is what `reviveTunnel` brings up; leaving it on
  a server just proved dead would make a kill-switch revive fail and `failRevive` → `stopVpn` take the
  whole tunnel down. It also keeps the field in step with `ActiveProfileRepository`, which only
  advances on success.
- **`episodeFailedIds` is episode-scoped.** Each unhealthy callback records the current server before
  choosing a replacement; a bring-up failure also records its candidate, which never became current.
  A core start does **not** end the episode — the remote proxy may still be dead — so a
  core-start-success / probe-fail sequence walks every eligible pool member rather than returning to
  the first one. The first passing live-tunnel health probe clears the set, as do give-up and full
  teardown, so a server that failed an hour ago is not skipped forever. Every write is inside the
  ownership re-check: `getById`/`resolve`/`bringUpTunnel` all ran off-lock, so a stop+restart in that
  window would otherwise let an old-epoch failure blacklist a server in the **new** session's episode.
- **A committed rotation cannot be reclassified by its own follow-up work.** `rotateTunnel`'s body is
  wrapped in `catch (Throwable) → failRotation → giveUpRotationLocked`, and after the `.onSuccess`
  lock block has committed, that funnel would run with `sessionTunnelState == CONNECTED` and a live
  fd — classifying `CONTAINED_BY_LIVE_TUNNEL` and writing `BLACKHOLED` over the healthy tunnel the
  rotation just restored. Every post-commit step therefore runs through `afterRotationCommitted`,
  which logs a throw instead of letting it escape (and still propagates `CancellationException`).
  Guarded **per step**, not as one block: skipping `applyFailoverPreferences` leaves the watchdog dead
  for the rest of the session and skipping the replay silently drops a kill-switch event, so a shared
  guard would let a throw in the trivial first step take both out.
- **That guard is not an ownership check, and the active-profile write needs one.** The off-lock
  post-commit steps run after the lock is dropped, so a stop — or a stop plus a whole new session —
  can land underneath them, and `afterRotationCommitted` only answers "did this throw".
  `ActiveProfileRepository.setActiveProfileId` is a write to **process-global state that outlives the
  process**: `resolveActiveAndStart` reads exactly that value, so a superseded rotation writing it
  would point the UI, the QS tile and the next always-on/boot start at a server the current session is
  not using. It therefore re-checks `isCurrentSessionLocked` and performs the write **under the same
  lock acquisition**, like every other mutation in `rotateTunnel`. Its three siblings need no such
  treatment: `applyFailoverPreferences` and the deferred-kill replay (via `killTunnel`) both re-check
  the session under `lock` themselves, and `postFailover` (1104) is an auto-cancel informational notice
  about a switch that genuinely happened — a stale one is cosmetic, not a false claim about the
  current state.
- **A committed REVIVE is guarded the same way, for a different consequence.** `reviveTunnel` does
  the same class of post-commit work (publish `CONNECTED`, retract 1103/1105, refresh 1101, restart
  the watchdog, replay a deferred kill) with its own `catch (Throwable) → failRevive` funnel still
  armed. Rotation's escape *reclassifies* a success; revive's escapes into **silence** — `failRevive`
  demands `REVIVING`, the commit has already left it, so it logs nothing, reports nothing and stops
  nothing, and the session is left with a live tunnel, a dead watchdog and a dropped kill-switch
  event with no trace of why. `afterReviveCommitted` wraps each step. Both wrappers share one body
  (`afterTransitionCommitted`), which keeps the try/catch and the `CancellationException` rule in one
  place while keeping the **per-step** granularity that is the whole point; the two named wrappers
  exist so each transition's distinct consequence stays written down at the code.
- **The thrash cap is a sliding window.** `FailoverDecision.admitRotation` prunes attempts older than
  `rotationWindowMs` before counting, so a burst long ago cannot permanently lock failover out.
- **Candidate order is list order, not latency order** (`FailoverDecision.nextCandidate`). Deliberate
  for v1: ping results live in `VpnViewModel` and are unreachable from the service, and for failover
  "any working server" beats "the fastest server" — a poor pick simply rotates again. Latency ordering
  needs a process-scoped ping repository (deferred).
- **The pool comes from `FailoverPoolResolver`** — the manual (`subscriptionId == null`) partition or
  the current profile's whole subscription, resolved fresh from the DAO and never persisted (Room
  profile ids churn: `replaceProfilesForSubscription` deletes and re-inserts on every refresh). That
  object's KDoc marks it the **Spec 2 seam** for user-curated pools. Connect-to-fastest resolves through
  the same object on purpose — an independently-derived second pool would diverge with no compile-time
  signal.

## The rotation bridge: no clear network during a switch

`rotateTunnel` tears the dead TUN down under `lock`, then `bringUpTunnel` does `buildRuntimeConfig`,
`GeoAssetPreparer.prepare`, the `SplitTunnelRepository` read and the whole `Builder` setup **off-lock**
before it reaches `establish()`. For that entire span the session owns **no VPN interface**, so every
app that was tunneled emits cleartext on the underlying network. It happens on **every** routine
rotation, and once per dead candidate while a pool is exhausted. For users evading state censorship
that is a multi-second window of real destinations visible to DPI, repeated per switch.

The teardown-before-bring-up ordering is forced by `bringUpTunnel`'s `check(tunInterface == null)` and
did not change. What changed is that the window is now **covered by a bridge TUN**: the give-up
blackhole builder re-used as a stop-gap — same session name, MTU, addresses, default routes, DNS
servers and `SplitTunnelPlanner` plan, so exactly the same apps stay captured, but **no protector and
no Xray**. Packets enter an fd nobody reads and are dropped.

**The trade is deliberate and is the safe direction:** during a switch, apps briefly lose connectivity
instead of briefly leaking.

`shouldEstablishRotationBridge(hasTunnel, hasRotationBridge, tunnelState)` (pure, in
`SessionLifecycleDecision.kt`) decides when one is opened: `ROTATING` only, no live tunnel, and none
already held. Scope is **rotation only** — an initial connect has no prior tunnel to bridge from, and
`reviveTunnel` starts from `PAUSED`, where the absence of a TUN is the kill-switch's deliberate intent.

### `tunInterface` holds either a live proxy or unread containment — kind is explicit

The bridge lives in `rotationBridgeInterface`, **never** in `tunInterface` during the rotation gap.
That is still load-bearing: mid-rotation the bridge must not join `tunInterface`'s overloaded meaning
before a give-up can adopt and re-kind it.

`tunInterface != null` alone is **not** "still proxying". After a blackhole give-up or bridge adoption,
that field holds an **unread** fd. `tunInterfaceKind` (`NONE` / `LIVE_PROXY` / `UNREAD_CONTAINMENT`)
tracks which, and `classifyGiveUpOutcome(heldKind, …)` keys off the kind — not a boolean presence
check. Without the kind, a **second** give-up in the same session (monitor rebuild over a blackhole,
or `failRotation` after a post-settlement throw) would post `vpn_status_no_response` over a drop-only
fd.

| Reader | What it does | What a bare `!= null` would cause after a blackhole |
|---|---|---|
| `giveUpRotationLocked` (`heldKind`) | feeds `classifyGiveUpOutcome` | `CONTAINED_BY_LIVE_TUNNEL` over an unread fd |
| `clearGiveUpStateOnRecovery` | refuses to clear while null (clear-network probe) | unchanged — still presence-based, and correct: a blackhole probe cannot succeed |
| `bringUpTunnel` / the unread-TUN builder | `check(… == null)` before `establish()` | unchanged |

A fuller **tunnel-role enum** that also folds `rotationBridgeInterface` into one field
(`LIVE_PROXY` / `UNREAD_BRIDGE` / `UNREAD_BLACKHOLE` / `NONE`) remains the recommended follow-up —
see the Wave A report — but the kind on `tunInterface` closes the misclassification without that
wider refactor.

### A give-up in the gap ADOPTS the bridge

The bridge already *is* the interface a give-up wants, so `giveUpRotationLocked` takes it over rather
than building a second one — which would strand the bridge fd, i.e. leak a VPN interface for the rest
of the process's life.

`containmentForGiveUp(hasTunnel, hasRotationBridge, tunnelState)` (pure) is the single ordered
decision: `NONE` / `ADOPT_ROTATION_BRIDGE` / `ESTABLISH_BLACKHOLE`. It **replaced** the two-valued
`shouldEstablishBlackholeTunnel`, because containment now has two sources that must be evaluated in a
fixed order and a pair of independent booleans cannot express that — check "build a blackhole" first
and it fires while a bridge is held. An exhaustive `when` over the enum makes that inversion
unrepresentable. `hasTunnel` still wins outright, and the `CONNECTED`-only rule is unchanged:
adopting a bridge into `PAUSED` would break the kill-switch's "no tunnel must exist" contract exactly
as surely as building one would.

The outcome is `CONTAINED_BY_BLACKHOLE`, and it is reached **honestly, not by coincidence**: an
adopted bridge is byte-for-byte the blackhole the give-up would have built. It stays honest because
`giveUpRotationLocked` captures `tunInterfaceKind` **before** the containment step and retags it
`UNREAD_CONTAINMENT` on adoption — so a later give-up over the same fd cannot inherit live-tunnel
copy either. `SessionLifecycleDecisionTest.adoptingTheBridgeIsClassifiedAsABlackhole_neverAsALiveTunnel`
pins the *within-one-give-up* composition; `giveUpOverAnExistingUnreadContainment_staysBlackhole_neverLiveTunnel`
pins the *across-give-ups* case.

### Every exit releases or adopts the bridge

An unreleased bridge is a leaked VPN interface that goes on capturing every tunneled app into an
unread fd. The full set of exits from the gap:

| Exit | Handling |
|---|---|
| Bring-up succeeds | `bringUpTunnel` releases it immediately before `establish()` (the handover, below) |
| Bring-up fails — before or after `establish()` | the failure arm tears down any half-built fd and **re-opens** the bridge (idempotent: a pre-`establish()` failure still holds the original and keeps that fd rather than churning it) before recursing, and the recursion re-reads the DB and re-resolves the pool off-lock |
| Recursive retry / N dead candidates | one bridge is held across the whole episode; `shouldEstablishRotationBridge`'s `hasRotationBridge` term is what prevents a second |
| Give-up (thrash cap, no candidate, rotation error) | **adopted** as the blackhole |
| Give-up stand-down (`sessionTunnelState != CONNECTED`) | released — a backstop; `killTunnel` and `stopVpn` already cover the states that reach it |
| Kill-switch pause between attempts | `killTunnel` releases it. Reachable: a failed candidate returns to `CONNECTED` and dispatches its retry as a *separate* coroutine, so a kill queued on the same serialized `tunnelOpScope` can land in between. `PAUSED` means "no tunnel must exist", and the bridge would also be stranded — the retry then bails at `canReserveRotation` |
| `stopVpn` (user stop, `onDestroy`, `onRevoke`, the give-up that stops the service, the `UNPROTECTED` recovery restart) | released, **above** the `!shouldStop && tunInterface == null` early return so every path through it is covered |
| A stale/superseded session | covered by `stopVpn`: losing ownership mid-rotation means a stop ran, and the failure arm and the give-up funnel both skip their own bridge handling when the ownership check fails |

`stopVpn` gains only one `ParcelFileDescriptor.close()` — nothing that awaits, so the RISK-1 rule
(the `UNPROTECTED` recovery restart calls `stopVpn` on the main thread) still holds.

Two things the table above depends on that are **not visible from it**:

- **`onDestroy` must keep calling `stopVpn()` after `tunnelOpScope.cancel()`.** Cancelling the scope
  means a rotation sitting in the gap never resumes, so it can never run its own release —
  `stopVpn` is then the only thing left that can, and it is what makes the "coroutine never resumes"
  case a covered exit rather than a leak. `tunnelOpScope.cancel()` has exactly one call site
  (`onDestroy`), so this is a single ordering to preserve, not a pattern. It is load-bearing for the
  bridge and reads as unrelated cleanup.
- **Disabling auto-failover mid-episode does not leave a stranded bridge.** The disable branch does
  not release a bridge mid-`ROTATING` (releasing on a settings change would drop the user onto the
  clear network). The reservation boundary is the
  `canReserveRotationFromAuthoritativeState` seam: it reads `FailoverPreferences.state.value.enabled`
  at the queued operation's lock-time admission, not the asynchronously collected service cache.
  If a queued retry refuses while the failed candidate's bridge is held,
  the refusal enters the give-up funnel and **adopts** that bridge as `CONTAINED_BY_BLACKHOLE`; only
  refusals without a sole containment bridge use the ordinary release cleanup. A rotation that
  already holds `ROTATING` still runs to a handled exit (release / re-open / adopt / abort-to-give-up).

### One builder body, two users

`establishUnreadTunnelLocked(label)` is the shared body; `establishBlackholeTunnelLocked()` and
`establishRotationBridgeLocked()` are thin wrappers that differ only in which field takes the fd. Not
copied, deliberately: a drifted second copy would capture a **different app set** than the tunnel it
replaced, which is a silent leak of exactly the apps the user tunneled. Its `check` covers both fields
and stays **inside** the `try` for the reason it always did — both callers are reachable from
`rotateTunnel`'s `catch (Throwable)`, and a throw raised inside a catch block escapes that try/catch
entirely, landing uncaught on a `SupervisorJob` with no handler.

Bridge establishment is **required once the live TUN is torn down**. On failure,
`shouldAbortRotationForMissingBridge` aborts into give-up rather than rebuilding uncovered — see
Known residuals / the bridge section's failure path.

### Handover ordering: release-then-establish, and the follow-up it defers

The handover is **release the bridge, then `establish()` the real interface**, adjacent, inside
`bringUpTunnel`'s existing locked block, with **no I/O between them** — everything expensive has
already run off-lock above. The window in which no interface exists collapses from seconds to one
binder round-trip, and `bringUpTunnel`'s `check(tunInterface == null)` precondition is untouched.

**The seamless ordering is a real, derived follow-up — not a rejected idea.** Android replaces the
process's active VPN interface when `establish()` is called again, so establishing *first* and closing
the bridge *after* would leave no instant without an interface at all, making the handover genuinely
gapless. The reason it is not implemented: what happens to the still-active bridge when that second
`establish()` **fails** is exactly the part no documentation settles. If a partially-applied failure
deactivates the bridge, the code would believe it holds an interface while the user is on the clear
network — trading a bounded stall for an unbounded leak, which is the wrong direction for this threat
model. It needs a device, and none was reachable. **Verify on hardware, then swap the two lines and
close the residual window.** Until then the residual exposure is one `establish()` call, plus — only
on the bring-up-failure path — the short hop from that call returning to the failure arm re-opening
the bridge under the same lock.

## Coexistence with the kill-switch

The two features share one tunnel, one screen receiver, and — in `ROTATING`/`PAUSED` — one set of
mutually-exclusive claims on the fd. The rules:

| Situation | Behaviour | Why |
|---|---|---|
| Kill-switch event arrives during `ROTATING` | **Deferred**, not dropped (`shouldDeferKillDuringTransition`), recorded in `pendingKillLabel`, and replayed if the rotation commits `CONNECTED`. If the rotation instead **gives up**, the funnel **drops** it and says so — see below | The foreground monitor is edge-triggered and would never re-fire the event, leaving the tunnel CONNECTED with a kill-listed app foregrounded |
| The listed app **leaves** before the rotation commits | The deferral is **withdrawn** (`deferredKillToWithdraw`), so nothing is replayed — see below | Same edge-triggered monitor: the leave fires once and, if dropped, the replayed kill parks the session `PAUSED` for an app that has gone, with no automatic recovery |
| Tunnel is `PAUSED` (kill-switch) | The health monitor is **stopped**, not paused (`shouldRunFailoverMonitor` is `CONNECTED`-only) | There is no tunnel, so every probe would fail and the engine would "rotate" a tunnel the kill-switch deliberately tore down. `stop()` (not `pausePolling()`) because pausing preserves the failure count, which would trip instantly on revive |
| Revive commits `CONNECTED` | `reviveTunnel` calls `applyFailoverPreferences` **outside** the locked block, wrapped in `afterReviveCommitted` like every other post-commit step | Nothing else restarts the monitor; without this, failover is dead for the rest of the session after the first kill-switch pause. It must run after `CONNECTED` is committed or it reads `REVIVING` and no-ops — and an unguarded throw on the way there would silently take both it and the deferred-kill replay out |
| Give-up lands while the state is not `CONNECTED` | Stand down: log, re-arm the monitor timer, touch nothing | `PAUSED`'s compliance contract is literally "no tunnel must exist"; establishing a blackhole (or overwriting the PAUSED connection state) would break it outright |
| Screen off / on | One shared `BroadcastReceiver` pauses/resumes **both** monitors | Registration used to belong entirely to the kill-switch, which failed failover two ways: with failover on and the kill-switch off (the **default** pairing) no receiver existed at all, and turning the kill-switch off mid-session tore the receiver out from under a running failover monitor. `shouldHoldScreenReceiver(killSwitchLive, failoverLive)` now owns it — hold while EITHER is live, release only when NEITHER is |

### The fourth leg: a leave-foreground edge must WITHDRAW the deferral

The deferral has an enter edge that creates it and three exits that discharge it (two commits that
replay, one give-up that drops). It was missing the case where the **reason for deferring ends**.

The trace: a kill-listed app is opened during a rotation, so the kill is deferred. The user closes it
while the rotation is still in flight. `onControlledAppLeftForeground` → `reviveTunnel` →
`canReserveRevive` needs `PAUSED`, sees `ROTATING`, and no-ops — the leave edge is spent, and the
monitor is edge-triggered so it never re-fires. The rotation then commits and replays the kill
regardless. Result: `PAUSED` (no TUN, every app on the clear network) for an app that is no longer in
the foreground, indefinitely, with notification 1103 telling the user the wrong reason. The only exits
are manual. The same trace holds with `REVIVING` in place of `ROTATING`.

`reviveTunnel` now calls `withdrawDeferredKillLocked` on **every** leave callback, under `lock`, on
`tunnelOpScope`, keyed to the callback's epoch — before the revive decision and independently of it,
because a revive reserves only from `PAUSED` while the stranding deferral is outstanding in the other
states. The rule is the pure `deferredKillToWithdraw(pendingKillLabel, running, activeSessionEpoch,
callbackSessionEpoch)`.

**It takes no tunnel state, deliberately, and it is WIDER than the deferral rule it answers.** The
mirror shape — withdraw only in `{REVIVING, ROTATING}` — looks right and barely fires. `tunnelOpScope`
is `Dispatchers.IO.limitedParallelism(1)` and **`bringUpTunnel` is not a `suspend` function**, so it
holds that single slot for its whole blocking span (config build, geo prep, `establish()`,
`startXray`). A leave arriving in that span — which is where a leave actually lands, since the DB reads
either side of it are milliseconds — queues behind the transition and does not execute until it has
already committed `CONNECTED`. A rotation episode also passes through `CONNECTED` *between* two failed
candidates with the marker still armed. So the marker, not the tunnel state, is what "a kill is queued
for replay" means, and a leave means "no queued kill is wanted" in every state. Not re-enumerating
`{REVIVING, ROTATING}` is the other half: that set keeps its single home in
`shouldDeferKillDuringTransition`, and a rule with no state parameter cannot drift into a second copy
of it. `SessionLifecycleRotationTest.everyStateThatCanDeferAKillCanAlsoWithdrawIt` asserts the
implication over the whole enum, and `withdrawalIsDeliberatelyWIDERThanDeferral_notAMirrorOfIt` pins
the asymmetry so it cannot be "fixed" back into a mirror.

The session check is the **only** refusal, and that is the second half of the derivation. Of the
reasons `canReserveRevive` can refuse a leave callback — stale epoch, stopped service, already
`CONNECTED`, mid-transition — only the first two may block a withdrawal: it cancels a *safety* event,
so a superseded session's late callback must never reach the marker a live session armed.

**The commits stop consuming the marker; the replay resolves it.** This half is what makes the
withdrawal reach the window that matters, and it is not a cleanup. `reviveTunnel`/`rotateTunnel` now
only *observe* `pendingKillLabel != null` at the commit and dispatch `replayDeferredKill`, which calls
`killTunnel` with a null label; `consumeDeferredKillLocked` reads and discharges the marker inside the
replay coroutine. Had the commit captured the label, the leave described above — queued *ahead* of the
replay but executing *after* the commit — would find an empty marker, withdraw nothing, and the replay
would pause the tunnel anyway. Reading it in the replay lets the one serialized scope's FIFO order
settle both cases:

- leave queued **before** the replay → it withdraws, the replay finds nothing, the tunnel stays up;
- leave queued **after** the replay → the replay pauses first, and the leave then arrives at `PAUSED`,
  which is `reviveTunnel`'s ordinary input, so the pause/revive pair self-corrects.

**No notification is posted.** 1106 (`announceDroppedDeferredKillLocked`) is the obvious-looking
precedent and is the wrong one: it fires when a give-up drops a kill while the listed app is *still
tunneled*, so the user asked for the VPN to be off for that app and it is not. Here the app has left —
nothing went unhonoured, and a heads-up would alert the user to a failure that did not occur. It is
logged only.

### The third exit: a give-up must DISCHARGE the deferred kill

A rotation has **three** exits, not two. `reviveTunnel`/`rotateTunnel`'s success arms replay
`pendingKillLabel`; the give-up funnel is the third and it must **drop** it. Leaving the marker armed
was a real merge blocker: the kill never fires, and then the re-arm rotation — up to
`rotationWindowMs` (default **10 min**, max 1 h) later — replays it, tearing down a just-restored
tunnel, setting `PAUSED`, and posting the 1103 "VPN is OFF — you're exposed" alert naming an app that
closed long ago. The user loses protection they never asked to lose, for a stated reason that is false.

So `giveUpRotationLocked` clears `pendingKillLabel` **at the top of the funnel** (one home, so no exit
— including the stand-down early return — can leave it armed) and then **notifies**: a bare silent
drop would leave the user with a silently non-functioning kill-switch. Replaying the kill on the two
contained outcomes was rejected for the same reason the deferral exists: if the app has already left
the foreground, the edge-triggered monitor has already fired, so the replay would strand the session
`PAUSED` with an exposure alert and no revive.

The notice is `VpnNotifications.postKillSwitchNotApplied` — **id 1106 on the existing
`EXPOSED_CHANNEL_ID`**. Ids and channels are independent: a new *id* is mandatory (channels are welded
at first post, so reusing 1103 would replace the exposure alert), while the kill-switch's
high-importance exposure channel is already the semantically right home for "your kill-switch did not
act". Whether it posts at all is the pure `deferredKillNoticeLabel(pendingKillLabel, tunnelStillUp)`:
nothing was deferred → silent; **no tunnel remains** (`UNPROTECTED`, and the give-up that stops the
service) → silent, because there the listed app genuinely is *not* behind a VPN and both of those
outcomes already report themselves on their own surfaces. Both contained outcomes still own an fd —
live or blackhole — and a blackhole TUN is still a VPN interface to the app that was trying to detect
one, so the notice is true there.

**1106 asserts something in the present tense, so it must be retracted when it stops being true.**
The notice says a listed app *is still going through the VPN*. `setAutoCancel(true)` only clears it if
the user taps it, so `VpnNotifications.cancelKillSwitchNotApplied` is called from **two** places:

- **`killTunnel`**, immediately **before** it posts 1103. The deferred kill has now landed and the
  tunnel is gone, so 1106 is false — and both notices live on the **same high-importance channel**, so
  leaving it up would pair "VPN is OFF for every app" with "that app is still going through the VPN"
  as two simultaneous heads-up alerts. Retract-before-post is the ordering: the two contradictory
  alerts must never coexist, not even briefly.
- **`stopVpn`**, alongside `cancelExposed` and `cancelFailoverBlackholed` — after a stop the claim is
  simply false, and `stopForeground` removes none of these (each has its own id).

The same retraction is recorded in [`kill-on-foreground.md`](kill-on-foreground.md), which owns the
1103/1106 channel story.

### 1103 and 1105 must never coexist

`killTunnel` retracts **1105 as well**, immediately before posting 1103, and for the identical reason.
Both are high-importance alerts and they contradict each other: 1105's contained variants say "your
connection was paused to keep you protected" while 1103 says "the VPN is OFF and you're exposed".

**This is reachable for all three give-up outcomes, not a theoretical pairing.** `killTunnel` proceeds
only from `CONNECTED`, and `giveUpRotationLocked` leaves `sessionTunnelState == CONNECTED` on *every*
path that posts 1105 — mechanically required, because a rotation reserves from `CONNECTED` and the
re-arm would otherwise never be able to try again. Nothing in the give-up path touches the kill-switch
monitor either (only `stopFailoverMonitorLocked`), so the pause can land at any time. The two paths
that do **not** post 1105 — the "leaving it to its owner" stand-down and the give-up that stops the
service — are also the two that do not leave `CONNECTED` running, so they are consistent.

**`giveUpOutcome` is deliberately not cleared with it.** Nothing reads it while `PAUSED`:
`repostOngoingNotification` takes its `PAUSED` branch, and `shouldRestartForRecovery` cannot be
reached because every start surface refuses `PAUSED`. It cannot outlive the pause either —
`reviveTunnel`'s success path clears it (and cancels 1105 again), and `failRevive` stops the session,
which clears it in `stopVpn`. Clearing it here would add a **second** disarm site for the marker
`shouldRestartForRecovery` keys off, which is the exact coupling that produced the
running-but-unconnectable defect on the disable path; the residual cost is recorded under Known
limitations instead.

## Give-up: three outcomes, one funnel, fail-closed

`giveUpRotationLocked` is the **single funnel every give-up passes through**. The thrash-cap and
no-candidate paths call it directly and never go through `failRotation`, so anything wired only into
`failRotation` (the re-arm timer, in particular) would leave the common "all servers dead" case
permanently disarmed.

### Why "just keep the tunnel up" is not fail-closed

The spec originally said give-up should keep the dead tunnel established so traffic is never exposed.
**That does not hold on the path that matters.** The all-servers-dead path tears the TUN down *before*
the final bring-up fails, so a give-up can land with `tunInterface == null` and hand the user's traffic
straight back to the clear network — and whether that happened would depend on *where* bring-up died
(after `establish()` = contained, before = exposed). A posture that is fail-closed-if-you-are-lucky is
worse than either consistent answer.

So when the session should own a tunnel and has none, the give-up ends up owning a **blackhole TUN**:
same session name, MTU, addresses, default routes, DNS servers and split-tunnel plan as a real
bring-up, but **no protector registration and no Xray**. Packets enter an fd nobody reads and are
dropped. It comes from one of two places, chosen by `containmentForGiveUp` — a fresh
`establishBlackholeTunnelLocked()`, or the **adoption of a rotation bridge** that is already open (see
[The rotation bridge](#the-rotation-bridge-no-clear-network-during-a-switch)). Both go through the same
builder, so this paragraph describes both. Capture parity with `bringUpTunnel` is exact and load-bearing — in particular
`addDnsServer` is set **and** both default routes send the resolver's own address into the unread fd,
so the system resolver, an app's own DoH/DoT to hardcoded IPs, and Private DNS strict mode all time
out rather than leaking. Apps the user split **out** keep the direct route they already had while
connected; everything else keeps riding the tun, now into the blackhole.

The builder's `check(tunInterface == null && rotationBridgeInterface == null)` sits **inside** the
`try` on purpose: it is reached from `rotateTunnel`'s `catch (Throwable)`, and a throw raised inside a
catch block escapes that try/catch entirely — landing uncaught on a `SupervisorJob` with no handler,
i.e. process death with the VPN up. A contract violation must degrade to "uncontained", never to a
crash.

### The three outcomes

`classifyGiveUpOutcome(heldKind, blackholeEstablished)` (pure, in `SessionLifecycleDecision.kt`).
`LIVE_PROXY` and `UNREAD_CONTAINMENT` both win outright — an fd is already containing traffic, so
`blackholeEstablished` carries no information in those cases — but they must not share one outcome:
unread containment is drop-only. `heldKind` is captured **before** the containment step within one
give-up, and the service keeps `tunInterfaceKind` honest after blackhole/adopt writes so a later
give-up cannot misread the same fd as still proxying.

| Outcome | Physical situation | Connection state | Ongoing line (1101) | Alert (1105) | Stops the service? |
|---|---|---|---|---|---|
| `CONTAINED_BY_LIVE_TUNNEL` | The current server's tunnel is **still up and still proxying**; there was simply nowhere to rotate to (no-candidate / thrash-cap, both of which run before any teardown) | `BLACKHOLED` | `vpn_status_no_response` | `postFailoverNoResponse` | never |
| `CONTAINED_BY_BLACKHOLE` | No live proxy; a blackhole was established or an unread containment fd was already held. Traffic is deliberately dropped | `BLACKHOLED` | `vpn_status_blackholed` | `postFailoverBlackholed` | never |
| `UNPROTECTED` | No tunnel **and** the blackhole could not be established. The user **is** on the clear network | `ERROR` + `LogRepository.emitError` | `vpn_status_unprotected` | `postFailoverUnprotected` | on the **second** consecutive one (see below) |

**These must never share one message.** An earlier revision fired the blackhole copy unconditionally,
so the 1105 body asserted "nothing leaks to the open network" in the exact case where everything leaks,
and the "no candidate" case told a user with a perfectly working tunnel that their traffic was blocked.
The `UNPROTECTED` variant must never inherit the reassuring containment copy — that would tell a user
their traffic is safe at the exact moment it is not. That is also why `UNPROTECTED` is the one outcome
that additionally goes through `LogRepository.emitError`: the Logs screen is not where a user learns
their traffic just went clear.

**The rule extends to the channel description.** All three alerts share `1105` and therefore share
`failover_blackhole_channel_description`, which is what the OS shows in the app's notification
settings. It used to read "…your connection was paused to keep you protected" — the containment claim,
attached to a channel that also carries `postFailoverUnprotected`. It is now outcome-neutral in both
locales ("Warns you when your servers stop responding and we cannot switch you to a working one. Each
alert says what happened to your connection."), which is true for all three. Any future notice added
to this channel must keep it that way.

### `BLACKHOLED` is a friendly facade, on purpose

`VpnConnectionState.BLACKHOLED` was added rather than reusing `ERROR`, because `ERROR` maps to a dead
session on every surface (tile → `STATE_INACTIVE`, no Disconnect) while a blackholed session is very
much **alive and holding a TUN**. The enum constant is technical because only developers read it;
**every user-visible string mapped from it is deliberately plain and non-alarming**:

- `main_state_blackholed_still_proxying` — "Server is not responding" (`CONTAINED_BY_LIVE_TUNNEL`)
- `main_state_blackholed_traffic_held` — "No server connection — paused" (`CONTAINED_BY_BLACKHOLE`)
- `main_state_blackholed` — "No server connection" (defensive fallback only when no line was recorded)
- `vpn_status_blackholed` — "No server connection — paused to keep you protected"
- `failover_blackhole_title/body` — "No server connection" / "…your connection is paused on purpose.
  That keeps your real location private instead of quietly going unprotected."

Home and tile resolve BLACKHOLED through `vpnConnectionStateLabelRes(state, LogRepository.giveUpLine)`,
which is the same recorded answer `giveUpRotationLocked` writes for 1101 — never re-derived from
`giveUpOutcome`. The two contained outcomes must not share one home/tile string.

**The same marker carries the uncontained outcome, which does NOT render as BLACKHOLED.** It was
once scoped to this state, on the reasoning that `connectionStateForGiveUp` sends `UNPROTECTED` to
`ERROR`. The cost was that home and the QS tile showed the generic `main_state_error` — literally
"Error", the same word an ordinary failed connection shows — for the one outcome that admits the
user is on the clear network, while the error banner, the 1101 line and the 1105 heads-up all said
"not protected". It never inherited *containment* copy, so the absolute rule held; it **understated**
exposure on the two most-looked-at surfaces. `ERROR` cannot tell an uncontained give-up from a dead
session on its own, so `GiveUpOngoingLine` has a third member and `vpnConnectionStateLabelRes`
consults it in the `ERROR` arm too:

- `main_state_unprotected` — "Not protected" (`UNPROTECTED`)
- `main_state_error` — "Error" (**no** recorded line: an ordinary connection failure, which must
  never be relabelled as an auto-failover give-up — the rule runs in both directions)

No jargon ("blackhole", "traffic blocked") appears in any user-visible string, in either locale. The
Russian copy carries the same facts and the same register — do not "improve" it toward the
technical wording.

**Widening this enum needs a grep sweep, not a green build.** The two `when` sites over
`VpnConnectionState` are exhaustive and the compiler flags them, but the enum is *also* read by plain
boolean equality chains the compiler cannot flag. Adding `BLACKHOLED` to the renders while missing the
chains left the QS tile rendering `STATE_ACTIVE` while `decideTileClick` still returned `Start` —
strictly worse than before, because the control now *looked* live and was not. The sites that must all
be **decided together** — note they do not all give the same answer, and are not supposed to: "is this
session live?" and "what connect affordance does it offer?" are two different questions, and
`BLACKHOLED` is the state where they diverge:

| Site | Rule |
|---|---|
| `tile/TileClickDecision.decideTileClick` | `BLACKHOLED` → `Stop`, via `shouldStopOnTileClick` |
| `tile/XrayVpnTileService.handleClick` | the same gate for its no-IO fast path — calls `shouldStopOnTileClick`, no longer a hand-written copy |
| `tile/XrayVpnTileService` render | `BLACKHOLED` → `STATE_ACTIVE`, like `PAUSED` |
| `state/connectAction` | `BLACKHOLED` → `RECONNECT` (**not** `UNAVAILABLE`) — see [Reconnect](#reconnect-the-affordance-a-give-up-actually-offers) |
| `MainActivity.isActive` | `BLACKHOLED` is **active** — the row stays highlighted and its menu offers Disconnect, not a connect row |
| `MainActivity.shouldShowDisconnect` | `BLACKHOLED` **and** `ERROR` both show Disconnect (whole-enum sweep in `MainActivityStateTest`; deliberately wider than the tile Stop set) |
| `XrayVpnService.repostOngoingNotification` | `BLACKHOLED` restores 1101 only (1105 is `setAutoCancel` — re-posting it would fight a deliberate dismissal), and picks its line from the recorded `giveUpLine`, **never** by re-deriving it from `giveUpOutcome` (see below); `ERROR` restores 1101 **only when** `giveUpLine == UNPROTECTED` — the same recorded source, so notification, home and tile cannot disagree about which give-up a session is in |

**The give-up line is a RECORDED answer, not a re-derived one.** `repostOngoingNotification` used
to read `giveUpOutcome` and treat "not `CONTAINED_BY_LIVE_TUNNEL`" as the blackhole line. The disable
branch clears `giveUpOutcome` while **deliberately leaving the state `BLACKHOLED`**, so after a
disable-release of a *live-tunnel* give-up a user swipe plus repost relabelled a still-proxying tunnel
with the blackhole copy — and the two contained outcomes have **opposite packet truths**, so that told
a user with a working connection that their traffic was being held. `giveUpRotationLocked` now records
`giveUpLine = giveUpOngoingLine(outcome)` (pure, total over the three outcomes; `null` only when no
give-up describes the session) in the same locked block that publishes the state. Restoring `giveUpOutcome` instead was not
available: that field is the "a recovery is still owed" marker `shouldRestartForRecovery` keys off, and
its release is load-bearing. The service and `LogRepository` clear the paired recorded line on a
successful rotation, successful revive, healthy recovery callback, and full teardown; disabling a
contained give-up still deliberately clears only `giveUpOutcome` while leaving `BLACKHOLED` and its
truthful line in place.

Three deliberate **non**-changes: `MainActivity.isConnecting`, `VpnViewModel`'s
`filter { CONNECTING }` error gate (widening it would erase the very error the user needs), and
`XrayVpnService`'s `wasPaused` check (`BLACKHOLED` is not the kill-switch's paused state, and
`reviveTunnel` would no-op at `canReserveRevive` anyway).

The error gate's *filter* is unchanged, but what it **does** no longer is. It used to clear
`VpnViewModel.error` unconditionally, which erased a **refusal** — the message telling the user that
the request they just made did nothing — as soon as the request that BEAT it announced `CONNECTING`.
`state/ConnectErrorRetention` now gives a refusal a reprieve of exactly one `CONNECTING` (the
winner's) and no more, so it cannot become the stale banner the auto-clear exists to remove. The
contended Reconnect in [Test 22](../qa/auto-failover-qa.md) is the path that reaches it: the state
reads `BLACKHOLED` for the whole teardown, so the affordance keeps rendering and a re-tap is the
natural response. Only the message was ever lost — `activeProfileIdToRestoreOnRefusedStart` covers
the correctness half separately, so nothing ever pointed at the wrong server.

## Reconnect: the affordance a give-up actually offers

A give-up used to leave every connect control **disabled**, on the reasoning that the service was
still running so a connect would no-op. That is true of a plain connect and false as a conclusion: it
left the user staring at a dead button in the one state where they most need a live one, with the
alert telling them to pick another server. The connect gate is therefore no longer a boolean.

### `ConnectAction` — three values, not two

`state/VpnViewModel.kt` holds three small top-level functions (`internal`, JVM-tested by
`state/ConnectActionTest`). They **replace the former boolean `canConnect`, which no longer exists**:

| Function | Contract |
|---|---|
| `connectAction(state)` | `DISCONNECTED`/`ERROR` → `CONNECT`; `BLACKHOLED` → `RECONNECT`; `CONNECTED`/`CONNECTING`/`PAUSED` → `UNAVAILABLE`. An exhaustive `when`, so a new `VpnConnectionState` is a compile error here. |
| `connectLabelRes(action, isConnecting)` | The label. `isConnecting` wins outright ("Connecting…"); otherwise `RECONNECT` → `main_button_reconnect`, everything else → `main_button_connect`. |
| `connectEnabled(action, isConnecting)` | `action != UNAVAILABLE && !isConnecting`. |

`ERROR` maps to `CONNECT` rather than `RECONNECT` on purpose: `ERROR` is where `UNPROTECTED` lands,
and "reconnect" would overstate what is left when the core could not establish at all. `UNPROTECTED`
keeps its own recovery path (`shouldRestartForRecovery`, below) — it never reaches `ReconnectFlow`.

> **The label and enablement halves are deliberately fed DIFFERENT arguments.** Every call site is
> `connectLabelRes(action, isConnecting)` beside `connectEnabled(action, isConnecting || requestInFlight)`.
> Only the reconnect *target* should read "Connecting…", while *every* control must be disabled (a
> contending tap would be refused anyway) — a single flag made thirty unrelated servers claim to be
> connecting. The invariant "never enabled while it reads Connecting…" holds because the widened set
> is a superset of the narrow one. **Do not "restore" identical arguments**; that is the defect, not
> the fix. `connectEnabled`'s KDoc says the same thing at the code.

### `ReconnectFlow` — stop, settle, start, verify

`state/ReconnectFlow.kt` is framework-free (the `FastestConnectRunner` shape) and is **the canonical
home for why Reconnect is sequenced this way**; `VpnViewModel.reconnect` and `MainActivity`'s connect
choke point both point at it rather than restating it. `ReconnectFlowTest` drives every rule.

The sequence: dispatch a Reconnect-marked `ACTION_STOP` → await `DISCONNECTED` for up to
`STOP_TIMEOUT_MS` (**8 000 ms**) → `start(profileId)` → observe the whole `START_VERIFY_MS`
(**2 000 ms**) window. A start is accepted only if it remains out of `DISCONNECTED` and never reaches
`ERROR`; a never-announced start, `CONNECTING → DISCONNECTED` bounce, or `ERROR` re-dispatches the
start **exactly once**.

Three things about that shape are load-bearing:

- **Why not `shouldRestartForRecovery`.** Widening it to cover the contained outcomes is the obvious
  fix and the one that must NOT happen. `CONTAINED_BY_LIVE_TUNNEL` has a **running Xray core**, and
  that path calls `stopVpn` on the **main thread**, where `stopXray()` would become a real
  `instance.Close()` — precisely the RISK-1 hazard below, which is cleared *only* because
  `UNPROTECTED` implies an already-stopped core. Reconnect's marked `ACTION_STOP` marshals onto the
  service's `tunnelOpScope` and calls `stopVpn(stopService = false)`, so stop → settle → start keeps
  every blocking call off the main thread **and leaves the service instance alive** for the next
  start. Explicit Disconnect, tile, and notification Stops do not carry that marker and still call
  `stopSelf()`. **Both** contained outcomes share this one path; the blackhole case deliberately gets
  no separate "faster" route, because two restart paths would be one rule in two homes — the shape
  behind most of this feature's defects.
- **The start is verified, not assumed.** `stopVpn` publishes `DISCONNECTED` about a dozen lines
  before it would normally call `stopSelf()`. Reconnect suppresses that destruction, but it still
  verifies the complete window: `CONNECTING` is only an announcement, and a subsequent
  `DISCONNECTED` or `ERROR` is a failed start, not success. The single bounded re-dispatch covers
  that failure or a start that never announces. It is bounded at one because a start that fails for a
  real reason (no profile, permission revoked) fails identically twice; a redundant second start is
  harmless (`startVpn` refuses it, and `activeProfileIdToRestoreOnRefusedStart` writes nothing for
  equal ids).
- **First request wins.** `reconnectingProfileId` is armed *synchronously*, before the coroutine is
  launched, so a second tap is refused and **reported** (`reportConnectRequestSuperseded`) rather
  than queued — matching the rule this branch settled for the parked-connect slot. This matters
  because the teardown window can last seconds while the state is still `BLACKHOLED`, so the
  affordance keeps rendering and a re-tap is the natural user response; a second `stop()` landing
  after the first flow's start would kill the session the user just asked for. A `generation` counter
  stops a cancelled job's cleanup clearing a slot its successor already owns.

`reconnectingProfileId` publishes the **target id**, not a flag, for the label/enablement split
described above. `VpnViewModel.cancelReconnect` is deliberately NOT called from `disconnect()` —
that method is the flow's own first step, so cancelling there would make every reconnect cancel
itself and silently degrade Reconnect into Disconnect.

**Every in-app teardown that is NOT the flow's own step goes through one method**,
`VpnViewModel.disconnectAbandoningReconnect` (cancel, then stop). There are two such callers — the
in-app Disconnect button and `deleteSubscription` — and writing the pair out by hand is what let the
second one ship without the cancel: the half-written version still compiles and still stops the VPN,
and the only symptom is the abandoned flow reading the resulting `DISCONNECTED` as its own settle and
starting the VPN back up.

`deleteSubscription` additionally has to decide *whether* to tear down, and the active profile id is
not enough to decide it. `ReconnectFlow`'s first step clears `ActiveProfileRepository`, so for the
whole settle window there is **no active profile**, and an active-id-only check waved the delete
through — the CASCADE then removed the rows and the flow's `start()` hit `startVpn`'s "Profile not
found" → `ERROR`. `state/SubscriptionDeleteDecision.shouldStopSessionForDeletedSubscription` takes
both the active profile's subscription **and** the reconnect target's, and is scoped per
subscription so a reconnect to an unrelated server is never cancelled as a side effect.

**External Stop vs this flow's own stop.** Tile and notification Stops set `EXTRA_USER_INITIATED_STOP`
and bump `LogRepository.userStopGeneration`; the in-flight sequence abandons without starting. The
flow's own stop does not set the extra. The remaining recorded limit is `viewModelScope` lifetime
(Test 22) — see [Known limitations](#known-limitations-and-accepted-trade-offs).

**The abort wins every tie, by construction.** Both awaits watch two **StateFlows**, and a StateFlow
replays its current value the instant collection starts. When the external Stop *and* the
`DISCONNECTED` it causes both land in the window between the generation snapshot and the await arming
— the window in which only `stop()` itself runs — the merge sees two terminal values at once, and
whichever branch is dispatched first would decide whether Off becomes On. So the `connectionState`
branch re-reads the generation itself instead of racing a sibling flow for it; the generation branch
stays for the ordinary case, where the bump arrives while the flow is already suspended. Pinned by
`aUserStopAlreadyVisibleWhenTheSettleAwaitArmsStillAbandons` — which fails, deterministically, if the
re-read is removed.

## "Disconnect now, stop if the re-arm fails"

An `UNPROTECTED` give-up used to leave a long-lived `ERROR` with the service **running** and no working
stop control on any surface, while its own copy instructed the user to "turn the VPN off and on again".
The ruling that closed it has two halves, and a third mechanism that had to be *created* rather than
preserved:

1. **Disconnect works immediately.** `MainActivity`'s Disconnect gate includes `ERROR`, and the ongoing
   FGS notification (1101) carries a **Stop action** — the one surface present in every running state,
   including when the app UI is closed.
2. **`retryByRotation`: the re-arm actually retries.** This is the subtle part.
   `scheduleFailoverRearmLocked` normally re-arms the **health monitor** after `rotationWindowMs`. Out
   of `UNPROTECTED` **that can never produce a rotation**: there is no tunnel, so the probe travels the
   **clear network**, succeeds for the wrong reason, fires `onHealthy`, and
   `clearGiveUpStateOnRecovery` early-returns on `tunInterface == null`; `onUnhealthy` can never fire
   because probes never fail. The recovery was therefore *nonexistent*, not merely fragile. The
   `retryByRotation` flag makes the timer call `rotateTunnel` **directly** for `UNPROTECTED` only; both
   contained outcomes keep re-arming the monitor as before, because they still have a tunnel for a
   probe to test.
3. **A second uncontained give-up stops the service.** `shouldStopServiceOnGiveUp(outcome,
   unprotectedRetryConsumed)` — only `UNPROTECTED`, and only once its single automatic recovery attempt
   has been spent. The first `UNPROTECTED` must **not** stop: forfeiting the re-arm would switch the
   VPN off without the user asking. It emits on **both** surfaces (`emitError` for a user who is
   looking, the 1102 error notification — its own id, survives `stopForeground` — for one who is not),
   then `stopVpn`. An honest off state beats a service that is running, protecting nothing, and telling
   the user to reconnect.

### The retry is spent by an ATTEMPT, and a retry that cannot be attempted is re-armed

`unprotectedRetryConsumed` is written **inside `rotateTunnel`'s reservation**, once
`canReserveRotation` has actually admitted the transition — not at schedule time in
`giveUpRotationLocked`. Marking it at schedule time broke the whole bound in one entirely reachable
way: a kill-switch pause landing before the timer fired left `rotateTunnel` bailing at
`canReserveRotation`, so **no second give-up ever happened**, so `shouldStopServiceOnGiveUp` could
never fire — and the foreground service went on running with **no TUN** until the user intervened.

The timer therefore no longer dispatches `rotateTunnel` blindly. `unprotectedRetryAction(tunnelState,
unprotectedSinceMs, now, rotationWindowMs)` (pure, `now` passed in) decides at the firing point:

| Arm | When | What it does |
|---|---|---|
| `ATTEMPT` | `sessionTunnelState == CONNECTED` — exactly `canReserveRotation`'s requirement, so the only state in which a dispatched rotation can do anything | `rotateTunnel(..., unprotectedRecoverySinceMs)`, which spends the retry as it reserves |
| `DEFER` | any other state (another owner mid-transition, or the kill-switch's deliberate `PAUSED`) while the episode is inside its deadline | re-arms the timer with the **same** `unprotectedSinceMs`, spending nothing |
| `STOP_SERVICE` | the episode has outlasted `UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS` (**3**) rotation windows without once becoming rotatable | `emitError` + the 1102 notification + `stopVpn` — the same honest off state the second give-up produces |

Being able to act beats the deadline: an over-deadline session that has become `CONNECTED` still
attempts, because the attempt is itself a terminating path. The deadline is expressed in rotation
windows rather than as a flat duration so a user who chose hour-long windows is never stopped before
their first window has even elapsed.

`rotateTunnel` closes the same hole from its own side. The gap between the timer's decision and the
rotation reserving the transition sits on `tunnelOpScope`, where a `killTunnel` can already be queued
ahead of it — so a recovery rotation that finds it cannot reserve **re-arms with the original
`unprotectedSinceMs`** instead of vanishing. The deadline keeps running from the original give-up, so
that loop terminates even if the session never becomes rotatable again.

`unprotectedRetryConsumed` is cleared exactly where `giveUpOutcome` is: a successful rotation, a
successful revive, the recovery callback, full teardown — i.e. the events where *a tunnel demonstrably
worked* — and the **contained** half of the disable release below. **One carry-over case is known and
ACCEPTED**, because it errs towards stopping a service that cannot protect anything: a retry whose
give-up classifies `CONTAINED_BY_BLACKHOLE` leaves the flag set (traffic is contained, so nothing
clears it), so a later `UNPROTECTED` stops immediately with no retry of its own.

### Disabling failover releases a give-up — but never the uncontained one

`shouldReleaseGiveUpOnDisable(enabled, giveUpOutcome)` is **three-valued, not a null check**, and the
exclusion is the whole reason it exists.

Disabling cancels `failoverRearmJob`, which is the only automatic way out of a **contained** give-up.
So for `CONTAINED_BY_BLACKHOLE` / `CONTAINED_BY_LIVE_TUNNEL` the disable branch drops `giveUpOutcome`
and `unprotectedRetryConsumed`, cancels the 1105 alert, and emits the per-outcome message
(`vpn_failover_disabled_while_blackholed` / `..._while_degraded` — never one shared message). The TUN
stays up and the state stays `BLACKHOLED` on purpose: tearing the TUN down would drop the user onto
the clear network as a side effect of a settings change, and `ERROR` would offer a plain Connect that
`startVpn` refuses with "VPN already running".

`UNPROTECTED` is **excluded**. It owns no TUN and no re-arm to be stranded behind — its recovery is the
`startVpn` restart, and `shouldRestartForRecovery` keys off this exact marker. Releasing it would leave
the service **running with no tunnel on the clear network**, every Connect surface taking the
"VPN already running" early return, no monitor left to produce a second give-up (so
`shouldStopServiceOnGiveUp` could never fire), 1105 retracted, and only Disconnect working. The marker
and its alert therefore both survive the disable, and `unprotectedRetryConsumed` is left alone with it
— it records that the single automatic recovery was spent, which stays true.

Two things happen on disable **regardless** of the outcome, both outside the release branch:
`failoverRearmJob` is cancelled and nulled, and `rotationAttempts` is emptied. The second is not
cosmetic: `scheduleFailoverRearmLocked` clears the sliding thrash window when its timer *fires*, so
cancelling that timer removes the owner of the reset. Whoever disables must perform it instead, or a
stale window could only ever **deny** the first automatic rotation of the next episode — a spurious
give-up charged to attempts made before the user intervened.

**The retry timer is gated twice.** `applyFailoverPreferences` cancels and nulls `failoverRearmJob`
when the user disables failover — gated on `!settings.enabled` **alone**, deliberately *not* on
`shouldRunFailoverMonitor`, which is also false in `PAUSED`/`ROTATING`: folding the cancel into it
would drop a legitimate pending retry on an unrelated settings save during a kill-switch pause. The
backstop is `shouldFireFailoverRetry(failoverEnabled, isCurrentSession)`, re-read from the flow at the
firing point, because the timer is scheduled under one set of preferences and fires up to an hour
later. Without both, a user who turned auto-failover **off** could still be handed an automatic server
rotation — and, on the unprotected retry path, an automatic VPN shutdown.

**The cancel stays unconditional even for `UNPROTECTED`, where this timer is now the only thing that
can end a service owning no TUN.** That is a deliberate, separate answer from the one the *firing*
path gets above, and the two must not be generalised into one rule. The firing path fixes a bound the
user never asked to lose; the disable path is the user explicitly asking this feature to stop acting,
and "no automatic VPN shutdown after a disable" is the rule `shouldFireFailoverRetry` already exists
to enforce — so keeping the job alive here would only let it fire, stand down and leave the same state
behind. What survives instead is everything the user needs to act themselves: the `UNPROTECTED` marker
and its 1105 alert are excluded from the release (`shouldReleaseGiveUpOnDisable`), Connect works
through `shouldRestartForRecovery`, and Disconnect works. The residual while disabled — a service
running unprotected until the user touches one of those two controls — is accepted.

**Re-enable restores the UNPROTECTED re-arm.** The disable reasoning above does **not** cover turning
the feature back on: without a restore the health monitor starts (it does not require a TUN), probes
the clear network, never asks for a rotation, and `clearGiveUpStateOnRecovery` refuses to clear
without a TUN — so no automatic recovery or stop would ever occur. `shouldRestoreUnprotectedRearm`
reschedules the rotation retry with the original `unprotectedEpisodeSinceMs` so the deferral deadline
keeps running across the toggle.

### Connect from `UNPROTECTED` restarts the session

`shouldRestartForRecovery(running, giveUpOutcome)` is deliberately narrow: **only** `UNPROTECTED`
bypasses `startVpn`'s "VPN already running" early return. Everything else keeps the early return,
because the tile, `START_REDELIVER_INTENT` crash recovery and stray/duplicated intents all depend on
"start while running" being idempotent — a general restart would let a stray intent bounce a perfectly
healthy tunnel.

**The two contained outcomes are excluded for DIFFERENT reasons, and stating one reason for both is
forbidden** — the shared, weaker one is exactly the rationale a maintainer would feel safe widening
on, and widening this predicate deadlocks a VPN service on the main thread:

- `CONTAINED_BY_LIVE_TUNNEL` holds a **running Xray core**, so admitting it turns the main-thread
  `stopVpn` below into a real `instance.Close()`. **That is RISK 1**, and it is the reason the
  companion rule — never add anything that awaits to `stopVpn` — has to hold too.
- `CONTAINED_BY_BLACKHOLE` holds **no running core**: it is classified with
  `TunInterfaceKind.NONE` (or already-`UNREAD_CONTAINMENT` on a later give-up), i.e. after
  `tearDownTunnelLocked()` already called `stopXray()`, and the blackhole builder starts
  none. RISK 1 does not apply to it. It is excluded because it **still holds a TUN, so there is
  nothing for a restart to rescue**, plus the general idempotence reason above.

Both reach a live tunnel again through [`ReconnectFlow`](#reconnectflow--stop-settle-start-verify),
which stops via `ACTION_STOP` — already marshalled onto `tunnelOpScope`, so nothing blocking lands on
the main thread.

The restart calls `stopVpn(stopService = false)` and **falls through** to the normal start path for a
fresh epoch. `stopService = false` keeps this service instance alive: a real `stopSelf()` there would
schedule our own destruction and `onDestroy` would tear down the session we are about to start, and
skipping `stopForeground` keeps the FGS promotion continuous across the restart instead of dropping and
re-taking it.

> ### ⚠️ RISK 1 — a documented constraint on `stopVpn`
>
> That `stopVpn` call runs **on the main thread**, inside the held admission block of `onStartCommand`.
> It is safe today for two reasons and **only** those two:
>
> 1. **`UNPROTECTED` implies a stopped core.** `StopXray` returns immediately when `instance == nil`
>    (`xray_bridge.go`), and Go's `mu` is taken only by `StartXray`/`StopXray`, both called exclusively
>    under the Kotlin `lock` — so the Go mutex adds no contention beyond the lock the caller already
>    holds.
> 2. **Nothing in `stopVpn` awaits.** Dispatching the teardown to `tunnelOpScope` and awaiting it would
>    **deadlock** (those ops take `lock` themselves, and `startVpn` is not suspendable), so "marshal
>    only the teardown" is unavailable without restructuring `onStartCommand`.
>
> **Therefore: never add anything that awaits to `stopVpn`, and never widen `shouldRestartForRecovery`
> to a state that can imply a running core.** Either change turns a fast no-op into a real
> `instance.Close()` + fd close on the main thread. (The pre-existing `synchronized(lock)` in
> `onStartCommand` *already* blocks the main thread for a full rotation's `bringUpTunnel`, so this is
> an increment on an existing risk, not a new class of it — but the constraint is what keeps it an
> increment.)

## Recovery: clearing a stale give-up

`clearGiveUpStateOnRecovery` is driven by `TunnelHealthMonitor.onHealthy`. Without it, the
no-candidate and thrash-cap give-ups — which can both land on a tunnel merely having a bad minute —
would leave the user staring at an error state over a **working** connection until they stopped and
restarted the VPN by hand, because the monitor only ever reported *failure*.

Its guard set is complete and each clause is load-bearing:

- current session (`isCurrentSessionLocked`),
- a give-up is actually showing (`giveUpOutcome != null`),
- `sessionTunnelState == CONNECTED`,
- **`tunInterface != null`** — after an `UNPROTECTED` give-up there is no tunnel, so the probe travels
  the clear network and succeeds for the wrong reason. Clearing on that would announce `CONNECTED` with
  no VPN at all. `UNPROTECTED` has no automatic *state* recovery by design; it needs the user action the
  notification and the in-app error both ask for.

**It also empties `rotationAttempts`, and that has a known consequence after a THRASH-CAP give-up:**
the session gets a full rotation budget back *inside* the window the cap was supposed to bound. The
cap is a rate limit, and a flapping tunnel produces exactly the healthy probe that resets it — so one
success is weaker evidence here than after a no-candidate give-up, where the pool really was exhausted.
It is **accepted, not overlooked**, for three reasons: reaching it early takes a **user action** (a
give-up stops the monitor, and the only thing that restarts it before the re-arm timer — which clears
the window itself — is a failover-settings save, the same "explicit try again" reading the disable
path's own unconditional reset already gets); an exception would need a second marker recording *which*
give-up reason produced the state, i.e. the extra disarm site whose coupling produced the
running-but-unconnectable defect on the disable path; and the cost is `maxRotations` extra rotations in
one window, each **bridged** and each announced — churn, never exposure.

The `CONTAINED_BY_BLACKHOLE` case is closed **structurally**, not by a guard: `SplitTunnelPlanner` puts
this app inside the tunnel in both modes and `Http204HealthProbe` uses an unprotected
`HttpURLConnection`, so a probe through an unread fd cannot succeed.

## Live settings, and why the monitor is rebuilt rather than mutated

`applyFailoverPreferences` is the single reconciliation point, mirroring `applyKillSwitchPreferences`
including its stale-epoch discipline (a superseded epoch returns **without touching the monitor**, so a
late emission from an already-cancelled observer can never stop the current session's monitor).

`TunnelHealthMonitor`/`Http204HealthProbe` bake interval, timeout and threshold in at **construction**,
so a timing edit can only land by rebuilding. `failoverMonitorNeedsRebuild(builtFrom, next)` decides:
only those three fields qualify (`enabled` is handled by `shouldRunFailoverMonitor`; `maxRotations` and
`rotationWindowMs` are read fresh at rotation time). **An unchanged emission MUST return false** — the
settings `StateFlow` re-emits on every save, and rebuilding on each one would restart the poll cycle
continuously so the tunnel is never actually observed.

### One question, one answer: `authoritativeFailoverSettings()`

**Every failover-settings read that DECIDES something goes through
`SessionLifecycleDecision.authoritativeFailoverSettings()`, which returns `FailoverPreferences.state.value`.**
`save()` and `load()` publish into that process-global flow **synchronously**; the service also
collects it, but that collector runs **asynchronously** on `serviceScope`, so anything it cached could
be a whole tuple behind the value the user had just saved.

That asymmetry is why the reservation seam
(`canReserveRotationFromAuthoritativeState`) was introduced — but only the *enable veto* used it. The
thrash cap (`maxRotations` / `rotationWindowMs`), the rotation-success `applyFailoverPreferences`, the
re-arm delay and the `UNPROTECTED` stop deadline all read a session cache field, so one question had
two answers in one file. The consequence was watchdog **timing**, never exposure — but the last of
those reads decides whether the service **stops itself**, and the revive path already called
`applyFailoverPreferences` with the flow while the rotation path next to it passed the cache. The
cache field was therefore **deleted**, not synchronised: it had no read left that could justify it.

Two things this deliberately does **not** change:

- **`failoverMonitorSettings` stays a cache, and must.** It is a different question — what the live
  monitor was *constructed from* — and `failoverMonitorNeedsRebuild` needs exactly that record of the
  past to tell a real timing edit from the settings flow's no-op re-emission.
- **`applyFailoverPreferences` keeps its `settings` parameter.** The collector's emission is the
  natural input to a reconciliation, and the seeded `FailoverPreferences.load(...)` below is what
  makes the parameter and the flow agree on a process-fresh connect.

The thrash cap reads the tuple **once** into a local at the reservation, rather than reading each
field separately: both describe the same sliding window, and two reads could mix a new `maxRotations`
with an old `rotationWindowMs`.

The `failoverMonitor != null` early return is what stops the observer stacking duplicate monitors. The
fix for a stale post-rotation monitor is to **clear the field** (`stopFailoverMonitorLocked()` inside
`rotateTunnel`'s reservation), **never** to drop that guard: a fired monitor is already terminal but
the field still holds it, so without the clear the post-rotation re-apply would early-return on a
non-null field and failover would arm exactly **once per session**.

**The seeded `FailoverPreferences.load(...)` in `startVpn` is load-bearing, not defensive.**
`FailoverPreferences.state` is a process-global `MutableStateFlow(DEFAULT)`; an observer-only wiring
would receive `enabled = false` and never arm failover on any path where the settings Activity never
ran in this process — process death, always-on restart, a first-launch QS-tile connect. Both that load
and `KillSwitchRepository.load` touch SharedPreferences and so stay **outside** the lifecycle lock.

## Settings, defaults, and the `timeout < interval` invariant

Reached from Settings → **Auto-failover** (`settings/SettingsHubActivity` → `failover_title`), the
screen is [`failover/FailoverSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverSettingsActivity.kt).
It follows the hub's **per-control autosave** house style (see [`settings-hub.md`](settings-hub.md)) —
no Save button, each control persists on change, and an invalid field holds its own last-good
**persisted** value without vetoing the rest of the tuple.

| Setting | Default | Bounds | Notes |
|---|---|---|---|
| `enabled` | **off** | — | The only field that takes effect mid-session without a rebuild |
| `probeIntervalMs` | 15 000 | 5 000 – 300 000 | |
| `probeTimeoutMs` | 5 000 | 1 000 – (interval − 1 000) | The cross-field rule; see below |
| `failureThreshold` | 2 | 1 – 10 | Consecutive failures before rotating |
| `maxRotations` | 3 | 1 – 10 | Sliding-window thrash cap |
| `rotationWindowMs` | 600 000 | 60 000 – 3 600 000 | **No UI control** — carried through from the stored tuple. Also the re-arm delay |

The probe **target URL** is not a failover setting and is **not user-editable at all**: it is the fixed
`ConfigBuilder.HEALTH_PROBE_TARGET_URL`. It used to read `PingPreferences.targetUrl`, and that sharing
was wrong twice over — a static routing carve-out cannot cover an editable target, and
the Ping Test target is validated only as an `http://` prefix, so any non-204 URL made every probe fail
forever. See ["Reaching the tun is not enough"](#reaching-the-tun-is-not-enough--the-routing-table-has-to-cooperate)
above. The Ping Test feature keeps its own editable target; the two are now independent.

### The cross-field rule, and the lesson it taught

`FailoverPreferences.coerce` clamps every field into bounds and **then** enforces
`probeTimeout ≤ interval − TIMEOUT_HEADROOM_MS` (floored at `TIMEOUT_MIN`). The pair rule runs **last**
so it sees already-clamped values and cannot be undone by a later clamp.

The UI must derive the ceiling **exactly the way `coerce()` derives it**, not restate it. It briefly
did not — the accept test was `timeout < interval`, so at interval 10 000 a typed 9 500 showed **no
error**, was accepted, and was then silently rewritten to 9 000 by `save()`. A ~999 ms silent-rewrite
window at every interval value, while the screen's own error string already stated the correct rule.
Both `resolveFailoverSettings` and the activity's display validity now compute
`(i − TIMEOUT_HEADROOM_MS).coerceAtLeast(TIMEOUT_MIN)` from the **effective** (post-fallback) interval,
so there is one rule rather than two, and `coerce()` remains the final backstop rather than the
enforcement point.

**The fix exposed that an existing unit test had encoded the bug** — it asserted 19 500 accepted at
fallback interval 20 000, whose real ceiling is 19 000. That is the argument, in one line, for
*deriving* shared bounds instead of restating them: ten tests written against a restated rule described
the defect instead of catching it.

Display validity falls back to `lastPersisted` (re-read via `FailoverPreferences.load` **after** each
save, so it reflects the post-`coerce` stored value), not the screen-open-time `initial`, so what the
screen shows and what `persist()` writes share one source of truth.

## Connect to fastest

The manual counterpart: long-press a server → **Connect to fastest** probes that profile's pool through
the existing, **unmodified** `PingCoordinator` and connects to whichever answered fastest. The menu/UI
half is documented in [`profile-actions-menu.md`](profile-actions-menu.md); the parts that belong here:

- **Same pool as auto-failover.** `FastestConnectRunner.resolvePool` is backed by
  `FailoverPoolResolver.resolve` — not a separately-derived view of the on-screen group. An earlier
  revision had a second copy of the rule in `VpnViewModel`; when curated pools land, that copy would
  have let auto-failover rotate within the curated pool while Connect-to-fastest kept probing the whole
  subscription, diverging with **no compile-time signal**. It was deleted, not synchronised.
- **An equivalent request coalesces.** While a Connect-to-fastest run is active, a second request for
  the same `FailoverPoolResolver` partition leaves that run in place instead of cancelling and
  re-probing. Its uninterruptible JNI calls can still own `PingCoordinator` native slots after a
  cancelled waiter unwinds; restarting the same pool at that instant would turn every replacement
  probe into prompt `N/A`. An explicit Cancel and a request for a different partition retain the
  replacement/cancellation behavior. `FailoverPoolResolver.samePool` lives beside `resolve` so a
  future curated-pool rule updates both decisions together.
- **The winner is re-gated TWICE — production side and consumption side — and both are needed.** The
  run can last minutes (`timeout × ceil(n / concurrency)`), and the winner can then sit **unconsumed
  indefinitely** because the Compose frame clock pauses below `STARTED`.
  - *Production-side* (`FastestConnectRunner`): its `canConnect` **constructor closure** — wired by
    `VpnViewModel` to `connectAction(...) != UNAVAILABLE`, so `BLACKHOLED` still delivers — is
    evaluated immediately before `_winnerId` is set, so it cannot be a stale captured value by
    construction. This bounds the probe's own window.
  - *Consumption-side* (`MainActivity`'s `LaunchedEffect(fastestWinnerId)`): re-checks
    `connectAction(state) != ConnectAction.UNAVAILABLE`
    against the live collected state right before `onConnect(winnerId)`; on failure it calls
    `discardFastestWinner()` (consume **and** report `STATE_CHANGED` — never silent). Both branches
    consume, so a winner can never re-fire.
  - Without the second check: start a run, background the app, connect via the QS tile, return →
    `onConnect` fires, `connect()` no-ops with "VPN already running" while `ActiveProfileRepository`'s
    active id is overwritten to the winner, and **the UI names server B while traffic flows through
    server A**. Two checks around one unbounded gap are correct, not redundant.
- **The service now rolls back too (T10b).** `activeProfileIdToRestoreOnRefusedStart(requested, current)`
  restores the session's real profile in `startVpn`'s "VPN already running" arm, closing
  "UI names B while traffic flows through A" for every connect path that reaches that refusal — not
  just this one. It is **not** literally total, so do not read it as a proof. It can only restore what
  `currentProfileId` already holds, and that field is written on the bring-up thread *after* the
  session's Room read — so a refusal landing between `running = true` and that write still reads
  `-1L`, and `activeProfileIdToRestoreOnRefusedStart` correctly writes nothing (restoring a
  non-positive id would point the app at nothing). Unreachable in practice: `CONNECTING` is announced
  almost immediately and gates every UI affordance, so a second start for a *different* profile has to
  be a duplicated or queued intent that lands inside that window. The
  `UNPROTECTED` recovery restart deliberately does **not** roll back — it really does go on to start the
  requested profile, so it must keep the caller's write. `setActiveProfileId` uses `apply()`, so no disk
  I/O is held under `lock`.
- **The ViewModel never calls `connect()` itself.** Every other Connect action routes through
  `MainActivity`'s permission-checked flow (notification permission, then `VpnService.prepare()`
  consent). Surfacing the winner as ViewModel state and letting Compose consume it through the same
  `onConnect` keeps that invariant and survives an Activity recreation mid-probe.
- **The shared entry point refuses to overwrite a parked choice.** `MainActivity`'s `onConnect`
  lambda is the single choke point for a manual per-server tap **and** for connect-to-fastest's
  winner delivery, and it is also where the `RECONNECT` branch lives (one mechanism, one home). A
  system permission dialog can park a request in the single `pendingProfileId` slot for minutes, and
  that dialog is a modal *continuation* of the request that raised it — so
  `vpn/SessionLifecycleDecision.shouldOverwritePendingConnect(pending, incoming)` admits an incoming
  request only when the slot is empty (`-1L`) or already names the same profile. **Whichever request
  parked first wins**, and the loser is reported via `reportConnectRequestSuperseded()` rather than
  dropped silently.
  - **The QS-tile hand-off is guarded by the same call.** `maybeAutoConnectFromTile` writes the same
    slot, so it goes through `shouldOverwritePendingConnect` too — one uniform rule across all
    **three** writers rather than a per-source hierarchy, because no refusal occurs on an overwrite,
    so nothing downstream would ever notice the substitution and the user could neither observe nor
    predict an asymmetry. The tile's extras are stripped before the check, so the hand-off is
    consumed either way; a refusal is reported, not swallowed.
  - **The `RECONNECT` branch releases the slot** (`pendingProfileId = -1L`) before dispatching. It
    needs no permission prompt — a session is already running, so consent was granted — so it owns
    the request outright; a `POST_NOTIFICATIONS` dialog opened earlier and answered later would
    otherwise connect a second, different profile out from under the reconnect.

## Components

| File | Responsibility |
|---|---|
| [`failover/FailoverPreferences.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPreferences.kt) | `FailoverSettings` model + `xray_prefs` persistence; `coerce` (bounds, then the `timeout < interval` pair rule); process-wide `state: StateFlow` mirroring `KillSwitchRepository.state`. |
| [`failover/HealthProbe.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/HealthProbe.kt) | One-call seam: `suspend fun isHealthy(): Boolean`. |
| [`failover/Http204HealthProbe.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/Http204HealthProbe.kt) | The concrete probe — plain `HttpURLConnection` GET requiring 204, through the tun. Injectable `opener` for tests. |
| [`failover/NetworkAvailability.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/NetworkAvailability.kt) | "Is there a non-VPN internet transport?" — the offline guard. `AndroidNetworkAvailability` is defensive (fail-open on a missing service or any throw). |
| [`failover/TunnelHealthMonitor.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/TunnelHealthMonitor.kt) | The poll loop: offline guard, consecutive-failure counting, terminal-after-fire, once-per-start `onHealthy`, pause/resume for screen off/on. |
| [`failover/FailoverDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverDecision.kt) | Pure: `nextCandidate(pool, currentId, recentlyFailed)` and `admitRotation(attempts, now, maxRotations, windowMs)` → `RotationAdmission`. No clock, no Android. |
| [`failover/FailoverPoolResolver.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPoolResolver.kt) | `resolve(dao, current)` — manual partition or the profile's subscription. **Spec 2 seam** for curated pools; shared with Connect-to-fastest. |
| [`failover/FailoverSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverSettingsActivity.kt) | The settings screen; per-control autosave, display validity derived the same way `coerce()` derives it. `FAILOVER_ENABLED_SWITCH_TAG` for the instrumented test. |
| [`failover/FailoverSettingsPersistDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverSettingsPersistDecision.kt) | Pure `resolveFailoverSettings(...)` — the autosave rule extracted out of the Activity so it is JVM-testable (the codebase's `TileClickDecision`/`StartCommandDecision` shape). |
| [`failover/FastestConnectRunner.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestConnectRunner.kt) | Framework-free Connect-to-fastest orchestration: equivalent-pool coalescing, generation-counter replacement for different pools, delivery-time re-gate, `FastestConnectOutcome` (NO_RESPONSE / BUSY / STATE_CHANGED), cancellation cleanup. |
| [`failover/FastestPick.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/failover/FastestPick.kt) | Pure `pickFastest(states, candidates)` and `clearStaleTesting(states, ids)`. |
| [`vpn/SessionLifecycleDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt) | All the pure service-side rules: `SessionTunnelState.ROTATING`, `canReserveRotation`, and the stateful `canReserveRotationFromAuthoritativeState` admission seam (reads the live `FailoverPreferences.state` before delegating), `shouldDeferKillDuringTransition`, `shouldHoldScreenReceiver`, `shouldRunFailoverMonitor`, `failoverMonitorNeedsRebuild`, **`shouldEstablishRotationBridge`**, **`GiveUpContainment` + `containmentForGiveUp`** (replaced `shouldEstablishBlackholeTunnel`), `FailoverGiveUpOutcome` + `classifyGiveUpOutcome`, **`connectionStateForGiveUp`** (outcome → `BLACKHOLED`/`ERROR`, the one place that mapping lives), `shouldStopServiceOnGiveUp`, `shouldFireFailoverRetry`, `shouldRestartForRecovery`, `activeProfileIdToRestoreOnRefusedStart`, `deferredKillNoticeLabel`, **`deferredKillToWithdraw`**, **`shouldReleaseGiveUpOnDisable`**, **`shouldOverwritePendingConnect`**, **`TunInterfaceKind`** (what the held fd *is* — `NONE`/`LIVE_PROXY`/`UNREAD_CONTAINMENT` — so a second give-up over an unread fd cannot be classified as a live tunnel), **`shouldAbortRotationForMissingBridge`**, **`shouldFunnelRotationReservationRefusal`**, **`shouldRestoreUnprotectedRearm`**, `unprotectedRetryAction`, `giveUpOngoingLine`. |
| [`state/ConnectAction`](../../app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt) (in `VpnViewModel.kt`) | The connect gate: `ConnectAction` + `connectAction`/`connectLabelRes`/`connectEnabled`. Replaces the former boolean `canConnect`. |
| [`state/ReconnectFlow.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/ReconnectFlow.kt) | Framework-free stop→settle→start→verify sequencing for Reconnect: `STOP_TIMEOUT_MS`, `START_VERIFY_MS`, the first-wins in-flight guard (`reconnectingProfileId`), `generation` cleanup, `cancel()`. Canonical home for **why** Reconnect is not a `shouldRestartForRecovery` widening. |
| [`state/SubscriptionDeleteDecision.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/state/SubscriptionDeleteDecision.kt) | Pure `shouldStopSessionForDeletedSubscription(deleted, activeProfileSub, reconnectTargetSub)` — a subscription delete must examine the **reconnect target** as well as the active profile, because the flow's own `disconnect()` has already cleared the active id for the whole settle window. |
| [`vpn/XrayVpnService.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt) | The wiring: `applyFailoverPreferences`, `rotateTunnel`, `giveUpRotationLocked`, the `rotationBridgeInterface` field and its four operations (`establishUnreadTunnelLocked` — the shared builder — plus `establishBlackholeTunnelLocked` / `establishRotationBridgeLocked` / `adoptRotationBridgeLocked` / `releaseRotationBridgeLocked`), `clearGiveUpStateOnRecovery`, `scheduleFailoverRearmLocked`, `reconcileScreenReceiverLocked`, the 1101 Stop action, and the recovery restart in `startVpn`. |
| [`vpn/VpnNotifications.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/vpn/VpnNotifications.kt) | Channels 4 and 5 and ids 1104/1105; `postFailover`, the three `postFailover*` give-up variants (shared id + `postGiveUp` builder), `cancelFailoverBlackholed`. Also id 1106 / `postKillSwitchNotApplied` — a new id on the kill-switch's **existing** exposure channel — **and its `cancelKillSwitchNotApplied` counterpart** (see below). |
| [`log/LogRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/LogRepository.kt) | `VpnConnectionState.BLACKHOLED`; `giveUpLine` (**which** give-up outcome the session is in — all three, not only the contained pair — the service writes it beside the state and clears it on successful rotation/revive/recovery/teardown, so home and tile never re-derive it from `giveUpOutcome`, which the disable branch clears while keeping the state); `userStopGeneration` + `signalUserStopRequested()` (the source distinction `connectionState` cannot provide). |
| [`log/VpnConnectionLabels.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/log/VpnConnectionLabels.kt) | `GiveUpOngoingLine` (three members — the two contained lines plus `UNPROTECTED`, which rides `ERROR`) and `vpnConnectionStateLabelRes(state, line)` — the single state→string mapping home and tile share. Lives in `log/` rather than `vpn/` so `LogRepository` can publish the line without a `log → vpn` import. |
| `res/values/strings.xml`, `res/values-ru/strings.xml` | All failover strings, both locales (release lint fails on a missing one). |
| `AndroidManifest.xml` | `ACCESS_NETWORK_STATE` (required — `cm.allNetworks` throws `SecurityException` without it), the `FailoverSettingsActivity` entry (`exported="false"`, like every sibling settings screen), and `android:networkSecurityConfig` — **load-bearing**, see the next row. |
| `res/xml/network_security_config.xml` | The one-host cleartext carve-out for `HEALTH_PROBE_HOST`. At `targetSdk = 36` cleartext is denied by default and `HttpURLConnection` is governed by `NetworkSecurityPolicy`, so without this file **every** probe throws and the watchdog answers with a rotation storm over healthy servers. Deliberately scoped to one domain with no `<base-config>`. `HealthProbeSchemeTest` couples file, manifest attribute and constant; `app/build.gradle.kts` declares both files as unit-test inputs so editing one alone cannot leave the task `UP-TO-DATE`. |

**No R8 change.** Nothing in `failover/` is reached by reflection or JNI; the ping path it borrows is
already covered by the existing `-keep class xraybridge.**`. `assembleRelease` is green with no R8
warning about the package. That proves the pipeline builds — it does **not** prove the reflection/JNI
bridge paths survive obfuscation; see the QA matrix.

## Known limitations and accepted trade-offs

Everything here was found, reasoned about, and **deliberately kept**. Please read before "fixing" one.

- **`cm.allNetworks` is deprecated and is used anyway.** The replacement, `activeNetwork`, returns the
  **VPN's own network** while the tunnel is up — precisely the transport this check must exclude.
  Switching to it would silently *invert* the guard. The deprecation surfaces as a kotlinc warning
  (also in the release compile) and is left unsuppressed on purpose so it stays visible. **Do not
  "fix" it.**
- **The probe timeout does not bound a hung DNS.** `withTimeoutOrNull` wraps a *blocking* `runProbe()`,
  so the timeout only lands after the blocking call returns. Not a leak — the poll loop is sequential,
  ticks cannot overlap — detection is just slower. Accepted for v1. `PingCoordinator.probeWithBackstop`
  is the in-repo pattern if this ever needs real bounding.
- **`TunnelHealthMonitor` has no constructor validation.** `failureThreshold <= 0` fires on the first
  failure (`>=` comparison); `intervalMs <= 0` hot-spins the loop. Both are unreachable through
  `FailoverPreferences.coerce`, which is the only production source.
- **`giveUpOutcome` survives into `PAUSED`.** No surface can dispatch a start from `PAUSED` today
  (`connectAction(PAUSED) == UNAVAILABLE`, `decideTileClick(PAUSED) == Stop`), but **any future start
  affordance in `PAUSED` would bring a tunnel up under a kill-switch pause** via the recovery-restart
  path. If you add one, clear `giveUpOutcome` in `killTunnel` first. Keeping it is a deliberate
  trade — see ["1103 and 1105 must never coexist"](#1103-and-1105-must-never-coexist) — and the one
  visible residue is that disabling failover *during* a kill-switch pause emits the contained-give-up
  message, whose "tap Reconnect" refers to a button `PAUSED` does not offer. Cosmetic: it does not
  misstate the protection posture.
- **`rotateTunnel` refuses reservation when failover is disabled.** `canReserveRotation` takes
  `failoverEnabled` (same veto shape as `shouldFireFailoverRetry` at the timer). A monitor callback or
  mid-episode recursive dispatch already queued on `tunnelOpScope` when the user disables therefore
  bails without admitting. If the failed candidate's bridge is the only containment TUN, that refusal
  enters give-up and adopts it as `CONTAINED_BY_BLACKHOLE`; otherwise the ordinary cleanup releases
  the between-candidates bridge. A rotation that already holds `ROTATING` is unaffected and still
  exits via release / adopt / give-up. The check is on `enabled`, not on the monitor, so the
  `UNPROTECTED` retry (which legitimately rotates with `failoverMonitor == null`) still works while
  the feature is on.
- **The single automatic recovery rotation excludes the last-known-good server**, because
  `nextCandidate` filters out `currentId` and `currentProfileId` was rolled back to it.
- **`UNPROTECTED` can briefly coexist with a live tunnel and a running core** — `bringUpTunnel` releases
  `lock` after `establish()` + `startXray()`, and the rotation's `.onSuccess` only clears
  `giveUpOutcome` after re-acquiring it. A start intent queued on the main thread could win the lock in
  between. Unreachable from the UI (during a rotation the state is `CONNECTING`, so `connectAction`
  returns `UNAVAILABLE` and the tile returns `Stop`); it needs a duplicated/queued intent. The outcome is still correct
  — epoch discipline no-ops the rotation's `.onSuccess`.
- **The `UNPROTECTED` recovery restart flickers through `DISCONNECTED`.** `startVpn` reaches it via
  `stopVpn(stopService = false)`, which publishes `DISCONNECTED` before the fall-through path
  announces `CONNECTING` — so the UI and the QS tile briefly render Disconnected / `STATE_INACTIVE`
  for a session that is being restarted, not stopped. Cosmetic, and the FGS promotion itself stays
  continuous (`stopService = false` skips `stopForeground`). Suppressing it would mean either a
  "restarting" flag threaded through `stopVpn` or reordering its state write, both of which touch the
  one function that must never grow anything that awaits.
- **`containmentForGiveUp`'s state arm is dead at its only call site** (the stand-down above it already
  returned for every non-`CONNECTED` state), as it was for the `shouldEstablishBlackholeTunnel` it
  replaced. Kept, because it is the rule that stops a future caller adopting a bridge into `PAUSED`;
  its tests for that arm therefore cover an unreachable branch — coverage that looks stronger than it is.
- **The rotation bridge's service-side SEQUENCING is not covered by any test.** `XrayVpnService`
  cannot be instantiated in the JVM suite, so the pure rules
  (`shouldEstablishRotationBridge`, `containmentForGiveUp`, and the classifier composition) are tested
  and mutation-verified, while "each exit really does reach a release or an adoption" rests on the
  enumeration in this document plus a code read. QA Test 23 is the device check.
- **The deferred-kill withdrawal's service-side SEQUENCING is not covered by any test either**, for
  the same reason: the pure rule (`deferredKillToWithdraw`) is tested and mutation-verified, but
  "`reviveTunnel` withdraws before it decides", "the commits observe rather than consume", and above
  all the **FIFO ordering** on `tunnelOpScope` that the fix's correctness rests on are argued from
  the code and this document, not asserted anywhere. QA Test 24 is the device check. Note what would
  silently un-fix it: giving `tunnelOpScope` more than one thread, or making `bringUpTunnel` a
  `suspend` function, both change which coroutine observes what and neither breaks a test.
- **Bridge establishment failure aborts into give-up.** If `establish()` returns null or throws while
  opening the bridge after the live TUN is already torn down, the rotation does **not** proceed through
  the uncovered gap. `shouldAbortRotationForMissingBridge` funnels into `giveUpRotationLocked`, which
  tries blackhole containment (or reports `UNPROTECTED` if that also fails). Logged as
  `Failover: rotation bridge could not be established… aborting the uncovered rebuild into give-up`.
  Deliberately not "retry the next candidate uncovered" — that reopens the clear-network window the
  bridge exists to close.
- **`stopVpn`'s `!shouldStop && tunInterface == null` early return skips `failoverRearmJob?.cancel()`**,
  so an up-to-an-hour timer can idle past it. Harmless: the job re-checks `isCurrentSessionLocked`.
- **The blackhole builder omits `bringUpTunnel`'s "allow-only mode with no selected apps" warning** —
  the one diagnostic difference between the two builders.
- **`FastestConnectRunner`'s BUSY-vs-NO_RESPONSE is a heuristic**, read from a `pingStates` snapshot
  rather than from `PingCoordinator`'s internal state (modifying the coordinator was out of scope). A
  false `NO_RESPONSE` is effectively unreachable; a false `BUSY` is reachable but both messages end in
  "try again", so a wrong label misexplains without misleading into a wrong action.
- **`PingCoordinator`'s cross-run dedup can hand `pickFastest` a stale `Success`** from an earlier run,
  producing a winner chosen from non-fresh data with no message. See [`ping-test.md`](ping-test.md).
- **`clearStaleTesting` can reset a row belonging to an overlapping concurrent run.** Its `ids`
  argument is the whole **resolved pool**, including ids `PingCoordinator.runGroup` deduped and never
  admitted — so cancelling a connect-fastest run can briefly flip a `Testing` row owned by a
  concurrent group ping back to `Idle`. **Self-healing and accepted:** the other run writes its own
  terminal state when it finishes. Narrowing it would mean tracking which ids this run actually
  admitted, i.e. modifying `PingCoordinator`, which was out of scope.
- **A Stop from the QS tile or the ongoing notification abandons a Reconnect in flight.** Those
  surfaces set `EXTRA_USER_INITIATED_STOP` on `ACTION_STOP`; `onStartCommand` bumps
  `LogRepository.userStopGeneration` (no await — RISK-1 safe) before launching `stopVpn`.
  `ReconnectFlow` snapshots the generation before its own stop and aborts without starting if it
  bumps during settle or start-verify. The flow's own stop (and in-app Disconnect after
  `cancelReconnect`) does **not** set the extra, so ordinary reconnect settle is unchanged. Process-
  global StateFlow so a backgrounded `MainActivity` still sees it. QA covers it as Test 21.
- **A reconnect in flight dies if the Activity is *finished*.** `ReconnectFlow.run` is launched on
  `viewModelScope`, so the `finally` clears the guard and nothing restarts the VPN — the user is left
  disconnected. Rotation and backgrounding are **not** affected (the ViewModel survives both); only a
  genuine finish (back-out, swipe from recents, process death) is. Fail-safe and honest — it fails to
  "VPN off", never to a silently unprotected tunnel. QA covers it as Test 22.
> **Note on `failover_hint`.** An earlier draft of this string read "the tunnel is kept up so your
> traffic is never exposed" — true for both *contained* outcomes but **false for `UNPROTECTED`**,
> where `establish()` itself failed. It was rewritten (commit `4091571`) to promise a pause rather
> than a guarantee, and to say the app will tell you when even that is impossible. Keep any future
> edit to this string honest about `UNPROTECTED`: the give-up notifications are per-outcome for
> exactly this reason, and a settings hint that contradicts them is the same defect one screen over.

## Deferred: extract a `FailoverEngine` behind a `TunnelHost` seam

**This is a real recommendation that was deferred to a follow-up spec, not a rejected one.**

`XrayVpnService` is now ~2,640 lines — it was ~1,670 when this recommendation was first written, and
every review round since has added to it rather than to a seam — carrying **three** tunnel
operations (kill, revive, rotate), two
monitors sharing one receiver, and a give-up funnel. Every Critical and Important finding in this
feature's review lived in the **sequencing** — announce-before-teardown, teardown-of-a-half-built-fd,
profile-id rollback, outcome classification, give-up ordering — and none of it is reachable from a JVM
test today: the pure predicates in `SessionLifecycleDecision.kt` guard the *rules*, but the *order in
which the service calls them* is only proven on hardware.

The recommendation is to extract a `vpn/FailoverEngine` behind a narrow `TunnelHost` seam
(`reserve` / `tearDown` / `bringUp` / `establishBlackhole` / `notify`) so that sequencing becomes
testable off-device.

**Why it was deferred, in the maintainer's words:** mixing a large refactor of human-review-gated code
into a *safety fix round* makes both harder to review, and it would have invalidated the review that
had just found those bugs. It is a follow-up spec, and the size of the "Known limitations" list above
is itself the argument for doing it.

## Testing

**JVM unit tests** (`:app:testDebugUnitTest`) — `app/src/test/java/com/justme/xtls_core_proxy/`:

| Test class | Coverage |
|---|---|
| `failover/FailoverPreferencesTest` (8) | Defaults; every bound clamps on load **and** save; the `timeout < interval` pair rule; `load`/`save` I/O against mocked `SharedPreferences` (the `KillSwitchRepositoryTest` precedent), with `save` pinning the **coerced** value per key via `eq()`, not a bare `any()`. |
| `failover/Http204HealthProbeTest` (5) | 204 → healthy; non-204/throwing opener → false, never a throw; `CancellationException` **propagates** rather than being swallowed. |
| `failover/TunnelHealthMonitorTest` (19) | Threshold counting; normal `start()` waits one interval while screen-on `resumePolling()` stays immediate; the offline guard skips the tick **and** resets the counter; a throwing availability check is treated as offline; terminal-after-fire across both pause/resume orderings; a throwing listener does not kill the loop; tick continuation; a fresh `start()` REVIVING a terminal monitor (the other half of the terminal contract, and the ordering the atomic install makes load-bearing); **the no-liveness-gate contract on BOTH listeners, staged by cancelling the poll coroutine from inside the probe** — the only formulation that actually fails when the gate is restored, because `job.getAndSet(null)` clears the reference without cancelling. The recovery half (`theHealthyListenerIsInvokedEvenWhenTheLoopWasAlreadyCancelled`) was missing while the unhealthy half was pinned, so restoring the gate on the healthy listener left the suite green — and that swallow is the worse of the two: `reportedHealthy` is latched before the invocation and survives `pausePolling()`, so `clearGiveUpStateOnRecovery` would never run again for the session. |
| `failover/FailoverDecisionTest` (7) | `nextCandidate` skips the current id and episode failures, and follows **list** order rather than id order (pinned with a backwards-id pool — the only shape that can tell the two apart); `admitRotation` admits under the cap, denies at it, and slides the window. |
| `failover/FailoverEpisodeDecisionTest` (6) | Probe-failed core-start-success hops walk every member before exhaustion, including from a non-first server; a healthy probe clears the episode; bring-up-failure recursion keeps excluding a candidate that never became current; the sliding thrash cap remains unchanged. |
| `failover/FailoverPoolResolverDispatchTest` (8) | Manual profile → `getManualList`, subscription profile → `getBySubscriptionId`, and the four-branch same-pool equivalence rule used by Connect-to-fastest coalescing; a fake DAO records calls and throws `UnsupportedOperationException` on any unexpected method, so a wrong dispatch fails loudly. |
| `failover/FailoverSettingsPersistDecisionTest` (12) | Per-control autosave: an invalid field never vetoes the tuple; the timeout ceiling is derived from the **effective** interval; the exact headroom boundary (9 000 at interval 10 000) is accepted and the coerce-gap value (9 500) is rejected. |
| `failover/FastestPickTest` (4), `failover/ClearStaleTestingTest` (3) | Lowest successful latency wins / nothing succeeded → null; stale-`Testing` reset scoped to the run's own ids. |
| `failover/FastestConnectRunnerTest` (9) | Sequencing against a **real** `PingCoordinator` under `kotlinx-coroutines-test`: different-pool supersede, identical-pool coalescing, and the `UnconfinedTestDispatcher` native-slot regression that keeps a same-pool replacement from painting every row `N/A`; cancel-resets-`Testing`, the delivery-time re-gate discards + reports `STATE_CHANGED`, connectable winner is delivered, `NO_RESPONSE` vs `BUSY`. |
| `vpn/SessionLifecycleDecisionTest` (58) | Every pure service rule above except the kill-deferral guard, including `shouldReleaseGiveUpOnDisable` (both the contained release **and** the `UNPROTECTED` non-release), `shouldOverwritePendingConnect`, and `containmentForGiveUp` — the live tunnel wins over a held bridge, the bridge wins over building a second TUN, the state arm, and `adoptingTheBridgeIsClassifiedAsABlackhole_neverAsALiveTunnel`, which composes containment with `classifyGiveUpOutcome` the way `giveUpRotationLocked` does — but with `hasTunnel` hard-coded, so it pins the composition and **not** the service's read ordering (see the give-up section). |
| `vpn/SessionLifecycleRotationTest` (23) | Rotation reservation; `shouldEstablishRotationBridge` (opened once the rotation tore the tunnel down, never twice, never over a live tunnel, `ROTATING` only); the give-up predicates; `deferredKillNoticeLabel` (names the app while a tunnel remains; silent with nothing deferred and silent with no tunnel left); and the **sole** home of the kill-deferral coverage — `shouldDeferKillDuringTransition` across `{REVIVING, ROTATING}` × current/stale-epoch/stopped, plus the four settled states. The five duplicate cases that used to test the production-dead `shouldDeferKillDuringRevive` were checked one by one against the live function (all still held; none involved `ROTATING`, so none inverted), found already covered here, and deleted with it. Also `theAuthoritativeReadCarriesTheWholeTuple_notJustTheEnabledFlag`, which pins that `authoritativeFailoverSettings()` carries `maxRotations`/`rotationWindowMs` and not only `enabled` — the fields the deleted session cache used to answer. Also the sole home of `deferredKillToWithdraw`: withdraws for the current session, silent with nothing deferred, refused for a stale epoch and a stopped session, `everyStateThatCanDeferAKillCanAlsoWithdrawIt` (whole-enum implication, so a new deferral state cannot open a hole) and `withdrawalIsDeliberatelyWIDERThanDeferral_notAMirrorOfIt`. |
| `vpn/FailoverNotificationIdsTest` (3) | All five ids and three channel ids are **mutually distinct** — the JVM-runnable (therefore CI-runnable) guard against the welded-channel regression. |
| `state/ConnectActionTest` (7) | The connect gate: the full `VpnConnectionState → ConnectAction` map as one table (so a mapping change is one visible diff), `BLACKHOLED` → `RECONNECT`, `ERROR` → `CONNECT`, the label for each action, and `connectEnabled` false for `UNAVAILABLE` and for every action while `isConnecting`. |
| `state/ReconnectFlowTest` (13) | Stop → settle → start ordering; the `STOP_TIMEOUT_MS` expiry dispatches **no** start and reports the timeout; the `START_VERIFY_MS` re-dispatch fires once and only once; a second `run` while one is in flight is refused and reported, not queued; `cancel()` abandons without starting; the guard releases so a later reconnect is admitted (per **`ReconnectFlow` instance** — i.e. per ViewModel, not per process); a `userStopGeneration` bump during settle or start-verify abandons without (re)starting; the flow's own stop does not count as that bump; and — the tie case the others cannot see — a Stop *already visible* when the settle await arms still abandons, which is what stops both replaying StateFlows racing for the answer. |
| `state/SubscriptionDeleteDecisionTest` (4) | The delete-vs-session rule: the active profile's subscription tears down; a reconnect **target**'s subscription tears down **even with no active profile** (the settle-window case an active-id-only check waved through); an unrelated subscription leaves an in-flight reconnect alone; a manual profile is owned by no subscription. |
| `log/VpnConnectionLabelsTest` (5) | The two contained give-up outcomes never share a home/tile string (`assertNotEquals`, so a copy edit that re-merges them fails); the **uncontained** one reads `main_state_unprotected` rather than the generic "Error" and shares neither containment string; an ERROR with **no** recorded line still reads the ordinary "Error", which is the same rule in the other direction; and the `null`/cross-arm lines fall back to their own state's generic label. This is the UI half of the rule that the three outcomes must never share one message. |
| `failover/TryInstallJobTest` (4) | The job install as a decision table — now shared by `start()` and `resumePolling()`: installs into an empty slot while live, rejects an occupied slot, rejects a monitor already stopped, and — the orphan case — **retires itself** when `isStarted` flips false after the CAS. A deterministic stand-in for a multi-thread `resume` × `stop` interleaving, which could not be staged without a flaky test. |
| `tile/TileClickDecisionTest` (16) | `BLACKHOLED` → `Stop`, with and without a profile; `everyLiveStateDecidesStop` pins the whole live set in one place; `everyStateIsClassifiedExplicitly` enumerates **the full enum**, so widening `VpnConnectionState` fails a test rather than silently falling through to `Start`. That last one is the grep sweep, automated. Both guards assert against `shouldStopOnTileClick` as well as `decideTileClick`, so they now cover `XrayVpnTileService.handleClick`'s fast path too (see below). |
| `MainActivityStateTest` (6) | `isActive` — every live state keeps the active row highlighted (incl. `BLACKHOLED`), dead states highlight nothing, another/`null` profile is never highlighted, and the same full-enum classification guard. `isActive` is `internal` (not `private`) purely so this test can reach it; it was **not** moved. Also the **top-bar Disconnect gate** (`shouldShowDisconnect`), extracted so it could be reached at all: it is deliberately **wider** than the tile's Stop gate and than `isActive` because it includes `ERROR` — not a live state, but still disconnectable — so its whole-enum guard pins *that* rule and must not be collapsed into the tile's. |

> **`XrayVpnTileService.handleClick` used to be the one gate without a test — that is now closed.**
> It re-stated `decideTileClick`'s Stop chain by hand for its no-IO fast path, and being an Android
> `TileService` method, no plain-JVM test could call it; `TileClickDecisionTest`'s full-enum guard
> exercised only the *pure* copy and could not see the duplicate. Deleting `BLACKHOLED` from
> `handleClick` left all 648 unit tests green, which is what the comment claiming otherwise was
> hiding. The shared gate has since been extracted: `tile/TileClickDecision.shouldStopOnTileClick`
> takes the **state alone** — deliberately not the `profileId` or the permission flags, so the fast
> path still returns before any `ActiveProfileRepository` lookup — and both `decideTileClick` and
> `handleClick` call it. `handleClick` is still unreachable from a plain-JVM test, but it no longer
> holds a rule of its own to drift, and the whole-enum sweep now asserts directly against the
> function it calls.

**Instrumented tests** (`:app:connectedDebugAndroidTest`, local only — not in CI):

- `failover/FailoverPoolResolverTest` (2) — the real Room `@Query` SQL for both pool shapes.
- `failover/FailoverSettingsPersistTest` (1) — `invalidInterval_doesNotVetoEnableToggle`, i.e. the
  `MuxSettingsActivity` whole-save-veto bug proven **absent**.
- `vpn/FailoverNotificationTest` (6) — including `failoverChannelIsDefaultImportance` and
  `failoverBlackholeChannelIsHighImportance`.

**Status:** all three classes above were previously never executed and now **pass on hardware**
(Samsung SM-S918B / Galaxy S23 Ultra, Android 15, arm64-v8a). Full instrumented suite: **57/57**.

> Note the instrumented notification tests post 1104 and 1105 and never cancel them, and
> `gradle.properties` keeps the app installed after a run — so a hardware run can leave a live
> HIGH-importance "no server connection" alert on the device while traffic is fine. An `@After`
> cancelling both ids would fix it. Also, `cancelFailoverBlackholed_clearsTheAlert` can pass
> **vacuously** (no `GrantPermissionRule`, no assert-posted-before-cancel), so with
> `POST_NOTIFICATIONS` denied the `notify` no-ops and the assertion holds without exercising the
> cancel at all.

**Manual on-device QA:** [`docs/qa/auto-failover-qa.md`](../qa/auto-failover-qa.md). The give-up
posture, the notification/tile controls in each state, the full "disconnect now, stop if the re-arm
fails" lifecycle, and the release-APK bridge check are all hardware-only and live there.
