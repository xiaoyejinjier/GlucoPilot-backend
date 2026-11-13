package com.threadfit.glucopilot.user.cancellation.job;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 注销 worker 的部署形态自检。
 *
 * <h2>为什么需要它</h2>
 *
 * {@link AccountCancellationWorker} 靠"时间窗隔离 + 幂等重试"保证并发安全，<b>没有分布式锁</b>：
 * 多实例同时轮询会对同一批凭据重复执行清理。这个约束只写在文档里迟早会被忘记，因此在启动时把
 * "是否显式声明单实例"变成一个可观测信号。
 *
 * <h2>为什么不全直接 fail 启动</h2>
 *
 * 存量部署的 .env 里没有这个变量，硬失败会让升级即不可用；而"未声明"本身不等于"多实例"。
 * 因此：未显式声明 → WARN（推动部署方补齐声明）；显式声明非 true → ERROR（这是真正的误配）。
 */
@Component
@RequiredArgsConstructor
public class AccountCancellationInstanceGuard {

    private static final Logger log = LoggerFactory.getLogger(AccountCancellationInstanceGuard.class);

    private static final String ENABLED_KEY = "account.cancellation.enabled";
    private static final String SINGLE_INSTANCE_KEY = "account.cancellation.single-instance";
    private static final String SINGLE_INSTANCE_ENV = "ACCOUNT_CANCELLATION_SINGLE_INSTANCE";

    private final Environment environment;

    /** 部署形态判定结果（单独暴露便于单测，不需要抓日志）。 */
    public enum DeploymentState {
        /** worker 已关闭，不参与判定。 */
        DISABLED,
        /** 显式声明了单实例。 */
        DECLARED_SINGLE,
        /** 未显式声明（配置里只有默认值）。 */
        UNDECLARED,
        /** 显式声明为多实例，但 worker 没有分布式锁。 */
        MULTI_INSTANCE
    }

    @PostConstruct
    public void checkDeployment() {
        switch (check()) {
            case UNDECLARED ->
                    log.warn(
                            "未显式声明 {}=true：注销 worker 没有分布式锁，仅支持单实例部署，"
                                    + "多实例会重复执行清理。请确认实例数为 1 并在部署环境显式声明该变量。",
                            SINGLE_INSTANCE_ENV);
            case MULTI_INSTANCE ->
                    log.error(
                            "{}=false，但注销 worker 没有分布式锁：多实例会重复执行 purge。"
                                    + "请将实例数收敛为 1、设置 ACCOUNT_CANCELLATION_ENABLED=false，"
                                    + "或先引入分布式锁（ShedLock/Redis/DB 租约）。",
                            SINGLE_INSTANCE_KEY);
            default -> {
                // 已声明单实例或 worker 关闭：无需提示
            }
        }
    }

    public DeploymentState check() {
        if (!Boolean.parseBoolean(environment.getProperty(ENABLED_KEY, "true").trim())) {
            return DeploymentState.DISABLED;
        }
        // 直接读环境变量名：配置文件里该项带默认值，无法据此区分"没写"与"写了 true"。
        // 显式设置（含空串）时以环境变量为准，避免 `${VAR:true}` 把空值解析成 true。
        String declaredByEnv = environment.getProperty(SINGLE_INSTANCE_ENV);
        if (declaredByEnv != null) {
            return "true".equalsIgnoreCase(declaredByEnv.trim())
                    ? DeploymentState.DECLARED_SINGLE
                    : DeploymentState.MULTI_INSTANCE;
        }
        // 环境变量未设置时，再看配置文件里是否被显式改成了非 true
        String configured = environment.getProperty(SINGLE_INSTANCE_KEY, "true");
        return "true".equalsIgnoreCase(configured.trim())
                ? DeploymentState.UNDECLARED
                : DeploymentState.MULTI_INSTANCE;
    }
}
