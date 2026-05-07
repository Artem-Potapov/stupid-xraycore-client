# Subscriptions — Design

## Context

Today the app stores VLESS configs in a single `profiles` Room table, populated only by manually pasting a `vless://` URI or raw Xray JSON into `ServerSettingsActivity`. In practice, most users get configs through *subscriptions*: an HTTP URL whose response body lists many configs at once (newline-separated `vless://` URIs, often base64-encoded; or newline-separated raw Xray JSON objects). Without subscription support, the app is unusable for the common case.

This design adds first-class subscriptions. A user can register a subscription URL once, the app fetches and parses it into individual `Profile` rows, and refreshes them on its own schedule. The user's manually-added profiles continue to work alongside subscription-sourced ones.

## Decisions captured during brainstorming

| # | Decision | Source |
|---|---|---|
| 1 | Subscriptions are a **first-class entity** with their own Room table; `Profile` gets a nullable `subscriptionId` FK. | User answer |
| 2 | Refresh policy is **replace wholesale**, *but* the currently-active profile row is preserved if it belongs to that subscription. | User answer |
| 3 | Refresh interval comes from the **`profile-update-interval`** HTTP response header (integer hours). Default **12h** when missing. The user can override this per subscription. | User answer + web verification |
| 4 | The staleness check fires **on app foreground** (`MainActivity.onStart`). No WorkManager. | User answer |
| 5 | UI is **HAPP-style**: manual profiles render at the top of `MainActivity`, then a collapsible group per subscription below. A "Subscriptions" toolbar action opens the management screen. | User answer |
| 6 | Subscription edit screen has **two tabs** mirroring `ServerSettingsActivity`: Simple = `(name, url, update_interval_hours)`. Advanced = `(user_agent_override, allow_insecure_tls)`. | User answer |

## Architecture overview

```
                 +-----------------------------------+
                 |          MainActivity             |
                 |  - LazyColumn: manual + groups    |
                 |  - onStart -> refreshIfStale()    |
                 +------------------+----------------+
                                    |
                          +---------v----------+
                          |   VpnViewModel     |
                          |   (existing, +)    |
                          +---------+----------+
                                    |
        +---------------------+-----+---+----------------------+
        |                     |         |                      |
+-------v-------+   +---------v---+   +-v---------+    +-------v-------+
|SubscriptionDao|   | ProfileDao  |   |Coordinator|    |Refresh trigger|
| (new)         |   | (existing,+)|   |(new)      |    | (in MainAct.) |
+---------------+   +-------------+   +-+--+------+    +---------------+
                                       |  |
                              +--------+  +---------+
                              |                     |
                       +------v-----+        +------v------+
                       | Fetcher    |        | BodyParser  |
                       | (HTTP GET) |        | (text->cfg) |
                       +------------+        +-------------+
```

New code lives under `com.justme.xtls_core_proxy.subs.*`. Existing files (`Profile.kt`, `ProfileDao.kt`, `AppDatabase.kt`, `VpnViewModel.kt`, `MainActivity.kt`) are touched but not restructured.

## Data model

### `Subscription` entity (new)

```kotlin
@Entity(tableName = "subscriptions")
data class Subscription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val userAgentOverride: String? = null,
    val allowInsecureTls: Boolean = false,
    val userIntervalHours: Int? = null,        // user-set; wins
    val lastSeenIntervalHours: Int? = null,    // from response header
    val lastFetchedAt: Long? = null,           // epoch millis
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
```

Effective interval: `userIntervalHours ?: lastSeenIntervalHours ?: 12`.

### `Profile` entity (modified)

Add a nullable FK to `Subscription`:

```kotlin
@Entity(
    tableName = "profiles",
    foreignKeys = [ForeignKey(
        entity = Subscription::class,
        parentColumns = ["id"],
        childColumns = ["subscriptionId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("subscriptionId")]
)
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val config: String,
    val subscriptionId: Long? = null
)
```

### Schema migration

Bump DB version `1 → 2`. Use **`fallbackToDestructiveMigrationFrom(1)`** rather than a hand-written `Migration`. The project is pre-release (`versionName = "0.7rc1"`, `exportSchema = false`), schema is unstable, and writing a verified `ALTER TABLE` migration is high-cost / low-value at this stage. Testers losing manual profiles on the v0.8 jump is acceptable damage.

## Components

### `SubscriptionFetcher` (new)

Single suspend function:

```kotlin
sealed class FetchResult {
    data class Success(val body: String, val intervalHoursFromHeader: Int?) : FetchResult()
    data class Failure(val message: String) : FetchResult()
}

suspend fun fetch(sub: Subscription, defaultUserAgent: String): FetchResult
```

