package com.threadfit.glucopilot.export.mapper;

/** viewer为空表示经过管理端鉴权后的查询；用户端始终传当前登录用户ID。 */
public record DataExportQuery(Long viewer, Integer status, String keyword) {}
