# Changelog

## 1.002
- Fixed: first launch now actually asks for permission. SYSTEM_ALERT_WINDOW has no system popup of its own - the app now sends you straight to that Settings screen on first open instead of waiting for someone to notice the "Grant permission" button. Also requests POST_NOTIFICATIONS (Android 13+) so the ongoing "can't be killed" notification actually shows.

## 1.001
- First release: floating half-circle volume tab (Soft Neumorphic skin), drag to reposition (10%-90% of the screen edge, flips side past center), tap to open the panel.
- EQ Segments bar + ball, capped at 25 even when the head unit's own max is higher - drag never passes 20, the nudge arrow (500ms cooldown) steps 21-25.
- Hold the volume number for 5s to open the 24-color theme popup; picking a color previews live, stays open until you tap Done.
- Hold the tab to close the app. Foreground service + ongoing notification keep it running until you do.
- Reopens at the last position on launch and after a reboot.
- Checks FuzzBC/fuzz_volume_hu on GitHub for updates on launch.
