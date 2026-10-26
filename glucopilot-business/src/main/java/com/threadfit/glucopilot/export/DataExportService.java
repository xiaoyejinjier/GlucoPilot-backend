package com.threadfit.glucopilot.export;

import com.threadfit.glucopilot.common.api.*;
import com.threadfit.glucopilot.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayInputStream;
import java.sql.Date;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class DataExportService {
    private final JdbcTemplate jdbc;
    public static final String MIME="application/pdf";
    public record Create(LocalDate startDate,LocalDate endDate,String clientRequestId) {}
    public record Task(String id,String userId,String userName,String status,LocalDate startDate,LocalDate endDate,LocalDateTime createdAt,LocalDateTime finishedAt,String error,int glucoseCount,int mealCount) {}
    private static final String SELECT="SELECT t.*,COALESCE(NULLIF(p.nickname,''),u.username,'用户') user_name,COALESCE(f.glucose_count,0) glucose_count,COALESCE(f.meal_count,0) meal_count FROM data_export_task t JOIN user_account u ON u.id=t.user_id AND u.is_deleted=0 LEFT JOIN user_profile p ON p.user_id=u.id AND p.is_deleted=0 LEFT JOIN data_export_file f ON f.task_id=t.id ";
    private BizException missing(){return new BizException(ErrorCode.NOT_FOUND,"导出任务不存在或不可访问");}
    @Transactional public void remove(long user,long id){
        if(jdbc.queryForList("SELECT id FROM data_export_task WHERE id=? AND user_id=? AND is_deleted=0 FOR UPDATE",Long.class,id,user).isEmpty())throw missing();
        jdbc.update("UPDATE data_export_task SET user_deleted_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND user_deleted_at IS NULL",id,user);
    }
    private static LocalDateTime dateTime(Object value){if(value==null)return null;if(value instanceof LocalDateTime date)return date;if(value instanceof java.sql.Timestamp date)return date.toLocalDateTime();return LocalDateTime.parse(value.toString().replace(' ','T'));}
    private static LocalDate date(Object value){if(value instanceof LocalDate day)return day;if(value instanceof Date day)return day.toLocalDate();return LocalDate.parse(value.toString());}
    private Task map(Map<String,Object> row){LocalDateTime created=dateTime(row.get("gmt_create"));LocalDate end=row.get("end_date")==null?created.toLocalDate():date(row.get("end_date"));LocalDate start=row.get("start_date")==null?end.minusDays(((Number)row.get("export_scope")).intValue()==1?6:29):date(row.get("start_date"));return new Task(row.get("id").toString(),row.get("user_id").toString(),row.get("user_name").toString(),switch(((Number)row.get("task_status")).intValue()){case 1->"processing";case 2->"done";case 3->"failed";default->"pending";},start,end,created,dateTime(row.get("finished_at")),Objects.toString(row.get("error_msg"),""),((Number)row.get("glucose_count")).intValue(),((Number)row.get("meal_count")).intValue());}
    public Task get(Long viewer,long id){var rows=jdbc.queryForList(SELECT+"WHERE t.id=? AND t.is_deleted=0"+(viewer==null?"":" AND t.user_id=? AND t.user_deleted_at IS NULL"),viewer==null?new Object[]{id}:new Object[]{id,viewer});if(rows.isEmpty())throw missing();return map(rows.getFirst());}
    public PageResult<Task> list(Long viewer,String status,String keyword,PageQuery q){if(q.getPageNum()<1||q.getPageSize()<1||q.getPageSize()>100)throw new BizException(ErrorCode.BAD_REQUEST,"分页参数不正确");String filter="t.is_deleted=0";var args=new ArrayList<Object>();if(viewer!=null){filter+=" AND t.user_id=? AND t.user_deleted_at IS NULL";args.add(viewer);}if(status!=null&&!status.isBlank()){var states=List.of("pending","processing","done","failed");int index=states.indexOf(status);if(index<0)throw new BizException(ErrorCode.BAD_REQUEST,"任务状态不正确");filter+=" AND t.task_status=?";args.add(index);}if(keyword!=null&&!keyword.isBlank()){if(keyword.length()>64)throw new BizException(ErrorCode.BAD_REQUEST,"关键词最多64字");filter+=" AND (LOCATE(?,COALESCE(p.nickname,''))>0 OR LOCATE(?,COALESCE(u.username,''))>0)";args.add(keyword.trim());args.add(keyword.trim());}long total=jdbc.queryForObject("SELECT COUNT(*) FROM ("+SELECT+"WHERE "+filter+") filtered",Long.class,args.toArray());args.add(q.getPageSize());args.add(q.offset());return PageResult.of(q.getPageNum(),q.getPageSize(),total,jdbc.queryForList(SELECT+"WHERE "+filter+" ORDER BY t.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(this::map).toList());}
    @Transactional public Task create(long user,Create p){if(p==null||p.startDate()==null||p.endDate()==null||p.startDate().isAfter(p.endDate())||p.endDate().isAfter(LocalDate.now())||java.time.temporal.ChronoUnit.DAYS.between(p.startDate(),p.endDate())>365||p.clientRequestId()==null||!p.clientRequestId().matches("[A-Za-z0-9_-]{8,64}"))throw new BizException(ErrorCode.BAD_REQUEST,"请选择最多366天的有效历史日期");if(jdbc.queryForList("SELECT id FROM user_account WHERE id=? AND is_deleted=0 AND account_status=1 FOR UPDATE",Long.class,user).isEmpty())throw missing();var prior=jdbc.queryForList("SELECT id FROM data_export_task WHERE user_id=? AND client_request_id=?",Long.class,user,p.clientRequestId());if(!prior.isEmpty()){if(jdbc.queryForObject("SELECT user_deleted_at IS NOT NULL FROM data_export_task WHERE id=?",Boolean.class,prior.getFirst()))throw new BizException(ErrorCode.CONFLICT,"该导出任务已移除，请重新发起导出");var saved=get(user,prior.getFirst());if(!saved.startDate().equals(p.startDate())||!saved.endDate().equals(p.endDate()))throw new BizException(ErrorCode.CONFLICT,"导出请求已使用，请重新确认");return saved;}if(jdbc.queryForObject("SELECT COUNT(*) FROM data_export_task WHERE user_id=? AND is_deleted=0 AND gmt_create>?",Long.class,user,LocalDateTime.now().minusHours(24))>=20)throw new BizException(ErrorCode.TOO_MANY_REQUESTS,"今日导出申请较多，请稍后再试");jdbc.update("INSERT INTO data_export_task(user_id,export_scope,start_date,end_date,client_request_id,created_by) VALUES(?,3,?,?,?,?)",user,p.startDate(),p.endDate(),p.clientRequestId(),user);return get(user,jdbc.queryForObject("SELECT id FROM data_export_task WHERE user_id=? AND client_request_id=?",Long.class,user,p.clientRequestId()));}
    @Transactional public Task generate(Long viewer,long id){if(jdbc.queryForList("SELECT id FROM data_export_task WHERE id=? AND is_deleted=0"+(viewer==null?"":" AND user_id=? AND user_deleted_at IS NULL")+" FOR UPDATE",Long.class,viewer==null?new Object[]{id}:new Object[]{id,viewer}).isEmpty())throw missing();var task=get(viewer,id);if(task.status().equals("done")&&jdbc.queryForObject("SELECT COUNT(*) FROM data_export_file WHERE task_id=?",Long.class,id)>0)return task;
        jdbc.update("UPDATE data_export_task SET task_status=1,error_msg=NULL,finished_at=NULL WHERE id=?",id);
        try {
            long user=Long.parseLong(task.userId());
            var glucose=jdbc.queryForList("SELECT record_date,node_code,glucose_value,source_type,recorded_at,remark FROM glucose_record WHERE user_id=? AND is_deleted=0 AND record_date>=? AND record_date<=? ORDER BY record_date,node_code,id LIMIT 20001",user,task.startDate(),task.endDate());
            var meals=jdbc.queryForList("SELECT record_date,node_code,foods_text,carbs_g,hunger_level,supplement_text,recorded_at FROM meal_record WHERE user_id=? AND is_deleted=0 AND record_date>=? AND record_date<=? ORDER BY record_date,node_code,id LIMIT 20001",user,task.startDate(),task.endDate());
            if(glucose.size()>20000||meals.size()>20000)throw new BizException(ErrorCode.BAD_REQUEST,"记录超过单次20000条，请缩小日期范围");
            byte[] bytes=ExportPdf.create(task,glucose,meals);
            jdbc.update("DELETE FROM data_export_file WHERE task_id=?",id);jdbc.update("INSERT INTO data_export_file(task_id,file_bytes,glucose_count,meal_count) VALUES(?,?,?,?)",id,bytes,glucose.size(),meals.size());
            jdbc.update("UPDATE data_export_task SET task_status=2,file_url=NULL,summary_text=?,finished_at=CURRENT_TIMESTAMP,error_msg=NULL WHERE id=?","血糖记录 "+glucose.size()+" 条，饮食记录 "+meals.size()+" 条",id);
        } catch(Exception ex) {String message=ex instanceof BizException?ex.getMessage():"文件生成失败，请稍后重试";jdbc.update("UPDATE data_export_task SET task_status=3,error_msg=?,finished_at=CURRENT_TIMESTAMP WHERE id=?",message,id);}
        return get(viewer,id);
    }
    public byte[] file(Long viewer,long id){var task=get(viewer,id);if(!task.status().equals("done"))throw new BizException(ErrorCode.CONFLICT,"导出文件尚未生成，请稍后再试");var bytes=jdbc.queryForList("SELECT file_bytes FROM data_export_file WHERE task_id=?",byte[].class,id);if(bytes.isEmpty())throw missing();byte[] stored=bytes.getFirst();if(stored.length>=5&&stored[0]=='%'&&stored[1]=='P'&&stored[2]=='D'&&stored[3]=='F'&&stored[4]=='-')return stored;
        // Convert completed legacy workbooks from their saved snapshot, never from current health records.
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(stored))){return ExportPdf.create(task,legacy(book,"血糖记录",new String[]{"record_date","node_code","glucose_value","source_type","recorded_at","remark"}),legacy(book,"饮食记录",new String[]{"record_date","node_code","foods_text","carbs_g","hunger_level","supplement_text","recorded_at"}));}
        catch(Exception ex){throw new BizException(ErrorCode.INTERNAL_ERROR,"历史导出文件暂不可用，请重新发起导出");}
    }
    private List<Map<String,Object>> legacy(XSSFWorkbook book,String name,String[] keys){var sheet=book.getSheet(name);if(sheet==null||sheet.getLastRowNum()>20000)throw new IllegalArgumentException("Invalid export snapshot");var rows=new ArrayList<Map<String,Object>>();var formatter=new org.apache.poi.ss.usermodel.DataFormatter(Locale.ROOT);for(int at=1;at<=sheet.getLastRowNum();at++){var row=sheet.getRow(at);if(row==null)continue;var result=new LinkedHashMap<String,Object>();for(int col=0;col<keys.length;col++)result.put(keys[col],formatter.formatCellValue(row.getCell(col)));rows.add(result);}return rows;}
}
