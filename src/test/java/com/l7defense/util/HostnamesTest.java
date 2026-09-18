package com.l7defense.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostnamesTest {
    @Test void allowedDomainNeedsALabelBoundary() {
        assertTrue(Hostnames.matches(Hostnames.normalize("Play.Example.com.:25565"), "example.com"));
        assertFalse(Hostnames.matches("example.com.attacker.test", "example.com"));
        assertFalse(Hostnames.matches("notexample.com", "example.com"));
    }
    @Test void forwardingMetadataAndIpv6AreHandled() {
        assertEquals("play.example.com", Hostnames.normalize("play.example.com\u0000192.0.2.1\u0000uuid"));
        assertEquals("2001:db8::1", Hostnames.normalize("[2001:db8::1]:25565"));
        assertTrue(IpUtils.isLiteral("2001:db8::1"));
        assertFalse(IpUtils.isLiteral("attacker.example"));
        assertFalse(IpUtils.isLiteral("127.0.0.1 -j ACCEPT"));
        assertEquals("attacker.example", IpUtils.normalize("attacker.example"));
    }
}
