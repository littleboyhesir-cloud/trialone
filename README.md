# StayFree

Android app (Kotlin, Jetpack Compose, Room) that helps you quit an addiction and stay free.

## Features
- Live streak timer, milestones, days free, money saved (when a cost applies)
- **Motivation briefing every time you open the app**: your streak, your own reasons, an online quote, motivations, a daily challenge, your next goal
- **Progress notifications** (morning / midday / evening / late night) with milestone alerts. Lock screen shows only a neutral message
- **Account login + cloud backup** (email/password). Restore everything on a new phone
- Craving log, urge SOS (4-7-8 breathing, 10-minute delay, tips), insights (7-day chart, danger time, top triggers), slip tracking
- Internet data: quotes (zenquotes.io), Wikipedia summaries (cached offline), Firebase for login and backup

## Build the APK (no Android Studio needed)
1. Do the cloud setup in SETUP_CLOUD.md (optional, but needed for login/backup).
2. Create a **private** GitHub repository and upload everything in this folder (keep the .github folder).
3. Actions tab > **Build APK** > **Run workflow**. After about 5 minutes download **StayFree-debug-apk**, unzip, install app-debug.apk.

Not medical advice. If you ever feel unable to cope, contact a local helpline (findahelpline.com).
