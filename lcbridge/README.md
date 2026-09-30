# Orbis embedded LC

This library mechanically imports the complete Java/Kotlin source and JVM tests
of the private LC 2.4.3-personal (versionCode 15) Android application, then adapts
its identity, entry points and Android manifest for an Orbis host. The source
baseline matches the installed phone's version verified on 2026-09-21. It does
not include any installed application data, signing material, API credentials,
observation logs, private server environment or production database.

## Entry and architecture

`OrbisPhonePage` embeds `BridgeDashboard`: eight genuine destinations, each
composing only its own settings (`BridgeSection`, `MainScreen` and the additional
`BridgeSettingsSections`). The host supplies Orbis colors. There is no second
launcher icon. Foreground-service notification shortcuts render the same module
dashboard in an unexported activity.

The dashboard uses three compact columns at widths of at least 360 dp with
normal text scaling, and two columns on narrower screens or larger text. There
are eight real modules; no artificial ninth feature is added.

The code namespace remains `com.lover.connect`; the **application identity,
permissions, storage, Keystore and component ownership are the Orbis host's**.
The standalone LC app is untouched. Nothing is read or imported from its sandbox.
The local-only endpoint uses port **5001**, not the standalone app's 5000, and
retains per-install random path authentication and browser-Origin rejection.
Opening the dashboard neither starts a service nor requests Android permissions.
Only explicit user actions enable capabilities. A saved enabled run preference
can be restored on boot/update; there is no old always-on preference migration.
Host adaptation deliberately removes the old location settings page's implicit
`restoreAfterBoot` call: merely browsing a page cannot start location tracking,
upload queued events, or create/cancel retry alarms. Explicit start/resume and
`BootReceiver` still retain recovery for the user's persisted enable state.
This is a changed UI lifecycle boundary, not removal of the geofence engine.
The uploader also independently refuses to create a worker unless event sending
is explicitly enabled, its endpoint/token are valid, and an existing database
contains pending events. Default-off, missing database and empty queue recovery
do not create a worker or touch retry alarms. This sender eligibility does not
prevent independently authorized location tracking from recovering, and queued
events may still be sent when tracking is paused/stopped.

Do not enable duplicate LC/Orbis observers and reminders. Android does not let
this library reliably prove the independent app's entire service state; the user
must stop the corresponding standalone capabilities before enabling these.

## Feature parity and ownership

