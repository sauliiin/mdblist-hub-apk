package com.mdblisthub.tv.ui.addons

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.mdblisthub.tv.core.data.DataGraph
import com.mdblisthub.tv.core.data.StremioSession
import com.mdblisthub.tv.core.model.Addon
import com.mdblisthub.tv.core.model.ImportReport
import com.mdblisthub.tv.core.ui.theme.HubColors
import com.mdblisthub.tv.core.ui.theme.HubDimens
import com.mdblisthub.tv.ui.component.HubButton
import com.mdblisthub.tv.ui.hubViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The two addons installed by default, right under the field — one for
 * subtitles, one for streams, which between them get a fresh install working.
 *
 * OpenSubtitles v3 has a fixed manifest that works for anyone, so its button
 * installs directly. AIOStreams does not: its root answers with the site's
 * own PWA manifest, not an addon one — confirmed by hand, the per-user URL
 * only exists after configuring — so its button opens that page instead.
 */
private data class QuickAddon(val name: String, val url: String? = null, val configureUrl: String? = null)

private val QUICK_ADDONS = listOf(
    QuickAddon(name = "OpenSubtitles v3", url = "https://opensubtitles-v3.strem.io/manifest.json"),
    QuickAddon(name = "AIOStreams", configureUrl = "https://aiostreams.elfhosted.com/configure"),
)

// -------------------------------------------------------------- view model

