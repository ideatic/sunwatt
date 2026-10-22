package com.ideatic.sunwatt.ui.text

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ideatic.sunwatt.R
import com.ideatic.sunwatt.core.models.PriceLevel
import com.ideatic.sunwatt.core.models.PriceRegion
import com.ideatic.sunwatt.core.services.SunWattError

/** A catalog string and its format arguments. Plain data, so the mapping can be tested without Android. */
data class TextRes(@StringRes val id: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(id, *args.toTypedArray())

    @Composable
    fun text(): String = stringResource(id, *args.toTypedArray())
}

/** The localized text of an error (the Swift `errorDescription`). No `else`: a new case must not compile until it has a text. */
fun SunWattError.textRes(): TextRes = when (this) {
    SunWattError.InvalidKioskURL -> TextRes(R.string.core_the_kiosk_url_is_not_valid)
    SunWattError.KioskLinkRejected -> TextRes(R.string.core_huawei_no_longer_accepts_this_kiosk)
    SunWattError.InvalidInverterAddress -> TextRes(R.string.core_this_is_not_a_valid_address)
    SunWattError.InverterUnreachable -> TextRes(R.string.core_the_inverter_did_not_answer_check)
    SunWattError.InvalidSolarCredentials -> TextRes(R.string.core_the_id_must_be_a_number)
    SunWattError.SolarCredentialsRejected -> TextRes(R.string.core_the_api_key_or_the_id)
    SunWattError.InvalidNorthboundAccount -> TextRes(R.string.core_enter_the_user_name_and_the)
    SunWattError.NorthboundAccountRejected -> TextRes(R.string.core_fusionsolar_did_not_accept_the_user)
    SunWattError.RateLimited -> TextRes(R.string.core_the_provider_allows_only_a_few)
    SunWattError.NotConfigured -> TextRes(R.string.core_set_up_your_solar_system_in)
    is SunWattError.Http -> TextRes(R.string.core_the_server_responded_with_code, listOf(status))
    is SunWattError.BadResponse -> TextRes(R.string.core_invalid_response, listOf(detail))
    SunWattError.Unavailable -> TextRes(R.string.core_data_not_available_yet)
    is SunWattError.Network -> TextRes(R.string.core_network_error, listOf(detail))
}

fun SunWattError.message(context: Context): String = textRes().resolve(context)

/** Country name of a region. */
@StringRes
fun PriceRegion.displayNameRes(): Int = when (this) {
    PriceRegion.SPAIN -> R.string.core_spain
    PriceRegion.PORTUGAL -> R.string.core_portugal
    PriceRegion.FRANCE -> R.string.core_france
    PriceRegion.GERMANY -> R.string.core_germany_luxembourg
    PriceRegion.AUSTRIA -> R.string.core_austria
    PriceRegion.BELGIUM -> R.string.core_belgium
    PriceRegion.NETHERLANDS -> R.string.core_netherlands
    PriceRegion.ITALY_NORTH -> R.string.core_italy_north
}

fun PriceRegion.displayName(context: Context): String = context.getString(displayNameRes())

/** Name of the price shown: "PVPC" in Spain (a proper name), "Day-ahead price" for the wholesale regions. */
fun PriceRegion.tariffName(context: Context): String = tariffName ?: context.getString(R.string.core_day_ahead_price)

/** "Cheap", "Normal", "Expensive". */
@StringRes
fun PriceLevel.labelRes(): Int = when (this) {
    PriceLevel.LOW -> R.string.app_cheap
    PriceLevel.MEDIUM -> R.string.app_normal
    PriceLevel.HIGH -> R.string.app_expensive
}

fun PriceLevel.label(context: Context): String = context.getString(labelRes())
