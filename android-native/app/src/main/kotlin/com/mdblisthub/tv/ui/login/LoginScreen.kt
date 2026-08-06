package com.mdblisthub.tv.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.ui.component.HubSpinner
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel

@Composable
fun LoginScreen(
    graph: DataGraph,
    onSignedIn: () -> Unit,
) {
    val viewModel = hubViewModel { LoginViewModel(graph) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
            // Capped, not fixed: on a TV this reads as a comfortable reading
            // column; on a phone it uses the width that is actually there.
            modifier = Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Text("mdblist hub", style = MaterialTheme.typography.displayLarge, color = HubColors.Text)
            Text(
                text = "Entre com a chave da API da sua conta do mdblist. Ela fica em " +
                    "mdblist.com/preferences e é guardada só neste aparelho.",
                style = MaterialTheme.typography.bodyLarge,
                color = HubColors.TextDim,
            )

            BasicTextField(
                value = state.key,
                onValueChange = viewModel::onKeyChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = HubColors.Text),
                cursorBrush = SolidColor(HubColors.Accent2),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .clip(RoundedCornerShape(10.dp))
                    .background(HubColors.Surface)
                    .border(1.dp, HubColors.Border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            )

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = HubColors.Rotten)
            }

            if (state.busy) {
                HubSpinner()
            } else {
                HubButton(text = "Entrar", primary = true, onClick = viewModel::signIn)
            }
        }
    }
}