| Module | Retained implementation and persistence | Runtime / permissions | Tools |
| --- | --- | --- | --- |
| Connection and permissions | `McpServiceController`, `McpLocalSecurity`, `McpServiceLifecyclePolicy`; `lc_service_control`, `lc_mcp_security`, diagnostics | Explicit start/stop; foreground notification; restore only persisted enable; system permissions and OEM settings | `get_l_service_status` |
| Screen observation | `McpService`, `ScreenshotManager`, `ScreenCaptureService`, `LCAccessibilityService`, `EyesResponseParser`, `EyesVisionResponse`, `EyesAlertPolicy`; name/personality/interval/API in `lc_config`; bounded local diary | Existing private parser/diagnostics and alert path; Android 11+ accessibility pixels; Android 8–10 user-approved MediaProjection session; configured visual model; network requests only when invoked/enabled | `take_screenshot`, `read_eyes_log`, `get_l_service_status` |
| Rest reminders | `AppRestRuntime`, `AppRestPolicy`, `AppRestEventGate`, `EyesAlertCooldown`; configured threshold in `lc_config` | Real usage events and monotonic duration; same non-chat app 60–1440 min; app switch/lock/reset; no screenshot inference; fixed 30 min alert cooldown; Orbis and Rikka exempt | Runtime diagnostics in `get_l_service_status` |
| Location | `LocationTrackingService`, `LocationSafetyManager`, `GeofenceStateMachine`, `SecureLocationConfigStore`, runtime/event stores, uploader/watchdog/retry/compaction policies | home/work/one custom destination, each 200–2000 m radius; current-location centers; 2–10 km second reminder; report/one-shot 6h exemption; start/pause/stop; clear; precise/background location; encrypted coordinates remain local | `get_location_safety_status` (labels/state/radii/queue, never coordinates) |
| Notifications and context | `DeviceContextCollector`, `DeviceContextStore`, `DeviceContextLogic`, `DeviceContextSnapshot`, `MusicListenerService`; separate config/runtime prefs | Default-off collection, default-off notification summaries, separate opt-in redacted text; TTL/bounds/clear; sensor and notification-listener permissions; external text has no instruction authority | `get_device_context`, `get_recent_context_events`, `get_context_capabilities` |
| Device control | `AppLockManager`, `LCAccessibilityService`, unlock receivers, `LockScreenReceiver`, `AlarmReceiver`, `AlarmRingService`, music listener | Lock/unlock, focus, time-window redirect; protected packages; Orbis redirect target; explicit UI confirmation; Vivo passive policy retained; exact alarms use user-granted SCHEDULE_EXACT_ALARM; device-admin lockscreen | `send_notification`, `set_alarm`, `cancel_alarm`, `get_alarms`, `lock_screen`, `play_music`, `get_now_playing`, `lock_app`, `unlock_app`, `focus_rikka`, `redirect_to_rikka`, `list_locked_apps` |
| Sentinel and self-wakeup | `SentinelEndpointPolicy`, `LocationSafetyUploader`, private rest/visual event protocol and delivery policy; user-entered URL/token/enabled in `lc_config` | Existing private ingress required; no runtime secrets copied. UI save does not send test. Uncertain/rejected delivery is not repeated as a local notification. External self-wakeup is separately configured in host MCP settings | `configure_sentinel`, `test_sentinel`; external service separately supplies `schedule_wakeup`, `list_wakeups`, `cancel_wakeup` |
| Local information | `DailyStepCounter`, battery/weather/anniversary/memory implementations; `lc_config`, `lc_step_counter`, `lc_memory.json` | Battery, sensor steps, weather city; countup/countdown anniversary/pin/delete; user-selected memory export/import (import overwrites); no ST memory migration | `get_battery`, `get_steps`, `get_weather`, `get_anniversary`, `save_memory`, `read_memory` |

App usage reporting now has one owner: Orbis native `ScreenTime` (`get_screen_time`). The duplicate LC `get_screen_time`, `get_app_timeline`, and `reset_screen_time` tools and implementations were removed at the user's request. LC's independent continuous-use rest reminder still uses usage events and retains its existing permission checks; no rest, context, memory, or other LC data is deleted. Existing MCP clients should refresh the tool list.

The companion tool names share one catalog and native execution adapter.
`CompanionToolCatalog` supplies the MCP schemas and native descriptors;
`CompanionNativeTools` invokes local implementations in the same process,
without HTTP, JSON-RPC or an MCP token. The host registers selected tools as
`companion_<legacy_name>`; no new MCP configuration is necessary. The legacy MCP
endpoint remains compatible. `focus_rikka` and `redirect_to_rikka` still target
the current Orbis package, not the independent Rikka app.

Native catalog enumeration, offline memory, runtime-status reads and alarm queries
do not start services or grant permissions. Other runtime tools may request bounded
recovery of a previously enabled companion service, but never enable one the user
has turned off. The service still starts its old
loopback compatibility listener when explicitly enabled; native calls do not
depend on that listener's port, URL or credential. Catalog enumeration is inert.
Permissions are rechecked at execution, errors are structured, waits are bounded,
and cancellation never automatically retries. A timed-out screenshot/analysis or
external write may already be in flight; an unknown outcome must be checked first.
The screenshot tool retains the existing behavior: screen capture plus the user's
configured visual-model analysis and local diary/alert handling, not raw offline OCR.

