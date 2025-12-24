package net.vrkknn.andromuks.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import net.vrkknn.andromuks.BuildConfig
import net.vrkknn.andromuks.R

/**
 * Minimal Car App entry point so Android Auto recognizes the app.
 *
 * For now we surface a placeholder screen and a strict host validator.
 * Messaging support still relies on notifications (MessagingStyle + CarExtender).
 */
class AndromuksCarAppService : CarAppService() {

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        return object : Session() {
            override fun onCreateScreen(intent: Intent): Screen {
                // Start with the landing screen
                return CarLandingScreen(carContext)
            }
        }
    }

    override fun createHostValidator(): HostValidator {
        // Allow all hosts in debug to ease DHU/emulator testing; restrict in release builds.
        if (BuildConfig.DEBUG) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

        return HostValidator.Builder(applicationContext)
            .addAllowedHosts(R.xml.host_validator)
            .build()
    }
}

