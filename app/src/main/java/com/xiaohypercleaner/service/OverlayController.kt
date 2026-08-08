package com.xiaohypercleaner.service

import java.lang.ref.WeakReference

object OverlayController {

    interface CancelHandler {
        fun cancelChain()
    }

    private var handlerRef: WeakReference<CancelHandler>? = null

    fun register(handler: CancelHandler) {
        handlerRef = WeakReference(handler)
    }

    fun triggerCancel() {
        handlerRef?.get()?.cancelChain()
    }

    fun clear() {
        handlerRef = null
    }
}