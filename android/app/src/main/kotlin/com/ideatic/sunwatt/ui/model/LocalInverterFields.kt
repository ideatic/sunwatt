package com.ideatic.sunwatt.ui.model

import com.ideatic.sunwatt.core.models.LocalInverterKind
import com.ideatic.sunwatt.core.models.SunWattSettings

// The settings fields that hold the address and unit ID of the inverter on the home network, per kind. Use them on
// the focused settings of a plant (`AppViewModel.settingsFocusedOn`).

fun SunWattSettings.localInverterHost(kind: LocalInverterKind): String = when (kind) {
    LocalInverterKind.HUAWEI -> huaweiLocalHost
    LocalInverterKind.SUN_SPEC -> sunSpecHost
    LocalInverterKind.FRONIUS -> froniusHost
}

fun SunWattSettings.withLocalInverterHost(kind: LocalInverterKind, host: String): SunWattSettings = when (kind) {
    LocalInverterKind.HUAWEI -> copy(huaweiLocalHost = host)
    LocalInverterKind.SUN_SPEC -> copy(sunSpecHost = host)
    LocalInverterKind.FRONIUS -> copy(froniusHost = host)
}

/** Null for the Fronius Solar API, which has no unit ID. */
fun SunWattSettings.localInverterUnitID(kind: LocalInverterKind): Int? = when (kind) {
    LocalInverterKind.HUAWEI -> huaweiLocalUnitID
    LocalInverterKind.SUN_SPEC -> sunSpecUnitID
    LocalInverterKind.FRONIUS -> null
}

fun SunWattSettings.withLocalInverterUnitID(kind: LocalInverterKind, unitID: Int): SunWattSettings = when (kind) {
    LocalInverterKind.HUAWEI -> copy(huaweiLocalUnitID = unitID)
    LocalInverterKind.SUN_SPEC -> copy(sunSpecUnitID = unitID)
    LocalInverterKind.FRONIUS -> this
}
