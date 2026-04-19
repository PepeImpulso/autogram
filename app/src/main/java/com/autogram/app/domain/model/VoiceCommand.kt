package com.autogram.app.domain.model

/**
 * Comandos de voz reconocidos por Autogram en Android Auto.
 * El reconocedor entrega texto libre; mapVoiceCommand() lo normaliza a este enum.
 */
enum class VoiceCommand {
    REPETIR,
    CANCELAR,
    PAUSAR,
    REANUDAR,
    ENVIAR,
    SILENCIO,
    UNKNOWN;

    companion object {
        private val keywords: Map<VoiceCommand, List<String>> = mapOf(
            REPETIR  to listOf("repetir", "otra vez", "repeat"),
            CANCELAR to listOf("cancelar", "cancela", "cancel"),
            PAUSAR   to listOf("pausar", "pausa", "pause"),
            REANUDAR to listOf("reanudar", "continuar", "continue", "resume"),
            ENVIAR   to listOf("enviar", "envía", "manda", "send"),
            SILENCIO to listOf("silencio", "silenciar", "mute", "silence")
        )

        fun fromUtterance(text: String): VoiceCommand {
            val normalized = text.lowercase().trim()
            for ((cmd, words) in keywords) {
                if (words.any { word -> normalized.contains(word) }) return cmd
            }
            return UNKNOWN
        }
    }
}
