package com.threadfit.glucopilot.consultation.service;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos.*;
import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.*;
import com.threadfit.glucopilot.consultation.entity.*;
import com.threadfit.glucopilot.consultation.mapper.ConsultationMapper;
import com.threadfit.glucopilot.payment.mapper.SandboxPaymentMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ConsultationService {
    private final ConsultationMapper mapper;
    private final SandboxPaymentMapper wallets;

    public Capabilities capabilities() {
        return new Capabilities("ACTIVE", true, true, true, 100, "CNY", "WALLET_SANDBOX");
    }

    public List<ConsultationTopic> topics() {
        return mapper.topics();
    }

    /** 只允许启用的字典编码，客户端不能自行创建分类。 */
    private String topicFilter(String topic) {
        if (topic == null || topic.isBlank()) return null;
        if (!topic.matches("[A-Z][A-Z0-9_]{0,15}") || mapper.enabledTopic(topic) != 1)
            throw new BizException(ErrorCode.BAD_REQUEST, "问题分类已停用或不存在，请重新选择");
        return topic;
    }

    public DoctorProfile doctor(long user) {
        return mapper.doctor(user);
    }

    /** 阅读与审核共享医生行锁，旧页面不能把之后的新审核结果标为已读。 */
    @Transactional
    public void readDoctorReview(long user, ReadDoctorReview request) {
        requireActiveUser(user);
        var current = mapper.lockDoctor(user);
        if (current == null || current.getReviewedAt() == null
                || !Set.of("APPROVED", "REJECTED", "DISABLED").contains(current.getStatus()))
            throw new BizException(ErrorCode.NOT_FOUND, "审核结果不存在或已更新");
        if (request == null || request.version() == null || request.version() != current.getVersion())
            throw new BizException(ErrorCode.CONFLICT, "审核结果已更新，请刷新后查看");
        mapper.readDoctorReview(user, current.getVersion());
    }

    @Transactional
    public DoctorProfile apply(long user, DoctorApplication request) {
        requireActiveUser(user);
        var form =
                new DoctorApplication(
                        text(request.realName()),
                        text(request.hospital()),
                        text(request.specialty()),
                        text(request.licenseNumber()),
                        text(request.qualificationNote()));
        var previous = mapper.lockDoctor(user);
        if (previous == null) mapper.insertDoctor(user, form);
        else if (mapper.updateDoctor(user, form) != 1)
            throw new BizException(ErrorCode.CONFLICT, "当前医生身份不能重新提交申请，请联系管理员");
        return mapper.doctor(user);
    }

    public PageResult<DoctorProfile> doctors(String status, PageQuery page) {
        checkPage(page);
        String filter = filter(status, Set.of("PENDING", "APPROVED", "REJECTED", "DISABLED"));
        return PageResult.of(
                page.getPageNum(),
                page.getPageSize(),
                mapper.doctorCount(filter),
                mapper.doctors(filter, page.getPageSize(), page.offset()));
    }

    @Transactional
    public DoctorProfile review(long ops, long user, DoctorReview form) {
        if (mapper.lockDoctor(user) == null) throw missing();
        var reviewed = new DoctorReview(form.status(), text(form.reviewNote()), form.version());
        if (mapper.review(user, ops, reviewed) != 1)
            throw new BizException(ErrorCode.CONFLICT, "医生申请已更新，请刷新后审核");
        return mapper.doctor(user);
    }

    public PageResult<CaseView> list(
            long viewer, boolean mine, boolean assigned, String topic, PageQuery page) {
        return list(viewer, mine, assigned, false, topic, page);
    }

    public PageResult<CaseView> list(
            long viewer,
            boolean mine,
            boolean assigned,
            boolean hideMine,
            String topic,
            PageQuery page) {
        return list(viewer, mine, assigned, hideMine, false, topic, page);
    }

    public PageResult<CaseView> list(
            long viewer,
            boolean mine,
            boolean assigned,
            boolean hideMine,
            boolean unclaimedOnly,
            String topic,
            PageQuery page) {
        return list(viewer, mine, assigned, hideMine, unclaimedOnly, null, topic, page);
    }

    public PageResult<CaseView> list(
            long viewer,
            boolean mine,
            boolean assigned,
            boolean hideMine,
            boolean unclaimedOnly,
            String status,
            String topic,
            PageQuery page) {
        checkPage(page);
        String filter = topicFilter(topic);
        String state = filter(status, Set.of("WAITING", "IN_PROGRESS", "REPORT_READY", "CLOSED"));
        var doctor = mapper.doctor(viewer);
        if (assigned && !approved(doctor))
            throw new BizException(ErrorCode.FORBIDDEN, "请先完成医生身份审核");
        if (unclaimedOnly && !approved(doctor))
            throw new BizException(ErrorCode.FORBIDDEN, "请先完成医生身份审核");
        if (unclaimedOnly && (mine || assigned))
            throw new BizException(ErrorCode.BAD_REQUEST, "未接诊筛选仅用于求助广场");
        if (unclaimedOnly && state != null && !"WAITING".equals(state))
            throw new BizException(ErrorCode.BAD_REQUEST, "未接诊筛选不能与其他状态同时使用");
        boolean excludeSelf = hideMine && !mine && !assigned;
        var rows =
                mapper.list(
                        viewer,
                        mine,
                        assigned,
                        excludeSelf,
                        unclaimedOnly,
                        state,
                        filter,
                        page.getPageSize(),
                        page.offset());
        var items = rows.stream().map(row -> view(viewer, row, doctor, null, false)).toList();
        return PageResult.of(
                page.getPageNum(),
                page.getPageSize(),
                mapper.count(viewer, mine, assigned, excludeSelf, unclaimedOnly, state, filter),
                items);
    }

    public CaseView detail(long viewer, long id) {
        var row = mapper.find(id);
        if (row == null) throw missing();
        return view(viewer, row, mapper.doctor(viewer), mapper.reportByCase(id), true);
    }

    @Transactional
    public CaseView publish(long user, PublishRequest request) {
        requireActiveUser(user);
        var form =
                new PublishRequest(
                        text(request.title()),
                        request.topic(),
                        text(request.symptoms()),
                        optional(request.glucoseNote()),
                        optional(request.medicationNote()),
                        request.anonymous(),
                        request.clientRequestId(),
                        imageIds(request.imageMediaIds()));
        if (form.title().length() < 4 || form.symptoms().length() < 10)
            throw new BizException(ErrorCode.BAD_REQUEST, "请补充完整标题和问题描述");
        var previous = mapper.request(user, form.clientRequestId());
        if (previous != null) {
            if (!sameCase(previous, form)
                    || !mapper.imageIds(previous.getId()).equals(form.imageMediaIds()))
                throw conflict();
            return detail(user, previous.getId());
        }
        // 旧请求可幂等重试；只有新发布需要校验分类当前是否启用。
        if (topicFilter(form.topic()) == null)
            throw new BizException(ErrorCode.BAD_REQUEST, "请选择问题分类");
        mapper.insertCase(user, form);
        long id = mapper.request(user, form.clientRequestId()).getId();
        int sort = 0;
        for (long media : form.imageMediaIds()) {
            if (mapper.ownedImage(user, media) != 1)
                throw new BizException(ErrorCode.BAD_REQUEST, "问诊图片不存在或不属于当前用户");
            mapper.linkImage(id, media, sort++);
        }
        return detail(user, id);
    }

    private List<Long> imageIds(List<Long> ids) {
        if (ids == null) return List.of();
        if (ids.size() > 6
                || ids.stream().anyMatch(id -> id == null || id < 1)
                || new HashSet<>(ids).size() != ids.size())
            throw new BizException(ErrorCode.BAD_REQUEST, "最多添加6张有效图片，不能重复");
        return List.copyOf(ids);
    }

    public List<Photo> images(long viewer, long id) {
        if (!detail(viewer, id).detailsVisible()) throw missing();
        return mapper.imageIds(id).stream()
                .map(media -> new Photo(media, "/api/v1/consultations/" + id + "/images/" + media))
                .toList();
    }

    public Messages messages(long viewer, long id, Long after, Long before, int limit) {
        var row = conversation(viewer, id, false);
        if (limit < 1
                || limit > 50
                || (after != null && after < 1)
                || (before != null && before < 1)
                || (after != null && before != null)) throw new BizException(ErrorCode.BAD_REQUEST);
        var records = new ArrayList<>(mapper.messages(id, after, before, limit + 1));
        boolean more = records.size() > limit;
        if (more) records.removeLast();
        if (after == null) Collections.reverse(records);
        records.forEach(message -> message.setMine(message.getSenderId() == viewer));
        return new Messages(
                records,
                more,
                records.isEmpty() ? null : records.getFirst().getId(),
                records.isEmpty() ? after : records.getLast().getId(),
                !"CLOSED".equals(row.getStatus()));
    }

    @Transactional
    public ConsultationMessage sendMessage(long viewer, long id, Send request) {
        var row = conversation(viewer, id, true);
        var form = new Send(text(request.content()), request.clientRequestId());
        if (form.content().length() > 2000
                || form.clientRequestId() == null
                || !form.clientRequestId().matches("[A-Za-z0-9_-]{8,64}"))
            throw new BizException(ErrorCode.BAD_REQUEST);
        var previous = mapper.messageRequest(viewer, form.clientRequestId());
        if (previous != null) return sameMessage(previous, id, form, viewer);
        if ("CLOSED".equals(row.getStatus()))
            throw new BizException(ErrorCode.CONFLICT, "问诊已结束，不能继续发送");
        try {
            mapper.insertMessage(
                    id, viewer, row.getPatientId() == viewer ? "PATIENT" : "DOCTOR", form);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            /* 唯一键兜底；下方核对原消息。 */
        }
        return sameMessage(mapper.messageRequest(viewer, form.clientRequestId()), id, form, viewer);
    }

    private ConsultationMessage sameMessage(
            ConsultationMessage message, long id, Send form, long viewer) {
        if (message == null) throw new BizException(ErrorCode.INTERNAL_ERROR);
        if (message.getConsultationId() != id || !message.getContent().equals(form.content()))
            throw conflict();
        message.setMine(message.getSenderId() == viewer);
        return message;
    }

    private ConsultationRow conversation(long viewer, long id, boolean lock) {
        var row = lock ? mapper.lockCase(id) : mapper.find(id);
        if (row == null
                || mapper.find(id) == null
                || "WAITING".equals(row.getStatus())
                || !detail(viewer, id).detailsVisible()) throw missing();
        if (lock) requireActiveUser(viewer);
        return row;
    }

    @Transactional
    public CaseView claim(long doctor, long id) {
        var row = mapper.lockCase(id);
        if (row == null || mapper.find(id) == null) throw missing();
        if (row.getPatientId() == doctor)
            throw new BizException(ErrorCode.BAD_REQUEST, "不能接诊自己的求助");
        if (wallets.lockActivatedUser(doctor) == null)
            throw new BizException(ErrorCode.BAD_REQUEST, "请先开通钱包以接收问诊费用");
        requireDoctor(doctor);
        if (Objects.equals(row.getDoctorUserId(), doctor)) return detail(doctor, id);
        if (mapper.claim(id, doctor) != 1)
            throw new BizException(ErrorCode.CONFLICT, "该求助已被接诊，请刷新");
        return detail(doctor, id);
    }

    @Transactional
    public CaseView report(long doctor, long id, ReportRequest request) {
        var row = mapper.lockCase(id);
        if (row == null || mapper.find(id) == null) throw missing();
        requireActiveUser(doctor);
        var profile = requireDoctor(doctor);
        if (!Objects.equals(row.getDoctorUserId(), doctor))
            throw new BizException(ErrorCode.FORBIDDEN);
        var form =
                new ReportRequest(
                        text(request.assessment()),
                        text(request.medicationAdvice()),
                        text(request.followUpAdvice()),
                        request.clientRequestId());
        var previous = mapper.reportRequest(doctor, form.clientRequestId());
        if (previous != null) {
            if (previous.getConsultationId() != id || !sameReport(previous, form)) throw conflict();
            return detail(doctor, id);
        }
        if (!"IN_PROGRESS".equals(row.getStatus()))
            throw new BizException(ErrorCode.CONFLICT, "该问诊单已出具或已结束");
        mapper.insertReport(id, profile, form);
        if (mapper.complete(id, doctor) != 1) throw conflict();
        return detail(doctor, id);
    }

    public ReportView reportDetail(long viewer, long id) {
        var report = mapper.report(id);
        if (report == null) throw missing();
        var row = mapper.find(report.getConsultationId());
        if (row == null
                || (row.getPatientId() != viewer && !Objects.equals(row.getDoctorUserId(), viewer)))
            throw missing();
        return reportView(viewer, row, mapper.doctor(viewer), report);
    }

    @Transactional
    public String createOrder(long user, OrderRequest form) {
        requireActiveUser(user);
        var previous = mapper.orderRequest(user, form.clientRequestId());
        if (previous != null) {
            if (!Objects.equals(previous.getReportId(), form.reportId())) throw conflict();
            return previous.getOrderNo();
        }
        var report = mapper.report(form.reportId());
        if (report == null) throw missing();
        var row = mapper.find(report.getConsultationId());
        if (row == null || row.getPatientId() != user) throw missing();
        var active = mapper.activeOrder(user, report.getId());
        if (active != null) return active.getOrderNo();
        requireDoctor(report.getDoctorUserId());
        String no = "CO" + UUID.randomUUID().toString().replace("-", "");
        mapper.insertOrder(no, user, report, form.clientRequestId());
        return no;
    }

    /** 同一事务完成扣款、医生入账、流水和阅读授权，重试先返回已完成订单。 */
    @Transactional
    public void pay(long user, String no) {
        var order = mapper.lockOrder(user, no);
        if (order == null) throw missing();
        if ("PAID".equals(order.getStatus())) return;
        if (!"PENDING".equals(order.getStatus())
                || order.getAmountCents() != 100
                || !"WALLET_SANDBOX".equals(order.getPaymentProvider())) throw conflict();
        long doctor = order.getDoctorUserId();
        if (user == doctor) throw conflict();
        // 先按相同顺序锁双方账号，再锁余额，避免两个付款事务反向等待。
        if (mapper.lockParticipants(Math.min(user, doctor), Math.max(user, doctor)).size() != 2)
            throw new BizException(ErrorCode.BAD_REQUEST, "双方需开通钱包且账号有效");
        requireDoctor(doctor);
        wallets.ensureAccount(Math.min(user, doctor));
        wallets.ensureAccount(Math.max(user, doctor));
        long first = wallets.lockBalance(Math.min(user, doctor));
        long second = wallets.lockBalance(Math.max(user, doctor));
        long patientBalance = user < doctor ? first : second;
        long doctorBalance = user < doctor ? second : first;
        if (patientBalance < 100) throw new BizException(ErrorCode.BAD_REQUEST, "沙箱钱包余额不足，请先充值");
        long creditedBalance = Math.addExact(doctorBalance, 100);
        if (mapper.debit(user) != 1 || mapper.creditDoctor(doctor) != 1 || mapper.markPaid(no) != 1)
            throw conflict();
        mapper.entry(no, user, -100, patientBalance - 100);
        mapper.entry(no, doctor, 100, creditedBalance);
    }

    private CaseView view(
            long viewer,
            ConsultationRow row,
            DoctorProfile profile,
            ConsultationReport report,
            boolean detail) {
        boolean mine = row.getPatientId() == viewer;
        boolean assigned = Objects.equals(row.getDoctorUserId(), viewer) && approved(profile);
        boolean visible = detail && (mine || assigned);
        String name = row.isAnonymous() ? "匿名糖友" : Objects.toString(row.getPatientName(), "糖友");
        return new CaseView(
                row.getId(),
                row.getTitle(),
                row.getTopic(),
                row.getStatus(),
                row.getTitle(),
                name,
                row.isAnonymous(),
                row.getCreatedAt(),
                visible ? row.getSymptoms() : null,
                visible ? row.getGlucoseNote() : null,
                visible ? row.getMedicationNote() : null,
                mine,
                visible,
                !mine && approved(profile) && "WAITING".equals(row.getStatus()),
                assigned && "IN_PROGRESS".equals(row.getStatus()),
                detail && (mine || assigned) ? reportView(viewer, row, profile, report) : null,
                row.getTopicName());
    }

    private ReportView reportView(
            long viewer, ConsultationRow row, DoctorProfile profile, ConsultationReport report) {
        if (report == null)
            return new ReportView(
                    null, row.getId(), false, "LOCKED", 100, null, null, null, null, null, null);
        boolean unlocked =
                (Objects.equals(row.getDoctorUserId(), viewer) && approved(profile))
                        || (row.getPatientId() == viewer
                                && mapper.hasAccess(viewer, report.getId()));
        return new ReportView(
                report.getId(),
                row.getId(),
                true,
                unlocked ? "UNLOCKED" : "LOCKED",
                100,
                report.getDoctorName(),
                report.getSpecialty(),
                unlocked ? report.getAssessment() : null,
                unlocked ? report.getMedicationAdvice() : null,
                unlocked ? report.getFollowUpAdvice() : null,
                report.getCreatedAt());
    }

    private DoctorProfile requireDoctor(long user) {
        var profile = mapper.lockDoctor(user);
        if (!approved(profile) || mapper.doctor(user) == null)
            throw new BizException(ErrorCode.FORBIDDEN, "请先完成医生身份审核");
        return profile;
    }

    private void requireActiveUser(long user) {
        if (mapper.lockUser(user) == null) throw new BizException(ErrorCode.FORBIDDEN);
    }

    private boolean approved(DoctorProfile doctor) {
        return doctor != null && "APPROVED".equals(doctor.getStatus());
    }

    private boolean sameCase(ConsultationRow row, PublishRequest form) {
        return row.getTitle().equals(form.title())
                && row.getTopic().equals(form.topic())
                && row.getSymptoms().equals(form.symptoms())
                && row.getGlucoseNote().equals(form.glucoseNote())
                && row.getMedicationNote().equals(form.medicationNote())
                && row.isAnonymous() == form.anonymous();
    }

    private boolean sameReport(ConsultationReport row, ReportRequest form) {
        return row.getAssessment().equals(form.assessment())
                && row.getMedicationAdvice().equals(form.medicationAdvice())
                && row.getFollowUpAdvice().equals(form.followUpAdvice());
    }

    private String text(String value) {
        if (value == null || value.isBlank())
            throw new BizException(ErrorCode.BAD_REQUEST, "请填写完整内容");
        return value.strip();
    }

    private String optional(String value) {
        return value == null ? "" : value.strip();
    }

    private void checkPage(PageQuery page) {
        if (page.getPageNum() < 1 || page.getPageSize() < 1 || page.getPageSize() > 50)
            throw new BizException(ErrorCode.BAD_REQUEST, "每页1～50条");
    }

    private String filter(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) return null;
        if (!allowed.contains(value)) throw new BizException(ErrorCode.BAD_REQUEST);
        return value;
    }

    private BizException conflict() {
        return new BizException(ErrorCode.CONFLICT, "请求已处理或内容已变化，请刷新");
    }

    private BizException missing() {
        return new BizException(ErrorCode.NOT_FOUND, "问诊记录不存在或无权查看");
    }
}
