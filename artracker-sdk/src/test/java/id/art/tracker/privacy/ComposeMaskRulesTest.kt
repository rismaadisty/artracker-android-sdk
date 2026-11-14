package id.art.tracker.privacy

import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.password
import androidx.compose.ui.text.AnnotatedString
import id.art.tracker.PrivacyConfig
import id.art.tracker.core.ClassAvailability
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Compose masking decision, by `ContentType`, explicit mask / unmask and the global switches. */
class ComposeMaskRulesTest {
    private val off = PrivacyConfig(maskAllInputs = false)

    @After
    fun tearDown() {
        ClassAvailability.resetOverrides()
    }

    private fun node(block: SemanticsConfiguration.() -> Unit) = SemanticsConfiguration().apply(block)

    private fun decide(config: PrivacyConfig, n: SemanticsConfiguration, parentMasked: Boolean = false) =
        ComposeMaskRules.decide(config, n, parentMasked)

    private val sensitive = listOf(
        "CreditCardNumber" to ContentType.CreditCardNumber,
        "CreditCardSecurityCode" to ContentType.CreditCardSecurityCode,
        "CreditCardExpirationDate" to ContentType.CreditCardExpirationDate,
        "CreditCardExpirationMonth" to ContentType.CreditCardExpirationMonth,
        "CreditCardExpirationYear" to ContentType.CreditCardExpirationYear,
        "CreditCardExpirationDay" to ContentType.CreditCardExpirationDay,
        "PhoneNumber" to ContentType.PhoneNumber,
        "PhoneNumberDevice" to ContentType.PhoneNumberDevice,
        "PhoneNumberNational" to ContentType.PhoneNumberNational,
        "EmailAddress" to ContentType.EmailAddress,
        "PostalAddress" to ContentType.PostalAddress,
        "SmsOtpCode" to ContentType.SmsOtpCode,
    )

    @Test
    fun contentTypeIsAvailableOnTheTestClasspath() {
        assertTrue("the SDK is compiled against Compose UI 1.8+", ComposeContentTypeRules.available)
    }

    @Test
    fun sensitiveContentTypes_areMasked_withMaskAllInputsOff() {
        for ((name, type) in sensitive + listOf("Password" to ContentType.Password, "NewPassword" to ContentType.NewPassword)) {
            assertEquals(name, MaskDecision.MASK, decide(off, node { contentType = type }))
        }
    }

    @Test
    fun nonSensitiveContentTypes_leaveTheDecisionUnchanged() {
        for (type in listOf(ContentType.Username, ContentType.PersonFullName, ContentType.PostalCode, ContentType.PhoneCountryCode)) {
            assertEquals(MaskDecision.NONE, decide(off, node { contentType = type }))
            // The defaults are not weakened: the same field as an input is still masked by maskAllInputs.
            assertEquals(
                MaskDecision.MASK,
                decide(PrivacyConfig(), node { contentType = type; editableText = AnnotatedString("x") }),
            )
        }
    }

    @Test
    fun combinedOrCustomContentType_failsClosed() {
        assertEquals(MaskDecision.MASK, decide(off, node { contentType = ContentType.Username + ContentType.EmailAddress }))
        assertEquals(MaskDecision.MASK, decide(off, node { contentType = ContentType("loyaltyId") }))
    }

    @Test
    fun unmaskCannotUnmaskPasswordSemantics() {
        assertEquals(MaskDecision.MASK, decide(off, node { password(); this[ARTrackerMaskKey] = false }))
    }

    @Test
    fun unmaskCannotUnmaskPasswordContentType() {
        for (type in listOf(ContentType.Password, ContentType.NewPassword)) {
            assertEquals(MaskDecision.MASK, decide(off, node { contentType = type; this[ARTrackerMaskKey] = false }))
        }
    }

    @Test
    fun unmaskOverridesOtherSensitiveContentTypes() {
        // Same as the View tag `artracker-unmask` beating a sensitive autofill hint.
        assertEquals(
            MaskDecision.NONE,
            decide(off, node { contentType = ContentType.CreditCardNumber; this[ARTrackerMaskKey] = false }),
        )
    }

    @Test
    fun contentTypeUnavailable_fallsBackWithoutMaskingEverything() {
        ClassAvailability.overrides[ComposeContentTypeRules.PROBE_KEY] = false

        // On Compose 1.7 the read would throw NoSuchMethodError, and the catch in decide() would MASK every node.
        assertEquals(MaskDecision.NONE, decide(off, node { contentType = ContentType.CreditCardNumber }))
        assertEquals(MaskDecision.NONE, decide(off, node { }))
        assertEquals(MaskDecision.MASK, decide(off, node { password() }))
        assertTrue(SemanticsProperties.Password in node { password() })
    }
}
