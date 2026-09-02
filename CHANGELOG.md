# Changelog

## 1.022
- Fixed: dragging the bubble across the screen's midpoint moved it to the new side but left its half-circle shape (and icon alignment) stuck on the old side until something unrelated triggered a refresh - positionTab() (called live while dragging) only moves/resizes the window, the shape itself comes from updateTabAppearance() inside refreshVisuals(), which the drag never called. Now refreshed live the moment the drag crosses sides.
- Added a version label ("v1.XXX") under the app name on the main screen.

## 1.021
- Tap outside the panel (anywhere else on screen) now collapses it back to the bubble - same as the collapse arrow. Tap outside the settings popup's card now closes just the popup, leaving the panel open.
- Updating the app now downloads the APK in the background with a live progress dialog (percent, size, speed, Cancel) and hands it straight to the system installer when done - same DownloadManager-based flow as FuZz LED / LEDCAR, replacing the old "open a browser to the raw APK URL" step.
- Grew the theme picker from 24 to 60 colors.
- Collapse (retract) arrow button is now a true circle instead of an oblong pill.
- Fixed: the "/25" next to the volume number in the panel never actually reflected the Conf tab's "max volume supported" - it was hardcoded in the layout and never looked up in code at all. It now shows "/" + whatever that's set to, live.
- "Volume panel width" (Size tab) can now go down to 80dp (was 110).

## 1.020
- Removed the "hold 2s for custom" hint text under the volume readout entirely - the hold-to-open gesture is still exactly 2s, just silent now; the thin progress line is still the only feedback while holding.
- The Size tab's first slider now resizes the floating bubble itself (was: the settings popup's own size, which is fixed at 260dp now) - its height and icon scale together with it.
- The bubble's icon is a little larger relative to the bubble than before.

## 1.019
- Fixed: the Size tab's "volume panel width" slider didn't actually resize the panel - positionPanel() re-measured the panel's content internally but never resized the overlay window itself, so the on-screen panel stayed whatever width it was when first opened. It now resizes for real.
- "Max volume supported" (Conf tab) can now go as low as 10 (was 25).

