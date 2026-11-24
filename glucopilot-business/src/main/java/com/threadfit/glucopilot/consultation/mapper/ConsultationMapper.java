package com.threadfit.glucopilot.consultation.mapper;

import com.threadfit.glucopilot.consultation.dto.ConsultationDtos.*;
import com.threadfit.glucopilot.consultation.entity.*;

import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ConsultationMapper {
    int ownedImage(@Param("user") long user, @Param("media") long media);

    int linkImage(@Param("id") long id, @Param("media") long media, @Param("sort") int sort);

    List<Long> imageIds(@Param("id") long id);

    com.threadfit.glucopilot.file.entity.MediaFile image(@Param("media") long media);

    ConsultationMessage messageRequest(@Param("user") long user, @Param("key") String key);

    int insertMessage(
            @Param("id") long id,
            @Param("user") long user,
            @Param("role") String role,
            @Param("form")
                    com.threadfit.glucopilot.consultation.dto.ConsultationConversationDtos.Send
                            form);

    List<ConsultationMessage> messages(
            @Param("id") long id,
            @Param("after") Long after,
            @Param("before") Long before,
            @Param("limit") int limit);

    Long lockUser(@Param("user") long user);

    DoctorProfile doctor(@Param("user") long user);

    DoctorProfile lockDoctor(@Param("user") long user);

    int insertDoctor(@Param("user") long user, @Param("form") DoctorApplication form);

    int updateDoctor(@Param("user") long user, @Param("form") DoctorApplication form);

    long doctorCount(@Param("status") String status);

    List<DoctorProfile> doctors(
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") long offset);

    int review(@Param("user") long user, @Param("ops") long ops, @Param("form") DoctorReview form);

    long count(
            @Param("viewer") long viewer,
            @Param("mine") boolean mine,
            @Param("assigned") boolean assigned,
            @Param("hideMine") boolean hideMine,
            @Param("unclaimedOnly") boolean unclaimedOnly,
            @Param("topic") String topic);

    List<ConsultationRow> list(
            @Param("viewer") long viewer,
            @Param("mine") boolean mine,
            @Param("assigned") boolean assigned,
            @Param("hideMine") boolean hideMine,
            @Param("unclaimedOnly") boolean unclaimedOnly,
            @Param("topic") String topic,
            @Param("limit") int limit,
            @Param("offset") long offset);

    ConsultationRow find(@Param("id") long id);

    ConsultationRow lockCase(@Param("id") long id);

    ConsultationRow request(@Param("user") long user, @Param("key") String key);

    int insertCase(@Param("user") long user, @Param("form") PublishRequest form);

    int claim(@Param("id") long id, @Param("doctor") long doctor);

    ConsultationReport report(@Param("id") long id);

    ConsultationReport reportByCase(@Param("id") long id);

    ConsultationReport reportRequest(@Param("user") long user, @Param("key") String key);

    int insertReport(
            @Param("id") long id,
            @Param("doctor") DoctorProfile doctor,
            @Param("form") ReportRequest form);

    int complete(@Param("id") long id, @Param("doctor") long doctor);

    boolean hasAccess(@Param("user") long user, @Param("report") long report);

    ConsultationOrder orderRequest(@Param("user") long user, @Param("key") String key);

    ConsultationOrder activeOrder(@Param("user") long user, @Param("report") long report);

    int insertOrder(
            @Param("no") String no,
            @Param("user") long user,
            @Param("report") ConsultationReport report,
            @Param("key") String key);

    ConsultationOrder lockOrder(@Param("user") long user, @Param("no") String no);

    List<Long> lockParticipants(@Param("first") long first, @Param("second") long second);

    int debit(@Param("user") long user);

    int creditDoctor(@Param("user") long user);

    int markPaid(@Param("no") String no);

    /** 作为医生仍在进行中且未支付的问诊单数量（注销预检第五项阻断）。 */
    long countInFlight(@Param("doctor") long doctor);

    int entry(
            @Param("no") String no,
            @Param("user") long user,
            @Param("amount") int amount,
            @Param("balance") long balance);
}
