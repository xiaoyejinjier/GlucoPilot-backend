package com.threadfit.glucopilot.auth.service;

import com.threadfit.glucopilot.common.context.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * RuoYi 权限表达式服务。控制器以 @PreAuthorize("@ss.hasPermi('x:y:z')") 使用。
 */
@org.springframework.context.annotation.Profile("upstream-standalone")
@Service("ss")
@RequiredArgsConstructor
public class RuoYiPermissionService {

    private final RuoYiAuthorizationService authorizationService;

    public boolean hasPermi(String permission) {
        return authorizationService.hasPermission(RequestContext.getOpsId(), permission);
    }

    public boolean lacksPermi(String permission) {
        return !hasPermi(permission);
    }

    public boolean hasAnyPermi(String permissions) {
        if (!StringUtils.hasText(permissions)) {
            return false;
        }
        for (String permission : permissions.split(",")) {
            if (hasPermi(permission)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasRole(String role) {
        Long opsId = RequestContext.getOpsId();
        return opsId != null && StringUtils.hasText(role)
                && authorizationService.roleCodesOf(opsId).contains(role.trim());
    }
}
