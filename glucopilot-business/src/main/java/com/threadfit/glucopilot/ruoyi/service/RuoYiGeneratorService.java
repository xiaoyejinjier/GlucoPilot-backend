package com.threadfit.glucopilot.ruoyi.service;

import com.ruoyi.common.utils.SecurityUtils;
import com.ruoyi.generator.service.IGenTableService;
import com.threadfit.glucopilot.common.api.ErrorCode;
import com.threadfit.glucopilot.common.exception.BizException;
import com.threadfit.glucopilot.ruoyi.dto.RuoYiAdminDtos;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RuoYiGeneratorService {
  private final IGenTableService generator;
  private final JdbcTemplate jdbc;

  public List<RuoYiAdminDtos.GeneratorTableItem> listDatabaseTables() {
    var imported = generator.selectGenTableAll();
    return jdbc.query(
        "SELECT TABLE_NAME,TABLE_COMMENT FROM information_schema.TABLES WHERE"
            + " TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME",
        (rs, row) -> {
          var i = new RuoYiAdminDtos.GeneratorTableItem();
          i.setTableName(rs.getString(1));
          i.setTableComment(rs.getString(2));
          imported.stream()
              .filter(t -> i.getTableName().equals(t.getTableName()))
              .findFirst()
              .ifPresent(
                  t -> {
                    i.setImported(true);
                    i.setGenTableId(t.getTableId());
                  });
          return i;
        });
  }

  public RuoYiAdminDtos.GeneratorPreview sync(String name) {
    validate(name);
    var old =
        generator.selectGenTableAll().stream()
            .filter(t -> name.equals(t.getTableName()))
            .findFirst();
    if (old.isPresent()) generator.synchDb(name);
    else {
      var tables = generator.selectDbTableListByNames(new String[] {name});
      if (tables.isEmpty()) throw new BizException(ErrorCode.NOT_FOUND, "数据表不存在");
      generator.importGenTable(tables, "element-plus", SecurityUtils.getUsername());
    }
    Long id =
        generator.selectGenTableAll().stream()
            .filter(t -> name.equals(t.getTableName()))
            .findFirst()
            .orElseThrow(() -> new BizException(ErrorCode.INTERNAL_ERROR))
            .getTableId();
    return preview(id);
  }

  public RuoYiAdminDtos.GeneratorPreview preview(Long id) {
    var table = generator.selectGenTableById(id);
    if (table == null) throw new BizException(ErrorCode.NOT_FOUND);
    var code = generator.previewCode(id);
    var v = new RuoYiAdminDtos.GeneratorPreview();
    v.setTableId(id);
    v.setTableName(table.getTableName());
    v.setEntitySource(source(code, "domain"));
    v.setControllerSource(source(code, "controller"));
    v.setColumns(
        table.getColumns().stream()
            .map(
                c -> {
                  var i = new RuoYiAdminDtos.GeneratorColumnItem();
                  i.setColumnName(c.getColumnName());
                  i.setColumnComment(c.getColumnComment());
                  i.setColumnType(c.getColumnType());
                  i.setJavaType(c.getJavaType());
                  i.setJavaField(c.getJavaField());
                  i.setPrimaryKey("1".equals(c.getIsPk()));
                  i.setSortNo(c.getSort());
                  return i;
                })
            .toList());
    return v;
  }

  private String source(Map<String, String> code, String kind) {
    return code.entrySet().stream()
        .filter(e -> e.getKey().toLowerCase().contains(kind) && e.getKey().contains("java"))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("");
  }

  private void validate(String name) {
    if (name == null
        || !name.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
        || name.startsWith("gp_legacy_")
        || name.equals("flyway_schema_history"))
      throw new BizException(ErrorCode.BAD_REQUEST, "不可导入的表名");
  }
}
