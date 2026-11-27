# Phase 1: Telegram Audio and Core Platform

Phase 1 delivers mandatory TDLib login, bot-based channel setup, scoped audio streaming, and offline downloads. This plan breaks Phase 1 into four buildable sub-phases (`1A` through `1D`) so you can verify each layer independently.

Read [Vision and Roadmap](VISION_AND_ROADMAP.md), [Telegram Media Architecture](TELEGRAM_MEDIA_ARCHITECTURE.md), and [Codebase and UI Design](CODEBASE_AND_UI_DESIGN.md) for context. See [README](../README.md) for build steps.

## Phase 1A: Source Cleanup and TDLib Auth Core

Phase 1A focuses the app on Telegram plus local storage and enforces one-time Telegram setup.

### Scope

1. Remove Navidrome and Jellyfin modules, and carve out core Gradle module seams (`:core:model`, `:core:dsp`, `:core:telegram`).
2. Keep local device audio scanning, 64-bit DSP (`EqualizerAudioSink`), and `DirectUsbAudioSink`.
3. Read `TG_API_ID` and `TG_API_HASH` from `local.properties` into `BuildConfig`, with optional `SetupScreen` fallback input.
4. Integrate TDLib client lifecycle and mandatory Telegram authentication in `SetupScreen` and `SetupViewModel`.
5. Support Offline Mode restarts and logout recovery according to the session rules below.

### Session Transitions

1. When an authenticated session starts without network access, open directly into Offline Mode.
2. When a user logs out or loses auth, return to `SetupScreen`, clear remote cache, and keep public downloads.

### Reference Files

1. `app/src/main/java/com/hitarashi/multibox/presentation/screens/SetupScreen.kt` (onboarding gate).
2. `app/src/main/java/com/hitarashi/multibox/data/equalizer/EqualizerAudioSink.kt` and `DirectUsbAudioSink.kt` (audio pipeline to preserve).
3. [PixelPlayerHQ/PixelPlayer/app/src/main/java/com/theveloper/pixelplay/data/telegram/TelegramClientManager.kt](https://github.com/PixelPlayerHQ/PixelPlayer/blob/master/app/src/main/java/com/theveloper/pixelplay/data/telegram/TelegramClientManager.kt) (TDLib session handling).
4. [PixelPlayerHQ/PixelPlayer/app/src/main/java/com/theveloper/pixelplay/presentation/telegram/auth/TelegramLoginActivity.kt](https://github.com/PixelPlayerHQ/PixelPlayer/blob/master/app/src/main/java/com/theveloper/pixelplay/presentation/telegram/auth/TelegramLoginActivity.kt) (login flow pattern).

### Done Criteria

App startup enforces Telegram auth on clean install, keeps local DSP playback active, and builds without Navidrome or Jellyfin modules.

## Phase 1B: Bot Discovery and Channel Handshake

Phase 1B lets users select any bot and register only the private channels that the bot assigns.

### Scope

1. Load existing Telegram bot chats with TDLib `getChats` and show them in a picker.
2. Add inline `@username` and link search with TDLib `searchPublicChat`, without any hardcoded bot.
3. Store one active bot ID in app configuration after user selection, switchable later.
4. Read the bot response message that lists private channel invites and media purposes.
5. Auto-join assigned channels, save `channelId -> purpose` mappings, and filter TDLib updates to registered IDs only.

### Pending Schema Note

Phase 1B expects an exact bot message schema for channel IDs, invite links, and media purposes before parser coding begins.

### Done Criteria

Selecting a bot saves its configuration, joins its assigned channels, and records scoped channel IDs in local storage.

## Phase 1C: Scoped Audio Indexing and Streaming

Phase 1C indexes music from registered audio channels and plays remote files through Multibox DSP.

### Scope

1. Backfill `getChatHistory` with checkpoint cursors strictly for registered audio channels, then listen incrementally.
2. Parse external audio IDs (`isrc`, `apple`, `yt`, `deezer`, `tidal`), and skip Telegram metadata for thumbnails.
3. Store tracks, albums, and local search indexes in Room for instant browsing without live TDLib calls.
4. Stream Telegram audio chunks on demand through a TDLib `DataSource` or local HTTP proxy for Media3.
5. Route Media3 output through `EqualizerAudioSink` and `DirectUsbAudioSink` with seeking support.

### Reference Files

1. [PixelPlayerHQ/PixelPlayer/app/src/main/java/com/theveloper/pixelplay/data/telegram/TelegramRepository.kt](https://github.com/PixelPlayerHQ/PixelPlayer/blob/master/app/src/main/java/com/theveloper/pixelplay/data/telegram/TelegramRepository.kt)
2. [PixelPlayerHQ/PixelPlayer/app/src/main/java/com/theveloper/pixelplay/data/telegram/TelegramStreamProxy.kt](https://github.com/PixelPlayerHQ/PixelPlayer/blob/master/app/src/main/java/com/theveloper/pixelplay/data/telegram/TelegramStreamProxy.kt)
3. [PixelPlayerHQ/PixelPlayer/app/src/main/java/com/theveloper/pixelplay/data/database/TelegramDao.kt](https://github.com/PixelPlayerHQ/PixelPlayer/blob/master/app/src/main/java/com/theveloper/pixelplay/data/database/TelegramDao.kt)

### Done Criteria

Audio tracks from registered Telegram channels load in the library, seek smoothly, and output through 64-bit DSP and USB DAC.

## Phase 1D: Offline Downloads and Offline Mode

Phase 1D gives users reliable offline audio playback using saved Telegram downloads and local files.

### Scope

1. Add per-track and per-album download actions powered by TDLib `downloadFile` and progress state.
2. Save completed Telegram downloads to user-chosen public folders via Storage Access Framework and record URIs in Room.
3. Prefer verified local URIs over remote streams, and show a local-availability icon badge on music items.
4. Add an explicit Offline Mode switch that pauses Telegram sync and hides uncached remote items.
5. Keep playback queue, lyrics, and DSP features operational while Offline Mode is active.

### Done Criteria

Users can download tracks from registered Telegram channels, turn on Offline Mode, and play downloaded plus local audio offline.