## 1.018
- Opening the app now auto-starts the overlay (if permissions are already granted and it isn't running yet) instead of requiring a manual "Start volume overlay" tap every time - puts up the permanent/ongoing notification right away, which is what keeps the foreground service (and so the app) alive. Skipped right after a crash, and only ever fires on an actual app open (onCreate), never just returning to this screen.

## 1.017
- Hold-hint text now reads "hold 2s for custom" (the popup is a full settings screen now, not just colors).
- Settings popup is now freely draggable: a drag handle next to Close (top-right) lets you move the whole popup anywhere on screen; it opens centered the first time and remembers wherever you last dragged it after that.
- Settings popup is now three tabs: **Theme** (the 24 colors + Dynamic checkbox), **Size**, **Conf**.
- Theme tab: new "Dynamic" checkbox. Checked (default): color follows volume, sliding from the theme's own color to red as it nears the "limited to" ceiling. Unchecked: one flat theme color everywhere, ignoring volume ("merge with theme").
- Size tab: sliders for popup size, volume panel width, and volume panel height (the EQ bar's length) - all live and persisted.
- Conf tab: sliders for the three volume tiers - "max volume supported" (40 default, the EQ bar's full scale), "limited to" (25 default, this widget's own write ceiling for drag/nudge), and "when go slowly" (20 default, where direct drag stops and the nudge arrow takes over). Each tier is bounded by the one above it.
- Nudge/collapse arrow buttons are now fully rounded (pill-shaped) instead of a 7dp corner radius.

## 1.016
- Theme popup now opens after a 2s hold instead of 5s.
- Theme popup is now its own centered overlay window, positioned in the middle of the whole screen, instead of being squeezed inside the docked side panel's width.
- Picking a theme now re-skins the whole open panel - card background, nudge/collapse button backgrounds, and the hold-progress bar - not just the EQ ball, matching the selected color.
- Panel is narrower: 150dp instead of 184dp.
- The panel card's corner shape now flips to match whichever edge it's docked to (flat against the screen edge, rounded facing into the screen) - same logic the floating tab already used.
- Tab's speaker icon is 20% smaller (22dp -> 17.6dp).

## 1.015
- **Found the actual root cause of every crash-on-open report across the last several versions**: every layout and drawable XML file in the project declared the resource namespace as `http://schemas.android.com/res/android` instead of the correct `http://schemas.android.com/apk/res/android` (missing `apk/`). This is subtle - `aapt2` compiles it without error and even `aapt2 dump xmltree` shows the attributes looking fine - but the on-device runtime's strict binary-XML parser doesn't recognize `android:` attributes under the wrong namespace as real framework attributes at all, so every `layout_width`/`layout_height`/etc. was silently discarded, and `setContentView()` threw `InflateException: ... You must supply a layout_width attribute` instantly, before a single trace-log line could even be written for it. Found by diffing against FuZz LEDCAR (a sibling app confirmed working on the same head unit hardware), which had the correct namespace. Fixed in all 8 affected files (activity_main.xml, overlay_panel.xml, overlay_tab.xml, theme_swatch_item.xml, and the 4 bg_*.xml drawables). Verified on a real device via adb: the app now opens, `setContentView` completes, and the overlay service starts end-to-end (`onCreate SUCCESS`) with zero crashes.

## 1.013
- Removed the first-run auto-start entirely - "white page, then crash" was reported specifically (and only) when permissions were already granted, which is exactly the condition that used to trigger it. Opening the app is now unconditionally stable regardless of permission state: starting the overlay is always, with zero exceptions, a manual "Start volume overlay" tap.
- Capped "View trace log" to the most recent ~20,000 characters - the file has been appending since v1.009 across many test cycles by now, and only the newest entries matter for the current crash.

## 1.012
- Removed MANAGE_EXTERNAL_STORAGE ("all files access") and the store-log-on-main-storage feature entirely. Reports of an instant crash with *nothing at all* written to the trace log - not even its very first line - started right after that permission was added, which points at something killing the process before the app's own code gets a chance to run at all (a manifest-level permission can do that; a manufacturer security layer treating overlay + foreground-service + all-files-access as a spyware-like combination is a known cause). This is a targeted test of that theory. The trace log keeps working fine from the app's own external-files folder without it.
- First run now auto-starts the overlay once, directly, right after permissions are granted - no storage detour in the way.

## 1.011
- First-run sequencing: once overlay + notification permissions are sorted, the app now prompts once to set the trace log to main storage before ever starting the overlay for the first time - "Set up now" or "Skip". Either way, the moment that's resolved, the overlay starts automatically that one time. This is the only case that still auto-starts it; every other launch remains a deliberate "Start volume overlay" tap.

## 1.010
- Added an optional "Store log on main storage" button - grants "all files access" so the trace log writes to a plain /storage/emulated/0/fuzz_volume_trace.log instead of the app's own Android/data folder, visible to any file manager with no special per-folder permission needed. Entirely optional; the log keeps working from its previous location either way.
- The main screen now shows the log's exact current path directly under the status line.

## 1.009
- Added a persistent, step-by-step trace log (TraceLog), separate from the crash dialog: every meaningful step through startup (both MainActivity's and VolumeOverlayService's) is appended to a file as it happens, not just written once when something finally throws. Written to external storage - Android/data/com.fuzz.volumehu/files/fuzz_volume_trace.log - so it can be read with any file manager even if the app never manages to show its own UI.
- Added a "View trace log" button on the main screen to read it in-app too, with a Clear button to reset it.
- The crash dialog now also names the exact trace log file path.

## 1.008
- Found another real gap: VolumeOverlayService.onCreate() only wrapped its second half in try/catch - the first few lines (WindowManager/AudioManager/prefs/theme setup) ran unguarded, and since starting the service is asynchronous, nothing MainActivity does can catch a failure there. The whole method is wrapped now, and it catches Throwable (not just Exception), since a resource or class-loading problem on unusual firmware can surface as an Error that a plain Exception catch lets straight through.
- Removed the automatic "start the overlay when the app opens" behavior entirely - it was itself a repeated, hard-to-pin-down crash trigger, firing on every single app open. Opening the app is now always just the main screen; starting the overlay is a deliberate tap on "Start volume overlay" so a problem there can actually be isolated and reported instead of racing with a crash-recovery dialog.

## 1.007
- Found the actual cause of "still crashes, never starts": VolumeOverlayService returned START_STICKY, which tells Android to relaunch it automatically the instant it's killed - including by a crash. If startup crashes for any reason, the OS itself keeps relaunching it in an infinite loop that no try/catch inside the app could ever stop. Changed to START_NOT_STICKY - a fresh start now only ever comes from an explicit source (opening the app, the boot receiver).
- After a crash, opening the app now only shows the error - it no longer auto-restarts the overlay. Starting it again is a deliberate tap on "Start volume overlay".

## 1.006
- Added an on-device crash reporter, since there's no adb on the target head units: any crash, anywhere in the app, now gets written to a file and shown in a dialog the next time the app is opened - readable and screenshottable instead of an invisible "it stopped."
- Wrapped every remaining touch handler, click listener, and drag callback in the floating tab/panel (drag, tap, long-press-close, the EQ bar's drag, the nudge arrow, the theme popup) so a failure anywhere in there logs and recovers instead of crashing.
- Fixed a real crash risk in the EQ bar's drawing code: it could ask for a gradient over a zero-height area before its first layout pass, which throws.

## 1.005
- On launch, a missing-permissions dialog now lists exactly what's needed and why ("Display over other apps - lets the floating volume tab draw on top of everything else", etc.) with a "Grant now" button, instead of silently jumping to Settings or only showing a toast.

## 1.004
- Extra hardening: starting/stopping the overlay service (on launch, and from the "Start/Stop volume overlay" button) is now also wrapped so any unexpected failure shows a toast instead of crashing.

## 1.003
- Fixed a crash-on-launch: v1.002's auto-prompt for overlay permission called startActivity() on the system's "display over other apps" screen with no safety net. On head-unit firmware that doesn't ship that exact screen, that throws and force-closes the app before any permission dialog can show. Every settings/browser Intent the app opens (overlay permission, battery optimization exemption, update download) is now wrapped so a missing screen shows a message instead of crashing, and falls back to a version of the Intent without the package-specific URI if the first form isn't supported.
- Hardened VolumeOverlayService the same way: adding/removing the floating windows can no longer crash the app if something about WindowManager behaves unexpectedly on this firmware - it logs and stops cleanly instead.

## 1.002
- Fixed: first launch now actually asks for permission. SYSTEM_ALERT_WINDOW has no system popup of its own - the app now sends you straight to that Settings screen on first open instead of waiting for someone to notice the "Grant permission" button. Also requests POST_NOTIFICATIONS (Android 13+) so the ongoing "can't be killed" notification actually shows.

## 1.001
- First release: floating half-circle volume tab (Soft Neumorphic skin), drag to reposition (10%-90% of the screen edge, flips side past center), tap to open the panel.
- EQ Segments bar + ball, capped at 25 even when the head unit's own max is higher - drag never passes 20, the nudge arrow (500ms cooldown) steps 21-25.
- Hold the volume number for 5s to open the 24-color theme popup; picking a color previews live, stays open until you tap Done.
- Hold the tab to close the app. Foreground service + ongoing notification keep it running until you do.
- Reopens at the last position on launch and after a reboot.
- Checks FuzzBC/fuzz_volume_hu on GitHub for updates on launch.
