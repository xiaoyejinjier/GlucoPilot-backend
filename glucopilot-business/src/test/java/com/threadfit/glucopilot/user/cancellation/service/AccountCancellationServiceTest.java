package com.threadfit.glucopilot.user.cancellation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.threadfit.glucopilot.auth.dto.AuthDtos;
import com.threadfit.glucopilot.auth.service.AuthService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.enums.CodeEnum.AccountStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationMode;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationStatus;
import com.threadfit.glucopilot.common.enums.CodeEnum.CancellationVerifyChannel;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.user.cancellation.service.impl.AccountCancellationServiceImpl;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeRequest;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SendCodeView;
import com.threadfit.glucopilot.user.dto.CancellationDtos.SubmitRequest;
import com.threadfit.glucopilot.user.entity.UserAccount;
import com.threadfit.glucopilot.user.entity.UserAccountCancellation;
import com.threadfit.glucopilot.user.mapper.UserAccountCancellationMapper;
import com.threadfit.glucopilot.user.mapper.UserAccountMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

/** 注销提交与验证码分支（恢复逻辑已迁至 AccountCancellationRecovery，见其单测）。 */
@ExtendWith(MockitoExtension.class)
class AccountCancellationServiceTest {

    @Mock private UserAccountMapper userAccountMapper;
    @Mock private UserAccountCancellationMapper cancellationMapper;
    @Mock private AuthService authService;
    @Mock private CaptchaService captchaService;
    @Mock private WalletCheckService walletCheckService;

    @InjectMocks private AccountCancellationServiceImpl service;

