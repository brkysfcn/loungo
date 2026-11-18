# Changelog

All notable changes to this project. Dates are when the change reached the TV.

## [Unreleased]

## [0.9.12] - 2026-11-18

### Added
- History on the phone remote (last 100 pages, stored on the phone).

### Changed
- Phone remote: the top bar has two rows, with the address on its own full-width, centered line.
- Phone remote: "Clear history", "Reset pairing" and "Clear browsing data" ask for a second tap instead of showing the browser's confirm box.

### Fixed
- Pages no longer get video codecs this TV can only decode in software (for example AV1 on TVs without an AV1 chip). YouTube and similar sites fall back to a hardware codec, so video stops freezing and higher resolutions play.
- Media detection reads only the start of downloaded files, so video segments no longer cost memory and CPU on the page.

## [0.9.11] - 2026-11-16

### Changed
- The TV app now runs on Android 9 and newer (was Android 12). When Android System WebView is older than version 80, a one-time notice suggests updating it from the Play Store.

### Fixed
- Extensionless DASH manifests (such as ok.ru streams) are now detected, and tiny placeholder MP4 files are ignored.
- The phone's "Video found" banner now switches to a streaming manifest (HLS/DASH) found after a placeholder video, so playback no longer closes after a second.
- If the native player fails to open, the page resumes instead of staying paused.

## [0.9.10] - 2026-11-14

### Changed
- The phone remote now installs on Android 10 and newer (was Android 12). The TV browser still needs Android 12: on an older TV it shows a short notice and starts nothing.
- The remote page also runs on the older WebView found on some Android 10 phones.

## [0.9.9] - 2026-11-13

### Changed
- Targets Android 16 (API 36), which Google Play now requires for phone apps. Back key behaviour is unchanged.

## [0.9.8] - 2026-11-12

### Added
- Loungo can now be installed on Android phones too. On a phone it opens as the TV's remote, never as a browser. It shows what Loungo is and how to install it on the TV, finds Loungo TVs on the same Wi-Fi by itself and pairs without a QR code: the TV asks "Connect this phone?" and the TV remote approves. This works in the browser and in the player. The remote page itself comes from the TV, so phone and TV always match.
- The TV also announces itself as `_loungo._tcp` on the network (old addresses keep working).

### Changed
- The QR card says "Scan with your camera to connect"; the tour mentions the Android app.
- Nothing TV-side (server, mDNS, Wi-Fi lock) ever starts on a phone.

### Fixed
- Subtitles without a language are listed as "Subtitle 1", "Subtitle 2" instead of "?".
- After "Reset pairing" a phone no longer retries with the old token in a loop (it got blocked by the TV for a minute); it asks to pair again. An iPhone with an old pairing now says "pair again" instead of "another device connected".
- The phone app lets go of the TV in the background, so it does not push out another phone.
- Plain `http://` pages and `http` video streams are no longer blocked by the app's network security setting.

## [0.9.7] - 2026-11-11

### Changed
- Renamed to Loungo (the visible name; package name unchanged). Phones that saved the old phantom-xxxx.local address keep working.

### Fixed
- Phone tips also appear when the phone reloads its page while the TV tour is on screen.

## [0.9.6] - 2026-11-10

### Added
- First-run showcase on the TV: a welcome page and four short pages (phone control, video player, ad blocking, performance and privacy) with simple drawings. Arrow keys change pages; Skip is always available. "About Phantom" in the menu shows it again.
- Short interactive tour after the showcase: move the cursor to a target and press OK, open the menu with a long OK press, pair the phone (or "Later"), video tips. "How to use" in the menu replays it. Shown once, also to existing users.
- Phone: add-to-home-screen tip per phone and browser (iPhone Safari/Chrome/Edge, Android Chrome/Samsung), a Fullscreen button on Android, trackpad gesture hints and short button tips on first connection; "Usage tips" in settings replays them. A phone that connects while the TV tour is on screen always gets its tips.

## [0.9.5] - 2026-11-09

### Added
- Phone: "Clear browsing data" in settings (cookies, cache, site data, event log). Pairing, ad-block settings and resume positions are kept.
- Phone: pull from the trackpad's left edge to the right to go back.

