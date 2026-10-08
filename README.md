# NTRelay

Stream a New Nintendo 3DS / 2DS screen to an Android phone over Wi-Fi, using the
remote play feature of NTR CFW (classic NTR 3.6 / 3.6.1 and NTR-HR).

NTRelay is a native Android viewer for the same protocol PC tools such as
Snickerstream, Chokistream and NTRViewer-HR use. It shows both 3DS screens, top above
bottom, and keeps streaming in the background.

## Features

- Both screens, decoded and rotated to landscape (top 400x240, bottom 320x240)
- Adjustable quality, QoS, priority screen and stream port
- Keeps streaming while the app is in the background (foreground service)
- Built-in connection test, plus NTR's own debug log shown when something fails
- Detects a stream that never starts or a 3DS that drops off the network
- Works with NTR-HR's custom viewer port, and resets NTR-HR to a stream format
  this app can read

## Requirements

**3DS**

- A **New** 3DS, New 3DS XL or New 2DS XL (remote play is New-model only)
- Custom firmware (Luma3DS) with NTR 3.6, NTR 3.6.1 or
  [NTR-HR](https://github.com/xzn/ntr-hr) installed. NTR-HR is recommended: it
  streams at a much higher frame rate.

**Phone**

- Android 8.0 (API 26) or newer
- On the same Wi-Fi network as the 3DS. The 3DS only supports 2.4 GHz, so the
  router must offer it, and it must not isolate wireless clients from each other.

## Setup

### 1. Start NTR on the 3DS

1. Launch NTR (for NTR-HR: the `BootNTRSelector` app from its releases page).
2. Make sure the 3DS is connected to Wi-Fi.
3. Find the 3DS's IP address. With NTR-HR, press **X + Y** to open the NTR menu and
   open **Remote Play**: the title shows `Remote Play: <IP address>`. Otherwise look
   it up in your router's list of connected devices.
4. Start a game, or stay on the HOME menu - both can be streamed.

### 2. Install the app

Build it from source (see [Building](#building)) or install a prebuilt
`app-debug.apk` on the phone.

### 3. Connect

1. Open NTRelay and enter the 3DS's IP address.
2. Tap **Test**. On Android 17 and newer, allow the local network ("Nearby
   devices") permission when asked - without it Android blocks all traffic to the
   3DS. You should see `OK: <ip>:8000 answers`.
3. Tap **Connect**. Allow notifications if asked (they show while streaming).
4. The stream should appear within a few seconds. Tap **Disconnect** to stop.

## Settings

| Setting | Default | Meaning |
|---------|---------|---------|
| Stream port | 8001 | UDP port the 3DS streams to. NTR-HR uses whatever port you set here; classic NTR always uses 8001. |
| Quality | 70 | JPEG quality, 10 - 100. Higher looks better but lowers the frame rate. NTR-HR recommends 75 - 90. |
| Priority factor | 4 | How many frames of the priority screen are sent for each frame of the other screen. |
| QoS | 16 | Bandwidth target, roughly in Mbit/s. NTR-HR accepts about 4 - 20; higher values cause packet loss. 101 disables QoS on classic NTR. |
| Priority screen | Top | The screen that gets the higher frame rate. |
| Color mode (Settings) | Regular | Regular is correct for NTR. Grayscale is a novelty filter. |

All settings are saved on the phone and sent to the 3DS each time you connect.

## Troubleshooting

When a connection fails, the error card shows the reason and the last lines of
NTR's own debug log.

| Message | What to do |
|---------|------------|
| `NTRelay needs local network access` | Android 17+ only. Allow it under Settings > Apps > NTRelay > Permissions (Nearby devices). |
| `Refused - nothing is listening` | NTR is not running, or the IP belongs to another device. Start NTR (or reboot and start it again) and check the IP. |
| `Timed out - no reply` / `No response from ...` | The phone can't reach the 3DS. Check the IP, turn off any VPN, make sure both are on the same network, and that the router doesn't isolate wireless clients (common on guest networks). |
| `Stream timed out - ... no frames arrived` | The 3DS accepted the connection but didn't stream. Read the NTR lines under the error. If a PC viewer was streaming, close it and connect again. |
| `NTR: remote play already started` | Classic NTR only starts remote play once per boot. Reboot the 3DS, start NTR, and connect again. |
| `NTR: remoteplay is available on new3ds only` | Remote play needs a New 3DS / New 2DS. |
| `UDP port ... is already in use` | Another app uses the stream port. Close it or pick another stream port (NTR-HR only). |
| `Lost the 3DS` | The 3DS left the network or went to sleep for more than 15 seconds. |
| Stream stops after 6 hours | Android 15+ limits this kind of background service to 6 hours a day. |

Some games are not compatible with NTR streaming, and DS / GBA games can't be
streamed at all. A dedicated 2.4 GHz access point with no other devices on it gives
the smoothest stream.

## How it works

1. The phone binds the UDP stream port (8001 by default).
2. It opens a TCP connection to the 3DS on port 8000 and sends the **remote play**
   command (cmd 901) with the stream settings and, for NTR-HR, the viewer port.
3. It sends a heartbeat (cmd 0) about once a second. NTR answers each one with its
   debug log, and NTR-HR learns the phone's address from these replies.
4. The 3DS sends each screen as a JPEG split over UDP datagrams. The app reassembles
   the datagrams into frames, decodes them, rotates them into landscape and draws
   them on a `SurfaceView`.

The full protocol, with the firmware source it was checked against, is in
[docs/protocol-notes.md](docs/protocol-notes.md).

## Building

Requirements:

- Android Studio with the Android SDK **platform 37** installed
- JDK 17 or newer to start Gradle (the build provisions its own JDK toolchain)

```sh
./gradlew assembleDebug        # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on a connected device
./gradlew testDebugUnitTest    # run the unit tests
./gradlew lintDebug            # run Android lint
```

On Windows use `gradlew.bat` instead of `./gradlew`.

## Project structure

```
app/src/main/java/com/dislopik/ntrealy/
  MainActivity.kt              App entry point and screen switching
  network/
    ConnectionState.kt         Idle / Connecting / Connected / Error
    LocalNetwork.kt            Android 17 permission check, Wi-Fi network lookup
    NTRSocketClient.kt         TCP connection: remote play, heartbeats, reconnects
    NTRUdpReceiver.kt          UDP socket that receives frame datagrams
  protocol/
    NTRCommands.kt             Ports, command IDs and other protocol constants
    NTRPacket.kt               84-byte command header encoding and parsing
    FrameAssembler.kt          Rebuilds JPEG frames from datagrams
    ScreenId.kt                Top / bottom screen
  render/
    FrameDecoder.kt            JPEG decode, rotation, color mode
    FrameRenderer.kt           Draws both screens on a render thread
    StreamSurfaceView.kt       SurfaceView hosting the renderer
  service/
    StreamSession.kt           The streaming pipeline and watchdog
    StreamForegroundService.kt Keeps the stream alive in the background
  ui/
    connect/ConnectScreen.kt   IP, settings, Test and Connect
    stream/StreamScreen.kt     Live stream with status overlay
    settings/SettingsScreen.kt Color mode
    SettingsStore.kt           Saved settings
    StreamConfig.kt            Stream settings model
app/src/test/                  Protocol unit tests
docs/protocol-notes.md         Protocol reference
```

## Limitations

- No audio, and no input redirection (touch or buttons) back to the 3DS
- Old 3DS / 2DS models are not supported (no remote play)
- HzMod and ChirunoMod are not supported
- NTR-HR's Reliable Stream, lossless and full-width modes are not supported; the
  app switches NTR-HR to plain UDP + JPEG when it connects

## Credits

- cell9 / 44670 - NTR CFW and the original remote play protocol
- xzn - NTR-HR
- Eiim and contributors - Chokistream, the main protocol reference
- RattletraPM - Snickerstream
