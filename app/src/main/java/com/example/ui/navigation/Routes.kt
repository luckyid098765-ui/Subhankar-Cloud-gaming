package com.example.ui.navigation

import kotlinx.serialization.Serializable

sealed interface AppRoute {
    @Serializable
    data object Splash : AppRoute

    @Serializable
    data object Auth : AppRoute

    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Library : AppRoute

    @Serializable
    data class GameDetail(val gameId: String) : AppRoute

    @Serializable
    data object Wallet : AppRoute

    @Serializable
    data object History : AppRoute

    @Serializable
    data object Profile : AppRoute

    @Serializable
    data object Support : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object StreamPlayer : AppRoute
}
