## Summary

-

## Linked issue

Closes #

## Changes

- App:
- Rust core:
- CI or docs:

## Testing

- [ ] `./gradlew :app:testDebugUnitTest`
- [ ] `cd rust && cargo fmt --check && cargo clippy --all-targets -- -D warnings && cargo test`
- [ ] Built and installed the APK on a device
- [ ] Not applicable, explain below

Commands and results:

```text

```

## Impact

- [ ] Touches archive write paths (add, rename, delete, extract)
- [ ] Touches file deletion, copy or move
- [ ] Touches storage access (SAF, elevation, permissions)
- [ ] Touches CI, signing or secrets handling
- [ ] UI strings only, no behavior change
- [ ] User-visible change that should be listed in the next release notes

## Checklist

- [ ] Diff is scoped to the stated change
- [ ] No new code comments, UI strings are in English
- [ ] No keystores, tokens, build output or generated `jniLibs` committed
- [ ] Release signing configuration and existing secrets were left untouched
