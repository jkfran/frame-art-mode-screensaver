# Protocol

What Frame Art Mode sends to a Samsung The Frame TV, and what it expects back. Everything goes to the TV's own local API; nothing leaves the local network.

| Purpose | Endpoint |
|---|---|
| Device information | `GET http://<tv>:8001/api/v2/` |
| Art Mode | WebSocket `wss://<tv>:8002/api/v2/channels/com.samsung.art-app` |
| Power key (optional fallback) | WebSocket `wss://<tv>:8002/api/v2/channels/samsung.remote.control` |

Placeholders are written `<like-this>`. The source of truth is the code under `app/src/main/kotlin/io/github/jkfran/frameartmode/tv/`.

## 1. Device information

```http
GET /api/v2/ HTTP/1.1
Host: <tv>:8001
```

Reply, shortened to the fields the app reads:

```json
{
  "device": {
    "FrameTVSupport": "true",
    "PowerState": "on",
    "TokenAuthSupport": "true",
    "id": "uuid:<tv-id>",
    "modelName": "QE55LS03BAUXXU",
    "name": "Samsung The Frame 55",
    "wifiMac": "00:00:5E:00:53:01"
  },
  "id": "uuid:<tv-id>",
  "version": "2.0.25"
}
```

| Field | Use |
|---|---|
| `device.PowerState` | `on` in TV mode **and** in Art Mode, `standby` when the TV is off. The app sends nothing unless it is `on`, and nothing when it is missing. It cannot tell Art Mode from TV mode; only the art channel can. |
| `device.FrameTVSupport` | The string `"true"` on TVs with Art Mode. The app acts on no other TV. |
| `device.id` (or `device.duid`), `device.wifiMac` | Identify the TV. The app stores them when the TV is chosen and sends nothing when a different TV answers at the stored address. |
| `device.name`, `device.modelName` | Shown in the app. Names may contain HTML entities such as `&#39;`, which the app decodes. |

Flags arrive as strings (`"true"`, `"false"`); JSON booleans are accepted too. The trailing slash in `/api/v2/` is required. When port 8001 refuses the connection, the app asks `https://<tv>:8002/api/v2/` instead (not during discovery).

### Discovery

**Find TVs** sends `GET http://<address>:8001/api/v2/` to every address of the streamer's IPv4 subnet, at most the /24 around the streamer, 48 at a time, with a 0.5 s connect and 1 s read timeout. Every reply that parses as above is listed, Frames first. No SSDP or mDNS is used.

## 2. WebSocket channels

```
wss://<tv>:8002/api/v2/channels/<channel>?name=<base64-name>[&token=<token>]
Origin: https://<tv>:8002
```

- `<channel>` is `com.samsung.art-app` or `samsung.remote.control`.
- `name` is the client name in standard Base64 with padding: `Frame Art Mode` is `RnJhbWUgQXJ0IE1vZGU=`. The TV shows this name in its prompts and in Device Connection Manager.
- `token` is sent when the TV has issued one before, and only over TLS.
- The TV presents a self-signed certificate. The app accepts it only for connections to the configured TV's address, through an HTTP client used for nothing else.

### Handshake

The TV answers the connection with:

```json
{"event": "ms.channel.connect", "data": {"id": "<client-id>", "clients": [], "token": "<token>"}}
```

`data.token` is present when the TV issues a token; the app stores it for later connections. On the art channel the TV then sends:

```json
{"event": "ms.channel.ready", "data": {}}
```

Other answers end the attempt:

| Frame | Meaning | What the app does |
|---|---|---|
| `{"event": "ms.channel.unauthorized"}` | The TV denied this client. | Forgets the token and the remote-control pairing. No retry, no power key. |
| `{"event": "ms.channel.timeOut"}` | The TV stopped waiting, typically for an answer to its "allow" prompt. | Stops. |
| `{"event": "ms.error", "data": {"message": "<text>"}}` | The TV refused. | Stops. |

Events the TV may send before it answers (`ed.edenTV.update`, `ms.voiceApp.hide`, `ms.remote.touchEnable`, `ms.remote.touchDisable`) are skipped. The screensaver waits 5 s for the handshake; the settings screen waits 31 s, which leaves time to answer a prompt on the TV.

## 3. Art channel requests

Every art request is an `ms.channel.emit` whose `params.data` is a **JSON-encoded string**. Inside it, `id` and `request_id` carry the same fresh UUID for each request.

Read the Art Mode status:

```json
{
  "method": "ms.channel.emit",
  "params": {
    "event": "art_app_request",
    "to": "host",
    "data": "{\"request\":\"get_artmode_status\",\"id\":\"<uuid>\",\"request_id\":\"<uuid>\"}"
  }
}
```

Switch to Art Mode: the same envelope, with this `data`:

```json
{"request": "set_artmode_status", "value": "on", "id": "<uuid>", "request_id": "<uuid>"}
```

**Check TV** also asks for the art service version with `api_version`, and with `get_api_version` when the TV rejects the first; the reply's `version` is only displayed.

### Replies