data class InstallState(
    val url: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

data class FirebaseSyncUi(
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val lastSync: String? = null,
    val lastDelta: Int? = null,
)

data class StremioUi(
    val session: StremioSession? = null,
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val report: ImportReport? = null,
)

class AddonsViewModel(private val graph: DataGraph) : ViewModel() {

    val addons: StateFlow<List<Addon>> = graph.addons.observeAddons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _install = MutableStateFlow(InstallState())
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private val _firebase = MutableStateFlow(FirebaseSyncUi())
    val firebase: StateFlow<FirebaseSyncUi> = _firebase.asStateFlow()

    private val _stremio = MutableStateFlow(StremioUi())
    val stremio: StateFlow<StremioUi> = _stremio.asStateFlow()

    init {
        viewModelScope.launch {
            combine(graph.firebaseSync.enabled, graph.firebaseSync.busy, graph.firebaseSync.error) {
                enabled, busy, error -> Triple(enabled, busy, error)
            }.collect { (enabled, busy, error) ->
                _firebase.update { it.copy(enabled = enabled, busy = busy, error = error) }
            }
        }
        viewModelScope.launch {
            graph.stremioAccount.session.collect { session ->
                _stremio.update { it.copy(session = session) }
            }
        }
    }

    // ------------------------------------------------------------- install

    fun onUrlChange(value: String) = _install.update { it.copy(url = value, error = null) }

    fun installFromField() = install(_install.value.url)

    /** Takes an explicit URL so the quick-add buttons can install directly. */
    fun install(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty() || _install.value.busy) return

        _install.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            graph.addons.install(url).fold(
                onSuccess = { _install.update { s -> s.copy(busy = false, url = "") } },
                onFailure = { e -> _install.update { s -> s.copy(busy = false, error = e.message) } },
            )
        }
    }

    fun remove(addon: Addon) {
        viewModelScope.launch { graph.addons.remove(addon.base) }
    }

    // --------------------------------------------------------- firebase sync

    fun toggleFirebaseSync() {
        _firebase.update { it.copy(lastDelta = null) }
        viewModelScope.launch {
            if (_firebase.value.enabled) {
                graph.firebaseSync.disable()
            } else {
                graph.firebaseSync.enable()
                    .onSuccess { delta -> _firebase.update { it.copy(lastDelta = delta) } }
            }
        }
    }

    fun pullFirebase() {
        _firebase.update { it.copy(lastDelta = null) }
        viewModelScope.launch {
            graph.firebaseSync.pull().onSuccess { delta -> _firebase.update { it.copy(lastDelta = delta) } }
        }
    }

    fun pushFirebase() {
        _firebase.update { it.copy(lastDelta = null) }
        viewModelScope.launch { graph.firebaseSync.push() }
    }

    // ------------------------------------------------------- stremio account

    fun onEmailChange(value: String) = _stremio.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _stremio.update { it.copy(password = value, error = null) }

    fun stremioSignIn() {
        val email = _stremio.value.email.trim()
        val password = _stremio.value.password
        if (email.isEmpty() || password.isEmpty() || _stremio.value.busy) return

        _stremio.update { it.copy(busy = true, error = null, report = null) }
        viewModelScope.launch {
            graph.stremioAccount.login(email, password).fold(
                onSuccess = { report ->
                    // Nothing here needs the password again, so it does not linger.
                    _stremio.update { it.copy(busy = false, password = "", report = report) }
                },
                onFailure = { e -> _stremio.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    fun stremioSync() {
        if (_stremio.value.busy) return
        _stremio.update { it.copy(busy = true, error = null, report = null) }
        viewModelScope.launch {
            graph.stremioAccount.sync().fold(
                onSuccess = { report -> _stremio.update { it.copy(busy = false, report = report) } },
                onFailure = { e -> _stremio.update { it.copy(busy = false, error = e.message) } },
            )
        }
    }

    fun stremioSignOut() {
        viewModelScope.launch {
            graph.stremioAccount.logout()
            _stremio.update { it.copy(report = null, error = null) }
        }
    }
}

// -------------------------------------------------------------------- UI

@Composable
fun AddonsScreen(graph: DataGraph, onBack: () -> Unit) {
    val viewModel = hubViewModel { AddonsViewModel(graph) }
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val install by viewModel.install.collectAsStateWithLifecycle()
    val firebase by viewModel.firebase.collectAsStateWithLifecycle()
    val stremio by viewModel.stremio.collectAsStateWithLifecycle()

    BackHandler { onBack() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = HubDimens.ScreenPaddingHorizontal,
            vertical = HubDimens.ScreenPaddingVertical,
        ),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item(key = "head") {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Addons", style = MaterialTheme.typography.displayLarge, color = HubColors.Text)
                Text(
                    text = "Cole a URL do manifest de um addon do Stremio. É dele que saem as " +
                        "fontes e as legendas — o app nunca mostra a lista de links, só usa a " +
                        "melhor que abrir.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = HubColors.TextDim,
                    modifier = Modifier.widthIn(max = 940.dp).fillMaxWidth(),
                )
            }
        }

        item(key = "firebase") {
            FirebaseSyncCard(
                state = firebase,
                onToggle = viewModel::toggleFirebaseSync,
                onPull = viewModel::pullFirebase,
                onPush = viewModel::pushFirebase,
            )
        }

        item(key = "stremio") {
            StremioAccountCard(
                state = stremio,
                onEmailChange = viewModel::onEmailChange,
                onPasswordChange = viewModel::onPasswordChange,
                onSignIn = viewModel::stremioSignIn,
                onSync = viewModel::stremioSync,
                onSignOut = viewModel::stremioSignOut,
            )
        }

        item(key = "install") {
            InstallCard(
                state = install,
                onUrlChange = viewModel::onUrlChange,
                onSubmit = viewModel::installFromField,
                onQuickInstall = viewModel::install,
            )
        }

        item(key = "installed-head") {
            Text(
                text = "Instalados (${addons.size})",
                style = MaterialTheme.typography.titleLarge,
                color = HubColors.Text,
            )
        }

        if (addons.isEmpty()) {
            item(key = "installed-empty") {
                Text(
                    text = "Nenhum addon instalado ainda.",
                    style = MaterialTheme.typography.titleMedium,
                    color = HubColors.TextFaint,
                )
            }
        } else {
            items(addons, key = { it.base }) { addon ->
                AddonRow(addon = addon, onRemove = { viewModel.remove(addon) })
            }
        }

        item(key = "bottom-space") { Spacer(Modifier.height(24.dp)) }
    }
}

// ------------------------------------------------------------ firebase card

@Composable
private fun FirebaseSyncCard(
    state: FirebaseSyncUi,
    onToggle: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
) {
    // Stacked — title, description, then a button row of its own — rather
    // than side by side. A Row here once put the description and the button
    // row in a width tug-of-war that, on a phone screen far narrower than
    // this was designed for, squeezed the buttons to nothing and wrapped
    // their labels one letter per line. Stacking removes the contest.
    SyncCard(accent = HubColors.Accent2) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sincronizar entre aparelhos", style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
            StatusPill(on = state.enabled)
        }
        Text(
            text = "Guarda a lista de addons na nuvem, atrelada à sua chave do mdblist. Ligue " +
                "nos dois aparelhos e ela acompanha — sem depender de conta do Stremio. Toda " +
                "alteração sobe sozinha.",
            style = MaterialTheme.typography.bodyMedium,
            color = HubColors.TextDim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HubButton(text = if (state.enabled) "Desligar" else "Ligar", enabled = !state.busy, onClick = onToggle)
            if (state.enabled) {
                HubButton(text = "Baixar", enabled = !state.busy, onClick = onPull)
                HubButton(text = "Enviar", enabled = !state.busy, onClick = onPush)
            }
        }

        state.error?.let { InlineMessage(it, isError = true) }
        state.lastDelta?.let { delta ->
            InlineMessage(
                if (delta > 0) "Lista atualizada — $delta addon(s) de diferença."
                else "Nada mudou — este aparelho já estava em dia.",
                isError = false,
            )
        }
    }
}

