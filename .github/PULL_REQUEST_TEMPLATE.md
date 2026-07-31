## What changed


## Why


## What was tested
- [ ] `./gradlew assembleDebug` passes
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] Tested on a real device (describe what was exercised)
- [ ] If this touches the voice loop: confirmed the keyless deterministic
      fallback still works (no Sarvam key configured)

## What still needs verification


## Scope check
- [ ] This does not add a sixth subsystem
- [ ] This does not remove a field from a frozen contract in `contracts/`
- [ ] This does not replace one of the five core pieces without a prior issue
      discussing why
