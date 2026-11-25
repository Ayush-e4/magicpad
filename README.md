# MagicPad

Use an Android phone as a Mac trackpad over USB. No root and no Android app needed.

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
