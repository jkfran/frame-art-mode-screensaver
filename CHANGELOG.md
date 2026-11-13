# Changelog

All notable changes to this project are documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-11-13

First release.

### Added

- Android TV and Google TV screensaver (`DreamService`) that switches a Samsung The Frame TV to Art Mode over the TV's local art channel (`com.samsung.art-app`) when the streamer goes idle. The TV then puts the streamer to sleep over HDMI-CEC and stays in Art Mode.
- Checks before anything is sent: the TV must be the chosen one (ID or Wi-Fi MAC address), report `PowerState` `on` and report `FrameTVSupport` `true`. A TV that is off is never woken or switched, and a TV already in Art Mode gets no request.
- The Art Mode request is sent at most once per attempt. Transient connection failures are retried twice with backoff, and each attempt is limited to 20 seconds.
- Error `-2` (Samsung Art Store terms not accepted on the TV) is explained on screen with the two items to accept. The screensaver then waits 24 hours before asking again; **Test now** asks at once.
- Settings screen for the remote and D-pad: **Find TVs** on the local network, manual IPv4 address, **Test now: switch to Art Mode** and **Check TV**.
- **Set as screensaver** and **Screensaver delay**, which work after an ADB grant of `WRITE_SECURE_SETTINGS`; otherwise the app shows the exact ADB commands.
- Optional **End the screensaver after Art Mode starts** (off by default).
- Optional **Power-key fallback** over the TV's remote-control channel, with one-time pairing (off by default).
- **Activity** list of the last 50 attempts.
- No analytics and no internet access: the app talks only to the chosen TV on the local network.

[0.1.0]: https://github.com/jkfran/frame-art-mode-screensaver/releases/tag/v0.1.0
