package com.threadfit.glucopilot.auth.security;

import com.ruoyi.framework.web.service.SysPermissionService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.auth.service.OpsIdentityService;
import com.threadfit.glucopilot.common.api.ApiResponse;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.util.JsonUtils;
import com.threadfit.glucopilot.doctor.service.DoctorRealmService;
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
  private final DoctorRealmService doctors;
  private final JsonUtils json;

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
        long ops = identities.opsIdOf(login.getUserId());
        // 医生身份是单独的业务边界，不能靠额外授予运营权限绕过。
        if (doctors.restricted(ops, user)
            && !doctors.allowed(request.getMethod(), request.getRequestURI())) {
          response.setStatus(403);
          response.setContentType("application/json;charset=UTF-8");
          response.getWriter().write(json.toJson(ApiResponse.fail(ErrorCode.FORBIDDEN)));
          return;
        }
        login.setUser(user);
        login.setPermissions(doctors.grants(ops, user, permissions.getMenuPermission(user)));
        RequestContext.setOpsId(ops);
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
