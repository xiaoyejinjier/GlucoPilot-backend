package com.threadfit.glucopilot.auth.security;

import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.auth.service.OpsIdentityService;
import com.threadfit.glucopilot.common.context.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Upstream JWT/Redis sessions on B-end paths, with a historical audit identity bridge. */
@Component
@RequiredArgsConstructor
public class OpsSessionAuthFilter extends OncePerRequestFilter {
  private final TokenService tokens;
  private final ISysUserService users;
  private final SysPermissionService permissions;
  private final OpsIdentityService identities;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/admin/v1/")
        || "OPTIONS".equals(request.getMethod());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var login = tokens.getLoginUser(request);
    if (login != null) {
      var user = users.selectUserById(login.getUserId());
      if (user != null && "0".equals(user.getStatus()) && "0".equals(user.getDelFlag())) {
        login.setUser(user);
        login.setPermissions(permissions.getMenuPermission(user));
        RequestContext.setOpsId(identities.opsIdOf(login.getUserId()));
        SecurityContextHolder.getContext()
            .setAuthentication(
                new UsernamePasswordAuthenticationToken(login, null, login.getAuthorities()));
        tokens.verifyToken(login);
      } else tokens.delLoginUser(login.getToken());
    }
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
