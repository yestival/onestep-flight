import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
const require=createRequire(import.meta.url);
const {build}=require('../../src/main/resources/static/flight-display.js');
const flight={flightNumber:'BA117',departureDate:'2026-10-10',departureAirport:'LHR',arrivalAirport:'JFK',terminal:'T5',gate:'B24',
  scheduledDeparture:'2026-10-10T15:10:00+01:00',estimatedDeparture:'2026-10-10T15:20:00+01:00',scheduledArrival:'2026-10-10T17:35:00-04:00',
  timeContext:{boardingTime:'2026-10-10T14:30:00+01:00'},dataRetrievedAt:'2026-10-10T14:05:00+01:00',status:'ON_TIME'};
const progress={checkedIn:true,hasCheckedBaggage:false,securityCleared:true};
function state(f=flight,action={actionCode:'GO_TO_GATE'},options={}) { return build(f,action,progress,{journeyKey:'BA117|2026-10-10|LHR|JFK',locale:'zh',...options}); }
test('stale/offline/warning states retain all known facts and regenerate the structured action with a new gate',()=>{
 const original=state();
 const stale=state({...flight,gate:'B32',stale:true}, {actionCode:'ASK_FOR_HELP',reason:'STALE_DATA_NEAR_DEPARTURE'},{previous:original,offline:true});
 assert.equal(stale.nextStep,'请前往 B32 登机口'); assert.equal(stale.gate,'B32'); assert.equal(stale.boardingTime,'14:30');
 assert.equal(stale.departureTime,'15:20'); assert.equal(stale.metricOneValue,'T5'); assert.equal(stale.headingIncludesGate,true);
 const missing=state({...flight,gate:'—',status:'UNKNOWN',timeContext:{}},null,{previous:stale});
 assert.equal(missing.nextStep,'请前往 B32 登机口'); assert.equal(missing.boardingTime,'14:30');
 assert.equal(missing.facts.status,'ON_TIME');
 assert.equal(missing.updatedAt,Date.parse(flight.dataRetrievedAt)/1000);
 const clockWarning=state({...flight,gate:'B32',stale:true},{actionCode:'ASK_FOR_HELP',reason:'DEPARTURE_TIME_PASSED'},{previous:stale});
 assert.equal(clockWarning.nextStep,'请前往 B32 登机口');
});
test('three languages distinguish missing, conflicts, scheduled and estimated values without duplicating gate',()=>{
 for(const [locale,scheduled,estimated] of [['zh','计划起飞','预计起飞'],['en','Scheduled departure','Estimated departure'],['es','Salida prevista','Salida estimada']]) {
   const gate=state(flight,undefined,{locale}); assert.equal(gate.metricThreeLabel,estimated); assert.equal(gate.metricOneValue,'T5');
   const security=state({...flight,estimatedDeparture:null,timeContext:{}},{actionCode:'SECURITY'},{locale});
   assert.equal(security.metricThreeLabel,scheduled); assert.equal(security.metricOneValue,'T5 · B24');
   assert.equal(security.boardingTime,security.missingLabel);
 }
 const conflict=state({...flight,conflicts:[{field:'gate',blocking:true}]});
 assert.equal(conflict.gate,'待确认'); assert.equal(conflict.headingIncludesGate,false); assert.equal(conflict.terminal,'T5');
 assert.equal(state({...flight,gate:'—'}).gate,'待公布');
});
test('time alone cannot promote boarding and cancellation preserves original times',()=>{
 assert.equal(state(flight,{actionCode:'BOARD_NOW'}).actionCode,'WAIT');
 const cancelled=state({...flight,status:'CANCELLED'}); assert.equal(cancelled.nextStep,'航班已取消，请联系航空公司');
 assert.equal(cancelled.metricThreeLabel,'原预计起飞'); assert.equal(cancelled.departureTime,'15:20');
 assert.equal(cancelled.metricTwoLabel,'原登机时间');
});
test('source timestamps and document fallback are not presented as successful verification',()=>{
 const sourceOnly=state({...flight,dataRetrievedAt:null,dataLastUpdatedAt:flight.dataRetrievedAt});
 assert.equal(sourceOnly.updatedAt,0); assert.equal(sourceOnly.updatedText,'尚未核验');
 const fallback=state({...flight,liveDataSource:'DOCUMENT_FALLBACK'});
 assert.equal(fallback.updatedAt,0);
});
test('arrival phases use destination time and applicable facts',()=>{
 const arrival=build({...flight,status:'ARRIVED',estimatedArrival:'2026-10-10T18:00:00-04:00',arrivalTerminal:'T8',arrivalBaggageBelt:'7'},null,{...progress,boarded:true},{locale:'zh'});
 assert.equal(arrival.phase,'ARRIVAL'); assert.equal(arrival.metricOneValue,'T8'); assert.equal(arrival.metricThreeValue,'18:00');
 assert.equal(arrival.metricTwoValue,'7'); assert.equal(arrival.actionCode,'EXIT_AIRPORT');
});
test('unknown times end at the next day end in the airport IANA zone including DST',()=>{
 const unknown=state({flightNumber:'QF1',departureDate:'2026-10-04',departureAirport:'SYD',arrivalAirport:'LHR'});
 assert.equal(unknown.journeyEndsAt,Date.parse('2026-10-06T00:00:00+11:00')/1000-1);
});
test('Widget retains facts in both display modes and uses one semantic heading',()=>{
 const widget=readFileSync(new URL('../ios/App/FlightActivity/FlightActivityWidget.swift',import.meta.url),'utf8');
 assert.ok(!widget.includes('old ?')); assert.ok(!widget.includes('OneStep Flight')); assert.ok(!widget.includes('minimumScaleFactor'));
 assert.ok(widget.includes('state.nextStep')); assert.ok(widget.includes('state.metricThreeValue'));
});
