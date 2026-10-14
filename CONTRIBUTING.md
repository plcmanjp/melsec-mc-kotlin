# Contributing

**English** | [한국어](CONTRIBUTING.ko.md) | [README](README.md)

## Report an issue

Include the library version, JDK/OS, PLC model and connection module, frame/data mode, expected behavior, and reproduction steps. Remove real PLC addresses, factory/customer names, production data, and credentials.

## Submit a change

Keep pull requests focused. Explain the reason for the change and include relevant automated tests. For protocol changes, cite the official manual identifier and section. Do not upload the manual itself. Submit only code you have the right to contribute. Unless separately agreed, contributions are provided under this repository's [Apache-2.0 license](LICENSE).

Process requests sequentially on each connection, from encoding through response decoding. Do not add tests that connect to a physical PLC to the default suite or CI. Changes to remote writes or communication lifecycles need regression evidence for failures, cancellation, and duplicate requests.

## Documentation

English is the default language for reader-facing documentation. Keep the matching Korean guides aligned when changing usage, supported features, or limitations. Maintain the language switch links, use repository-relative links, and avoid local absolute paths. Preserve the original license texts.

## Verify

From the repository root, run `./gradlew build dist`. On Windows, use `gradlew.bat`.
## Safe local cleanup
Before removing local workspace material, confirm the exact targets, Git state and active users. Preserve source data, credentials, test evidence and recovery copies. Use a verified OS recycle/trash operation with permanent deletion disabled, retain its actual payload receipt and verify restoration. If this cannot be established, leave the files in place. Do not work around a denied operation by changing flags, splitting paths or switching APIs. Build cache regeneration alone is not proof that deletion is safe.
