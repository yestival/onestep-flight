package com.selene.onestepflight.mobile;

import com.selene.onestepflight.dto.ResolvedFlightContext;
import com.selene.onestepflight.service.NextActionService;
import java.nio.file.*;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import tools.jackson.databind.json.JsonMapper;

class FlightDisplayContractTest {
    final JsonMapper mapper=JsonMapper.builder().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(tools.jackson.databind.cfg.DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE,
                    tools.jackson.databind.cfg.DateTimeFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE).build();
    @Test void exactSharedFixtureUsesIntegerUnixSecondsAndFitsApns() throws Exception {
        var fixture=mapper.readTree(Files.readString(Path.of("src/test/fixtures/flight-display-state.json")));
        var logic=new MobileFlightLogic(mapper,new NextActionService(Clock.fixed(Instant.ofEpochSecond(fixture.path("now").asLong()),ZoneOffset.UTC)));
        var f=mapper.treeToValue(fixture.path("flight"),ResolvedFlightContext.class);
        var progress=mapper.treeToValue(fixture.path("progress"),MobileFlightLogic.Progress.class);
        var options=fixture.path("options");
        var display=logic.display(f,progress,options.path("locale").asText(),options.path("journeyKey").asText(),null,7,3,MobileFlightLogic.endAt(f));
        // Compare wire JSON: valueToTree retains Java Long node types for small versions.
        assertEquals(fixture.path("expected"),mapper.readTree(mapper.writeValueAsBytes(display)));
        assertTrue(mapper.writeValueAsBytes(java.util.Map.of("aps",java.util.Map.of("timestamp",fixture.path("now").asLong(),"event","update","content-state",display,"stale-date",display.staleAt()))).length<4096);
        assertEquals(display,mapper.readValue(mapper.writeValueAsBytes(display),FlightDisplayState.class));
    }
    @Test void joseSignatureIs64BytesAndVerifiesWithoutDer() throws Exception {
        var generator=KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); var keys=generator.generateKeyPair();
        byte[] input="header.payload".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var signature=ApnsClient.joseSignature(keys.getPrivate(),input); assertEquals(64,signature.length);
        var verifier=Signature.getInstance("SHA256withECDSAinP1363Format"); verifier.initVerify(keys.getPublic()); verifier.update(input); assertTrue(verifier.verify(signature));
    }
    @Test void missingAppleCredentialsCannotClaimReadiness() {
        var client=new ApnsClient("","","com.selene.onestepflight","",mapper); assertFalse(client.ready());
        assertEquals("APNS_NOT_READY",client.send(new PushDelivery()).reason());
    }
    @Test void unknownTimesUseAirportLocalDayAndFarFutureFlightsDoNotExpireAfterCreation() {
        var f=mapper.readValue("{\"flightNumber\":\"QF1\",\"departureDate\":\"2026-10-04\",\"departureAirport\":\"SYD\",\"arrivalAirport\":\"LHR\"}",ResolvedFlightContext.class);
        assertEquals(OffsetDateTime.parse("2026-10-06T00:00:00+11:00").toEpochSecond()-1,MobileFlightLogic.endAt(f));
        assertEquals(3600,ApnsClient.retryAfter("3600",100));
    }
    @Test void mobileMigrationIsPortableAndRepeatableOnH2() throws Exception {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:mobile-migration;DB_CLOSE_DELAY=-1","sa","");
        var script=new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("db/migration/V2__mobile_flight_monitoring.sql"));
        script.execute(source); script.execute(source);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(source);
        assertEquals(0,jdbc.queryForObject("select count(*) from mobile_push_delivery",Integer.class));
        var policy=new com.selene.onestepflight.service.ProviderCooldownService(source);
        long until=Instant.now().getEpochSecond()+3600;
        policy.observe(com.selene.onestepflight.dto.ProviderFlightResult.failure("TEST","RATE_LIMITED","TEST_429;retryAt="+until,Instant.now()));
        assertEquals(until,new com.selene.onestepflight.service.ProviderCooldownService(source).retryAt("TEST"));
    }
    @Test void verificationIntervalsFollowFlightPhaseRatherThanSubscriptionAge() {
        var fixture=mapper.readTree(readFixture());
        var logic=new MobileFlightLogic(mapper,new NextActionService());
        var f=mapper.treeToValue(fixture.path("flight"),ResolvedFlightContext.class);
        long departure=f.estimatedDeparture().toEpochSecond();
        assertEquals(900,logic.interval(f,departure-21601));
        assertEquals(60,logic.interval(f,departure-21600));
        var n=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(f);
        n.put("status","IN_FLIGHT");
        var flying=mapper.treeToValue(n,ResolvedFlightContext.class);
        long arrival=f.scheduledArrival().toEpochSecond();
        assertEquals(300,logic.interval(flying,arrival-7201)); assertEquals(60,logic.interval(flying,arrival-7200));
        n.put("status","ARRIVED"); assertEquals(60,logic.interval(mapper.treeToValue(n,ResolvedFlightContext.class),arrival+60));
    }
    private String readFixture() { try { return Files.readString(Path.of("src/test/fixtures/flight-display-state.json")); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    @Test void changesCompareInstantsAndIgnoreTemporaryUnknownValues() {
        var f=mapper.treeToValue(mapper.readTree(readFixture()).path("flight"),ResolvedFlightContext.class);
        var logic=new MobileFlightLogic(mapper,new NextActionService());
        var events=new MobilePushEvents(null,null,logic);
        var node=(tools.jackson.databind.node.ObjectNode)mapper.valueToTree(f);
        node.put("estimatedDeparture",f.estimatedDeparture().withOffsetSameInstant(ZoneOffset.UTC).toString());
        assertTrue(events.changes(f,mapper.treeToValue(node,ResolvedFlightContext.class)).isEmpty());
        node.put("estimatedDeparture",f.estimatedDeparture().plusSeconds(59).toString());
        assertTrue(events.changes(f,mapper.treeToValue(node,ResolvedFlightContext.class)).isEmpty());
        node.put("estimatedDeparture",f.estimatedDeparture().plusSeconds(60).toString());
        assertEquals("DEPARTURE_TIME",events.changes(f,mapper.treeToValue(node,ResolvedFlightContext.class)).get(0).type());
        node.put("estimatedDeparture",f.estimatedDeparture().toString()); node.putNull("gate");
        var missing=mapper.treeToValue(node,ResolvedFlightContext.class);
        assertTrue(events.changes(f,missing).isEmpty());
        assertEquals("GATE",events.changes(missing,f).get(0).type());
    }
    @Test void staleClockWarningsKeepLastStructuredStepAndLatestGate() {
        var fixture=mapper.readTree(readFixture());
        var node=(tools.jackson.databind.node.ObjectNode)fixture.path("flight").deepCopy(); node.put("stale",true); node.put("gate","B99");
        var logic=new MobileFlightLogic(mapper,new NextActionService(Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"),ZoneOffset.UTC)));
        var f=mapper.treeToValue(node,ResolvedFlightContext.class);
        var p=mapper.treeToValue(fixture.path("progress"),MobileFlightLogic.Progress.class);
        assertEquals("请前往 B99 登机口",logic.display(f,p,"zh","test","GO_TO_GATE",8,3,MobileFlightLogic.endAt(f)).nextStep());
    }
}
