# Contributing

Use Java 21 or newer and keep changes simulation-only. Do not add paid services,
real sensor data, secrets, generated build output, or edits to the original
nested Yggdrasil checkout. Make focused changes with tests, then run from the
repository root:

```sh
mvn -q verify
```

For changes to `DiffEngine`, update `docs/DETECTION.md`. For changes to a public
route or payload, update `docs/API.md`. Keep test and evaluation results tied to
commands actually run.