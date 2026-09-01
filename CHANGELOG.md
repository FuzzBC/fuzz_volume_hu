# Changelog

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
