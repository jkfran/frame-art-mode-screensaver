# Security policy

## Supported versions

Only the latest release receives security fixes.

## Reporting a vulnerability

Report it privately through GitHub: on the repository's **Security** tab, choose **Report a vulnerability** ([direct link](https://github.com/jkfran/frame-art-mode-screensaver/security/advisories/new)). Do not open a public issue for it. Include the app version, the device and what an attacker could achieve.

## Design notes

Some behaviour is deliberate and follows from how the TV's API works:

- The TV presents a self-signed certificate on port 8002. The app accepts it, but only for connections to the configured TV's own address, through an HTTP client that nothing else uses. No trust is installed globally.
- The TV serves its device information over plain HTTP on port 8001, so the app reads it without TLS.
- Any device on the same network can use the TV's local API. The app does not change that.
- `WRITE_SECURE_SETTINGS` is only ever granted from a computer with ADB, and the app uses it only to select itself as the screensaver and to set the screensaver delay.

In scope, for example: a token leaving the app's private storage or appearing in logs, the certificate exception reaching hosts other than the configured TV, the app sending commands other than those in [docs/PROTOCOL.md](docs/PROTOCOL.md), or sending anything to a TV that reports that it is off.
