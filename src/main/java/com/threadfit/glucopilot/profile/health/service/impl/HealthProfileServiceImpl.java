package com.threadfit.glucopilot.profile.health.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthSaveRequest;
import com.threadfit.glucopilot.profile.health.dto.HealthDtos.HealthView;
import com.threadfit.glucopilot.profile.health.entity.UserHealth;
import com.threadfit.glucopilot.profile.health.mapper.UserHealthMapper;
import com.threadfit.glucopilot.profile.health.service.HealthProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;

@Service
@RequiredArgsConstructor
public class HealthProfileServiceImpl implements HealthProfileService {

    /** 糖尿病类型文案，下标即 CodeEnum.DiabetesType 的枚举码；与前端 utils/mappers.js 对齐。 */
    private static final String[] DIABETES_TYPE_TEXT = {
            "未填写", "1 型糖尿病", "2 型糖尿病", "妊娠糖尿病", "其他"
    };

    private final UserHealthMapper userHealthMapper;

    @Override
    public HealthView healthViewOf(Long userId) {
        return toHealthView(requireHealth(userId));
    }

    /** 全量保存；null 字段显式置空，避免 updateById 跳过 null 导致旧值残留。 */
    @Override
    @Transactional
    public HealthView saveHealth(Long userId, HealthSaveRequest request) {
        UserHealth existing = requireHealth(userId);

        if (request.getDiagnosedYm() != null
                && YearMonth.parse(request.getDiagnosedYm()).isAfter(YearMonth.now())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "确诊年月不能晚于当前月");
        }

        // 全量保存：非 null 走 entity（触发 AuditMetaObjectHandler 填充 gmt_modified/updated_by），
        // null 走 wrapper.set(=NULL)，二者合一次 update
        UserHealth entity = new UserHealth();
        entity.setId(existing.getId());
        LambdaUpdateWrapper<UserHealth> wrapper = new LambdaUpdateWrapper<UserHealth>()
                .eq(UserHealth::getId, existing.getId());

        int diabetesType = request.getDiabetesType() == null ? 0 : request.getDiabetesType();
        entity.setDiabetesType(diabetesType);
        // 派生写入，不采用请求里的自由文本，保证与 diabetes_type 一致
        entity.setDiabetesTypeText(diabetesTypeTextOf(diabetesType));

        String diagnosedYm = nullIfBlank(request.getDiagnosedYm());
        if (diagnosedYm != null) {
            entity.setDiagnosedYm(diagnosedYm);
        } else {
            wrapper.set(UserHealth::getDiagnosedYm, null);
        }
        if (request.getAge() != null) {
            entity.setAge(request.getAge());
        } else {
            wrapper.set(UserHealth::getAge, null);
        }
        String medication = nullIfBlank(request.getMedication());
        if (medication != null) {
            entity.setMedication(medication);
        } else {
            wrapper.set(UserHealth::getMedication, null);
        }
        String exerciseHabit = nullIfBlank(request.getExerciseHabit());
        if (exerciseHabit != null) {
            entity.setExerciseHabit(exerciseHabit);
        } else {
            wrapper.set(UserHealth::getExerciseHabit, null);
        }
        if (request.getTargetFasting() != null) {
            entity.setTargetFasting(request.getTargetFasting());
        } else {
            wrapper.set(UserHealth::getTargetFasting, null);
        }
        if (request.getTargetPostprandial() != null) {
            entity.setTargetPostprandial(request.getTargetPostprandial());
        } else {
            wrapper.set(UserHealth::getTargetPostprandial, null);
        }

        userHealthMapper.update(entity, wrapper);
        entity.setUserId(existing.getUserId());
        return toHealthView(entity);
    }

    /** 行缺失时兜底新建，避免历史数据导致 500；与 user 侧 requireProfile/requireAccount 行为一致。 */
    private UserHealth requireHealth(Long userId) {
        UserHealth health = userHealthMapper.selectOne(new LambdaQueryWrapper<UserHealth>()
                .eq(UserHealth::getUserId, userId)
                .last("LIMIT 1"));
        if (health != null) {
            return health;
        }
        UserHealth created = new UserHealth();
        created.setUserId(userId);
        created.setDiabetesType(0);
        userHealthMapper.insert(created);
        return created;
    }

    private static String diabetesTypeTextOf(int code) {
        return code >= 0 && code < DIABETES_TYPE_TEXT.length ? DIABETES_TYPE_TEXT[code] : null;
    }

    private static String nullIfBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static HealthView toHealthView(UserHealth h) {
        return HealthView.builder()
                .id(String.valueOf(h.getId()))
                .diabetesType(h.getDiabetesType())
                .diabetesTypeText(h.getDiabetesTypeText())
                .diagnosedYm(h.getDiagnosedYm())
                .age(h.getAge())
                .medication(h.getMedication())
                .exerciseHabit(h.getExerciseHabit())
                .targetFasting(h.getTargetFasting())
                .targetPostprandial(h.getTargetPostprandial())
                .build();
    }
}
