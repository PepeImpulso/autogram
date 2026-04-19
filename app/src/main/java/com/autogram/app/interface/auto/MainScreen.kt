package com.autogram.app.interface.auto

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template

class MainScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        return GridTemplate.Builder()
            .setTitle("Autogram")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(buildGrid())
            .build()
    }

    private fun buildGrid(): ItemList {
        val builder = ItemList.Builder()

        builder.addItem(
            GridItem.Builder()
                .setTitle("Record")
                .setImage(
                    androidx.car.app.model.CarIcon.Builder(
                        androidx.core.graphics.drawable.IconCompat.createWithResource(
                            carContext,
                            android.R.drawable.ic_btn_speak_now
                        )
                    ).build()
                )
                .setOnClick {
                    // TODO: Start recording
                }
                .build()
        )

        // Quick replies placeholder
        listOf("Driving", "Later", "OK").forEach { label ->
            builder.addItem(
                GridItem.Builder()
                    .setTitle(label)
                    .setOnClick {
                        // TODO: Send quick reply
                    }
                    .build()
            )
        }

        return builder.build()
    }
}
