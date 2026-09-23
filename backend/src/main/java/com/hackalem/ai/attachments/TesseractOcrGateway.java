package com.hackalem.ai.attachments;
import com.hackalem.domain.attachments.*;
import com.hackalem.domain.attachments.AttachmentModels.WordBox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
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
    public String recognize(byte[] bytes,long deadline){return recognizeWithRegions(bytes,deadline).text();}
    public Result recognizeWithRegions(byte[] bytes,long deadline){
        if(!enabled)throw AttachmentException.invalid("OCR_UNAVAILABLE");Path directory=null;
        try{
            int[] size=dimensions(bytes);directory=Files.createTempDirectory("attachment-ocr-");Path input=directory.resolve("input.png");Files.write(input,bytes);
            Result best=run(input,directory.resolve("text"),deadline,size[0],size[1],0);
            if(quality(best)<64){
                BufferedImage image=ImageIO.read(new ByteArrayInputStream(bytes));
                if(image!=null)try{for(int turn=1;turn<=3&&System.currentTimeMillis()+1500<deadline;turn++){
                    BufferedImage rotated=rotate(image,turn);Path path=directory.resolve("rotation-"+turn+".png");
                    try{ImageIO.write(rotated,"png",path.toFile());}finally{rotated.flush();}
                    Result candidate=run(path,directory.resolve("text-"+turn),deadline,size[0],size[1],turn);
                    if(quality(candidate)>quality(best))best=candidate;if(quality(best)>=64)break;
                }}finally{image.flush();}
            }
            return best;
        }catch(AttachmentException e){throw e;}catch(IOException e){throw AttachmentException.invalid("OCR_RUNTIME_UNAVAILABLE");}
        finally{if(directory!=null)try(var paths=Files.walk(directory)){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}}
    }
    private Result run(Path input,Path output,long deadline,int width,int height,int quarter)throws IOException{
        long remaining=Math.min(10_000,deadline-System.currentTimeMillis());if(remaining<=0)throw AttachmentException.invalid("OCR_DEADLINE");
        Process process=new ProcessBuilder(executable,input.toString(),output.toString(),"-l",languages,"--psm","6","tsv","txt")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{if(!process.waitFor(remaining,TimeUnit.MILLISECONDS)){process.destroyForcibly();throw AttachmentException.invalid("OCR_DEADLINE");}
            if(process.exitValue()!=0)throw AttachmentException.invalid("OCR_FAILED");Path text=Path.of(output+".txt"),tsv=Path.of(output+".tsv");
            if(Files.size(text)>AttachmentLimits.TEXT*4L||Files.size(tsv)>4L*1024*1024)throw AttachmentException.invalid("TEXT_LIMIT");
            return new Result(Files.readString(text),parseTsv(Files.readString(tsv),width,height,quarter),quarter*90);
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw AttachmentException.invalid("OCR_INTERRUPTED");}finally{if(process.isAlive())process.destroyForcibly();}
    }
    /** TSV level 5 has word coordinates. Rotate them back to the original pixel frame. */
    static List<WordBox> parseTsv(String tsv,int width,int height,int quarter){
        List<WordBox> words=new ArrayList<>();
        for(String line:tsv.split("\\R")){
            String[] columns=line.split("\\t",12);if(columns.length<12||!columns[0].equals("5")||columns[11].isBlank())continue;
            if(words.size()>=10_000)throw AttachmentException.invalid("OCR_WORD_LIMIT");
            try{
                int x=Integer.parseInt(columns[6]),y=Integer.parseInt(columns[7]),w=Integer.parseInt(columns[8]),h=Integer.parseInt(columns[9]);double confidence=Double.parseDouble(columns[10]);
                if(x<0||y<0||w<=0||h<=0||!Double.isFinite(confidence))continue;
                int mappedX=x,mappedY=y,mappedW=w,mappedH=h;
                if(quarter==1){mappedX=y;mappedY=height-x-w;mappedW=h;mappedH=w;}
                else if(quarter==2){mappedX=width-x-w;mappedY=height-y-h;}
                else if(quarter==3){mappedX=width-y-h;mappedY=x;mappedW=h;mappedH=w;}
                if(mappedX<0||mappedY<0||mappedX+mappedW>width||mappedY+mappedH>height)continue;
                words.add(new WordBox(columns[11],mappedX,mappedY,mappedW,mappedH,width,height,confidence,String.join(":",columns[1],columns[2],columns[3],columns[4])));
            }catch(NumberFormatException ignored){}
        }
        return List.copyOf(words);
    }
    private static int[] dimensions(byte[] bytes)throws IOException{
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw AttachmentException.invalid("CORRUPT_IMAGE");var reader=readers.next();
            try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<=0||h<=0||(long)w*h>AttachmentLimits.PIXELS)throw AttachmentException.invalid("IMAGE_PIXEL_LIMIT");return new int[]{w,h};}finally{reader.dispose();}
        }
    }
    private static int quality(Result result){
        // OCR confidence ranks orientation candidates, never catalog correctness.
        return (int)result.words().stream().filter(w->w.text().matches("[\\p{L}0-9]{3,}")&&w.confidence()>=35).mapToInt(w->w.text().length()).sum();
    }
    private static BufferedImage rotate(BufferedImage image,int quarter){
        int w=image.getWidth(),h=image.getHeight();if((long)w*h>AttachmentLimits.PIXELS)throw AttachmentException.invalid("IMAGE_PIXEL_LIMIT");
        BufferedImage out=new BufferedImage(quarter%2==0?w:h,quarter%2==0?h:w,BufferedImage.TYPE_INT_RGB);var graphics=out.createGraphics();
        try{if(quarter==1){graphics.translate(h,0);graphics.rotate(Math.PI/2);}else if(quarter==2){graphics.translate(w,h);graphics.rotate(Math.PI);}else{graphics.translate(0,w);graphics.rotate(-Math.PI/2);}graphics.drawImage(image,0,0,null);}finally{graphics.dispose();}return out;
    }
}
