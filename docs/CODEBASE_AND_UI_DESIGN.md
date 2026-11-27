# Codebase and Material 3 UI Design

Multibox splits features into isolated Gradle modules and applies Material 3 Expressive across the entire app.

Read [Vision and Roadmap](VISION_AND_ROADMAP.md), [Phase 1 Sub-Phases](PHASE_1_TELEGRAM_AUDIO.md), and [Telegram Media Architecture](TELEGRAM_MEDIA_ARCHITECTURE.md) for product rules. See [README](../README.md) for project build setup.

## Material 3 Expressive Shell

Multibox extends the Material 3 Expressive design system from PixelPlayerOSS across setup, Music, Movies and Shows, and Comics.

1. Keep shared expressive tokens in `:core:ui`, including variable headline typography, concentric corner radii, and spring motion schemes.
2. Add one media mode switcher in `AppSidebarDrawer` to switch between **Music**, **Movies and Shows**, and **Comics**.
3. Keep all music views faithful to the expressive PixelPlayer layout for both local and Telegram tracks.
4. Show a compact local-availability badge icon on tracks and albums stored locally or downloaded to public storage.
5. Keep interactive badge and mode controls at a minimum 40dp to 44dp touch target without crowding rows.

### Active Layout Focus

Each media domain uses the same Material 3 Expressive visual language while keeping its own clean navigation graph.

## Gradle Module Architecture

Separating Multibox into Gradle modules isolates TDLib networking, database indexing, audio DSP, and media features from each other.

| Module | Responsibility | Allowed dependencies |
| --- | --- | --- |
| `:core:model` | Canonical IDs, track/video/comic domain models, error types | Kotlin stdlib |
| `:core:telegram` | TDLib client lifecycle, bot handshake, scoped backfill, chunk reader | `:core:model` |
| `:core:database` | Room entities, DAOs, local search indexes, offline file locators | `:core:model` |
| `:core:dsp` | Media3 `EqualizerAudioSink`, `DirectUsbAudioSink`, native USBDEVFS | `:core:model` |
| `:core:ui` | Material 3 Expressive theme, `AppSidebarDrawer`, mode switcher, badges | `:core:model` |
| `:feature:music` | Expressive music screens, library, player sheet, offline queue | `:core:model`, `:core:database`, `:core:dsp`, `:core:ui` |
| `:feature:video` | Phase 2 movies and series UI, multi-stream video/dub selector | `:core:model`, `:core:database`, `:core:ui` |
| `:feature:comics` | Phase 3 comic library, chapter tracker, offline page reader | `:core:model`, `:core:database`, `:core:ui` |
| `:app` | Hilt DI wiring, onboarding gate, top-level navigation shell | All `:core` and `:feature` modules |

### Clean Architecture Rules

1. Pure domain models and repository interfaces live in `:core:model` with zero Android, Room, or TDLib imports.
2. Feature modules never talk directly to `:core:telegram`. Repositories coordinate cached Room reads and background sync.
3. Music DSP lives in `:core:dsp` so audio playback stays decoupled from UI screens and network sources.

## Deep Modules and Seam Discipline

Multibox follows deep-module design so callers rely on small interfaces while complex logic stays encapsulated inside implementations.

1. Expose a narrow `TelegramGateway` interface in `:core:telegram` that hides auth states, bot lookups, and update filtering.
2. Place adapters at real variation seams, such as TDLib production vs in-memory fake for tests.
3. Avoid shallow pass-through wrappers that expose internal TDLib message structures to ViewModels.
4. Pass required dependencies into UseCases and repositories so unit tests run against module interfaces directly.

## Outcome

This layout lets Multibox scale Material 3 Expressive across the whole app while isolating Music, Video, and Comics modules cleanly.