    @BeforeEach
    void stubWalletCheck() {
        lenient().when(walletCheckService.check(anyLong())).thenReturn(WalletCheckService.WalletCheckResult.ok());
    }

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // 纯单测没有 MyBatis 容器，LambdaQueryWrapper 解析列名要先注册 TableInfo
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserAccountCancellation.class);
    }

    @Test
    void submitRejectsImmediateMode() {
        SubmitRequest request = submit(CancellationMode.IMMEDIATE.getCode());

        BizException error = assertThrows(BizException.class, () -> service.submit(1L, request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        verify(cancellationMapper, never()).insert(any(UserAccountCancellation.class));
        verify(authService, never()).revokeAllSessions(any());
    }

    @Test
    void submitIsRejectedForAlreadyCancelledAccount() {
        when(userAccountMapper.selectById(1L))
                .thenReturn(account(1L, AccountStatus.CANCELLED.getCode()));
        when(cancellationMapper.selectList(any())).thenReturn(List.of());

        BizException error =
                assertThrows(
                        BizException.class,
                        () -> service.submit(1L, submit(CancellationMode.ISOLATE_7D.getCode())));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(authService, never()).revokeAllSessions(any());
    }

    @Test
    void submitWritesSevenDayDeadlineAndBlocksLogin() {
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
        assertEquals(
                CancellationVerifyChannel.SMS.getCode(), inserted.getValue().getVerifyChannel());
        assertEquals(AccountStatus.NORMAL.getCode(), inserted.getValue().getPreStatus());
        assertNotNull(inserted.getValue().getIsolateUntil());
        assertTrue(
                inserted.getValue().getIsolateUntil().isAfter(before)
                        && inserted.getValue().getIsolateUntil().isBefore(after));
        // scene 精确匹配：注销只能消费 cancel 场景验证码
        verify(authService).consumeSmsCode("13812345678", "123456", "cancel");
        // 钱包校验占位被调用
        verify(walletCheckService).check(1L);

        // 提交即置可恢复态：登录仍可用（窗口内），但受门禁限制
        assertEquals(AccountStatus.CANCELLED_RECOVERABLE.getCode(), account.getAccountStatus());
        verify(authService).revokeAllSessions(1L);
    }

    @Test
    void submitAcceptsPureWechatAccountAndRecordsWechatChannel() {
        // 纯微信账号（未绑手机号/邮箱）：wx.login code 现场验证，不再强制先绑定
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        account.setOpenid("wx-openid-1");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of());
        SubmitRequest request = submit(CancellationMode.ISOLATE_7D.getCode());
        request.setVerifyType(CancellationVerifyChannel.WECHAT.getCode());
        request.setVerifyCode(null);
        request.setWechatCode("wx-code-1");

        service.submit(1L, request);

        verify(authService).assertWechatIdentity("wx-code-1", "wx-openid-1");
        ArgumentCaptor<UserAccountCancellation> inserted =
                ArgumentCaptor.forClass(UserAccountCancellation.class);
        verify(cancellationMapper).insert(inserted.capture());
        assertEquals(
                CancellationVerifyChannel.WECHAT.getCode(), inserted.getValue().getVerifyChannel());
        assertEquals(AccountStatus.NORMAL.getCode(), inserted.getValue().getPreStatus());
        verify(authService, never()).consumeSmsCode(any(), any(), any());
        verify(authService, never()).consumeEmailCode(any(), any(), any());
    }

    @Test
    void submitIsRejectedWithin24HoursAfterRecovery() {
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        UserAccountCancellation recovered = new UserAccountCancellation();
        recovered.setId(9L);
        recovered.setUserId(1L);
        recovered.setMode(CancellationMode.ISOLATE_7D.getCode());
        recovered.setStatus(CancellationStatus.RECOVERED.getCode());
        recovered.setRecoveredAt(LocalDateTime.now().minusHours(23));
        // 第一次 selectList = findActive（无处理中申请）；第二次 = 最近一次已恢复记录
        when(cancellationMapper.selectList(any()))
                .thenReturn(List.of())
                .thenReturn(List.of(recovered));

        BizException error =
                assertThrows(
                        BizException.class,
                        () -> service.submit(1L, submit(CancellationMode.ISOLATE_7D.getCode())));

        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        verify(cancellationMapper, never()).insert(any(UserAccountCancellation.class));
        verify(authService, never()).revokeAllSessions(any());
    }

    @Test
    void submitIsIdempotentWhenAnActiveRecordAlreadyExists() {
        UserAccount account = account(1L, AccountStatus.CANCELLED_RECOVERABLE.getCode());
        UserAccountCancellation active = new UserAccountCancellation();
        active.setId(7L);
        active.setMode(CancellationMode.ISOLATE_7D.getCode());
        active.setStatus(CancellationStatus.PENDING.getCode());
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of(active));

        var view = service.submit(1L, submit(CancellationMode.ISOLATE_7D.getCode()));

        assertEquals(AccountStatus.CANCELLED_RECOVERABLE.getCode(), view.getAccountStatus());
        // 幂等：不重复写凭据、不重复校验
        verify(cancellationMapper, never()).insert(any(UserAccountCancellation.class));
        verify(authService, never()).consumeSmsCode(any(), any(), any());
    }

    @Test
    void submitIsIdempotentForLegacyCancellingAccount() {
        UserAccount account = account(1L, AccountStatus.CANCELLING.getCode());
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of());

        var view = service.submit(1L, submit(CancellationMode.ISOLATE_7D.getCode()));

        assertEquals(AccountStatus.CANCELLING.getCode(), view.getAccountStatus());
        verify(cancellationMapper, never()).insert(any(UserAccountCancellation.class));
    }

    @Test
    void sendCodeRejectsUnboundChannelBeforeConsumingTicket() {
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        SendCodeRequest request = new SendCodeRequest();
        request.setChannel(CancellationVerifyChannel.SMS.getCode());
        request.setCaptchaTicket("ticket-1");

        BizException error = assertThrows(BizException.class, () -> service.sendCode(1L, request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        verify(captchaService, never()).consume(any(), any());
        verify(authService, never()).sendSmsCode(any());
    }

    @Test
    void sendCodeConsumesTicketThenSendsCancelSceneCode() {
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        SendCodeRequest request = new SendCodeRequest();
        request.setChannel(CancellationVerifyChannel.SMS.getCode());
        request.setCaptchaTicket("ticket-1");

        SendCodeView view = service.sendCode(1L, request);

        verify(captchaService).consume("ticket-1", 1L);
        verify(captchaService).guardSendCode(any(), any());
        ArgumentCaptor<AuthDtos.SmsCodeRequest> captor =
                ArgumentCaptor.forClass(AuthDtos.SmsCodeRequest.class);
        verify(authService).sendSmsCode(captor.capture());
        assertEquals("13812345678", captor.getValue().getPhone());
        assertEquals("cancel", captor.getValue().getScene());
        assertEquals(CancellationVerifyChannel.SMS.getCode(), view.getChannel());
        assertNotNull(view.getMaskedTarget());
        assertEquals(60, view.getCooldownSeconds());
    }

    @Test
    void submitRejectsVerifyTypeThatDoesNotMatchBinding() {
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of());
        SubmitRequest request = submit(CancellationMode.ISOLATE_7D.getCode());
        request.setVerifyType(CancellationVerifyChannel.EMAIL.getCode());

        BizException error = assertThrows(BizException.class, () -> service.submit(1L, request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        verify(authService, never()).consumeEmailCode(any(), any(), any());
        verify(cancellationMapper, never()).insert(any(UserAccountCancellation.class));
    }

    @Test
    void placeholderOpenidIsNotTreatedAsWechatBinding() {
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        account.setOpenid("phone_8223c208c61443ef");
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of());

        var view = service.getStatus(1L);

        assertTrue(view.getHasPhone());
        assertFalse(view.getHasWechat());
    }

    @Test
    void submitRejectsWechatVerifyTypeForPlaceholderOpenid() {
        UserAccount account = account(1L, AccountStatus.NORMAL.getCode());
        account.setOpenid("phone_8223c208c61443ef");
        account.setPhone("13812345678");
        when(userAccountMapper.selectById(1L)).thenReturn(account);
        when(cancellationMapper.selectList(any())).thenReturn(List.of());
        SubmitRequest request = submit(CancellationMode.ISOLATE_7D.getCode());
        request.setVerifyType(CancellationVerifyChannel.WECHAT.getCode());
        request.setWechatCode("wx-code-1");

        BizException error = assertThrows(BizException.class, () -> service.submit(1L, request));

        assertEquals(ErrorCode.BAD_REQUEST, error.getErrorCode());
        verify(authService, never()).assertWechatIdentity(any(), any());
        verify(cancellationMapper, never()).insert(any(UserAccountCancellation.class));
    }

    private static UserAccount account(long id, int status) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setAccountStatus(status);
        return account;
    }

    private static SubmitRequest submit(int mode) {
        SubmitRequest request = new SubmitRequest();
        request.setMode(mode);
        request.setVerifyType(CancellationVerifyChannel.SMS.getCode());
        request.setVerifyCode("123456");
        return request;
    }
}
