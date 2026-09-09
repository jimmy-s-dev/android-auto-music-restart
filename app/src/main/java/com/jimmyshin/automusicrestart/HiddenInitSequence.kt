package com.jimmyshin.automusicrestart

/** Own the temporary display until the initialized player can survive its removal. */
class HiddenInitSequence(private val port: Port) {
    interface Port {
        fun create()
        fun launch()
        fun waitFor(millis: Long)
        fun verifyPlacement()
        fun playAndConfirm()
        fun release()
    }
    fun execute() {
        try {
            port.create()
            port.launch()
            port.waitFor(1000)
            port.verifyPlacement()
            port.waitFor(5000)
            port.verifyPlacement()
            port.playAndConfirm()
            port.verifyPlacement()
        } finally {
            port.release()
        }
    }
}
