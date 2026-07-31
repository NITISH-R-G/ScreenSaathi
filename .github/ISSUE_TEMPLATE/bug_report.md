---
name: Bug report
about: Something in the overlay, accessibility reader, or voice loop is broken
title: "[bug] "
labels: bug
---

**What happened**
A clear description of the bug.

**Steps to reproduce**
1.
2.
3.

**Expected behavior**


**Environment**
- Android version / device model:
- Sarvam key configured? (yes/no — if no, this should be the deterministic
  fallback path, say so)
- Task running (`pay_bill`, `book_taxi`, other):
- Language spoken, if voice-related:

**Debug panel output**
Long-press the pill to reveal it (heard / intent / step / target / latency /
confidence). Paste it here if the bug is anywhere in the voice loop.

**Logs**
`adb logcat -s SessionController:D SarvamPlanner:W SarvamStt:W SarvamTts:W ScreenReaderService:D`
