(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(require('./airport-timezones.js'));
  else root.OneStepFlightDisplay = factory(root.OneStepAirportTimezones);
}(typeof window !== 'undefined' ? window : this, function (zones) {
  'use strict';
  function build(f, action, p, o) {
    o = o || {}; p = p || {};
    var prev = o.previous || {}, lang = o.locale || 'en';
    function l(zh,en,es) { return lang === 'zh' ? zh : lang === 'es' ? es : en; }
    function absent(value) { return value == null || value === '' || /^(?:-|—|–|N\/A|NA|TBA|TBD|UNKNOWN|UNAVAILABLE|NOT_FOUND|NULL)$/i.test(String(value).trim()); }
    function v(k) { var value = absent(f[k]) ? (prev.facts || {})[k] : f[k]; return absent(value) ? '' : value; }
    function epoch(v) { var n = Date.parse(v || ''); return Number.isFinite(n) ? Math.floor(n/1000) : 0; }
    function conflict(k) { return (f.conflicts || []).some(function(c) { return c.field === k && !o.conflictsConfirmed; }); }
    var missing = l('待公布','To be announced','Por anunciar'), uncertain = l('待确认','To be confirmed','Por confirmar');
    function field(k) { return conflict(k) ? uncertain : v(k) || missing; }
    var code = action && action.actionCode || prev.actionCode || 'WAIT';
    if (action && action.reason === 'STALE_DATA_NEAR_DEPARTURE') code = prev.actionCode || (p.securityCleared ? p.atGate ? 'WAIT' : 'GO_TO_GATE' : 'SECURITY');
    if (action && action.reason === 'DEPARTURE_TIME_PASSED' && prev.actionCode && (f.stale || o.stale || o.offline || f.timeContext && f.timeContext.stale)) code = prev.actionCode;
    if (code === 'BOARD_NOW' && f.status !== 'BOARDING') code = prev.actionCode === 'BOARD_NOW' ? 'BOARD_NOW' : 'WAIT';
    var phase = p.landed || v('actualArrival') || /^(ARRIVED|LANDED)/.test(v('status')) ? 'ARRIVAL'
      : p.boarded || v('actualDeparture') || /^(DEPARTED|EN_ROUTE|IN_FLIGHT)/.test(v('status')) ? 'IN_FLIGHT' : 'DEPARTURE';
    if (phase === 'IN_FLIGHT') code = p.boarded ? 'IN_FLIGHT' : 'ASK_FOR_HELP';
    if (phase === 'ARRIVAL') code = !p.boarded && !p.landed ? 'ASK_FOR_HELP' : p.hasCheckedBaggage && !p.baggageCollected ? 'BAGGAGE_CLAIM' : 'EXIT_AIRPORT';
    if (p.needsAssistance) code = 'ASK_FOR_HELP';
    var cancelled = v('status') === 'CANCELLED', gateKnown = !!v('gate') && !conflict('gate');
    var includes = !cancelled && phase === 'DEPARTURE' && gateKnown && (code === 'GO_TO_GATE' || code === 'BOARD_NOW');
    var headings = {
      GO_TO_GATE: gateKnown ? l('请前往 '+field('gate')+' 登机口','Go to gate '+field('gate'),'Ve a la puerta '+field('gate')) : l('请确认登机口','Confirm your gate','Confirma tu puerta'),
      BOARD_NOW: gateKnown ? l('请在 '+field('gate')+' 登机口登机','Board at gate '+field('gate'),'Embarca en la puerta '+field('gate')) : l('请确认登机口后登机','Confirm the gate before boarding','Confirma la puerta antes de embarcar'),
      SECURITY: l('请前往安检','Go to security','Ve al control de seguridad'),
      CHECK_IN_QUESTION: l('请确认是否已值机','Confirm your check-in status','Confirma si has facturado'),
      CONFIRM_FLIGHT: l('请确认航班','Confirm your flight','Confirma tu vuelo'),
      CHECK_IN: l('请办理值机','Check in','Ve a facturar'),
      BAG_DROP: l('请托运行李','Drop off your baggage','Entrega el equipaje'),
      BAGGAGE_QUESTION: l('请确认是否托运行李','Confirm checked baggage','Confirma el equipaje facturado'),
      IN_FLIGHT: l('飞行中，请留意机组指引','In flight · Follow cabin crew guidance','En vuelo · Sigue las indicaciones de la tripulación'),
      BAGGAGE_CLAIM: l('请前往行李提取区','Go to baggage claim','Ve a recoger el equipaje'),
      EXIT_AIRPORT: l('请前往机场出口','Go to the airport exit','Ve a la salida del aeropuerto'),
      WAIT: l('请留意屏幕及广播','Watch the screens and announcements','Consulta las pantallas y escucha los avisos'),
      ASK_FOR_HELP: phase !== 'DEPARTURE' && !p.boarded ? l('航班已离港，请联系航空公司','Flight departed · Contact the airline','El vuelo ha salido · Contacta con la aerolínea') : l('请联系工作人员','Ask a member of staff','Pide ayuda al personal')
    };
    var heading = cancelled ? l('航班已取消，请联系航空公司','Flight cancelled · Contact the airline','Vuelo cancelado · Contacta con la aerolínea') : headings[code] || l('请核对当前行程','Check your journey','Revisa tu viaje');
    if (cancelled) code = 'CANCELLED';
    var dep = v('estimatedDeparture') || v('scheduledDeparture') || v('documentScheduledDeparture') || f.timeContext && (f.timeContext.estimatedDeparture || f.timeContext.scheduledDeparture || f.timeContext.effectiveDeparture), arr = v('estimatedArrival') || v('scheduledArrival');
    var offset = /([+-])(\d\d):(\d\d)$/.exec((phase === 'DEPARTURE' ? dep : arr) || '');
    var minutes = offset ? (offset[1] === '-' ? -1 : 1) * (+offset[2]*60 + +offset[3]) : 0;
    function clock(v) { var n=epoch(v), off=/([+-])(\d\d):(\d\d)$/.exec(v || ''); var m=off ? (off[1]==='-'?-1:1)*(+off[2]*60 + +off[3]) : minutes; return n ? new Date((n+m*60)*1000).toISOString().slice(11,16) : missing; }
    var updated = (f.liveDataSource === 'DOCUMENT_FALLBACK' ? 0 : epoch(f.dataRetrievedAt || f.retrievedAt)) || prev.updatedAt || 0;
    var freshness = epoch(f.dataLastUpdatedAt || f.freshestSourceUpdatedAt) || updated;
    var ends = epoch(v('actualArrival') || arr);
    if (ends) ends += 21600;
    else if (epoch(dep)) ends = epoch(dep)+86400;
    else {
      var target = new Date(f.departureDate+'T00:00:00Z').getTime()+172800000;
      var zone = zones && zones[f.departureAirport] || 'UTC';
      // Resolve the next local midnight using IANA rules, including DST transitions.
      var local = new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
      var guess=target;
      for(var i=0;i<3;i++) { var wall=local.format(new Date(guess)).replace(' ','T')+'Z'; guess += target-Date.parse(wall); }
      ends = Math.floor(guess/1000)-1;
    }
    var dt = v('estimatedDeparture') ? 'ESTIMATED' : 'SCHEDULED', at = v('estimatedArrival') ? 'ESTIMATED' : 'SCHEDULED';
    var depLabel = dt === 'ESTIMATED' ? l('预计起飞','Estimated departure','Salida estimada') : l('计划起飞','Scheduled departure','Salida prevista');
    if (cancelled) depLabel = dt === 'ESTIMATED' ? l('原预计起飞','Former estimated departure','Anterior salida estimada') : l('原计划起飞','Original scheduled departure','Salida prevista original');
    var boardingLabel = cancelled ? l('原登机时间','Original boarding','Embarque original') : l('登机时间','Boarding','Embarque');
    var arrLabel = at === 'ESTIMATED' ? l('预计到达','Estimated arrival','Llegada estimada') : l('计划到达','Scheduled arrival','Llegada prevista');
    if (cancelled) arrLabel = at === 'ESTIMATED' ? l('原预计到达','Former estimated arrival','Anterior llegada estimada') : l('原计划到达','Original scheduled arrival','Llegada prevista original');
    var facts = {};
    ['gate','terminal','arrivalTerminal','arrivalBaggageBelt','status','boardingTime','documentBoardingTime','estimatedDeparture','scheduledDeparture','documentScheduledDeparture','estimatedArrival','scheduledArrival','actualDeparture','actualArrival'].forEach(function(k) { facts[k]=v(k); });
    facts.boardingTime = v('boardingTime') || f.timeContext && f.timeContext.boardingTime || v('documentBoardingTime') || '';
    var boarding = conflict('boardingTime') ? uncertain : clock(v('boardingTime') || f.timeContext && f.timeContext.boardingTime || v('documentBoardingTime'));
    return { journeyKey:o.journeyKey, flightNumber:(f.flightNumber || '').replace(/^([A-Z]{2}|[A-Z][0-9]|[0-9][A-Z]|[A-Z]{3})(\d)/,'$1 $2'), flightDate:f.departureDate || '', departureAirport:f.departureAirport || '', arrivalAirport:f.arrivalAirport || '',
      dateText:f.departureDate ? new Intl.DateTimeFormat(lang === 'zh' ? 'zh-CN' : lang,{month:'short',day:'numeric',timeZone:'UTC'}).format(new Date(f.departureDate+'T12:00:00Z')) : '',
      phase:phase,actionCode:code,nextStep:heading,headingIncludesGate:includes,terminal:field('terminal'),gate:field('gate'),boardingTime:boarding,
      departureTime:conflict(v('estimatedDeparture') ? 'estimatedDeparture' : 'scheduledDeparture') ? uncertain : clock(dep),departureTimeType:dt,arrivalTime:conflict(v('estimatedArrival') ? 'estimatedArrival' : 'scheduledArrival') ? uncertain : clock(arr),arrivalTimeType:at,arrivalTerminal:field('arrivalTerminal'),baggageBelt:field('arrivalBaggageBelt'),locale:lang,
      metricOneLabel:phase === 'DEPARTURE' ? includes ? l('航站楼','Terminal','Terminal') : l('航站楼 / 登机口','Terminal / Gate','Terminal / Puerta') : l('到达航站楼','Arrival terminal','Terminal de llegada'),
      metricOneValue:phase === 'DEPARTURE' ? includes ? field('terminal') : field('terminal')+' · '+field('gate') : field('arrivalTerminal'),
      metricTwoLabel:phase === 'DEPARTURE' ? boardingLabel : l('行李转盘','Baggage belt','Cinta de equipaje'),metricTwoValue:phase === 'DEPARTURE' ? boarding : field('arrivalBaggageBelt'),
      metricThreeLabel:phase === 'DEPARTURE' ? depLabel : arrLabel,metricThreeValue:conflict(phase === 'DEPARTURE' ? v('estimatedDeparture') ? 'estimatedDeparture' : 'scheduledDeparture' : v('estimatedArrival') ? 'estimatedArrival' : 'scheduledArrival') ? uncertain : clock(phase === 'DEPARTURE' ? dep : arr),
      timezoneText:phase === 'DEPARTURE' ? l('时间为出发机场当地时间','Departure airport local time','Hora local del aeropuerto de salida') : l('时间为目的地当地时间','Destination local time','Hora local del destino'),
      updatedText:updated ? clock(new Date(updated*1000).toISOString())+l(' 更新',' updated',' actualizado') : l('尚未核验','Not verified','Sin verificar'),
      staleLabel:l('可打开 App 刷新','Open app to refresh','Abre la app para actualizar'),offlineLabel:l('联网后可刷新','Refresh when online','Actualiza al conectarte'),missingLabel:missing,
      stale:!!(f.stale || o.stale || f.timeContext && f.timeContext.stale || o.offline || Date.now()/1000 >= freshness+600),offline:!!o.offline,
      updatedAt:updated,staleAt:freshness+600,journeyEndsAt:ends,snapshotVersion:o.snapshotVersion || 0,clientRevision:o.clientRevision || 0,facts:facts };
  }
  return {build:build};
}));
