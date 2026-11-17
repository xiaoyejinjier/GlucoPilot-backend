package com.threadfit.glucopilot.user.cancellation.job;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 部署形态自检：未显式声明、显式多实例、worker 关闭三种边界。 */
class AccountCancellationInstanceGuardTest {

    @Test
    void undeclaredWarnsBecauseOnlyDefaultValueExists() {
        // application.yaml 里 single-instance 带默认值，环境变量未设置时必须判为"未声明"
        MockEnvironment environment = new MockEnvironment();

        assertEquals(
                AccountCancellationInstanceGuard.DeploymentState.UNDECLARED,
                new AccountCancellationInstanceGuard(environment).check());
    }

    @Test
    void explicitSingleInstancePasses() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("ACCOUNT_CANCELLATION_SINGLE_INSTANCE", "true");

        assertEquals(
                AccountCancellationInstanceGuard.DeploymentState.DECLARED_SINGLE,
                new AccountCancellationInstanceGuard(environment).check());
    }

    @Test
    void explicitMultiInstanceIsReported() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("account.cancellation.single-instance", "false");

        assertEquals(
                AccountCancellationInstanceGuard.DeploymentState.MULTI_INSTANCE,
                new AccountCancellationInstanceGuard(environment).check());
    }

    @Test
    void disabledWorkerSkipsCheck() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("account.cancellation.enabled", "false");

        assertEquals(
                AccountCancellationInstanceGuard.DeploymentState.DISABLED,
                new AccountCancellationInstanceGuard(environment).check());
    }

    @Test
    void blankValueIsTreatedAsMultiInstance() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("ACCOUNT_CANCELLATION_SINGLE_INSTANCE", " ");

        // 空串不等于 true：仍按多实例处理，避免"声明了但值无效"被静默放过
        assertEquals(
                AccountCancellationInstanceGuard.DeploymentState.MULTI_INSTANCE,
                new AccountCancellationInstanceGuard(environment).check());
    }
}
