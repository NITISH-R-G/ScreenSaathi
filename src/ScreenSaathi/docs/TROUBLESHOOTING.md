# Troubleshooting

## For users

| Problem | Fix |
| --- | --- |
| Mic not working | Settings → Apps → ScreenSaathi → Permissions → Microphone |
| Assistant not showing | Settings → Apps → Special app access → Display over other apps → ScreenSaathi |
| Nothing gets highlighted | Settings → Accessibility → ScreenSaathi → Enable |
| Stopped working after reinstall | Android disables the Accessibility Service on every reinstall. Re-enable it — this is platform behaviour, not a bug |
| Home button doesn't open ScreenSaathi | Settings → Apps → Default apps → Home app → ScreenSaathi |
| Nothing happens when speaking | Check network; confirm `sarvam.api.key` is set if you built from source |

## For developers — misleading device state

These cost real debugging time during development. Check them before
assuming a code change caused a regression.

**`am force-stop com.screensaathi` disables the Accessibility Service.**
Every downstream symptom — stuck "Thinking…", `elements=0`, `readerInstance=false`
— traces back to this if you force-stopped the app mid-test. Only
force-stop *other* apps to reset their state.

**Reinstalling also disables the Accessibility Service** (same root cause,
different trigger). Re-run:
```bash
adb shell settings put secure enabled_accessibility_services \
  com.screensaathi/com.screensaathi.ScreenReaderService
adb shell settings put secure accessibility_enabled 1
```

**Reinstalling can reset the default launcher.** If `KEYCODE_HOME` starts
landing somewhere unexpected, check:
```bash
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME
```
and reset if needed:
```bash
adb shell cmd package set-home-activity com.screensaathi/.launcher.LauncherActivity
```

**Never guess touch coordinates from a screenshot.** The overlay window is
positioned in real screen pixels, but a screenshot's displayed size (from a
scaled screen-share/tool) is not 1:1 with device pixels, and the window's
own frame is authoritative — not where it *looks* like it is in a downscaled
capture. Always read the real frame first:
```bash
adb shell dumpsys window windows | grep -A20 "Window{.*com.screensaathi}:" | grep -E "frame=|mAttrs="
```
then compute the tap target from that rectangle, not from pixel-counting a
screenshot.

**`dumpsys battery unplug` and toggling airplane mode both destabilized
demo sessions** during development (device dozing, network dropped mid-STT
call). Don't use either while testing the app.

**A real incoming phone call or the lock screen will silently block input
delivery** — a screencap during either returns a near-empty (~15KB) PNG.
Check `adb shell dumpsys power | grep mWakefulness` and
`adb shell dumpsys window | grep mKeyguardUnlocked` if a screenshot looks
suspiciously blank.

**PhonePe (and similar apps) can present their own security lock** after the
screen has been idle. This is not bypassed by design — unlock it manually
and continue the test.

## Build failures

**`SDK location not found`** — `local.properties` is missing or has no
`sdk.dir`. Copy `local.properties.example` and set it.

**`Invalid file path` from a `sdk.dir` with backslashes** — use forward
slashes even on Windows: `C:/Users/you/AppData/Local/Android/Sdk`.

**Fresh-clone build fails but the working directory builds fine** — almost
always a file the working directory has locally but isn't tracked in git
(most often `local.properties` itself, correctly gitignored, or a stale
Gradle cache masking a missing dependency). Test from an actual fresh clone,
not just `git clean`.
