package com.elyndra.launcher.ui.masha.live

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import com.elyndra.launcher.ui.masha.MashaEars
import com.elyndra.launcher.ui.masha.live.LiveTurns.State
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La conversación en vivo de punta a punta con un reconocedor falso: el VAD
 * (tramos por un [LiveEar] de verdad) → el reconocedor → el texto final → el
 * chat → su voz → otra vez escuchando. Y lo que pasa cuando el reconocedor no
 * oye de verdad: relevo y "No te estoy escuchando bien".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoiceTest {

    private class FakeEars : LiveVoice.Ears {
        var streamWorks = true
        val calls = mutableListOf<String>()
        override fun listenToStream(language: String): Boolean {
            calls += "stream"
            return streamWorks
        }

        override fun listenToMic(language: String) {
            calls += "mic"
        }

        override fun finish() {
            calls += "finish"
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private class FakeMic : LiveVoice.Mic {
        val ear = LiveEar()
        var running = false
        var starts = 0
        var stops = 0
        override var silencedMs = 0
        override var backlogMs = 0
        override val inTurn: Boolean get() = ear.segmenter.inTurn
        override val speechMs: Int get() = ear.segmenter.speechMs
        override val silenceMs: Int get() = ear.segmenter.silence
        override fun start() {
            running = true
            starts++
        }

        override fun stop() {
            running = false
            stops++
        }

        override fun closeStream() = Unit
        override fun clearTurn() = ear.segmenter.clear()
        override fun report() = "fake mic"
        override fun levels() = "mic=-30.0dB"
    }

    private class FakeVoice : LiveVoice.Voice {
        override var queued by mutableStateOf(false)
        override var paused by mutableStateOf(false)
        override val sayingLast = false
        override val nowSaying: String? = null
        override fun pause() {
            paused = true
        }

        override fun resume() {
            paused = false
        }

        override fun stop() {
            queued = false
            paused = false
        }

        override fun spokenSoFar() = ""
    }

    private class FakePresence : LiveVoice.Presence {
        override var speaking by mutableStateOf(false)
        override var listening = false
    }

    private class FakeChat : LiveVoice.Chat {
        override var busy by mutableStateOf(false)
        val sent = mutableListOf<String>()
        var stops = 0
        var forced = 0

        // Como MashaController.say: ocupado, no acepta nada; a la fuerza, corta lo que hubiera y manda.
        override fun send(text: String, language: String, force: Boolean): Boolean {
            if (busy && force) {
                busy = false
                forced++
            }
            if (text.isBlank() || busy) return false
            sent += text
            busy = true
            return true
        }

        override fun stop() {
            stops++
        }

        override fun interrupt(spoken: String) = Unit
    }

    private class Harness(val test: TestScope) {
        val ears = FakeEars()
        val mic = FakeMic()
        val voice = FakeVoice()
        val presence = FakePresence()
        val chat = FakeChat()
        val logs = mutableListOf<String>()
        val live = LiveVoice(
            ears = ears,
            voice = voice,
            presence = presence,
            chat = chat,
            mic = mic,
            scope = test.backgroundScope,
            language = { "es-ES" },
            voiceBargeIn = { true },
            hasPermission = { true },
            onEnded = {},
            clock = { test.testScheduler.currentTime },
            log = { logs += it },
        )

        /** Los cambios de estado llegan a quien los mira (snapshotFlow) y corre lo pendiente. */
        fun settle() {
            Snapshot.sendApplyNotifications()
            test.runCurrent()
        }

        /** [n] tramos de 16 ms por el oído de verdad: voz del usuario o silencio, con Lucy callada. */
        fun frames(n: Int, voiced: Boolean) = repeat(n) {
            mic.ear.accept(if (voiced) -30f else -70f, EchoGate.SILENT, if (voiced) 0.9f else 0.05f)?.let(live::onSignal)
        }

        /** El usuario dice algo (~640 ms de voz) y se calla. */
        fun userSpeaks() {
            frames(40, voiced = true)
            frames(50, voiced = false)
        }

        /** Ella contesta, termina y se calla: la sesión vuelve a escuchar. */
        fun lucyReplies() {
            presence.speaking = true
            voice.queued = true
            settle()
            chat.busy = false
            presence.speaking = false
            voice.queued = false
            settle()
            test.advanceTimeBy(LiveVoice.SETTLE_MS + 50)
            settle()
        }

        fun start() {
            live.start()
            settle()
        }
    }

    @Test
    fun aSpokenTurnGoesFromTheVadToTheChatAndBackToListening() = runTest {
        val h = Harness(this)
        h.start()
        assertEquals(State.Waiting, h.live.state)
        assertTrue(h.mic.running)

        h.frames(20, voiced = true)
        assertEquals(State.UserSpeaking, h.live.state)
        assertEquals(listOf("stream"), h.ears.calls)
        h.live.onPartial("pon el acento")
        // El parcial es el subtítulo; nada va al campo de texto ni se manda aún.
        assertEquals("pon el acento", h.live.partial)
        assertTrue(h.chat.sent.isEmpty())

        h.frames(50, voiced = false)
        assertEquals(listOf("stream", "finish"), h.ears.calls)

        h.live.onHeard("pon el acento morado")
        assertEquals(listOf("pon el acento morado"), h.chat.sent)
        assertEquals(State.Thinking, h.live.state)

        h.presence.speaking = true
        h.voice.queued = true
        h.settle()
        assertEquals(State.Speaking, h.live.state)

        h.chat.busy = false
        h.presence.speaking = false
        h.voice.queued = false
        h.settle()
        advanceTimeBy(LiveVoice.SETTLE_MS + 50)
        h.settle()
        assertEquals(State.Waiting, h.live.state)
        assertTrue(h.mic.running)
        assertFalse(h.live.struggling)
    }

    @Test
    fun whenTheRecognizerTakesTheMicOursLetsGoAndItsTextStillArrives() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        // Abrió su propio micrófono: Android le da a él el audio y al nuestro ceros.
        h.mic.silencedMs = 400
        advanceTimeBy(LiveVoice.MONITOR_MS + 10)
        assertEquals(1, h.mic.stops)
        assertFalse("stop" in h.ears.calls) // se le deja escuchar
        assertTrue(h.logs.any { "recognizer took the mic" in it })

        h.mic.silencedMs = 0
        h.live.onHeard("hola Lucy")
        assertEquals(listOf("hola Lucy"), h.chat.sent)
        assertTrue(h.mic.running)

        // Desde ahora, relevo: el siguiente turno lo escucha él directamente.
        h.lucyReplies()
        assertEquals(State.Waiting, h.live.state)
        h.frames(20, voiced = true)
        assertEquals("mic", h.ears.calls.last())
        assertEquals(2, h.mic.stops)
    }

    @Test
    fun aRecognizerThatDoesNotReadOurStreamIsSwappedForHandoffMidTurn() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        h.mic.backlogMs = 700
        advanceTimeBy(LiveVoice.MONITOR_MS + 10)
        assertEquals(listOf("stream", "stop", "mic"), h.ears.calls)
        assertEquals(State.UserSpeaking, h.live.state)
        h.live.onHeard("qué juego me recomiendas")
        assertEquals(listOf("qué juego me recomiendas"), h.chat.sent)
    }

    @Test
    fun twoEmptyStreamTurnsSwitchToHandoffAndAThirdShowsTheStatus() = runTest {
        val h = Harness(this)
        h.start()
        repeat(2) {
            h.userSpeaks()
            h.live.onEarsFailure(MashaEars.Failure.NoMatch, 7)
            assertEquals(State.Waiting, h.live.state)
        }
        assertFalse(h.live.struggling)
        assertEquals(listOf("stream", "finish", "stream", "finish"), h.ears.calls)

        h.userSpeaks()
        assertEquals("mic", h.ears.calls.last())
        h.live.onEarsFailure(MashaEars.Failure.NoMatch, 6)
        assertTrue(h.live.struggling)
        assertTrue(h.live.active) // nunca termina la sesión

        // En cuanto llega un texto, el aviso se va.
        h.mic.clearTurn()
        h.userSpeaks()
        h.live.onHeard("ahora sí")
        assertFalse(h.live.struggling)
        assertEquals(listOf("ahora sí"), h.chat.sent)
    }

    @Test
    fun errorsAndEmptyResultsAlsoCountTowardsTheStatus() = runTest {
        val h = Harness(this)
        h.ears.streamWorks = false // antes de Android 13: siempre relevo
        h.start()
        listOf<(LiveVoice) -> Unit>(
            { it.onHeard("") },
            { it.onEarsFailure(MashaEars.Failure.Network, 2) },
            { it.onEarsFailure(MashaEars.Failure.NoMatch, 7) },
        ).forEach { fail ->
            h.mic.clearTurn()
            h.userSpeaks()
            fail(h.live)
            advanceTimeBy(RecognizerBackoff.MAX_MS)
            h.settle()
        }
        assertTrue(h.live.struggling)
        assertEquals(State.Waiting, h.live.state)
    }

    @Test
    fun whatTheUserSaysWhileSheAnswersSomethingTypedIsNotDropped() = runTest {
        val h = Harness(this)
        h.start()
        // Contesta a algo escrito y aún no suena: la sesión sigue esperando.
        h.chat.busy = true
        h.settle()
        h.userSpeaks()
        h.live.onHeard("para, mejor otra cosa")
        assertEquals(1, h.chat.stops)
        assertTrue(h.chat.sent.isEmpty())

        h.chat.busy = false
        h.settle()
        assertEquals(listOf("para, mejor otra cosa"), h.chat.sent)
        assertEquals(State.Thinking, h.live.state)
    }

    @Test
    fun aMicThatOnlyGivesSilenceShowsTheStatusInsteadOfFailingQuietly() = runTest {
        val h = Harness(this)
        h.start()
        h.mic.silencedMs = HearingHealth.SILENCED_LIMIT_MS
        advanceTimeBy(LiveVoice.MONITOR_MS + 10)
        assertTrue(h.live.struggling)
        h.mic.silencedMs = 0
        advanceTimeBy(LiveVoice.MONITOR_MS + 10)
        assertFalse(h.live.struggling)
    }

    @Test
    fun lockingClearsTheStatus() = runTest {
        val h = Harness(this)
        h.start()
        h.mic.silencedMs = HearingHealth.SILENCED_LIMIT_MS
        advanceTimeBy(LiveVoice.MONITOR_MS + 10)
        assertTrue(h.live.struggling)
        h.live.lock()
        assertFalse(h.live.struggling)
        assertFalse(h.mic.running)
    }

    /* ── todo turno acaba en un envío (el texto nunca se queda en el campo) ── */

    /** La línea de log del turno: `turn end via=… → sent|queued|dropped`. */
    private fun Harness.turnLine(): String = logs.last { it.startsWith("turn end via=") }

    @Test
    fun aFinalResultIsSentDirectlyAndLogsTheTurnEnd() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        h.live.onPartial("pon el")
        h.live.onPartial("pon el acento")
        h.frames(50, voiced = false)
        h.live.onHeard("pon el acento morado")
        assertEquals(listOf("pon el acento morado"), h.chat.sent)
        assertEquals(State.Thinking, h.live.state)
        assertEquals("", h.live.partial)
        val line = h.turnLine()
        assertTrue(line, line.startsWith("turn end via=silence source=final") && line.endsWith("→ sent"))
    }

    @Test
    fun aFinalResultWithoutOurSilenceEndsTheTurnViaResults() = runTest {
        val h = Harness(this)
        h.ears.streamWorks = false
        h.start()
        h.frames(20, voiced = true)
        h.live.onHeard("hola Lucy")
        assertEquals(listOf("hola Lucy"), h.chat.sent)
        assertTrue(h.turnLine(), h.turnLine().startsWith("turn end via=results source=final"))
    }

    @Test
    fun withOnlyPartialsTheLastOneIsSentSoonAfterTheSilence() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        h.live.onPartial("qué juego")
        h.live.onPartial("qué juego me recomiendas")
        h.frames(50, voiced = false) // fin de turno por silencio; el reconocedor nunca da su resultado
        advanceTimeBy(LiveVoice.FINAL_WAIT_MS - 50)
        h.settle()
        assertTrue(h.chat.sent.isEmpty()) // aún se espera el resultado final
        advanceTimeBy(100)
        h.settle()
        assertEquals(listOf("qué juego me recomiendas"), h.chat.sent)
        assertEquals("stop", h.ears.calls.last()) // el reconocedor se suelta: lo oído ya va
        val line = h.turnLine()
        assertTrue(line, line.startsWith("turn end via=silence source=partial") && line.endsWith("→ sent"))
        // Si el final llega tarde, no se manda dos veces.
        h.live.onHeard("qué juego me recomiendas hoy")
        assertEquals(1, h.chat.sent.size)
    }

    @Test
    fun anErrorAfterPartialsSendsTheLastPartial() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        h.live.onPartial("abre el último juego")
        h.frames(50, voiced = false)
        h.live.onEarsFailure(MashaEars.Failure.NoMatch, 7)
        assertEquals(listOf("abre el último juego"), h.chat.sent)
        assertTrue(h.turnLine(), h.turnLine().startsWith("turn end via=silence source=partial"))
    }

    @Test
    fun aClientErrorRightAfterFinishingDoesNotLoseThePartials() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        h.live.onPartial("hola")
        h.frames(50, voiced = false)
        // Algunos reconocedores, al cerrarles el pipe, dan ERROR_CLIENT al poco de empezar.
        h.live.onEarsFailure(MashaEars.Failure.StreamUnsupported, 5)
        assertEquals(listOf("hola"), h.chat.sent)
    }

    @Test
    fun inFallbackModeThePartialTimeoutEndsTheTurn() = runTest {
        val h = Harness(this)
        h.ears.streamWorks = false // relevo: el reconocedor tiene el micrófono, el nuestro no oye el final
        h.start()
        h.frames(20, voiced = true)
        assertEquals("mic", h.ears.calls.last())
        h.live.onPartial("hola")
        advanceTimeBy(300)
        h.live.onPartial("hola Lucy")
        advanceTimeBy(LiveVoice.PARTIAL_TIMEOUT_MS - 50)
        h.settle()
        assertTrue(h.chat.sent.isEmpty())
        advanceTimeBy(100)
        h.settle()
        assertEquals(listOf("hola Lucy"), h.chat.sent)
        assertTrue(h.mic.running) // nuestro micrófono vuelve
        val line = h.turnLine()
        assertTrue(line, line.startsWith("turn end via=partialTimeout source=partial") && line.endsWith("→ sent"))
    }

    @Test
    fun inFallbackModeTheRecognizersEndOfSpeechEndsTheTurn() = runTest {
        val h = Harness(this)
        h.ears.streamWorks = false
        h.start()
        h.frames(20, voiced = true)
        h.live.onPartial("pon música")
        h.live.onEndOfSpeech()
        advanceTimeBy(LiveVoice.FINAL_WAIT_MS + 50)
        h.settle()
        assertEquals(listOf("pon música"), h.chat.sent)
        assertTrue(h.turnLine(), h.turnLine().startsWith("turn end via=endOfSpeech source=partial"))
    }

    @Test
    fun whenTheRecognizerTakesOurStreamsMicTheTurnStillEnds() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        h.mic.silencedMs = 400 // abrió su propio micrófono: el nuestro da ceros
        advanceTimeBy(LiveVoice.MONITOR_MS + 10)
        h.live.onPartial("apaga la música")
        advanceTimeBy(LiveVoice.PARTIAL_TIMEOUT_MS + 100)
        h.settle()
        assertEquals(listOf("apaga la música"), h.chat.sent)
    }

    @Test
    fun whileOurMicStillHearsTheUserThePartialTimeoutWaits() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(20, voiced = true)
        h.live.onPartial("quiero")
        advanceTimeBy(LiveVoice.PARTIAL_TIMEOUT_MS + 300)
        h.settle()
        assertTrue(h.chat.sent.isEmpty()) // sigue hablando (nuestro VAD lo oye): no se corta
        h.frames(50, voiced = false)
        h.live.onHeard("quiero jugar a algo")
        assertEquals(listOf("quiero jugar a algo"), h.chat.sent)
    }

    @Test
    fun aTurnWithNoTextNeverHangs() = runTest {
        val h = Harness(this)
        h.start()
        h.userSpeaks() // ni parciales ni resultado ni error
        advanceTimeBy(LiveVoice.NO_TEXT_MS + 100)
        h.settle()
        assertEquals(State.Waiting, h.live.state)
        assertTrue(h.chat.sent.isEmpty())
        assertTrue(h.turnLine(), h.turnLine().endsWith("→ dropped(noText)"))
    }

    @Test
    fun aChatThatStaysBusyTimesOutAndStillSends() = runTest {
        val h = Harness(this)
        h.start()
        h.chat.busy = true // un "ocupado" que stop() no suelta (una confirmación aplicándose, el saludo)
        h.settle()
        h.userSpeaks()
        h.live.onHeard("sí, hazlo")
        assertTrue(h.logs.any { it.startsWith("turn end via=silence") && it.endsWith("→ queued(chatBusy)") })
        advanceTimeBy(LiveVoice.GATE_MS + 50)
        h.settle()
        assertEquals(listOf("sí, hazlo"), h.chat.sent)
        assertEquals(1, h.chat.forced)
        assertTrue(h.logs.any { it.startsWith("send dequeued") && "force=true" in it && it.endsWith("→ sent") })
    }

    @Test
    fun theWholeTurnIsSentWithinABudgetAfterTheUserStops() = runTest {
        val h = Harness(this)
        h.start()
        h.frames(40, voiced = true)
        h.live.onPartial("cuéntame un chiste")
        val stoppedAt = testScheduler.currentTime
        // Los tramos van sin tiempo: el silencio de fin de turno (700 ms) se cuenta aparte.
        h.frames(50, voiced = false)
        advanceTimeBy(LiveVoice.FINAL_WAIT_MS + 10)
        h.settle()
        val elapsed = testScheduler.currentTime - stoppedAt + SpeechSegmenter.END_SILENCE_MS
        assertEquals(listOf("cuéntame un chiste"), h.chat.sent)
        assertTrue("sent ${elapsed}ms after the user stopped", elapsed <= 1_500)
    }

    @Test
    fun aRecognizerWithoutNetworkShowsTheOfflineStatusUntilItAnswersAgain() = runTest {
        val h = Harness(this)
        h.start()
        repeat(2) {
            h.mic.clearTurn()
            h.userSpeaks()
            h.live.onEarsFailure(MashaEars.Failure.Network, 2)
            advanceTimeBy(RecognizerBackoff.MAX_MS)
            h.settle()
        }
        assertTrue(h.live.offline)
        assertTrue(h.live.active) // la escucha sigue
        h.mic.clearTurn()
        h.userSpeaks()
        h.live.onHeard("ya hay red")
        assertFalse(h.live.offline)
        assertEquals(listOf("ya hay red"), h.chat.sent)
    }

    @Test
    fun oneNetworkHiccupIsNotWorthAStatus() = runTest {
        val h = Harness(this)
        h.start()
        h.userSpeaks()
        h.live.onEarsFailure(MashaEars.Failure.Network, 2)
        assertFalse(h.live.offline)
    }

    /* ── ella hablando: no se interrumpe sola ── */

    /** Ella empieza a contestar algo (sin turno de voz): la sesión pasa a "habla". */
    private fun Harness.lucyStartsTalking() {
        presence.speaking = true
        voice.queued = true
        settle()
    }

    /** Un ruido que abre turno sobre su voz y se calla: pausa, sin texto, y ella sigue. */
    private fun Harness.noiseOverHer() {
        frames(30, voiced = true)
        assertEquals(State.Held, live.state)
        assertTrue(voice.paused)
        frames(20, voiced = false)
        test.advanceTimeBy(LiveVoice.HOLD_TICK_MS * 3)
        settle()
        assertEquals(State.Speaking, live.state)
        assertFalse(voice.paused)
    }

    @Test
    fun twoPausesWithoutTextTurnVoiceBargeInOffForTheRestOfHerReply() = runTest {
        val h = Harness(this)
        h.start()
        h.lucyStartsTalking()
        assertEquals(State.Speaking, h.live.state)
        h.noiseOverHer()
        h.noiseOverHer()
        assertTrue(h.logs.any { it.startsWith("voice barge-in off for this reply") })
        // La tercera vez ya no la pausa.
        h.frames(30, voiced = true)
        assertEquals(State.Speaking, h.live.state)
        assertFalse(h.voice.paused)
        // El botón sigue cortándola.
        h.live.bargeIn()
        assertEquals(State.UserSpeaking, h.live.state)
    }

    @Test
    fun voiceBargeInComesBackOnHerNextReply() = runTest {
        val h = Harness(this)
        h.start()
        h.lucyStartsTalking()
        h.noiseOverHer()
        h.noiseOverHer()
        // Termina esa respuesta y empieza otra.
        h.presence.speaking = false
        h.voice.queued = false
        h.settle()
        advanceTimeBy(LiveVoice.SETTLE_MS + 50)
        h.settle()
        assertEquals(State.Waiting, h.live.state)
        h.lucyStartsTalking()
        h.frames(30, voiced = true)
        assertEquals(State.Held, h.live.state)
    }

    @Test
    fun everyPauseAndResumeIsLoggedWithItsReason() = runTest {
        val h = Harness(this)
        h.start()
        h.lucyStartsTalking()
        h.noiseOverHer()
        assertTrue(h.logs.toString(), h.logs.any { it.startsWith("pause voice reason=userSpeech") && "levels=" in it })
        assertTrue(h.logs.toString(), h.logs.any { it.startsWith("resume voice reason=noText") && "heldMs=" in it })
    }

    @Test
    fun interruptingHerInHandoffAndSendingWhileHerReplyStillRunsDoesNotBreak() = runTest {
        val h = Harness(this)
        h.ears.streamWorks = false // relevo: el reconocedor se queda el micrófono
        h.start()
        h.chat.busy = true // su respuesta aún llega
        h.lucyStartsTalking()
        // Le habla encima, en serio (más de 0,9 s): se corta y escucha el reconocedor.
        h.frames(70, voiced = true)
        advanceTimeBy(LiveVoice.HOLD_TICK_MS * 2)
        h.settle()
        assertEquals(State.UserSpeaking, h.live.state)
        assertEquals("mic", h.ears.calls.last())
        assertFalse(h.mic.running)
        // Cortada: su voz se calla (como MashaVoice.stop).
        h.presence.speaking = false
        h.settle()
        h.live.onPartial("para y dime la hora")
        h.live.onEndOfSpeech()
        h.live.onHeard("para y dime la hora")
        advanceTimeBy(LiveVoice.GATE_MS + 50)
        h.settle()
        assertEquals(listOf("para y dime la hora"), h.chat.sent)
        assertTrue(h.mic.running) // nuestro micrófono vuelve
        assertEquals(State.Thinking, h.live.state)
    }
}
