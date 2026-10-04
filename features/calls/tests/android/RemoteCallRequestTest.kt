package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCallRequestTest {
    @Test
    fun normalizesCommonPhoneNumberFormatting() {
        assertEquals("+79991234567", normalizedPhoneNumber(" +7 (999) 123-45-67 "))
        assertEquals("12345", normalizedPhoneNumber("12345"))
    }

    @Test
    fun rejectsCommandsExtensionsAndInvalidLengths() {
        assertNull(normalizedPhoneNumber("*100#"))
        assertNull(normalizedPhoneNumber("+1 555 CALL-NOW"))
        assertNull(normalizedPhoneNumber("12"))
        assertNull(normalizedPhoneNumber("1234567890123456"))
        assertNull(normalizedPhoneNumber("7 +999 123"))
        assertNull(normalizedPhoneNumber("١٢٣٤٥"))
    }

    @Test
    fun wireKindsMatchTheDocumentedProtocol() {
        // Locks docs/protocol.md's calls.v1 reply kinds so the Phase 0.5 extraction
        // cannot silently drift the wire protocol.
        assertEquals("calls.started", RemoteCallResult.STARTED.wireKind)
        assertEquals("calls.confirmation_required", RemoteCallResult.CONFIRMATION_REQUIRED.wireKind)
        assertEquals("calls.rejected", RemoteCallResult.REJECTED.wireKind)
    }
}
