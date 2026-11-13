# Contributing

Bug reports, compatibility reports and pull requests are welcome. For a larger change, open an issue first so the approach can be agreed before the work.

## Reporting results with your TV

Use the [bug report form](https://github.com/jkfran/frame-art-mode-screensaver/issues/new/choose), also when everything works on a TV or streamer that is not yet in the README's compatibility table. The TV's model, year and firmware version, the streamer's model and Android version, and the lines from the app's **Activity** list are what make a report useful.

## Development

Build requirements and commands are in the [README](README.md#building-from-source). Before opening a pull request, run what CI runs:

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug assembleRelease
```

- **Tests run on the JVM and never touch the network or a real TV.** Use the fakes in `app/src/test/kotlin/io/github/jkfran/frameartmode/testing/`: `ScriptedTv` plays the TV's side of a WebSocket on OkHttp's MockWebServer, `Fakes.kt` has a scripted TV (`FakeFrameTv`, `FakeSession`) and an in-memory settings store.
- **The controller's rules are the product.** `ArtModeController` sends nothing to a TV that is off, unknown or not the chosen one; sends the Art Mode request at most once per attempt; never retries a refusal in a loop; and keeps the power key opt-in. A change to any of these comes with tests in `ArtModeControllerTest`.
- **Protocol changes** update [docs/PROTOCOL.md](docs/PROTOCOL.md) and come with tests of the message building and parsing.
- **User interface:** all text goes in `strings.xml`; every control must work with a D-pad and be readable from across a room.
- **No new network destinations, analytics or tracking.** The app talks only to the chosen TV. New dependencies need a reason.
- **User-visible changes** get an entry in [CHANGELOG.md](CHANGELOG.md) under the next version.

## Public text

README, docs, comments, commit messages and release notes state what the app does and what to do, in plain, neutral English.

## License

Contributions are accepted under the [MIT License](LICENSE) of this project.
