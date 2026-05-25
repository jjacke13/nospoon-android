package com.nospoon.vpn

/**
 * Single source of truth for the VPN's connection state, exposed to UI
 * via [ConnectionStateRepository]. Replaces the previous stringly-typed
 * broadcast (`EXTRA_STATUS_TEXT` + `EXTRA_CONNECTED`).
 *
 * The presentation layer (MainActivity / notification builder) is
 * responsible for translating each variant to a localized string at
 * render time; the state itself is locale-free.
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Reconnecting : ConnectionState()

    /**
     * Terminal-ish error. [message] is already localized by the service
     * (via `translateError`) so the UI can display it verbatim.
     */
    data class Error(val message: String) : ConnectionState()
}
