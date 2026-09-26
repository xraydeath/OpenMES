package ru.openmes.core.data

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

class RefreshResultTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, """{"error":"x"}""".toResponseBody(null)))

    @Test
    fun `явный отказ SUDIR — повторный вход`() {
        assertEquals(RefreshResult.REJECTED, classifyRefreshError(http(400)))
        assertEquals(RefreshResult.REJECTED, classifyRefreshError(http(401)))
    }

    @Test
    fun `сеть и 5xx не разлогинивают`() {
        assertEquals(RefreshResult.FAILED, classifyRefreshError(http(500)))
        assertEquals(RefreshResult.FAILED, classifyRefreshError(http(503)))
        assertEquals(RefreshResult.FAILED, classifyRefreshError(http(429)))
        assertEquals(RefreshResult.FAILED, classifyRefreshError(IOException("offline")))
        assertEquals(RefreshResult.FAILED, classifyRefreshError(SocketTimeoutException()))
        assertEquals(RefreshResult.FAILED, classifyRefreshError(IllegalStateException("parse")))
    }
}
