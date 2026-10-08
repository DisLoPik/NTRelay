# NTR Remote-Play Protocol Notes

Target fork for v1: **classic NTR 3.6 / 3.6.1** and **NTR-HR** (the forks that share
the same command IDs and ports). HzMod / ChirunoMod are out of scope for v1.

All values below were extracted from the reference source, not from memory:

- **Chokistream** (`Eiim/Chokistream`, `src/main/java/chokistream/NTRClient.java` and
  `NTRUDPThread.java`) - primary reference, itself derived from Cell9/44670's NTRClient.
- **Snickerstream** (`RattletraPM/Snickerstream`) - original concept; NTR handling
  agrees with Chokistream.

**Status: confirmed against reference source AND the actual firmware source
(`44670/NTR` `source/ns/ns.c`; `xzn/ntr-hr` v0.3.8 `source/ns.c`,
`source/menu/rp_menu.c`, `source/nwm_rs`). Not yet confirmed against a live device
over the network.**
If a real-device test contradicts anything here, update this file.

## TCP command connection (port 8000)

84-byte header, **all integers little-endian** (NTR is an ARM device):

| Offset | Size | Field |
|-------:|-----:|-------|
| 0      | 4    | magic = bytes `78 56 34 12` (LE `0x12345678`) |
| 4      | 4    | seq (echoed in the reply) |
| 8      | 4    | type (1 if exdata follows, else 0) |
| 12     | 4    | cmd |
| 16     | 64   | 16 x 32-bit args |
| 80     | 4    | exdata length |
| 84     | ?    | exdata (only if length > 0) |

Byte order was verified against Chokistream's `intToBytes` / `bytesToInt`, which put
the least-significant byte first. A unit test pins the `78 56 34 12` layout.

### Commands used by the client

| cmd | Name | Notes |
|----:|------|-------|
| 0   | Heartbeat | seq = random 0..99; reply echoes cmd+seq, exdata = NTR debug output |
| 4   | Reload | unused by v1 |
| 10  | Write memory | used for NFC patch / input redirection; out of scope |
| 901 | Remote play | starts the stream, see below |

### Remote play (cmd 901)

Sent once on connect, re-sent on settings change. Header: `seq=3000`, `type=0`.

| Arg | Meaning | Encoding |
|----:|---------|----------|
| 0   | priority screen + factor | `(screenIsTop ? 1 : 0) << 8 \| (priority % 256)` |
| 1   | JPEG quality | 10..100 |
| 2   | QoS | `(qos * 2) << 16` (qos=101 disables QoS) |
| 3   | NTR-HR extension guard | `1404036572` - NTR-HR reads args 4..6 only if this matches |
| 4   | NTR-HR viewer port + format flags | low 16 bits = UDP port; high bits = Reliable Stream / lossless flags (0 = plain UDP + JPEG) |
| 5   | NTR-HR audio | 0 = off |
| 6   | NTR-HR top-screen width | 0 = standard 400 px |

`(qos * 2) << 16` is the same as `qos * 1024 * 1024 / 8` from the older protocol docs.
NTR-HR clamps it to 0.5..2.5 MiB/s.

Classic NTR ignores args 3..15. Without the extension, NTR-HR reuses whatever its
X+Y menu was last set to (viewer port, Reliable Stream/KCP, lossless) - formats this
client can't receive - so the client always sends args 3..6
(xzn/ntr-hr `source/ns.c` `nsHandleRemotePlay`, `source/menu/rp_menu.c`).

## Frame stream (UDP port 8001)

The client binds UDP **8001** by default (the 3DS sends frames to the client's IP on
that port after the TCP handshake - so the listener must be bound *before* the init
packet is sent, or the first frames are lost). NTR-HR sends to the port given in
arg 4; classic NTR always uses 8001.

NTR-HR learns the client's IP (and MAC) by watching for the first TCP packet it sends
**from port 8000** after remote play starts - the heartbeat replies provide it.

Datagram layout:

| Byte | Bits | Meaning |
|-----:|-----:|---------|
| 0    | all  | frame index |
| 1    | 7..4 | isLastPacket (1 = final packet of the frame) |
| 1    | 3..0 | screen: bit 0 = top (NTR-HR full-width mode also sets bits 1-2) |
| 2    | all  | `2` (NTR-HR also packs downsample/lossless bits here) |
| 3    | all  | packet index within the frame |
| 4+   |      | compressed payload |

Frames are JPEG (verified via Chokistream's `ImageIO.read` on the assembled payload).
Datagrams are at most 1448 bytes, header included (`PACKET_SIZE`).
Payloads are stitched per screen; a lost or out-of-order packet drops the in-progress
frame and assembly resumes at the next packet 0, which carries the JPEG headers
(matches Chokistream's flush behavior).

### Orientation

NTR sends each screen rotated 90 degrees (portrait): top = 240x400, bottom = 240x320.
Clients rotate 90 degrees CCW for display, mapping `out(x, y) = in(W-1-y, x)` (W = source
width, 240) to yield **top 400x240, bottom 320x240**. Implemented in
`render/FrameDecoder.kt`.

### Color

Default decode (no adjustment) is correct for NTR. Chokistream's `VC_BLUE_SHIFT`
is marked "Test"/broken upstream and is not implemented. Grayscale is a novelty
filter. (HzMod needs an R/B channel swap - out of scope for v1.)

## Firmware behavior (44670/NTR and xzn/ntr-hr source)

- `nsMainLoop` listens on **TCP 8000**, accepts **one connection at a time**
  (`listen(1)`), and reads exactly 84-byte headers. A stale client connection
  blocks new ones until it dies - reconnect loops can starve the console.
- `nsHandleRemotePlay` validates `quality` (10..100) and rejects with
  `"remoteplay is available on new3ds only"` on Old 3DS or
  `"remote play already started"` if a stream is active - **no error reply is
  sent**; the connection just stays open and silent. Both strings appear in
  the heartbeat debug log, which the app surfaces in the UI.
- Heartbeat replies always echo the client's seq and carry NTR's debug buffer
  (possibly empty) as exdata - the client drains and displays it.
- The UDP stream is built by hijacking the home-menu network stack; frames are
  JPEG with **top = 240x400, bottom = 240x320** (portrait; clients rotate 90 degrees CCW).
- Classic NTR always sets datagram byte 2 to `2`; packet index is byte 3.
- The TCP port is **always 8000** (`NS_MENU_LISTEN_PORT`, hard-coded) in NTR-HR too.
  What its menu lets you change is the **viewer (UDP) port** - the app's port field
  is that one, and it is sent in arg 4.

## Latency / robustness notes

- One heartbeat in flight at a time, ~1 s apart; a slow reply is waited for, never
  abandoned mid-packet (that would misalign every later header).
- TCP drop -> close the old socket (NTR serves one client at a time), re-open,
  re-send remote play, up to 3 retries.
- Watchdog in `service/StreamSession.kt`: no frame within 10 s of the handshake =>
  the stream never started; no frame *and* no heartbeat reply for 15 s => the 3DS is
  gone. Frames alone may pause legitimately (NTR-HR skips unchanged frames; the
  3DS sleeps with the lid closed), so that is not fatal.
- Android 17+: apps targeting SDK 37 need the `ACCESS_LOCAL_NETWORK` runtime
  permission, or all LAN traffic is dropped (TCP connects just time out).
- The foreground service + partial wake lock keep the stream alive in background.

## Open questions (needs a real 3DS)

- Whether classic NTR 3.6 replies to cmd 901 (NTR-HR does not; the client drains
  any reply either way).
- Exact priority-factor range accepted by the firmware (client sends 1..9).
