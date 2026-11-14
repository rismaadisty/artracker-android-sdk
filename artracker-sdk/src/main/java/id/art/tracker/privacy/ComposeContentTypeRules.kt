package id.art.tracker.privacy

import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import id.art.tracker.core.ClassAvailability

/** What a node's autofill [ContentType] means for masking. */
internal enum class ContentTypeClass { NONE, PASSWORD, SENSITIVE }

/**
 * Reads `SemanticsProperties.ContentType` (public from Compose UI 1.8) and classifies it, so a Compose field marked as
 * a card number, phone, e-mail, address or one-time code is masked like a View with the same autofill hint.
 *
 * Compose 1.7 has no public getter for it: calling it there throws `NoSuchMethodError`. [available] makes that
 * direct call once, inside a probe that turns any [LinkageError] into `false`, and the rest of this object is only
 * used when it is true. Everything is compared by **identity** with the companion constants: a `ContentType` keeps its
 * hint string private (and a host's R8 renames it), and it has no `equals`.
 *
 * References Compose types: only touch it from code that runs when Compose is on the classpath.
 */
internal object ComposeContentTypeRules {
    const val PROBE_KEY = "androidx.compose.ui.semantics.SemanticsProperties#getContentType"

    /** True when the Compose UI at runtime has the public `ContentType` semantics (1.8+). */
    val available: Boolean
        get() = ClassAvailability.probe(PROBE_KEY) { SemanticsProperties.ContentType != null }

    private val passwords: List<ContentType> by lazy { listOf(ContentType.Password, ContentType.NewPassword) }

    private val sensitive: List<ContentType> by lazy {
        listOf(
            ContentType.CreditCardNumber,
            ContentType.CreditCardSecurityCode,
            ContentType.CreditCardExpirationDate,
            ContentType.CreditCardExpirationMonth,
            ContentType.CreditCardExpirationYear,
            ContentType.CreditCardExpirationDay,
            ContentType.PhoneNumber,
            ContentType.PhoneNumberDevice,
            ContentType.PhoneNumberNational,
            ContentType.EmailAddress,
            ContentType.PostalAddress,
            ContentType.SmsOtpCode,
        )
    }

    /** The other companion constants: known, and not sensitive on their own. */
    private val harmless: List<ContentType> by lazy {
        listOf(
            ContentType.Username,
            ContentType.NewUsername,
            ContentType.PostalCode,
            ContentType.PostalCodeExtended,
            ContentType.AddressCountry,
            ContentType.AddressRegion,
            ContentType.AddressLocality,
            ContentType.AddressStreet,
            ContentType.AddressAuxiliaryDetails,
            ContentType.PersonFullName,
            ContentType.PersonFirstName,
            ContentType.PersonLastName,
            ContentType.PersonMiddleName,
            ContentType.PersonMiddleInitial,
            ContentType.PersonNamePrefix,
            ContentType.PersonNameSuffix,
            ContentType.PhoneCountryCode,
            ContentType.Gender,
            ContentType.BirthDateFull,
            ContentType.BirthDateDay,
            ContentType.BirthDateMonth,
            ContentType.BirthDateYear,
        )
    }

    /** Call only when [available]. */
    fun classify(node: SemanticsConfiguration): ContentTypeClass {
        val type = node.getOrNull(SemanticsProperties.ContentType) ?: return ContentTypeClass.NONE
        return when {
            passwords.any { it === type } -> ContentTypeClass.PASSWORD
            sensitive.any { it === type } -> ContentTypeClass.SENSITIVE
            harmless.any { it === type } -> ContentTypeClass.NONE
            // A combination (`a + b`) or `ContentType("hint")`: the hints cannot be read, so fail closed.
            else -> ContentTypeClass.SENSITIVE
        }
    }
}
