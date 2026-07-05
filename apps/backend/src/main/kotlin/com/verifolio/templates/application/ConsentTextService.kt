package com.verifolio.templates.application

import com.verifolio.platform.SupportedLocales
import com.verifolio.platform.VerifolioProperties
import com.verifolio.templates.api.ConsentTextView
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Service

/**
 * Consent type exposed at GET /api/v1/consent-texts/{consentType}.
 * Each type maps to the region-configured active text (VerifolioProperties.Consents);
 * the copy itself ships as classpath resources keyed by textId/version/locale
 * (docs/REGION_POLICIES.md § Consent Texts).
 */
enum class ConsentType {
    REQUESTER_VERBAL_CONSENT_ATTESTATION,
    RECOMMENDER_PROCESSING_CONSENT,
    CROSS_BORDER_TRANSFER_CONSENT,
    RECOMMENDER_PUBLIC_SHARING_CONSENT,
}

private const val FALLBACK_LOCALE = "en"

@Service
internal class ConsentTextService(
    private val props: VerifolioProperties,
) : InitializingBean {

    /** Fail fast at startup if any active consent text lacks its English resource (config error). */
    override fun afterPropertiesSet() {
        ConsentType.entries.forEach { type ->
            val text = activeText(type)
            checkNotNull(readResource(text, FALLBACK_LOCALE)) {
                "Missing consent text resource ${resourcePath(text, FALLBACK_LOCALE)} for active $type " +
                    "(verifolio.consents misconfigured or resource not shipped)"
            }
        }
    }

    /**
     * Resolves the active text for [consentType] in [locale], falling back to English when the
     * locale is unsupported or has no resource. Never throws for locale problems; the startup
     * check guarantees the English resource exists.
     */
    fun resolve(consentType: ConsentType, locale: String): ConsentTextView {
        val text = activeText(consentType)
        val requested = locale.takeIf { it in SupportedLocales.ALL } ?: FALLBACK_LOCALE
        val content = readResource(text, requested)
        val effectiveLocale = if (content != null) requested else FALLBACK_LOCALE
        val effectiveContent = content
            ?: readResource(text, FALLBACK_LOCALE)
            ?: error("Missing consent text resource ${resourcePath(text, FALLBACK_LOCALE)}")

        val lines = effectiveContent.lines()
        val titleIndex = lines.indexOfFirst { it.trimStart().startsWith("#") }
        check(titleIndex >= 0) { "Consent text resource ${resourcePath(text, effectiveLocale)} has no '#' title line" }
        val title = lines[titleIndex].trimStart().trimStart('#').trim()
        val body = lines.drop(titleIndex + 1).joinToString("\n").trim()

        return ConsentTextView(
            consentType = consentType.name,
            textId = text.textId,
            version = text.version,
            locale = effectiveLocale,
            title = title,
            body = body,
        )
    }

    private fun activeText(consentType: ConsentType): VerifolioProperties.ConsentText = when (consentType) {
        ConsentType.REQUESTER_VERBAL_CONSENT_ATTESTATION -> props.consents.requesterAttestation
        ConsentType.RECOMMENDER_PROCESSING_CONSENT -> props.consents.processing
        ConsentType.CROSS_BORDER_TRANSFER_CONSENT -> props.consents.crossBorderTransfer
        ConsentType.RECOMMENDER_PUBLIC_SHARING_CONSENT -> props.consents.publicSharing
    }

    private fun resourcePath(text: VerifolioProperties.ConsentText, locale: String) =
        "consent-texts/${text.textId}/${text.version}/$locale.md"

    private fun readResource(text: VerifolioProperties.ConsentText, locale: String): String? =
        javaClass.classLoader.getResourceAsStream(resourcePath(text, locale))
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
}
