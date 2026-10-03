package com.shilapi.xcertplay.shared

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import com.shilapi.xcertplay.shared.R

class MyCarAppScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        return MessageTemplate.Builder(carContext.getString(R.string.car_app_hardware_message))
            .setHeaderAction(Action.APP_ICON)
            .setTitle(carContext.getString(R.string.car_app_hardware_title))
            .build()
    }
}
