package com.autogram.app.domain.usecase

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps an incoming message's text (from `textContent` or `transcription`) to
 * up to three short canned replies the user is likely to want to send.
 *
 * All rules are deterministic regex matches — no LLM, no network. Each rule
 * declares the language of its replies, so a Spanish incoming message gets
 * Spanish chip suggestions even when the phone UI is set to English.
 *
 * The ruleset is intentionally short. Adding too many patterns produces
 * irrelevant suggestions that the user starts ignoring — worse than showing
 * nothing. Ship new rules only when a concrete incoming message would have
 * triggered a useful chip.
 */
@Singleton
class SuggestQuickReplyUseCase @Inject constructor() {

    data class Suggestion(val text: String, val ruleId: String)

    /**
     * Return up to [limit] suggested canned replies for [incomingText].
     * Never returns duplicates. Order is most-confident-first.
     */
    fun suggestFor(incomingText: String, limit: Int = 3): List<Suggestion> {
        val normalized = incomingText.lowercase().trim()
        if (normalized.isBlank()) return emptyList()
        val out = mutableListOf<Suggestion>()
        val seen = mutableSetOf<String>()
        for (rule in RULES) {
            if (out.size >= limit) break
            if (rule.trigger.containsMatchIn(normalized)) {
                for (reply in rule.replies) {
                    if (out.size >= limit) break
                    if (seen.add(reply.lowercase())) {
                        out.add(Suggestion(reply, rule.id))
                    }
                }
            }
        }
        return out
    }

    private data class Rule(
        val id: String,
        val trigger: Regex,
        val replies: List<String>
    )

    companion object {
        // Regex flags:
        //   - IGNORE_CASE is implicit via .lowercase() in suggestFor
        //   - we anchor as loose substring matches; specificity comes from
        //     keyword choice, not position
        private val RULES = listOf(
            // Spanish — location / arrival
            Rule("es.where", Regex("""\b(d[oó]nde est[aá]s|por d[oó]nde|ubicaci[oó]n)\b"""),
                listOf("En casa", "Ya llego", "En el trabajo")),
            Rule("es.coming", Regex("""\b(vienes|llegas|est[aá]s viniendo|cu[aá]ndo llegas)\b"""),
                listOf("En 10 minutos", "Voy de camino", "Ya casi")),
            Rule("es.call", Regex("""\b(ll[aá]mame|me llamas|tel[eé]fono)\b"""),
                listOf("Te llamo ahora", "Ahora no puedo", "En un rato")),
            Rule("es.thanks", Regex("""\b(gracias|mil gracias|muy amable)\b"""),
                listOf("De nada", "Un placer")),
            Rule("es.canyou", Regex("""\b(puedes|podr[ií]as)\b"""),
                listOf("Sí, claro", "Ahora no puedo", "Luego lo miro")),
            Rule("es.greet", Regex("""\b(buenos d[ií]as|buenas tardes|buenas noches|hola)\b"""),
                listOf("¡Hola!", "Buenas", "¿Qué tal?")),
            Rule("es.how", Regex("""\b(qu[eé] tal|c[oó]mo est[aá]s|c[oó]mo va)\b"""),
                listOf("Bien, gracias", "Todo en orden", "Tirando")),
            Rule("es.yesno_q", Regex("""\b(vale\??|de acuerdo\??|te parece\??)\b"""),
                listOf("Sí", "No", "Vale")),
            Rule("es.when", Regex("""\b(cu[aá]ndo|a qu[eé] hora)\b"""),
                listOf("Ahora", "Esta tarde", "Mañana")),

            // English — location / arrival
            Rule("en.where", Regex("""\b(where are you|where r u|your location)\b"""),
                listOf("At home", "On my way", "At work")),
            Rule("en.coming", Regex("""\b(are you coming|you coming|when will you)\b"""),
                listOf("In 10 minutes", "On my way", "Almost there")),
            Rule("en.call", Regex("""\b(call me|phone me|ring me)\b"""),
                listOf("Calling you", "Can't right now", "Later")),
            Rule("en.thanks", Regex("""\b(thanks|thank you|thx|ty)\b"""),
                listOf("You're welcome", "No problem")),
            Rule("en.canyou", Regex("""\b(can you|could you)\b"""),
                listOf("Sure", "Can't right now", "I'll check")),
            Rule("en.greet", Regex("""\b(hi|hello|good morning|good afternoon|good evening)\b"""),
                listOf("Hi!", "Hello", "Hey")),
            Rule("en.how", Regex("""\b(how are you|how're you|how's it going)\b"""),
                listOf("Good, thanks", "All good", "Tired")),
            Rule("en.when", Regex("""\b(when|what time)\b"""),
                listOf("Now", "This afternoon", "Tomorrow"))
        )
    }
}
