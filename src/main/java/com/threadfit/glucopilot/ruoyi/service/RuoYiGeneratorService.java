package com.threadfit.glucopilot.ruoyi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.context.RequestContext;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import com.threadfit.glucopilot.ruoyi.entity.GenTable;
import com.threadfit.glucopilot.ruoyi.entity.GenTableColumn;
import com.threadfit.glucopilot.ruoyi.mapper.GenTableColumnMapper;
import com.threadfit.glucopilot.ruoyi.mapper.GenTableMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Controlled RuoYi code-generator metadata import and preview. It never executes generated source. */
@Service
@RequiredArgsConstructor
public class RuoYiGeneratorService {

    private final JdbcTemplate jdbcTemplate;
    private final GenTableMapper genTableMapper;
    private final GenTableColumnMapper genTableColumnMapper;

    public List<RuoYiAdminDtos.GeneratorTableItem> listDatabaseTables() {
        Map<String, GenTable> imported = genTableMapper.selectList(null).stream()
                .collect(Collectors.toMap(GenTable::getTableName, table -> table, (a, b) -> a));
        return jdbcTemplate.query("""
                        SELECT table_name, COALESCE(table_comment, '') AS table_comment
                        FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                        ORDER BY table_name
                        """, (rs, rowNum) -> {
                    String name = rs.getString("table_name");
                    RuoYiAdminDtos.GeneratorTableItem item = new RuoYiAdminDtos.GeneratorTableItem();
                    item.setTableName(name); item.setTableComment(rs.getString("table_comment"));
                    GenTable table = imported.get(name); item.setImported(table != null); item.setGenTableId(table == null ? null : table.getId());
                    return item;
                });
    }

    @Transactional
    public RuoYiAdminDtos.GeneratorPreview sync(String tableName) {
        validateTableName(tableName);
        List<ColumnSpec> columns = tableColumns(tableName);
        if (columns.isEmpty()) throw new BizException(ErrorCode.NOT_FOUND, "数据表不存在");
        GenTable table = genTableMapper.selectOne(new LambdaQueryWrapper<GenTable>().eq(GenTable::getTableName, tableName).last("LIMIT 1"));
        if (table == null) {
            table = new GenTable();
            table.setTableName(tableName);
            table.setTableComment(tableComment(tableName));
            table.setClassName(toClassName(tableName));
            table.setTemplateCategory("crud");
            table.setPackageName("com.threadfit.glucopilot.generated");
            table.setModuleName("generated");
            table.setBusinessName(toFieldName(tableName));
            table.setFunctionName(table.getTableComment());
            table.setFunctionAuthor("GlucoPilot");
            genTableMapper.insert(table);
        } else {
            table.setTableComment(tableComment(tableName));
            if (table.getClassName() == null || table.getClassName().isBlank()) table.setClassName(toClassName(tableName));
            genTableMapper.updateById(table);
        }
        retireColumns(table.getId());
        int sort = 1;
        for (ColumnSpec column : columns) {
            GenTableColumn entity = new GenTableColumn();
            entity.setTableId(table.getId()); entity.setColumnName(column.name()); entity.setColumnComment(column.comment()); entity.setColumnType(column.type());
            entity.setJavaType(javaType(column.type())); entity.setJavaField(toFieldName(column.name())); entity.setIsPk(column.primaryKey() ? 1 : 0);
            entity.setIsRequired(column.nullable() ? 0 : 1); entity.setIsInsertable(1); entity.setIsEditable(column.primaryKey() ? 0 : 1); entity.setIsListable(1);
            entity.setIsQueryable(column.primaryKey() ? 1 : 0); entity.setQueryType("EQ"); entity.setHtmlType(htmlType(column.type())); entity.setSortNo(sort++);
            genTableColumnMapper.insert(entity);
        }
        return preview(table.getId());
    }

    public RuoYiAdminDtos.GeneratorPreview preview(Long tableId) {
        GenTable table = genTableMapper.selectById(tableId);
        if (table == null) throw new BizException(ErrorCode.NOT_FOUND, "生成器表配置不存在");
        List<GenTableColumn> persisted = genTableColumnMapper.selectList(new LambdaQueryWrapper<GenTableColumn>()
                .eq(GenTableColumn::getTableId, tableId).orderByAsc(GenTableColumn::getSortNo));
        List<RuoYiAdminDtos.GeneratorColumnItem> columns = persisted.stream().map(this::toColumnItem).toList();
        RuoYiAdminDtos.GeneratorPreview preview = new RuoYiAdminDtos.GeneratorPreview();
        preview.setTableId(tableId); preview.setTableName(table.getTableName()); preview.setColumns(columns);
        preview.setEntitySource(entitySource(table, columns)); preview.setControllerSource(controllerSource(table));
        return preview;
    }

