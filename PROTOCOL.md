# Link to Mac wire protocol (v1)

- **Transport:** TCP. The Mac listens on port 47820 (or any free port if that one is busy) and advertises over Bonjour as `_pixelbridge._tcp`. The phone connects.
- **Framing:** each frame is `[u32 big-endian length][payload]`, max 8 MiB.

## Pairing

The Mac holds a 16-byte random secret `S`. It shares it as:

- a QR code: `pixelbridge://pair?v=1&k=<base32 S>&p=<port>&n=<mac name>&h=<lan ipv4>`
- a manual code: RFC 4648 base32 of `S` without padding, in dash-separated groups of 4 (26 characters)

## Handshake

1. Phone → Mac (plaintext frame): `{"v":1,"nonce":"<base64 16 random bytes Nc>"}`
2. Mac → phone (plaintext frame): `{"v":1,"nonce":"<base64 Ns>"}`
3. Both sides derive `K = HKDF-SHA256(ikm=S, salt=Nc||Ns, info="pixelbridge-v1", L=32)`.
4. Every later frame is `AES-256-GCM(K, nonce, plaintext)` sent as `ciphertext||tag(16)`.
   - `nonce = [u32 BE direction][u64 BE counter]`
   - direction 0 = phone→Mac, 1 = Mac→phone
   - each direction's counter starts at 0 and goes up by 1 per frame
5. The phone sends a `hello` message first and the Mac answers with its own `hello`. If the phone's frame fails to decrypt, it used the wrong secret and the Mac closes the connection.

## Messages (decrypted plaintext)

Each message is `[u32 BE header length][UTF-8 JSON header][binary body]`. The header always has `"t"`.

| t | header | body |
|---|---|---|
| `hello` | `name`, `platform` (`mac`/`android`) | — |
| `ping` / `pong` | — | — |
| `clip_text` | — | UTF-8 text |
| `clip_image` | `mime` | image bytes (≤ 6 MiB) |
| `file_start` | `id`, `name`, `size` (−1 if unknown), `mime` | — |
| `file_chunk` | `id` | ≤ 256 KiB |
| `file_end` | `id` | — |
| `notif` (phone→Mac) | `key`, `app`, `appName`, `title`, `text`, `time`, `canReply` | app icon PNG |
| `notif_removed` (phone→Mac) | `key` | — |
| `notif_reply` (Mac→phone) | `key` | UTF-8 reply text |
| `notif_reply_result` (phone→Mac) | `key`, `ok` | — |
| `notif_dismiss` (Mac→phone) | `key` | — |
| `call` (phone→Mac) | `state` (`ringing` / `active` / `ended`), `key`, `name`, `detail`, `canDecline` | dialer icon PNG |
| `call_action` (Mac→phone) | `key`, `action` (`decline`) | — |
| `sms_threads_req` / `sms_threads` | reply may have `error` (`permission`) | JSON array of conversations |
| `sms_messages_req` / `sms_messages` | `threadId` | JSON array of messages, oldest first |

The phone sends `ping` every 20 seconds and the Mac answers with `pong`. A side that hears nothing for 60–70 seconds drops the connection, and the phone reconnects with backoff.
