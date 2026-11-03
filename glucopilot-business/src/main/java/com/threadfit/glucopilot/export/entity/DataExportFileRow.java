package com.threadfit.glucopilot.export.entity;

import lombok.Data;

/** 使用明确的BLOB字段映射读取文件，避免byte[]被Mapper误判为多行返回数组。 */
@Data
public class DataExportFileRow {
    private byte[] fileBytes;
}
