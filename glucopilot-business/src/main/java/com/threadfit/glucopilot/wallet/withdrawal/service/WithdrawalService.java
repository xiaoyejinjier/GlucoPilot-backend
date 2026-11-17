package com.threadfit.glucopilot.wallet.withdrawal.service;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;
import com.threadfit.glucopilot.wallet.withdrawal.dto.WithdrawalDtos.*;
import com.threadfit.glucopilot.wallet.withdrawal.entity.*;
import com.threadfit.glucopilot.wallet.withdrawal.mapper.WithdrawalMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WithdrawalService {
    private final WithdrawalMapper mapper;
    private final SandboxPaymentMapper wallets;
    private final ConsultationMapper consultations;

    public LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    public Summary summary(long user) {
        var doctor = consultations.doctor(user);
        var balance = mapper.balance(user, false);
        boolean eligible = doctor != null && "APPROVED".equals(doctor.getStatus());
        long available =
                balance == null
                        ? 0
                        : Math.min(balance.getBalanceCents(), balance.getIncomeAvailableCents());
        return new Summary(
                eligible,
                true,
                eligible ? available : 0,
                balance == null ? 0 : balance.getFrozenCents(),
                5,
                "配置的节假日/调休优先，未配置按周一至周五");
    }

    @Transactional
    public WithdrawalOrder apply(long user, Apply form) {
        if (form.amountCents() == null
                || form.amountCents() < 1
                || form.amountCents() > 100000000
                || form.clientRequestId() == null
                || !form.clientRequestId().matches("[A-Za-z0-9_-]{8,64}"))
            throw bad("提现金额或请求编号不合法");
        if (wallets.lockActivatedUser(user) == null) throw bad("请先开通钱包");
        var previous = mapper.byRequest(user, form.clientRequestId());
        if (previous != null) {
            if (!previous.getAmountCents().equals(form.amountCents())) throw conflict();
            return previous;
        }
        var doctor = consultations.doctor(user);
        if (doctor == null || !"APPROVED".equals(doctor.getStatus()))
            throw new BizException(ErrorCode.FORBIDDEN, "仅审核通过的医生可申请提现");
        wallets.ensureAccount(user);
        var balance = mapper.balance(user, true);
        long available = Math.min(balance.getBalanceCents(), balance.getIncomeAvailableCents());
        if (form.amountCents() > available) throw bad("可提现问诊收入不足");
        String no = "WD" + UUID.randomUUID().toString().replace("-", "");
        // 先创建包含服务端金额和身份的订单，再冻结资金；任一步失败整体回滚。
        mapper.insert(no, user, form.amountCents(), form.clientRequestId());
        if (mapper.freeze(user, form.amountCents()) != 1) throw conflict();
        mapper.event(no, "FREEZE", form.amountCents(), mapper.balance(user, true), user);
        return mapper.find(no);
    }

    public PageResult<WithdrawalOrder> list(Long user, String status, PageQuery page) {
        if (page.getPageNum() < 1 || page.getPageSize() < 1 || page.getPageSize() > 50)
            throw bad("每页1～50条");
        String filter = status == null || status.isBlank() ? null : status;
        if (filter != null
                && !Set.of("PENDING", "APPROVED", "REJECTED", "COMPLETED").contains(filter))
            throw bad("提现状态不合法");
        return PageResult.of(
                page.getPageNum(),
                page.getPageSize(),
                mapper.count(user, filter),
                mapper.list(user, filter, page.getPageSize(), page.offset()));
    }

    @Transactional
    public WithdrawalOrder review(long ops, String no, Review form) {
        var order = mapper.lock(no);
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND);
        if (!"PENDING".equals(order.getStatus())
                || !Objects.equals(order.getVersion(), form.version())) throw conflict();
        if (!Set.of("APPROVED", "REJECTED").contains(form.status())
                || form.note() == null
                || form.note().isBlank()
                || form.note().length() > 500) throw bad("请填写审核结果和意见");
        var balance = mapper.balance(order.getUserId(), true);
        if (balance == null || balance.getFrozenCents() < order.getAmountCents()) throw conflict();
        LocalDate due = "APPROVED".equals(form.status()) ? fifthBusinessDay(today()) : null;
        if (mapper.review(no, ops, form.status(), form.note().strip(), due, form.version()) != 1)
            throw conflict();
        if ("REJECTED".equals(form.status())) {
            if (mapper.release(order.getUserId(), order.getAmountCents(), true) != 1)
                throw conflict();
            mapper.event(
                    no,
                    "REJECT",
                    order.getAmountCents(),
                    mapper.balance(order.getUserId(), true),
                    ops);
        }
        return mapper.find(no);
    }

    public LocalDate fifthBusinessDay(LocalDate date) {
        int count = 0;
        LocalDate next = date;
        for (int attempts = 0; attempts < 370; attempts++) {
            next = next.plusDays(1);
            Integer flag = mapper.workingDay(next);
            boolean working =
                    flag != null
                            ? flag == 1
                            : next.getDayOfWeek() != DayOfWeek.SATURDAY
                                    && next.getDayOfWeek() != DayOfWeek.SUNDAY;
            if (working && ++count == 5) return next;
        }
        throw bad("工作日历配置异常，请联系管理员");
    }

    @Transactional
    public WithdrawalOrder complete(String no, Long ops) {
        var order = mapper.lock(no);
        if (order == null) throw new BizException(ErrorCode.NOT_FOUND);
        if ("COMPLETED".equals(order.getStatus())) return order;
        if (!"APPROVED".equals(order.getStatus())
                || order.getExpectedPayoutDate() == null
                || order.getExpectedPayoutDate().isAfter(today())) throw bad("尚未到达审核后的预计发放日期");
        var balance = mapper.balance(order.getUserId(), true);
        if (balance == null || balance.getFrozenCents() < order.getAmountCents()) throw conflict();
        // 仅扣减沙箱冻结额并生成明确的模拟流水；不调用真实支付宝转账。
        if (mapper.release(order.getUserId(), order.getAmountCents(), false) != 1
                || mapper.complete(no, "SIM" + UUID.randomUUID().toString().replace("-", "")) != 1)
            throw conflict();
        mapper.event(
                no, "PAYOUT", order.getAmountCents(), mapper.balance(order.getUserId(), true), ops);
        return mapper.find(no);
    }

    public List<String> due() {
        return mapper.due(today());
    }

    public List<Map<String, Object>> calendar(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start) || end.isAfter(start.plusYears(1)))
            throw bad("工作日历查询范围最多一年");
        return mapper.calendarRows(start, end);
    }

    @Transactional
    public void calendar(long ops, CalendarDay day) {
        if (day.date() == null
                || day.workingDay() == null
                || (day.note() != null && day.note().length() > 100)) throw bad("工作日设置不合法");
        mapper.calendar(
                day.date(), day.workingDay(), day.note() == null ? "" : day.note().strip(), ops);
    }

    private BizException bad(String message) {
        return new BizException(ErrorCode.BAD_REQUEST, message);
    }

    private BizException conflict() {
        return new BizException(ErrorCode.CONFLICT, "提现订单已更新，请刷新后重试");
    }
}
