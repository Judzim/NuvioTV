package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.local.TraktSettingsDataStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A confirmed rewatch cannot ride the scrobble that already recorded the watch, so it has to leave as
 * its own `/sync/history` write. These tests pin that body, the answer it reads back, and the session
 * id the next write has to carry.
 */
class SimklRewatchConfirmationTest {
    private val json = Json

    @Test
    fun `confirmed movie rewatch writes the recorded timestamp as a rewatch`() = runTest {
        val engine = RecordingEngine(response(201, RECORDED_MOVIE_RESPONSE))
        val coordinator = coordinator(engine)

        coordinator.offer(SimklRewatchPrompt(movie(), "2026-05-10T20:00:00Z"))
        val outcome = coordinator.confirm()

        assertEquals(listOf("/sync/history"), engine.paths)
        assertTrue(engine.urls.single().contains("allow_rewatch=yes"))
        val item = body(engine).getValue("movies").jsonArray.single().jsonObject
        assertEquals("2026-05-10T20:00:00Z", item.getValue("watched_at").jsonPrimitive.content)
        assertTrue(item.getValue("is_rewatch").jsonPrimitive.content.toBoolean())
        assertNull(item["rewatch_id"])
        assertEquals("The Walking Dead", item.getValue("title").jsonPrimitive.content)
        assertEquals(7482L, outcome?.rewatchId)
        assertEquals(SimklRewatchStatus.ACTIVE, outcome?.status)
        assertEquals(1, outcome?.addedCount)
        assertNull(coordinator.pending.value)
    }

    @Test
    fun `confirmed episode rewatch keeps the coordinates and carries no show level timestamp`() = runTest {
        val engine = RecordingEngine(response(201, RECORDED_EPISODE_RESPONSE))
        val coordinator = coordinator(engine)

        coordinator.offer(
            SimklRewatchPrompt(show(TrackingEpisode(season = 2, number = 5)), "2026-05-11T21:30:00Z")
        )
        coordinator.confirm()

        assertTrue(body(engine)["movies"]?.jsonArray.orEmpty().isEmpty())
        val item = body(engine).getValue("shows").jsonArray.single().jsonObject
        assertTrue(item.getValue("is_rewatch").jsonPrimitive.content.toBoolean())
        assertNull(item["watched_at"])
        val season = item.getValue("seasons").jsonArray.single().jsonObject
        // Simkl reads the season from the block, so the episode only carries its own number.
        assertEquals(2, season.getValue("number").jsonPrimitive.content.toInt())
        val episode = season.getValue("episodes").jsonArray.single().jsonObject
        assertNull(episode["season"])
        assertEquals(5, episode.getValue("number").jsonPrimitive.content.toInt())
        assertEquals("2026-05-11T21:30:00Z", episode.getValue("watched_at").jsonPrimitive.content)
    }

    @Test
    fun `a rewatch without season coordinates goes into the flat episode list`() = runTest {
        val engine = RecordingEngine(response(201, RECORDED_EPISODE_RESPONSE))
        val coordinator = coordinator(engine)

        coordinator.offer(
            SimklRewatchPrompt(
                movie().copy(kind = TrackingMediaKind.ANIME, episode = TrackingEpisode(number = 7)),
                "2026-05-12T20:00:00Z"
            )
        )
        coordinator.confirm()

        val item = body(engine).getValue("shows").jsonArray.single().jsonObject
        assertTrue(item["seasons"]?.jsonArray.orEmpty().isEmpty())
        val episode = item.getValue("episodes").jsonArray.single().jsonObject
        assertEquals(7, episode.getValue("number").jsonPrimitive.content.toInt())
        assertEquals("2026-05-12T20:00:00Z", episode.getValue("watched_at").jsonPrimitive.content)
    }

    @Test
    fun `the session id from the first answer is pinned on the next write`() = runTest {
        val engine = RecordingEngine(
            response(201, RECORDED_MOVIE_RESPONSE),
            response(201, RECORDED_MOVIE_RESPONSE)
        )
        val coordinator = coordinator(engine)

        coordinator.offer(SimklRewatchPrompt(movie(), "2026-05-10T20:00:00Z"))
        coordinator.confirm()
        coordinator.offer(SimklRewatchPrompt(movie(), "2026-05-20T20:00:00Z"))
        coordinator.confirm()

        val pinned = body(engine, index = 1).getValue("movies").jsonArray.single().jsonObject
        assertEquals(7482L, pinned.getValue("rewatch_id").jsonPrimitive.content.toLong())
    }

    @Test
    fun `a reversed mode cancels the write`() = runTest {
        val engine = RecordingEngine(response(201, RECORDED_MOVIE_RESPONSE))
        val coordinator = coordinator(engine, mode = SimklRewatchMode.OFF)

        coordinator.offer(SimklRewatchPrompt(movie(), "2026-05-10T20:00:00Z"))

        assertNull(coordinator.confirm())
        assertTrue(engine.paths.isEmpty())
        assertNull(coordinator.pending.value)
    }

