package ir.bazr.inspection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceArchiveRulesTest {
    @Test fun imageFilterIncludesImagesOnly() {
        assertTrue(EvidenceArchiveRules.matches(EvidenceArchiveRules.IMAGES, true, "image/webp", "", emptyList()))
        assertFalse(EvidenceArchiveRules.matches(EvidenceArchiveRules.IMAGES, true, "application/pdf", "", emptyList()))
    }

    @Test fun documentFilterIncludesPdfAndLegacyUnknownFiles() {
        assertTrue(EvidenceArchiveRules.matches(EvidenceArchiveRules.DOCUMENTS, true, "application/pdf", "", emptyList()))
        assertTrue(EvidenceArchiveRules.matches(EvidenceArchiveRules.DOCUMENTS, true, "application/octet-stream", "", emptyList()))
        assertFalse(EvidenceArchiveRules.matches(EvidenceArchiveRules.DOCUMENTS, true, "image/jpeg", "", emptyList()))
    }

    @Test fun localAndServerFiltersAreMutuallyExclusive() {
        assertTrue(EvidenceArchiveRules.matches(EvidenceArchiveRules.LOCAL, true, "image/jpeg", "", emptyList()))
        assertFalse(EvidenceArchiveRules.matches(EvidenceArchiveRules.LOCAL, false, "image/jpeg", "", emptyList()))
        assertTrue(EvidenceArchiveRules.matches(EvidenceArchiveRules.SERVER, false, "application/pdf", "", emptyList()))
        assertFalse(EvidenceArchiveRules.matches(EvidenceArchiveRules.SERVER, true, "application/pdf", "", emptyList()))
    }

    @Test fun searchMatchesAnyMetadataFieldCaseInsensitively() {
        assertTrue(EvidenceArchiveRules.matches(EvidenceArchiveRules.ALL, true, "image/jpeg", "اداره", listOf("photo.jpg", "اداره آموزش", "شاخص ۱۲")))
        assertTrue(EvidenceArchiveRules.matches(EvidenceArchiveRules.ALL, true, "image/jpeg", "PHOTO", listOf("photo.jpg")))
        assertFalse(EvidenceArchiveRules.matches(EvidenceArchiveRules.ALL, true, "image/jpeg", "بیمارستان", listOf("photo.jpg", "اداره آموزش")))
    }

    @Test fun sizeFormattingIsBoundedAndReadable() {
        assertTrue(EvidenceArchiveRules.formatSize(2048).contains("کیلوبایت"))
        assertTrue(EvidenceArchiveRules.formatSize(2L * 1024 * 1024).contains("مگابایت"))
    }
}
