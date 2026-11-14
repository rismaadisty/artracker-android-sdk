package id.art.tracker.playground.labs

/** What replay is expected to show for a case. LEAKS_KNOWN_LIMITATION cases document a gap the README lists. */
enum class Expectation { MASKED, VISIBLE, BLOCKED, LEAKS_KNOWN_LIMITATION }

/**
 * [limitationRef] is a phrase from the README's "Known limitations" section; a unit test checks it is really there, so
 * the lab can never claim a limitation the README does not list.
 */
data class PrivacyCase(
    val id: String,
    val title: String,
    val description: String,
    val expectation: Expectation,
    val limitationRef: String? = null,
)

object PrivacyCases {
    private const val UNMASK_REF = "resets masking for that node's whole"
    private const val PLAIN_TEXT_REF = "appears verbatim in the replay"

    val all: List<PrivacyCase> = listOf(
        PrivacyCase("compose_password", "Compose: password", "Field dengan PasswordVisualTransformation dan semantics password: selalu di-mask.", Expectation.MASKED),
        PrivacyCase("compose_email_default", "Compose: email (default)", "OutlinedTextField biasa: di-mask karena maskAllInputs = true.", Expectation.MASKED),
        PrivacyCase("compose_numeric_default", "Compose: angka (default)", "Field dengan keyboard angka: di-mask oleh maskAllInputs. Dengan maskAllInputs = false angka Compose tetap terbaca kecuali field punya ContentType sensitif (Compose UI 1.8+).", Expectation.MASKED),
        PrivacyCase("compose_mask_modifier", "Compose: Modifier.artrackerMask()", "Teks total di-mask walaupun bukan input.", Expectation.MASKED),
        PrivacyCase("compose_unmask_modifier", "Compose: Modifier.artrackerUnmask()", "Field biasa yang sengaja dibuka (bukan password).", Expectation.VISIBLE),
        PrivacyCase("compose_unmask_inside_masked", "Compose: unmask di dalam area mask", "Unmask di dalam kontainer ter-mask membuka kembali seluruh subtree-nya.", Expectation.LEAKS_KNOWN_LIMITATION, UNMASK_REF),
        PrivacyCase("compose_plain_text_email", "Compose: Text berisi email", "Teks biasa tidak diredaksi nilainya di replay: email tampil apa adanya.", Expectation.LEAKS_KNOWN_LIMITATION, PLAIN_TEXT_REF),
        PrivacyCase("view_password", "View: EditText password", "TYPE_TEXT_VARIATION_PASSWORD: selalu di-mask.", Expectation.MASKED),
        PrivacyCase("view_numeric", "View: EditText angka", "Input angka di View di-mask walaupun maskAllInputs = false.", Expectation.MASKED),
        PrivacyCase("view_tag_mask", "View: tag artracker-mask", "Kontainer dengan android:tag=\"artracker-mask\" di-mask beserta isinya.", Expectation.MASKED),
        PrivacyCase("view_autofill_hint_card", "View: hint autofill kartu", "autofillHints creditCardNumber dianggap sensitif.", Expectation.MASKED),
        PrivacyCase("webview_blocked", "WebView", "Selalu digambar sebagai blok berlabel; isinya tidak pernah ditangkap.", Expectation.BLOCKED),
        PrivacyCase("imageview_placeholder", "ImageView", "Digambar sebagai placeholder netral; bitmap tidak pernah direkam.", Expectation.BLOCKED),
        PrivacyCase("popup_window", "Popup (window terpisah)", "Popup adalah window root sendiri, tapi tepi atasnya menempel pada area yang di-mask (Column dengan artrackerMask()): seluruh popup di-mask.", Expectation.MASKED),
        PrivacyCase("dropdown_menu", "Dropdown menu (window terpisah)", "Menu dropdown di bawah anchor yang di-mask: seluruh menu di-mask.", Expectation.MASKED),
        PrivacyCase("view_autocomplete_masked", "View: saran AutoCompleteTextView", "Daftar saran email muncul di window terpisah di bawah field yang di-mask (maskAllInputs): seluruh daftar di-mask. Ketik `f` untuk memunculkannya.", Expectation.MASKED),
        PrivacyCase("max_all_text_toggle", "maskAllText", "Teks biasa terlihat kecuali maskAllText diaktifkan di Pengaturan (butuh restart).", Expectation.VISIBLE),
    )
}
