package net.isaeff.android.asproxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Define the possible connection states
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/**
 * Singleton object to hold and manage the global connection state.
 * This allows the Service and the UI to synchronize on the current state.
 */
object ConnectionStateHolder {
    // MutableStateFlow to hold the current state, initially DISCONNECTED
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    // Expose the state as an immutable StateFlow for observation
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Updates the current connection state.
     * @param state The new state to set.
     */
    fun setState(state: ConnectionState) {
        _connectionState.value = state
    }
}
