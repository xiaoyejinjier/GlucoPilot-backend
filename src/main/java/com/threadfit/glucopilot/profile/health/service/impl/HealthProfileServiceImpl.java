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

    /**
     * 健康档案（user_health）服务实现。
     * 单行表、一用户一份活跃档案（uk_user_active(user_id, is_deleted) 保证）。
     * 保存语义为"全量提交"：入参 null 表示"清空该项恢复未填写"，而非忽略；
     * 因此采用 entity（承载有值字段，触发审计填充）+ wrapper.set(field, null)
     * （承载置空字段）的双写方案，合一次 update 防旧值残留。
     * diabetesTypeText 服务端派生、avatar/脱敏等由 user 侧聚合负责，本模块不依赖 user 实现。
     */

    /** 糖尿病类型文案，下标即 CodeEnum.DiabetesType 的枚举码；与前端 utils/mappers.js 对齐。 */
    private static final String[] DIABETES_TYPE_TEXT = {
            "未填写", "1 型糖尿病", "2 型糖尿病", "妊娠糖尿病", "其他"
    };

    private final UserHealthMapper userHealthMapper;

    @Override
    public HealthView healthViewOf(Long userId) {
        return toHealthView(requireHealth(userId));
    }

    /**
     * 全量保存健康档案（幂等覆盖，非补丁式增量）。
     * 校验规则：
     * - diagnosedYm 需匹配 YYYY-MM（由 DTO @Pattern 前置校验），且不得晚于当前月，否则抛 40001(BizException)；
     * - diabetesType 入参范围 [0,4]（@Min/@Max），展示文案 diabetesTypeText 由服务端按
     *   diabetesType(0未填/1一型/2二型/3妊娠/4其他) 派生，忽略客户端直接传入的文本，保证二者自洽；
     * - targetFasting / targetPostprandial 限 1.0~33.3 mmol/L（DTO @DecimalMin/@DecimalMax）。
     * null 语义：字段为 null/空白表示"清空该项恢复未填写"。
     * @param userId 当前登录用户 id（必填）
     * @param request 全量提交的档案字段
     * @return 保存后的健康档案视图
     */
    @Override
    @Transactional
    public HealthView saveHealth(Long userId, HealthSaveRequest request) {
        UserHealth existing = requireHealth(userId);

        // 确诊年月晚于当前月即拒绝（防误填未来日期），空值不校验
        if (request.getDiagnosedYm() != null
                && YearMonth.parse(request.getDiagnosedYm()).isAfter(YearMonth.now())) {
            throw new BizException(ErrorCode.BIZ_REJECTED, "确诊年月不能晚于当前月");
        }

        // 全量保存：有值字段非 null 走 entity（触发 AuditMetaObjectHandler 填充 gmt_modified/updated_by），
        // 置空字段 null/空白 走 wrapper.set(该列, NULL)；二者合并成同一条 update，
        // 避免只写 entity 时 MyBatis-Plus 默认跳过 null 列导致旧值残留。
        UserHealth entity = new UserHealth();
        entity.setId(existing.getId());
        LambdaUpdateWrapper<UserHealth> wrapper = new LambdaUpdateWrapper<UserHealth>()
                .eq(UserHealth::getId, existing.getId());

        // 未传糖尿病类型按"未填写(0)"处理
        int diabetesType = request.getDiabetesType() == null ? 0 : request.getDiabetesType();
        entity.setDiabetesType(diabetesType);
        // 展示文案由服务端按编号派生写入，不采用请求里的自由文本，保证与 diabetes_type 一致
        entity.setDiabetesTypeText(diabetesTypeTextOf(diabetesType));

        // 空白字符串归一化为 null，命中 null 即视为"清空"走 wrapper 置空
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

    /** 读取当前用户健康档案行；行缺失时兜底插入一条"未填写(0)"的新行并返回，
     *  避免历史数据导致空指针 500；与 user 侧 requireProfile/requireAccount 行为一致。 */
    private UserHealth requireHealth(Long userId) {
        UserHealth health = userHealthMapper.selectOne(new LambdaQueryWrapper<UserHealth>()
                .eq(UserHealth::getUserId, userId)
                .last("LIMIT 1"));
        if (health != null) {
            return health;
        }
        // 兜底新建：先把该用户的健康档案行落库，后续 update 才有可依据的主键 id
        UserHealth created = new UserHealth();
        created.setUserId(userId);
        created.setDiabetesType(0);
        userHealthMapper.insert(created);
        return created;
    }

    /** 按糖尿病类型编号取展示文案；越界（0~4 之外）防御性返回 null。 */
    private static String diabetesTypeTextOf(int code) {
        return code >= 0 && code < DIABETES_TYPE_TEXT.length ? DIABETES_TYPE_TEXT[code] : null;
    }

    /** 空白串归一化为 null：null 与纯空白均视为"未填写"，与保存的置空语义一致。 */
    private static String nullIfBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 实体 → 前端视图；id 转字符串对外暴露（避免精度/类型暴露内部自增 id）。 */
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
