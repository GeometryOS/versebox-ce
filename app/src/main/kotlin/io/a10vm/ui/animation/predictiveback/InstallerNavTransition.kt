// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package io.a10vm.ui.animation.predictiveback

import io.a10vm.domain.settings.model.preferences.PredictiveBackAnimation
import io.a10vm.domain.settings.model.preferences.PredictiveBackExitDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

fun verseBoxNavTransition(animation: PredictiveBackAnimation, exitDirection: PredictiveBackExitDirection): NavTransition = when (animation) {
    PredictiveBackAnimation.None -> NoPredictiveBackTransition
    PredictiveBackAnimation.MIUIX -> NavTransitions.MiuixDefault
    PredictiveBackAnimation.AOSP -> AospNavTransition
    PredictiveBackAnimation.Scale -> scaleNavTransition(exitDirection)
    PredictiveBackAnimation.Classic -> ClassicNavTransition
}
