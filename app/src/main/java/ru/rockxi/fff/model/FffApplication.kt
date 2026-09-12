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
            id = "harness",
            name = "AI Harness",
            kicker = "PAIRED ASSISTANT",
            description = "Безопасное подключение к тому же ИИ-ассистенту и нативный чат.",
            meta = "PAIR · CHAT · DEVICE",
            accent = AppAccent.Mint,
            destination = Destination.Harness,
        ),
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
        FffApplication(
            id = "gym",
            name = "Gym Tracker",
            kicker = "TRAINING LOG",
            description = "Тренировки по дням, подходы с весом и повторениями, календарь и личные рекорды.",
            meta = "WORKOUTS · SETS · RECORDS",
            accent = AppAccent.Mint,
            destination = Destination.Gym,
        ),
        FffApplication(
            id = "calories",
            name = "Калории",
            kicker = "NUTRITION LOG",
            description = "Локальный дневник питания, калории и баланс белков, жиров и углеводов.",
            meta = "MEALS · CALORIES · MACROS",
            accent = AppAccent.Mint,
            destination = Destination.Calories,
        ),
    )
}
