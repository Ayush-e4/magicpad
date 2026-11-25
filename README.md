# MagicPad

Use an Android phone as a Mac trackpad, with full macOS gestures. Made for Macs
that don't have a built-in trackpad, like the **Mac mini**, Mac Studio or an iMac
at a desk without a Magic Trackpad.

Two ways to run it:

- **Wi-Fi mode:** an Android app plus a Mac menu-bar app, paired and authenticated.
- **USB mode:** no root and no Android app needed, just `adb`.

## Requirements

- An Apple Silicon Mac (M1 or later) on macOS 13 or later. Intel Macs aren't supported.
- An Android phone, Android 8 (API 26) or later

To build from source you also need the Xcode command-line tools
(`xcode-select --install`) and the Android SDK. USB mode also needs `adb` and the
NDK, at `~/Library/Android/sdk` (override with `make SDK=/path/to/sdk`).

## Download (Wi-Fi mode)

Get `MagicPad-mac.zip` and `MagicPad.apk` from the
[latest release](https://github.com/Ayush-e4/magicpad/releases/latest).

1. Unzip `MagicPad-mac.zip` and move `MagicPad.app` to Applications. The app isn't
   notarized, so macOS blocks the first launch. Clear the download flag once:
   ```sh
   xattr -dr com.apple.quarantine /Applications/MagicPad.app
   ```
   Then open it. Give it **Accessibility** permission when macOS asks, and allow
   **Local Network** access.
2. Install `MagicPad.apk` on the phone. Your browser or file manager will ask you
   to allow installing unknown apps.
3. Put the phone and the Mac on the same Wi-Fi network. Open MagicPad on the phone,
   tap your Mac, and check that the pairing code matches on both screens.

## Getting the lowest lag (Wi-Fi mode)

The app already asks for low-latency Wi-Fi, the screen's highest refresh rate and
priority network packets. The network itself matters most:

- **Plug the Mac into Ethernet** if you can (a Mac mini usually can). Then only
  the phone's side of the trip is over the air.
- Use **5 GHz or 6 GHz** Wi-Fi, not 2.4 GHz, and keep the phone near the router.
- If the cursor stutters about once a second, it's usually AirDrop/Continuity
  scanning on the Mac (AWDL). Turning off AirDrop and Handoff while you use the pad
  helps.
- In USB mode none of this applies; it's the steadiest option.

## Wi-Fi mode from source

1. Build and open the Mac menu-bar app:
   ```sh
   make install-app    # builds dist/MagicPad.app, copies it to ~/Applications and opens it
   ```
   Give it **Accessibility** permission when macOS asks, and allow
   **Local Network** access.
2. Build and install the Android app with the phone plugged in (USB debugging on):
   ```sh
   cd android && ./gradlew installDebug
   ```
   You can also open `android/` in Android Studio and press Run.
3. Put the phone and the Mac on the same Wi-Fi network. Open MagicPad on the phone,
   tap your Mac, and check that the pairing code matches on both screens.

The menu-bar app has pointer speed, haptics, paired phones and start at login.
On the phone, the settings gear changes the pad background and auto-connect,
and there's a Quick Settings tile. The wire format is in [PROTOCOL.md](PROTOCOL.md).

## USB mode

```
phone touch chip ─► /dev/input/event6 ─► magicpad-grab ─► adb exec-out (USB)
                                                               │
macOS ◄─ CGEvents (cursor, scroll, gestures) ◄─ gesture engine ◄┘
```

- **`phone/grab.c`** runs on the phone as the adb `shell` user, which is in the
  `input` group. It takes exclusive control of the touchscreen (`EVIOCGRAB`),
  so Android ignores your touches, and streams the raw multitouch events.
  If the Mac goes away, it exits within 1 s and gives the touchscreen back.
- **`mac/`** is a Swift CLI. It decodes the stream, recognises gestures, and
  posts native macOS events, including the private gesture events the real
  trackpad uses.

## Gestures (v1)

| Fingers | Gesture | Action |
|---|---|---|
| 1 | move | cursor, with speed-based acceleration |
| 1 | tap / double / triple tap | click / double-click / triple-click |
| 1 | tap, then touch and drag | drag (move windows, select text) |
| 2 | tap | right-click |
| 2 | double-tap | Smart Zoom |
| 2 | slide | scroll, with momentum (follows your Natural Scrolling setting) |
| 2 | pinch / twist | zoom / rotate |
| 3 | tap | Look Up |
| 3 | drag | drag, if Three-Finger Drag is on in macOS |
| 3–4 | swipe left/right | switch Spaces / full-screen apps |
| 3–4 | swipe up / down | Mission Control / App Exposé |
| 4–5 | pinch in / out | Apps (Launchpad) / Show Desktop |

The phone buzzes on every click.

## Use

```sh
make            # builds bin/magicpad and bin/magicpad-grab
make run        # foreground; Ctrl-C to stop
make debug      # also prints every touch frame
make install    # background agent: starts at login, waits for the phone
```

1. On the phone, turn on **USB debugging** and plug it in.
2. Give macOS **Accessibility** permission to whatever runs magicpad: your
   terminal for `make run`, or `~/.magicpad/bin/magicpad` for the agent.
3. Hold the phone in **landscape with the camera on the left**. You can
   change this with `orientation` in the config.

While it runs, the phone's screen is dimmed and kept on, because the touch chip
turns off when the screen does. Your brightness is restored when you stop
magicpad.

## Config: `~/.magicpad/config.json`

`magicpad config` creates this file. Any key you leave out keeps its default.
Useful keys:

| Key | Default | |
|---|---|---|
| `orientation` | `landscape` | `landscape` (camera left), `landscapeFlipped`, `portrait` |
| `pointerSpeed` | 1.0 | overall cursor speed |
| `minGain` / `maxGain` | 2.5 / 16 | cursor points per mm at slow / fast finger speed |
| `scrollSpeed` | 1.0 | |
| `naturalScroll`, `threeFingerDrag` | macOS setting | set true/false to override |
| `tapToClick`, `smartZoom`, `momentum`, `haptics`, `dimScreen` | true | |
| `hapticMs` / `hapticAmplitude` | 14 / 180 | buzz length (ms) / strength (1–255) |
| `gestureMode` | `native` | `keys` switches Spaces etc. with ⌃-arrow shortcuts instead |
| `palmMajor` | 0 (off) | raw contact size above which a touch counts as a palm |

## Troubleshooting

- **Cursor doesn't move:** it's the Accessibility permission. Re-grant it after
  every rebuild or reinstall, because macOS ties it to the exact binary.
- **A swipe goes the wrong way:** flip the sign constants at the top of
  `GestureEngine.swift`. Test a single gesture with
  `bin/magicpad probe spaces-left|spaces-right|mission|expose|apps|desktop`.
- **The phone is unresponsive after a crash:** unplug it. The grab ends
  when the USB connection does.

## License

MIT, see [LICENSE](LICENSE).
