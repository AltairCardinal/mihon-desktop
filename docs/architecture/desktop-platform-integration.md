# Desktop platform integration

Desktop entry points are registered through `DesktopAppModule`: the UI receives shared use cases and Desktop adapters from `DesktopUiDependencies`.  Platform actions enter through the single-instance broker, while share actions use `DesktopShareService`; neither path reinterprets domain data.

User entry and failure matrix: About starts update checks; Updates displays shared updates; Security exposes lock/delay and reports capture limitations.  Unsupported widget, native-notification, telemetry, missing trust-root, verifier failure, cancellation, and unsupported OS all retain a visible unavailable/manual path rather than a successful-looking control.

Updates use `DesktopUpdateScreenModel` and `DesktopUpdateController` as an explicit state machine.  Downloads stay inside the update cache, validate redirect, size and checksum boundaries, and only offer automatic handoff after platform signature policy accepts the artifact.  A verifier failure, unsupported platform, missing trust root, or cancelled operation leaves a manual-update path instead of claiming installation succeeded.  The runtime owns the updater scope and waits for cancellation cleanup during application close.

Privacy adapters expose their capability rather than treating every operating system as Android.  `DesktopAppLock` consumes lock and delay settings.  `DesktopWindowPrivacy` reports supported, limited, failed, and unsupported capture protection.  Native notification-content controls and telemetry are unsupported until a real consumer exists; in-app feedback is not a substitute.  Desktop system widgets are unsupported: the Updates screen consumes shared `GetUpdates`, but no fake widget provider is created.  Android Glance widget behavior and lock privacy remain covered by the Android `presentation-widget` production test.

When adding an operating system, first add a platform adapter with a stable unsupported/limited failure result, then wire it through DI and a production-facing test.  Update the parity manifest with the fixed-main source, shared contract, Android and Desktop consumers, adapter, protection test, and any deviation.  Promote a candidate only after the applicable real OS behavior is accepted.  For updater support, publish canonical signed artifacts and trust configuration before enabling automatic handoff; rollback keeps the verified artifact available for manual installation and changes the capability back to manual-only if verification cannot be trusted.

New-OS maintenance sequence: define the capability result, add the adapter and DI consumer, cover the user-facing state and failure, validate the target OS, then update the manifest scope from candidate to accepted evidence.


## Automatic library-update device conditions

`DesktopDeviceConditions` is one DI-owned platform port shared by the library scheduler,
Library settings, and the existing Test Mode `/test/state` library snapshot. Capabilities
and observed states are separate: a Windows query failure remains `UNKNOWN` for a
supported condition. An unsupported platform has no capabilities, retains the shared
Windows preferences, hides their controls, and ignores them when scheduling. No Windows
DLL is loaded by the unsupported-platform factory.

Windows observes WLAN interface state through the enumerated native GUID, without
requesting SSID/BSSID or location access, and frees query/list buffers and closes the
handle. MIB interface rows use JNA ABI layout. Multiple active interfaces, active
unknown-media tunnels, and virtual interfaces prevent a claim that one physical
connection determines routing. Machine-wide NLM `GetCost(NULL)` is used only with one
confirmed physical connected Ethernet/WLAN interface; unknown cost, native failure,
and unresolved routing remain unknown. This is deliberately conservative and does
not infer a route from an interface name. AC line status uses 0/1/255; a missing battery
is not evidence of external power. COM initialization and release belong to each
query's calling thread.

A scheduled occurrence freezes its selected conditions together with its original
workset. The scheduler checks them between works, never interrupts an accepted source
unit, and persists waiting reasons in the existing library task context. Selected
unknown/unmet conditions wait; unselected or unsupported conditions do not. A failed
checkpoint cannot authorize a source request. Continuous waits preserve the occurrence,
completed units, original prediction time/window, and do not consume the periodic check
marker. The marker and shared last-check preference are recorded once before the first
real source check; an empty/all-skipped completed check also consumes the period. Sleep
or restart resumes the same persisted work rather than creating a parallel occurrence.

Manual refresh and explicit failed-only retry can supersede an automatic wait. They
cancel and join its old owner before starting their own authorized scope; ordinary
active checking remains busy. Shutdown also waits for replaced owners that are still
inside a native query or cancellation cleanup. Automatic resume retains automatic
conditions and displays waiting, not a checking spinner. Task activity drives Cancel;
checking activity drives the spinner. The result modal retains a bounded scroll path
to original work rows, including at narrow windows and large font sizes.

Development tests cover real DI/HTTP diagnostic wiring, actual FileTaskCheckpointStore
failure/reopen, native byte shapes and the Windows adapter's real query. These are not
claims about every router, hardware configuration, macOS runtime, or a packaged release;
formal production runtime and hardware acceptance remain the final iteration matrix.


## Two-stage content wheel refresh

Library and manga detail content owners share `TwoStageRefreshGesture`. Only native,
unmodified vertical wheel input that started at the top and remained unconsumed after
child dispatch can contribute distance. Reaching the top from the middle does not arm.
The first 80dp prepares refresh; a separate 48dp segment after at least 400ms submits
once. The hint has at least 300ms before submission, and the armed deadline is an
absolute three seconds. Actual accepted task completion starts an 800ms quiet period;
wheel activity and real lazy viewport movement restart that period. Programmatic
positioning, scrollbar actions, modified wheel, keyboard navigation, focus departure,
selection, modal ownership, and scope changes cannot submit a refresh.

`DesktopRefreshGesture` deliberately uses Compose Foundation Desktop 1.10.2's internal
`platformScrollConfig` / `Density.calculateMouseWheelScroll` behind a file-local
visibility suppression. Foundation skips nested scroll dispatch for upward wheel when
already at the top; the adapter therefore records old top eligibility in Initial and
observes the same unconsumed event in Final. The platform configuration remains the
authority for AWT precise rotation, scroll amount, viewport dimensions and density.
An upgrade must preserve this API and rerun actual native wheel/density/child-consumption
contracts; compilation or those behavioral contracts must fail rather than silently
substitute a guessed multiplier. Nested scroll records activity and revokes intentions.

The library submits its complete current category through the existing scheduler,
independent of UI query/filter projection. Detail uses only the current persisted manga,
with the right chapter pane in wide mode and the content pane in narrow mode. Accepted
handles retain the original occurrence's terminal observation, including initial store
refusal and cancellation before the lazy body starts. Busy rejection does not borrow
another scope's job or enter completed cooldown. The original detail More/Retry source
refresh remains on its existing source-owner chain. No additional updater, persisted
gesture state, HTTP client, or task registry is introduced.

Offscreen Compose/AWT and SQL/HTTP tests establish wiring and input contracts. Physical
mouse, touchpad, natural scrolling, and packaged Windows DPI acceptance remain the
release matrix; synthetic precise AWT input is not a touchpad hardware claim.
