package com.threadfit.glucopilot.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportPdfTest {
    @Test void chineseLongNotesAndPageBreaksPreserveEveryRecordAndEmbedFonts() throws Exception {
        var task=new DataExportService.Task("42","1","中文用户😀","done",LocalDate.of(2026,10,1),LocalDate.of(2026,10,23),LocalDateTime.now(),null,"",0,0);
        var glucose=new ArrayList<Map<String,Object>>();
        for(int i=0;i<70;i++)glucose.add(Map.of("record_date","2026-10-23","node_code","before_breakfast","glucose_value","5.2","source_type",1,"remark","唯一记录编号"+String.format("%03d",i)+" 保留真实备注"));
        String longNote="中文多行备注，需要正常换行。\n".repeat(90)+"长备注结束标记";
        var meals=List.<Map<String,Object>>of(Map.of("record_date","2026-10-23","node_code","lunch","foods_text","米饭、鸡蛋与蔬菜","carbs_g","32.5","supplement_text",longNote,"recorded_at",LocalDateTime.of(2026,10,23,12,30)));
        try(var pdf=Loader.loadPDF(ExportPdf.create(task,glucose,meals))){
            assertTrue(pdf.getNumberOfPages()>=5);var stripper=new PDFTextStripper();String all=stripper.getText(pdf);
            for(int i=0;i<70;i++)assertTrue(all.contains("唯一记录编号"+String.format("%03d",i)),"Missing record "+i);
            assertTrue(all.contains("[U+1F600]"));assertTrue(all.contains("长备注结束标记"));assertTrue(all.contains("32.5"));
            for(int i=0;i<pdf.getNumberOfPages();i++){
                stripper.setStartPage(i+1);stripper.setEndPage(i+1);String page=stripper.getText(pdf);assertTrue(page.contains("控糖健康记录"));assertTrue(page.contains("第 "+(i+1)+" / "+pdf.getNumberOfPages()+" 页"));
                for(var fontName:pdf.getPage(i).getResources().getFontNames())assertTrue(pdf.getPage(i).getResources().getFont(fontName).isEmbedded());
            }
        }
    }
}
