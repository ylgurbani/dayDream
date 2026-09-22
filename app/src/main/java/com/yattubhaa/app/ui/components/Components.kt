package com.yattubhaa.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonKind { Primary, Secondary, Danger }

/**
 * The layout rule for every screen in the app: content scrolls, the buttons sit below it in a
 * fixed area, so no font size can push the thing he needs to tap off the screen.
 */
@Composable
fun Screen(
    modifier: Modifier = Modifier,
    buttons: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = buttons,
        )
    }
}

/** Big, high contrast, and it grows (rather than clips) when the text does. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    minHeight: Dp = 80.dp,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
) {
    val shape = RoundedCornerShape(20.dp)
    val size = modifier.fillMaxWidth().heightIn(min = minHeight)
    val label = @Composable {
        Text(text = text, style = textStyle, textAlign = TextAlign.Center)
    }
    when (kind) {
        ButtonKind.Secondary -> OutlinedButton(
            onClick = onClick, modifier = size, shape = shape, enabled = enabled,
            border = BorderStroke(3.dp, MaterialTheme.colorScheme.outline),
        ) { label() }
        else -> Button(
            onClick = onClick, modifier = size, shape = shape, enabled = enabled,
            colors = if (kind == ButtonKind.Danger) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            },
        ) { label() }
    }
}

@Composable
fun Heading(text: String) = Text(text, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)

@Composable
fun Body(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = modifier)

@Composable
fun Gap(height: Int = 16) = Spacer(Modifier.height(height.dp))

/** A screen with a title, a sentence, and one or two buttons. Covers most of the simple cases. */
@Composable
fun MessageScreen(
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) = Screen(
    buttons = {
        BigButton(primary, onPrimary)
        if (secondary != null) BigButton(secondary, onSecondary, kind = ButtonKind.Secondary)
    },
) {
    Heading(title)
    Gap(16)
    Body(body)
}
