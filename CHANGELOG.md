# Changelog

## 1.052
- Custom theme's RGB panel gets a 4th slider: Alpha (0-255, default 255/opaque). The hex readout is now 8-digit ARGB so the effect is visible there too. Since the bubble/panel/button backgrounds are real overlay windows, dropping Alpha makes them genuinely see-through to whatever's behind - mixColors() now interpolates alpha alongside RGB, which only actually changes anything for Custom (every preset theme color is opaque, so nothing shifts for those).

## 1.051
- Same dark-background fix as 1.050's readout text, extended to the nudge/collapse button icons and the bubble icon: ic_chevron was drawn solid black with no tint at all, so it stayed invisible-on-dark on the same saturated colors that made the readout unreadable; the bubble icon had the same problem with its old fixed dark-brown tint. Both now switch to white on a dark background, same perceptive-luminance check as the readout. Bubble icon on a Clear background (no background to contrast against) is unaffected - it still tints with the raw color itself, unchanged.

## 1.050
- Volume readout text (the big number + "/25") now switches to white on a dark panel background instead of staying its usual dark ink - now that 1.049 lets the card show genuinely dark, saturated colors, dark-on-dark was unreadable on those themes and at the high-volume end of Dynamic mode. Both now also carry a soft shadow so the number stays legible against a busy/saturated card color either way.

## 1.049
- **Fixes washed-out colors on the actual bubble/panel/buttons**, not just the theme swatches (1.048 fixed those): the real widget backgrounds mixed only 22-40% of the chosen color into a cream/tan base, so a saturated pick like pure red rendered as a pale pink - real reported symptom, "when i set Red, show very low led color". That blend ratio is now 85% real color everywhere it's used (bubble background, panel card, small buttons, and the shape-morph open animation), keeping just enough of the neutral base for the surface to still read as tinted cream rather than a flat swatch, without diluting the actual chosen color past recognition.

## 1.048
- Preset theme swatches are now a flat, precise color instead of a diagonal low/mid/high gradient "splash" - each ball shows its theme's actual representative color (the same one "flat merge" mode uses everywhere), consistent with the Custom swatch's own plain solid fill right above them.

## 1.047
- Bubble icon is now 80% of the bubble's own size (was 40%) and scales with it via the Bubble tab's size slider, same as before - just bigger.
- Bubble icon gets a soft drop shadow, shaped to the glyph itself rather than a rectangle behind the whole icon view.

## 1.046
- Panel tab gets a "Hide after" slider (2-30s, default 5s) - now configurable instead of the fixed 5 seconds the panel's auto-close-to-bubble idle timer used since it was added.
- Theme tab: picking Custom no longer grows the popup taller - its RGB bars now replace the preset theme grid entirely instead of adding onto it (never shown together). Press Custom again to collapse the bars back down to the grid and pick a preset instead.

## 1.045
- Removed the small fake bubble-size preview swatch next to the Bubble tab's size slider - redundant now that this tab shows the real floating bubble live behind the dialog (1.043), so the slider's effect was already visible on the actual bubble.

## 1.044
- Theme tab layout: the Custom swatch now sits in its own row above the preset color grid, not as the grid's first cell - its RGB bars open right underneath that button (still toggling open/closed on repeat taps of Custom, unchanged), with all 90 preset theme swatches below both instead of above them.

## 1.043
- Bubble tab now shows the real floating bubble instead of the (dimmed, unrelated) volume panel behind the settings dialog: since Bubble tab's own settings - background shape, icon - only ever show on the actual bubble, and the bubble doesn't normally exist while the panel is open, switching to this tab temporarily hides the panel and brings the real bubble window back so changes here show live on the real thing, not just the small size-preview swatch. Switching to any other tab (or closing Settings) restores the panel exactly as it was.

