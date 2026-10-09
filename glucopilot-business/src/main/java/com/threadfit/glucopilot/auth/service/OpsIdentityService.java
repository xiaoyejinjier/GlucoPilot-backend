package com.threadfit.glucopilot.auth.service;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Explicit bridge between historical business audit IDs and upstream login identities. */
@Service
@RequiredArgsConstructor
public class OpsIdentityService {
  private final JdbcTemplate jdbc;

  public Long userIdOf(Long opsId) {
    return find("ruoyi_user_id", "legacy_ops_id", opsId);
  }

  public Long opsIdOf(Long userId) {
    return find("legacy_ops_id", "ruoyi_user_id", userId);
  }

  public void bind(Long opsId, Long userId) {
    jdbc.update(
        "INSERT INTO gp_ops_identity_map(legacy_ops_id,ruoyi_user_id) VALUES (?,?)", opsId, userId);
  }

  private Long find(String result, String key, Long value) {
    if (value == null) throw new BizException(ErrorCode.UNAUTHORIZED);
    var rows =
        jdbc.queryForList(
            "SELECT " + result + " FROM gp_ops_identity_map WHERE " + key + " = ?",
            Long.class,
            value);
    if (rows.isEmpty()) throw new BizException(ErrorCode.UNAUTHORIZED, "运营身份未迁移");
    return rows.getFirst();
  }
}
