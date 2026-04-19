package com.autogram.app.ui.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.LongMessageTemplate
import androidx.car.app.model.Template
import com.autogram.app.R

/**
 * Pantalla de ayuda con la lista de comandos de voz disponibles.
 * Diseñada para que el conductor entienda qué puede decir sin distraerse.
 */
class VoiceCommandsHelpScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return LongMessageTemplate.Builder(carContext.getString(R.string.aa_voice_help_body))
            .setTitle(carContext.getString(R.string.aa_voice_help_title))
            .setHeaderAction(Action.BACK)
            .build()
    }
}
