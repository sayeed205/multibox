# Telegram Media Architecture

This document defines the shared architecture for Multibox across audio, movies, series, and comics.

Read [Vision and Roadmap](VISION_AND_ROADMAP.md), [Phase 1 Sub-Phases](PHASE_1_TELEGRAM_AUDIO.md), and [Codebase and UI Design](CODEBASE_AND_UI_DESIGN.md) for module seams. See [README](../README.md) for build setup.

## Auth and Source Boundary

Multibox uses Telegram and local device storage as its only media sources.

1. First-run onboarding requires a TDLib login before users can browse remote media.
2. Build scripts read `TG_API_ID` and `TG_API_HASH` from `local.properties` or CI secrets into `BuildConfig`.
3. If build keys are absent, `SetupScreen` accepts user-provided API credentials.

### Offline and Logout

1. When offline starts find a valid session, Multibox opens in Offline Mode with local and cached media.
2. Downloaded Telegram media saves to a user-chosen public folder tracked by Room URIs.
3. When logout or session revocation happens, Multibox clears remote channel data, keeps public downloads, and opens `SetupScreen`.

## Bot-Channel Access Contract

Multibox discovers channels through user-selected Telegram bots rather than hardcoded chat IDs.

```text
TDLib getChats + searchPublicChat
        |
        v
User picks bot -> Save bot ID in app configuration
        |
        v
Bot grants channel access + sends channel-purpose message
        |
        v
App auto-joins channels -> Save (channelId -> purpose)
        |
        v
Drop all TDLib updates whose chatId is not registered
```

### Channel Isolation Rules

1. When `chatId` exists in the registered channel table, scoped sync processes the update.
2. Each registered channel carries a purpose tag such as `audio`, `movies`, `series`, or `comics`.
3. Multibox stores one active bot ID at a time.
4. Switching or disconnecting the active bot clears its remote channel index and preserves downloaded offline files.

### Scoped Backfill and Local Index

Multibox connects only to registered media channels and skips general Telegram chat history.

1. When Multibox joins a registered channel, a worker backfills `getChatHistory` using saved checkpoint cursors.
2. After backfill completes, incremental TDLib updates write changes only for registered `chatId` rows.
3. Room indexes metadata tags, external IDs, and file locators so browsing and search run locally without TDLib queries.

### Open Contract Detail

Before Phase 1B starts, define the exact bot message schema that maps `channelId` to a channel purpose.

## Metadata and External ID Grouping

Multibox reads media metadata directly from Telegram messages and attached external IDs. This design lets multiple Telegram files merge cleanly into one catalog entry.

| Media category | External IDs supported | Grouping purpose |
| --- | --- | --- |
| Audio | `isrc`, `apple`, `yt`, `deezer`, `tidal` | Group versions and artwork by canonical release |
| Movies and series | `imdb`, `tmdb`, `trakt` | Link title details, seasons, video cuts, and dubs |
| Comics | `kitsu`, `mal`, `anilist` | Match chapters and volumes to canonical series |

### Open Metadata Detail

1. Before Phase 1C and Phase 2 start, define the exact Telegram caption or metadata block format.
2. Multibox never uses Telegram metadata for thumbnails.
3. Finalize metadata and thumbnail fetcher behavior per media type during each feature implementation.

## Multi-Stream Playback

Video titles can store video streams and audio dub streams as separate Telegram files inside registered channels. Separating streams avoids storing duplicate video copies for every language dub.

1. One video title groups multiple video stream variants (`1080p`, `720p`, codec variants) and multiple audio dub tracks.
2. Before playback starts, the user chooses a video quality file and an audio track file.
3. Two TDLib byte-range `DataSource` instances read the chosen video and audio files concurrently.
4. Media3 combines both sources with `MergingMediaSource` during playback and seeking.
5. For audio-only tracks in Phase 1, Media3 plays the single TDLib `DataSource` or verified offline file directly.

## Reference Blueprint

Once implemented, this design gives Multibox one shared TDLib streaming and download engine. It serves audio first and scales cleanly to video and comics.
