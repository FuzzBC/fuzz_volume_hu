# Changelog

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
