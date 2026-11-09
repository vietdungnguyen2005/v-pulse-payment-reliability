package dev.vpulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import com.fasterxml.jackson.databind.*;
import dev.vpulse.payment.PaymentReconciliationService;

@Import(PaymentReliabilityIntegrationTest.GatewayTestConfiguration.class)
public class PortfolioRecoveryEvidenceTest extends PaymentReliabilityIntegrationTest {
  private Object parentField(String name) throws Exception {var f=PaymentReliabilityIntegrationTest.class.getDeclaredField(name);f.setAccessible(true);return f.get(this);}
  private Object parentCall(String name,Class<?>[] types,Object... args) throws Exception {
    var m=PaymentReliabilityIntegrationTest.class.getDeclaredMethod(name,types);m.setAccessible(true);
    try{return m.invoke(this,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
  }
  private String create(String label,String mode) throws Exception {
    JsonNode result=(JsonNode)parentCall("createSimulation",new Class<?>[]{String.class,String.class},label,mode);
    return result.path("payment").path("paymentNumber").asText();
  }
  private Map<String,Object> snapshot(String number) {
    var s=new LinkedHashMap<String,Object>();
    s.put("localState",jdbc.sql("SELECT status FROM payment_instruction WHERE payment_number=:n").param("n",number).query(String.class).single());
    s.put("authorizationCalls",((ControlledGateway)gateway).calls.get());
    s.put("providerOutcome",jdbc.sql("SELECT l.outcome FROM simulated_provider_ledger l JOIN payment_instruction p ON p.provider_reference=l.reference WHERE p.payment_number=:n").param("n",number).query(String.class).optional().orElse("RECORD_ABSENT"));
    s.put("resolutionAudits",jdbc.sql("SELECT COUNT(*) FROM audit_event WHERE aggregate_id=:n AND action='RECONCILIATION_RESOLVED'").param("n",number).query(Long.class).single());
    s.put("resolvedEvidenceRows",jdbc.sql("SELECT COUNT(*) FROM payment_reconciliation e JOIN payment_instruction p ON e.payment_id=p.id WHERE p.payment_number=:n AND e.state='RESOLVED'").param("n",number).query(Long.class).single());
    return s;
  }
  @Test void captureFailureRecoveryMatrix() throws Exception {
    var service=applicationContext.getBean(PaymentReconciliationService.class);
    var mvc=(MockMvc)parentField("mvc");
    var rows=new ArrayList<Map<String,Object>>();
    var report=new LinkedHashMap<String,Object>();
    report.put("sourceSha",System.getenv("PORTFOLIO_SOURCE_SHA"));report.put("synthetic",true);
    report.put("startedAtUtc",java.time.Instant.now().toString());report.put("scenarios",rows);
    try {
      for(String scenario:List.of("accepted-lost-repeated-resolution","concurrent-investigation-resolution","UNKNOWN","UNAVAILABLE","NOT_FOUND")) {
        resetFaultInjection();
        String number=create("MR-"+rows.size(),scenario.equals("accepted-lost-repeated-resolution")||scenario.startsWith("concurrent")?"ACCEPT_LOST":"TIMEOUT");
        var row=new LinkedHashMap<String,Object>();row.put("scenario",scenario);row.put("before",snapshot(number));row.put("operatorRequired",true);rows.add(row);
        int rejected=0;
        if(scenario.equals("accepted-lost-repeated-resolution")) {
          var evidence=service.reconcile(number,"synthetic-operator");row.put("afterLookup",snapshot(number));
          service.resolve(number,evidence.id(),"synthetic-operator");service.resolve(number,evidence.id(),"synthetic-operator");
        } else if(scenario.startsWith("concurrent")) {
          jdbc.sql("UPDATE provider_lookup_configuration SET delay_ms=200 WHERE rail='CARD'").update();
          var gate=new CountDownLatch(1);
          try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return service.reconcile(number,"synthetic-a");});
            var b=pool.submit(()->{gate.await();return service.reconcile(number,"synthetic-b");});gate.countDown();
            var ea=a.get(10,TimeUnit.SECONDS);var eb=b.get(10,TimeUnit.SECONDS);assertThat(ea.id()).isEqualTo(eb.id());
            var x=pool.submit(()->service.resolve(number,ea.id(),"synthetic-a"));
            var y=pool.submit(()->service.resolve(number,eb.id(),"synthetic-b"));
            assertThat(x.get(10,TimeUnit.SECONDS).state()).isEqualTo("RESOLVED");assertThat(y.get(10,TimeUnit.SECONDS).state()).isEqualTo("RESOLVED");
          }
        } else {
          jdbc.sql("UPDATE provider_lookup_configuration SET mode=:mode WHERE rail='CARD'").param("mode",scenario.equals("NOT_FOUND")?"NORMAL":scenario).update();
          if(scenario.equals("NOT_FOUND"))jdbc.sql("DELETE FROM simulated_provider_ledger WHERE reference=(SELECT provider_reference FROM payment_instruction WHERE payment_number=:n)").param("n",number).update();
          var evidence=service.reconcile(number,"synthetic-operator");
          row.put("observedOutcome",evidence.outcome());
          var auth=(RequestPostProcessor)parentCall("operatorRequest",new Class<?>[]{});
          for(String url:List.of("/api/payments/"+number+"/reconciliation/"+evidence.id()+"/resolve","/api/parking/"+number+"/replay")) {
            int status=mvc.perform(post(url).with(auth).header("X-V-Pulse-BFF-Secret",parentField("BFF")).header("X-V-Pulse-Ops-Secret",parentField("OPS"))).andReturn().getResponse().getStatus();
            row.put(url.contains("/resolve")?"resolveHttpStatus":"replayHttpStatus",status);if(status==409)rejected++;
            assertThat(status).isEqualTo(409);
          }
          assertThat(rejected).isEqualTo(2);
        }
        var after=snapshot(number);row.put("after",after);row.put("correctlyRejectedOperatorCommands",rejected);
        row.put("unexpectedAdditionalAuthorizationCalls",Math.max(0,(int)after.get("authorizationCalls")-1));
        String expected=scenario.equals("accepted-lost-repeated-resolution")||scenario.startsWith("concurrent")?"SUCCEEDED":"PARKED";
        row.put("expectedFinalState",expected);
        assertThat(after.get("localState")).isEqualTo(expected);assertThat(after.get("authorizationCalls")).isEqualTo(1);
        assertThat(after.get("resolutionAudits")).isEqualTo(expected.equals("SUCCEEDED")?1L:0L);
        assertThat(after.get("resolvedEvidenceRows")).isEqualTo(expected.equals("SUCCEEDED")?1L:0L);row.put("outcome","passed");
      }
      String[][] reused={
        {"database-finalization-rollback","providerCommitSurvivesLocalFinalizationRollbackAndRecoveryNeverReauthorizes","ROLLBACK-PROVIDER"},
        {"permission-revoked-while-lock-wait","replayRechecksPermissionAfterWaitingForTheInstructionLock","MERCHANT-LOCKED-PERMISSION"},
        {"late-provider-response","aLateGatewayResultCannotOverwriteARecoveredInstruction","MERCHANT-LATE"}};
      for(var scenario:reused) {
        resetFaultInjection();var row=new LinkedHashMap<String,Object>();row.put("scenario",scenario[0]);rows.add(row);
        row.put("before",null);row.put("beforeEvidence","Preconditions asserted in unchanged original method, not separately snapshotted");
        row.put("originalMethod",scenario[1]);parentCall(scenario[1],new Class<?>[]{});
        String number=jdbc.sql("SELECT payment_number FROM payment_instruction WHERE merchant_id=:merchant").param("merchant",scenario[2]).query(String.class).single();
        var after=snapshot(number);row.put("after",after);
        row.put("operatorRequired",scenario[0].equals("database-finalization-rollback"));
        int initial=scenario[0].equals("permission-revoked-while-lock-wait")?0:1;
        row.put("unexpectedAdditionalAuthorizationCalls",Math.max(0,(int)after.get("authorizationCalls")-initial));
        row.put("correctlyRejectedOperatorCommands",scenario[0].equals("permission-revoked-while-lock-wait")?1:0);
        row.put("expectedFinalState",scenario[0].equals("database-finalization-rollback")?"SUCCEEDED":"PARKED");
        assertThat(after.get("localState")).isEqualTo(row.get("expectedFinalState"));assertThat(after.get("authorizationCalls")).isEqualTo(initial);
        row.put("outcome","passed");
      }
      report.put("outcome","passed");
    }catch(Throwable error){report.put("outcome","failed");report.put("failureType",error.getClass().getSimpleName());throw error;}
    finally {report.put("finishedAtUtc",java.time.Instant.now().toString());new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of(System.getenv("PORTFOLIO_EVIDENCE_OUTPUT")).toFile(),report);}
  }
}
