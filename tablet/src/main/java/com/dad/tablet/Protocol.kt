package com.dad.tablet

/**
 * Shared control vocabulary between the phone (controller) and the tablet.
 * The same values are mirrored in the phone module.
 */
object Protocol {
    /** NSD service type the tablet advertises and the phone looks for. */
    const val SERVICE_TYPE = "_dadctl._tcp."
    const val SERVICE_NAME = "DadTablet"

    /** TCP port the embedded HTTP server listens on. */
    const val PORT = 8766

    // Command paths (HTTP POST /cmd/<name>)
    const val CMD_LOCK = "lock"            // fake shutdown
    const val CMD_BATTERY = "battery"      // fake low-battery warning
    const val CMD_UNLOCK = "unlock"        // release the lock
    const val CMD_STATUS = "status"        // report battery / lock state

    /** Header carrying the pairing token on every request. */
    const val HEADER_TOKEN = "x-dad-token"
}
