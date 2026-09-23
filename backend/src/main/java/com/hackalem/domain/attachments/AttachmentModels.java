package com.hackalem.domain.attachments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Decimal quantities and identifiers cross the HTTP boundary as strings. */
public final class AttachmentModels {
    private AttachmentModels() {}
    public record WordBox(String text, int x, int y, int width, int height, int imageWidth, int imageHeight, double confidence, String lineId) {}
    public record Location(String kind, String sheet, Integer row, String cell, Integer page, Integer paragraph, List<WordBox> words) {
        public Location(String kind,String sheet,Integer row,String cell,Integer page,Integer paragraph){this(kind,sheet,row,cell,page,paragraph,List.of());}
    }
    public record VisualEvidence(String category, List<String> visibleMarkings, Map<String,String> observedAttributes, List<String> qualityFlags) {}
    public record ExtractedRow(String id, String rawText, String article, String quantity, String unit,
                               Location source, List<String> warnings, VisualEvidence visualEvidence) {
        public ExtractedRow(String id,String rawText,String article,String quantity,String unit,Location source,List<String> warnings){this(id,rawText,article,quantity,unit,source,warnings,null);}
    }
    public record Extraction(List<ExtractedRow> rows, List<String> warnings) {}
    public record Candidate(String productId, String article, String name, String unit, String evidence, String minimum, String step, List<String> warehouses) {}
    public record MatchedRow(ExtractedRow extracted, String status, List<Candidate> candidates) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="AttachmentSelection")
    public record Selection(@NotBlank String rowId, @NotBlank String productId,
                            @NotBlank @Pattern(regexp="[0-9]+(?:\\.[0-9]{1,6})?") String quantity,
                            @NotBlank String unit, @NotBlank String warehouse, boolean selected) {}
    public record ReprocessRequest(@NotBlank @Pattern(regexp="[1-9][0-9]*") String expectedVersion) {}
    public record ReviewRequest(@NotBlank @Pattern(regexp="[1-9][0-9]*") String expectedVersion, @NotNull @Size(max=1000) List<@Valid Selection> selections) {}
    public record Snapshot(String attachmentId, String conversationId, String version, String status, String stage,
                           String filename, String mimeType, List<MatchedRow> rows,
                           List<Selection> selections, List<String> warnings, String errorCode) {}
    public record Accepted(String attachmentId, String jobId, String version, String status, boolean deduplicated) {}
    public record ReviewedItems(String attachmentId, long version, List<Selection> items) {}
    public record Product(String id, String article, String name, String unit, BigDecimal minimum,
                          BigDecimal step, Map<String,String> attributes, List<String> warehouses) {}
    public record Capabilities(List<String> extensions, int maxBytes, int maxRows, int maxPages,
                               long maxImagePixels, boolean ocrAvailable, boolean visionAvailable,
                               boolean conversationAccessConfigured) {}
    public record Stored(String id, String owner, String conversationId, long version, int desiredVersion,
                         String status, String stage, String filename, String mime, String extension,
                         byte[] bytes, String warnings, String error, boolean deleted) {}
    public record Job(String id, String attachmentId, int version, long epoch, int attempts) {}
}
