package com.aiaixyz.minemic.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.buildAnnotatedString

/** Add a spoken action hint without replacing the control's existing click action. */
internal fun Modifier.actionHint(hint: String): Modifier = semantics {
    onClick(label = hint, action = null)
}

/** Keep the editable value intact while asking screen readers to spell codes and addresses. */
@OptIn(ExperimentalTextApi::class)
internal fun spelledInput(value: String): AnnotatedString = buildAnnotatedString {
    pushTtsAnnotation(VerbatimTtsAnnotation(value))
    append(value)
    pop()
}
