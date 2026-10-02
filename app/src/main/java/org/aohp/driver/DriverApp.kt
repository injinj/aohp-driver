package org.aohp.driver

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.aohp.driver.binder.ContainerService
import org.aohp.driver.binder.VirtualDisplayService
import org.aohp.driver.terminal.PtySessionRegistry

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class Settings(private val ctx: Context) {
    companion object {
        val SELECTED_ENV = stringPreferencesKey("selected_env")
        val BOOTSTRAP_REPO = stringPreferencesKey("bootstrap_repo")
        const val DEFAULT_REPO = "injinj/aohp-config-chris"
    }
    val selectedEnv: Flow<String?> = ctx.dataStore.data.map { it[SELECTED_ENV] }
    suspend fun setSelectedEnv(name: String?) = ctx.dataStore.edit { p -> if (name == null) p.remove(SELECTED_ENV) else p[SELECTED_ENV] = name }
    val bootstrapRepo: Flow<String> = ctx.dataStore.data.map { it[BOOTSTRAP_REPO] ?: DEFAULT_REPO }
    suspend fun setBootstrapRepo(v: String) = ctx.dataStore.edit { it[BOOTSTRAP_REPO] = v }
}

class DriverApp : Application() {
    val containers by lazy { ContainerService() }
    val virtualDisplays by lazy { VirtualDisplayService() }
    val settings by lazy { Settings(this) }
    val ptySessions by lazy { PtySessionRegistry(containers) }

    companion object {
        lateinit var instance: DriverApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
