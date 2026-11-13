package io.github.jkfran.frameartmode

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import io.github.jkfran.frameartmode.control.ScreensaverTiming

/**
 * Reads and, when this app holds WRITE_SECURE_SETTINGS (granted over ADB), writes the system's
 * screensaver settings. Google TV's own settings only offer its Ambient mode, so this is how a
 * third-party screensaver gets selected there.
 */
object ScreensaverSetup {
    // Hidden Settings.Secure keys; they are readable by apps.
    private const val SCREENSAVER_COMPONENTS = "screensaver_components"
    private const val SCREENSAVER_ENABLED = "screensaver_enabled"
    private const val SCREENSAVER_ACTIVATE_ON_SLEEP = "screensaver_activate_on_sleep"
    // Written by the device's energy settings ("turn off display" and similar).
    private const val SLEEP_TIMEOUT = "sleep_timeout"
    private const val ATTENTIVE_TIMEOUT = "attentive_timeout"

    data class State(
        /** Null when the device does not let apps read the setting. */
        val components: String?,
        val enabled: Boolean?,
        val delayMs: Long?,
        /** Idle time after which the device sleeps; null when nothing limits it or it is unreadable. */
        val sleepLimitMs: Long?,
    )

    /** What [setDelay] did. */
    enum class DelayChange { SET, TOO_LONG, NOT_ALLOWED }

    fun component(context: Context): ComponentName = ComponentName(context, ArtModeDreamService::class.java)

    fun read(context: Context): State {
        val resolver = context.contentResolver
        val components = try {
            Settings.Secure.getString(resolver, SCREENSAVER_COMPONENTS)
        } catch (_: SecurityException) {
            null
        }
        val enabled = try {
            Settings.Secure.getInt(resolver, SCREENSAVER_ENABLED) != 0
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        val delay = try {
            Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT)
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        val sleepLimit = ScreensaverTiming.sleepLimitMs(
            secureLong(resolver, SLEEP_TIMEOUT),
            secureLong(resolver, ATTENTIVE_TIMEOUT),
        )
        return State(components, enabled, delay, sleepLimit)
    }

    private fun secureLong(resolver: ContentResolver, key: String): Long? = try {
        Settings.Secure.getLong(resolver, key)
    } catch (_: Settings.SettingNotFoundException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** True when this app's screensaver is the one the system picks (the first valid entry). */
    fun isSelected(context: Context, state: State): Boolean {
        val first = state.components?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return ComponentName.unflattenFromString(first) == component(context)
    }

    fun canWriteSettings(context: Context): Boolean =
        context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** Selects this screensaver and switches screensavers on. Returns false when not allowed. */
    fun select(context: Context): Boolean {
        if (!canWriteSettings(context)) return false
        val resolver = context.contentResolver
        return try {
            Settings.Secure.putString(resolver, SCREENSAVER_COMPONENTS, component(context).flattenToShortString())
            Settings.Secure.putInt(resolver, SCREENSAVER_ENABLED, 1)
            Settings.Secure.putInt(resolver, SCREENSAVER_ACTIVATE_ON_SLEEP, 1)
            isSelected(context, read(context))
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Sets the idle time before the screensaver. A delay that leaves the screensaver no time
     * before the device sleeps is refused. A refused write returns false, so it is read back.
     */
    fun setDelay(context: Context, delayMs: Long): DelayChange {
        if (!ScreensaverTiming.fits(delayMs, read(context).sleepLimitMs)) return DelayChange.TOO_LONG
        if (!canWriteSettings(context)) return DelayChange.NOT_ALLOWED
        val written = try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, delayMs.toInt())
            read(context).delayMs == delayMs
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
        return if (written) DelayChange.SET else DelayChange.NOT_ALLOWED
    }

    /** The commands that select this screensaver from a computer. */
    fun adbCommands(context: Context, delayMs: Long): String =
        context.getString(
            R.string.adb_commands,
            context.packageName,
            component(context).flattenToShortString(),
            delayMs.toString(),
        )

    /** The system's screensaver settings, when this device has a screen for them. */
    fun systemSettingsIntent(context: Context): Intent? {
        val intent = Intent(Settings.ACTION_DREAM_SETTINGS)
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }
}
