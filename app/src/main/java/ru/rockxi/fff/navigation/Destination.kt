package ru.rockxi.fff.navigation

sealed class Destination(val route: String) {
    data object Launcher : Destination("launcher")
    data object Finance : Destination("finance")
    data object RemoteControl : Destination("remote-control")
    data object Harness : Destination("harness")

    companion object {
        // Keep the registry lazy: JVM class initialization can enter the sealed
        // object singletons while their companion is still being initialized.
        val all: List<Destination> by lazy(LazyThreadSafetyMode.NONE) {
            listOf(Launcher, Finance, RemoteControl, Harness)
        }

        fun fromRoute(route: String?): Destination? = all.firstOrNull { it.route == route }
    }
}
