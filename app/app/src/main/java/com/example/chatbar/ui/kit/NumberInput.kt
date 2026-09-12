package com.example.chatbar.ui.kit

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.byValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType

private val decimalDraftPattern = Regex("[+-]?(?:[0-9]*(?:\\.[0-9]*)?)(?:[eE][+-]?[0-9]*)?")

internal fun acceptsNumberDraft(text: String, decimal: Boolean, signed: Boolean): Boolean {
    if (!signed && text.firstOrNull() in listOf('-', '+')) return false
    return if (decimal) decimalDraftPattern.matches(text)
    else text.removePrefix("-").removePrefix("+").all { it in '0'..'9' } &&
        text.count { it == '-' || it == '+' } <= 1
}

/** String-owned forms retain empty/unfinished text for their existing submit validation. */
@Composable
fun CbNumberInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    decimal: Boolean = false,
    signed: Boolean = false,
    onFocusChanged: ((Boolean) -> Unit)? = null
) {
    CbInput(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        enabled = enabled,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        inputTransformation = InputTransformation.byValue { current, proposed ->
            proposed.takeIf { acceptsNumberDraft(it.toString(), decimal, signed) } ?: current
        },
        onFocusChanged = onFocusChanged
    )
}

/** Numeric models keep their last valid value while the user clears or finishes a number. */
@Composable
fun CbNumberValueInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    decimal: Boolean = false,
    signed: Boolean = false,
    isValid: (String) -> Boolean = { it.toLongOrNull() != null }
) {
    var draft by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val pendingEchoes = remember { mutableListOf<java.math.BigDecimal>() }
    // A numeric echo ("1." -> "1.0", "001" -> "1") must not rewrite the editor.
    LaunchedEffect(value) {
        val externalNumber = value.toBigDecimalOrNull()
        val echoIndex = pendingEchoes.indexOfFirst { externalNumber != null && it.compareTo(externalNumber) == 0 }
        if (echoIndex >= 0) {
            repeat(echoIndex + 1) { pendingEchoes.removeAt(0) }
            return@LaunchedEffect
        }
        pendingEchoes.clear()
        val sameNumber = draft.toBigDecimalOrNull()?.let { number ->
            externalNumber?.compareTo(number) == 0
        } == true
        if (!focused || !sameNumber) draft = value
    }
    CbNumberInput(
        value = draft,
        onValueChange = { text ->
            draft = text
            if (isValid(text)) {
                text.toBigDecimalOrNull()?.let { number ->
                    if (value.toBigDecimalOrNull()?.compareTo(number) != 0) {
                        pendingEchoes += number
                        if (pendingEchoes.size > 32) pendingEchoes.removeAt(0)
                    }
                }
                onValueChange(text)
            }
        },
        modifier = modifier,
        enabled = enabled,
        decimal = decimal,
        signed = signed,
        isError = draft.isNotEmpty() && !isValid(draft),
        onFocusChanged = { hasFocus ->
            if (focused && !hasFocus && !isValid(draft)) draft = value
            focused = hasFocus
        }
    )
}
