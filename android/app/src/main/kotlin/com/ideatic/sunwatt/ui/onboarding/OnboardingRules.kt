package com.ideatic.sunwatt.ui.onboarding

import androidx.annotation.StringRes
import com.ideatic.sunwatt.R

/** The pages of the first-launch flow. */
enum class OnboardingPage { WELCOME, COUNTRY, SOLAR, WIDGET, DONE;

    val next: OnboardingPage? get() = entries.getOrNull(ordinal + 1)
    val previous: OnboardingPage? get() = entries.getOrNull(ordinal - 1)

    /** Back is offered from the second page up to the widget page (not on the first page and not on the last). */
    val hasBack: Boolean get() = this != WELCOME && this != DONE
}

/** The text of the main button: "Skip, prices only" on the solar page while no solar input is typed. */
@StringRes
fun primaryLabel(page: OnboardingPage, hasSolarInput: Boolean): Int = when (page) {
    OnboardingPage.WELCOME -> R.string.app_get_started
    OnboardingPage.DONE -> R.string.app_open_sunwatt
    OnboardingPage.SOLAR -> if (hasSolarInput) R.string.app_continue else R.string.app_skip_prices_only
    OnboardingPage.COUNTRY, OnboardingPage.WIDGET -> R.string.app_continue
}
