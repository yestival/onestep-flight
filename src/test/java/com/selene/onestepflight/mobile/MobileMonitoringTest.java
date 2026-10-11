package com.selene.onestepflight.mobile;

import com.selene.onestepflight.dto.*;
import com.selene.onestepflight.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties={"onestep.mobile.monitor-enabled=false","onestep.share-session.cleanup-enabled=false"})
@AutoConfigureMockMvc
class MobileMonitoringTest {
    @Autowired MockMvc mvc;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired MobileJourneyService journeys;
    @Autowired MobileFlightMonitor monitor;
    @Autowired MobilePushSender sender;
    @Autowired MobileFlightLogic logic;
    @Autowired ResolvedFlightService resolver;
    @Autowired MobileSubscriptionRepository subscriptions;
    @Autowired MobileInstallationRepository installations;
    @Autowired FlightWatchRepository watches;
    @Autowired PushEventRepository events;
    @Autowired PushDeliveryRepository deliveries;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean MultiSourceFlightService source;
    @MockitoBean ApnsClient apns;
    String installation,auth;
    Instant at;
    OffsetDateTime departure,arrival;
    final String device="a1".repeat(32), activity="b2".repeat(32);
    @BeforeEach void prepare() {
        deliveries.deleteAll(); events.deleteAll(); subscriptions.deleteAll(); installations.deleteAll(); watches.deleteAll();
        reset(source,apns); when(apns.ready()).thenReturn(true); when(apns.send(any())).thenReturn(new ApnsClient.Result(200,"ACCEPTED",0));
        installation=UUID.randomUUID().toString(); auth="Bearer "+Base64.getEncoder().encodeToString(new byte[32]);
        at=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS); departure=at.atOffset(ZoneOffset.ofHours(1)).plusMinutes(100); arrival=departure.plusHours(7);
    }
    MultiSourceFlightSnapshot snapshot(String gate,String status,int seconds) {
        Instant updated=at.plusSeconds(seconds);
        FlightResponse f=new FlightResponse("BA117",departure.toLocalDate(),"LHR","London Heathrow","JFK","New York",departure,departure.plusMinutes(10),null,arrival,arrival.plusMinutes(10),null,departure.minusMinutes(40),"T5","T8",gate,null,status,10,10,updated,updated,"TEST_LIVE",null,null,true,false,false);
        return new MultiSourceFlightSnapshot(f,null,"7",Map.of(),Map.of(),List.of(),updated,updated);
    }
    MobileFlightLogic.Progress progress(boolean security,boolean atGate) { return new MobileFlightLogic.Progress(true,false,false,security,atGate,false,false,false,false,false,null,null,false); }
    MobileSubscription subscribe(String id,String secret,boolean security,boolean atGate) {
        journeys.register(id,secret); journeys.update(id,secret,new MobileJourneyService.InstallationUpdate(device,"sandbox","authorized","zh"));
        var f=resolver.resolveOperational(snapshot("B24","ON_TIME",0),null);
        String key="BA117|"+f.departureDate()+"|LHR|JFK";
        journeys.sync(id,secret,new MobileJourneyService.JourneySync(key,f,progress(security,atGate),"zh",1,0));
        journeys.bind(id,secret,new MobileJourneyService.ActivityBinding(key,"activity-1",activity,"active",1));
        return subscriptions.findByInstallationId(id).orElseThrow();
    }
    void due() { new TransactionTemplate(manager).executeWithoutResult(tx->{ for(var w:watches.findAll()) {w.dueAt=0; w.leaseUntil=0; watches.save(w);} }); }
    void verifySnapshot(MultiSourceFlightSnapshot snapshot) { when(source.findFlight(anyString(),any(),anyString(),anyString(),eq(true))).thenReturn(snapshot); due(); monitor.tick(); }
    @Test void serverFindsGateChangeWithoutClientExecutionAndMergesOneAlertWithOneCard() {
        var s=subscribe(installation,auth,true,true);
        verifySnapshot(snapshot("B24","ON_TIME",1));
        assertEquals(0,deliveries.findAll().stream().filter(d->"ALERT".equals(d.channel)).count());
        verifySnapshot(snapshot("B32","BOARDING",2));
        var pending=deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING");
        assertEquals(2,pending.size());
        assertEquals(1,pending.stream().filter(d->"ALERT".equals(d.channel)).count());
        var alert=pending.stream().filter(d->"ALERT".equals(d.channel)).findFirst().orElseThrow();
        assertTrue(alert.payload.contains("B24 → B32")); assertTrue(alert.payload.contains("time-sensitive"));
        var card=pending.stream().filter(d->"ACTIVITY".equals(d.channel)).findFirst().orElseThrow();
        assertTrue(card.payload.contains("请前往 B32 登机口")); assertFalse(card.payload.contains("\"alert\""));
        assertTrue(card.payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096);
        assertFalse(logic.read(subscriptions.findById(s.id).orElseThrow().progress,MobileFlightLogic.Progress.class).atGate());
        long count=events.count(); verifySnapshot(snapshot("B32","BOARDING",2)); assertEquals(count,events.count());
        sender.tick(); assertEquals(2,deliveries.findBySubscriptionIdAndStatus(s.id,"ACCEPTED").size());
        verifySnapshot(snapshot("B24","BOARDING",3)); verifySnapshot(snapshot("B32","BOARDING",4));
        assertEquals(3,events.findAll().stream().filter(e->"GATE".equals(e.eventType)).count());
        verify(source,times(5)).findFlight(anyString(),any(),anyString(),anyString(),eq(true));
    }
    @Test void groupedFlightsShareOneLookupAndUseEachTravellersProgress() {
        var a=subscribe(installation,auth,false,false); String other=UUID.randomUUID().toString(); var b=subscribe(other,auth,true,true);
        verifySnapshot(snapshot("B24","ON_TIME",1)); verifySnapshot(snapshot("B32","ON_TIME",2));
        verify(source,times(2)).findFlight(anyString(),any(),anyString(),anyString(),eq(true));
        assertTrue(deliveries.findBySubscriptionIdAndStatus(a.id,"PENDING").stream().anyMatch(d->d.payload.contains("完成安检后前往 B32")));
        assertTrue(subscriptions.findById(a.id).orElseThrow().displayState.contains("请前往安检"));
        assertTrue(subscriptions.findById(b.id).orElseThrow().displayState.contains("请前往 B32"));
    }
    @Test void failureAndMissingFieldsKeepFactsWithoutInventingChanges() {
        var s=subscribe(installation,auth,true,false); verifySnapshot(snapshot("B24","ON_TIME",1)); verifySnapshot(snapshot("—","UNKNOWN",2));
        var saved=subscriptions.findById(s.id).orElseThrow(); assertEquals("B24",logic.read(saved.facts,ResolvedFlightContext.class).gate());
        assertEquals("ON_TIME",logic.read(saved.facts,ResolvedFlightContext.class).status());
        assertEquals(0,events.findAll().stream().filter(e->"GATE".equals(e.eventType)).count());
        when(source.findFlight(anyString(),any(),anyString(),anyString(),eq(true))).thenThrow(new IllegalStateException("timeout"));
        due(); monitor.tick(); saved=subscriptions.findById(s.id).orElseThrow();
        assertTrue(saved.active); assertTrue(saved.displayState.contains("B24"));
        var w=watches.findAll().get(0); assertEquals(1,w.failures); assertTrue(w.dueAt>=Instant.now().getEpochSecond()+58);
        assertEquals(0,deliveries.findAll().stream().filter(d->"ALERT".equals(d.channel)).count());
    }
    @Test void restartedWorkerUsesPersistedWorkAndRejectsOldProgressAndBindings() {
        var s=subscribe(installation,auth,true,false);
        var restarted=new MobileFlightMonitor(watches,subscriptions,installations,source,resolver,logic,new MobilePushEvents(events,deliveries,logic),journeys,manager);
        when(source.findFlight(anyString(),any(),anyString(),anyString(),eq(true))).thenReturn(snapshot("B24","ON_TIME",1)); due(); restarted.tick();
        assertTrue(subscriptions.findById(s.id).orElseThrow().baseline);
        var f=logic.read(s.facts,ResolvedFlightContext.class);
        journeys.sync(installation,auth,new MobileJourneyService.JourneySync(s.journeyKey,f,progress(true,true),"es",2,0));
        assertThrows(ResponseStatusException.class,()->journeys.sync(installation,auth,new MobileJourneyService.JourneySync(s.journeyKey,f,progress(false,false),"en",1,0)));
        journeys.bind(installation,auth,new MobileJourneyService.ActivityBinding(s.journeyKey,"activity-2","cc".repeat(32),"active",2));
        assertThrows(ResponseStatusException.class,()->journeys.bind(installation,auth,new MobileJourneyService.ActivityBinding(s.journeyKey,"activity-1",activity,"active",1)));
        journeys.clear(installation,auth,3,s.journeyKey); journeys.clear(installation,auth,3,s.journeyKey);
        assertFalse(subscriptions.findById(s.id).orElseThrow().active); assertTrue(deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING").isEmpty());
        assertThrows(ResponseStatusException.class,()->journeys.sync(installation,auth,new MobileJourneyService.JourneySync(s.journeyKey,f,progress(true,true),"es",2,0)));
    }
    @Test void cancellationEnqueuesFinalContentBeforeStoppingAndChannelsFailIndependently() {
        var s=subscribe(installation,auth,true,true); verifySnapshot(snapshot("B24","ON_TIME",1)); verifySnapshot(snapshot("B24","CANCELLED",2));
        assertFalse(subscriptions.findById(s.id).orElseThrow().active);
        var pending=deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING"); assertEquals(2,pending.size());
        assertTrue(pending.stream().anyMatch(d->d.payload.contains("\"event\":\"end\"") && d.payload.contains("dismissal-date") && d.payload.contains("原预计起飞")));
        when(apns.send(any())).thenAnswer(inv->{PushDelivery d=inv.getArgument(0); return new ApnsClient.Result("ALERT".equals(d.channel) ? 410 : 200,"test",0);});
        sender.tick(); assertEquals(1,deliveries.findBySubscriptionIdAndStatus(s.id,"ACCEPTED").size());
        assertNull(installations.findById(installation).orElseThrow().deviceToken); assertNotNull(subscriptions.findById(s.id).orElseThrow().activityToken);
    }
    @Test void installationOwnershipAndDatabaseLeasesAreEnforced() {
        var s=subscribe(installation,auth,true,false);
        assertThrows(ResponseStatusException.class,()->journeys.get(installation,"Bearer "+Base64.getEncoder().encodeToString(new byte[64])));
        var tx=new TransactionTemplate(manager); long now=Instant.now().getEpochSecond();
        assertEquals(Integer.valueOf(1),tx.execute(t->watches.claim(s.watchId,"first",now+60,now)));
        assertEquals(Integer.valueOf(0),tx.execute(t->watches.claim(s.watchId,"second",now+60,now)));
    }
    @Test void switchingSegmentsRevokesOldWorkAndRejectsLateOldSegmentWrites() {
        var old=subscribe(installation,auth,true,true); verifySnapshot(snapshot("B24","ON_TIME",1)); verifySnapshot(snapshot("B32","ON_TIME",2));
        var f=logic.read(old.facts,ResolvedFlightContext.class);
        var n=(tools.jackson.databind.node.ObjectNode)new tools.jackson.databind.ObjectMapper().valueToTree(f);
        n.put("arrivalAirport","CDG");
        var next=logic.read(n.toString(),ResolvedFlightContext.class);
        journeys.sync(installation,auth,new MobileJourneyService.JourneySync("next-segment",next,progress(false,false),"en",2,0));
        assertEquals(1,subscriptions.count()); assertTrue(deliveries.findBySubscriptionIdAndStatus(old.id,"PENDING").isEmpty());
        assertThrows(ResponseStatusException.class,()->journeys.clear(installation,auth,1,old.journeyKey));
        assertThrows(ResponseStatusException.class,()->journeys.bind(installation,auth,new MobileJourneyService.ActivityBinding(old.journeyKey,"old",activity,"active",3)));
        assertEquals("next-segment",subscriptions.findByInstallationId(installation).orElseThrow().journeyKey);
    }
    @Test void reselectingAClearedFlightWakesDormantPersistedWorkAndCreatesANewSubscription() {
        var old=subscribe(installation,auth,true,false); verifySnapshot(snapshot("B32","ON_TIME",1));
        journeys.clear(installation,auth,2,old.journeyKey);
        new TransactionTemplate(manager).executeWithoutResult(tx->{var w=watches.findById(old.watchId).orElseThrow();w.dueAt=Instant.now().getEpochSecond()+86400;watches.save(w);});
        var stale=resolver.resolveOperational(snapshot("B24","ON_TIME",0),null);
        journeys.sync(installation,auth,new MobileJourneyService.JourneySync(old.journeyKey,stale,progress(true,false),"zh",3,0));
        var renewed=subscriptions.findByInstallationId(installation).orElseThrow();
        assertNotEquals(old.id,renewed.id); assertTrue(renewed.active);
        assertEquals("B32",logic.read(renewed.facts,ResolvedFlightContext.class).gate());
        assertTrue(watches.findById(old.watchId).orElseThrow().dueAt<=Instant.now().getEpochSecond());
    }
    @Test void cachedAtGateProgressCannotCheckTheTravellerIntoANewGate() {
        var s=subscribe(installation,auth,true,true);
        var cached=logic.read(s.facts,ResolvedFlightContext.class);
        verifySnapshot(snapshot("B24","ON_TIME",1)); verifySnapshot(snapshot("B32","ON_TIME",2));
        journeys.sync(installation,auth,new MobileJourneyService.JourneySync(s.journeyKey,cached,progress(true,true),"es",2,0));
        var updated=subscriptions.findById(s.id).orElseThrow();
        assertFalse(logic.read(updated.progress,MobileFlightLogic.Progress.class).atGate());
        assertEquals("B32",logic.read(updated.facts,ResolvedFlightContext.class).gate());
    }
    @Test void nativeWirePayloadUsesAllSixAuthenticatedEndpointsIdempotently() throws Exception {
        String base="/api/mobile/installations", own=base+"/"+installation;
        for(int n=0;n<2;n++) mvc.perform(post(base).header("Authorization",auth).contentType("application/json").content(logic.json(Map.of("id",installation))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pushReady").value(true));
        mvc.perform(put(own).header("Authorization",auth).contentType("application/json").content(logic.json(new MobileJourneyService.InstallationUpdate(device,"sandbox","authorized","es"))))
                .andExpect(status().isOk());
        var flight=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(resolver.resolveOperational(snapshot("B24","ON_TIME",0),null));
        flight.put("boardingTime",departure.minusMinutes(40).toString());
        var progress=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(progress(true,false));
        progress.put("flightNumber","BA117"); progress.put("refreshRequested",false);
        String body=logic.json(Map.of("journeyKey","native-wire","flight",flight,"progress",progress,"locale","es","clientRevision",1,"snapshotVersion",0));
        mvc.perform(put(own+"/journey").header("Authorization",auth).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayState.gate").value("B24"));
        mvc.perform(put(own+"/journey/activity").header("Authorization",auth).contentType("application/json").content(logic.json(new MobileJourneyService.ActivityBinding("native-wire","wire-activity",activity,"active",1))))
                .andExpect(status().isOk());
        mvc.perform(get(own+"/journey")).andExpect(status().isUnauthorized());
        mvc.perform(get(own+"/journey").header("Authorization",auth)).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        for(int n=0;n<2;n++) mvc.perform(delete(own+"/journey").param("clientRevision","2").param("journeyKey","native-wire").header("Authorization",auth))
                .andExpect(status().isNoContent());
    }
    @Test void actualArrivalReplacesTheScheduledFallbackDeadline() {
        var s=subscribe(installation,auth,true,false); verifySnapshot(snapshot("B24","ON_TIME",1));
        var arriving=snapshot("B24","ARRIVED",2);
        var node=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(arriving.flight());
        var actual=arrival.minusHours(1); node.put("actualArrival",actual.toString());
        verifySnapshot(new MultiSourceFlightSnapshot(mapper.treeToValue(node,FlightResponse.class),null,"7",Map.of(),Map.of(),List.of(),at.plusSeconds(2),at.plusSeconds(2)));
        assertEquals(actual.toEpochSecond()+21600,subscriptions.findById(s.id).orElseThrow().endsAt);
    }
    @Test void foregroundProgressSyncKeepsAStillValidUnsentGateAlert() {
        var s=subscribe(installation,auth,false,false); verifySnapshot(snapshot("B24","ON_TIME",1)); verifySnapshot(snapshot("B32","ON_TIME",2));
        var before=deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING").stream().filter(d->"ALERT".equals(d.channel)).findFirst().orElseThrow();
        var current=logic.read(subscriptions.findById(s.id).orElseThrow().facts,ResolvedFlightContext.class);
        journeys.sync(installation,auth,new MobileJourneyService.JourneySync(s.journeyKey,current,progress(true,false),"es",2,0));
        var after=deliveries.findBySubscriptionIdAndStatus(s.id,"PENDING").stream().filter(d->"ALERT".equals(d.channel)).findFirst().orElseThrow();
        assertEquals(before.id,after.id); assertEquals(before.eventId,after.eventId);
    }
}
