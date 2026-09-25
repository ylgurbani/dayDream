package com.yattubhaa.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Keeps the remote tap and swipe service out of the phone's Accessibility list except while a
 * session wants it. In the manifest it is switched off; it is offered when the person says yes to
 * a request, and [switchOff] takes it away again as the session ends.
 *
 * Switched off after every session on purpose, although it costs him a trip through Settings each
 * time (Android forgets that he had turned it on the moment it is switched off): some banking apps
 * refuse to open at all while any app's accessibility service is switched on, found on a real
 * phone. Left on between sessions, it would lock him out of his bank until he uninstalled this app.
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

    /** Whether Android's Accessibility list includes this app's service yet: it takes a moment to
     *  appear after [setOffered], and a list opened before then does not show it. */
    fun isListed(context: Context): Boolean {
        val mine = component(context)
        return context.getSystemService(AccessibilityManager::class.java).installedAccessibilityServiceList
            .any { it.resolveInfo.serviceInfo.let { s -> ComponentName(s.packageName, s.name) } == mine }
    }

    /** True from the moment he says yes until the session ends. */
    fun isOffered(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /**
     * Switches remote tap and swipe off in Android itself and takes it back out of the
     * Accessibility list. Called as every session on this phone ends, and when the app starts: no
     * session can be running then, so this also covers one cut off without ending properly (the
     * app killed, the phone restarted).
     *
     * Two steps, because either alone might not be enough on every phone: the service switches
     * itself off through Android's own call for that, if it is connected; and hiding it from the
     * list means nothing can bind it again until he next says yes.
     */
    fun switchOff(context: Context) {
        RemoteInput.switchOff()
        if (isOffered(context)) setOffered(context, false)
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                // A fresh list, in case Settings was already sitting on an older one.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
    }
}
