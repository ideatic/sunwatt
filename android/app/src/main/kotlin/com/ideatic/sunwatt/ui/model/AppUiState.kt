package com.ideatic.sunwatt.ui.model

import com.ideatic.sunwatt.core.data.SourceStatus
import com.ideatic.sunwatt.core.data.SunWattData
import com.ideatic.sunwatt.core.data.SunWattEntry
import com.ideatic.sunwatt.core.models.SolarAccount
import com.ideatic.sunwatt.core.models.SolarProviderProfile
import com.ideatic.sunwatt.core.models.SunWattSettings
import com.ideatic.sunwatt.core.services.KioskEndpoint
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration

/** Everything the app screens read. One immutable value; [AppViewModel] publishes a new one at each change. */
data class AppUiState(
    /** False until the saved settings and the cached data are read: screens draw only the background. */
    val isReady: Boolean = false,
    val settings: SunWattSettings = SunWattSettings(),
    /** Last loaded data. Null until something (cache or network) is available. */
    val data: SunWattData? = null,
    /** The "current time" the dashboard is drawn for: the clock when live, the sample time in demo. */
    val now: Instant = Instant.EPOCH,
    val isLoading: Boolean = false,
    /** False until the first load finished (or demo): the dashboard shows placeholders instead of an error. */
    val hasLoaded: Boolean = false,
    /** Number of SunWatt widgets the user has placed. Null when unknown. */
    val widgetCount: Int? = null,
    val showsOnboarding: Boolean = false,
    val isDemo: Boolean = false,
) {
    val entry: SunWattEntry? get() = data?.let { SunWattEntry(now, it) }

    val isSolarConfigured: Boolean
        get() = if (isDemo) data?.solarStatus != SourceStatus.NotConfigured else SolarAccount.fromSettings(settings) != null

    /**
     * When the kiosk link stops working at the latest (saved + 1 year). Set only while the "renew soon" notice should
     * show: from 30 days before, unless the user closed it or the link already counts as expired.
     */
    val kioskExpiryNoticeDate: Instant?
        get() {
            if (settings.solarProvider.profile.setup != SolarProviderProfile.Setup.KIOSK_LINK) return null
            if (settings.kioskExpiryNoticeDismissed || data?.solarLinkExpired == true) return null
            if (KioskEndpoint.parse(settings.kioskURL) == null) return null
            val saved = settings.kioskLinkSavedAt ?: return null
            val expiry = saved.atZone(ZoneId.systemDefault()).plusYears(1).toInstant()
            return expiry.takeIf { now >= it.minus(KIOSK_NOTICE_LEAD.toJavaDuration()) }
        }

    companion object {
        /** How long before the expiry the notice appears. */
        val KIOSK_NOTICE_LEAD = 30.days
    }
}
