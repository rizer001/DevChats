# DevChats

![Development status](https://img.shields.io/badge/status-In_development-yellow)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-purple)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.11.1-blue)
![License](https://img.shields.io/badge/License-AGPL--3.0-blue)

DevChats is a free, open-source desktop messenger (a Discord-like app) that **does not depend on any service's servers**: every user runs their own node, and connections go directly over `IP:port`. As long as your node is running, you are online and reachable.

---

## Features

### Accounts
- Sign up and sign in: display name, password, **avatar** (loaded from a file)
- Everything is stored locally in SQLite — no external services involved

### Servers (nodes)
- Create a server: name, description, avatar
- Server settings (tabs **General** and **People**):
  - profile: name, description, avatar, **banner color** (16 base colors + custom HEX)
  - join/leave messages (on/off + channel selection)
  - AFK channel (on/off + channel selection)
  - delete server (with confirmation)
- Connect to other nodes by `IP:port`

### Channels
- Five types: **text, voice, announcements, conference, forum**
- Create via right-click on empty space: type + name + description
- Right-click a channel: rename, change type, settings, delete (with confirmation)
- Context menus appear under the cursor (positioned at the real click coordinates)

### Messages
- Text chat, **Enter — send, Shift+Enter — new line**
- Message history
- File sending (📎, file picker dialog, drag & drop)
- Reactions: server emojis with autocomplete and `:name:` → emoji conversion

### Voice and video
- 1-on-1 voice calls (Opus codec, microphone capture)
- Video calls and **screen sharing** (JPEG frames over WebSocket)
- Soundboard: your own sounds with length trimming and hotkey binding

### Roles
- Create, edit, delete
- Role color (16 base colors + HEX), ordering (higher = more priority), search
- Settings: display members separately, allow everyone to @mention the role

---

## Tech stack

| Layer | Technology |
|---|---|
| Language / UI | Kotlin, Compose Multiplatform (desktop) |
| Networking | Ktor 3.5 (server + client), WebSocket, JSON (kotlinx.serialization) |
| Storage | SQLite via Exposed, schema migrations |
| Audio | Opus (Concentus), microphone capture |
| Video | Webcam Capture, JPEG frame compression |
| Build | Gradle (wrapper 9.4), JDK 25 |

---

## Project structure

```
DevChats/
├── protocol/        — shared DTOs, message schemas, protocol version
├── server-core/     — node logic: Ktor server (WebSocket + HTTP), SQLite,
│                      channels, mailbox, files (shared library)
├── client-core/     — connection manager: Ktor client, connection state
├── desktop/         — Compose UI (depends on client-core + server-core)
```

Key decision: **all server logic lives in the shared `server-core` library**.
The desktop app embeds it; the future permanent Linux server will be a separate
entry point on top of the same library. One codebase — one protocol.

---

## Running

Requires **JDK 25+** (project builds with Gradle wrapper 9.4).

```bash
# build and run the desktop app
./gradlew :desktop:run

# run all tests
./gradlew build
```

From the IDE (IntelliJ IDEA): import the project as a Gradle project and run the
main class `devchats.desktop.MainKt`. On startup the console prints the data
directory and a build marker, so you can immediately see which build is running.

### Where data is stored

Everything (accounts, servers, channels, messages, settings) lives in a SQLite
file in the data directory:

| Method | Path |
|---|---|
| Default | `~/.devchats` |
| Environment variable `DEVCHATS_HOME` | wherever you point it (e.g. a second instance) |
| System property `devchats.home` | `-Ddevchats.home=...` (handy in IDE run configurations) |

---

## How it works

- User A knows user B's address: `IP:port`.
- A connects to B's node (WebSocket). B **must be online** — their local node is
  running (or B has a permanent server).
- If B is offline, the message is not delivered immediately. Node A puts it into
  an **outbox**; on the next connection a **mailbox sync** happens and messages
  are delivered.
- A node can host channels (text/voice) — members connect to it like to a Discord
  "server". When the node owner is offline, their channels are unavailable.

The protocol is versioned: every message is a JSON envelope
`{ "v": 1, "type": "dm.send", "id": "uuid", "payload": { ... } }`,
and the version is verified during the `hello` handshake.

---

## Roadmap

- ✅ **M0–M6** — scaffold, node, connect/presence, text messages, file transfer,
  voice, video and screen sharing
- ✅ **M7+** — accounts, servers, typed channels, roles, emojis, soundboard,
  full server settings
- ⏳ Planned — permanent Linux server, bots and node API, hardening (TLS),
  NAT traversal (STUN/TURN)

Full plan — see [PLAN.md](PLAN.md).

---

## License

Distributed under the **GNU Affero General Public License v3.0** — see [LICENSE](LICENSE).
