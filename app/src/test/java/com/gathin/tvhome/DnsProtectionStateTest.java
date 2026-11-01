package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DnsProtectionStateTest {
    @Test public void activeAdGuardIsProtected() {
        DnsProtectionState.Result result = DnsProtectionState.evaluate("hostname",
                AdBlockingManager.HOST, true, true, AdBlockingManager.HOST);
        assertEquals(DnsProtectionState.Kind.PROTECTED, result.kind);
        assertTrue(result.adGuardSelected);
    }

    @Test public void selectedButUnvalidatedIsAnError() {
        DnsProtectionState.Result result = DnsProtectionState.evaluate("hostname",
                AdBlockingManager.HOST, true, false, null);
        assertEquals(DnsProtectionState.Kind.CONNECTION_ERROR, result.kind);
        assertTrue(result.adGuardSelected);
    }

    @Test public void selectedWithoutNetworkWaits() {
        DnsProtectionState.Result result = DnsProtectionState.evaluate("hostname",
                AdBlockingManager.HOST, false, false, null);
        assertEquals(DnsProtectionState.Kind.OFFLINE, result.kind);
    }

    @Test public void anotherProviderIsNotClaimedAsAdGuard() {
        DnsProtectionState.Result result = DnsProtectionState.evaluate("hostname",
                "one.one.one.one", true, true, "one.one.one.one");
        assertEquals(DnsProtectionState.Kind.OTHER_PROVIDER, result.kind);
        assertFalse(result.adGuardSelected);
    }

    @Test public void automaticModeIsOff() {
        DnsProtectionState.Result result = DnsProtectionState.evaluate("opportunistic",
                null, true, false, null);
        assertEquals(DnsProtectionState.Kind.OFF, result.kind);
        assertFalse(result.adGuardSelected);
    }
}