    @Test
    fun `dismissing the prompt drops it without touching the network`() = runTest {
        val engine = RecordingEngine(response(201, RECORDED_MOVIE_RESPONSE))
        val coordinator = coordinator(engine)

        coordinator.offer(SimklRewatchPrompt(movie(), "2026-05-10T20:00:00Z"))
        coordinator.dismiss()

        assertNull(coordinator.confirm())
        assertTrue(engine.paths.isEmpty())
    }

    @Test
    fun `a failed write is reported as no outcome and keeps logging out of the way`() = runTest {
        val engine = RecordingEngine(response(400, """{"error":"bad_request"}"""))
        val coordinator = coordinator(engine)

        coordinator.offer(SimklRewatchPrompt(movie(), "2026-05-10T20:00:00Z"))

        assertNull(coordinator.confirm())
        assertNull(coordinator.pending.value)
    }

    @Test
    fun `a missing answered timestamp falls back to the current time`() = runTest {
        val engine = RecordingEngine(response(201, RECORDED_MOVIE_RESPONSE))
        val coordinator = coordinator(engine)

        coordinator.offer(SimklRewatchPrompt(movie(), watchedAt = null))
        coordinator.confirm()

        val item = body(engine).getValue("movies").jsonArray.single().jsonObject
        assertTrue(item.getValue("watched_at").jsonPrimitive.content.endsWith("Z"))
    }

    @Test
    fun `silent no-op answers read as nothing added`() {
        val response = SimklApiResponse(
            status = 200,
            body = """{"added":{"movies":0,"shows":0,"episodes":0,"statuses":[]},"not_found":{}}""",
            headers = emptyMap()
        )

        val outcome = response.toSimklRewatchWriteOutcome()

        assertNull(outcome.rewatchId)
        assertNull(outcome.status)
        assertEquals(0, outcome.addedCount)
    }

    @Test
    fun `rewatch answer reads status and session even when the id is missing`() {
        val response = SimklApiResponse(
            status = 200,
            body = """{"added":{"shows":0,"episodes":0,"statuses":[{"response":{"rewatch_status":"too_soon"}}]}}""",
            headers = emptyMap()
        )

        val outcome = response.toSimklRewatchWriteOutcome()

        assertNull(outcome.rewatchId)
        assertEquals(SimklRewatchStatus.TOO_SOON, outcome.status)
        assertFalse(outcome.status!!.isRecorded)
    }

    @Test
    fun `a malformed answer does not throw`() {
        val response = SimklApiResponse(status = 200, body = "<html>", headers = emptyMap())

        val outcome = response.toSimklRewatchWriteOutcome()

        assertNull(outcome.rewatchId)
        assertNull(outcome.status)
        assertEquals(0, outcome.addedCount)
    }

    private fun coordinator(
        engine: RecordingEngine,
        mode: SimklRewatchMode = SimklRewatchMode.MANUAL
    ): SimklRewatchConfirmationCoordinator {
        val settings = mockk<TraktSettingsDataStore> {
            every { simklRewatchMode } returns MutableStateFlow(mode)
        }
        return SimklRewatchConfirmationCoordinator(
            mutationService = SimklMutationService(client(engine)),
            settingsDataStore = settings
        )
    }

    private fun client(engine: RecordingEngine): SimklApiClient = SimklApiClient(
        engine = engine,
        configuration = SimklApiConfiguration("client-id", "nuvio", "1.0"),
        authorization = { testSimklAuthorization() },
        onUnauthorized = {},
        nowEpochMs = { 1_700_000_000_000L },
        sleep = {},
        retryJitterMs = { 0L }
    )

    private fun body(engine: RecordingEngine, index: Int = 0): JsonObject =
        json.parseToJsonElement(engine.bodies[index]).jsonObject

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "The Walking Dead",
        year = 2010,
        ids = TrackingExternalIds(simkl = 2090, imdb = "tt1520211", tvdb = "153021")
    )

    private fun show(episode: TrackingEpisode) = movie().copy(
        kind = TrackingMediaKind.SHOW,
        episode = episode
    )

    private class RecordingEngine(vararg responses: SimklRawHttpResponse) : SimklHttpEngine {
        private val queued = responses.toMutableList()
        val paths = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String
        ): SimklRawHttpResponse {
            urls += url
            paths += url.substringAfter("api.simkl.com").substringBefore('?')
            bodies += body
            return queued.removeAt(0)
        }
    }

    private companion object {
        const val RECORDED_MOVIE_RESPONSE =
            """{"added":{"movies":1,"statuses":[{"response":{"rewatch_id":7482,"rewatch_status":"active","status":"completed"}}]},"not_found":{}}"""
        const val RECORDED_EPISODE_RESPONSE =
            """{"added":{"shows":1,"episodes":1,"statuses":[{"response":{"rewatch_id":7483,"rewatch_status":"active","status":"watching"}}]},"not_found":{}}"""
        fun response(status: Int, body: String) = SimklRawHttpResponse(status, body)
    }
}
