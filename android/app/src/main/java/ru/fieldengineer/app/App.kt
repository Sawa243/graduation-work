package ru.fieldengineer.app

import android.app.Application

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // вошли раньше — периодическая синхронизация должна стоять в очереди WorkManager
        if (container.authRepository.isLoggedIn) container.syncScheduler.schedulePeriodic()
    }
}
