package com.hackalem.catalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hackalem.ai.catalog.CatalogEmbeddingProvider;
import com.hackalem.domain.catalog.*;
import com.hackalem.domain.port.Contracts.*;
import com.hackalem.integration.catalog.CatalogDataAdapter;
import com.hackalem.security.SessionService;
import com.hackalem.web.ApiException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","spring.ai.openai.api-key=offline-no-calls","app.catalog.embedding.mode=fake","app.catalog.seed.enabled=false","app.worker.enabled=false","app.visitor-enabled=true"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("d2") @Testcontainers
@org.springframework.test.annotation.DirtiesContext
class CatalogServicesIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> redis=new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("spring.data.redis.host",redis::getHost);r.add("spring.data.redis.port",()->redis.getMappedPort(6379));}
    @Autowired DataSource dataSource;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired CatalogImportService imports;
    @Autowired CatalogRepository catalog;@Autowired CatalogSearchService search;@Autowired CatalogOfferService offers;@Autowired CatalogAnalogsService analogs;
    @Autowired CatalogDataAdapter adapter;@Autowired SessionService sessions;
    @Autowired com.hackalem.domain.chat.ChatService chat;@Autowired com.hackalem.domain.cart.CartService carts;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @MockitoSpyBean CatalogEmbeddingProvider embeddings;
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.hackalem.domain.port.LlmGateway unusedLlm;
    TrustedScope owner,other;String ownerToken;
    void schemaAndCatalog()throws Exception{
        if(Boolean.FALSE.equals(db.queryForObject("SELECT to_regclass('catalog_read_snapshots') IS NOT NULL",Boolean.class)))new ResourceDatabasePopulator(new ClassPathResource("db/drafts/catalog.sql")).execute(dataSource);
        var document=json.readValue(new File("../data/sample_catalog/products.json"),CatalogImportDocument.class);
        assertThat(imports.importNow(document,catalog.findActiveVersion().map(v->v.sourceVersion().equals(document.version())).orElse(false)?"catalog-services-test":null,"tests").status()).isEqualTo(ImportJobStatus.SUCCEEDED);
    }
    @BeforeEach void reset()throws Exception{schemaAndCatalog();db.execute("TRUNCATE visitor_sessions CASCADE");db.execute("TRUNCATE catalog_offer_seed,sample_offers");ownerToken=sessions.create().accessToken();owner=sessions.verify(ownerToken);other=sessions.verify(sessions.create().accessToken());clearInvocations(embeddings);}
    SearchCriteria query(String text){return new SearchCriteria(text,20,null,null,null,null,null,Map.of(),false);}
    @Test void exactAndMissingSkuNeverCallEmbedding(){
        assertThat(search.search(query("000001")).products()).extracting(CatalogProduct::article).containsExactly("000001");
        assertThat(search.search(query("999999")).mode()).isEqualTo("NOT_FOUND");assertThat(search.search(query("999999")).products()).isEmpty();verify(embeddings,never()).embedQuery(anyString());
    }
    @Test void typoAndHardParametersAreAppliedBeforeResults(){
        assertThat(search.search(query("автомт C16")).products()).isNotEmpty();
        var constrained=new SearchCriteria("автомат",20,"breakers",null,null,null,null,Map.of("currentA","16"),false);
        assertThat(search.search(constrained).products()).allMatch(p->p.specs().get("currentA").equals("16"));
        assertThatThrownBy(()->new SearchCriteria("q",20,null,null,new BigDecimal("2"),BigDecimal.ONE,null,Map.of(),false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->new SearchCriteria("q",20,null,null,null,null,null,Map.of("$sql","1=1"),false)).isInstanceOf(ApiException.class);
    }
    @Test void stockUsesAuthoritativeRowsAndNeverReseedsChangedValues(){
        var selection=new Selection("000001","pcs","ALA","1");var first=offers.getOffers(List.of(selection),owner).getFirst();assertThat(first.available().value()).isEqualTo("12");
        offers.updateSample("000001","ALA",new BigDecimal("1700.00"),new BigDecimal("7"),first.version());var next=offers.getOffers(List.of(selection),owner).getFirst();
        assertThat(next.available().value()).isEqualTo("7");assertThat(new BigDecimal(next.price().amount())).isEqualByComparingTo("1700.00");assertThat(next.version()).isNotEqualTo(first.version());
        assertThat(search.search(query("000001")).products().getFirst().offer().price()).isEqualByComparingTo("1700");assertThat(offers.getOffers(List.of(selection),other).getFirst().available().value()).isEqualTo("7");
        assertThatThrownBy(()->offers.getOffers(List.of(new Selection("000001","pcs","CLOSED","1")),owner)).hasMessage("warehouse_ineligible");
        assertThatThrownBy(()->offers.getOffers(List.of(new Selection("000006","pcs","ALA","1")),owner)).hasMessage("stock_unknown");
        assertThatThrownBy(()->offers.getOffers(List.of(new Selection("000013","m","ALA","0.3")),owner)).hasMessage("invalid_quantity_unit_or_step");
    }
    @Test void analogsGiveTwelvePlusEightAndWholeTwentyWithoutChangingCart(){
        var result=analogs.find("000001",new BigDecimal("20"),"pcs",query("analogs"),owner);assertThat(result.analogs()).noneMatch(a->a.article().equals("000004"));
        assertThat(result.options()).anyMatch(o->o.kind().equals("PARTIAL_REPLACEMENT")&&o.lines().size()==2&&o.lines().getFirst().addQuantity().equals("12")&&o.lines().get(1).addQuantity().equals("8"));
        assertThat(result.options()).anyMatch(o->o.kind().equals("FULL_ALTERNATIVE")&&o.lines().getFirst().article().equals("000003")&&o.lines().getFirst().addQuantity().equals("20"));
        for(var option:result.options())assertThat(option.lines().stream().map(l->new BigDecimal(l.addQuantity())).reduce(BigDecimal.ZERO,BigDecimal::add)).isEqualByComparingTo("20");
        assertThat(analogs.option(UUID.fromString(result.options().getFirst().id()),owner)).isEqualTo(result.options().getFirst());assertThatThrownBy(()->analogs.option(UUID.fromString(result.options().getFirst().id()),other)).isInstanceOf(ApiException.class);
        assertThat(analogs.find("000005",BigDecimal.ONE,"pcs",query("analogs"),owner).analogs()).isNotEmpty();assertThat(db.queryForObject("SELECT count(*) FROM cart_lines",Integer.class)).isZero();
    }
    @Test void resultOrdinalsStayImmutableAcrossNewSearchAndAreOwnerScoped(){
        var saved=adapter.searchSnapshot(query("автомат"),owner);var first=saved.resultSet().products().getFirst();adapter.searchSnapshot(query("000003"),owner);
        assertThat(adapter.compare(UUID.fromString(saved.resultSet().id()),List.of(0,1),owner).resultSet().products().getFirst()).isEqualTo(first);
        assertThatThrownBy(()->adapter.saved(UUID.fromString(saved.resultSet().id()),other)).isInstanceOf(ApiException.class);
    }
    @Test void catalogPublicationInvalidatesPreparedCartWithoutAnyInterveningCatalogRead()throws Exception{
        UUID conversation=UUID.fromString(chat.create(owner).id());
        chat.submit(owner,conversation,"prepare-race",new TurnRequest("000001",null,null));var claim=chat.claim().orElseThrow();
        var results=chat.saveResults(claim,adapter.search(new SearchQuery("000001",Map.of(),null,null,null),owner));chat.finish(claim,"completed",null);
        var proposal=carts.propose(owner,new ProposalRequest(conversation,chat.state(owner,conversation).version(),results.id(),List.of(new Selection("000001","pcs","ALA","12"))),"proposal-race");
        var data=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(new File("../data/sample_catalog/products.json"));data.put("version","publication-race-v2");
        ((com.fasterxml.jackson.databind.node.ObjectNode)data.get("products").get(0).get("warehouses").get(0)).put("availableQuantity","7");
        assertThat(imports.importNow(json.treeToValue(data,CatalogImportDocument.class),null,"tests").status()).isEqualTo(ImportJobStatus.SUCCEEDED);
        assertThat(db.queryForObject("SELECT available FROM sample_offers WHERE article='000001' AND warehouse='ALA'",BigDecimal.class)).isEqualByComparingTo("7");
        var outcome=carts.confirm(owner,UUID.fromString(proposal.id()),"confirm-race",new ConfirmRequest(proposal.revision(),proposal.digest(),ConsentOrigin.button,null));
        assertThat(outcome.status()).isEqualTo("failed");assertThat(carts.cart(owner).lines()).isEmpty();
    }
    @Test void certificateHasAuthenticatedProductRouteAndCannotReadUnrelatedCertificate()throws Exception{
        var product=adapter.getProduct("000001",owner);String url=product.certificates().getFirst().id();
        assertThat(url).isEqualTo("/api/products/000001/certificates/SYN-CERT-breakers");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization","Bearer "+ownerToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
        assertThat(response.getContentAsByteArray()).startsWith("%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/products/000001/certificates/SYN-CERT-cables").header("Authorization","Bearer "+ownerToken))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
    @Test void sourceTimeoutIsUnavailableAndNeverZero()throws Exception{
        var p=catalog.findActiveByArticle("000001").orElseThrow();offers.quotes(p);
        try(var connection=dataSource.getConnection()){
            connection.setAutoCommit(false);connection.createStatement().execute("LOCK TABLE sample_offers IN ACCESS EXCLUSIVE MODE");
            var quote=offers.quotes(p).getFirst();assertThat(quote.freshness()).isEqualTo("UNAVAILABLE");assertThat(quote.offer()).isNull();
            connection.rollback();
        }
    }
    @Test void providerFailureIsExplicitAndDoesNotSubstituteResults(){
        doThrow(new IllegalStateException("provider unavailable")).when(embeddings).embedQuery("несуществующее описание");
        try{var result=search.search(query("несуществующее описание"));assertThat(result.mode()).isEqualTo("UNAVAILABLE");assertThat(result.products()).isEmpty();assertThat(result.warnings()).isNotEmpty();}
        finally{doCallRealMethod().when(embeddings).embedQuery("несуществующее описание");}
    }
    @Test void vectorPathAndExactBaselineRunOnSameFilteredSpace(){
        long version=catalog.findActiveVersion().orElseThrow().id();float[] vector=embeddings.embedQuery("автомат");
        var hnsw=catalog.semanticSearch(version,vector,5,"breakers",null,Map.of("currentA","16"),false);var exact=catalog.semanticSearch(version,vector,5,"breakers",null,Map.of("currentA","16"),true);
        assertThat(hnsw).allMatch(p->p.category().equals("breakers")&&p.specs().get("currentA").equals("16"));assertThat(hnsw.stream().map(CatalogProduct::id)).containsExactlyElementsOf(exact.stream().map(CatalogProduct::id).toList());
    }
}
