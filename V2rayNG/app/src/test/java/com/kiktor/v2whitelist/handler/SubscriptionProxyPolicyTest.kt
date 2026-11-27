package com.kiktor.v2whitelist.handler

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionProxyPolicyTest {

    @Test
    fun `shouldUseEphemeralProxy is true only when running VPN hides tunnel in TUN mode`() {
        assertTrue(
            SubscriptionHelper.shouldUseEphemeralProxy(
                isVpnRunning = true,
                isVpnMode = true,
                isUsingHevTun = false,
                isHideTunnelEnabled = true
            )
        )
        assertFalse(
            SubscriptionHelper.shouldUseEphemeralProxy(
                isVpnRunning = false,
                isVpnMode = true,
                isUsingHevTun = false,
                isHideTunnelEnabled = true
            )
        )
        assertFalse(
            SubscriptionHelper.shouldUseEphemeralProxy(
                isVpnRunning = true,
                isVpnMode = false,
                isUsingHevTun = false,
                isHideTunnelEnabled = true
            )
        )
        assertFalse(
            SubscriptionHelper.shouldUseEphemeralProxy(
                isVpnRunning = true,
                isVpnMode = true,
                isUsingHevTun = true,
                isHideTunnelEnabled = true
            )
        )
        assertFalse(
            SubscriptionHelper.shouldUseEphemeralProxy(
                isVpnRunning = true,
                isVpnMode = true,
                isUsingHevTun = false,
                isHideTunnelEnabled = false
            )
        )
    }

    @Test
    fun `shouldAllowDirect respects onlyViaVpn except explicit system VPN transport`() {
        assertTrue(SubscriptionHelper.shouldAllowDirect(onlyViaVpn = false, allowDirectViaSystemVpn = false))
        assertTrue(SubscriptionHelper.shouldAllowDirect(onlyViaVpn = false, allowDirectViaSystemVpn = true))
        assertFalse(SubscriptionHelper.shouldAllowDirect(onlyViaVpn = true, allowDirectViaSystemVpn = false))
        assertTrue(SubscriptionHelper.shouldAllowDirect(onlyViaVpn = true, allowDirectViaSystemVpn = true))
    }

    @Test
    fun `canProceedOnlyViaVpn requires active VPN and reachable proxy or system VPN`() {
        assertFalse(SubscriptionHelper.canProceedOnlyViaVpn(isVpnActive = false, socksPort = 35720, allowDirectViaSystemVpn = false))
        assertFalse(SubscriptionHelper.canProceedOnlyViaVpn(isVpnActive = false, socksPort = 0, allowDirectViaSystemVpn = true))
        assertFalse(SubscriptionHelper.canProceedOnlyViaVpn(isVpnActive = true, socksPort = 0, allowDirectViaSystemVpn = false))
        assertTrue(SubscriptionHelper.canProceedOnlyViaVpn(isVpnActive = true, socksPort = 41234, allowDirectViaSystemVpn = false))
        assertTrue(SubscriptionHelper.canProceedOnlyViaVpn(isVpnActive = true, socksPort = 0, allowDirectViaSystemVpn = true))
    }
}
