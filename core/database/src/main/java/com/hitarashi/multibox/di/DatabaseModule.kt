package com.hitarashi.multibox.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.hitarashi.multibox.core.database.BuildConfig
import com.hitarashi.multibox.data.database.AlbumArtThemeDao
import com.hitarashi.multibox.data.database.AudioBookmarkDao
import com.hitarashi.multibox.data.database.EngagementDao
import com.hitarashi.multibox.data.database.FavoritesDao
import com.hitarashi.multibox.data.database.ListenBrainzDao
import com.hitarashi.multibox.data.database.LocalPlaylistDao
import com.hitarashi.multibox.data.database.LyricsDao
import com.hitarashi.multibox.data.database.MultiboxDatabase
import com.hitarashi.multibox.data.database.MusicDao
import com.hitarashi.multibox.data.database.SearchHistoryDao
import com.hitarashi.multibox.data.database.TransitionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Singleton
    @Provides
    fun provideMultiboxDatabase(@ApplicationContext context: Context): MultiboxDatabase {
        val builder = Room.databaseBuilder(
            context.applicationContext,
            MultiboxDatabase::class.java,
            "multibox_database"
        )
            .addCallback(MultiboxDatabase.createRuntimeArtifactsCallback())
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)

        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration(dropAllTables = true)
        }

        return builder.build()
    }

    @Singleton
    @Provides
    fun provideAlbumArtThemeDao(database: MultiboxDatabase): AlbumArtThemeDao =
        database.albumArtThemeDao()

    @Singleton
    @Provides
    fun provideSearchHistoryDao(database: MultiboxDatabase): SearchHistoryDao =
        database.searchHistoryDao()

    @Singleton
    @Provides
    fun provideMusicDao(database: MultiboxDatabase): MusicDao =
        database.musicDao()

    @Singleton
    @Provides
    fun provideTransitionDao(database: MultiboxDatabase): TransitionDao =
        database.transitionDao()

    @Singleton
    @Provides
    fun provideEngagementDao(database: MultiboxDatabase): EngagementDao =
        database.engagementDao()

    @Singleton
    @Provides
    fun provideFavoritesDao(database: MultiboxDatabase): FavoritesDao =
        database.favoritesDao()

    @Singleton
    @Provides
    fun provideLyricsDao(database: MultiboxDatabase): LyricsDao =
        database.lyricsDao()

    @Singleton
    @Provides
    fun provideLocalPlaylistDao(database: MultiboxDatabase): LocalPlaylistDao =
        database.localPlaylistDao()

    @Singleton
    @Provides
    fun provideListenBrainzDao(database: MultiboxDatabase): ListenBrainzDao =
        database.listenBrainzDao()

    @Singleton
    @Provides
    fun provideAudioBookmarkDao(database: MultiboxDatabase): AudioBookmarkDao =
        database.audioBookmarkDao()
}
