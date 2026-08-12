package com.xiaohypercleaner.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "xhc_settings")

sealed interface PreferenceKey {
    data object HasCompletedOnboarding : PreferenceKey {
        override val name = "has_completed_onboarding"
    }

    val name: String

    data object DarkTheme : PreferenceKey {
        override val name = "dark_theme"
    }

    data object HasManuallyChosenTheme : PreferenceKey {
        override val name = "has_manually_chosen_theme"
    }

    data object HiddenSettingsApplied : PreferenceKey {
        override val name = "hidden_settings_applied"
    }

    data object PendingOptimization : PreferenceKey {
        override val name = "pending_optimization"
    }

    data object HasShownRestrictedDialog : PreferenceKey {
        override val name = "has_shown_restricted_dialog"
    }

    data object DnsFilterEnabled : PreferenceKey {
        override val name = "dns_filter_enabled"
    }

    data object HasSeenDnsWarning : PreferenceKey {
        override val name = "has_seen_dns_warning"
    }

    data object LastReportJson : PreferenceKey {
        override val name = "last_report_json"
    }
}

class PreferencesManager(private val context: Context) {

    val hasCompletedOnboarding: Flow<Boolean> =
        readBool(PreferenceKey.HasCompletedOnboarding, false)

    suspend fun setHasCompletedOnboarding(completed: Boolean) =
        writeBool(PreferenceKey.HasCompletedOnboarding, completed)

    val isDarkTheme: Flow<Boolean> = readBool(PreferenceKey.DarkTheme, false)
    val hasManuallyChosenTheme: Flow<Boolean> =
        readBool(PreferenceKey.HasManuallyChosenTheme, false)
    val isHiddenSettingsApplied: Flow<Boolean> =
        readBool(PreferenceKey.HiddenSettingsApplied, false)
    val pendingOptimization: Flow<Boolean> =
        readBool(PreferenceKey.PendingOptimization, false)
    val hasShownRestrictedDialog: Flow<Boolean> =
        readBool(PreferenceKey.HasShownRestrictedDialog, false)
    val dnsFilterEnabled: Flow<Boolean> =
        readBool(PreferenceKey.DnsFilterEnabled, false)
    val hasSeenDnsWarning: Flow<Boolean> =
        readBool(PreferenceKey.HasSeenDnsWarning, false)

    suspend fun setDarkTheme(enabled: Boolean) =
        writeBool(PreferenceKey.DarkTheme, enabled)

    suspend fun setHasManuallyChosenTheme(chosen: Boolean) =
        writeBool(PreferenceKey.HasManuallyChosenTheme, chosen)

    suspend fun setHiddenSettingsApplied(applied: Boolean) =
        writeBool(PreferenceKey.HiddenSettingsApplied, applied)

    suspend fun setPendingOptimization(pending: Boolean) =
        writeBool(PreferenceKey.PendingOptimization, pending)

    suspend fun setHasShownRestrictedDialog(shown: Boolean) =
        writeBool(PreferenceKey.HasShownRestrictedDialog, shown)

    suspend fun setDnsFilterEnabled(enabled: Boolean) =
        writeBool(PreferenceKey.DnsFilterEnabled, enabled)

    suspend fun setHasSeenDnsWarning(seen: Boolean) =
        writeBool(PreferenceKey.HasSeenDnsWarning, seen)

    suspend fun clearPendingOptimization() =
        writeBool(PreferenceKey.PendingOptimization, false)

    suspend fun getPendingOptimization(): Boolean =
        pendingOptimization.first()

    suspend fun getDnsFilterEnabled(): Boolean =
        dnsFilterEnabled.first()

    suspend fun setLastReportJson(json: String) {
        context.dataStore.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("last_report_json")] =
                json
        }
    }

    val lastReportJson: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[androidx.datastore.preferences.core.stringPreferencesKey("last_report_json")] ?: ""
    }

    private fun readBool(key: PreferenceKey, default: Boolean): Flow<Boolean> =
        context.dataStore.data.map { it[booleanPreferencesKey(key.name)] ?: default }

    private suspend fun writeBool(key: PreferenceKey, value: Boolean) {
        context.dataStore.edit { it[booleanPreferencesKey(key.name)] = value }
    }
}