### Changed
- Lighter when idle: the cursor's per-frame loop sleeps when nothing moves, and the page stops running in the background (Home, another app), so it no longer slows the TV down.
- Light look (phone settings: Automatic / Always / Off). Automatic: on TVs with less than 3 GB of memory, a page that stutters gets its background blur turned off; panels get a solid background so text stays readable.
- In the background the system may reclaim the page's memory; the page reloads when you return.
- Player logs what the server returned when a playlist cannot be read (for "Report a problem").

### Fixed
- Native player sends the same request headers the page sent for the video (some servers answered "security error" before, so the player opened and closed at once).
- Phone could not reconnect after a VPN had been on: the TV announced the VPN tunnel address instead of its home-network address.
- Seek-preview thumbnail tracks (`thumbs.jpg#xywh=...`) are no longer shown as subtitles.
- A page's drop-down list (`<select>`) can be used from the phone: move up/down, tap to choose, Back closes it. Pasting into page fields from the phone (visible text field above the keyboard).

## [0.9.4] - 2026-11-07

Same app as 0.9.3; a new version code was needed because Google Play does not accept a version code twice.

## [0.9.3] - 2026-11-07

First build prepared for Google Play (AAB included). Built and signed by GitHub Actions.

### Added
- App icon and a new Android TV banner (`tools/make_brand.py`).
- Release workflow builds the Play bundle (AAB) next to the APK, signed in GitHub Actions.

### Changed
- Listed as an Android TV app only (`leanback` required); Wi-Fi hardware is optional, so Ethernet TVs qualify.

## [0.9.2] - 2026-11-05

### Added
- The phone UI reloads itself when the TV app has been updated (`hello.pwa` build id), so no stale panels after an update.

### Removed
- Leftover tabs code from the phone UI (about 330 lines and the thumbnail handling); lighter bundle.

## [0.9.1] - 2026-11-05

### Changed
- Phone: favorites open as soon as you tap the address bar.
- QR caption says "phone camera" instead of "iPhone camera".

### Removed
- Developer tools (latency and sharpness tests) from the phone settings.
- Manual "update list" button: the TV updates the ad list itself every 7 days.
- Favorites from the remote's long-press menu.

## [0.9.0] - 2026-11-05

First tagged beta. Signed with the project upload key.

### Added
- Subtitles move into the black bar under letterboxed films.
- Remote-only player settings (Up key): subtitles, audio language, quality.
- TV banner when a page has video; the Play key opens the native player.
- Release signing with a project upload key; R8-minified release build.
- `phantom-xxxx.local` name (mDNS), so the phone finds the TV after an IP change.
- English UI next to Turkish.
- Persistent event log and "Report a problem" on the phone.
- Remote-only use: on-screen keyboard for URLs, long-press OK menu, edge scrolling.
- GitHub Actions tests; quality budget script (start time, memory, size).

### Changed
- Quality is labelled by width (1920×800 shows as 1080p). Trick-play (I-frame) tracks and tracks without a hardware decoder are hidden.
- Media detection prefers the HLS master playlist over audio and variant sub-playlists.
- Each subtitle file is requested with its own Referer. Turkish is the default; an unlabelled single track is used when there is no Turkish one.
- The background service runs only while the app is open; it no longer starts at boot.
- Cursor speed lowered; redirect protection counts only clicks/OK as user gestures.
- Resume position is keyed by page, so it survives changing CDN URLs.
- No site-specific code; detection and protection rules are general.

### Fixed
- Black screen with a single audio language (an audio-only playlist was opened).
- Player closed when an undecodable quality was chosen; it now falls back to automatic quality.
- Track selectors on the phone reset every second while open.
- Stale player panel on the phone after the TV app restarted.
- Coloured band under the video (focus highlight).

### Removed
- Tabs (one page keeps the TV light).
- Software AV1 decoder: tried, but 1080p used about two thirds of the TV's CPU and stuttered.
- `SYSTEM_ALERT_WINDOW` and boot-start permissions.
