package com.hackalem.domain.catalog;

import com.hackalem.config.CatalogProperties;
import com.hackalem.domain.port.Contracts.TrustedScope;
import com.hackalem.web.ApiException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriUtils;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

@Service
public class CatalogCertificates {
    private final CatalogRepository catalog;private final CatalogProperties properties;private final ResourceLoader resources;
    public CatalogCertificates(CatalogRepository catalog,CatalogProperties properties,ResourceLoader resources){this.catalog=catalog;this.properties=properties;this.resources=resources;}
    public static String url(CatalogProduct product,ProductCertificate certificate){
        if(!product.synthetic()||!Boolean.TRUE.equals(certificate.synthetic()))return certificate.url();
        return "/api/products/"+UriUtils.encodePathSegment(product.article(),StandardCharsets.UTF_8)+"/certificates/"+UriUtils.encodePathSegment(certificate.id(),StandardCharsets.UTF_8);
    }
    public Resource read(String article,String certificateId,TrustedScope scope){
        CatalogOfferService.requireScope(scope);
        var product=catalog.findActiveByArticle(article).orElseThrow(ApiException::missing);
        var certificate=product.certificates().stream().filter(c->c.id().equals(certificateId)).findFirst().orElseThrow(ApiException::missing);
        // The synthetic fixture is intentionally public catalog data, but requires a verified visitor session.
        // Never resolve an arbitrary filesystem path or fetch a URL supplied by an import.
        if(!product.synthetic()||!Boolean.TRUE.equals(certificate.synthetic())
                ||!"data/sample_catalog/certificates/synthetic-certificate.pdf".equals(certificate.file()))throw ApiException.missing();
        try{
            var resource=resources.getResource(properties.seed().location()).createRelative("certificates/synthetic-certificate.pdf");
            if(!resource.exists()||!resource.isReadable())throw ApiException.missing();
            return resource;
        }catch(IOException e){throw ApiException.unavailable("certificate_source_unavailable");}
    }
}
