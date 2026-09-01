package com.secondbrain.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** EC-E1: "Validate RFC 5322 shape" — the shape check used before a gate ever opens, and again on retype. */
class EmailAddressValidatorTest {

    @Test
    fun `accepts ordinary addresses`() {
        assertTrue(EmailAddressValidator.isValid("udit@example.com"))
        assertTrue(EmailAddressValidator.isValid("udit.narayana2672@gmail.com"))
        assertTrue(EmailAddressValidator.isValid("first.last+tag@sub.example.co.in"))
        assertTrue(EmailAddressValidator.isValid("a@b.co"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertTrue(EmailAddressValidator.isValid("  udit@example.com  "))
    }

    @Test
    fun `rejects the shapes ASR is most likely to mangle into`() {
        assertFalse(EmailAddressValidator.isValid("udit at example dot com"))
        assertFalse(EmailAddressValidator.isValid("udit@example"))
        assertFalse(EmailAddressValidator.isValid("udit example.com"))
        assertFalse(EmailAddressValidator.isValid("@example.com"))
        assertFalse(EmailAddressValidator.isValid("udit@"))
        assertFalse(EmailAddressValidator.isValid("udit@@example.com"))
        assertFalse(EmailAddressValidator.isValid(""))
        assertFalse(EmailAddressValidator.isValid("   "))
    }

    @Test
    fun `rejects a bare name with no domain at all`() {
        assertFalse(EmailAddressValidator.isValid("charan"))
    }

    @Test
    fun `an absurdly long address is rejected rather than accepted unbounded`() {
        val huge = "a".repeat(300) + "@example.com"
        assertFalse(EmailAddressValidator.isValid(huge))
    }
}
