package com.xiaohypercleaner.service

/** Мост между оверлеем и службой: кнопка «Отменить» дёргает этот колбэк. */
object OverlayController {
    @Volatile
    var onCancel: (() -> Unit)? = null
}