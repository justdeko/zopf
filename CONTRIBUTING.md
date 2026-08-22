# Contributing

Thanks for taking an interest in contributing to zopf!

Feel free to open an issue if you have feature suggestions. 
Or fork the repository directly and implement it.

Bug reports are just as welcome. Please include:

- the version
- if workflow related, an anonymized version of the workflow
- if the workflow includes agents, which provider
- the relevant part of `~/Library/Logs/zopf/zopf.log`

## House rules

- No comments. If you adapt the architecture, please also adapt `CLAUDE.md`.
- macOS on Apple Silicon only, there are no plans to support other platforms for now.

## Building

IntelliJ is recommended as an IDE because this is a Compose Multiplatform Project.

```bash
# linting
./gradlew ktlintCheck
# test suite
./gradlew :core:jvmTest :shared:jvmTest :cli:test
# run app/cli
./gradlew :desktopApp:run
./gradlew :cli:installDist
```

If you add a dependency, re-run `:cli:exportLibraryDefinitions` and
`:desktopApp:exportLibraryDefinitions` or CI fails on the diff.
