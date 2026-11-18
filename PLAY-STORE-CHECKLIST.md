# Mayra AI - package `com.rjbilal.mayraai` - Play Store checklist (for later)

Status: the app is currently built as an APK for APKPure / direct download.
Items marked DONE are already in this project. The rest must be done BEFORE a Play Store upload.

## Already in place
- DONE  Final package name `com.rjbilal.mayraai` (cannot be changed after the first Play upload).
- DONE  Permanent signing key kept OUT of the repository (GitHub Secrets `MAYRA_KS_B64`, `MAYRA_KS_PASS`).
         The build FAILS if the key is missing, so an APK signed with a temporary key can never be released by mistake.
- DONE  versionCode increases automatically on every build (GitHub run number + 100). Play needs this.
- DONE  `.gitignore` blocks `*.p12 / *.jks / *.keystore`.

## Needed for Google Play (verified against Google's pages, Oct 2026)
1. Target API level 36. New apps AND updates must target Android 16 since 31 Aug 2026.
   This project uses Capacitor 7 (targets API 35). Upgrade to Capacitor 8 (needs Node 22+, edge-to-edge UI check). NOT done yet - do it as a separate, tested step.
2. Upload an Android App Bundle (.aab), not an APK, and enroll in Play App Signing. The key in this project becomes your UPLOAD key.
3. SMS: `SEND_SMS` may only be used by the default SMS handler (or an approved exception). A Play version must open the user's SMS app with an SMS intent instead of sending silently, and `SEND_SMS` must be removed from the Play manifest.
4. AccessibilityService: needs the Permission Declaration form, a prominent in-app disclosure + consent screen, and Google prohibits using it to autonomously initiate, plan and execute actions. Mayra's phone-control tools are the biggest approval risk - expect to remove or narrow them in the Play version.
5. Foreground services (microphone, screen capture / mediaProjection): declare each type and its purpose in Play Console.
6. In-app APK self-update (bell icon -> APK download link) is not allowed in a Play build. Hide it and rely on Play updates.
7. Store paperwork: public privacy-policy URL (the in-app Policy page is not enough), Data safety form (microphone, contacts, camera, location, Gemini/Google processing), content rating, and the Generative-AI policy (users must be able to report offensive AI output).
8. New developer accounts: Google may require identity verification and a closed test before production - check the Play Console for the current rules.

## Keep forever
- `mayra-release-NEW.p12` + its password (2 backups). Losing it = no more updates for existing users.
