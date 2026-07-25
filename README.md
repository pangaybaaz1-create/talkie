# Talkie — Android app

Native Android version of Talkie. It talks to the same signaling server you already deployed on Render — no server changes needed. The big upgrade over the browser version: this uses a proper Android foreground service, so it's designed to keep the connection alive with the screen locked, the way a real walkie-talkie app should.

## How to build it (no Android Studio needed)

This uses GitHub Actions to build the APK in the cloud automatically.

1. **Upload these files to your existing `talkie` GitHub repo**, keeping the folder structure exactly as-is:
   - `settings.gradle.kts`
   - `build.gradle.kts`
   - `gradle.properties`
   - `app/build.gradle.kts`
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/java/com/talkie/app/MainActivity.kt`
   - `app/src/main/java/com/talkie/app/WalkieService.kt`
   - `.github/workflows/build-apk.yml`

   Since GitHub's upload screen doesn't preserve nested folders when you drag files in, the easiest way is: on the repo page, tap **Add file → Create new file**, and for each file, type its **full path** (e.g. `app/src/main/java/com/talkie/app/MainActivity.kt`) into the filename box — GitHub will create the folders automatically. Then paste in that file's contents and commit. Repeat for each file above.

2. Once everything is committed, go to your repo's **Actions** tab. You should see a workflow run start automatically (it's triggered by the push). If it doesn't, click **Build Talkie APK → Run workflow**.

3. Wait for it to finish (a few minutes). Click into the completed run, scroll to **Artifacts**, and download **talkie-debug-apk** — that's a zip containing `app-debug.apk`.

4. Transfer `app-debug.apk` to your Android phone (email it to yourself, upload to Google Drive, etc.), open it, and allow "install from unknown sources" when prompted. That installs the app.

## Important — the first build will likely need a fix or two

I wrote this carefully but couldn't compile or test it myself (no Android build tools available on my end). Android/Gradle/WebRTC projects are notoriously easy to get one small thing wrong in. If the Actions build fails:

1. Click into the failed run → click the failed step → screenshot the red error text
2. Send it to me and I'll fix the specific file

This is normal for a first build — same as when we had to fix the `public` folder path for Render.

## What's different from the browser version

- Runs as a foreground service with a persistent notification, so it keeps working when the screen is off or you switch apps
- Native Android UI instead of a browser tab
- Same channel codes, same server — a phone on the app and a phone on the website can talk to each other, since they use the same signaling protocol

## Limitations

- Still a mesh connection (fine for small groups, not built for large ones)
- No custom app icon yet (uses a placeholder system icon)
- The Render free tier still sleeps after inactivity — the first connection after idle time takes ~30-50 seconds
