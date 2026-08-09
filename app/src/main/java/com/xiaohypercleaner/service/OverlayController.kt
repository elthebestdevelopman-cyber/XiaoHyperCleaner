package com.xiaohypercleaner.service

import java.lang.ref.WeakReference

object OverlayController {

    interface CancelHandler {
        fun cancelChain()
    }

    private var handlerRef: WeakReference<CancelHandler>? = null

    var onCancel: (() -> Unit)? = null
        set(value) {
            field = value
            if (value != null) {
                register(object : CancelHandler {
                    override fun cancelChain() = value.invoke()
                })
            } else {
                clear()
            }
        }

    fun register(handler: CancelHandler) {
        handlerRef = WeakReference(handler)
    }

    fun triggerCancel() {
        handlerRef?.get()?.cancelChain()
    }

    fun clear() {
        handlerRef = null
        onCancel = null
    }
}