package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktAuthServiceTest {
    @Test
    fun `refresh token 400 clears credentials and prevents another refresh`() = runTest {
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>()
        var authState = authenticatedState()

        coEvery { traktAuthDataStore.getCurrentState() } answers { authState }
        coEvery { traktAuthDataStore.clearAuth() } answers { authState = TraktAuthState() }
        coEvery { authSessionNoticeDataStore.markTraktReconnectRequired() } returns Unit
        coEvery { traktApi.refreshToken(any()) } returns Response.error(400, "invalid_grant".toResponseBody())

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = authSessionNoticeDataStore
        )

        assertFalse(service.refreshTokenIfNeeded(force = true))
        assertFalse(service.refreshTokenIfNeeded(force = true))

        coVerify(exactly = 1) { traktApi.refreshToken(any()) }
        coVerify(exactly = 1) { authSessionNoticeDataStore.markTraktReconnectRequired() }
        coVerify(exactly = 1) { traktAuthDataStore.clearAuth() }
    }

    @Test
    fun `the code challenge is the s256 of the verifier`() {
        val verifier = newTraktCodeVerifier()

        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })

        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray())
        )
        assertEquals(expected, traktCodeChallenge(verifier))
    }

    @Test
    fun `a secretless device flow carries a challenge and stores its verifier`() = runTest {
        if (BuildConfig.TRAKT_CLIENT_ID.isBlank()) return@runTest // the flow is off without a client id
        val traktApi = mockk<TraktApi>()
        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        val requestSlot = slot<TraktDeviceCodeRequestDto>()
        val verifierSlot = slot<String>()

        coEvery { traktAuthDataStore.getCurrentState() } returns TraktAuthState()
        coEvery { traktAuthDataStore.saveDeviceFlow(any(), any()) } returns Unit
        coEvery { traktApi.requestDeviceCode(any()) } returns Response.success(
            TraktDeviceCodeResponseDto(
                deviceCode = "device-code",
                userCode = "USERCODE",
                verificationUrl = "https://auth.trakt.tv/activate",
                expiresIn = 600,
                interval = 5
            )
        )

        val service = TraktAuthService(
            context = mockk<Context>(relaxed = true),
            traktApi = traktApi,
            traktAuthDataStore = traktAuthDataStore,
            authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        )

        assertTrue(service.startDeviceAuth().isSuccess)

        coVerify { traktApi.requestDeviceCode(capture(requestSlot)) }

        if (BuildConfig.TRAKT_CLIENT_SECRET.isBlank()) {
            coVerify { traktAuthDataStore.saveDeviceFlow(any(), capture(verifierSlot)) }
            assertEquals("S256", requestSlot.captured.codeChallengeMethod)
            assertEquals(traktCodeChallenge(verifierSlot.captured), requestSlot.captured.codeChallenge)
        } else {
            coVerify { traktAuthDataStore.saveDeviceFlow(any(), isNull()) }
            assertNull(requestSlot.captured.codeChallenge)
        }
    }

    private fun authenticatedState(): TraktAuthState {
        return TraktAuthState(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            tokenType = "bearer",
            createdAt = 1L,
            expiresIn = 3600
        )
    }
}
