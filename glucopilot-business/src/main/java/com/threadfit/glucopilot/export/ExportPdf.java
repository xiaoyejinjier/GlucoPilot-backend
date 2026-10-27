package com.threadfit.glucopilot.export;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** A fixed snapshot of actual records. No AI, thresholds or invented record values. */
final class ExportPdf implements AutoCloseable {
    private static final float MARGIN=36,LINE=14,FONT=9;
    private static final PDColor BLUE=color(9,105,218),INK=color(31,35,40),MUTED=color(89,99,110),PALE=color(234,243,255),BORDER=color(209,217,224);
    private final PDDocument document=new PDDocument();
    private final PDType0Font font;
    private final DataExportService.Task task;
    private PDPageContentStream stream;
    private float y;
    private String section="";
    private String[] headers;
    private float[] widths;
    private final Map<Integer,Boolean> glyphs=new HashMap<>();
    private ExportPdf(DataExportService.Task task)throws IOException {
        this.task=task;
        try(var input=ExportPdf.class.getResourceAsStream("/fonts/GlucoPilotSans-Regular.ttf")){
            if(input==null)throw new IOException("Export font unavailable");font=PDType0Font.load(document,input,true);
        }
    }
    static byte[] create(DataExportService.Task task,List<Map<String,Object>> glucose,List<Map<String,Object>> meals)throws IOException {
        try(var pdf=new ExportPdf(task);var output=new ByteArrayOutputStream()){
            pdf.section="记录概览";pdf.page();
            pdf.text("血糖记录 "+glucose.size()+" 条     饮食记录 "+meals.size()+" 条",MARGIN,pdf.y,12,INK);pdf.y-=28;
            pdf.text("按日期范围导出本人记录；空白表示该字段未填写。",MARGIN,pdf.y,9,MUTED);pdf.y-=28;
            pdf.table("血糖记录",new String[]{"日期","时段","血糖 mmol/L","来源","记录时间","备注"},new float[]{70,85,78,55,116,365},new String[]{"record_date","node_code","glucose_value","source_type","recorded_at","remark"},glucose);
            pdf.y-=20;
            pdf.table("饮食记录",new String[]{"日期","餐点","食物","碳水 g","饥饱程度","补充信息","记录时间"},new float[]{70,70,215,60,60,178,116},new String[]{"record_date","node_code","foods_text","carbs_g","hunger_level","supplement_text","recorded_at"},meals);
            pdf.stream.close();pdf.stream=null;
            for(int i=0;i<pdf.document.getNumberOfPages();i++){
                var page=pdf.document.getPage(i);
                try(var footer=new PDPageContentStream(pdf.document,page,PDPageContentStream.AppendMode.APPEND,true,true)){
                    footer.setNonStrokingColor(MUTED);footer.beginText();footer.setFont(pdf.font,8);footer.newLineAtOffset(MARGIN,20);footer.showText("个人健康记录 · 第 "+(i+1)+" / "+pdf.document.getNumberOfPages()+" 页");footer.endText();
                }
            }
            pdf.document.getDocumentInformation().setTitle("控糖健康记录");pdf.document.getDocumentInformation().setAuthor("GlucoPilot");pdf.document.save(output);return output.toByteArray();
        }
    }
    private void page()throws IOException {
        if(stream!=null)stream.close();var page=new PDPage(PDRectangle.A4);page.setRotation(0);
        // Landscape gives long food names and notes a readable column without reducing the font.
        page.setMediaBox(new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()));document.addPage(page);
        stream=new PDPageContentStream(document,page);float top=page.getMediaBox().getHeight();y=top-MARGIN;
        text("控糖健康记录",MARGIN,y,20,BLUE);y-=24;
        String name=task.userName();if(name.codePointCount(0,name.length())>30)name=name.substring(0,name.offsetByCodePoints(0,30))+"…";
        for(String line:wrap("用户："+name+"     日期："+task.startDate()+" 至 "+task.endDate(),770)){text(line,MARGIN,y,9,INK);y-=LINE;}y-=4;
        text("任务 #"+task.id()+"   ·   "+section,MARGIN,y,9,MUTED);y-=22;
    }
    private void table(String title,String[] labels,float[] columns,String[] keys,List<Map<String,Object>> rows)throws IOException {
        section=title;headers=labels;widths=columns;
        if(y<115)page();
        text(title,MARGIN,y,12,BLUE);y-=20;header();
        if(rows.isEmpty()){text("所选日期内暂无"+title+"。",MARGIN+8,y-14,10,MUTED);y-=38;return;}
        int index=0;
        for(var row:rows){
            var cells=new ArrayList<List<String>>();int lines=1;
            for(int i=0;i<keys.length;i++){var wrapped=wrap(value(keys[i],row.get(keys[i])),widths[i]-12);cells.add(wrapped);lines=Math.max(lines,wrapped.size());}
            int offset=0;
            while(offset<lines){
                if(y<70){page();header();}
                int count=Math.min(lines-offset,Math.max(1,(int)((y-42-12)/LINE)));float height=count*LINE+12;
                if(index%2==0){stream.setNonStrokingColor(color(246,248,250));stream.addRect(MARGIN,y-height,totalWidth(),height);stream.fill();}
                float x=MARGIN;
                for(int c=0;c<cells.size();c++){
                    var cell=cells.get(c);for(int at=0;at<count&&offset+at<cell.size();at++)text(cell.get(offset+at),x+6,y-16-at*LINE,FONT,INK);x+=widths[c];
                }
                stream.setStrokingColor(BORDER);stream.setLineWidth(.4f);stream.moveTo(MARGIN,y-height);stream.lineTo(MARGIN+totalWidth(),y-height);stream.stroke();y-=height;offset+=count;
            }
            index++;
        }
    }
    private void header()throws IOException {
        stream.setNonStrokingColor(PALE);stream.addRect(MARGIN,y-27,totalWidth(),27);stream.fill();float x=MARGIN;
        for(int i=0;i<headers.length;i++){text(headers[i],x+6,y-17,9,BLUE);x+=widths[i];}y-=27;
    }
    private float totalWidth(){float sum=0;for(float width:widths)sum+=width;return sum;}
    private String safe(String input){
        var out=new StringBuilder();input.codePoints().forEach(cp->{
            if(cp=='\n'){out.append('\n');return;}if(cp=='\r')return;if(cp=='\t'){out.append("  ");return;}
            if(Character.isISOControl(cp))return;
            boolean available=glyphs.computeIfAbsent(cp,key->{try{font.encode(new String(Character.toChars(key)));return true;}catch(IOException|IllegalArgumentException ex){return false;}});
            out.append(available?new String(Character.toChars(cp)):"[U+"+Integer.toHexString(cp).toUpperCase(Locale.ROOT)+"]");
        });return out.toString();
    }
    private List<String> wrap(String input,float width)throws IOException {
        var result=new ArrayList<String>();var line=new StringBuilder();float used=0;
        for(int cp:safe(input).codePoints().toArray()){
            if(cp=='\n'){result.add(line.toString());line.setLength(0);used=0;continue;}
            String ch=new String(Character.toChars(cp));float size=font.getStringWidth(ch)/1000*FONT;
            if(used+size>width&&line.length()>0){result.add(line.toString());line.setLength(0);used=0;}line.append(ch);used+=size;
        }
        result.add(line.toString());return result;
    }
    private void text(String value,float x,float at,float size,PDColor color)throws IOException {
        stream.setNonStrokingColor(color);stream.beginText();stream.setFont(font,size);stream.newLineAtOffset(x,at);stream.showText(safe(value).replace('\n',' '));stream.endText();
    }
    private static String value(String key,Object raw){
        if(raw==null)return "";
        if(key.equals("node_code"))return Map.ofEntries(Map.entry("fasting","空腹"),Map.entry("before_breakfast","早餐前"),Map.entry("breakfast","早餐"),Map.entry("after_breakfast","早餐后"),Map.entry("before_lunch","午餐前"),Map.entry("lunch","午餐"),Map.entry("after_lunch","午餐后"),Map.entry("before_dinner","晚餐前"),Map.entry("dinner","晚餐"),Map.entry("after_dinner","晚餐后"),Map.entry("before_sleep","睡前")).getOrDefault(raw.toString(),raw.toString());
        if(key.equals("source_type"))return Map.of("1","手动","2","OCR","3","设备").getOrDefault(raw.toString(),raw.toString());
        if(raw instanceof LocalDateTime date)return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        if(raw instanceof java.sql.Timestamp date)return date.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        return raw.toString();
    }
    private static PDColor color(int r,int g,int b){return new PDColor(new float[]{r/255f,g/255f,b/255f},PDDeviceRGB.INSTANCE);}
    @Override public void close()throws IOException{try{if(stream!=null)stream.close();}finally{document.close();}}
}
