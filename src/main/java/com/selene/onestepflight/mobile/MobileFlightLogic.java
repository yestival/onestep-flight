package com.selene.onestepflight.mobile;

import com.selene.onestepflight.dto.*;
import com.selene.onestepflight.service.NextActionService;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class MobileFlightLogic {
    public record Progress(boolean checkedIn, Boolean hasCheckedBaggage, boolean bagDropped, boolean securityCleared,
            boolean atGate, boolean boarded, boolean landed, boolean baggageCollected, boolean exitedAirport,
            boolean needsAssistance, String previousTerminal, String previousGate, boolean conflictsConfirmed) {
        JourneyContext context(ResolvedFlightContext f) {
            return new JourneyContext(f.flightNumber(), f.departureDate(), f, false, null, f.documentType(), false,
                    checkedIn, hasCheckedBaggage, bagDropped, securityCleared, atGate, boarded, landed, baggageCollected,
                    exitedAirport, needsAssistance, previousTerminal, previousGate, conflictsConfirmed, false);
        }
        Progress gateChanged() { return new Progress(checkedIn,hasCheckedBaggage,bagDropped,securityCleared,false,boarded,landed,baggageCollected,exitedAirport,needsAssistance,previousTerminal,previousGate,conflictsConfirmed); }
    }
    private final ObjectMapper mapper;
    private final NextActionService actions;
    public MobileFlightLogic(ObjectMapper mapper, NextActionService actions) { this.mapper=mapper; this.actions=actions; }
    public String json(Object value) { return mapper.writeValueAsString(value); }
    public <T> T read(String value, Class<T> type) { return mapper.readValue(value,type); }
    public ObjectNode wireDisplay(FlightDisplayState display,ResolvedFlightContext flight) {
        ObjectNode state=(ObjectNode)mapper.valueToTree(display), source=(ObjectNode)mapper.valueToTree(flight);
        ObjectNode facts=state.putObject("facts");
        for(String field:List.of("gate","terminal","arrivalTerminal","arrivalBaggageBelt","status","documentBoardingTime","documentScheduledDeparture","scheduledDeparture","estimatedDeparture","actualDeparture","scheduledArrival","estimatedArrival","actualArrival")) {
            var value=source.get(field);if(value!=null && !value.isNull()) facts.set(field,value);
        }
        var boarding=flight.timeContext()==null ? flight.documentBoardingTime() : first(flight.timeContext().boardingTime(),flight.documentBoardingTime());
        if(boarding!=null) facts.put("boardingTime",boarding.toString());
        return state;
    }
    public ResolvedFlightContext minimal(ResolvedFlightContext f) {
        ObjectNode n=(ObjectNode)mapper.valueToTree(f);
        n.putObject("providerResults"); n.putObject("documentEvidence"); n.putNull("seat"); n.putNull("sourceUrl");
        ObjectNode provenance=n.putObject("fieldProvenance");
        if(f.fieldProvenance()!=null) f.fieldProvenance().forEach((k,v)-> {
            if(v!=null) provenance.set(k,mapper.valueToTree(new FieldProvenance(v.value(),v.provider(),v.sourceUpdatedAt(),v.retrievedAt(),v.live(),v.conflict(),List.of())));
        });
        return mapper.treeToValue(n,ResolvedFlightContext.class);
    }
    public ResolvedFlightContext retain(ResolvedFlightContext old, ResolvedFlightContext fresh) {
        if(old==null) return minimal(fresh);
        ObjectNode n=(ObjectNode)mapper.valueToTree(minimal(fresh));
        ObjectNode previous=(ObjectNode)mapper.valueToTree(old);
        for(String k: List.of("terminal","gate","arrivalTerminal","arrivalBaggageBelt","scheduledDeparture","estimatedDeparture","actualDeparture","effectiveDeparture","scheduledArrival","estimatedArrival","actualArrival","documentBoardingTime","documentScheduledDeparture","status")) {
            if(n.path(k).isNull() || n.path(k).asText().isBlank() || !reliable(fresh,k)) {
                n.set(k,previous.path(k));
                if(!previous.path(k).isNull() && !previous.path(k).asText().isBlank()) pending(n,old,k,previous.path(k).asText());
            }
        }
        if(fresh.timeContext()!=null && old.timeContext()!=null && (fresh.timeContext().boardingTime()==null || !reliable(fresh,"boardingTime"))) {
            ((ObjectNode)n.path("timeContext")).set("boardingTime",mapper.valueToTree(old.timeContext().boardingTime()));
            if(old.timeContext().boardingTime()!=null) pending(n,old,"boardingTime",old.timeContext().boardingTime().toString());
        }
        return mapper.treeToValue(n,ResolvedFlightContext.class);
    }
    private void pending(ObjectNode result,ResolvedFlightContext old,String field,String value) {
        var previous=old.fieldProvenance()==null ? null : old.fieldProvenance().get(field);
        String provider=previous==null ? "LAST_KNOWN" : previous.provider();
        Instant source=previous==null ? old.dataLastUpdatedAt() : previous.sourceUpdatedAt();
        Instant retrieved=previous==null ? old.dataRetrievedAt() : previous.retrievedAt();
        ((ObjectNode)result.path("fieldProvenance")).set(field,mapper.valueToTree(new FieldProvenance(value,provider,source,retrieved,false,false,
                List.of(new FieldCandidate(value,provider,source,retrieved,false,"AWAITING_VERIFICATION")))));
    }
    public boolean reliable(ResolvedFlightContext f,String field) {
        if(f.stale() || !f.live()) return false;
        String value=switch(field) { case "gate" -> f.gate(); case "terminal" -> f.terminal(); case "arrivalTerminal" -> f.arrivalTerminal(); case "arrivalBaggageBelt" -> f.arrivalBaggageBelt(); default -> null; };
        if(Set.of("gate","terminal","arrivalTerminal","arrivalBaggageBelt").contains(field) && !usable(value)) return false;
        if("status".equals(field) && Set.of("","UNKNOWN","UNAVAILABLE","NOT_FOUND").contains(Objects.toString(f.status(),""))) return false;
        if(f.conflicts()!=null && f.conflicts().stream().anyMatch(c->field.equals(c.field()))) return false;
        var p=f.fieldProvenance()==null ? null : f.fieldProvenance().get(field);
        return p==null || p.live() && !p.conflict();
    }
    public static String identity(ResolvedFlightContext f) {
        return f.flightNumber().replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT)+"|"+f.departureDate()+"|"+f.departureAirport()+"|"+f.arrivalAirport();
    }
    public static long endAt(ResolvedFlightContext f) {
        OffsetDateTime arrival=first(f.actualArrival(),f.estimatedArrival(),f.scheduledArrival());
        if(arrival!=null) return arrival.toEpochSecond()+21600;
        OffsetDateTime departure=first(f.estimatedDeparture(),f.scheduledDeparture(),f.documentScheduledDeparture());
        if(departure!=null) return departure.toEpochSecond()+86400;
        return f.departureDate().plusDays(2).atStartOfDay(zone(f.departureAirport())).toEpochSecond()-1;
    }
    private static final Properties AIRPORT_ZONES = loadZones();
    private static Properties loadZones() {
        Properties zones=new Properties();
        try(var input=MobileFlightLogic.class.getResourceAsStream("/airport-timezones.properties")) { zones.load(input); }
        catch(java.io.IOException ex) { throw new ExceptionInInitializerError(ex); }
        return zones;
    }
    public static ZoneId zone(String airport) { return ZoneId.of(AIRPORT_ZONES.getProperty(Objects.toString(airport,""),"UTC")); }
    @SafeVarargs static <T> T first(T... values) { for(T v:values) if(v!=null) return v; return null; }
    public long interval(ResolvedFlightContext f,long now) {
        if(f.actualArrival()!=null || Set.of("ARRIVED","LANDED").contains(Objects.toString(f.status(),""))) return 60;
        if(f.actualDeparture()!=null || Set.of("DEPARTED","EN_ROUTE","IN_FLIGHT").contains(Objects.toString(f.status(),""))) {
            var arrival=first(f.estimatedArrival(),f.scheduledArrival()); return arrival!=null && arrival.toEpochSecond()-now>7200 ? 300 : 60;
        }
        var departure=first(f.estimatedDeparture(),f.scheduledDeparture(),f.documentScheduledDeparture());
        long earliest=departure!=null ? departure.toEpochSecond() : f.departureDate().atStartOfDay(zone(f.departureAirport())).toEpochSecond();
        return earliest-now>21600 ? 900 : 60;
    }
    public FlightDisplayState display(ResolvedFlightContext f,Progress p,String lang,String key,String lastAction,long version,long revision,long ends) {
        Text t=new Text(lang);
        NextActionResponse action=actions.calculate(f,p.context(f));
        String code=action.actionCode().name();
        if("STALE_DATA_NEAR_DEPARTURE".equals(action.reason())) code=lastAction==null ? p.securityCleared() ? p.atGate() ? "WAIT" : "GO_TO_GATE" : "SECURITY" : lastAction;
        if("DEPARTURE_TIME_PASSED".equals(action.reason()) && lastAction!=null && (f.stale() || f.timeContext()!=null && f.timeContext().stale())) code=lastAction;
        if("BOARD_NOW".equals(code) && !"BOARDING".equals(f.status())) code="BOARD_NOW".equals(lastAction) ? lastAction : "WAIT";
        boolean cancelled="CANCELLED".equals(f.status());
        String phase=p.landed() || f.actualArrival()!=null || Set.of("ARRIVED","LANDED").contains(Objects.toString(f.status(),"")) ? "ARRIVAL"
                : p.boarded() || f.actualDeparture()!=null || Set.of("DEPARTED","EN_ROUTE","IN_FLIGHT").contains(Objects.toString(f.status(),"")) ? "IN_FLIGHT" : "DEPARTURE";
        if(!"DEPARTURE".equals(phase)) code=!p.boarded() && !p.landed() ? "ASK_FOR_HELP" : "IN_FLIGHT".equals(phase) ? "IN_FLIGHT" : Boolean.TRUE.equals(p.hasCheckedBaggage()) && !p.baggageCollected() ? "BAGGAGE_CLAIM" : "EXIT_AIRPORT";
        if(p.needsAssistance()) code="ASK_FOR_HELP";
        if(cancelled) code="CANCELLED";
        String gate=field(f,"gate",f.gate(),t), terminal=field(f,"terminal",f.terminal(),t);
        boolean includes=!cancelled && usable(f.gate()) && !conflict(f,"gate") && Set.of("GO_TO_GATE","BOARD_NOW").contains(code);
        String heading=switch(code) {
            case "GO_TO_GATE" -> includes ? t.l("请前往 "+gate+" 登机口","Go to gate "+gate,"Ve a la puerta "+gate) : t.l("请确认登机口","Confirm your gate","Confirma tu puerta");
            case "BOARD_NOW" -> includes ? t.l("请在 "+gate+" 登机口登机","Board at gate "+gate,"Embarca en la puerta "+gate) : t.l("请确认登机口后登机","Confirm the gate before boarding","Confirma la puerta antes de embarcar");
            case "SECURITY" -> t.l("请前往安检","Go to security","Ve al control de seguridad");
            case "CHECK_IN" -> t.l("请办理值机","Check in","Ve a facturar");
            case "CHECK_IN_QUESTION" -> t.l("请确认是否已值机","Confirm your check-in status","Confirma si has facturado");
            case "CONFIRM_FLIGHT" -> t.l("请确认航班","Confirm your flight","Confirma tu vuelo");
            case "BAG_DROP" -> t.l("请托运行李","Drop off your baggage","Entrega el equipaje");
            case "BAGGAGE_QUESTION" -> t.l("请确认是否托运行李","Confirm checked baggage","Confirma el equipaje facturado");
            case "IN_FLIGHT" -> t.l("飞行中，请留意机组指引","In flight · Follow cabin crew guidance","En vuelo · Sigue las indicaciones de la tripulación");
            case "BAGGAGE_CLAIM" -> t.l("请前往行李提取区","Go to baggage claim","Ve a recoger el equipaje");
            case "EXIT_AIRPORT" -> t.l("请前往机场出口","Go to the airport exit","Ve a la salida del aeropuerto");
            case "CANCELLED" -> t.l("航班已取消，请联系航空公司","Flight cancelled · Contact the airline","Vuelo cancelado · Contacta con la aerolínea");
            case "ASK_FOR_HELP" -> t.l("请联系工作人员","Ask a member of staff","Pide ayuda al personal");
            default -> t.l("请留意屏幕及广播","Watch the screens and announcements","Consulta las pantallas y escucha los avisos");
        };
        if("ASK_FOR_HELP".equals(code) && !"DEPARTURE".equals(phase) && !p.boarded() && !p.needsAssistance()) {
            heading=t.l("航班已离港，请联系航空公司","Flight departed · Contact the airline","El vuelo ha salido · Contacta con la aerolínea");
        }
        OffsetDateTime dep=first(f.estimatedDeparture(),f.scheduledDeparture(),f.documentScheduledDeparture()), arr=first(f.estimatedArrival(),f.scheduledArrival());
        boolean departure="DEPARTURE".equals(phase);
        ZoneId zone=departure ? dep==null ? zone(f.departureAirport()) : dep.getOffset() : arr==null ? zone(f.arrivalAirport()) : arr.getOffset();
        String boarding=field(f,"boardingTime",clock(f.timeContext()==null ? f.documentBoardingTime() : first(f.timeContext().boardingTime(),f.documentBoardingTime()),zone),t);
        String depType=f.estimatedDeparture()!=null ? "ESTIMATED" : "SCHEDULED", arrType=f.estimatedArrival()!=null ? "ESTIMATED" : "SCHEDULED";
        String depLabel=f.estimatedDeparture()!=null ? t.l("预计起飞","Estimated departure","Salida estimada") : t.l("计划起飞","Scheduled departure","Salida prevista");
        if(cancelled) depLabel=f.estimatedDeparture()!=null ? t.l("原预计起飞","Former estimated departure","Anterior salida estimada") : t.l("原计划起飞","Original scheduled departure","Salida prevista original");
        long updated=f.dataRetrievedAt()==null || "DOCUMENT_FALLBACK".equals(f.liveDataSource()) ? 0 : f.dataRetrievedAt().getEpochSecond();
        String boardingLabel=cancelled ? t.l("原登机时间","Original boarding","Embarque original") : t.l("登机时间","Boarding","Embarque");
        String arrivalLabel=f.estimatedArrival()!=null ? t.l("预计到达","Estimated arrival","Llegada estimada") : t.l("计划到达","Scheduled arrival","Llegada prevista");
        if(cancelled) arrivalLabel=f.estimatedArrival()!=null ? t.l("原预计到达","Former estimated arrival","Anterior llegada estimada") : t.l("原计划到达","Original scheduled arrival","Llegada prevista original");
        String updatedText=updated==0 ? t.l("尚未核验","Not verified","Sin verificar") : DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochSecond(updated))+t.l(" 更新"," updated"," actualizado");
        return new FlightDisplayState(key,f.flightNumber().replaceFirst("^([A-Z]{2}|[A-Z][0-9]|[0-9][A-Z]|[A-Z]{3})([0-9])","$1 $2"),f.departureDate().toString(),f.departureAirport(),f.arrivalAirport(),
                f.departureDate().format(DateTimeFormatter.ofPattern("zh".equals(lang) ? "M月d日" : "MMM d",Locale.forLanguageTag(lang))),phase,code,heading,includes,
                terminal,gate,boarding,field(f,f.estimatedDeparture()!=null ? "estimatedDeparture" : "scheduledDeparture",clock(dep,dep==null ? zone(f.departureAirport()) : dep.getOffset()),t),depType,field(f,f.estimatedArrival()!=null ? "estimatedArrival" : "scheduledArrival",clock(arr,arr==null ? zone(f.arrivalAirport()) : arr.getOffset()),t),arrType,
                field(f,"arrivalTerminal",f.arrivalTerminal(),t),field(f,"arrivalBaggageBelt",f.arrivalBaggageBelt(),t),lang,
                departure ? includes ? t.l("航站楼","Terminal","Terminal") : t.l("航站楼 / 登机口","Terminal / Gate","Terminal / Puerta") : t.l("到达航站楼","Arrival terminal","Terminal de llegada"),
                departure ? includes ? terminal : terminal+" · "+gate : field(f,"arrivalTerminal",f.arrivalTerminal(),t),
                departure ? boardingLabel : t.l("行李转盘","Baggage belt","Cinta de equipaje"),departure ? boarding : field(f,"arrivalBaggageBelt",f.arrivalBaggageBelt(),t),
                departure ? depLabel : arrivalLabel,field(f,departure ? f.estimatedDeparture()!=null ? "estimatedDeparture" : "scheduledDeparture" : f.estimatedArrival()!=null ? "estimatedArrival" : "scheduledArrival",clock(departure ? dep : arr,zone),t),
                departure ? t.l("时间为出发机场当地时间","Departure airport local time","Hora local del aeropuerto de salida") : t.l("时间为目的地当地时间","Destination local time","Hora local del destino"),updatedText,
                t.l("数据可能已过期 · 打开 App 刷新","Data may be out of date · Open app to refresh","Datos posiblemente desactualizados · Abre la app"),t.l("离线 · 联网后可刷新","Offline · Refresh when online","Sin conexión · Actualiza al conectarte"),t.missing(),f.stale() || pendingVerification(f),false,updated,(f.dataLastUpdatedAt()==null ? updated : f.dataLastUpdatedAt().getEpochSecond())+600,ends,version,revision);
    }
    private boolean pendingVerification(ResolvedFlightContext f) {
        return f.fieldProvenance()!=null && f.fieldProvenance().values().stream().filter(Objects::nonNull)
                .flatMap(p->p.candidateValues()==null ? java.util.stream.Stream.empty() : p.candidateValues().stream())
                .anyMatch(c->"AWAITING_VERIFICATION".equals(c.quality()));
    }
    private static String clock(OffsetDateTime time,ZoneId zone) { return time==null ? null : time.atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")); }
    private static boolean conflict(ResolvedFlightContext f,String k) { return f.conflicts()!=null && f.conflicts().stream().anyMatch(c->k.equals(c.field())); }
    private static boolean usable(String value) { return value!=null && !value.isBlank() && !Set.of("-","—","–","N/A","NA","TBA","TBD","UNKNOWN","UNAVAILABLE","NULL").contains(value.strip().toUpperCase(Locale.ROOT)); }
    private static String field(ResolvedFlightContext f,String k,String value,Text t) { return conflict(f,k) ? t.l("待确认","To be confirmed","Por confirmar") : !usable(value) ? t.missing() : value; }
    public record Text(String language) {
        public String l(String zh,String en,String es) { return "zh".equals(language) ? zh : "es".equals(language) ? es : en; }
        String missing() { return l("待公布","To be announced","Por anunciar"); }
    }
}
