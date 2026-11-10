package id.art.tracker.playground.labs

import android.os.Build
import android.text.InputType
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import id.art.tracker.ARTracker
import id.art.tracker.R
import id.art.tracker.playground.boot.BootState
import id.art.tracker.playground.local.LocalServersProvider
import id.art.tracker.playground.settings.SharedPrefsSettingsStore
import id.art.tracker.playground.ui.ActionCard
import id.art.tracker.privacy.artrackerMask
import id.art.tracker.privacy.artrackerUnmask

/** Every masking case on one screen, each with its documented expectation, plus the attribute-redaction form. */
@Composable
fun PrivacyLab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { SharedPrefsSettingsStore(context).load() }
    val remote = BootState.resolved?.badge?.remote ?: false
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.lab_privacy_effective, settings.maskAllInputs, settings.maskAllText),
            style = MaterialTheme.typography.bodySmall,
        )
        PrivacyCases.all.forEach { case ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(case.title, style = MaterialTheme.typography.titleMedium)
                    Text(expectationLabel(case.expectation), style = MaterialTheme.typography.labelMedium)
                    Text(case.description, style = MaterialTheme.typography.bodySmall)
                    case.limitationRef?.let { Text("README: …$it…", style = MaterialTheme.typography.bodySmall) }
                    CaseWidget(case.id)
                }
            }
        }
        Text(stringResource(R.string.lab_privacy_redaction), style = MaterialTheme.typography.titleSmall)
        RedactionForm(remote)
    }
}

private fun expectationLabel(e: Expectation) = when (e) {
    Expectation.MASKED -> "Diharapkan: di-mask"
    Expectation.VISIBLE -> "Diharapkan: terlihat"
    Expectation.BLOCKED -> "Diharapkan: diblok (isi tidak ditangkap)"
    Expectation.LEAKS_KNOWN_LIMITATION -> "Diketahui TIDAK di-mask (keterbatasan terdokumentasi)"
}

@Composable
private fun CaseWidget(id: String) {
    when (id) {
        "compose_password" -> {
            var v by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                v, { v = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().semantics { password() },
            )
        }
        "compose_email_default" -> {
            var v by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                v, { v = it }, label = { Text("Email") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(),
            )
        }
        "compose_numeric_default" -> {
            var v by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                v, { v = it }, label = { Text("Nomor kartu") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
            )
        }
        "compose_mask_modifier" -> Text("Total: Rp 120.000", Modifier.artrackerMask())
        "compose_unmask_modifier" -> {
            var v by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(v, { v = it }, label = { Text("Nama panggilan") }, singleLine = true, modifier = Modifier.fillMaxWidth().artrackerUnmask())
        }
        "compose_unmask_inside_masked" -> Column(Modifier.artrackerMask()) {
            Text("Saldo: Rp 5.000.000 (di-mask)")
            Box(Modifier.artrackerUnmask()) { Text("Teks ini ikut tampil kembali") }
        }
        "compose_plain_text_email" -> Text("Hubungi: fake.user@example.invalid")
        "view_password" -> AndroidView(factory = { ctx ->
            EditText(ctx).apply {
                hint = "Password (View)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }, modifier = Modifier.fillMaxWidth())
        "view_numeric" -> AndroidView(factory = { ctx ->
            EditText(ctx).apply {
                hint = "Nomor (View)"
                inputType = InputType.TYPE_CLASS_NUMBER
            }
        }, modifier = Modifier.fillMaxWidth())
        "view_tag_mask" -> AndroidView(factory = { ctx ->
            FrameLayout(ctx).apply {
                tag = "artracker-mask"
                addView(TextView(ctx).apply { text = "Saldo: Rp 5.000.000 (di-mask lewat tag)" })
            }
        }, modifier = Modifier.fillMaxWidth())
        "view_autofill_hint_card" -> AndroidView(factory = { ctx ->
            EditText(ctx).apply {
                hint = "Kartu (hint autofill)"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setAutofillHints("creditCardNumber")
            }
        }, modifier = Modifier.fillMaxWidth())
        "webview_blocked" -> AndroidView(factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160)
                loadData("<html><body><h3>Isi WebView</h3><p>fake.user@example.invalid</p></body></html>", "text/html", "UTF-8")
            }
        }, modifier = Modifier.fillMaxWidth().height(120.dp))
        "imageview_placeholder" -> AndroidView(factory = { ctx ->
            ImageView(ctx).apply { setImageResource(R.drawable.ic_launcher_foreground) }
        }, modifier = Modifier.height(72.dp))
        "popup_window" -> {
            var open by remember { mutableStateOf(false) }
            Button(onClick = { open = !open }) { Text("Buka popup") }
            if (open) Popup { Card { Text("Isi popup: fake.user@example.invalid", Modifier.padding(12.dp)) } }
        }
        "dropdown_menu" -> {
            var open by remember { mutableStateOf(false) }
            Box {
                Button(onClick = { open = true }) { Text("Buka menu") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Item rahasia: fake.user@example.invalid") }, onClick = { open = false })
                }
            }
        }
        "max_all_text_toggle" -> Text("Teks biasa ini terlihat kecuali maskAllText aktif.")
    }
}

/** Sends obviously fake sensitive data so the SDK's redactor can be seen working (disabled against a remote endpoint). */
@Composable
fun RedactionForm(remote: Boolean) {
    val reason = LabGuard.disabledReason(LabActionKind.FAKE_PII, remote)
    fun payload(name: String) = HostilePayloads.all.first { it.name == name }.properties
    ActionCard(stringResource(R.string.lab_privacy_send_keys), stringResource(R.string.lab_privacy_send_keys_desc), disabledReason = reason) {
        ARTracker.track("redaction_keys", payload("sensitive_keys"))
        "track `redaction_keys` → antre"
    }
    ActionCard(stringResource(R.string.lab_privacy_send_values), stringResource(R.string.lab_privacy_send_values_desc), disabledReason = reason) {
        ARTracker.track("redaction_values", payload("sensitive_values"))
        "track `redaction_values` → antre"
    }
    ActionCard(stringResource(R.string.lab_privacy_send_name), stringResource(R.string.lab_privacy_send_name_desc), disabledReason = reason) {
        ARTracker.track("fake.user@example.invalid")
        "track dengan nama berisi email → antre"
    }
}
