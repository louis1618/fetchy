package com.luverse.fetchy

import org.junit.Test
import org.junit.Assert.*

class NetworkPolicyTest {
    @Test fun blockedNetworksExplainWhyWithoutChangingPolicy() {
        assertEquals("인터넷 연결을 기다리는 중", downloadNetworkWaitReason(false, false, false, true, false))
        assertEquals("Wi-Fi 연결을 기다리는 중", downloadNetworkWaitReason(true, false, true, true, true))
        assertEquals("모바일 데이터 사용이 꺼져 있어요 · Wi-Fi에 연결하세요", downloadNetworkWaitReason(true, false, true, false, false))
        assertNull(downloadNetworkWaitReason(true, true, false, true, false))
    }
    @Test fun offlineNeverTransfers() {
        assertFalse(downloadNetworkAllowed(false, true, false, false, true))
    }
    @Test fun wifiOnlyBlocksCellular() {
        assertFalse(downloadNetworkAllowed(true, false, true, true, true))
        assertTrue(downloadNetworkAllowed(true, true, false, true, true))
    }
    @Test fun mobileDataDisabledPreservesWifiAndEthernet() {
        assertFalse(downloadNetworkAllowed(true, false, true, false, false))
        assertTrue(downloadNetworkAllowed(true, true, false, false, false))
        assertTrue(downloadNetworkAllowed(true, false, false, false, false))
    }
    @Test fun mobileDataEnabledAllowsCellular() {
        assertTrue(downloadNetworkAllowed(true, false, true, false, true))
    }
}
