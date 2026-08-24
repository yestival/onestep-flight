(function () {
  'use strict';

  var LOCALE_KEY = 'onestep-flight-locale';
  // Leave enough room for the request and render to complete within the
  // product's 15-second family-update promise.
  var POLL_INTERVAL_MS = 13000;
  var TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
  var currentLocale = normalizeLocale(readStoredLocale());
  var currentJourney = null;
  var currentView = 'loading';
  var pollTimer = null;
  var requestInFlight = false;
  var viewToken = readViewToken();

  var translations = {
    zh: {
      pageTitle: 'OneStep Flight · 家属行程', brandSubtitle: '一步乘机', languageLabel: '选择语言',
      loadingTitle: '正在读取共享行程', loadingLead: '请稍候，我们正在取得最新一步。',
      sharedJourney: '家属共享行程', currentStep: '现在要做的事', autoUpdates: '自动更新', destinationLabel: '现在前往',
      flight: '航班', routeLabel: '航线', departureDate: '出发日期', completedSteps: '已完成步骤', completedCount: '已完成 {count} 个步骤',
      knownLocation: '已确认的地点', lastUpdated: '最近更新', airportReminder: '请以机场屏幕和工作人员的最新指引为准。',
      completeTitle: '这段行程已完成', completeLead: '家属已完成本次乘机指引。', stoppedTitle: '共享已停止',
      stoppedLead: '旅客已经停止分享这段行程。', expiredTitle: '共享链接已过期', expiredLead: '为保护隐私，这个链接已不再提供行程信息。',
      errorTitle: '暂时无法读取行程', errorLead: '请检查网络连接，然后重试。', invalidTitle: '这个共享链接无效',
      invalidLead: '请让旅客重新发送正确的共享链接。', notFoundTitle: '找不到这段共享行程', notFoundLead: '链接可能不完整，请向旅客确认。', retry: '重试',
      privacyNote: '此页面只显示完成乘机协助所需的最少信息。', updatedAnnouncement: '共享行程已更新。',
      terminal: '航站楼', gate: '登机口', checkIn: '值机柜台', bagDrop: '托运行李柜台', baggageBelt: '行李转盘',
      terminalValue: '{value}号航站楼', gateValue: '{value}登机口', checkInValue: '{value}号柜台', checkInAreaValue: '{value}区值机柜台',
      checkInAreaCountersValue: '{area}区 {counters}号值机柜台', bagDropAreaCountersValue: '{area}区 {counters}号柜台',
      bagDropValue: '{value}号托运行李柜台', baggageBeltValue: '{value}号行李转盘',
      justNow: '刚刚', minutesAgo: '{count}分钟前', hoursAgo: '{count}小时前', daysAgo: '{count}天前', unknownTime: '更新时间未知',
      statusNormal: '航班正常', statusBoarding: '正在登机', statusDelayed: '航班延误', statusDeparted: '已经起飞', statusArrived: '已经到达', statusCancelled: '航班取消',
      actionCheckInTitle: '先办理值机', actionCheckInDescription: '请前往航空公司值机区域，准备好旅行文件。',
      actionBagDropTitle: '现在托运行李', actionBagDropDescription: '请把需要托运的行李交给航空公司。',
      actionSecurityTitle: '现在前往安检', actionSecurityDescription: '请准备好旅行文件，按照机场指引通过安检。',
      actionGateTitle: '现在前往登机口', actionGateDescription: '请查看机场屏幕，并前往航班登机口。',
      actionWaitTitle: '现在原地等候', actionWaitDescription: '请留意机场屏幕和广播，等待下一步。',
      actionBoardTitle: '现在登机', actionBoardDescription: '请准备好旅行文件，按照工作人员指引登机。',
      actionHelpTitle: '请向工作人员求助', actionHelpDescription: '请把这个页面出示给机场工作人员。',
      actionFlightTitle: '现在安心乘机', actionFlightDescription: '航班正在途中。到达后，我们会继续提示下一步。',
      actionBaggageTitle: '现在领取行李', actionBaggageDescription: '请前往行李提取区，并核对屏幕上的航班信息。',
      actionExitTitle: '现在前往出口', actionExitDescription: '请带好随身物品和行李，按照出口标志离开机场。',
      destinationCheckIn: '航空公司值机区域', destinationBagDrop: '托运行李柜台', destinationSecurity: '安检入口', destinationGate: '航班登机口',
      destinationWait: '当前安全位置', destinationStaff: '机场工作人员服务台', destinationFlight: '飞往{airport}', destinationBaggage: '行李提取区', destinationExit: '机场出口',
      completedCheckIn: '办理值机', completedBagDrop: '托运行李', completedSecurity: '通过安检', completedGate: '到达登机口', completedBoard: '完成登机', completedFlight: '抵达目的地', completedBaggage: '领取行李', completedExit: '离开机场'
    },
    en: {
      pageTitle: 'OneStep Flight · Shared journey', brandSubtitle: 'Journey update', languageLabel: 'Choose language',
      loadingTitle: 'Loading the shared journey', loadingLead: 'Please wait while we get the latest step.',
      sharedJourney: 'Journey shared with family', currentStep: 'What to do now', autoUpdates: 'Updates automatically', destinationLabel: 'Go to',
      flight: 'Flight', routeLabel: 'Route', departureDate: 'Departure date', completedSteps: 'Completed steps', completedCount: '{count} steps completed',
      knownLocation: 'Confirmed locations', lastUpdated: 'Last updated', airportReminder: 'Always follow the latest airport screens and staff instructions.',
      completeTitle: 'This journey is complete', completeLead: 'Your family member has completed this journey.', stoppedTitle: 'Sharing has stopped',
      stoppedLead: 'The traveller has stopped sharing this journey.', expiredTitle: 'This sharing link has expired', expiredLead: 'To protect privacy, this link no longer shows journey information.',
      errorTitle: 'The journey cannot be loaded right now', errorLead: 'Check your connection, then try again.', invalidTitle: 'This sharing link is invalid',
      invalidLead: 'Ask the traveller to send the correct sharing link again.', notFoundTitle: 'This shared journey was not found', notFoundLead: 'The link may be incomplete. Please check with the traveller.', retry: 'Try again',
      privacyNote: 'This page shows only the minimum information needed to help with the journey.', updatedAnnouncement: 'The shared journey has been updated.',
      terminal: 'Terminal', gate: 'Gate', checkIn: 'Check-in', bagDrop: 'Bag drop', baggageBelt: 'Baggage belt',
      terminalValue: 'Terminal {value}', gateValue: 'Gate {value}', checkInValue: 'Counters {value}', checkInAreaValue: 'Check-in area {value}',
      checkInAreaCountersValue: 'Check-in area {area}, counters {counters}', bagDropAreaCountersValue: 'Area {area}, counters {counters}',
      bagDropValue: 'Bag drop {value}', baggageBeltValue: 'Baggage belt {value}',
      justNow: 'just now', minutesAgo: '{count} min ago', hoursAgo: '{count} hr ago', daysAgo: '{count} days ago', unknownTime: 'update time unavailable',
      statusNormal: 'Flight on time', statusBoarding: 'Boarding now', statusDelayed: 'Flight delayed', statusDeparted: 'Flight departed', statusArrived: 'Flight arrived', statusCancelled: 'Flight cancelled',
      actionCheckInTitle: 'Check in first', actionCheckInDescription: 'Go to the airline check-in area and have your travel document ready.',
      actionBagDropTitle: 'Drop off checked bags now', actionBagDropDescription: 'Give your checked baggage to the airline.',
      actionSecurityTitle: 'Go through security now', actionSecurityDescription: 'Have your travel document ready and follow the airport signs through security.',
      actionGateTitle: 'Go to the gate now', actionGateDescription: 'Check the airport screens and go to your flight gate.',
      actionWaitTitle: 'Wait here for now', actionWaitDescription: 'Watch the airport screens and listen for announcements.',
      actionBoardTitle: 'Board the plane now', actionBoardDescription: 'Have your travel document ready and follow staff instructions to board.',
      actionHelpTitle: 'Ask airport staff for help', actionHelpDescription: 'Show this page to an airport staff member.',
      actionFlightTitle: 'Continue your flight', actionFlightDescription: 'The flight is on the way. The next step will appear after arrival.',
      actionBaggageTitle: 'Collect baggage now', actionBaggageDescription: 'Go to baggage claim and confirm the flight on the airport screen.',
      actionExitTitle: 'Go to the exit now', actionExitDescription: 'Take your belongings and baggage and follow the exit signs.',
      destinationCheckIn: 'Airline check-in area', destinationBagDrop: 'Bag drop', destinationSecurity: 'Security entrance', destinationGate: 'Flight gate',
      destinationWait: 'Your current safe location', destinationStaff: 'Airport help desk', destinationFlight: 'Flight to {airport}', destinationBaggage: 'Baggage claim', destinationExit: 'Airport exit',
      completedCheckIn: 'Checked in', completedBagDrop: 'Dropped off baggage', completedSecurity: 'Passed security', completedGate: 'Reached the gate', completedBoard: 'Boarded the flight', completedFlight: 'Arrived at destination', completedBaggage: 'Collected baggage', completedExit: 'Left the airport'
    },
    es: {
      pageTitle: 'OneStep Flight · Viaje compartido', brandSubtitle: 'Un paso para volar', languageLabel: 'Elegir idioma',
      loadingTitle: 'Cargando el viaje compartido', loadingLead: 'Espera mientras obtenemos el paso más reciente.',
      sharedJourney: 'Viaje compartido con la familia', currentStep: 'Qué hacer ahora', autoUpdates: 'Se actualiza automáticamente', destinationLabel: 'Ve a',
      flight: 'Vuelo', routeLabel: 'Ruta', departureDate: 'Fecha de salida', completedSteps: 'Pasos completados', completedCount: '{count} pasos completados',
      knownLocation: 'Lugares confirmados', lastUpdated: 'Última actualización', airportReminder: 'Sigue siempre las pantallas del aeropuerto y las indicaciones más recientes del personal.',
      completeTitle: 'Este viaje ha terminado', completeLead: 'Tu familiar ha completado este viaje.', stoppedTitle: 'Se dejó de compartir',
      stoppedLead: 'La persona que viaja dejó de compartir este viaje.', expiredTitle: 'Este enlace ha caducado', expiredLead: 'Para proteger la privacidad, el enlace ya no muestra información del viaje.',
      errorTitle: 'No se puede cargar el viaje ahora', errorLead: 'Comprueba la conexión y vuelve a intentarlo.', invalidTitle: 'Este enlace no es válido',
      invalidLead: 'Pide a la persona que viaja que vuelva a enviar el enlace correcto.', notFoundTitle: 'No se encontró este viaje compartido', notFoundLead: 'Es posible que el enlace esté incompleto. Confírmalo con la persona que viaja.', retry: 'Volver a intentar',
      privacyNote: 'Esta página muestra solo la información mínima necesaria para ayudar con el viaje.', updatedAnnouncement: 'El viaje compartido se ha actualizado.',
      terminal: 'Terminal', gate: 'Puerta', checkIn: 'Facturación', bagDrop: 'Entrega de equipaje', baggageBelt: 'Cinta de equipaje',
      terminalValue: 'Terminal {value}', gateValue: 'Puerta {value}', checkInValue: 'Mostradores {value}', checkInAreaValue: 'Zona de facturación {value}',
      checkInAreaCountersValue: 'Zona de facturación {area}, mostradores {counters}', bagDropAreaCountersValue: 'Zona {area}, mostradores {counters}',
      bagDropValue: 'Entrega de equipaje {value}', baggageBeltValue: 'Cinta de equipaje {value}',
      justNow: 'ahora mismo', minutesAgo: 'hace {count} min', hoursAgo: 'hace {count} h', daysAgo: 'hace {count} días', unknownTime: 'hora de actualización no disponible',
      statusNormal: 'Vuelo puntual', statusBoarding: 'Embarcando ahora', statusDelayed: 'Vuelo retrasado', statusDeparted: 'Vuelo despegado', statusArrived: 'Vuelo llegado', statusCancelled: 'Vuelo cancelado',
      actionCheckInTitle: 'Primero, factura', actionCheckInDescription: 'Ve a la zona de facturación de la aerolínea y prepara tu documento de viaje.',
      actionBagDropTitle: 'Entrega ahora el equipaje', actionBagDropDescription: 'Entrega a la aerolínea el equipaje que vas a facturar.',
      actionSecurityTitle: 'Pasa ahora el control de seguridad', actionSecurityDescription: 'Prepara tu documento de viaje y sigue las señales del aeropuerto.',
      actionGateTitle: 'Ve ahora a la puerta', actionGateDescription: 'Consulta las pantallas del aeropuerto y ve a la puerta de tu vuelo.',
      actionWaitTitle: 'Espera aquí por ahora', actionWaitDescription: 'Mira las pantallas del aeropuerto y escucha los avisos.',
      actionBoardTitle: 'Sube ahora al avión', actionBoardDescription: 'Prepara tu documento de viaje y sigue las indicaciones del personal.',
      actionHelpTitle: 'Pide ayuda al personal', actionHelpDescription: 'Muestra esta página a un miembro del personal del aeropuerto.',
      actionFlightTitle: 'Continúa el vuelo', actionFlightDescription: 'El vuelo está en camino. El siguiente paso aparecerá después de llegar.',
      actionBaggageTitle: 'Recoge ahora el equipaje', actionBaggageDescription: 'Ve a la recogida de equipaje y confirma el vuelo en la pantalla del aeropuerto.',
      actionExitTitle: 'Ve ahora a la salida', actionExitDescription: 'Recoge tus pertenencias y equipaje, y sigue las señales de salida.',
      destinationCheckIn: 'Zona de facturación de la aerolínea', destinationBagDrop: 'Entrega de equipaje', destinationSecurity: 'Control de seguridad', destinationGate: 'Puerta del vuelo',
      destinationWait: 'Tu ubicación segura actual', destinationStaff: 'Punto de ayuda del aeropuerto', destinationFlight: 'Vuelo a {airport}', destinationBaggage: 'Recogida de equipaje', destinationExit: 'Salida del aeropuerto',
      completedCheckIn: 'Facturación completada', completedBagDrop: 'Equipaje entregado', completedSecurity: 'Control de seguridad superado', completedGate: 'Llegada a la puerta', completedBoard: 'Embarque completado', completedFlight: 'Llegada al destino', completedBaggage: 'Equipaje recogido', completedExit: 'Salida del aeropuerto'
    }
  };

  var actions = {
    CHECK_IN: ['actionCheckInTitle', 'actionCheckInDescription', 'destinationCheckIn'],
    BAG_DROP: ['actionBagDropTitle', 'actionBagDropDescription', 'destinationBagDrop'],
    SECURITY: ['actionSecurityTitle', 'actionSecurityDescription', 'destinationSecurity'],
    GO_TO_GATE: ['actionGateTitle', 'actionGateDescription', 'destinationGate'],
    WAIT: ['actionWaitTitle', 'actionWaitDescription', 'destinationWait'],
    BOARD_NOW: ['actionBoardTitle', 'actionBoardDescription', 'destinationGate'],
    ASK_FOR_HELP: ['actionHelpTitle', 'actionHelpDescription', 'destinationStaff'],
    IN_FLIGHT: ['actionFlightTitle', 'actionFlightDescription', 'destinationFlight'],
    BAGGAGE_CLAIM: ['actionBaggageTitle', 'actionBaggageDescription', 'destinationBaggage'],
    EXIT_AIRPORT: ['actionExitTitle', 'actionExitDescription', 'destinationExit']
  };

  var completedLabels = {
    CHECK_IN: 'completedCheckIn', BAG_DROP: 'completedBagDrop', SECURITY: 'completedSecurity', GO_TO_GATE: 'completedGate',
    WAIT: null, BOARD_NOW: 'completedBoard', IN_FLIGHT: 'completedFlight', BAGGAGE_CLAIM: 'completedBaggage', EXIT_AIRPORT: 'completedExit'
  };

  function normalizeLocale(value) { return value === 'en' || value === 'es' ? value : 'zh'; }
  function readStoredLocale() {
    try { return localStorage.getItem(LOCALE_KEY) || document.documentElement.lang; } catch (error) { return document.documentElement.lang; }
  }
  function storeLocale(locale) { try { localStorage.setItem(LOCALE_KEY, locale); } catch (error) { /* Page remains usable. */ } }
  function t(key, params, locale) {
    var language = translations[normalizeLocale(locale || currentLocale)];
    var value = language[key] == null ? translations.en[key] : language[key];
    return String(value == null ? '' : value).replace(/\{(\w+)\}/g, function (_, name) {
      return params && params[name] != null ? String(params[name]) : '';
    });
  }

  function readViewToken() {
    var parts = window.location.pathname.split('/').filter(Boolean);
    if (parts.length !== 2 || parts[0] !== 'share') return null;
    try { return decodeURIComponent(parts[1]); } catch (error) { return null; }
  }

  function setView(name) {
    var changed = currentView !== name;
    currentView = name;
    document.querySelectorAll('[data-view]').forEach(function (element) { element.hidden = element.dataset.view !== name; });
    var heading = document.querySelector('[data-view="' + name + '"] h1');
    if (heading && changed) { heading.setAttribute('tabindex', '-1'); heading.focus({ preventScroll: true }); }
  }

  function updateLanguage() {
    document.documentElement.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    document.title = t('pageTitle');
    var select = document.getElementById('share-locale');
    select.value = currentLocale;
    select.setAttribute('aria-label', t('languageLabel'));
    document.querySelectorAll('[data-i18n]').forEach(function (element) { element.textContent = t(element.dataset.i18n); });
    document.querySelectorAll('[data-i18n-aria]').forEach(function (element) { element.setAttribute('aria-label', t(element.dataset.i18nAria)); });
    if (currentJourney) renderJourney(currentJourney, false);
    if (currentView === 'error') renderError(window.shareErrorKind || 'network');
  }

  function safeCode(value) { return typeof value === 'string' ? value.trim().toUpperCase() : ''; }
  function cleanValue(value) {
    if (value == null) return null;
    var text = String(value).trim();
    return text && text !== '-' && text !== '—' ? text : null;
  }
  function stripPrefix(value, pattern) { var text = cleanValue(value); return text ? text.replace(pattern, '').trim() : null; }
  function formatTerminal(value, locale) {
    var clean = stripPrefix(value, /^(terminal|航站楼|航廈|terminal de)\s*/i);
    return clean ? t('terminalValue', { value: clean }, locale) : null;
  }
  function formatGate(value, locale) {
    var clean = stripPrefix(value, /^(gate|登机口|登機口|puerta)\s*/i);
    return clean ? t('gateValue', { value: clean }, locale) : null;
  }
  function parseDeskParts(value) {
    var text = cleanValue(value);
    if (!text) return null;
    text = text.replace(/[‐‑‒–—−-]/g, '–').trim();
    text = text
      .replace(/^(?:check[– ]?in|bag(?:gage)?\s*drop|值机|值機|托运行李|托運行李|facturación|entrega de equipaje)\s*/i, '')
      .replace(/^(?:area|zone|zona(?:\s+de\s+facturación)?)\s*/i, '')
      .replace(/^(?:counters?|desks?|柜台|櫃檯|mostradores?)\s*/i, '');
    var composite = /^([A-Za-z])(?:\s*(?:区|區|area|zone|zona))?\s*(?:[,;/]|\s)\s*(?:counters?|desks?|柜台|櫃檯|mostradores?)?\s*([0-9A-Za-z]+(?:\s*–\s*[0-9A-Za-z]+)?)\s*(?:号|號)?(?:柜台|櫃檯)?$/i.exec(text);
    if (composite) {
      return { area: composite[1].toUpperCase(), counters: composite[2].replace(/\s*–\s*/g, '–') };
    }
    var areaOnly = /^([A-Za-z])(?:\s*(?:区|區|area|zone|zona))?$/i.exec(text);
    if (areaOnly) return { area: areaOnly[1].toUpperCase(), counters: null };
    var countersOnly = /^([0-9A-Za-z]+(?:\s*–\s*[0-9A-Za-z]+)?)\s*(?:号|號)?(?:柜台|櫃檯)?$/i.exec(text);
    if (countersOnly) return { area: null, counters: countersOnly[1].replace(/\s*–\s*/g, '–') };
    return { area: null, counters: text };
  }
  function formatCounter(value, locale) {
    var parts = parseDeskParts(value);
    if (!parts) return null;
    if (parts.area && parts.counters) return t('checkInAreaCountersValue', parts, locale);
    if (parts.area) return t('checkInAreaValue', { value: parts.area }, locale);
    return parts.counters ? t('checkInValue', { value: parts.counters }, locale) : null;
  }
  function formatBagDrop(value, locale) {
    var parts = parseDeskParts(value);
    if (!parts) return null;
    if (parts.area && parts.counters) return t('bagDropAreaCountersValue', parts, locale);
    if (parts.area) return t('bagDropValue', { value: parts.area }, locale);
    return parts.counters ? t('bagDropValue', { value: parts.counters }, locale) : null;
  }
  function formatBelt(value, locale) {
    var clean = stripPrefix(value, /^(baggage\s*(belt|carousel)|belt|carousel|行李转盘|行李轉盤|cinta de equipaje)\s*/i);
    return clean ? t('baggageBeltValue', { value: clean }, locale) : null;
  }

  function actionDestination(data, locale) {
    var code = safeCode(data.actionCode);
    if (code === 'CHECK_IN') return formatCounter(data.checkInDeskOrZone, locale) || formatTerminal(data.terminal, locale) || t('destinationCheckIn', null, locale);
    if (code === 'BAG_DROP') return formatBagDrop(data.dedicatedBagDropDesk, locale) || formatCounter(data.checkInDeskOrZone, locale) || t('destinationBagDrop', null, locale);
    if (code === 'GO_TO_GATE' || code === 'BOARD_NOW' || code === 'WAIT') return formatGate(data.gate, locale) || t(actions[code][2], null, locale);
    if (code === 'IN_FLIGHT') return t('destinationFlight', { airport: cleanValue(data.arrivalAirport) || '' }, locale).trim();
    if (code === 'BAGGAGE_CLAIM') return formatBelt(data.arrivalBaggageBelt, locale) || t('destinationBaggage', null, locale);
    var mapped = actions[code];
    return mapped ? t(mapped[2], null, locale) : null;
  }

  function statusLabel(value) {
    var code = safeCode(value);
    if (['SCHEDULED', 'EXPECTED', 'ON_TIME'].indexOf(code) >= 0) return t('statusNormal');
    if (code === 'BOARDING') return t('statusBoarding');
    if (code === 'DELAYED') return t('statusDelayed');
    if (code === 'DEPARTED') return t('statusDeparted');
    if (code === 'ARRIVED') return t('statusArrived');
    if (code === 'CANCELLED' || code === 'CANCELED') return t('statusCancelled');
    return null;
  }

  function completedStepValues(value) {
    if (Array.isArray(value)) return value.map(safeCode).filter(function (code) { return completedLabels[code]; });
    if (Number.isFinite(Number(value))) return Number(value);
    return 0;
  }

  function completedSummary(value) {
    var normalized = completedStepValues(value);
    if (Array.isArray(normalized)) {
      return normalized.map(function (code) { return t(completedLabels[code]); }).join(' · ') || t('completedCount', { count: 0 });
    }
    return t('completedCount', { count: Math.max(0, normalized) });
  }

  function formatDate(value) {
    var text = cleanValue(value);
    if (!text) return '—';
    var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
    if (!match) return text;
    var date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
    return new Intl.DateTimeFormat(currentLocale === 'zh' ? 'zh-CN' : currentLocale, { year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC' }).format(date);
  }

  function relativeUpdated(value) {
    var date = value ? new Date(value) : null;
    if (!date || !Number.isFinite(date.getTime())) return t('unknownTime');
    var minutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
    if (minutes < 1) return t('justNow');
    if (minutes < 60) return t('minutesAgo', { count: minutes });
    var hours = Math.floor(minutes / 60);
    if (hours < 24) return t('hoursAgo', { count: hours });
    return t('daysAgo', { count: Math.floor(hours / 24) });
  }

  function addLocation(list, labelKey, value) {
    if (!value) return;
    var row = document.createElement('div');
    var term = document.createElement('dt');
    var detail = document.createElement('dd');
    term.textContent = t(labelKey);
    detail.textContent = value;
    row.appendChild(term);
    row.appendChild(detail);
    list.appendChild(row);
  }

  function renderJourney(data, announceChange) {
    currentJourney = data;
    if (data.completed || !cleanValue(data.actionCode)) {
      document.querySelector('[data-complete-flight]').textContent = [cleanValue(data.flightNumber), cleanValue(data.departureAirport), cleanValue(data.arrivalAirport)].filter(Boolean).join(' · ');
      document.querySelector('[data-complete-flight]').hidden = !cleanValue(data.flightNumber);
      setView('complete');
      return;
    }
    var action = actions[safeCode(data.actionCode)] || actions.ASK_FOR_HELP;
    document.querySelector('[data-action-title]').textContent = t(action[0]);
    document.querySelector('[data-action-description]').textContent = t(action[1]);
    var destination = actionDestination(data, currentLocale);
    var destinationEnglish = currentLocale === 'en' ? null : actionDestination(data, 'en');
    document.querySelector('[data-destination-primary]').textContent = destination || '';
    document.querySelector('[data-destination-english]').textContent = destinationEnglish || '';
    document.querySelector('[data-destination-english]').hidden = !destinationEnglish || destinationEnglish === destination;
    document.querySelector('[data-destination-block]').hidden = !destination;
    document.querySelector('.action-card').dataset.urgency = ['CRITICAL', 'URGENT', 'IMMEDIATE'].indexOf(safeCode(data.urgency)) >= 0 ? 'urgent' : 'normal';

    document.querySelector('[data-flight-number]').textContent = cleanValue(data.flightNumber) || '—';
    document.querySelector('[data-departure-airport]').textContent = cleanValue(data.departureAirport) || '—';
    document.querySelector('[data-arrival-airport]').textContent = cleanValue(data.arrivalAirport) || '—';
    document.querySelector('[data-departure-date]').textContent = formatDate(data.departureDate);
    document.querySelector('[data-completed-steps]').textContent = completedSummary(data.completedSteps);
    var status = statusLabel(data.status);
    document.querySelector('[data-flight-status]').textContent = status || '';
    document.querySelector('[data-flight-status]').hidden = !status;

    var list = document.querySelector('[data-location-list]');
    list.replaceChildren();
    addLocation(list, 'terminal', formatTerminal(data.terminal, currentLocale));
    addLocation(list, 'gate', formatGate(data.gate, currentLocale));
    addLocation(list, 'checkIn', formatCounter(data.checkInDeskOrZone, currentLocale));
    addLocation(list, 'bagDrop', formatBagDrop(data.dedicatedBagDropDesk, currentLocale));
    if (safeCode(data.actionCode) === 'BAGGAGE_CLAIM' || safeCode(data.actionCode) === 'EXIT_AIRPORT') {
      addLocation(list, 'baggageBelt', formatBelt(data.arrivalBaggageBelt, currentLocale));
    }
    document.querySelector('[data-location-card]').hidden = !list.children.length;
    document.querySelector('[data-last-updated]').textContent = relativeUpdated(data.lastUpdated);
    setView('journey');
    if (announceChange) announce(t('updatedAnnouncement'));
  }

  function renderError(kind) {
    window.shareErrorKind = kind;
    var titleKey = kind === 'invalid' ? 'invalidTitle' : kind === 'notFound' ? 'notFoundTitle' : 'errorTitle';
    var leadKey = kind === 'invalid' ? 'invalidLead' : kind === 'notFound' ? 'notFoundLead' : 'errorLead';
    document.querySelector('[data-error-title]').textContent = t(titleKey);
    document.querySelector('[data-error-lead]').textContent = t(leadKey);
    document.querySelector('[data-action="retry"]').hidden = kind === 'invalid' || kind === 'notFound';
    setView('error');
  }

  function announce(message) {
    var region = document.querySelector('[data-announcer]');
    region.textContent = '';
    window.setTimeout(function () { region.textContent = message; }, 30);
  }

  function responseErrorCode(response) {
    return response.json().then(function (body) { return body && cleanValue(body.code || body.errorCode); }).catch(function () { return null; });
  }

  function loadJourney(options) {
    options = options || {};
    if (!viewToken || !TOKEN_PATTERN.test(viewToken)) { renderError('invalid'); return Promise.resolve(); }
    if (requestInFlight) return Promise.resolve();
    requestInFlight = true;
    return fetch('/api/share-sessions/' + encodeURIComponent(viewToken), { headers: { Accept: 'application/json' }, cache: 'no-store' })
      .then(function (response) {
        if (response.ok) return response.json();
        if (response.status === 410) {
          return responseErrorCode(response).then(function (code) {
            if (safeCode(code) === 'SHARE_SESSION_EXPIRED') setView('expired'); else setView('stopped');
            throw { handled: true };
          });
        }
        if (response.status === 404) { renderError('notFound'); throw { handled: true }; }
        throw new Error('Share request failed with status ' + response.status);
      })
      .then(function (data) {
        var previousMarker = currentJourney ? [currentJourney.actionCode, currentJourney.lastUpdated, currentJourney.completed].join('|') : null;
        var nextMarker = [data.actionCode, data.lastUpdated, data.completed].join('|');
        renderJourney(data, Boolean(options.poll && previousMarker && previousMarker !== nextMarker));
      })
      .catch(function (error) { if (!error || !error.handled) renderError('network'); })
      .finally(function () { requestInFlight = false; });
  }

  function startPolling() {
    window.clearInterval(pollTimer);
    pollTimer = window.setInterval(function () {
      if (!document.hidden && currentView !== 'stopped' && currentView !== 'expired') loadJourney({ poll: true });
    }, POLL_INTERVAL_MS);
  }

  document.getElementById('share-locale').addEventListener('change', function (event) {
    currentLocale = normalizeLocale(event.target.value);
    storeLocale(currentLocale);
    updateLanguage();
  });
  document.querySelector('[data-action="retry"]').addEventListener('click', function () { setView('loading'); loadJourney(); });
  document.addEventListener('visibilitychange', function () { if (!document.hidden && currentView === 'journey') loadJourney({ poll: true }); });

  updateLanguage();
  loadJourney();
  startPolling();
}());
