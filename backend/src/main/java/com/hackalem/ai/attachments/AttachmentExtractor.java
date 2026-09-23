package com.hackalem.ai.attachments;

import com.hackalem.domain.attachments.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.*;
import java.util.*;
import static com.hackalem.domain.attachments.AttachmentModels.*;

@Component
public class AttachmentExtractor {
    private final ObjectProvider<OcrGateway> ocr;
    private final ObjectProvider<VisionGateway> vision;
    public AttachmentExtractor(ObjectProvider<OcrGateway> ocr,ObjectProvider<VisionGateway> vision) {
        this.ocr=ocr;this.vision=vision;
        ZipSecureFile.setMaxEntrySize(30L*1024*1024);
        ZipSecureFile.setMaxTextSize(AttachmentLimits.TEXT);
    }
    public boolean ocrAvailable() {var p=ocr.getIfAvailable();return p!=null&&p.available();}
    public boolean visionAvailable() {var p=vision.getIfAvailable();return p!=null&&p.available();}
    public Extraction extract(byte[] bytes,String extension) {
        long deadline=System.currentTimeMillis()+AttachmentLimits.DEADLINE_MILLIS;
        DocumentRows out=new DocumentRows(); List<String> warnings=new ArrayList<>();
        try {
            switch(extension) {
                case "xls","xlsx" -> excel(bytes,out,deadline);
                case "docx" -> docx(bytes,out,deadline);
                case "doc" -> doc(bytes,out,deadline);
                case "pdf" -> pdf(bytes,out,warnings,deadline);
                case "jpg","jpeg" -> photo(bytes,out,warnings,deadline);
                default -> throw AttachmentException.invalid("UNSUPPORTED_EXTENSION");
            }
            check(deadline);
            if(out.rows().isEmpty()&&warnings.isEmpty()) throw AttachmentException.invalid("NO_PRODUCT_ROWS");
            return new Extraction(out.rows(),List.copyOf(warnings));
        } catch(AttachmentException e) {throw e;}
        catch(org.apache.poi.EncryptedDocumentException | org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {throw AttachmentException.invalid("ENCRYPTED_DOCUMENT");}
        catch(Exception e) {throw AttachmentException.invalid("PARSER_FAILED");}
    }
    private void excel(byte[] bytes,DocumentRows out,long deadline) throws IOException {
        try(Workbook book=WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter format=new DataFormatter(Locale.ROOT);format.setUseCachedValuesForFormulaCells(true);
            if(book.getNumberOfSheets()>20) throw AttachmentException.invalid("SHEET_LIMIT");
            for(Sheet sheet:book) {
                out.resetHeader();
                if(sheet.getLastRowNum()>AttachmentLimits.ROWS+50) throw AttachmentException.invalid("ROW_LIMIT");
                for(Row row:sheet) {
                    check(deadline);
                    if(row.getLastCellNum()>100) throw AttachmentException.invalid("COLUMN_LIMIT");
                    List<String> cells=new ArrayList<>();boolean formula=false;
                    for(int i=0;i<Math.max(row.getLastCellNum(),0);i++) {
                        Cell cell=row.getCell(i); cells.add(cell==null?"":format.formatCellValue(cell));
                        formula|=cell!=null&&cell.getCellType()==CellType.FORMULA;
                    }
                    out.add(cells,new Location("sheet",sheet.getSheetName(),row.getRowNum()+1,"A"+(row.getRowNum()+1),null,null),formula);
                }
            }
        }
    }
    private void docx(byte[] bytes,DocumentRows out,long deadline) throws IOException {
        try(var document=new XWPFDocument(new ByteArrayInputStream(bytes))) {
            int paragraph=0,table=0;
            for(var body:document.getBodyElements()) {
                check(deadline);
                if(body instanceof XWPFParagraph p) out.text(p.getText(),new Location("paragraph",null,null,null,null,++paragraph));
                else if(body instanceof XWPFTable t) {
                    out.resetHeader();int row=0;table++;
                    for(var r:t.getRows()) {
                        check(deadline);
                        out.add(r.getTableCells().stream().map(XWPFTableCell::getText).toList(),new Location("table","table-"+table,++row,null,null,null),false);
                    }
                }
            }
        }
    }
    private void doc(byte[] bytes,DocumentRows out,long deadline) throws IOException {
        try(var document=new HWPFDocument(new ByteArrayInputStream(bytes))) {
            var range=document.getRange();int tableNumber=0,tableEnd=-1;
            for(int p=0;p<range.numParagraphs();p++) {
                check(deadline);var paragraph=range.getParagraph(p);
                if(paragraph.getStartOffset()<tableEnd)continue;
                if(!paragraph.isInTable()) {out.text(paragraph.text().strip(),new Location("paragraph",null,null,null,null,p+1));continue;}
                // Range.getTable handles a table that ends at EOF (TableIterator loses it).
                var table=range.getTable(paragraph);tableEnd=table.getEndOffset();tableNumber++;out.resetHeader();
                for(int row=0;row<table.numRows();row++) {
                    check(deadline);var r=table.getRow(row);List<String> cells=new ArrayList<>();
                    for(int c=0;c<r.numCells();c++)cells.add(r.getCell(c).text().replace("\u0007","").strip());
                    out.add(cells,new Location("table","table-"+tableNumber,row+1,null,null,null),false);
                }
            }
        }
    }
    private void pdf(byte[] bytes,DocumentRows out,List<String> warnings,long deadline) throws IOException {
        try(var pdf=Loader.loadPDF(bytes)) {
            if(pdf.getNumberOfPages()>AttachmentLimits.PAGES) throw AttachmentException.invalid("PDF_PAGE_LIMIT");
            var stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            for(int p=1;p<=pdf.getNumberOfPages();p++) {
                check(deadline);stripper.setStartPage(p);stripper.setEndPage(p);
                String text=stripper.getText(pdf);List<WordBox> boxes=List.of();
                if(text.strip().length()<8) {
                    if(!ocrAvailable()) {warnings.add("OCR_UNAVAILABLE_PAGE_"+p);continue;}
                    var box=pdf.getPage(p-1).getCropBox();
                    if(box.getWidth()*box.getHeight()*4d>AttachmentLimits.PIXELS) throw AttachmentException.invalid("IMAGE_PIXEL_LIMIT");
                    var rendered=new PDFRenderer(pdf).renderImageWithDPI(p-1,144);
                    try(var buffer=new ByteArrayOutputStream()) {
                        ImageIO.write(rendered,"png",buffer);
                        var recognized=ocr.getObject().recognizeWithRegions(buffer.toByteArray(),deadline);text=recognized.text();boxes=recognized.words();
                        warnings.add("OCR_TEXT_REQUIRES_REVIEW_PAGE_"+p);
                    } finally {rendered.flush();}
                }
                out.text(text,new Location("page",null,null,null,p,null,boxes));
            }
        }
    }
    private void photo(byte[] bytes,DocumentRows out,List<String> warnings,long deadline) throws IOException {
        AttachmentValidator.validateJpeg(bytes);
        String text="";List<WordBox> boxes=List.of();
        if(ocrAvailable()) {var recognized=ocr.getObject().recognizeWithRegions(bytes,deadline);text=recognized.text();boxes=recognized.words();warnings.add("OCR_TEXT_REQUIRES_REVIEW");}
        else warnings.add("OCR_UNAVAILABLE");
        check(deadline);
        boolean usable=text!=null&&text.strip().length()>=4&&(boxes.isEmpty()||boxes.stream().anyMatch(word->word.confidence()>=40&&word.text().matches(".*[\\p{L}0-9]{3,}.*")));
        if(usable) out.text(text,new Location("image",null,null,null,1,null,boxes));
        else if(visionAvailable()) {
            var observation=vision.getObject().inspect(bytes,deadline);
            warnings.addAll(observation.qualityFlags());warnings.add("VISUAL_CANDIDATES_REQUIRE_REVIEW");
            String observed=observation.rawText()==null?"":observation.rawText();
            if(observed.isBlank()) observed=String.join(" ",observation.visibleMarkings());
            if(observed.isBlank()) observed=Objects.toString(observation.category(),"Unidentified product");
            out.text(observed,new Location("image",null,null,null,1,null));
            out.visual(new VisualEvidence(observation.category(),observation.visibleMarkings(),observation.observedAttributes(),observation.qualityFlags()));
        } else warnings.add("VISION_UNAVAILABLE");
    }
    private static void check(long deadline) {
        if(Thread.currentThread().isInterrupted()||System.currentTimeMillis()>deadline) throw AttachmentException.invalid("PROCESSING_DEADLINE");
    }
}
