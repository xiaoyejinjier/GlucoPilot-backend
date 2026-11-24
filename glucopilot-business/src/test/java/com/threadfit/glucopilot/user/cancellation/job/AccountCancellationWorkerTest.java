package com.threadfit.glucopilot.user.cancellation.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.user.cancellation.service.AccountDataPurger;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import com.threadfit.glucopilot.user.mapper.UserCancelLogMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 注销 worker 的关键契约：<b>账号状态守卫</b>、<b>成功才记 EXECUTED</b>、
 * <b>失败只回到 PENDING / 满 3 次置 FAILED，绝不回退成 NORMAL</b>。
 */
@ExtendWith(MockitoExtension.class)
class AccountCancellationWorkerTest {

    @Mock private UserAccountCancellationMapper cancellationMapper;
    @Mock private UserAccountMapper userAccountMapper;
    @Mock private UserCancelLogMapper userCancelLogMapper;
    @Mock private AccountDataPurger purger;

    @InjectMocks private AccountCancellationWorker worker;

    @BeforeAll
    static void initTableInfo() {
        // 纯单测没有 MyBatis 容器，poll() 里构造 LambdaQueryWrapper 需要先注册 TableInfo
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserAccountCancellation.class);
    }

    @BeforeEach
    void injectSafetyMargin() {
        // @Value 注入在纯 Mockito 下不生效，显式预置默认值
        ReflectionTestUtils.setField(worker, "safetyMargin", Duration.ofMinutes(5));
    }

    @Test
    void marksExecutedOnlyAfterPurgeSucceeds() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(userAccountMapper.selectById(9L)).thenReturn(account(9L, AccountStatus.CANCELLED_RECOVERABLE));

        worker.process(record);

        verify(purger).purge(9L);
        verify(cancellationMapper).markExecuted(eq(1L), any(LocalDateTime.class));
        verify(cancellationMapper, never()).markFailed(anyLong(), anyInt(), anyString());
    }

    @Test
    void failureBeforeLimitGoesBackToPendingForRetry() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(userAccountMapper.selectById(9L)).thenReturn(account(9L, AccountStatus.CANCELLED_RECOVERABLE));
        doThrow(new IllegalStateException("对象存储超时")).when(purger).purge(9L);

        worker.process(record);

        verify(cancellationMapper)
                .markFailed(eq(1L), eq(CancellationStatus.PENDING.getCode()), anyString());
        verify(cancellationMapper, never()).markExecuted(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void thirdFailureBecomesFailedAndNeverRevertsToNormal() {
        UserAccountCancellation record = record(1L, 9L, 2);
        when(userAccountMapper.selectById(9L)).thenReturn(account(9L, AccountStatus.CANCELLED_RECOVERABLE));
        doThrow(new IllegalStateException("数据库只读")).when(purger).purge(9L);

        worker.process(record);

        verify(cancellationMapper)
                .markFailed(eq(1L), eq(CancellationStatus.FAILED.getCode()), anyString());
    }

    @Test
    void skipsPurgeWhenAccountAlreadyRecovered() {
        // 双 PENDING：残留那条到期时账号已被登录恢复成 NORMAL，绝不能清理
        UserAccountCancellation record = record(1L, 9L, 0);
        when(userAccountMapper.selectById(9L)).thenReturn(account(9L, AccountStatus.NORMAL));

        worker.process(record);

        verify(purger, never()).purge(anyLong());
        verify(cancellationMapper).markExecuted(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void skipsPurgeWhenAccountDisabled() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(userAccountMapper.selectById(9L)).thenReturn(account(9L, AccountStatus.DISABLED));

        worker.process(record);

        verifyNoInteractions(purger);
        verify(cancellationMapper).markExecuted(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void marksExecutedWithoutPurgeWhenAccountAlreadyTombstoned() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(userAccountMapper.selectById(9L)).thenReturn(account(9L, AccountStatus.CANCELLED));

        worker.process(record);

        verifyNoInteractions(purger);
        verify(cancellationMapper).markExecuted(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void marksExecutedWithoutPurgeWhenAccountMissing() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(userAccountMapper.selectById(9L)).thenReturn(null);

        worker.process(record);

        verifyNoInteractions(purger);
        verify(cancellationMapper).markExecuted(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void legacyCancellingAccountIsStillPurged() {
        // 历史立即模式行账号态是 CANCELLING(2)，守卫白名单必须包含 2
        UserAccountCancellation record = record(1L, 9L, 0);
        when(userAccountMapper.selectById(9L)).thenReturn(account(9L, AccountStatus.CANCELLING));

        worker.process(record);

        verify(purger).purge(9L);
    }

    @Test
    void scheduledPollIsSilentWhenNothingIsDue() {
        when(cancellationMapper.selectList(any())).thenReturn(java.util.List.of());

        worker.poll();

        verifyNoInteractions(purger);
    }

    private static UserAccountCancellation record(long id, long userId, int failCount) {
        UserAccountCancellation record = new UserAccountCancellation();
        record.setId(id);
        record.setUserId(userId);
        record.setStatus(CancellationStatus.PENDING.getCode());
        record.setFailCount(failCount);
        return record;
    }

    private static UserAccount account(long id, AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setAccountStatus(status.getCode());
        return account;
    }
}
