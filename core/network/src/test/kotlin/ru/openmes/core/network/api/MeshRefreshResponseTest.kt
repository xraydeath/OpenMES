package ru.openmes.core.network.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshRefreshResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `полный ответ — оба токена`() {
        val response = json.decodeFromString<MeshRefreshResponse>(
            """{"access_token":"mesh-a","refresh_token":"mesh-r","expires_in":3600}""",
        )
        assertEquals("mesh-a", response.accessToken)
        assertEquals("mesh-r", response.refreshToken)
    }

    @Test
    fun `без ротации refresh_token — null, сохраняем прежний`() {
        val response = json.decodeFromString<MeshRefreshResponse>(
            """{"access_token":"mesh-a"}""",
        )
        assertEquals("mesh-a", response.accessToken)
        assertNull(response.refreshToken)
    }

    @Test
    fun `пустой ответ — пустой access, обновление не применяем`() {
        val response = json.decodeFromString<MeshRefreshResponse>("""{}""")
        assertEquals("", response.accessToken)
        assertNull(response.refreshToken)
    }
}