## 1.042
- Settings popup reorganized: the old Size and Form tabs mixed bubble-only and panel-only controls together with no clear grouping. Now four tabs - Theme, Conf, **Bubble**, **Panel** - and everything about one specific piece lives entirely under its own tab: Bubble has bubble size + live preview, Bubble background shape, and the 16-icon picker; Panel has panel width, panel height, Panel style (the EQ bar's visual form), and Panel background shape. Nothing about what any control actually does changed, only where it lives.

## 1.040
- Form tab gets a "Bubble icon" picker: 16 icon options (the shipping speaker plus 15 new glyphs) grouped into four categories - Classic (Minimal/Bold/Hairline refinements of the speaker cone), Audio levels (EQ Bars/VU Meter/Radar/Waveform/Pulse Dot/Fade Waves - abstract, no cone at all), Alternate (Megaphone/Headphones/Volume Knob/Geometric), and Thematic (Retro LCD, matching the panel's own LCD form; Wheel + Wave, a nod to the head-unit itself). Whatever's picked shows on the real floating bubble immediately.
- Theme tab's grid now opens on a "Custom" swatch: tap it to open R/G/B sliders (0-255 each) and dial in any color, with a live preview swatch and hex readout - it behaves like any other theme (Dynamic/LEDCAR Set still apply on top of it) and is remembered across restarts.
- 30 more built-in themes, bringing the picker to 90 total.
- Main screen (MainActivity) is now a dark screen - cream text on a near-black warm ground, dark rounded buttons - independent of the floating panel's own fixed cream skin, which is unchanged.

## 1.039
- **Removes "Block system volume popup" entirely** - the main-screen toggle, VolumeKeyAccessibilityService, and its Accessibility permission. Across 1.028-1.038 this went through key interception, a window-watch dismiss attempt, and a window-watch identify-only version, but on real hardware the native popup either kept showing anyway or (briefly, in 1.036) the dismiss attempt itself misfired into whatever app had focus. Removed rather than keep shipping a permission-sensitive feature that never reliably delivered what it was for.
- The volume panel now auto-closes back to the bubble after 5 seconds with no interaction, instead of staying open until manually collapsed - dragging the bar, tapping nudge, or holding to open settings all reset the countdown, and it stands down entirely while the settings popup is open (an active configuration session doesn't get yanked away), resuming fresh once that's closed.

## 1.038
- Adds "which app is actually showing that popup" diagnostics for the head-unit report where the native volume UI still appears despite blocking: VolumeKeyAccessibilityService now also logs the package + class name of any window that shows up within 3 seconds of a volume press (not filtered by package name - a head unit's vendor popup won't necessarily be "com.android.systemui" the way it is on a Samsung, so guessing names up front would just miss it; filtered by timing instead, so normal navigation doesn't flood the log). No action is taken on what's found, purely identification - check "View trace log" after pressing a volume button on the head unit for a "Window shown ...ms after volume press: pkg=... cls=..." line. **Important:** Android caches an accessibility service's event subscriptions at the moment it's enabled, so this new logging won't start until the service is toggled off and back on in Android Settings > Accessibility after updating - reinstalling alone isn't enough.

## 1.037
- **Reverts 1.036's window-watch backstop** - tested live and it doesn't do what it was meant to. Confirmed (adb dumpsys window) that the native volume popup is a protected system window, and GLOBAL_ACTION_BACK simply does nothing to it - Android doesn't let an accessibility service dismiss system UI that way. Worse, that BACK press didn't fail silently: it was still delivered to whatever app actually had focus, so every volume tap while blocking was on was sending a spurious back-press into the foreground app (confirmed live - it backed the test device out to its home launcher). Removed entirely rather than leave a feature in that does nothing for the popup while quietly navigating the driver backward on every volume press. "Block system volume popup" itself (the key-side interception) is unaffected - back to exactly its 1.035 behavior.

## 1.036
- "Block system volume popup" gets a second layer for hardware where the native volume popup showed up anyway despite the block (the 1.034 head-unit report): confirmed live via `adb dumpsys window` that Android's own volume dialog is a window of its own (systemui, VolumeDialogImpl/VolumePanelDialogActivity depending on Android version), independent of whether the key press itself was ever caught - so on a head unit whose vendor volume HUD isn't wired through the standard key pipeline at all, catching the key was never going to be enough. VolumeKeyAccessibilityService now also watches for that window appearing and immediately backs out of it while blocking is on. Any unrecognized systemui window that shows up gets logged to the trace log too (not dismissed) - on unfamiliar head-unit firmware that line reveals the real class name to add, the same way key-press logging did for the held-button repeat fix.

## 1.035
- Removed the panel auto-peek: a volume change no longer opens the panel on its own. "Block system volume popup" still intercepts the hardware keys and applies the change silently exactly as before - only the panel's own reaction changed, and it now only ever updates its display when it's already open (a manual tap), never opening or closing itself.

## 1.034
- Diagnostics for "head unit's own volume popup still shows even with Block on": VolumeKeyAccessibilityService now writes to the trace log once per actual physical volume press (key name + whether the block toggle read as on at that moment) and once per resulting AudioManager.adjustStreamVolume() call (the stream's new value, proving the write actually landed). Check "View trace log" on the main screen right after pressing a volume button on the head unit - if both lines show up correctly there and the native popup still appears, that popup isn't tied to the standard Android volume key at all (some OEM launchers show their own volume indicator purely off the stream value changing, independent of who changed it or FLAG_SHOW_UI) and nothing at this app's level can suppress it. If neither line shows up, the accessibility service isn't actually intercepting on that hardware, which points to a different problem.

## 1.033
- "Block system volume popup": held-button repeat rate raised from every 130ms to every 40ms (~25 steps/second) - the 400ms delay before repeat starts (so a plain tap still only ever registers as one step) is unchanged.

## 1.032
- Fixed: the open-morph animation (1.031) always grew toward the "Themed" panel shape, ignoring whatever Panel background shape was actually selected on the Form tab (Rounded/Square/Pill/Clear) - it would morph toward the wrong shape for the whole animation, then visibly snap to the real one the instant it ended. On Clear it also briefly drew a background that shouldn't exist at all before it vanished. The animation now targets whichever shape is actually selected, and draws no background throughout on Clear, same as the settled state.

## 1.031
- Tapping the bubble now grows it open instead of instantly swapping to the panel - one window animating from the bubble's own size/position/shape to the panel's, its corner radii morphing from the bubble's fully rounded edge to the panel's own shape in lockstep, with the panel's actual content (readout/EQ bar/buttons) cross-fading in over the back half of the ~340ms run. Closing (collapse arrow, tap-outside, long-press) is unchanged - still instant; ask if you want that animated to match.

## 1.030
- Fixed: with "Block system volume popup" on, holding a volume button down only ever produced a single step, no matter how long it was held. An accessibility key filter sits ahead of the normal focused-window input queue, and Android's own key-repeat generation is tied to that queue - not guaranteed to reach a filter intercepting earlier, and in practice never did. VolumeKeyAccessibilityService now runs its own repeat timer instead of depending on OS-generated repeat events: one immediate step on a tap, then repeated steps every 130ms once held past 400ms, until release.

## 1.029
- Fixed: the main screen's "widget running"/"widget stopped" status (and Start/Stop button label) could show the old state right after tapping it, or right after the overlay auto-started on app open - VolumeOverlayService.start()/stop() only request the change, the flag the status text reads doesn't actually flip until the service's own onCreate()/onDestroy() runs a moment later. Neither string was wired backwards; the status is refreshed again shortly after now, to catch the settled state instead of a stale one.

## 1.028
- New "Block system volume popup" button on the main screen (off by default). When on, the hardware volume buttons no longer bring up Android's own volume popup at all - a new VolumeKeyAccessibilityService intercepts them directly and applies the change itself, silently. Whenever the volume changes this way (or from anywhere else), the panel now opens for 2 seconds if it wasn't already showing, then closes itself back to the bubble - Android's own popup, replaced. Turning the button on the first time walks you to Android's own Accessibility settings to grant it (a separate, more sensitive permission from overlay/notifications) - turning it back off never needs that screen again. A real interaction with the panel while it's peeking (dragging, nudging, holding) cancels the auto-close instead of yanking it away mid-use.

## 1.027
- New "LEDCAR Set" checkbox on the Theme tab: mirrors whatever color is currently set in FuZz LEDCAR, overriding the theme entirely once one arrives. Needs FuZz LEDCAR v1.036+ (it now announces its color to other FuZz apps whenever you pick one - swatch, RGB dialog, or the color wheel).

## 1.026
- New Form tab: 15 additional visual styles for the volume meter (Clear mode, Liquid fill, Dial, Speedometer, Minimal line, Chunky LED, Neumorphic, Glass, Dot column, Ring, Thermometer, Equalizer wave, Icon header, Compact pill, Retro LCD) alongside the original Classic EQ, all sharing the exact same drag behavior - only how it looks changes, never how it responds to a touch.
- Form tab also adds independent background-shape pickers for the volume panel and for the floating bubble (Themed / Rounded / Square / Pill / Clear) - Clear removes the card/bubble background entirely; when the bubble has no background the icon itself picks up the volume color instead, so that signal isn't lost.
- Size tab's "Bubble size" now shows a small live-scaled replica of the bubble next to the slider - the real bubble is hidden behind the panel while this popup is open, so without it the resize wasn't visible until everything closed.
- Fixed: switching to a taller settings-popup tab (Form's list, in particular) while the popup sat low on screen could push its bottom edge past the actual display - the window resizes on a tab switch but its position wasn't being re-checked, so the last few items had nowhere on-screen to scroll into. Re-clamped on every tab switch now, not just when the popup first opens.

## 1.025
- Conf tab now shows this device's own real media-volume ceiling under "Max volume supported" (e.g. many Samsung phones cap it at 15 steps). That's an Android/OEM hard limit no app can write past - independent of "max volume supported"/"limited to"/"when go slowly" - and was silently capping every drag/nudge below whatever those were set to, on some devices, with no explanation why.

## 1.024
- Fixed: reinstalling/updating the app while the overlay was running showed "Stop volume overlay" on the main screen with no bubble actually visible - the install kills the old process outright (no crash, no VolumeOverlayService.onDestroy(), no chance to clear the "running" flag), so the very next launch trusted a stale "true" left over from before. FuzzVolumeApp.onCreate() now clears it unconditionally at the top - it runs exactly once per process, so it running at all is itself proof any leftover "true" can't be real. The main screen now correctly shows "Start" and auto-starts the overlay again right away (same as it would for any other fresh launch with permissions already granted).

## 1.023
- "Volume panel height" (Size tab) is now capped at 80% of the actual screen height instead of a fixed 260dp, so it can never grow taller than the screen can sensibly show - the cap is computed live per device.
- Size/Conf tab sliders now write to disk with a synchronous commit() instead of the async apply() - only once per drag release (not on every intermediate tick, so dragging stays smooth), and again as a safety net whenever the settings popup closes. This device's OEM battery/security manager is known to kill this app's process aggressively; an in-flight apply() isn't guaranteed to survive that, commit() is.
- Fixed a latent crash risk in onDestroy() (runs on "Stop volume overlay" and as the tail of a failed startup): several fields, prefs included, were dereferenced unguarded - a sufficiently early startup failure would have thrown right there, in a method Android itself doesn't wrap in any try/catch. Fully defensive now.

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
