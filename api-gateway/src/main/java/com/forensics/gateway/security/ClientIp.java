package com.forensics.gateway.security;

import java.net.InetSocketAddress;

import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * Client IP as seen by Google's front end. Cloud Run appends the connecting address as the
 * <em>last</em> X-Forwarded-For entry; earlier entries are client-controlled and are ignored, so
 * spoofing the header can't mint fresh rate-limit buckets.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] hops = xff.split(",");
            String last = hops[hops.length - 1].trim();
            if (!last.isEmpty()) {
                return last;
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
