# Private server data contract

Ghost Drive records locally first. Configure history/metrics and entropy URLs independently in Settings. Both require bearer tokens and an active VPN route to a Tailnet IPv4 address (`100.64.0.0/10`) or Tailscale IPv6 prefix (`fd7a:115c:a1e0::/48`). URL hostnames must resolve through the VPN to those address spaces. Each flush pins those validated addresses and uses VPN-bound sockets while retaining the hostname for HTTPS certificate verification; redirects and system proxies are disabled. HTTP is permitted only over this validated private path; use HTTPS if your server has a valid certificate.

## Requests

`POST` JSON with `Authorization: Bearer <configured token>` and `Content-Type: application/json`.

```json
{
  "schema_version": 1,
  "event_id": "unique UUID",
  "device_id": "persistent per-install UUID",
  "vehicle": "Vehicle",
  "driver": "Driver",
  "captured_at_ms": 0,
  "type": "position",
  "payload": {}
}
```

HTTP 2xx means durable receipt. Retrying the same event ID must not create duplicates. Non-2xx / timeout leaves the event queued with exponential backoff. Receivers should not use bare 409 for an already accepted duplicate; return 2xx with its original acknowledgement.

| Type | Payload | Destination |
| --- | --- | --- |
| `trip_started` | Trip object | History |
| `position` | trip_id, fix, current Trip snapshot | History |
| `trip_ended` | Closed Trip object | History |
| `trip_updated` | Closed Trip with local nearby-place labels | History |
| `telemetry` | Source, Pool, Boot, Wi-Fi, radar, or diagnostic metrics; firmware packet, boot/sequence | History |
| `conditioned_entropy` | Accepted Pool metrics and 64-byte digest | Entropy |

Fix fields: latitude, longitude, time_ms (UTC epoch), elapsed_ms (phone monotonic clock), accuracy_m, speed_mps (nullable). Trip fields: id, start_ms/end_ms, duration_ms, moving_ms, stationary_ms, unobserved_ms, distance_m, start/end fixes, start_name/end_name, end_reason, boot_id, parking_before_ms, demo. Unobserved time includes initial GPS acquisition, long gaps, and time after the last fix. It is not automatically classified as stationary. Progress notifications are shown live but not added to the upload queue.

Telemetry retains the authenticated firmware envelope as `firmware_packet_base64` for downstream verification. The HMAC key is never forwarded. Your receiving daemon needs the key through its own private provisioning to independently verify the firmware envelope. The optional receiver validates the transport schema but does not perform ESP32 HMAC verification; authenticated Android has already verified it. Production entropy acceptance should independently verify and re-test/further condition supplemental inputs according to Jupiter Labs policy.

Pool payload includes `conditioned_digest_hex` (128 hex characters / 64 bytes), source mask, fresh source, newly accepted byte count, window ID, all pool diagnostics, and credited_bits. Credited bits remain zero. Never interpret SHA3 output statistics or acceptance percentages as proven entropy.

## Optional Private Receiver Setup

Ghost Drive works 100% standalone on-device without a server. If you choose to host your own private Tailscale/Headscale or LAN ingest server, expose:

- `POST /v1/events` (for trip and telemetry events)
- `POST /v1/entropy` (for conditioned entropy blocks)
- `GET /v1/archive` and `POST /v1/archive` (for optional cross-device archive sync)

Wait for durable SQLite/database commit before returning HTTP `2xx`, and deduplicate by `event_id`.

## Privacy and freshness behavior

- Disconnected GPS is off, regardless of sync policy.
- Strict default: only upload during verified BLE connection; final endpoint remains local until next connection.
- Optional setting: cached records may upload after disconnect. No new GPS fixes are obtained.
- Simulator data never reaches either queue or network.
- Public map downloads contain no travel coordinates. Map display and local place lookup are offline.
- Opening an external map app is an explicit user action handing that selected coordinate to that app.
- Web and Discord integrations must show last received time and avoid suggesting an old position is live.
- Discord later consumes structured facts. An LLM should narrate known trips and inferred parking intervals, not invent destinations or claim driver presence from car location alone.

## Queue / retention limits

History remains in app-private SQLite. Trips default to 90 days; set 0 for indefinite retention. Logs default to 30 days; locally retained metric observations to two days. Recent in-app history/log lists show 500 records; full retained data is exported.

Offline pending entropy is capped at 5000 records and history telemetry at 10,000 pending records. New telemetry beyond those thresholds is not queued and the UI displays backpressure. Location and trip records are not deleted by telemetry backpressure. History retention and pending uploads are independent: queued records remain deliverable after local-view retention pruning.
