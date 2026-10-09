package com.threadfit.glucopilot.auth.service;

import com.ruoyi.framework.web.service.SysLoginService;
import com.ruoyi.framework.web.service.TokenService;
import com.ruoyi.system.service.ISysUserService;
import com.threadfit.glucopilot.auth.dto.OpsAuthDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OpsAuthService {
  private final SysLoginService loginService;
  private final TokenService tokenService;
  private final ISysUserService users;
  private final OpsIdentityService identities;
  private final RuoYiAuthorizationService authorization;

  @Value("${token.expireTime:480}")
  private long expireMinutes;

  public OpsAuthDtos.SessionResponse login(OpsAuthDtos.LoginRequest request) {
    String name = request.getUsername().trim();
    String token = loginService.login(name, request.getPassword(), "", "");
    var info =
        authorization.userInfoOf(identities.opsIdOf(users.selectUserByUserName(name).getUserId()));
    return OpsAuthDtos.SessionResponse.builder()
        .accessToken(token)
        .expiresIn(expireMinutes * 60)
        .opsId(info.getOpsId())
        .username(info.getUsername())
        .nickname(info.getNickname())
        .role(info.getRole())
        .roles(info.getRoles())
        .permissions(info.getPermissions())
        .menus(info.getMenus())
        .build();
  }

  public void logout(String token) {
    var user = tokenService.getLoginUserByToken(token);
    if (user != null) tokenService.delLoginUser(user.getToken());
  }
}