`CompanionMemoryStore` is the only reader/writer used by native memory, legacy MCP,
the observer and UI import/export. It reuses `lc_memory.json`, serializes access,
and atomically replaces the file without discarding unreadable original data.
It never imports standalone LC data or creates a second memory database. Explicit
UI import still replaces the memory contents and should be preceded by export.
This device-local store is shared by opted-in assistants and is not ST memory.

The host owns native per-AI enablement and approval. Read-only alarm queries require
no execution confirmation. Device-changing calls carry approval metadata and use
the host's current or remembered approval; system permissions remain separate.
External MCP clients continue to own their own confirmation policy.
The library does not silently add/enable an MCP connection or overwrite its policy.

### Alarm observations

`get_alarms` (native host name `companion_get_alarms`) reads only this application's
alarm ledger and recent trigger/stop receipts. Its optional arguments are
`include_history` (boolean, default `true`), `limit` (integer, 1–100, default 30),
and `offset` (integer, 0–10000, default 0). Continue with the returned `next_offset`
using the same history option; `null` means the last page.
It works without the companion service and never schedules, cancels, backfills or
rings an alarm. It is not an inventory of every alarm in the system Clock app;
older versions did not retain complete receipts, so missing history is inconclusive.

`set_alarm` creates a one-shot alarm; a second setting for the same HH:mm replaces
that slot, while a time already passed is scheduled for the next day. Its optional
message is limited to 4096 characters. Check its
full-date receipt rather than assuming today's date. `cancel_alarm` addresses that
same application-owned HH:mm slot. All three alarm methods return structured JSON
with a boolean `ok` and an optional `outcome`/error code. The native adapter rejects
malformed or contradictory receipts rather than labelling them successful.
Scheduler acceptance is not proof that audio started or a person heard it; queries
must preserve that distinction and must never repair uncertainty by scheduling again.

## External service boundary

The private ingress deployment is not the public send-only server: it supports
continuous-use validation, visual events, geofence persistence and report
detection. Reusing the source client does not deploy, upgrade or retarget that
server. The client authenticates with a user-supplied token; all values start
empty. The existing server's Rikka conversation remains its target until the
server owner explicitly changes its configuration. Server-side Orbis identity
rules should be reviewed before production migration as well.

Self-wakeup and the legacy random-silence sentinel are **independent services**.
There is no fake local switch for server silence/probability/quiet-hour rules.
Add the self-wakeup MCP in Orbis, choose it for the intended assistant and use
`list_wakeups` to verify read-only reachability. Explicitly authorized
`test_sentinel` produces a real event. HTTP acceptance is not proof of chat
display, model reply or an Android notification.

## Data and validation boundary

API/sentinel secrets, private endpoint, coordinate ciphertext, observation diary,
context and location-event database are excluded from Android cloud/device
transfer backup by host rules. This is not a portable LC migration: Android
Keystore keys cannot be silently copied. No automatic private data import is
implemented. LC local memory export remains an explicit user operation.

The existing JVM suite is copied intact, with additional host identity, port,
default-off and module-coverage tests. Root integration builds and device testing
must validate manifest merging, permissions, foreground/background lifecycle,
visual response, geofences and actual configured downstream services. Source
parity and a successful compile alone are not proof of real-device end-to-end
delivery; no new permissions or production service calls are performed by tests.

Instrumentation isolation is broader than a Composable Context wrapper: the
isolated runner closes `LcExternalRecoveryGate` before creating Application, so
real boot/package-replaced/upload-retry broadcasts cannot access the host's
preferences or alarms while synthetic fixtures run. This gate is process-local,
defaults allowed in normal processes, never persists and never changes Android
permissions/component settings. Consequently, UI fixtures do **not** claim to
exercise real broadcast recovery. The production sender eligibility is tested
separately without this gate, and normal-process default-off recovery still
requires a real-device diagnostic check.
