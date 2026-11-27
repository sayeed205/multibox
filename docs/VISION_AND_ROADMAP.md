# Multibox Vision and Roadmap

Multibox is an Android streaming app built on Telegram and local storage. This document tracks the product goal, settled design decisions, and phased delivery plan.

## Product Goal

Multibox turns Telegram private channels into a multi-media streaming library for invited users.

1. Setup requires a Telegram account login through TDLib before media streaming starts.
2. Each user picks a personal bot from chat history or `@username` search.
3. The bot grants access to private channels and defines each channel purpose.
4. Multibox accepts those channels, reads media updates only from them, and ignores other chats.
5. Users can stream online over TDLib or download files for Offline Mode playback.

## Settled Design Decisions

These rules come from the architecture grill and guide all implementation phases.

| Area | Decision | Reference |
| --- | --- | --- |
| Q1 Bot discovery | Show existing TDLib bot chats and `@username` search, with zero hardcoded bots. | [Phase 1B](PHASE_1_TELEGRAM_AUDIO.md#phase-1b-bot-discovery-and-channel-handshake) |
| Q2 Channel mapping | Read the bot message that maps channel IDs to media purposes. | [Architecture](TELEGRAM_MEDIA_ARCHITECTURE.md#bot-channel-access-contract) |
| Q3 Media sources | Support Telegram and local device storage only, with download and Offline Mode tools. | [Phase 1D](PHASE_1_TELEGRAM_AUDIO.md#phase-1d-offline-downloads-and-offline-mode) |
| Q4 Media streams | Separate video quality files and audio dub files, grouped by external metadata IDs. | [Architecture](TELEGRAM_MEDIA_ARCHITECTURE.md#multi-stream-playback) |
| Q5 API credentials | Inject `TG_API_ID` and `TG_API_HASH` at build time, with setup fallback if unset. | [Phase 1A](PHASE_1_TELEGRAM_AUDIO.md#phase-1a-source-cleanup-and-tdlib-auth-core) |
| Q6 Auth gate | Require login once during setup, then allow Offline Mode starts without network access. | [Phase 1A](PHASE_1_TELEGRAM_AUDIO.md#phase-1a-source-cleanup-and-tdlib-auth-core) |
| Q7 Sync and index | Run full background backfill per registered channel into a fast local Room index. | [Phase 1C](PHASE_1_TELEGRAM_AUDIO.md#phase-1c-scoped-audio-indexing-and-streaming) |
| Q8 Active bot limit | Allow one active bot at a time, and keep downloaded offline files when switching bots. | [Phase 1B](PHASE_1_TELEGRAM_AUDIO.md#phase-1b-bot-discovery-and-channel-handshake) |
| Q9 UI and indicator | Apply Material 3 Expressive across the entire app, add mode switcher, and show local badge icons. | [Codebase and UI Design](CODEBASE_AND_UI_DESIGN.md#material-3-expressive-shell) |
| Q10 Modularization | Separate app into deep Gradle modules (`:core:*`, `:feature:*`) following Clean Architecture seams. | [Codebase and UI Design](CODEBASE_AND_UI_DESIGN.md#gradle-module-architecture) |
| Q11 Offline storage | Save downloaded Telegram media into user-chosen public folders via Storage Access Framework. | [Phase 1D](PHASE_1_TELEGRAM_AUDIO.md#phase-1d-offline-downloads-and-offline-mode) |
| Q12 Logout policy | Return to Telegram login gate on logout, clear remote cache, and keep public downloads. | [Phase 1A](PHASE_1_TELEGRAM_AUDIO.md#phase-1a-source-cleanup-and-tdlib-auth-core) |
| Q13 Metadata fetcher | Skip Telegram metadata for thumbnails, and finalize metadata fetchers when building each media type. | [Architecture](TELEGRAM_MEDIA_ARCHITECTURE.md#metadata-and-external-id-grouping) |
| Q14 Build order | Remove unused sources first, split core Gradle modules, then build TDLib auth in `:core:telegram`. | [Phase 1A](PHASE_1_TELEGRAM_AUDIO.md#phase-1a-source-cleanup-and-tdlib-auth-core) |

## Delivery Phases

Multibox ships one media domain at a time so each release stays testable.

### Phase 1: Telegram Audio and Core Platform

Phase 1 delivers TDLib login, bot discovery, scoped audio streaming, and offline downloads. Because this milestone covers both core plumbing and audio playback, [Phase 1 Sub-Phases](PHASE_1_TELEGRAM_AUDIO.md) splits it into four parts:

1. Phase 1A: Source cleanup, build credentials, and mandatory TDLib auth core.
2. Phase 1B: Bot discovery UI, channel access handshake, and update filtering.
3. Phase 1C: Scoped audio channel indexing, external ID grouping, and Media3 streaming.
4. Phase 1D: Offline downloads, local storage management, and Offline Mode toggle.

### Phase 2: Movies and Series

Phase 2 adds video libraries built around separate video and audio files in Telegram channels.

1. Index movie and episode entries from video-tagged channels with `imdb`, `tmdb`, and `trakt` IDs.
2. Group separate resolution files (`1080p`, `720p`) and audio dub files under one title entry.
3. Stream selected video and audio pairs together through Media3 `MergingMediaSource`.
4. Track watch progress and support offline downloads per quality and audio track.

### Phase 3: Comics and Future Media

Phase 3 expands channel support to comics and additional media formats.

1. Add comic and manga channel readers with page prefetching and offline chapter downloads.
2. Parse comic metadata IDs (`kitsu`, `mal`, `anilist`) for canonical series grouping.
3. Add new channel purpose handlers as each future media format enters scope.

## Reference Repositories

Multibox references these upstream open-source repositories for implementation patterns.

| Repository | Focus area | Key paths |
| --- | --- | --- |
| [PixelPlayerHQ/PixelPlayer](https://github.com/PixelPlayerHQ/PixelPlayer) | TDLib audio streaming and Room schema | `PixelPlayerHQ/PixelPlayer/app/src/main/java/com/theveloper/pixelplay/data/telegram/TelegramClientManager.kt`, `TelegramStreamProxy.kt` |
| [dracu-lah/TMPlayer](https://github.com/dracu-lah/TMPlayer) | Telegram media player patterns | `dracu-lah/TMPlayer` |
| [aravindbuilds/TGTV](https://github.com/aravindbuilds/TGTV) | Telegram video and TV playback flows | `aravindbuilds/TGTV` |
| [MG-Devs-Team/Mategram](https://github.com/MG-Devs-Team/Mategram) | TDLib client integration and chat helpers | `MG-Devs-Team/Mategram` |
| [mihonapp/mihon](https://github.com/mihonapp/mihon) | Offline library, tracking, and reader patterns | `mihonapp/mihon` |

## Open Details Pending Spec

Two exact wire formats stay open until the server bot implementation is finalized:

1. Bot channel-purpose message format (`channelId` to media type and role mapping).
2. Telegram caption or metadata block format for external IDs and multi-stream grouping.

## Next Step

Read [Phase 1 Sub-Phases](PHASE_1_TELEGRAM_AUDIO.md) to plan Phase 1A through 1D. See [Telegram Media Architecture](TELEGRAM_MEDIA_ARCHITECTURE.md), [Codebase and UI Design](CODEBASE_AND_UI_DESIGN.md), and [README](../README.md) for contracts and structure.
