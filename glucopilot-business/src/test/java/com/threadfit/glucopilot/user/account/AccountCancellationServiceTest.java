package com.threadfit.glucopilot.user.account;

import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationMode;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationVerifyChannel;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.dto.CancellationDtos.RecoveryRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 注销状态机与 CAS 的关键分支（其余分支由接口层校验覆盖）。 */
@ExtendWith(MockitoExtension.class)
class AccountCancellationServiceTest {

    @Mock private UserAccountMapper userAccountMapper;
    @Mock private UserAccountCancellationMapper cancellationMapper;
    @Mock private AuthService authService;

    @InjectMocks private AccountCancellationServiceImpl service;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // 纯单测没有 MyBatis 容器，LambdaQueryWrapper 解析列名要先注册 TableInfo
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserAccountCancellation.class);
    }

    @Test
    void submitIsRejectedForAlreadyCancelledAccount() {
        when(userAccountMapper.selectById(1L)).thenReturn(account(1L, AccountStatus.CANCELLED.getCode()));
        when(cancellationMapper.selectList(any())).thenReturn(List.of());

        BizException error = assertThrows(BizException.class,
                () -> service.submit(1L, submit(CancellationMode.IMMEDIATE.getCode())));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(authService, never()).revokeAllSessions(any());
    }

    @Test
    void submitWithIsolateModeImmediatelyBlocksLoginAndWritesSevenDayDeadline() {
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        // findActive → 空；rejectIfRecentlyRecovered → 空
        when(cancellationMapper.selectList(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now().plusDays(6);
        service.submit(1L, submit(CancellationMode.ISOLATE_7D.getCode()));
        LocalDateTime after = LocalDateTime.now().plusDays(8);

        ArgumentCaptor<UserAccountCancellation> inserted =
                ArgumentCaptor.forClass(UserAccountCancellation.class);
        verify(cancellationMapper).insert(inserted.capture());
        assertEquals(CancellationMode.ISOLATE_7D.getCode(), inserted.getValue().getMode());
        assertEquals(CancellationStatus.PENDING.getCode(), inserted.getValue().getStatus());
        assertEquals(CancellationVerifyChannel.SMS.getCode(), inserted.getValue().getVerifyChannel());
        assertNotNull(inserted.getValue().getIsolateUntil());
        assertTrue(inserted.getValue().getIsolateUntil().isAfter(before)
                && inserted.getValue().getIsolateUntil().isBefore(after));

        // 提交即置可恢复态：登录仍可用，但会被门禁限制在白名单
        assertEquals(AccountStatus.CANCELLED_RECOVERABLE.getCode(), account.getAccountStatus());
        verify(authService).revokeAllSessions(1L);
    }

    @Test
    void submitIsIdempotentWhenAnActiveRecordAlreadyExists() {
        UserAccount account = account(1L, AccountStatus.CANCELLING.getCode());
        UserAccountCancellation active = new UserAccountCancellation();
        active.setId(7L);
        active.setMode(CancellationMode.IMMEDIATE.getCode());
        active.setStatus(CancellationStatus.PENDING.getCode());
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of(active));

        var view = service.submit(1L, submit(CancellationMode.IMMEDIATE.getCode()));

        assertEquals(AccountStatus.CANCELLING.getCode(), view.getAccountStatus());
        // 幂等：不重复写凭据、不重复发验证码
        verify(cancellationMapper, never()).insert(any(UserAccountCancellation.class));
        verify(authService, never()).consumeSmsCode(any(), any());
    }

    @Test
    void recoverFailsWhenWorkerAlreadyClaimedTheRecord() {
        UserAccount account = account(1L, AccountStatus.CANCELLED_RECOVERABLE.getCode());
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of(
                recoverableRecord(LocalDateTime.now().plusDays(3))));
        when(cancellationMapper.markRecovered(7L)).thenReturn(0);

        BizException error = assertThrows(BizException.class, () -> service.recover(1L, recover()));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(userAccountMapper, never()).updateById(any(UserAccount.class));
        // 账号状态必须保持可恢复态：不能"用户以为恢复了、数据却在被删"
        assertEquals(AccountStatus.CANCELLED_RECOVERABLE.getCode(), account.getAccountStatus());
    }

    @Test
    void recoverRestoresNormalOnlyWhenCasWins() {
        UserAccount account = account(1L, AccountStatus.CANCELLED_RECOVERABLE.getCode());
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of(
                recoverableRecord(LocalDateTime.now().plusDays(3))));
        when(cancellationMapper.markRecovered(7L)).thenReturn(1);

        var view = service.recover(1L, recover());

        assertEquals(AccountStatus.NORMAL.getCode(), view.getAccountStatus());
        verify(userAccountMapper).updateById(account);
    }

    @Test
    void recoverIsRejectedAfterTheIsolateWindowCloses() {
        UserAccount account = account(1L, AccountStatus.CANCELLED_RECOVERABLE.getCode());
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of(
                recoverableRecord(LocalDateTime.now().minusMinutes(1))));

        BizException error = assertThrows(BizException.class, () -> service.recover(1L, recover()));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(cancellationMapper, never()).markRecovered(any());
    }

    private static UserAccount account(long id, int status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setAccountStatus(status);
        return account;
    }

    private static UserAccountCancellation recoverableRecord(LocalDateTime isolateUntil) {
        UserAccountCancellation record = new UserAccountCancellation();
        record.setId(7L);
        record.setUserId(1L);
        record.setMode(CancellationMode.ISOLATE_7D.getCode());
        record.setStatus(CancellationStatus.PENDING.getCode());
        record.setVerifyChannel(CancellationVerifyChannel.SMS.getCode());
        record.setIsolateUntil(isolateUntil);
        return record;
    }

    private static SubmitRequest submit(int mode) {
        SubmitRequest request = new SubmitRequest();
        request.setMode(mode);
        request.setVerifyCode("123456");
        return request;
    }

    private static RecoveryRequest recover() {
        RecoveryRequest request = new RecoveryRequest();
        request.setVerifyCode("123456");
        return request;
    }
}
