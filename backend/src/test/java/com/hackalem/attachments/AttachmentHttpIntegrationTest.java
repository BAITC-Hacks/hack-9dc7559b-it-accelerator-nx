package com.hackalem.attachments;
import com.hackalem.domain.Json;
import com.hackalem.domain.attachments.*;
import com.hackalem.domain.chat.ChatService;
import com.hackalem.domain.port.Contracts.*;
import com.hackalem.ai.attachments.AttachmentWorker;
import com.hackalem.security.SessionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"app.worker.enabled=false","app.attachments.worker-enabled=false","app.attachments.ocr.enabled=false","app.attachments.vision.enabled=false","app.limits.global-rpm=1000","app.limits.principal-rpm=1000"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Testcontainers @org.springframework.test.annotation.DirtiesContext
class AttachmentHttpIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> redis=new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("spring.data.redis.host",redis::getHost);r.add("spring.data.redis.port",()->redis.getMappedPort(6379));}
    @Autowired SessionService sessions;@Autowired ChatService chat;@Autowired AttachmentService attachments;@Autowired AttachmentRepository files;@Autowired AttachmentWorker worker;
    @Autowired JdbcTemplate db;@Autowired MockMvc mvc;@Autowired Json json;
    @MockitoBean AttachmentCatalog catalog;
    SessionToken tokenA,tokenB;TrustedScope a,b;UUID conversation;
    @BeforeEach void setup(){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT to_regclass('attachments') IS NOT NULL",Boolean.class)))new ResourceDatabasePopulator(new ClassPathResource("db/drafts/attachments.sql")).execute(Objects.requireNonNull(db.getDataSource()));
        db.execute("TRUNCATE visitor_sessions CASCADE");tokenA=sessions.create();tokenB=sessions.create();a=sessions.verify(tokenA.accessToken());b=sessions.verify(tokenB.accessToken());conversation=UUID.fromString(chat.create(a).id());
        var product=new AttachmentModels.Product("1001","000001","Synthetic product","pcs",BigDecimal.ONE,BigDecimal.ONE,Map.of(),List.of("main"));
        when(catalog.search(anyString(),anyString())).thenReturn(List.of());when(catalog.search(anyString(),eq("000001"))).thenReturn(List.of(product));when(catalog.product("1001")).thenReturn(Optional.of(product));
    }
    AttachmentModels.Accepted upload()throws Exception{
        var file=new MockMultipartFile("file","specification.xlsx","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",Files.readAllBytes(Path.of("../data/attachments/office/specification.xlsx")));
        String body=mvc.perform(multipart("/api/conversations/"+conversation+"/attachments").file(file).header("Authorization","Bearer "+tokenA.accessToken())).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();return json.read(body,AttachmentModels.Accepted.class);
    }
    @Test void authenticatedUploadIsDurablePrivateAndDeduplicated()throws Exception{
        var accepted=upload();assertThat(upload().attachmentId()).isEqualTo(accepted.attachmentId());
        assertThat(db.queryForObject("SELECT count(*) FROM attachment_jobs",Integer.class)).isEqualTo(1);
        for(String path:List.of("/api/attachments/"+accepted.attachmentId(),"/api/attachments/"+accepted.attachmentId()+"/source")){
            mvc.perform(get(path)).andExpect(status().isUnauthorized());mvc.perform(get(path).header("Authorization","Bearer "+tokenB.accessToken())).andExpect(status().isNotFound());
        }
        mvc.perform(delete("/api/attachments/"+accepted.attachmentId()).header("Authorization","Bearer "+tokenB.accessToken())).andExpect(status().isNotFound());
        var foreign=new MockMultipartFile("file","bad.pdf","application/pdf","%PDF-".getBytes());
        mvc.perform(multipart("/api/conversations/"+conversation+"/attachments").file(foreign).header("Authorization","Bearer "+tokenB.accessToken())).andExpect(status().isNotFound());
        assertThat(worker.processOne()).isTrue();var result=attachments.status(a,accepted.attachmentId());assertThat(result.rows()).isNotEmpty();assertThat(result.version()).isEqualTo("2");
    }
    @Test void reviewCasAndPortRequireExplicitValidSelectionAndNeverTouchCart()throws Exception{
        var accepted=upload();worker.processOne();var snapshot=attachments.status(a,accepted.attachmentId());
        var port=new ReviewedAttachmentPort(attachments);assertThat(port.getReviewedItems(accepted.attachmentId(),snapshot.version(),a).items()).isEmpty();
        String row=snapshot.rows().stream().filter(r->"000001".equals(r.extracted().article())).findFirst().orElseThrow().extracted().id();
        var selection=new AttachmentModels.Selection(row,"1001","3","pcs","main",true);var request=new AttachmentModels.ReviewRequest(snapshot.version(),List.of(selection));
        mvc.perform(post("/api/attachments/"+accepted.attachmentId()+"/review").header("Authorization","Bearer "+tokenB.accessToken()).contentType("application/json").content(json.write(request))).andExpect(status().isNotFound());
        mvc.perform(post("/api/attachments/"+accepted.attachmentId()+"/review").header("Authorization","Bearer "+tokenA.accessToken()).contentType("application/json").content(json.write(request))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value("3"));
        mvc.perform(post("/api/attachments/"+accepted.attachmentId()+"/review").header("Authorization","Bearer "+tokenA.accessToken()).contentType("application/json").content(json.write(request))).andExpect(status().isConflict());
        assertThat(port.getReviewedItems(accepted.attachmentId(),"3",a).items()).containsExactly(new Selection("000001","pcs","main","3"));
        assertThat(db.queryForObject("SELECT count(*) FROM cart_proposals",Integer.class)).isZero();
        var invalid=new AttachmentModels.ReviewRequest("3",List.of(new AttachmentModels.Selection(row,"1001","1.5","pcs","main",true)));
        assertThatThrownBy(()->attachments.review(a,accepted.attachmentId(),invalid)).hasMessage("invalid_quantity_step");
    }
    @Test void expiredLeaseCannotPublishAndDeleteWinsAgainstLateWorker()throws Exception{
        var accepted=upload();var old=files.claim().orElseThrow();db.update("UPDATE attachment_jobs SET lease_until=now()-interval '1 second' WHERE id=?",old.id());var next=files.claim().orElseThrow();
        assertThat(files.publish(old,List.of(),List.of(),null)).isFalse();assertThat(next.epoch()).isGreaterThan(old.epoch());
        attachments.delete(a,accepted.attachmentId());attachments.delete(a,accepted.attachmentId());assertThat(files.publish(next,List.of(),List.of(),null)).isFalse();
        assertThatThrownBy(()->attachments.status(a,accepted.attachmentId())).hasMessage("resource_not_found");assertThat(db.queryForObject("SELECT original_bytes IS NULL FROM attachments WHERE id=?",Boolean.class,accepted.attachmentId())).isTrue();
    }
    @Test void reprocessIsOwnedVersionedAndRetainsExplicitReviewAfterReload()throws Exception{
        var accepted=upload();worker.processOne();var before=attachments.status(a,accepted.attachmentId());
        String row=before.rows().stream().filter(r->"000001".equals(r.extracted().article())).findFirst().orElseThrow().extracted().id();
        attachments.review(a,accepted.attachmentId(),new AttachmentModels.ReviewRequest(before.version(),List.of(new AttachmentModels.Selection(row,"1001","7","pcs","main",true))));
        var request=new AttachmentModels.ReprocessRequest("3");
        mvc.perform(post("/api/attachments/"+accepted.attachmentId()+"/reprocess").header("Authorization","Bearer "+tokenB.accessToken()).contentType("application/json").content(json.write(request))).andExpect(status().isNotFound());
        mvc.perform(post("/api/attachments/"+accepted.attachmentId()+"/reprocess").header("Authorization","Bearer "+tokenA.accessToken()).contentType("application/json").content(json.write(request))).andExpect(status().isAccepted()).andExpect(jsonPath("$.version").value("4"));
        assertThatThrownBy(()->attachments.reprocess(a,accepted.attachmentId(),request)).hasMessage("stale_attachment");
        assertThatThrownBy(()->attachments.reviewed(accepted.attachmentId(),"4",a)).hasMessage("attachment_not_ready");
        worker.processOne();var after=attachments.status(a,accepted.attachmentId());assertThat(after.version()).isEqualTo("5");
        assertThat(after.rows()).hasSize(before.rows().size());assertThat(after.warnings()).contains("REVIEWED_ROWS_PRESERVED");
        assertThat(attachments.reviewed(accepted.attachmentId(),"5",a).items()).containsExactly(new Selection("000001","pcs","main","7"));
        assertThat(db.queryForObject("SELECT count(*) FROM attachment_reviews WHERE attachment_id=?",Integer.class,accepted.attachmentId())).isEqualTo(1);
    }
    @Test void replacingInFlightJobFencesOldDesiredVersion()throws Exception{
        var accepted=upload();var old=files.claim().orElseThrow();var retry=attachments.reprocess(a,accepted.attachmentId(),new AttachmentModels.ReprocessRequest("1"));
        assertThat(files.publish(old,List.of(),List.of(),null)).isFalse();assertThat(retry.version()).isEqualTo("2");
        worker.processOne();assertThat(attachments.status(a,accepted.attachmentId()).version()).isEqualTo("3");
    }
    @Test void changedOcrRowsCannotRebindAnExistingUserSelection()throws Exception{
        var accepted=upload();worker.processOne();var before=attachments.status(a,accepted.attachmentId());
        var original=before.rows().stream().filter(r->"000001".equals(r.extracted().article())).findFirst().orElseThrow();
        attachments.review(a,accepted.attachmentId(),new AttachmentModels.ReviewRequest(before.version(),List.of(new AttachmentModels.Selection(original.extracted().id(),"1001","7","pcs","main",true))));
        attachments.reprocess(a,accepted.attachmentId(),new AttachmentModels.ReprocessRequest("3"));var job=files.claim().orElseThrow();
        var changed=new AttachmentModels.ExtractedRow("row-changed","different OCR product","999999",null,null,original.extracted().source(),List.of("ARTICLE_UNCERTAIN"));
        assertThat(files.publish(job,List.of(new AttachmentModels.MatchedRow(changed,"unmatched",List.of())),List.of(),null)).isTrue();
        var after=attachments.status(a,accepted.attachmentId());assertThat(after.rows()).anySatisfy(row->assertThat(row.extracted()).isEqualTo(original.extracted()));
        assertThat(attachments.reviewed(accepted.attachmentId(),after.version(),a).items()).containsExactly(new Selection("000001","pcs","main","7"));
    }
    @Test void unsupportedTypeReturns415AndOwnerCanRetryFailedParser()throws Exception{
        var invalid=new MockMultipartFile("file","file.exe","application/octet-stream",new byte[]{1,2,3});
        mvc.perform(multipart("/api/conversations/"+conversation+"/attachments").file(invalid).header("Authorization","Bearer "+tokenA.accessToken())).andExpect(status().isUnsupportedMediaType());
        var accepted=upload();var claim=files.claim().orElseThrow();files.publish(claim,List.of(),List.of(),"TRANSIENT_OCR_FAILURE");
        var retry=attachments.reprocess(a,accepted.attachmentId(),new AttachmentModels.ReprocessRequest("2"));worker.processOne();
        assertThat(attachments.status(a,accepted.attachmentId()).status()).isNotEqualTo("FAILED");assertThat(retry.version()).isEqualTo("3");
    }

}