    private List<ColumnSpec> tableColumns(String tableName) {
        return jdbcTemplate.query("""
                        SELECT column_name, COALESCE(column_comment, '') AS column_comment,
                               column_type, is_nullable, column_key
                        FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = ?
                        ORDER BY ordinal_position
                        """, (rs, rowNum) -> new ColumnSpec(rs.getString("column_name"), rs.getString("column_comment"),
                rs.getString("column_type"), "YES".equals(rs.getString("is_nullable")), "PRI".equals(rs.getString("column_key"))), tableName);
    }

    private String tableComment(String tableName) {
        List<String> result = jdbcTemplate.query("SELECT COALESCE(table_comment, '') FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                (rs, rowNum) -> rs.getString(1), tableName);
        return result.isEmpty() ? "" : result.getFirst();
    }

    private void retireColumns(Long tableId) {
        long actor = RequestContext.operatorIdOrZero(); LocalDateTime now = LocalDateTime.now();
        for (GenTableColumn column : genTableColumnMapper.selectList(new LambdaQueryWrapper<GenTableColumn>().eq(GenTableColumn::getTableId, tableId))) {
            column.setIsDeleted(1); column.setDeletedBy(actor); column.setDeletedAt(now); genTableColumnMapper.updateById(column);
        }
    }

    private RuoYiAdminDtos.GeneratorColumnItem toColumnItem(GenTableColumn column) {
        RuoYiAdminDtos.GeneratorColumnItem item = new RuoYiAdminDtos.GeneratorColumnItem();
        item.setColumnName(column.getColumnName()); item.setColumnComment(column.getColumnComment()); item.setColumnType(column.getColumnType());
        item.setJavaType(column.getJavaType()); item.setJavaField(column.getJavaField()); item.setPrimaryKey(column.getIsPk() != null && column.getIsPk() == 1); item.setSortNo(column.getSortNo()); return item;
    }

    private String entitySource(GenTable table, List<RuoYiAdminDtos.GeneratorColumnItem> columns) {
        String packageName = table.getPackageName() == null || table.getPackageName().isBlank() ? "com.threadfit.glucopilot.generated" : table.getPackageName();
        StringBuilder source = new StringBuilder("package ").append(packageName).append(";\n\nimport lombok.Data;\n\n@Data\npublic class ")
                .append(table.getClassName()).append(" {\n");
        for (RuoYiAdminDtos.GeneratorColumnItem column : columns) {
            source.append("    /** ").append(column.getColumnComment() == null ? "" : column.getColumnComment()).append(" */\n")
                    .append("    private ").append(column.getJavaType()).append(" ").append(column.getJavaField()).append(";\n");
        }
        return source.append("}\n").toString();
    }

    private String controllerSource(GenTable table) {
        String className = table.getClassName();
        String path = table.getBusinessName() == null || table.getBusinessName().isBlank() ? toFieldName(table.getTableName()) : table.getBusinessName();
        return """
                package com.threadfit.glucopilot.generated.controller;

                import org.springframework.web.bind.annotation.*;

                @RestController
                @RequestMapping(\"/admin/v1/generated/%s\")
                public class %sController {
                    // Generated endpoints must return ApiResponse and add @PreAuthorize before enabling.
                }
                """.formatted(path, className);
    }

    private static void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) throw new BizException(ErrorCode.BAD_REQUEST, "表名不合法");
    }
    private static String toClassName(String value) { String field = toFieldName(value); return Character.toUpperCase(field.charAt(0)) + field.substring(1); }
    private static String toFieldName(String value) {
        StringBuilder output = new StringBuilder(); boolean upper = false;
        for (char ch : value.toCharArray()) { if (ch == '_') { upper = true; continue; } output.append(upper ? Character.toUpperCase(ch) : Character.toLowerCase(ch)); upper = false; }
        return output.toString();
    }
    private static String javaType(String mysqlType) { String type = mysqlType.toLowerCase(); if (type.startsWith("bigint")) return "Long"; if (type.startsWith("int") || type.startsWith("smallint") || type.startsWith("tinyint")) return "Integer"; if (type.startsWith("decimal") || type.startsWith("double") || type.startsWith("float")) return "java.math.BigDecimal"; if (type.contains("date") || type.contains("time")) return "java.time.LocalDateTime"; return "String"; }
    private static String htmlType(String mysqlType) { String type = mysqlType.toLowerCase(); if (type.contains("text")) return "textarea"; if (type.contains("date") || type.contains("time")) return "datetime"; if (type.startsWith("tinyint(1")) return "radio"; return "input"; }

    private record ColumnSpec(String name, String comment, String type, boolean nullable, boolean primaryKey) {}
}
