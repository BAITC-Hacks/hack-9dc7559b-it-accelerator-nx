package com.hackalem.ai.attachments;

import com.hackalem.domain.attachments.AttachmentException;
import com.hackalem.domain.attachments.AttachmentLimits;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

@Component
public class AttachmentValidator {
    public static final Map<String,String> MIMES=Map.of(
        "xls","application/vnd.ms-excel", "xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "doc","application/msword", "docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "pdf","application/pdf", "jpg","image/jpeg", "jpeg","image/jpeg");
    public record Validated(String filename,String extension,String mime,byte[] bytes) {}
    public Validated validate(String filename,String mime,byte[] bytes) {
        if(bytes.length==0) throw AttachmentException.invalid("EMPTY_FILE");
        if(bytes.length>AttachmentLimits.BYTES) throw new AttachmentException(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE");
        String safe=filename==null?"":filename.replace('\\','/');
        safe=safe.substring(safe.lastIndexOf('/')+1).replaceAll("[\\p{Cntrl}]", "_");
        if(safe.isBlank() || safe.length()>255 || !safe.contains(".")) throw AttachmentException.invalid("INVALID_FILENAME");
        String ext=safe.substring(safe.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        String expected=MIMES.get(ext);
        if(expected==null) throw new AttachmentException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_EXTENSION");
        String declared=mime==null?"":mime.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if(!expected.equals(declared)) throw new AttachmentException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,"MIME_MISMATCH");
        try {
            switch(ext) {
                case "pdf" -> { if(!new String(bytes,0,Math.min(5,bytes.length),StandardCharsets.US_ASCII).equals("%PDF-")) throw AttachmentException.invalid("SIGNATURE_MISMATCH"); }
                case "jpg","jpeg" -> validateJpeg(bytes);
                case "xlsx","docx" -> validateZip(bytes,ext);
                case "xls","doc" -> {
                    try(var fs=new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
                        boolean valid=ext.equals("xls") ? fs.getRoot().hasEntry("Workbook")||fs.getRoot().hasEntry("Book") : fs.getRoot().hasEntry("WordDocument");
                        if(!valid) throw AttachmentException.invalid("SIGNATURE_MISMATCH");
                    }
                }
                default -> throw new AttachmentException(org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE,"UNSUPPORTED_EXTENSION");
            }
        } catch(AttachmentException e) { throw e; }
        catch(Exception e) { throw AttachmentException.invalid("CORRUPT_OR_ENCRYPTED_FILE"); }
        return new Validated(safe,ext,expected,bytes);
    }
    private void validateZip(byte[] bytes,String ext) throws IOException {
        boolean correct=false, contentTypes=false; int entries=0; long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                if(++entries>2000) throw AttachmentException.invalid("ZIP_ENTRY_LIMIT");
                String name=entry.getName();
                if(name.equals("[Content_Types].xml")) contentTypes=true;
                if(name.equals(ext.equals("xlsx")?"xl/workbook.xml":"word/document.xml")) correct=true;
                if(name.toLowerCase(Locale.ROOT).endsWith("vbaproject.bin")) throw AttachmentException.invalid("MACROS_NOT_SUPPORTED");
                byte[] buffer=new byte[8192]; int n;
                while((n=zip.read(buffer))!=-1) {
                    total+=n;
                    if(total>30L*1024*1024 || total>Math.max(bytes.length*100L,1024*1024)) throw AttachmentException.invalid("ZIP_EXPANSION_LIMIT");
                }
            }
        }
        if(!correct||!contentTypes) throw AttachmentException.invalid("SIGNATURE_MISMATCH");
    }
    public static void validateJpeg(byte[] bytes) throws IOException {
        if(bytes.length<3 || (bytes[0]&255)!=255 || (bytes[1]&255)!=216 || (bytes[2]&255)!=255) throw AttachmentException.invalid("SIGNATURE_MISMATCH");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw AttachmentException.invalid("CORRUPT_IMAGE");
            var reader=readers.next();
            try {
                reader.setInput(input);
                long width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0||height<=0||width*height>AttachmentLimits.PIXELS) throw AttachmentException.invalid("IMAGE_PIXEL_LIMIT");
            } finally {reader.dispose();}
        }
    }
}
