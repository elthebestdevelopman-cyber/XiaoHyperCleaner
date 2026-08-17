package com.xiaohypercleaner.service

import java.lang.ref.WeakReference

object OverlayController {

    interface CancelHandler {
        fun cancelChain()
    }

    private var handlerRef: WeakReference<CancelHandler>? = null
    private var serviceRef: WeakReference<OverlayService>? = null

    fun register(handler: CancelHandler) {
        handlerRef = WeakReference(handler)
    }

    fun registerService(service: OverlayService) {
        serviceRef = WeakReference(service)
    }

    fun triggerCancel() {
        handlerRef?.get()?.cancelChain()
    }

    fun showInteractiveHint(hint: InteractiveHint) {
        serviceRef?.get()?.showInteractiveHint(hint)
    }

    fun hideInteractiveHint() {
        serviceRef?.get()?.hideInteractiveHint()
    }

    fun clear() {
        handlerRef?.clear()
        serviceRef?.clear()
        handlerRef = null
        serviceRef = null
    }
}