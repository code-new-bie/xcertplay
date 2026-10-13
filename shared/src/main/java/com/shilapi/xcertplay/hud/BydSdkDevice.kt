package com.shilapi.xcertplay.hud

import android.content.Context
import java.io.Closeable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * A BYD vehicle SDK device used in this app's own process, without ADB. On DiLink firmware the
 * BYDAUTO_*_COMMON permissions are normal permissions (protectionLevel 0), so an app that declares
 * one holds it, and getInstance, the getters and registerListener(IBYDAutoListener) carry no
 * hidden-API restriction. Classes are reached by reflection, so the build needs no BYD SDK, and a
 * listener is a dynamic proxy of IBYDAutoListener.
 */
internal class BydSdkDevice internal constructor(private val type: Class<*>, private val instance: Any) {
    /** An int getter's value, or null when the call fails. */
    fun readInt(method: String): Int? =
        runCatching { (type.getMethod(method).invoke(instance) as Number).toInt() }.getOrNull()

    /**
     * Calls [onEvent] on any event of this device (the listener only signals a change; read the value
     * with [readInt]). Returns a handle that unregisters it, or null when registering fails.
     */
    fun listen(listenerInterface: String = LISTENER_INTERFACE, onEvent: () -> Unit): Closeable? = runCatching {
        val listenerType = Class.forName(listenerInterface)
        val proxy = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType), BydEventHandler(onEvent))
        type.getMethod("registerListener", listenerType).invoke(instance, proxy)
        Closeable { runCatching { type.getMethod("unregisterListener", listenerType).invoke(instance, proxy) } }
    }.getOrNull()

    companion object {
        const val LISTENER_INTERFACE = "android.hardware.IBYDAutoListener"

        /** The SDK's shared instance of [className] (getInstance), or the reason it is unavailable. */
        fun open(context: Context, className: String): Result<BydSdkDevice> = runCatching {
            val type = Class.forName(className)
            val instance = checkNotNull(type.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)) {
                "getInstance returned null"
            }
            BydSdkDevice(type, instance)
        }
    }
}

/** Forwards IBYDAutoListener.onDataChanged; Object's own methods behave as for any object. */
internal class BydEventHandler(private val onEvent: () -> Unit) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
        "equals" -> proxy === args?.getOrNull(0)
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "BydEventHandler@${Integer.toHexString(System.identityHashCode(proxy))}"
        else -> {
            runCatching(onEvent)
            null
        }
    }
}
