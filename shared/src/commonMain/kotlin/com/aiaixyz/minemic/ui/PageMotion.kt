package com.aiaixyz.minemic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection

@Composable
internal fun rememberPageMotion(visible: Boolean): MutableTransitionState<Boolean> =
    remember { MutableTransitionState(false) }.apply { targetState = visible }

// Keep lower layers covered until the outgoing page has completely left the screen.
internal val MutableTransitionState<Boolean>.coversContent: Boolean
    get() = currentState || targetState || !isIdle

internal fun Modifier.blockPageInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
    }
}

@Composable
internal fun PageMotion(
    state: MutableTransitionState<Boolean>,
    covered: Boolean = false,
    content: @Composable () -> Unit,
) {
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val reducedMotion = LocalReducedMotion.current
    AnimatedVisibility(state,
        enter = if (reducedMotion) fadeIn(tween(120)) else slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { direction * it },
        exit = if (reducedMotion) fadeOut(tween(90)) else slideOutHorizontally(tween(240, easing = FastOutSlowInEasing)) { direction * it },
        modifier = Modifier.fillMaxSize(),
    ) {
        val inactive = !state.targetState || covered
        Box(Modifier.fillMaxSize().then(if (inactive) Modifier.clearAndSetSemantics {} else Modifier)) {
            content()
            if (inactive) Box(Modifier.matchParentSize().blockPageInput())
        }
    }
}
