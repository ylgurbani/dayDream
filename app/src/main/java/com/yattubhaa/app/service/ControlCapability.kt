package com.yattubhaa.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Keeps the remote tap and swipe service out of the phone's Accessibility list until it is first
 * wanted. In the manifest it is switched off; it is offered when the person first says yes to a
 * request, and stays offered until they tap "Turn off remote control" on the home screen.
 *
 * It cannot be switched off and on again per session: Android forgets that the person had turned
 * it on the moment it is switched off, so every session would mean another trip through Settings.
 *
 * Android does not let an app turn on its own accessibility service: the person still has to
 * switch it on in Settings themselves. This only controls whether the service is offered there.
 */
object ControlCapability {
    private fun component(context: Context) =
        ComponentName(context, RemoteInputAccessibilityService::class.java)

    fun setOffered(context: Context, offered: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (offered) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Whether he has actually switched Yattu Bhaa on in Android's accessibility settings — read
     * from Android's own record, not guessed. Different from [RemoteInput.isAvailable], which
     * also needs Android to have finished (re)connecting the service: on an older phone that can
     * take several seconds, and an earlier version sent him back to Settings during that wait,
     * for a switch that was already on.
     */
    fun isSwitchedOn(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val mine = component(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == mine }
    }

    /** True from the moment he first says yes until he turns remote control off. */
    fun isOffered(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                // A fresh list, in case Settings was already sitting on an older one.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }
}
