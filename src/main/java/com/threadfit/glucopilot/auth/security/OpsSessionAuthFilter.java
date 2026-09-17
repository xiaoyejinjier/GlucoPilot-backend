package com.threadfit.glucopilot.auth.security;

import com.threadfit.glucopilot.common.constant.HttpHeaderNames;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.auth.entity.OpsSession;
import com.threadfit.glucopilot.auth.service.OpsAuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * 解析 B 端 Bearer，校验 ops_session，写入 RequestContext.opsId。
 */
@Component
@RequiredArgsConstructor
public class OpsSessionAuthFilter extends OncePerRequestFilter {

    private final OpsAuthService opsAuthService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return !request.getRequestURI().startsWith("/admin/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaderNames.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (StringUtils.hasText(token)) {
                try {
                    OpsSession session = opsAuthService.requireValidSession(token);
                    RequestContext.setOpsId(session.getOpsId());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(session.getOpsId(), null, Collections.emptyList());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (BizException ex) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
