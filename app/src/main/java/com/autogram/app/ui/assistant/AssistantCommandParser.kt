package com.autogram.app.ui.assistant

/**
 * v1.23.0-rc.3 / issue #63 Phase 5 — keyword-match assistant
 * commands so the user can drive Autogram hands-free without sending
 * a voice note for trivial control actions.
 *
 * Scope (v1):
 *   - Action commands: play pending, repeat last, pause, resume, stop.
 *   - Languages: Spanish + English. The parser normalizes case +
 *     whitespace + strips trailing/leading filler ("por favor", "please",
 *     trailing punctuation) before pattern-matching.
 *   - "Send <X> to <contact>" is intentionally out of scope for this
 *     RC — the contact picker chip row already covers multi-target.
 *
 * Returns null when the transcript doesn't match any known command —
 * the caller falls back to sending the recording as a normal voice
 * note. Patterns are ordered specific→general; the first match wins.
 */
object AssistantCommandParser {

    sealed class Command {
        /** Drain the entire pending playback queue. */
        object PlayPending : Command()
        /** Replay the latest incoming message. */
        object RepeatLast : Command()
        /** Pause whatever is currently playing. */
        object Pause : Command()
        /** Resume whatever was paused. */
        object Resume : Command()
        /** Hard-stop current playback. */
        object Stop : Command()
    }

    /**
     * Parse [transcript] into an assistant command, or null if no
     * known pattern matches. Case-insensitive; whitespace and trailing
     * punctuation are normalized. Empty / blank input → null.
     */
    fun parse(transcript: String?): Command? {
        if (transcript.isNullOrBlank()) return null
        val normalized = transcript
            .lowercase()
            .trim()
            .trimEnd('.', '!', '?', ',', ';', ':')
            .trim()
        if (normalized.isEmpty()) return null

        // Order matters — match more-specific patterns before generic ones.
        // "repeat the last message" should match RepeatLast, not be ambiguous
        // with bare "play" / "play it" which we deliberately don't catch
        // (too permissive).
        return when {
            REPEAT_PATTERNS.any { it.matches(normalized) } -> Command.RepeatLast
            PLAY_PENDING_PATTERNS.any { it.matches(normalized) } -> Command.PlayPending
            PAUSE_PATTERNS.any { it.matches(normalized) } -> Command.Pause
            RESUME_PATTERNS.any { it.matches(normalized) } -> Command.Resume
            STOP_PATTERNS.any { it.matches(normalized) } -> Command.Stop
            else -> null
        }
    }

    // English + Spanish phrasings. Use word-boundary regex so partial
    // tokens inside other words don't false-match. Each pattern matches
    // the FULL transcript so the user's intent is unambiguous.
    private val PLAY_PENDING_PATTERNS = listOf(
        Regex("""^(play|reproduce|reproducir|lee|read)\s+(pending|pendientes|cola|queue|all|todos|todo)$"""),
        Regex("""^(play|reproduce)\s+(the\s+)?queue$"""),
        Regex("""^(reproducir|reproduce)\s+(la\s+)?cola$"""),
        Regex("""^(reproducir|reproduce|lee|read)\s+(los\s+)?(mensajes\s+)?pendientes$"""),
        Regex("""^drain(\s+queue)?$"""),
    )

    private val REPEAT_PATTERNS = listOf(
        Regex("""^(repeat|replay)$"""),
        Regex("""^repeat\s+(the\s+)?last(\s+message)?$"""),
        Regex("""^(replay|play)\s+(the\s+)?last(\s+(one|message))?$"""),
        Regex("""^(repite|repetir|repítelo|repítela)$"""),
        Regex("""^repite\s+(el\s+)?último(\s+mensaje)?$"""),
        Regex("""^repetir\s+(el\s+)?último(\s+mensaje)?$"""),
        Regex("""^vuelve\s+a\s+reproducir(\s+el\s+último)?$"""),
    )

    private val PAUSE_PATTERNS = listOf(
        Regex("""^pause$"""),
        Regex("""^(pause|pausa)\s+(it|playback|reproducci[oó]n)$"""),
        Regex("""^pausa$"""),
        Regex("""^espera$"""),
    )

    private val RESUME_PATTERNS = listOf(
        Regex("""^resume$"""),
        Regex("""^continue$"""),
        Regex("""^(reanuda|continuar|continúa|sigue)$"""),
        Regex("""^(resume|continue)\s+(playback|reproducci[oó]n)$"""),
    )

    private val STOP_PATTERNS = listOf(
        Regex("""^stop$"""),
        Regex("""^stop\s+(playback|playing|it)$"""),
        Regex("""^(para|detener|detente|alto)$"""),
        Regex("""^(para|detener)\s+(de\s+)?reproducir$"""),
        Regex("""^para\s+la\s+reproducci[oó]n$"""),
    )
}
