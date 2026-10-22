package com.ideatic.sunwatt

import android.content.Context
import com.ideatic.sunwatt.core.intents.IntentDataSource
import com.ideatic.sunwatt.core.services.HttpClient
import com.ideatic.sunwatt.core.services.NorthboundSessionStore
import com.ideatic.sunwatt.core.services.OkHttpSunWattClient
import com.ideatic.sunwatt.core.store.DirectoryFileStorage
import com.ideatic.sunwatt.core.store.SecretCipher
import com.ideatic.sunwatt.core.store.SunWattStore
import com.ideatic.sunwatt.core.timeline.SunWattLoader
import com.ideatic.sunwatt.data.SettingsRepository
import com.ideatic.sunwatt.security.EncryptedNorthboundSessionStore
import com.ideatic.sunwatt.security.KeystoreSecretCipher

/** The app's shared objects, one per process (the app, widgets and tiles share them). Every one is created on first use. */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    val http: HttpClient by lazy { OkHttpSunWattClient() }

    val cipher: SecretCipher by lazy { KeystoreSecretCipher() }

    private val storage by lazy { DirectoryFileStorage(appContext.filesDir) }

    val store: SunWattStore by lazy { SunWattStore(storage, cipher) }

    /** FusionSolar API sessions: in memory and encrypted on disk (DECISIONS D36). */
    val northboundSessions: NorthboundSessionStore by lazy { EncryptedNorthboundSessionStore(storage, cipher) }

    val settings: SettingsRepository by lazy { SettingsRepository(store) }

    /** The one way to load data (AGENTS.md, "One way in for each job"); its network lock is process-wide. */
    val loader: SunWattLoader by lazy { SunWattLoader(store, http, northboundSessions) }

    /** Cache-first data for tiles and app shortcuts. */
    val intentData: IntentDataSource by lazy { IntentDataSource(loader) }
}
