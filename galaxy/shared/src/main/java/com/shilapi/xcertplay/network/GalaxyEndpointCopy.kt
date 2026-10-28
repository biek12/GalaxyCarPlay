package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.Iap2WirelessCarPlayEndpoint

/** 只替换已经绑定的发布地址；热点、端口和认证身份保持原会话值，核心端点无需改动。 */
internal fun Iap2WirelessCarPlayEndpoint.withIpAddresses(addresses: List<String>) =
    Iap2WirelessCarPlayEndpoint(ssid, passphrase, channel, security, addresses, airPlayPort,
        deviceIdentifier, publicKey, sourceVersion, accessPointBssid)
