package com.hackalem;

import static com.hackalem.domain.port.Contracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.hackalem.domain.chat.*;
import com.hackalem.domain.cart.CartService;
import com.hackalem.domain.port.*;
import com.hackalem.security.SessionService;
import com.hackalem.web.ApiException;
import com.hackalem.ai.agent.AgentTools;
import com.hackalem.ai.agent.AgentWorker;
import com.hackalem.ai.agent.Limits;
import com.hackalem.domain.Json;
import com.hackalem.integration.cart.SampleCartAdapter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"app.worker.enabled=false","app.limits.queue-size=2","app.limits.global-rpm=1000","app.limits.principal-rpm=1000","app.limits.tokens-per-minute=1000000","app.admin-principal-ids=00000000-0000-0000-0000-000000000100"})
@ActiveProfiles("test") @AutoConfigureMockMvc @Testcontainers
@org.springframework.test.annotation.DirtiesContext
class CoreIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> redis=new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void config(DynamicPropertyRegistry r){r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("spring.data.redis.host",redis::getHost);r.add("spring.data.redis.port",()->redis.getMappedPort(6379));}
    @Autowired SessionService sessions;@Autowired ChatService chat;@Autowired CartService carts;@Autowired CatalogPort catalog;
    @Autowired AgentTools tools;@Autowired AgentWorker worker;@Autowired EventJournal events;@Autowired Limits limits;
    @Autowired JdbcTemplate db;@Autowired StringRedisTemplate cache;@Autowired MockMvc mvc;@Autowired Json json;
    @MockitoSpyBean SampleCartAdapter adapter;
    TrustedScope a,b;SessionToken tokenA,tokenB;
    @BeforeEach void reset(){
        db.execute("TRUNCATE visitor_sessions CASCADE");
        db.update("UPDATE sample_offers SET available=CASE article WHEN 'DEMO-001' THEN 12 ELSE 30 END,price=CASE article WHEN 'DEMO-001' THEN 1500 ELSE 1000 END,version=1");
        try(var connection=Objects.requireNonNull(cache.getConnectionFactory()).getConnection()){connection.serverCommands().flushDb();}
        tokenA=sessions.create();tokenB=sessions.create();a=sessions.verify(tokenA.accessToken());b=sessions.verify(tokenB.accessToken());
    }
    UUID conversation(){return UUID.fromString(chat.create(a).id());}
    ChatService.Claim claim(UUID conversation){chat.submit(a,conversation,"turn",new TurnRequest("DEMO-001",null,null));return chat.claim().orElseThrow();}
    ProductResultSet result(ChatService.Claim claim){return chat.saveResults(claim,catalog.search(new SearchQuery("автомат",Map.of(),null,null,null),a));}
    ProposalSnapshot proposal(String quantity){UUID c=conversation();var claim=claim(c);var results=result(claim);chat.finish(claim,"completed",null);return carts.propose(a,new ProposalRequest(c,chat.state(a,c).version(),results.id(),List.of(new Selection("DEMO-001","piece","main",quantity))),"proposal");}
    ConfirmRequest consent(ProposalSnapshot p){return new ConfirmRequest(p.revision(),p.digest(),ConsentOrigin.button,null);}

    @Test void healthAndPingRemainPublicAndProtectedRoutesRequireIdentity()throws Exception{
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());mvc.perform(get("/api/ping")).andExpect(status().isOk());
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("unauthorized"));
        mvc.perform(post("/api/products").header("Authorization","Bearer "+tokenA.accessToken()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void visitorCannotReadOtherConversationRunEventsOrProposal()throws Exception{
        UUID c=conversation();var accepted=chat.submit(a,c,"owner",new TurnRequest("hello",null,null));
        for(String path:List.of("/api/conversations/"+c+"/messages","/api/runs/"+accepted.runId(),"/api/runs/"+accepted.runId()+"/events"))
            mvc.perform(get(path).header("Authorization","Bearer "+tokenB.accessToken())).andExpect(status().isNotFound());
        chat.cancel(a,UUID.fromString(accepted.runId()));var p=proposal("2");
        assertThatThrownBy(()->carts.get(b,UUID.fromString(p.id()))).isInstanceOf(ApiException.class);
    }
    @Test void expiredForgedAndWrongAudienceTokensFail()throws Exception{
        var key=io.jsonwebtoken.security.Keys.hmacShaKeyFor("dev-only-secret-change-me-min-32-chars-long".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String wrong=io.jsonwebtoken.Jwts.builder().issuer("hackalem").subject(a.principalId().toString()).audience().add("other").and().expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        String expired=io.jsonwebtoken.Jwts.builder().issuer("hackalem").subject(a.principalId().toString()).audience().add("ekt-widget").and().expiration(Date.from(Instant.now().minusSeconds(60))).signWith(key).compact();
        for(String token:List.of(wrong,expired,tokenA.accessToken()+"x"))mvc.perform(get("/api/cart").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        sessions.revoke(a);assertThatThrownBy(()->sessions.verify(tokenA.accessToken())).isInstanceOf(ApiException.class);
    }
    @Test void concurrentDuplicateTurnsAndPayloadConflict()throws Exception{
        UUID c=conversation();var body=new TurnRequest("hello",null,null);
        try(var pool=Executors.newFixedThreadPool(8)){
            var tasks=new ArrayList<Callable<AcceptedTurn>>();for(int i=0;i<8;i++)tasks.add(()->chat.submit(a,c,"same",body));
            var results=pool.invokeAll(tasks).stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).toList();
            assertThat(results.stream().map(AcceptedTurn::runId).distinct()).hasSize(1);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM messages",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->chat.submit(a,c,"same",new TurnRequest("changed",null,null))).isInstanceOf(ApiException.class).hasMessage("idempotency_conflict");
        assertThatThrownBy(()->chat.submit(a,c,"new",body)).hasMessage("active_run");
    }
    @Test void acceptedReplayPrecedesFullQueueAndRateLimits(){
        UUID c=conversation();var request=new TurnRequest("hello",null,null);var first=chat.submit(a,c,"same",request);
        chat.submit(b,UUID.fromString(chat.create(b).id()),"second",request);
        assertThat(chat.submit(a,c,"same",request)).isEqualTo(first);
        assertThatThrownBy(()->chat.submit(a,conversation(),"overflow",request)).hasMessage("queue_full");
    }
    @Test void durableJobCanBeClaimedAndFinalHistorySurvivesReload(){
        UUID c=conversation();var claim=claim(c);chat.delta(claim,"partial ");chat.delta(claim,"answer");chat.finish(claim,"completed",null);
        var first=chat.messages(a,c,null,1);assertThat(first.items()).hasSize(1);assertThat(first.nextCursor()).isEqualTo("1");
        var second=chat.messages(a,c,first.nextCursor(),1);assertThat(second.items().getFirst().text()).isEqualTo("partial answer");
        assertThat(chat.run(a,claim.id()).status()).isEqualTo("completed");assertThat(chat.claim()).isEmpty();
    }
    @Test void cancelledAndOldEpochWorkersCannotPublish(){
        var claim=claim(conversation());chat.delta(claim,"first");chat.cancel(a,claim.id());
        assertThatThrownBy(()->chat.delta(claim,"stale")).hasMessage("run_not_active");chat.finish(claim,"completed",null);
        assertThat(chat.run(a,claim.id()).status()).isEqualTo("cancelled");
        assertThat(events.read(claim.id(),0).stream().filter(e->e.type().startsWith("run.")&&!e.type().equals("run.started"))).hasSize(1);
    }
    @Test void initialSseIncludesBeginningAndTerminalAfterDurableFinal()throws Exception{
        var claim=claim(conversation());chat.delta(claim,"verified text");chat.finish(claim,"completed",null);
        var pending=mvc.perform(get("/api/runs/"+claim.id()+"/events").header("Authorization","Bearer "+tokenA.accessToken())).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("verified text"))).andExpect(content().string(org.hamcrest.Matchers.containsString("run.completed")));
    }
    @Test void journalLossReturnsSnapshotAndNeverRestartsGeneration()throws Exception{
        var claim=claim(conversation());chat.delta(claim,"durable");chat.finish(claim,"completed",null);db.update("DELETE FROM run_events WHERE run_id=?",claim.id());
        var pending=mvc.perform(get("/api/runs/"+claim.id()+"/events").header("Authorization","Bearer "+tokenA.accessToken())).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(content().string(org.hamcrest.Matchers.containsString("replay_unavailable"))).andExpect(content().string(org.hamcrest.Matchers.containsString("durable")));
        assertThat(db.queryForObject("SELECT count(*) FROM chat_runs",Integer.class)).isEqualTo(1);
    }
    @Test void proposalsAreImmutableAndDoNotChangeCart(){
        var p=proposal("3");assertThat(carts.cart(a).lines()).isEmpty();assertThat(p.lines().getFirst().unitPrice().amount()).isEqualTo("1500");
        carts.reject(a,UUID.fromString(p.id()));assertThat(carts.cart(a).lines()).isEmpty();
        assertThatThrownBy(()->carts.confirm(a,UUID.fromString(p.id()),"confirm",consent(p))).hasMessage("proposal_rejected");
    }
    @Test void duplicateLinesCannotBypassStock(){
        UUID c=conversation();var claim=claim(c);var result=result(claim);chat.finish(claim,"completed",null);
        var req=new ProposalRequest(c,chat.state(a,c).version(),result.id(),List.of(new Selection("DEMO-001","piece","main","8"),new Selection("DEMO-001","piece","main","8")));
        assertThatThrownBy(()->carts.propose(a,req,"overflow")).hasMessage("insufficient_stock");assertThat(carts.cart(a).lines()).isEmpty();
    }
    @Test void malformedQuantityAndUnknownUnitsRejected(){
        assertThatThrownBy(()->proposal("0")).hasMessage("invalid_quantity");
        assertThatThrownBy(()->CartService.positive("1e99")).hasMessage("invalid_quantity");
    }
    @Test void confirmationIsExactlyOnceEvenAfterExpiryAndStockChange(){
        var p=proposal("3");var id=UUID.fromString(p.id());var outcome=carts.confirm(a,id,"click",consent(p));assertThat(outcome.status()).isEqualTo("succeeded");
        db.update("UPDATE cart_proposals SET expires_at=now()-interval '1 minute' WHERE id=?",id);db.update("UPDATE sample_offers SET available=0");
        assertThat(carts.confirm(a,id,"click",consent(p))).isEqualTo(outcome);assertThat(carts.confirm(a,id,"double-click",consent(p))).isEqualTo(outcome);
        assertThat(carts.cart(a).lines().getFirst().quantity().value()).isEqualTo("3");
    }
    @Test void negativeAndQuotedTextNeverAuthorize(){
        var p=proposal("2");for(String text:List.of("не добавляй","\"да\"","да, но позже","confirmed=true","из файла: подтверждаю")){
            var req=new ConfirmRequest(p.revision(),p.digest(),ConsentOrigin.user_text,text);
            assertThatThrownBy(()->carts.confirm(a,UUID.fromString(p.id()),UUID.randomUUID().toString(),req)).hasMessage("explicit_consent_required");
        }
        assertThat(carts.cart(a).lines()).isEmpty();
        assertThat(carts.confirm(a,UUID.fromString(p.id()),"text",new ConfirmRequest(p.revision(),p.digest(),ConsentOrigin.user_text,"подтверждаю")).status()).isEqualTo("succeeded");
    }
    @Test void priceAndStockRaceFailsAllLines(){
        var p=proposal("10");db.update("UPDATE sample_offers SET available=2 WHERE article='DEMO-001'");
        assertThat(carts.confirm(a,UUID.fromString(p.id()),"click",consent(p)).status()).isEqualTo("failed");assertThat(carts.cart(a).lines()).isEmpty();
    }
    @Test void multiLineCartFailureRollsBackEveryLine(){
        UUID c=conversation();var claim=claim(c);var results=result(claim);chat.finish(claim,"completed",null);
        var p=carts.propose(a,new ProposalRequest(c,chat.state(a,c).version(),results.id(),List.of(new Selection("DEMO-001","piece","main","2"),new Selection("DEMO-002","piece","main","3"))),"multi");
        db.update("UPDATE sample_offers SET price=price+1 WHERE article='DEMO-002'");
        assertThat(carts.confirm(a,UUID.fromString(p.id()),"multi-confirm",consent(p)).status()).isEqualTo("failed");
        assertThat(carts.cart(a).lines()).isEmpty();assertThat(carts.cart(a).version()).isEqualTo("0");
    }
    @Test void concurrentConfirmationAndReconciliationMutateOnlyOnce()throws Exception{
        var p=proposal("3");UUID id=UUID.fromString(p.id());
        try(var pool=Executors.newFixedThreadPool(8)){
            List<Callable<OperationOutcome>> work=new ArrayList<>();
            for(int i=0;i<8;i++){String key="click-"+i;work.add(()->carts.confirm(a,id,key,consent(p)));}
            for(var result:pool.invokeAll(work,10,TimeUnit.SECONDS))assertThat(result.get().status()).isIn("succeeded","outcome_unknown");
        }
        assertThat(carts.operation(a,UUID.fromString(p.operationId())).status()).isEqualTo("succeeded");
        assertThat(carts.cart(a).lines().getFirst().quantity().value()).isEqualTo("3");assertThat(carts.cart(a).version()).isEqualTo("1");
    }
    @Test void timeoutAfterCommitIsReconciledWithoutSecondMutation(){
        var p=proposal("4");doAnswer(invocation->{invocation.callRealMethod();throw new RuntimeException("lost response");}).when(adapter).addConditionally(any(),any());
        var outcome=carts.confirm(a,UUID.fromString(p.id()),"lost",consent(p));assertThat(outcome.status()).isEqualTo("outcome_unknown");
        assertThat(carts.operation(a,UUID.fromString(p.operationId())).status()).isEqualTo("succeeded");
        assertThat(carts.confirm(a,UUID.fromString(p.id()),"lost",consent(p)).status()).isEqualTo("succeeded");assertThat(carts.cart(a).lines().getFirst().quantity().value()).isEqualTo("4");
        verify(adapter,times(1)).addConditionally(any(),any());
    }
    @Test void restartBeforeAdapterResumesOnlyAtomicSampleOperation(){
        var p=proposal("4");doThrow(new RuntimeException("crash before send")).when(adapter).addConditionally(any(),any());
        assertThat(carts.confirm(a,UUID.fromString(p.id()),"before-send",consent(p)).status()).isEqualTo("outcome_unknown");
        doCallRealMethod().when(adapter).addConditionally(any(),any());
        assertThat(carts.operation(a,UUID.fromString(p.operationId())).status()).isEqualTo("succeeded");
        assertThat(carts.operation(a,UUID.fromString(p.operationId())).status()).isEqualTo("succeeded");
        assertThat(carts.cart(a).lines().getFirst().quantity().value()).isEqualTo("4");
    }
    @Test void purchaseTermsContainActualSourceText(){
        UUID c=conversation();chat.submit(a,c,"terms",new TurnRequest("условия доставки",null,null));var claim=chat.claim().orElseThrow();worker.execute(claim);
        assertThat(chat.run(a,claim.id()).text()).contains("самовывоз");
        assertThat(events.read(claim.id(),0).stream().map(ChatEvent::payload).filter(p->p instanceof SourcesPayload).map(p->((SourcesPayload)p).excerpts())).isNotEmpty();
    }
    @Test void administratorComesFromServerAllowlistOnly()throws Exception{
        UUID owner=UUID.fromString("00000000-0000-0000-0000-000000000100");UUID cart=UUID.randomUUID();
        db.update("INSERT INTO visitor_sessions(id,cart_id,expires_at) VALUES (?,?,now()+interval '1 hour')",owner,cart);
        var key=io.jsonwebtoken.security.Keys.hmacShaKeyFor("dev-only-secret-change-me-min-32-chars-long".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String token=io.jsonwebtoken.Jwts.builder().issuer("hackalem").subject(owner.toString()).audience().add("ekt-widget").and().expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key).compact();
        mvc.perform(get("/actuator/info").header("Authorization","Bearer "+token)).andExpect(status().isOk());
        // Admin reaches catalog controller using the verified session, without legacy X-Admin-Token.
        mvc.perform(get("/api/admin/jobs/99999999").header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/jobs/99999999").header("Authorization","Bearer "+tokenA.accessToken())).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").header("Authorization","Bearer "+tokenA.accessToken())).andExpect(status().isForbidden());
    }
    @Test void unknownToolsCannotMutate(){
        var claim=claim(conversation());for(String name:List.of("cart_add","confirm","sql","delete_all"))assertThatThrownBy(()->tools.execute(claim,new LlmGateway.ToolCall("x",name,"{}"))).hasMessage("tool_not_allowed");
        assertThat(carts.cart(a).lines()).isEmpty();
    }
    @Test void agentUsesTypedProductsAndFinishesOffline()throws Exception{
        var claim=claim(conversation());worker.execute(claim);assertThat(chat.run(a,claim.id()).status()).isEqualTo("completed");
        assertThat(events.read(claim.id(),0).stream().map(ChatEvent::type)).contains("products.result","message.delta","run.completed");
        assertThat(carts.cart(a).lines()).isEmpty();
        mvc.perform(get("/api/runs/"+claim.id()+"/usage").header("Authorization","Bearer "+tokenA.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].model").value("contract-scripted")).andExpect(jsonPath("$[0].inputTokens").value(100));
        mvc.perform(get("/api/runs/"+claim.id()+"/usage").header("Authorization","Bearer "+tokenB.accessToken())).andExpect(status().isNotFound());
    }
    @Test void dialogueVersionsAndOriginalResultOrderSurviveNewRanking(){
        UUID c=conversation();var claim=claim(c);var first=result(claim);var original=first.products().getFirst().article();
        var second=chat.saveResults(claim,new ProductResultSet("ignored","1",first.products().reversed(),first.offers()));
        var state=chat.state(a,c);var update=new UpdateDialogue(state.version(),"breakers",new Money("2000","KZT"),Map.of("current","16A"),new Quantity("20","piece","1"),first.id(),List.of(0),null,null,null);
        var saved=chat.updateState(a,c,update);assertThat(saved.selectedArticles()).containsExactly(original);assertThat(saved.lastResultSetId()).isEqualTo(first.id());
        assertThatThrownBy(()->chat.updateState(a,c,update)).hasMessage("stale_dialogue");assertThat(chat.state(a,c)).isEqualTo(saved);
    }
    @Test void shortQuantityReplySupersedesOldProposalWithoutConsent(){
        var p=proposal("2");UUID c=UUID.fromString(p.conversationId());
        chat.submit(a,c,"quantity-change",new TurnRequest("нужно 20 штук",null,chat.state(a,c).version()));
        assertThat(chat.state(a,c).quantity().value()).isEqualTo("20");
        assertThat(carts.get(a,UUID.fromString(p.id())).status()).isEqualTo("superseded");
        assertThatThrownBy(()->carts.confirm(a,UUID.fromString(p.id()),"stale-click",consent(p))).hasMessage("proposal_superseded");
        assertThat(carts.cart(a).lines()).isEmpty();
    }
    @Test void globalLimiterIsSharedAndDuplicateReservationIsFree(){
        UUID run=UUID.randomUUID();limits.reserve(run,a.principalId(),100,60);limits.reserve(run,a.principalId(),100,60);
        assertThat(cache.opsForZSet().size("ekt:active")).isEqualTo(1);limits.release(run,50);assertThat(cache.opsForZSet().size("ekt:active")).isZero();
    }
    @Test void cleanSchemaAndUpgradeRegistryAreApplied(){assertThat(db.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",String.class)).contains("1","2","3","4","5");}
    @Test void existingV2DatabaseUpgradesWithoutChangingOldMigrations(){
        db.execute("CREATE DATABASE upgrade_d1");
        String url=postgres.getJdbcUrl().replace("/"+postgres.getDatabaseName(),"/upgrade_d1");
        var flyway=org.flywaydb.core.Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).target("2").load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        var upgrade=org.flywaydb.core.Flyway.configure().dataSource(url,postgres.getUsername(),postgres.getPassword()).locations("classpath:db/migration","classpath:db/baseline-identity").target("5").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(3);assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("5");
    }
    @Test void eventOpenApiHasDiscriminatorWithoutCircularSubtypeInheritance()throws Exception{
        var response=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var schema=json.read(response.getResponse().getContentAsString(),com.fasterxml.jackson.databind.JsonNode.class).path("components").path("schemas");
        assertThat(schema.path("DeltaPayload").has("allOf")).isFalse();
        assertThat(schema.path("DeltaPayload").path("properties").path("kind").path("enum").get(0).asText()).isEqualTo("delta");
        assertThat(schema.path("EventPayload").path("oneOf").size()).isEqualTo(8);
    }
}
