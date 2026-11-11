package org.mozilla.focus.utils;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmartAddressTest {
    @Test public void handlesModernDomainSuffixes() {
        for (String domain : new String[]{"example.st", "example.sx", "example.cx", "example.pk", "example.dev", "example.photography"}) {
            assertTrue(domain, SmartAddress.isAddress(domain));
            assertEquals("https://" + domain, SmartAddress.normalize(domain));
        }
        assertEquals("https://example.sx/live?a=1", SmartAddress.normalize("example.sx/live?a=1"));
        assertEquals("https://example.pk:8443", SmartAddress.normalize("example.pk:8443"));
        assertEquals("https://example.st", SmartAddress.normalize("//example.st"));
    }

    @Test public void searchesRemainSearchesAndExplicitSchemesAreRespected() {
        assertFalse(SmartAddress.isAddress("weather tomorrow"));
        assertFalse(SmartAddress.isAddress("streaming"));
        assertFalse(SmartAddress.isAddress("javascript:alert(1)"));
        assertTrue(SmartAddress.isAddress("www.example.sx"));
        assertTrue(SmartAddress.isAddress("localhost:8080"));
        assertEquals("http://localhost:8080", SmartAddress.normalize("localhost:8080"));
        assertEquals("http://example.st", SmartAddress.normalize("http://example.st"));
        assertEquals("https://example.st", SmartAddress.normalize("https://example.st"));
    }
}
