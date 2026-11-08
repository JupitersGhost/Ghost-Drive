# Nullvalon BLE Wire Protocol (`0xC7A8`)

This document specifies the binary BLE GATT protocol between the `esp-firmware-companion` (`ESP32-C3` + `HLK-LD2410C`) and the Ghost Drive Android client (`NullvalonProtocol.kt` / `NullvalonClient.kt`).

The full Rust firmware implementation is located in `esp-firmware-companion/src/`. There is no JSON-over-BLE mode.

## 1. GATT Service & Characteristics

Advertising name: `NULLVALON` (the advertising name alone is never trusted to start GPS tracking).

| Role | UUID | Operations |
| --- | --- | --- |
| Service | `c7a80000-0dab-4d03-9a0b-a000a0000001` | Service discovery |
| Telemetry | `c7a80001-0dab-4d03-9a0b-a000a0000001` | Read / notify |
| Challenge | `c7a80002-0dab-4d03-9a0b-a000a0000001` | Write of 16 random challenge bytes |
| Proof | `c7a80003-0dab-4d03-9a0b-a000a0000001` | Read / notify of 16-byte HMAC proof |

Android connects without OS-level Bluetooth bonding, serializes CCCD descriptor subscriptions (subscribing to `Proof` first and `Telemetry` second), inspects an initial authenticated frame to learn the current `boot_id`, writes a fresh 16-byte `SecureRandom` challenge nonce, and verifies the 16-byte proof. Phone GPS and continuous phone sensor harvesting remain disabled until proof verification succeeds. When authenticated telemetry ceases for **45 seconds** (missing-telemetry watchdog), the session closes and GPS stops.

> **Development-Only Zero-Key Warning**: Out of the box, the development build accepts an all-zero 32-byte protocol key (`0x00 * 32`) alongside provisioned keys so contributors can exercise `Postcard` decoding and challenge/proof flows on the bench without provisioning hardware secrets. **The all-zero key is strictly a development-only test mode and does not authenticate device identity or provide confidentiality.** Always provision a unique 32-byte key (`esp-firmware-companion/tools/provision-key.sh`) for real deployments.

## 2. Fragmented 20-Byte Chunks & HMAC-Authenticated Envelope

Every BLE notification fits within 20 bytes (compatible with the default 23-byte ATT MTU):

| Offset | Length | Meaning |
| --- | --- | --- |
| 0 | 1 | Magic byte `0xA7` |
| 1 | 1 | Protocol version `1` |
| 2 | 4 | Sequence number (`u32` little-endian) |
| 6 | 1 | Zero-based fragment index (`u8`) |
| 7 | 1 | Total fragment count (`u8`) |
| 8 | 1 | Fragment payload length, `1..11` (`u8`) |
| 9 | up to 11 | Fragment payload bytes (remaining bytes zero-padded) |

Reassembled envelope:

`boot_id:u32LE || sequence:u32LE || postcard(Message) || mac_tag:16B`

- **Telemetry MAC Tag**: First 16 bytes of `HMAC-SHA3-256(protocol_key, "NULLVALON-telemetry-v1\0" || body_without_tag)`.
- **Challenge Proof MAC Tag**: First 16 bytes of `HMAC-SHA3-256(protocol_key, "NULLVALON-auth-v1\0" || nonce:16B || boot_id:u32LE)`.

*(Note: `HMAC-SHA3-256/16` is a symmetric message authentication code (MAC) verifying integrity and shared-key authenticity, not an asymmetric digital signature.)*

The Android parser bounds lengths, pending packet count, and fragment lifetime; rejects conflicting chunks; verifies the `HMAC-SHA3-256/16` tag before parsing any `Postcard` fields; checks header/body sequence equality; rejects duplicate `(boot_id, sequence)` frames within the session; and logs sequence gaps. A `boot_id` change forces re-authentication.

## 3. Postcard Field Order

`Postcard` is a non-self-describing binary format:
- `f32` values are little-endian IEEE-754 floats.
- `u16`, `u32`, `u64` use unsigned LEB128 varints; `u8` and `i8` are one raw byte.
- Strings and byte vectors are varint-length-prefixed.
- Booleans and `Option<T>` tags use `0` / `1`.
- **Serde enum variant indices start at zero** (`SourceId` serialized variant indices are `0..6`).

Message variants, in order:

0. `Boot`: `version:u8`, `boot_id:u32`, `device:String`
1. `Progress`: `SourceId`, `Health`, `filled:u16`, `needed:u16`
2. `Source`: `SourceReport`
3. `Pool`: `PoolReport`
4. `WifiSummary`: `ap_count:u8`, `mean_rssi:i8`, `scan_duration_ms:u32`
5. `Diagnostic`: `uptime_ms:u64`, `free_heap:u32`, `radar_frames:u64`, `radar_errors:u64`, `ble_connected:bool`
6. `WifiMotion`: `score:u8`, `matched_aps:u8`, `previous_aps:u8`, `current_aps:u8`
7. `RadarSummary`: `engineering:bool`, `target_state:u8`, `moving_energy:u8`, `stationary_energy:u8`, `moving_distance_cm:u16`, `stationary_distance_cm:u16`, `detection_distance_cm:u16`

Unknown variants, trailing bytes, invalid varint encodings, and non-finite floating-point metric values are rejected.

## 4. Verification Tests

Kotlin unit tests (`ProtocolTest.kt`) verify field interpretation, enum indices, negative RSSI, HMAC/proof equality, reversed fragment ordering, tampering rejection, wrong keys, invalid bounds, and schema mismatch against `protocol-vectors.json`.
