package com.hackalem.ai.attachments;
import com.hackalem.domain.attachments.AttachmentException;
import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
/** Conservative pre-provider gate: near-absent high-frequency detail cannot support reading a label. */
public final class ImageQualityGate {
    private ImageQualityGate() {}
    public static boolean clearlyBlurred(byte[] jpeg)throws IOException{return laplacianVariance(jpeg)<8.0;}
    public static double laplacianVariance(byte[] jpeg)throws IOException{
        AttachmentValidator.validateJpeg(jpeg);BufferedImage input=ImageIO.read(new ByteArrayInputStream(jpeg));
        if(input==null)throw AttachmentException.invalid("CORRUPT_IMAGE");
        double scale=Math.min(1d,1024d/Math.max(input.getWidth(),input.getHeight()));
        int width=Math.max(3,(int)(input.getWidth()*scale)),height=Math.max(3,(int)(input.getHeight()*scale));
        BufferedImage small=new BufferedImage(width,height,BufferedImage.TYPE_BYTE_GRAY);
        try{
            var g=small.createGraphics();try{g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(input,0,0,width,height,null);}finally{g.dispose();}
            int[] pixels=small.getRaster().getSamples(0,0,width,height,0,(int[])null);double sum=0,squares=0;long count=0;
            for(int y=1;y<height-1;y++)for(int x=1;x<width-1;x++){
                int index=y*width+x;double value=4*pixels[index]-pixels[index-1]-pixels[index+1]-pixels[index-width]-pixels[index+width];
                sum+=value;squares+=value*value;count++;
            }
            return squares/count-Math.pow(sum/count,2);
        }finally{small.flush();input.flush();}
    }
}