Implementation notes:
- Plain `HttpURLConnection` (no new dependency). Connect timeout 15s, read timeout 30s, hard cap on response size 2 MiB read in 16 KiB chunks.
- `User-Agent` = `sub.userAgentOverride` ?: `"XTLSCoreProxy/${BuildConfig.VERSION_NAME}"`. *(Requires `buildFeatures { buildConfig = true }` in `app/build.gradle.kts`.)*
- When `allowInsecureTls` is true, build a permissive `SSLSocketFactory` + `HostnameVerifier` and apply *only to that connection* — never via `HttpsURLConnection.setDefaultSSLSocketFactory` (that would leak globally).
- `profile-update-interval` is parsed case-insensitively from `connection.headerFields`. Accept any value parseable as `Int >= 1`.

### `SubscriptionBodyParser` (new, pure)

```kotlin
data class ParsedConfig(val displayName: String, val config: String)
data class ParseOutcome(val parsed: List<ParsedConfig>, val parseErrorCount: Int)

fun parseBody(body: String): ParseOutcome
```

`parseErrorCount` is surfaced to the user via `Subscription.lastError` ("3 lines failed to parse") only when non-zero and the overall fetch otherwise succeeded.

Pipeline:
1. **Base64 detection.** Trim body. If it matches `^[A-Za-z0-9+/=_\-\s]+$`, attempt `Base64.decode` (try standard, then URL-safe, with `NO_WRAP` permissive padding). If decoded UTF-8 contains `vless://` or `{`, use it. Otherwise fall back to the raw body.
2. **Split** on `\r\n|\n`. Drop empty lines and lines starting with `#`.
3. **Per-line parse.** For each line: try as `vless://` first, then as JSON; pass through `ConfigBuilder.buildRuntimeConfig(line)` for validation. On success, derive a display name. On failure, increment a `parseErrors` counter (surfaced via `Subscription.lastError` if non-zero).
4. **Per-line base64 fallback.** If a line itself looks base64 and decodes to a `vless://` URI, accept it. Handles mixed-format providers.
5. **Display-name derivation:**
   - VLESS: URL-decoded `#fragment`; else `host:port`.
   - JSON: `outbounds[0].tag`; else `streamSettings.tlsSettings.serverName` / `realitySettings.serverName`; else `address:port`; else `Config <index>`.
6. **Within-fetch dedup.** Case-insensitive name dedup with ` (2)`, ` (3)` suffixes.

### `SubscriptionRefreshCoordinator` (new)

Singleton holding a `ConcurrentHashMap<Long, Job>` of in-flight refreshes per subscription id.

```kotlin
suspend fun refresh(
    subId: Long,
    activeProfileId: Long?,
    db: AppDatabase,
    defaultUserAgent: String
)
```

