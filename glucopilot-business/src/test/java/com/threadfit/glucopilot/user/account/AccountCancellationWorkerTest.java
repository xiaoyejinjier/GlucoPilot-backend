package com.threadfit.glucopilot.user.account;

import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 注销 worker 的三条关键契约：<b>抢不到就跳过</b>、<b>成功才记 EXECUTED</b>、
 * <b>失败只回到 PENDING / 满 3 次置 FAILED，绝不回退成 NORMAL</b>。
 */
@ExtendWith(MockitoExtension.class)
class AccountCancellationWorkerTest {

    @Mock
    private UserAccountCancellationMapper cancellationMapper;
    @Mock
    private AccountDataPurger purger;

    @InjectMocks
    private AccountCancellationWorker worker;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // 纯单测没有 MyBatis 容器，poll() 里构造 LambdaQueryWrapper 需要先注册 TableInfo
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserAccountCancellation.class);
    }

    @Test
    void skipsWhenAnotherInstanceAlreadyClaimedTheRecord() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(cancellationMapper.claim(1L)).thenReturn(0);

        worker.process(record);

        verify(purger, never()).purge(anyLong());
        verify(cancellationMapper, never()).markExecuted(anyLong());
    }

    @Test
    void marksExecutedOnlyAfterPurgeSucceeds() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(cancellationMapper.claim(1L)).thenReturn(1);

        worker.process(record);

        verify(purger).purge(9L);
        verify(cancellationMapper).markExecuted(1L);
        verify(cancellationMapper, never()).markFailed(anyLong(), anyInt(), anyString());
    }

    @Test
    void failureBeforeLimitGoesBackToPendingForRetry() {
        UserAccountCancellation record = record(1L, 9L, 0);
        when(cancellationMapper.claim(1L)).thenReturn(1);
        doThrow(new IllegalStateException("对象存储超时")).when(purger).purge(9L);

        worker.process(record);

        verify(cancellationMapper).markFailed(eq(1L), eq(CancellationStatus.PENDING.getCode()), anyString());
        verify(cancellationMapper, never()).markExecuted(anyLong());
    }

    @Test
    void thirdFailureBecomesFailedAndNeverRevertsToNormal() {
        UserAccountCancellation record = record(1L, 9L, 2);
        when(cancellationMapper.claim(1L)).thenReturn(1);
        doThrow(new IllegalStateException("数据库只读")).when(purger).purge(9L);

        worker.process(record);

        // 关键断言：置 FAILED(4) 而不是把账号状态改回 NORMAL —— 半删状态的账号不可"复活"
        verify(cancellationMapper).markFailed(eq(1L), eq(CancellationStatus.FAILED.getCode()), anyString());
    }

    @Test
    void scheduledPollIsSilentWhenNothingIsDue() {
        when(cancellationMapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.List.of());

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
}
