package org.fossify.gallery.helpers

import org.fossify.gallery.models.Medium
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudUploadFilterTest {
    @Test
    fun `filters out uploaded media only when enabled`() {
        val media = listOf(
            medium("/a.jpg"),
            medium("/b.jpg"),
            medium("/c.jpg")
        )
        val statuses = mapOf(
            "/a.jpg" to true,
            "/b.jpg" to false,
            "/c.jpg" to null
        )

        val filtered = CloudUploadFilter.filterMedia(media, statuses, true)

        assertEquals(listOf("/b.jpg", "/c.jpg"), filtered.map { it.path })
    }

    @Test
    fun `keeps all media when disabled`() {
        val media = listOf(
            medium("/a.jpg"),
            medium("/b.jpg")
        )
        val statuses = mapOf(
            "/a.jpg" to true,
            "/b.jpg" to false
        )

        val filtered = CloudUploadFilter.filterMedia(media, statuses, false)

        assertEquals(listOf("/a.jpg", "/b.jpg"), filtered.map { it.path })
    }

    private fun medium(path: String) = Medium(
        id = null,
        name = path.substringAfterLast('/'),
        path = path,
        parentPath = "",
        modified = 0L,
        taken = 0L,
        size = 0L,
        type = 1,
        videoDuration = 0,
        isFavorite = false,
        deletedTS = 0L,
        mediaStoreId = 0L
    )
}
