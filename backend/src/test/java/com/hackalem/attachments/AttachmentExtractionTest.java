package com.hackalem.attachments;
import com.hackalem.ai.attachments.*;
import com.hackalem.domain.attachments.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.nio.file.*;
import java.io.*;
import static org.assertj.core.api.Assertions.*;
class AttachmentExtractionTest {
    private final AttachmentValidator validator=new AttachmentValidator();
    static AttachmentExtractor extractor(){var beans=new DefaultListableBeanFactory();return new AttachmentExtractor(beans.getBeanProvider(OcrGateway.class),beans.getBeanProvider(VisionGateway.class));}
    @ParameterizedTest @ValueSource(strings={"xls","xlsx","doc","docx"})
    void fourRealOfficeFixturesPreserveLeadingZerosFractionAndUnknownQuantity(String extension)throws Exception{
        byte[] bytes=Files.readAllBytes(Path.of("../data/attachments/office/specification."+extension));
        validator.validate("specification."+extension,AttachmentValidator.MIMES.get(extension),bytes);
        var rows=extractor().extract(bytes,extension).rows();
        assertThat(rows).anySatisfy(row->{assertThat(row.article()).isEqualTo("000001");assertThat(row.quantity()).isEqualTo("20");assertThat(row.unit()).isEqualTo("pcs");assertThat(row.source()).isNotNull();});
        assertThat(rows).anySatisfy(row->{assertThat(row.article()).isEqualTo("000013");assertThat(row.quantity()).isEqualTo("2.5");});
        assertThat(rows).anySatisfy(row->{assertThat(row.article()).isEqualTo("999999");assertThat(row.quantity()).isNull();assertThat(row.warnings()).contains("QUANTITY_REQUIRED");});
    }
    @Test void textPdfAndScannedPdfHaveHonestOutcomes()throws Exception{
        var text=extractor().extract(Files.readAllBytes(Path.of("../data/attachments/pdf/text.pdf")),"pdf");
        assertThat(text.rows()).anySatisfy(row->assertThat(row.article()).isEqualTo("000001"));
        var scan=extractor().extract(Files.readAllBytes(Path.of("../data/attachments/pdf/scan.pdf")),"pdf");
        assertThat(scan.warnings()).contains("OCR_UNAVAILABLE_PAGE_1");assertThat(scan.rows()).isEmpty();
        var mixed=extractor().extract(Files.readAllBytes(Path.of("../data/attachments/pdf/mixed.pdf")),"pdf");
        assertThat(mixed.rows()).isNotEmpty();assertThat(mixed.warnings()).contains("OCR_UNAVAILABLE_PAGE_2");
    }
    @Test void corruptSpoofedAndOversizedInputsAreRejected()throws Exception{
        assertThatThrownBy(()->validator.validate("bad.jpg","image/jpeg",Files.readAllBytes(Path.of("../data/attachments/invalid/renamed-executable.jpg")))).isInstanceOf(AttachmentException.class);
        assertThatThrownBy(()->extractor().extract(Files.readAllBytes(Path.of("../data/attachments/invalid/corrupt.pdf")),"pdf")).isInstanceOf(AttachmentException.class);
        assertThatThrownBy(()->validator.validate("file.pdf","image/jpeg","%PDF-1".getBytes())).hasMessage("MIME_MISMATCH");
        assertThatThrownBy(()->validator.validate("file.pdf","application/pdf",new byte[AttachmentLimits.BYTES+1])).hasMessage("FILE_TOO_LARGE");
    }
    @Test void photoWithoutProvidersNeverLooksSuccessfullyRecognized()throws Exception{
        var result=extractor().extract(Files.readAllBytes(Path.of("../data/attachments/images/product-only.jpg")),"jpg");
        assertThat(result.rows()).isEmpty();assertThat(result.warnings()).contains("OCR_UNAVAILABLE","VISION_UNAVAILABLE");
    }
    @Test void priceIsNotQuantityAndCachedFormulaRequiresReview()throws Exception{
        try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()){
            var sheet=book.createSheet();var header=sheet.createRow(0);header.createCell(0).setCellValue("Article");header.createCell(1).setCellValue("Price");header.createCell(2).setCellValue("Quantity");header.createCell(3).setCellValue("Unit");
            var row=sheet.createRow(1);row.createCell(0).setCellValue("00123");row.createCell(1).setCellValue(999);row.createCell(2).setCellFormula("1+1");row.createCell(3).setCellValue("pcs");book.write(bytes);
            var extracted=extractor().extract(bytes.toByteArray(),"xlsx").rows().getFirst();assertThat(extracted.article()).isEqualTo("00123");assertThat(extracted.quantity()).isNull();assertThat(extracted.warnings()).contains("CACHED_FORMULA_REQUIRES_REVIEW");
        }
    }
    @Test void realTesseractReadsSyntheticLabelWhenRuntimeIsPresent()throws Exception{
        String executable=System.getenv().getOrDefault("TEST_TESSERACT","/opt/homebrew/bin/tesseract");
        Assumptions.assumeTrue(Files.isExecutable(Path.of(executable)),"OCR runtime absent; container/live gate remains pending");
        var ocr=new TesseractOcrGateway(true,executable,"eng");
        String text=ocr.recognize(Files.readAllBytes(Path.of("../data/attachments/images/label.jpg")),System.currentTimeMillis()+20_000);
        assertThat(text).contains("000001","000013");
    }
    @ParameterizedTest @ValueSource(strings={"images/label.jpg","images/rotated.jpg","pdf/scan.pdf","pdf/mixed.pdf"})
    void realOcrFixturesRemainReviewable(String filename)throws Exception{
        String executable=System.getenv().getOrDefault("TEST_TESSERACT","/opt/homebrew/bin/tesseract");
        Assumptions.assumeTrue(Files.isExecutable(Path.of(executable)),"OCR runtime absent");
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("ocr",new TesseractOcrGateway(true,executable,"eng"));
        var extractor=new AttachmentExtractor(beans.getBeanProvider(OcrGateway.class),beans.getBeanProvider(VisionGateway.class));
        String extension=filename.substring(filename.lastIndexOf('.')+1);
        var result=extractor.extract(Files.readAllBytes(Path.of("../data/attachments/"+filename)),extension);
        assertThat(result.warnings()).anyMatch(warning->warning.contains("REQUIRES_REVIEW"));
        assertThat(result.rows()).anySatisfy(row->{assertThat(row.article()).isEqualTo("000001");assertThat(row.source().words()).isNotEmpty();
            assertThat(row.source().words()).allSatisfy(word->{assertThat(word.x()).isGreaterThanOrEqualTo(0);assertThat(word.y()).isGreaterThanOrEqualTo(0);assertThat(word.x()+word.width()).isLessThanOrEqualTo(word.imageWidth());assertThat(word.y()+word.height()).isLessThanOrEqualTo(word.imageHeight());});});
    }

    @Test void productOnlyPhotoCallsVisionAndPreservesVisibleEvidence()throws Exception{
        var beans=new DefaultListableBeanFactory();var called=new java.util.concurrent.atomic.AtomicBoolean();
        beans.registerSingleton("ocr",new OcrGateway(){public boolean available(){return true;}public String recognize(byte[] bytes,long deadline){return "";}});
        beans.registerSingleton("vision",new VisionGateway(){public boolean available(){return true;}public Observation inspect(byte[] bytes,long deadline){called.set(true);return new Observation("","circuit breaker",java.util.List.of(),java.util.Map.of("visibleLevers","1"),java.util.List.of("RATING_NOT_VISIBLE"));}});
        var extractor=new AttachmentExtractor(beans.getBeanProvider(OcrGateway.class),beans.getBeanProvider(VisionGateway.class));
        var result=extractor.extract(Files.readAllBytes(Path.of("../data/attachments/images/product-only.jpg")),"jpg");
        assertThat(called).isTrue();assertThat(result.warnings()).contains("VISUAL_CANDIDATES_REQUIRE_REVIEW","RATING_NOT_VISIBLE");
        assertThat(result.rows()).anySatisfy(row->{assertThat(row.article()).isNull();assertThat(row.visualEvidence().category()).isEqualTo("circuit breaker");assertThat(row.visualEvidence().observedAttributes()).doesNotContainKey("currentA");});
    }

}
