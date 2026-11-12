package com.threadfit.glucopilot.user.cancellation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationMode;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

/** 登录即恢复的分支：窗口内恢复、并发 no-op、窗口过期、终态裁判、禁用态还原。 */
@ExtendWith(MockitoExtension.class)
class AccountCancellationRecoveryTest {

    @Mock private UserAccountCancellationMapper cancellationMapper;
    @Mock private UserAccountMapper userAccountMapper;

    @InjectMocks private AccountCancellationRecovery recovery;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserAccountCancellation.class);
    }

    @Test
    void recoversWithinWindowAndRestoresPreviousStatus() {
        UserAccount account = account(AccountStatus.CANCELLED_RECOVERABLE);
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any()))
                .thenReturn(List.of(record(LocalDateTime.now().plusDays(3),
                        AccountStatus.NORMAL.getCode())));
        when(cancellationMapper.markRecovered(anyLong(), any(LocalDateTime.class))).thenReturn(1);

        AccountCancellationRecovery.RecoverOutcome outcome = recovery.recoverOnLogin(1L);

        assertTrue(outcome.recovered());
        assertEquals(AccountStatus.NORMAL.getCode(), outcome.account().getAccountStatus());
        verify(userAccountMapper).updateById(account);
    }

    @Test
    void restoresDisabledStatusInsteadOfWashingItClean() {
        UserAccount account = account(AccountStatus.CANCELLED_RECOVERABLE);
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any()))
                .thenReturn(List.of(record(LocalDateTime.now().plusDays(3),
                        AccountStatus.DISABLED.getCode())));
        when(cancellationMapper.markRecovered(anyLong(), any(LocalDateTime.class))).thenReturn(1);

        AccountCancellationRecovery.RecoverOutcome outcome = recovery.recoverOnLogin(1L);

        assertTrue(outcome.recovered());
        assertEquals(AccountStatus.DISABLED.getCode(), outcome.account().getAccountStatus());
    }

    @Test
    void concurrentSecondLoginIsNoopAndNotReportedAsRecovered() {
        // 锁内重读发现已被先到者恢复成 NORMAL：不得再判定恢复，也不得弹"账号已恢复"
        when(userAccountMapper.selectById(1L)).thenReturn(account(AccountStatus.NORMAL));

        AccountCancellationRecovery.RecoverOutcome outcome = recovery.recoverOnLogin(1L);

        assertFalse(outcome.recovered());
        verify(cancellationMapper, never()).markRecovered(anyLong(), any(LocalDateTime.class));
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
    }

    @Test
    void rejectsRecoveryAfterWindowCloses() {
        when(userAccountMapper.selectById(1L))
                .thenReturn(account(AccountStatus.CANCELLED_RECOVERABLE));
        when(cancellationMapper.selectList(any()))
                .thenReturn(List.of(record(LocalDateTime.now().minusMinutes(1),
                        AccountStatus.NORMAL.getCode())));

        BizException error =
                assertThrows(BizException.class, () -> recovery.recoverOnLogin(1L));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(cancellationMapper, never()).markRecovered(anyLong(), any(LocalDateTime.class));
    }

    @Test
    void rejectsRecoveryWhenRecordNoLongerPending() {
        when(userAccountMapper.selectById(1L))
                .thenReturn(account(AccountStatus.CANCELLED_RECOVERABLE));
        when(cancellationMapper.selectList(any()))
                .thenReturn(List.of(record(LocalDateTime.now().plusDays(3),
                        AccountStatus.NORMAL.getCode())));
        when(cancellationMapper.markRecovered(anyLong(), any(LocalDateTime.class))).thenReturn(0);

        BizException error =
                assertThrows(BizException.class, () -> recovery.recoverOnLogin(1L));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
    }

    private static UserAccount account(AccountStatus status) {
        UserAccount account = new UserAccount();
        account.setId(1L);
        account.setAccountStatus(status.getCode());
        return account;
    }

    private static UserAccountCancellation record(LocalDateTime isolateUntil, int preStatus) {
        UserAccountCancellation record = new UserAccountCancellation();
        record.setId(7L);
        record.setUserId(1L);
        record.setMode(CancellationMode.ISOLATE_7D.getCode());
        record.setStatus(CancellationStatus.PENDING.getCode());
        record.setPreStatus(preStatus);
        record.setIsolateUntil(isolateUntil);
        return record;
    }
}
