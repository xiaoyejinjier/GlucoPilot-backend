package com.threadfit.glucopilot.common.web;

import com.threadfit.glucopilot.common.constant.HttpHeaderNames;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.util.IdUtils;
import com.threadfit.glucopilot.common.util.IpUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 解析/生成 requestId，写入 Context、MDC、响应头；请求结束清理 ThreadLocal。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String requestId = request.getHeader(HttpHeaderNames.REQUEST_ID);
            if (!StringUtils.hasText(requestId)) {
                requestId = IdUtils.uuid();
            }
            RequestContext.setRequestId(requestId);
            RequestContext.setClientIp(IpUtils.clientIp(request));
            RequestContext.setUserAgent(request.getHeader("User-Agent"));

            String idempotencyKey = request.getHeader(HttpHeaderNames.IDEMPOTENCY_KEY);
            if (StringUtils.hasText(idempotencyKey)) {
                RequestContext.setIdempotencyKey(idempotencyKey.trim());
            }

            MDC.put(MDC_REQUEST_ID, requestId);
            response.setHeader(HttpHeaderNames.REQUEST_ID, requestId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            RequestContext.clear();
        }
    }
}
