package com.threadfit.glucopilot.auth.service;

import com.ruoyi.common.constant.CacheConstants;
import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.core.redis.RedisCache;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Upstream Redis is the single authority for operations sessions. */
@Service
@RequiredArgsConstructor
public class OpsSessionRegistry {
  private final RedisCache redis;

  public List<LoginUser> online() {
    return redis.keys(CacheConstants.LOGIN_TOKEN_KEY + "*").stream()
        .map(key -> (LoginUser) redis.getCacheObject(key))
        .filter(Objects::nonNull)
        .toList();
  }

  public void revokeUser(Long userId) {
    online().stream()
        .filter(u -> Objects.equals(u.getUserId(), userId))
        .forEach(u -> revokeToken(u.getToken()));
  }

  public void revokeAll() {
    online().forEach(u -> revokeToken(u.getToken()));
  }

  public void revokeToken(String tokenId) {
    if (tokenId != null && tokenId.matches("[A-Za-z0-9-]{1,64}"))
      redis.deleteObject(CacheConstants.LOGIN_TOKEN_KEY + tokenId);
  }
}
