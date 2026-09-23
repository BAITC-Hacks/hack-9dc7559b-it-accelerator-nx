package com.hackalem.ai.attachments;
import com.hackalem.domain.attachments.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
/** Actual bounded local OCR. No shell, document logging, or user-controlled executable. */
@Component
public class TesseractOcrGateway implements OcrGateway {
    private final boolean enabled;private final String executable,languages;
    public TesseractOcrGateway(@Value("${app.attachments.ocr.enabled:false}")boolean enabled,@Value("${app.attachments.ocr.executable:tesseract}")String executable,@Value("${app.attachments.ocr.languages:eng+rus}")String languages){
        this.enabled=enabled;this.executable=executable;this.languages=languages;
        if(!languages.matches("[a-z_]+(?:\\+[a-z_]+)*"))throw new IllegalArgumentException("Invalid OCR language config");
    }
    public boolean available(){return enabled;}
    public String recognize(byte[] bytes,long deadline){
        if(!enabled)throw AttachmentException.invalid("OCR_UNAVAILABLE");Path directory=null;
        try{
            directory=Files.createTempDirectory("attachment-ocr-");Path input=directory.resolve("input.png");Files.write(input,bytes);
            String best=run(input,directory.resolve("text"),deadline);
            if(quality(best)<64){
                BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
                if(image!=null)try{for(int turn=1;turn<=3&&System.currentTimeMillis()+1500<deadline;turn++){
                    BufferedImage rotated=rotate(image,turn);Path path=directory.resolve("rotation-"+turn+".png");
                    try{ImageIO.write(rotated,"png",path.toFile());}finally{rotated.flush();}
                    String candidate=run(path,directory.resolve("text-"+turn),deadline);
                    if(quality(candidate)>quality(best))best=candidate;if(quality(best)>=64)break;
                }}finally{image.flush();}
            }
            return best;
        }catch(AttachmentException e){throw e;}catch(IOException e){throw AttachmentException.invalid("OCR_RUNTIME_UNAVAILABLE");}
        finally{if(directory!=null)try(var paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}}
    }
    private String run(Path input,Path output,long deadline)throws IOException{
        long remaining=Math.min(10_000,deadline-System.currentTimeMillis());if(remaining<=0)throw AttachmentException.invalid("OCR_DEADLINE");
        Process process=new ProcessBuilder(executable,input.toString(),output.toString(),"-l",languages,"--psm","6").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{if(!process.waitFor(remaining,TimeUnit.MILLISECONDS)){process.destroyForcibly();throw AttachmentException.invalid("OCR_DEADLINE");}
            if(process.exitValue()!=0)throw AttachmentException.invalid("OCR_FAILED");Path text=Path.of(output+".txt");
            if(Files.size(text)>AttachmentLimits.TEXT*4L)throw AttachmentException.invalid("TEXT_LIMIT");return Files.readString(text);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw AttachmentException.invalid("OCR_INTERRUPTED");}finally{if(process.isAlive())process.destroyForcibly();}
    }
    private static int quality(String text){var tokens=java.util.regex.Pattern.compile("[\\p{L}0-9]{3,}").matcher(text);int quality=0;while(tokens.find())quality+=tokens.group().length();return quality;}
    private static BufferedImage rotate(BufferedImage image,int quarter){
        int w=image.getWidth(),h=image.getHeight();if((long)w*h>AttachmentLimits.PIXELS)throw AttachmentException.invalid("IMAGE_PIXEL_LIMIT");
        BufferedImage out=new BufferedImage(quarter%2==0?w:h,quarter%2==0?h:w,BufferedImage.TYPE_INT_RGB);var graphics=out.createGraphics();
        try{if(quarter==1){graphics.translate(h,0);graphics.rotate(Math.PI/2);}else if(quarter==2){graphics.translate(w,h);graphics.rotate(Math.PI);}else{graphics.translate(0,w);graphics.rotate(-Math.PI/2);}graphics.drawImage(image,0,0,null);}finally{graphics.dispose();}return out;
    }
}
