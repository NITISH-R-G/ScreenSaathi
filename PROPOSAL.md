# Architecture Proposal

> Fill this out and commit it by **Friday, July 24 · 11:59 PM IST**. This file *is*
> your Ideation-Phase submission — no separate form. Keep it living; update it as
> your design evolves.

- **Team name:** CODESTREAK
- **Team code:** TEAM-018
- **Track:** AI for Strong Institutions
- **Members:** Aswin R (@Aswin-Times), Nitish R.G. (@NITISH-R-G), Padmanabhan Sureshbabu (@padmanabhansb08), Sonika P (@Sonika-275)

## 1. Problem
Elderly and first-time Android users struggle with digital interfaces, especially for critical tasks like booking an online consultation through the eSanjeevani OPD Android app. Traditional screen readers or chatbots do not adequately solve the problem of visual orientation and contextual guidance in complex UIs.

## 2. Who it helps
Elderly and first-time smartphone users, particularly those navigating the eSanjeevani OPD Android app. We reach them by providing an accessible, on-device floating copilot that guides them in their native language (Hindi, Tamil, or English).

## 3. Proposed solution
ScreenSaathi is an AI-powered accessibility copilot. It observes the screen via Android Accessibility Service, uses a vision-language model to understand the booking flow, highlights exactly where to tap with a blooming ring cursor, and explains the next step audibly in the user's preferred language.

## 4. High-level architecture
An Android Accessibility Service captures a read-only snapshot of the screen and user voice input. The Audio is processed by Saaras STT for transcription and language detection. The planner model determines the next target field and instruction. The OverlayService then flies a cursor to the field while Bulbul TTS narrates the instruction. A deterministic StepEngine handles offline fallbacks.

```
User Voice → STT (Saaras) → Planner Model (Vision/Language)
                                  ↑
Screen Snapshot (Accessibility) ──┘
                                  ↓
                  Overlay Service (Visual Highlight) & TTS (Audio Instruction)
```

## 5. Tech stack
- **Language**: Kotlin
- **Frameworks**: Android Accessibility Service, FastAPI (Backend)
- **AI/ML**: Gemini Vision / OpenAI Vision, Saaras STT, Sarvam-105B planner, Bulbul TTS
- **Backend/Data**: Firebase, PostgreSQL

## 6. Milestones to hackathon day
- [ ] Integrate existing ScreenSaathi Android project into the hackathon repository.
- [ ] Refine the eSanjeevani OPD booking flow tasks.
- [ ] Ensure offline StepEngine fallback works robustly.

## 7. Open questions / help needed
None currently.
