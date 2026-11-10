package com.threadfit.glucopilot.notification.mapper;

import com.threadfit.glucopilot.notification.entity.UserReminderRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface UserReminderMapper {
    long countUser(@Param("userId") long userId);

    List<UserReminderRow> alerts(@Param("userId") long userId, @Param("date") LocalDate date);

    long countReplies(@Param("userId") long userId);

    List<UserReminderRow> replies(
            @Param("userId") long userId, @Param("limit") int limit, @Param("offset") long offset);

    UserReminderRow alert(@Param("userId") long userId, @Param("id") long id);

    UserReminderRow reply(@Param("userId") long userId, @Param("id") long id);

    int saveRead(
            @Param("userId") long userId,
            @Param("kind") String kind,
            @Param("row") UserReminderRow row,
            @Param("replyHash") String replyHash);

    Long messageCursor(@Param("userId") long userId);

    int readMessages(@Param("userId") long userId, @Param("through") long through);

    Long noticeCursor(@Param("userId") long userId, @Param("kind") String kind);

    int readNotices(
            @Param("userId") long userId,
            @Param("kind") String kind,
            @Param("through") long through);

    List<UserReminderRow> reportSnapshots(@Param("userId") long userId);

    int readReports(
            @Param("userId") long userId, @Param("snapshots") List<UserReminderRow> snapshots);
}