// ------------------------------------------------------------- stremio card

@Composable
private fun StremioAccountCard(
    state: StremioUi,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
) {
    // Stacked, the same as the Firebase card above — a side-by-side layout
    // here is what deformed the sign-in row on a phone width before: two text
    // fields and a button sharing one line, each demanding a fixed size the
    // screen did not have to give.
    SyncCard(accent = HubColors.Accent) {
        val session = state.session
        if (session != null) {
            Text("Conta Stremio conectada", style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
            Text(session.email, style = MaterialTheme.typography.bodyMedium, color = HubColors.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HubButton(
                    text = if (state.busy) "Sincronizando…" else "Sincronizar agora",
                    enabled = !state.busy,
                    onClick = onSync,
                )
                HubButton(text = "Desconectar", onClick = onSignOut)
            }
        } else {
            Text("Trazer os addons da sua conta Stremio", style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
            Text(
                text = "A coleção inteira vem pronta — já com as URLs configuradas, chave de " +
                    "debrid incluída. A senha vai direto para api.strem.io e não fica salva: o " +
                    "que guardamos é a chave de sessão que ela devolve.",
                style = MaterialTheme.typography.bodyMedium,
                color = HubColors.TextDim,
                modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(),
            )

            HubTextField(
                value = state.email,
                onValueChange = onEmailChange,
                placeholder = "e-mail do Stremio",
                keyboardType = KeyboardType.Email,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
            HubTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                placeholder = "senha",
                keyboardType = KeyboardType.Password,
                obscure = true,
                imeAction = ImeAction.Done,
                onImeAction = onSignIn,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            )
            HubButton(
                text = if (state.busy) "Entrando…" else "Entrar",
                primary = true,
                enabled = state.email.isNotBlank() && state.password.isNotBlank() && !state.busy,
                onClick = onSignIn,
            )
        }

        state.error?.let { InlineMessage(it, isError = true) }
        state.report?.let { report ->
            InlineMessage(
                "${report.imported.size} de ${report.received} addon(s) da sua conta importado(s).",
                isError = false,
            )
            if (report.skipped.isNotEmpty()) {
                Text(
                    text = "Não deu para importar ${report.skipped.size}: " +
                        report.skipped.joinToString("; ") { "${it.name} — ${it.reason}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = HubColors.TextFaint,
                    modifier = Modifier.widthIn(max = 880.dp).fillMaxWidth(),
                )
            }
        }
    }
}

// -------------------------------------------------------------- install card

@Composable
private fun InstallCard(
    state: InstallState,
    onUrlChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onQuickInstall: (String) -> Unit,
) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // `weight(1f)`, not a fixed width: the button beside it is
            // measured for its natural size first, and the field takes
            // whatever is left — the same fix as the sync cards above.
            HubTextField(
                value = state.url,
                onValueChange = onUrlChange,
                placeholder = "https://exemplo.strem.fun/manifest.json",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = onSubmit,
                modifier = Modifier.weight(1f),
            )
            HubButton(
                text = if (state.busy) "Lendo…" else "Instalar",
                primary = true,
                enabled = state.url.isNotBlank() && !state.busy,
                onClick = onSubmit,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            // A phone narrower than "label + two chips" scrolls instead of
            // clipping or forcing a wrap — there is nothing here worth
            // reflowing onto a second line.
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Padrão para começar",
                style = MaterialTheme.typography.labelLarge,
                color = HubColors.TextFaint,
            )
            QUICK_ADDONS.forEach { quick ->
                QuickAddChip(
                    label = quick.name,
                    enabled = !state.busy,
                    onClick = {
                        when {
                            quick.url != null -> onQuickInstall(quick.url)
                            quick.configureUrl != null -> openUrl(context, quick.configureUrl)
                        }
                    },
                )
            }
        }

        state.error?.let { InlineMessage(it, isError = true) }
    }
}

