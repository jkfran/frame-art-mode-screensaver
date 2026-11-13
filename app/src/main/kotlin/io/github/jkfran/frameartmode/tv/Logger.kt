package io.github.jkfran.frameartmode.tv

/** Diagnostic output. Implementations must not receive tokens; callers never pass them. */
fun interface Logger {
    fun log(message: String)

    companion object {
        val NONE = Logger { }
    }
}
