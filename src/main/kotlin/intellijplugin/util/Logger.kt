package intellijplugin.util

import intellijplugin.isDebugEnabled
import org.jetbrains.kotlin.util.Logger

/**
 * This is a short description.
 *
 * @author Scott Smith 2019-12-07 17:23
 */
inline fun Logger.debug(lazyMessage: () -> String) {
    if (isDebugEnabled) log(lazyMessage())
}

