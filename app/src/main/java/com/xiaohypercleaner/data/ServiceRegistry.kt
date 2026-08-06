package com.xiaohypercleaner.data

object ServiceRegistry {
    val PACKAGES = listOf(
        "com.miui.msa.global",
        "com.miui.systemAdSolution",
        "com.miui.analytics",
        "com.miui.daemon",
        "com.xiaomi.ab",
        "com.miui.yellowpage",
        "com.miui.miservice"
    )

    val HIDDEN_KEYS_DISABLE = listOf(
        "settings put secure miui_region DE",
        "settings put secure miui_ad_filtering_enabled 0",
        "settings put global ad_control_enabled 0",
        "settings put secure miui_ad_bg_thread_enabled 0",
        "settings put system show_commercial_content 0"
    )

    val HIDDEN_KEYS_RESTORE = listOf(
        "settings put secure miui_region RU",
        "settings put secure miui_ad_filtering_enabled 1",
        "settings put global ad_control_enabled 1",
        "settings put secure miui_ad_bg_thread_enabled 1",
        "settings put system show_commercial_content 1"
    )
}