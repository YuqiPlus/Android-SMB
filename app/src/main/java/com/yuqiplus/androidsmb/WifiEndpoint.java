package com.yuqiplus.androidsmb;

import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;

record WifiEndpoint(Network network, LinkAddress link) {
    static WifiEndpoint from(Network network, LinkProperties properties) {
        for (LinkAddress address : properties.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address
                    && !address.getAddress().isLoopbackAddress()
                    && address.getPrefixLength() > 0) {
                return new WifiEndpoint(network, address);
            }
        }
        return null;
    }

    String host() {
        return link.getAddress().getHostAddress();
    }

    String subnet() {
        return host() + "/" + link.getPrefixLength();
    }
}
