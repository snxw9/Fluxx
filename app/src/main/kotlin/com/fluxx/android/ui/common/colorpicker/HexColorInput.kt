package com.fluxx.android.ui.common.colorpicker

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fluxx.android.model.FluxxColor

@Composable internal fun HexColorInput(color: FluxxColor, onChange: (FluxxColor)->Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(color.toHexString(true)) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(color,focused) { if (!focused) text=color.toHexString(true) }
    fun commit() {
        val parsed=FluxxColor.parseHex(text,color.a)
        if(parsed!=null) { text=parsed.toHexString(true); onChange(parsed) }
        else text=color.toHexString(true)
    }
    val invalid = FluxxColor.parseHex(text,color.a)==null
    BasicTextField(text, { text=it }, singleLine=true,
        textStyle=MaterialTheme.typography.labelMedium.copy(color=MaterialTheme.colorScheme.onSurface),
        cursorBrush=SolidColor(MaterialTheme.colorScheme.onSurface),
        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={ commit(); focus.clearFocus() }),
        modifier=modifier.border(.5.dp,if(invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
            RoundedCornerShape(6.dp)).padding(7.dp).onFocusChanged {
            if(focused && !it.isFocused) commit(); focused=it.isFocused
        }.semantics { contentDescription="Hex colour: RRGGBB or AARRGGBB"; if(invalid) error("Enter six or eight hexadecimal digits") })
}