@Composable
private fun QuickAddChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(HubColors.Accent.copy(alpha = 0.14f))
            .border(1.dp, HubColors.Accent.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text = "+ $label", style = MaterialTheme.typography.labelLarge, color = HubColors.AccentSoft)
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Most set-top boxes have no browser at all; the configure page is
        // meant to be visited from a phone or PC in that case anyway.
    }
}

// -------------------------------------------------------------- installed

@Composable
private fun AddonRow(addon: Addon, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(HubColors.Surface.copy(alpha = 0.65f))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(addon.name, style = MaterialTheme.typography.titleLarge, color = HubColors.Text)
            Text(
                text = buildString {
                    append(addon.resources.joinToString(", ").ifBlank { "sem recursos declarados" })
                    if (addon.types.isNotEmpty()) append("  ·  ${addon.types.joinToString(", ")}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = HubColors.TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HubButton(text = "Remover", onClick = onRemove)
    }
}

// ------------------------------------------------------------ shared bits

@Composable
private fun SyncCard(accent: androidx.compose.ui.graphics.Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.07f))
            .border(1.dp, accent.copy(alpha = 0.26f), RoundedCornerShape(14.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private typealias ColumnScope = androidx.compose.foundation.layout.ColumnScope

@Composable
private fun StatusPill(on: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) HubColors.Accent2.copy(alpha = 0.16f) else HubColors.Surface)
            .border(
                1.dp,
                if (on) HubColors.Accent2.copy(alpha = 0.4f) else HubColors.Border,
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = if (on) "ligado" else "desligado",
            style = MaterialTheme.typography.labelSmall,
            color = if (on) HubColors.Accent2 else HubColors.TextFaint,
        )
    }
}

@Composable
private fun InlineMessage(text: String, isError: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) HubColors.Rotten else HubColors.Accent2,
    )
}

@Composable
private fun HubTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    obscure: Boolean = false,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HubColors.Surface)
            .border(1.dp, HubColors.Border, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.titleMedium, color = HubColors.TextFaint)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = HubColors.Text),
            cursorBrush = SolidColor(HubColors.Accent2),
            visualTransformation = if (obscure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { onImeAction?.invoke() },
            ),
        )
    }
}
