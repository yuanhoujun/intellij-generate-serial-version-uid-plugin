package intellijplugin.util

import intellijplugin.DEBUG

/**
 * This is a short description.
 *
 * @author Scott Smith 2019-12-07 17:23
 */
object Log {
    fun debug(tag: String = "GenerateSerialVersionUID", msg: String) {
        if (DEBUG) {
            println("$tag: $msg")
        }
    }
}
