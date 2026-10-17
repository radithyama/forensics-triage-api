package com.forensics.gateway;

import java.util.Map;

import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;

/**
 * Client-facing messages are kept for 4xx (they are ours, written for the caller), but 5xx messages
 * come from library exceptions and can reveal bucket names, URLs or stack details, so they are
 * replaced with a generic text. The full exception is still logged server-side.
 */
@Component
public class SafeErrorAttributes extends DefaultErrorAttributes {

    @Override
    public Map<String, Object> getErrorAttributes(ServerRequest request, ErrorAttributeOptions options) {
        Map<String, Object> attributes = super.getErrorAttributes(request, options);
        if (attributes.get("status") instanceof Integer status && status >= 500) {
            attributes.put("message", status == 503 ? "Service temporarily unavailable, please retry" : "Internal error");
        }
        return attributes;
    }
}
