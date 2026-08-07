package com.xiaohypercleaner.service

object OverlayController {
    @Volatile
    var onCancel: (() -> Unit)? = null

    fun clear() {
        onCancel = null
    }
}