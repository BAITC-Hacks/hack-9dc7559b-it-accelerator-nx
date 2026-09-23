package com.hackalem.ai.attachments;
import com.hackalem.domain.attachments.AttachmentModels.WordBox;
import java.util.List;
public interface OcrGateway {
    record Result(String text,List<WordBox> words,int clockwiseRotationDegrees) {}
    boolean available();
    String recognize(byte[] image, long deadlineEpochMillis);
    default Result recognizeWithRegions(byte[] image,long deadlineEpochMillis){return new Result(recognize(image,deadlineEpochMillis),List.of(),0);}
}
