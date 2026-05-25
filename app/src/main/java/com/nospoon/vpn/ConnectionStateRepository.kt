package com.nospoon.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-singleton repository for [ConnectionState]. The VPN service
 * updates it; the UI collects it via `lifecycleScope.launch { ... }`.
 *
 * Since the service runs in the same process as MainActivity (no
 * `android:process` override in the manifest), a plain object is enough
 * — no IPC, no Binder, no broadcast intent.
 *
 * StateFlow guarantees the current value is always observable: when a
 * new collector arrives, it receives the latest value immediately. This
 * replaces the previous "ACTION_QUERY" round-trip the Activity used to
 * seed its initial UI.
 */
object ConnectionStateRepository {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)

    /** Read-only view of the current state. */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Service-side: publish a new state. Safe to call from any thread. */
    fun update(newState: ConnectionState) {
        _state.value = newState
    }
}
