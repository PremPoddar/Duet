<div align="center">

# Duet

**Same song, same moment — wherever you both are.**

Listen to music together with up to seven other people, on the same couch or in different cities.

![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin&logoColor=white)
![WebRTC](https://img.shields.io/badge/WebRTC-peer--to--peer-333333?logo=webrtc&logoColor=white)
![License](https://img.shields.io/badge/License-Apache%202.0-blue)

</div>

---

Start a session and Duet gives you six words. Read them out on a call, let someone scan the code,
or send the link. Pick a song from your phone and it plays on everyone's phone at the same moment.
Anyone can pause, skip, seek or add their own songs.

## What it does

| | |
|---|---|
| 🎶 **In sync** | Within a few tens of milliseconds, kept there continuously. |
| 🌍 **Anywhere** | Phones connect directly over the internet; no account, no server of ours. |
| 🛋️ **Same room** | On the same Wi-Fi, sessions show up by themselves and work with no internet at all. |
| 🎛️ **Everyone's in control** | Play, pause, skip, seek, reorder — for everybody. |
| ➕ **Shared queue** | Add songs from your own phone; they're sent to the others. |
| 💬 **Chat & reactions** | Floating emoji over the record. |
| 💓 **Thinking of you** | Tap the heart and their phone beats. |
| 🔒 **Lock screen controls** | Keeps playing in the background. |
| 🌗 **Light & dark** | |

## How it works

### Finding each other

The six words come from the BIP-39 list (66 bits). Stretched with PBKDF2 they give a topic name
and an AES-256 key. Phones exchange WebRTC offers, answers and ICE candidates through HiveMQ's
free public MQTT broker over TLS — sealed with that key, so the broker only ever sees noise under
a random topic. Music never passes through it.

On the same Wi-Fi the host also advertises the session with NSD. A guest who has the code connects
to it directly; a guest who doesn't can tap the session and ask, and on the host's approval receives
the code sealed to a one-off X25519 key. No internet is involved.

**Limit:** with no relay server of our own (no TURN), roughly one in ten network pairs — mostly
strict mobile carriers — can't connect directly. Duet says so and suggests Wi-Fi on one side.

### Playing in sync

The host keeps the shared state: the queue, the current song, and its position at a moment of the
host's clock. Guests estimate the offset to that clock NTP-style (the lowest round-trip sample
of recent pings wins). Every 200 ms each phone compares where the song *should* be with where its
player is: under 15 ms it leaves it, up to 80 ms it eases the speed by up to 2%, beyond that it
jumps. A per-output **sync nudge** (Bluetooth adds 150–250 ms) lines up headphones.

### Songs

The host sends each song to every guest over a WebRTC data channel before it plays, and the next
one while the current one plays. Songs are named by their SHA-256, checked on arrival, limited to
50 MB, and deleted when the session ends.

## Project layout

```
core/     pure Kotlin: session codes, sealing, pairing, clock sync, drift control,
          playback state + commands, protocol, host and guest session logic
net/      MQTT relay, local network (NSD + TCP), WebRTC data channels
data/     settings, on-disk song store, music library and import
play/     ExoPlayer sync loop, media session service
session/  wires a live session together
ui/       Compose screens, the record
```

## Testing

```
./gradlew testDebugUnitTest
```

Covers the session code and key derivation, sealing, same-room pairing, clock estimation, drift
decisions, every playback command, the protocol, and whole sessions over in-memory links: joining,
clock skew, songs reaching everyone (and corrupt ones being rejected), guests' songs being uploaded
and forwarded, commands from guests, chat, reactions, hearts, leaving and the eight-person cap.

Connecting across networks and on Wi-Fi needs two real phones.

## Building

Requires JDK 17 or 21 for Gradle.

```
./gradlew assembleDebug
```

## License

Apache 2.0. Fraunces and DM Sans are used under the SIL Open Font License. The BIP-39 word list is
MIT licensed.