Replies arrive as `d2d_service_message` events whose `data` is again a JSON-encoded string:

```json
{"event": "d2d_service_message", "data": "{\"id\":\"<uuid>\",\"request_id\":\"<uuid>\",\"event\":\"<reply-name>\",\"value\":\"off\",\"target_client_id\":\"<client-id>\"}"}
```

The app takes as the answer the first payload whose `request_id` (or, without one, `id`) equals the request's UUID, and skips every other frame. It does not depend on the reply's `event` name, except for `error`. For `get_artmode_status`, `value` `on` means Art Mode and `off` means TV mode. Older art APIs send binary frames that start with the JSON object; the app reads the leading object and ignores the rest.

An error looks like this (decoded `data`):

```json
{
  "id": "<uuid>",
  "request_id": "<uuid>",
  "event": "error",
  "error_code": "-2",
  "request_data": "{\"request\":\"set_artmode_status\",\"value\":\"on\",\"id\":\"<uuid>\",\"request_id\":\"<uuid>\"}"
}
```

`error_code` may be a string or a number. An error without an id is matched by the request named in `request_data`, which may be a JSON string or an object.

**`error_code` `-2` on `set_artmode_status`** means the Samsung Art Store terms have not been accepted on the TV. The TV shows its "Terms & Conditions, Privacy Notice & Device Features" screen at the same time. Accepting **Terms and Conditions for the Samsung Art Store** and **Art Store Privacy Notice** there is enough. `get_artmode_status` works before that. The app reports the refusal, never retries it in a loop and never answers it with a power key; the screensaver waits 24 hours before it asks again, and **Test now** asks at once.

## 4. Remote-control channel (optional fallback)

Used only when the **Power-key fallback** is switched on. **Allow remote control on the TV** connects once so that the TV can ask whether to allow Frame Art Mode, and stores the token from `ms.channel.connect`. A fallback press is one message:

```json
{
  "method": "ms.remote.control",
  "params": {"Cmd": "Click", "DataOfCmd": "KEY_POWER", "Option": "false", "TypeOfRemote": "SendRemoteKey"}
}
```

The app keeps the socket open for one second after the click, then closes it. It only ever sends `Click`, never a press-and-hold. On The Frame a short power press normally toggles between TV mode and Art Mode.

## 5. What one attempt does

| Step | Condition | Result |
|---|---|---|
| 1 | No TV chosen | Nothing is sent. |
| 2 | A `-2` refusal less than 24 hours ago (screensaver only) | Nothing is sent. |
| 3 | `GET /api/v2/` fails | Nothing more is sent: the TV is most likely off. |
| 4 | Another TV answers, `PowerState` is missing or not `on`, or `FrameTVSupport` is not `"true"` | Nothing more is sent. |
| 5 | Art channel connection fails for a transient reason (refused, timed out, TLS failure, closed during the handshake) | Retried at most twice, after 1 s and 2 s. |
| 6 | `get_artmode_status` is `on` | Done; nothing more is sent. |
| 7 | `get_artmode_status` is answered with an error | The request in step 8 decides. (No answer in time ends the attempt.) |
| 8 | Send `set_artmode_status` `on` | Sent at most once per attempt, never re-sent. `-2` stops the attempt as above. |
| 9 | Read the status back | For the Activity list only: a reply of `on` is "switched", anything else "accepted". |
| 10 | Power-key fallback | See the conditions in the [README](../README.md#should-i-turn-on-the-power-key-fallback). At most one click. |

The whole attempt is limited to 20 s in the screensaver and 120 s from the settings screen. **Check TV** runs steps 3 and 4, the status read and the version request, and never sends `set_artmode_status`.

## 6. HDMI-CEC around it

The app does not send HDMI-CEC; the TV and the streamer do. With The Frame and an Android streamer that is the TV's active source:

| Event | CEC traffic | Result |
|---|---|---|
| The Frame switches to Art Mode on `set_artmode_status` | The TV broadcasts `<Standby>` (`0F:36`). | The streamer goes to sleep about a second later. |
| The streamer goes to sleep because the TV asked | `<Inactive Source>` from the streamer, no `<Standby>`. | A TV in Art Mode ignores it and stays in Art Mode. |
| The streamer goes to sleep by itself, `power_control_mode` `to_tv`, `to_tv_and_audio_system` or `broadcast` | `<Standby>` from the streamer. | The Frame turns off, also from Art Mode. |
| The streamer goes to sleep by itself, `power_control_mode` `none` | `<Inactive Source>` from the streamer. | A TV in TV mode switches to the previous input ("Check device power" if that device is off). |
| The streamer wakes while the TV is in Art Mode, `power_control_mode` `none` | The streamer asks for the TV's power status; the TV reports on. No One Touch Play. | The first key press does not take the screen; a second Home press sends One Touch Play. |
| The streamer wakes, any other `power_control_mode` | One Touch Play from the streamer. | The TV is asked to switch to the streamer. |

A device without HDMI-CEC that stops sending a picture makes The Frame go to Art Mode within a few seconds.
