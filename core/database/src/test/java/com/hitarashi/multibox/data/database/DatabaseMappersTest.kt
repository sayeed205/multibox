package com.hitarashi.multibox.data.database

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DatabaseMappersTest {

    @Test
    fun `AlbumEntity normalizes volatile artwork URIs to stable local song URI`() {
        val entity = AlbumEntity(
            id = 42L,
            title = "  Test Album ",
            artistName = "Test Artist",
            artistId = 7L,
            albumArtUriString = "content://com.hitarashi.multibox.artwork/song/99?t=123",
            songCount = 5,
            dateAdded = 1000L,
            year = 2024
        )

        val album = entity.toAlbum()

        assertThat(album.title).isEqualTo("Test Album")
        assertThat(album.albumArtUriString).isEqualTo("multibox_local_art://song/99")
    }

    @Test
    fun `PlaylistEntity round trips through domain model`() {
        val entity = PlaylistEntity(
            id = "pl-1",
            name = "Late Night",
            createdAt = 10L,
            lastModified = 20L,
            source = "LOCAL"
        )

        val domain = entity.toPlaylist(songIds = listOf("1", "2"))

        assertThat(domain.songIds).containsExactly("1", "2").inOrder()
        assertThat(domain.toEntity()).isEqualTo(entity)
    }

    @Test
    fun `SearchHistoryEntity round trips through SearchHistoryItem`() {
        val entity = SearchHistoryEntity(id = 3L, query = "synthwave", timestamp = 999L)
        val item = entity.toSearchHistoryItem()

        assertThat(item.query).isEqualTo("synthwave")
        assertThat(item.toEntity()).isEqualTo(entity)
    }
}
