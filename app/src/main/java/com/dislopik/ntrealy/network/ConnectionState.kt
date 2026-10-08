package com.dislopik.ntrealy.network

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState

    data class Error(val message: String) : ConnectionState
}
