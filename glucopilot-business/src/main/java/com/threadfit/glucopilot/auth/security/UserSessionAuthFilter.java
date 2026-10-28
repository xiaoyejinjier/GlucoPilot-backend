package com.threadfit.glucopilot.auth.security;

import com.threadfit.glucopilot.auth.dto.AuthDtos.SessionPrincipal;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.constant.HttpHeaderNames;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.util.JsonUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * 解析 C 端 Bearer，校验 user_session，写入 RequestContext + SecurityContext。
 * 对 DISABLED(0) / CANCELLED_RECOVERABLE(4) 账号仅放行注销（恢复）白名单路径，其余 403。
 */
@Component
@RequiredArgsConstructor
public class UserSessionAuthFilter extends OncePerRequestFilter {

    private final AuthService authService;
    private final JsonUtils json;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaderNames.AUTHORIZATION);
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (StringUtils.hasText(token)) {
                SessionPrincipal principal = authService.resolveSession(token);
                if (principal != null) {
                    Integer status = principal.accountStatus();
                    boolean restricted = status != null
                            && (status == AccountStatus.DISABLED.getCode()
                                || status == AccountStatus.CANCELLED_RECOVERABLE.getCode());
                    if (restricted && !isCancellationWhitelist(request)) {
                        writeForbidden(response);
                        return;
                    }
                    RequestContext.setUserId(principal.userId());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal.userId(), null, Collections.emptyList());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean isCancellationWhitelist(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(method)) {
            return path.startsWith("/api/v1/users/me/cancellation")
                    || "/api/v1/auth/sms/codes".equals(path)
                    || "/api/v1/auth/email/codes".equals(path);
        }
        if ("GET".equalsIgnoreCase(method)) {
            return "/api/v1/users/me".equals(path)
                    || "/api/v1/app-config".equals(path)
                    || path.startsWith("/api/v1/users/me/cancellation");
        }
        if ("DELETE".equalsIgnoreCase(method)) {
            return "/api/v1/auth/sessions/current".equals(path);
        }
        return false;
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(json.toJson(ApiResponse.fail(ErrorCode.FORBIDDEN)));
    }
}