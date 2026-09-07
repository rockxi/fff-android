package ru.rockxi.fff.model

import ru.rockxi.fff.navigation.Destination

data class FffApplication(
    val id: String,
    val name: String,
    val kicker: String,
    val description: String,
    val meta: String,
    val accent: AppAccent,
    val destination: Destination,
)

enum class AppAccent { Mint, Violet }

object AppCatalog {
    val applications = listOf(
        FffApplication(
            id = "control",
            name = "Remote Control",
            kicker = "INFRASTRUCTURE",
            description = "Терминалы, SSH-хосты и запуск автономных агентов из одного защищённого контура.",
            meta = "HOSTS · TERMINAL · CODEX",
            accent = AppAccent.Mint,
            destination = Destination.RemoteControl,
        ),
        FffApplication(
            id = "finance",
            name = "Finance",
            kicker = "PERSONAL LEDGER",
            description = "Счета, доходы и расходы, категории и наглядная статистика без лишней бухгалтерии.",
            meta = "ACCOUNTS · ANALYTICS",
            accent = AppAccent.Violet,
            destination = Destination.Finance,
        ),
    )
}

