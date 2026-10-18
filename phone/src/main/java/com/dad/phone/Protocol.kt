package com.dad.phone

/** Mirror of the tablet's control vocabulary. Keep in sync with the tablet module. */
object Protocol {
    const val SERVICE_TYPE = "_dadctl._tcp."
    const val SERVICE_NAME = "DadTablet"
    const val PORT = 8766

    const val CMD_LOCK = "lock"
    const val CMD_BATTERY = "battery"
    const val CMD_UNLOCK = "unlock"
    const val CMD_STATUS = "status"

    const val HEADER_TOKEN = "x-dad-token"
}
