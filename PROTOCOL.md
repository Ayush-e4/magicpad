# MagicPad Wi-Fi protocol, v1

The phone app (`android/`) sends raw touches. The Mac app (`mac/`) runs the
gesture engine and posts macOS events. All gesture logic stays on the Mac.

## 1. Discovery

The Mac advertises a Bonjour service:

- type `_magicpad._tcp`, name = the Mac's computer name
- TXT: `v=1`, `id=<mac id, 32 hex chars>`

The phone lists these with `NsdManager` and connects to the TCP port.

## 2. Control channel (TCP)

Each message is a UTF-8 JSON object, prefixed by its byte length as a
**u16, big-endian**. Unknown keys are ignored. Either side closes the socket
to end the session.

### Handshake

Both sides make a fresh **P-256** key pair per connection. Public keys are
sent as base64 of the **DER SubjectPublicKeyInfo** (Java `PublicKey.encoded`,
CryptoKit `derRepresentation`). Nonces are 16 random bytes, base64.

1. phone → Mac:
   `{"t":"hello","v":1,"device":"<32 hex>","name":"Galaxy S21 FE","pub":"…","nonce":"…"}`
2. Mac → phone:
   `{"t":"hello","v":1,"id":"<32 hex>","name":"My MacBook","pub":"…","nonce":"…","known":true|false}`
   `known` says whether the Mac has a pairing key for this `device`.

Both compute `shared` = ECDH(own private, other public) (32 bytes, raw X
coordinate) and `salt = phoneNonce ‖ macNonce` (32 bytes).

`HKDF(ikm, info, n)` below is HKDF-SHA256 (RFC 5869) with that salt.

### Pairing (only when `known` is false, or the phone has no key for this Mac)

3. Both show the 6-digit code
   `code = u32be(HKDF(shared, "magicpad v1 code", 4)) mod 1000000`, zero-padded.
   The phone sends `{"t":"pair"}` and shows "Confirm 123 456 on your Mac".
4. The Mac asks the user (Allow / Deny, showing the phone name and code).
   - Allow → `K = HKDF(shared, "magicpad v1 pair", 32)`. Both store `K`
     (Mac: per device id, phone: per Mac id). Mac sends `{"t":"paired"}`.
   - Deny, or no answer in 60 s → `{"t":"denied"}` and close.

If the phone has a key but the Mac says `known:false` (the Mac forgot it), the
phone drops its key and pairs again. If the Mac knows the device but the phone
has no key, the phone sends `{"t":"pair"}` anyway and the Mac pairs again.

### Authentication (every connection, after pairing if any)

5. phone → Mac: `{"t":"auth","proof":b64(HMAC-SHA256(K, "phone" ‖ transcript))}`
6. Mac verifies, then → phone:
   `{"t":"ready","proof":b64(HMAC-SHA256(K, "mac" ‖ transcript)),"udp":<port>,"sid":<u32>}`
   On a bad proof the Mac sends `{"t":"denied"}` and closes.
   The phone verifies the Mac's proof, then closes on a mismatch.

`transcript = phonePub ‖ macPub ‖ salt` (raw DER bytes, not base64).

Session key: `S = HKDF(shared ‖ K, "magicpad v1 session", 32)`.

### After `ready`

- Mac → phone `{"t":"buzz","k":"<kind>","s":<strength>}`: play one haptic.
  `s` is 0.0–1.0 (missing → 0.7). `k` (missing or unknown → `click`):

  | `k` | When | Feel |
  |---|---|---|
  | `click` | tap = left click | short crisp click |
  | `heavy` | right click | firmer, deeper click |
  | `drag` | a drag starts (tap-drag, three-finger drag) | soft "lift" |
  | `confirm` | Spaces switch, Mission Control, Smart Zoom, Look Up… | two quick clicks |
  | `tick` | every few lines of scrolling (if enabled on the Mac) | very light tick |

  The Mac sends nothing when haptics are off.
- Either side `{"t":"ping"}` every 2 s. No message for 6 s → close.
- phone → Mac `{"t":"bye"}` before a clean close (optional).

## 3. Touch frames (UDP, phone → Mac)

Sent to the Mac's address on the `udp` port from `ready`. Big-endian. The phone
marks these packets DSCP EF (46), which Wi-Fi sends in the voice queue.

| Offset | Size | Field |
|---|---|---|
| 0 | 2 | magic `0x4D 0x50` ("MP") |
| 2 | 1 | version `1` |
| 3 | 1 | type `1` = frame |
| 4 | 4 | `sid` from `ready` |
| 8 | 4 | `seq`, starts at 1, +1 per packet |
| 12 | 4 | `t`, ms on the phone's monotonic clock (wraps fine; Mac uses differences) |
| 16 | 2 | pad width, 0.01 mm units |
| 18 | 2 | pad height, 0.01 mm units |
| 20 | 1 | `n`, number of touches (0–10) |
| 21 | 6·n | per touch: `id` u8, `x` u16, `y` u16 (0.01 mm), `major` u8 (0.1 mm, capped 255) |
| end | 16 | tag = first 16 bytes of HMAC-SHA256(S, all bytes before the tag) |

- Coordinates: origin top-left of the touch surface as the user holds it,
  x to the right, y down, in millimetres.
- Each frame is the **full** set of touches down right now (not a delta), so a
  lost packet only loses one sample.
- The phone sends a frame on every touch event (unbatched), plus a repeat of
  the current frame every 50 ms while any finger is down, plus the empty frame
  (`n = 0`) three times, 10 ms apart, when the last finger lifts.
- The Mac drops packets with a bad tag, an unknown `sid`, or `seq` ≤ the last
  accepted one. If fingers are down and no frame arrives for 400 ms, the Mac
  treats it as all fingers lifted.
