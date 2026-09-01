# FuZz Volume HU

A floating volume control for Android car head units. A half-circle tab
sits docked to the left or right edge of the screen, draggable up and down
(clamped to the middle 80% - never in the top/bottom 10%) and flips edges
when dragged past center. Its color shifts from green to red as volume
rises. Tap it to open a panel with the current level, an EQ-segments bar +
ball to drag it, and a 5-second hold on the number for a 24-color theme
picker. Hold the tab itself to close the app.

The panel's bar is capped at **25**, even if the head unit's own volume
range goes higher - dragging never passes 20, a nudge arrow (with a 500ms
cooldown) steps the rest of the way to 25. The number always shows the
*real* system volume, though, even above 25 if something else (the nav
app, hardware buttons) changed it - only what this widget itself sets is
ever capped.

It runs as a foreground service with an ongoing notification so Android
won't kill it for memory pressure or a recents-list swipe, and relaunches
itself after the head unit reboots. Long-pressing the tab is the actual
way to stop it - Android's own App Info screen is the only thing that can
override that.

## Install

Grab the latest `.apk` from [Releases](https://github.com/FuzzBC/fuzz_volume_hu/releases),
copy it to the head unit, and install it directly (enable "install unknown
apps" for whatever file manager/browser you use). On first launch it'll
ask for the "display over other apps" permission - without that the tab
can't be drawn.

The app checks this repo for a newer release on every launch and offers to
download it if one exists.

## Build

Standard Gradle Android project.

```
./gradlew assembleRelease
```

`assembleRelease` also publishes the built APK as a GitHub release
(`publish_release.ps1`, via the `gh` CLI) and bumps `version.properties`
for the next build - see that script and `app/build.gradle`.
