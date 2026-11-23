package com.fintech.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecipientEmailMaskingTest {

    @Test
    void keepsFirstCharacterAndDomainOnly() {

        assertEquals(
                "j***@example.com",
                RecipientEmailMasking.mask("john.doe@example.com")
        );
    }

    @Test
    void singleCharacterLocalPart() {

        assertEquals(
                "a***@example.com",
                RecipientEmailMasking.mask("a@example.com")
        );
    }

    @Test
    void doesNotSplitSurrogatePairs() {

        assertEquals(
                "😀***@example.com",
                RecipientEmailMasking.mask("😀smile@example.com")
        );
    }

    @Test
    void malformedValuesAreFullyMasked() {

        assertEquals("***", RecipientEmailMasking.mask(null));
        assertEquals("***", RecipientEmailMasking.mask("no-at-sign"));
        assertEquals("***", RecipientEmailMasking.mask("@example.com"));
        assertEquals("***", RecipientEmailMasking.mask("user@"));
    }
}
