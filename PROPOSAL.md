# CODESTREAK

**Tech for Good 2026** · GDG Coimbatore · Build weekend Aug 8–9, GRD College

**Track:** AI for Strong Institutions
**Team code:** TEAM-018

## Problem

Lakshmi, a 68 year old grandmother in Coimbatore, wants to book an online OP consultation through the eSanjeevani OPD Android app, but struggles to navigate the English interface and is unsure which buttons to press. She often depends on family members to complete a simple healthcare task that should be accessible independently.

## Who it helps

Elderly Android users and first time smartphone users who need to book appointments through the eSanjeevani OPD app but struggle with English interfaces and complex navigation.

## Solution

We are extending ScreenSaathi into an AI powered accessibility copilot for one specific task: helping users successfully book an online consultation through the eSanjeevani OPD Android app.

The assistant observes the current screen using Android's Accessibility Service, understands each step of the booking flow with a vision language model, highlights exactly where the user should tap, and explains every step in the user's preferred language until the appointment is successfully booked.

Unlike traditional screen readers, the assistant provides contextual, task oriented guidance instead of simply reading screen content aloud.

## Architecture

eSanjeevani OPD Android App
            │
            ▼
Android Accessibility Service
            │
            ▼
Screen Context Extraction
            │
            ▼
Vision Language Model
            │
            ▼
Booking Workflow Reasoning Engine
            │
            ├────────► Voice Guidance (Tamil / Hindi / English)
            │
            └────────► Tap Highlight Overlay
            │
            ▼
Successful Appointment Confirmation

## Tech stack

Kotlin Android Accessibility Service Gemini Vision / OpenAI Vision FastAPI Firebase PostgreSQL

## Getting started

1. Accept your collaborator invite (check your email / GitHub notifications).
2. Clone this repo and start building.
3. Commit early and often — this repo is what you present on the day.

---

_Created automatically when your proposal was validated._