Returns Unit; results are observed via reactive `Flow`s on `Subscription` and `Profile` (the row's `lastError`/`lastFetchedAt` updates flow back to the UI on their own).

Behavior:
1. Short-circuit if a refresh is already in flight for `subId`.
2. Load the `Subscription` row.
3. Call `SubscriptionFetcher.fetch`; on `Failure`, write `lastError` to the row and return.
4. Re-read `activeProfileId` and look up the active profile; compute `keepProfileId = active?.takeIf { it.subscriptionId == subId }?.id`.
5. Call `ProfileDao.replaceProfilesForSubscription(subId, keepProfileId, parsed)` (transactional — see below).
6. Write `markFetchResult(subId, lastFetchedAt = now, lastSeenIntervalHours, lastError = null-or-warning)`.

### `ProfileDao` additions

```kotlin
@Query("SELECT * FROM profiles WHERE subscriptionId IS NULL ORDER BY id ASC")
fun getManual(): Flow<List<Profile>>

@Query("DELETE FROM profiles WHERE subscriptionId = :subId AND id != :keepId")
suspend fun deleteForSubExceptId(subId: Long, keepId: Long)

@Query("DELETE FROM profiles WHERE subscriptionId = :subId")
suspend fun deleteForSub(subId: Long)

@Insert
suspend fun insertAll(profiles: List<Profile>)

@Transaction
suspend fun replaceProfilesForSubscription(
    subId: Long,
    keepProfileId: Long?,
    newProfiles: List<Profile>
) {
    if (keepProfileId != null) deleteForSubExceptId(subId, keepProfileId)
    else deleteForSub(subId)
    insertAll(newProfiles.map { it.copy(subscriptionId = subId) })
}
```

The `@Transaction` is critical: it guarantees Room's `getAll()` `Flow` emits exactly **once** after the swap rather than flickering through an empty intermediate state.

### `VpnViewModel` additions

```kotlin
val subscriptions: StateFlow<List<Subscription>> = ...
data class SubGroup(val subscription: Subscription, val profiles: List<Profile>)
data class ProfilesView(val manual: List<Profile>, val groups: List<SubGroup>)
val groupedProfiles: StateFlow<ProfilesView> =
    combine(profiles, subscriptions) { p, s ->
        val bySubId = p.groupBy { it.subscriptionId }
        ProfilesView(
            manual = bySubId[null].orEmpty(),
            groups = s.map { SubGroup(it, bySubId[it.id].orEmpty()) }
        )
    }.stateIn(...)

fun addSubscription(name: String, url: String, ...): Job
fun updateSubscription(sub: Subscription): Job
fun deleteSubscription(sub: Subscription): Job  // CASCADE handles profiles; clear active id if affected
fun refreshSubscription(subId: Long): Job
fun refreshAllStaleSubscriptions(): Job  // called from MainActivity.onStart
```

`refreshAllStaleSubscriptions` filters by `now - lastFetchedAt >= effectiveInterval * 3_600_000`, then launches each refresh in parallel via the coordinator.

### UI changes — `MainActivity`

The current `LazyColumn` (lines 263–283) becomes:

```kotlin
val view by viewModel.groupedProfiles.collectAsState()
val expanded = remember { mutableStateMapOf<Long, Boolean>() }

LazyColumn(...) {
    if (view.manual.isNotEmpty()) {
        item(key = "h-manual") { SectionHeader("My profiles") }
        items(view.manual, key = { "p-${it.id}" }) { ProfileRow(...) }
    }
    view.groups.forEach { group ->
        val isExpanded = expanded[group.subscription.id] ?: true
        item(key = "h-${group.subscription.id}") {
            SubscriptionGroupHeader(group.subscription, group.profiles.size, isExpanded,
                onToggle = { expanded[group.subscription.id] = !isExpanded },
                onRefresh = { viewModel.refreshSubscription(group.subscription.id) })
        }
        if (isExpanded) {
            items(group.profiles, key = { "p-${it.id}" }) { ProfileRow(...) }
        }
    }
}
```

A new `SubscriptionGroupHeader` Composable shows: chevron, sub name, "Updated 2h ago", refresh `IconButton`, and a small error pill when `lastError != null`.

A toolbar icon (e.g., `Icons.Default.Cloud`) launches `SubscriptionsActivity`.

`MainActivity.onStart` calls `viewModel.refreshAllStaleSubscriptions()`. (Not `onResume` — that over-fires on permission-dialog return.)

### UI changes — new activities

- **`SubscriptionsActivity`** — manage list. `LazyColumn` of `Subscription` rows: name, URL, last-fetched-relative, refresh icon, edit chevron. FAB to add. Long-press → delete confirmation.
- **`SubscriptionEditActivity`** — dual-tab editor mirroring `ServerSettingsActivity`. Simple tab: name, url, update_interval_hours (hint: "leave empty to use server-provided"). Advanced tab: user_agent_override, allow_insecure_tls toggle. Save validates name non-blank and url is `https?://...`. A secondary "Save and refresh now" button is a nice-to-have.

### Reusable component extraction

`DropdownField` from `ServerSettingsActivity.kt:332-372` moves to `app/src/main/java/com/justme/xtls_core_proxy/ui/components/DropdownField.kt` so `SubscriptionEditActivity` can reuse it.

## Refresh flow (end-to-end)

```
MainActivity.onStart
  → VpnViewModel.refreshAllStaleSubscriptions()
    → for each stale sub (parallel):
      → Coordinator.refresh(subId, activeProfileId)
        → short-circuit if in-flight
        → SubscriptionFetcher.fetch(sub) on Dispatchers.IO
          → HTTP GET, parse profile-update-interval header, read body (≤2 MiB)
        → SubscriptionBodyParser.parseBody(body)
          → base64 detection → split → per-line validate → derive names → dedup
        → keepProfileId = activeProfile if its subscriptionId == subId else null
        → ProfileDao.replaceProfilesForSubscription(subId, keepProfileId, parsed)
        → SubscriptionDao.markFetchResult(...)
      → Flow re-emits → groupedProfiles re-derives → MainScreen recomposes
```

Active VPN tunnel is unaffected: `XrayVpnService` snapshots `Profile.config` at connect time. The "ghost" kept row stays until the user disconnects and the next refresh prunes it.

## Edge cases & error handling

- **Base64 false positives:** require decoded UTF-8 to contain `vless://` or `{`. Otherwise treat the raw body as plaintext.
- **Mixed line content:** body-level base64 detection plus per-line base64 fallback covers most cases.
- **Comment lines:** drop lines starting with `#` and lines that contain neither `://` nor `{`.
- **Oversize body:** abort and surface "subscription too large" via `lastError`.
- **Concurrent refresh of same sub:** coordinator short-circuits.
- **Concurrent refresh of different subs:** parallelized; harmless.
- **User edits subscription URL:** existing profiles stick around with old configs until next refresh. Optional "Save and refresh now" button gives the user explicit immediate replacement.
- **Active profile during refresh:** preserved row keeps stale `name`/`config` until next refresh after disconnect (documented in code).
- **Subscription deletion while VPN connected to one of its profiles:** `deleteSubscription` first checks if active profile belongs to it; if so, calls `disconnect(context)` and clears active id, then deletes the sub (cascade kills the profiles).
- **TLS insecure leakage:** apply per-connection only.

## Testing strategy

JVM unit tests under `app/src/test/java/.../subs/` (test infra is already wired):

- **`SubscriptionBodyParserTest`** — base64-wrapped vless, raw vless, JSON-per-line, mixed, base64 with whitespace, comment lines, malformed lines, dedup, name derivation, edge cases (empty body, body with only comments, body that's almost-but-not-quite base64).
- **`SubscriptionFetcherTest`** — header parsing in isolation: extract the case-insensitive `profile-update-interval` reader as `parseIntervalHeader(headers: Map<String, List<String>>): Int?` and unit-test it directly.
- **Coordinator behavior** — manual / instrumented test, since it touches Room. Use the existing `androidTest` setup.

End-to-end manual verification:
1. Add a subscription pointing at a real provider (e.g., a Marzban panel or any `vless://` aggregator).
2. Verify profiles populate, group is collapsible, refresh works.
3. Connect to one of the sub-sourced profiles. Trigger a refresh. Confirm the active row is preserved and others are replaced. Disconnect. Trigger another refresh. Confirm the ghost is now reconciled.
4. Edit the subscription's URL. Confirm old profiles persist until next refresh.
5. Delete the subscription. Confirm cascade removed all its profiles and the active id was cleared if it pointed inside.
6. Toggle airplane mode and refresh. Confirm `lastError` populates and the error pill shows.
7. Change device clock to simulate 12h elapsed; reopen the app cold. Confirm auto-refresh fires from `onStart`.

## Build order (incrementally testable)

1. **DB foundation** — `Subscription` entity, `SubscriptionDao`, `Profile` FK, version bump, destructive fallback. App still works (manual profiles only).
2. **Body parser pure logic** + JVM unit tests.
3. **Fetcher** + header-parsing unit test.
4. **Coordinator + VM wiring** (debug-only "Add test sub" entry-point to validate end-to-end).
5. **Subscription management UI** (`SubscriptionsActivity` + `SubscriptionEditActivity`).
6. **MainActivity grouped UI** (manual section + collapsible groups + toolbar icon).
7. **Foreground refresh trigger** (`onStart` hook).
8. **Polish** — error pill, relative-time formatting, delete confirm dialog, "Save and refresh now".

## Critical files

| File | Change |
|---|---|
| `app/src/main/java/com/justme/xtls_core_proxy/db/Profile.kt` | Add `subscriptionId` FK + index |
| `app/src/main/java/com/justme/xtls_core_proxy/db/AppDatabase.kt` | Add entity, bump version, destructive fallback |
| `app/src/main/java/com/justme/xtls_core_proxy/db/ProfileDao.kt` | Add scoped queries + `@Transaction` replace method |
| `app/src/main/java/com/justme/xtls_core_proxy/db/Subscription.kt` | **NEW** — entity |
| `app/src/main/java/com/justme/xtls_core_proxy/db/SubscriptionDao.kt` | **NEW** — DAO |
| `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionFetcher.kt` | **NEW** — `HttpURLConnection` GET |
| `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParser.kt` | **NEW** — pure parser |
| `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt` | **NEW** — in-flight de-dup + orchestration |
| `app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt` | Add subs, grouped view, refresh methods |
| `app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt` | Toolbar icon, grouped LazyColumn, `onStart` hook |
| `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionsActivity.kt` | **NEW** — list/manage screen |
| `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionEditActivity.kt` | **NEW** — dual-tab editor |
| `app/src/main/java/com/justme/xtls_core_proxy/ui/components/DropdownField.kt` | **NEW** — extracted from `ServerSettingsActivity` |
| `app/src/main/AndroidManifest.xml` | Register two new activities |
| `app/build.gradle.kts` | Enable `buildFeatures.buildConfig = true` (for `BuildConfig.VERSION_NAME`) |
