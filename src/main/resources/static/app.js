(function () {
  'use strict';

  var platform = window.OneStepPlatform;
  var localStorage = platform ? platform.storage : window.localStorage;
  var nativeApp = Boolean(platform && platform.isNative);
  if (nativeApp) document.getElementById('main-content').inert = true;
  var liveActivity = nativeApp ? window.OneStepLiveActivity(platform.live) : null;
  var pageInitialized = false;
  var lastRecheckAt = 0;
  var lastLockState = null;
  var pushSyncFingerprint = null;
  var serverSnapshotVersion = 0;

  function apiFetch(url, options) {
    return platform ? platform.apiFetch(url, options) : fetch(url, options);
  }

  var STORAGE_KEY = 'onestep-flight-journey-v2';
  var OFFLINE_STORAGE_KEY = 'onestep-flight-offline-journey-v1';
  var QA_STORAGE_KEY = 'onestep-flight-qa-journey-v1';
  var QA_OFFLINE_STORAGE_KEY = 'onestep-flight-qa-offline-journey-v1';
  var LOCALE_KEY = 'onestep-flight-locale';
  var SHARE_SESSION_KEY = 'onestep-flight-share-session-v1';
  var SHARE_PENDING_KEY = 'onestep-flight-share-pending-v1';
  var SHARE_REVOCATION_PENDING_KEY = 'onestep-flight-share-revocation-pending-v1';
  var QA_SHARE_SESSION_KEY = 'onestep-flight-qa-share-session-v1';
  var QA_SHARE_PENDING_KEY = 'onestep-flight-qa-share-pending-v1';
  var QA_SHARE_REVOCATION_PENDING_KEY = 'onestep-flight-qa-share-revocation-pending-v1';
  var DEMO_JOURNEY_PREFIX = 'onestep-flight-demo-journey-v1:';
  var DEMO_OFFLINE_PREFIX = 'onestep-flight-demo-offline-journey-v1:';
  var DEMO_SHARE_PREFIX = 'onestep-flight-demo-share-session-v1:';
  var DEMO_SHARE_PENDING_PREFIX = 'onestep-flight-demo-share-pending-v1:';
  var DEMO_SHARE_REVOCATION_PENDING_PREFIX = 'onestep-flight-demo-share-revocation-pending-v1:';
  var DEMO_CONTROL_PREFIX = 'onestep-flight-demo-control-v1:';
  var OFFLINE_MAX_AGE_MS = 48 * 60 * 60 * 1000;
  var currentScreen = 'welcome';
  var navigationHistory = [];
  var journeyHistory = [];
  var currentLocale = detectInitialLocale();
  var loadingTimer;
  var loadingTimeout;
  var activeLoadingMessages = null;
  var activeLoadingMessageIndex = 0;
  var requestSequence = 0;
  var currentFlight = null;
  var currentAction = null;
  var journeyContext = null;
  var currentError = null;
  var lastLookupContext = null;
  var retryRequest = null;
  var overlayReturnFocus = null;
  var pendingDocument = null;
  var journeyEvents = [];
  var conflictsConfirmed = false;
  var clockAnchor = null;
  var clockTimer = null;
  var refreshStatusState = null;
  var restoredFromOffline = false;
  var offlineSnapshotSavedAt = null;
  var expiredJourneyDiscarded = false;
  var currentDestinationCopy = null;
  var shareSession = null;
  var shareSyncTimer = null;
  var shareCreationInFlight = false;
  var shareCreationPromise = null;
  var shareCreationJourneyKey = null;
  var shareCreationGeneration = 0;
  var pngCardCache = null;
  var pngCardPreparing = null;
  var pngCardGeneration = 0;
  var arrivalRefreshFlightKey = null;
  var documentSegments = [];
  var usableDocumentSegmentIndexes = [];
  var selectedDocumentSegmentIndex = null;
  var awaitingDocumentSelection = false;
  var documentRootFields = {};
  var documentRootReviewFields = [];
  var documentRootReviewIssues = [];
  var confirmedDocumentFlightNumberIndexes = [];
  var flightRequestController = null;
  var lookupInFlight = false;
  var speakingActive = false;
  var speechGeneration = 0;
  var automaticSpeechTimer = null;
  var lastAutomaticSpeechKey = null;
  var languageChangeInProgress = false;
  var checkInStatusAnswer = null;
  var flightCorrectionOpen = false;
  var partialDocumentEntry = false;
  var fieldStaffHelpContext = null;
  var staffOverlayView = 'CARD';
  var staffReturnFocusElement = null;
  var airportMapCache = Object.create(null);
  var airportContactCache = Object.create(null);
  var airportIdentityCache = Object.create(null);
  var airportResourceInFlight = Object.create(null);
  var airportResourceGeneration = 0;
  var airportResourceMode = null;
  var airportResourceOrigin = null;
  var airportResourceState = 'IDLE';
  var airportResourceResult = null;
  var airportResourceContext = null;
  var airportResourceReturnFocus = null;
  var airportResourceHistoryActive = false;
  var airportResourceHistoryClosing = false;
  var manualCandidateEntryFields = { flight_number: false, departure_date: false };
  var AUTO_REFRESH_FAR_MS = 300000;
  var AUTO_REFRESH_NEAR_MS = 120000;
  var AUTO_REFRESH_BOUNDARY_MS = 90 * 60 * 1000;
  var autoRefreshTimer = null;
  var autoRefreshDueAt = null;
  var stepChangeAlert = null;
  var displayedFlightChanges = [];
  var displayedFlightChangeFlight = null;
  var pendingFlightChangeSpeechBatch = null;
  var lastFlightChangeSignature = '';
  var presentedFlightChangeSignatures = Object.create(null);
  var flightChangeDismissTimer = null;
  var journeyProblemState = { hasConnection: null, busy: false, answer: null, responseLocale: null };
  var journeyProblemRecognition = null;
  var journeyProblemRequestController = null;
  var journeyProblemRequestGeneration = 0;
  var demoScenariosEnabled = new URLSearchParams(window.location.search).get('demoScenarios') === 'true';
  var demoFlightEnabled = demoScenariosEnabled
    || new URLSearchParams(window.location.search).get('demo') === 'true';
  var activeDemoScenarioId = null;
  var qaJourneyEnabled = new URLSearchParams(window.location.search).get('qaJourney') === 'true';
  var requestedDemoSessionId = new URLSearchParams(window.location.search).get('demoSession');
  var demoRecordingSessionId = null;
  var demoRecordingChannel = null;
  var demoRecordingCountdownTimer = null;
  var demoRecordingCountdownSeconds = null;
  var activeDialog = null;
  var screens = document.querySelectorAll('.screen');
  var fileInput = document.getElementById('ticket-file');

  var translations = {
    zh: {
      eyebrow: '你的乘机助手', welcomeTitle: '现在，只做下一步', welcomeLead: '上传旅行文件或输入航班信息，我们每次只告诉你现在应该做什么。',
      upload: '上传预订确认、电子客票或登机牌', uploadHint: '支持 PDF、JPG、JPEG、PNG，也可以上传航空公司预订确认页截图、电子客票行程单或登机牌。', manual: '手动输入航班号', family: '分享行程给家属', privacy: '旅行文件只用于生成乘机指引',
      languageLabel: '选择语言', back: '返回', backStep: '返回上一步', fileLabel: '选择预订确认、电子客票或登机牌文件', loadingTitle: '正在准备你的乘机步骤', checkDetails: '请确认', confirmTitle: '这是你的航班吗？', departure: '计划起飞', terminal: '航站楼', gate: '登机口', status: '状态', notPublished: '尚未公布', demoDisclaimer: '演示数据', yesContinue: '是，继续', editFlight: '不是，我要修改', manualContext: '填写航班信息', baggageContext: '确认行李情况', manualTitle: '输入你的航班信息', manualLead: '请输入航班号和出发日期。', flightLabel: '航班号', dateLabel: '出发日期', flightPlaceholder: '例如 BA 117', searchLiveFlight: '查询真实航班', useDemoFlight: '使用 BA 117 演示航班', demoHint: '演示数据', nextAction: '现在要做的事', baggageYes: '有，需要托运', baggageNo: '没有', baggageUnsure: '我不确定', speak: '朗读当前步骤', staff: '请工作人员帮忙', contactFamily: '分享行程给家属', endJourney: '结束本次指引', askForHelp: '请工作人员帮忙', destination: '现在前往', couldNotLoad: '暂时无法读取', errorTitle: '实时航班状态暂未更新', errorLead: '请检查输入或网络连接，然后重试。你也可以手动输入航班。', retry: '重试', enterManually: '手动输入航班', askStaff: '请工作人员帮忙', technicalDetails: '查看技术详情', returnHome: '返回首页', endConfirmTitle: '确定要结束本次指引吗？', endConfirmLead: '结束后，当前步骤记录会从这台设备清除。', continueJourney: '继续指引', confirmEnd: '结束并返回首页', refreshLive: '刷新实时信息', refreshArrival: '重新查询行李转盘', reviewNeeded: '需要你确认', reviewCorrect: '这项信息正确', reviewEdit: '信息不对，手动修改', reviewOnSite: '这项未读取到，稍后现场确认', reviewWrongOnSite: '这项不对，标记为现场确认', currentStep: '当前步骤', boardConfirmTitle: '确认你已经登机吗？', boardConfirmLead: '确认后将进入机上模式，抵达后仍会继续指引。', confirmBoard: '是，我已经登机', cancelBoard: '返回，再检查一下', landedConfirmTitle: '确认你已经下飞机吗？', landedConfirmLead: '确认后，我们会继续指引你领取行李或前往出口。', confirmLanded: '是，我已经下飞机', cancelLanded: '返回，再检查一下', flightDuration: '预计飞行时间', estimatedArrival: '预计到达', timeUntilArrival: '距离预计到达', destinationLocalTime: '目的地当地时间', estimatedDeparture: '预计起飞', boardingTime: '登机时间', seat: '座位', airportLocalTime: '机场当地时间', timeUntilDeparture: '距离预计起飞', journeyTimeline: '查看本次行程记录', scenarioTitle: '模拟场景', scenarioLead: '仅供本地流程演示。', conflictTitle: '信息有关键冲突，请先停下来', conflictLead: '请向机场工作人员核对文件和实时屏幕后，再继续。', confirmConflicts: '我已向工作人员核对，继续', checkInArea: '值机 / 托运区域', close: '关闭', familyTitle: '分享行程给家属', familyLead: '通过系统分享面板分享 48 小时有效的安全链接或脱敏行程图片。', localhostWarning: '当前是 localhost：其他设备无法打开这个地址。请部署到可访问的网址后再分享。', shareLink: '分享链接', sharePng: '分享图片'
    },
    en: {
      eyebrow: 'Your flight companion', welcomeTitle: 'Now, take the next step', welcomeLead: 'Upload a travel document or enter flight details. We show one action at a time.', upload: 'Upload booking confirmation, e-ticket, or boarding pass', uploadHint: 'PDF, JPG, JPEG, and PNG are supported, including booking screenshots, e-ticket itineraries, and boarding passes.', manual: 'Enter flight number manually', family: 'Share journey with family', privacy: 'Your travel document is only used to create flight guidance', languageLabel: 'Choose language', back: 'Back', backStep: 'Go back', fileLabel: 'Choose a booking confirmation, e-ticket, or boarding pass', loadingTitle: 'Preparing your next step', checkDetails: 'Please confirm', confirmTitle: 'Is this your flight?', departure: 'Scheduled departure', terminal: 'Terminal', gate: 'Gate', status: 'Status', notPublished: 'Not published', demoDisclaimer: 'Demo data', yesContinue: 'Yes, continue', editFlight: 'No, edit it', manualContext: 'Enter flight details', baggageContext: 'Confirm your baggage', manualTitle: 'Enter your flight details', manualLead: 'Enter the flight number and departure date.', flightLabel: 'Flight number', dateLabel: 'Departure date', flightPlaceholder: 'For example BA 117', searchLiveFlight: 'Search live flight', useDemoFlight: 'Use BA 117 demo flight', demoHint: 'Demo data', nextAction: 'Your next action', baggageYes: 'Yes, I have bags to check', baggageNo: 'No', baggageUnsure: 'I am not sure', speak: 'Read this step aloud', staff: 'Ask a member of staff', contactFamily: 'Share journey with family', endJourney: 'End this journey', askForHelp: 'Ask a member of staff', destination: 'Go now to', couldNotLoad: 'Unable to load', errorTitle: 'We cannot continue right now', errorLead: 'Check your details or connection, then try again. You can also enter the flight manually or ask staff for help.', retry: 'Try again', enterManually: 'Enter flight manually', askStaff: 'Ask a member of staff', technicalDetails: 'View technical details', returnHome: 'Return home', endConfirmTitle: 'End this journey?', endConfirmLead: 'Your current step history will be cleared from this device.', continueJourney: 'Continue journey', confirmEnd: 'End and return home', refreshLive: 'Refresh live information', refreshArrival: 'Check baggage belt again', reviewNeeded: 'Please confirm this', reviewCorrect: 'This information is correct', reviewEdit: 'This is wrong; edit manually', reviewOnSite: 'Not found; confirm it at the airport', reviewWrongOnSite: 'This is wrong; confirm it at the airport', currentStep: 'Current step', boardConfirmTitle: 'Have you boarded the aircraft?', boardConfirmLead: 'After confirmation, onboard mode will continue guiding you through arrival.', confirmBoard: 'Yes, I have boarded', cancelBoard: 'Go back and check again', landedConfirmTitle: 'Have you left the aircraft?', landedConfirmLead: 'After confirmation, guidance will continue to baggage claim or the exit.', confirmLanded: 'Yes, I have left the aircraft', cancelLanded: 'Go back and check again', flightDuration: 'Estimated flight time', estimatedArrival: 'Expected arrival', timeUntilArrival: 'Until expected arrival', destinationLocalTime: 'Destination local time', estimatedDeparture: 'Estimated departure', boardingTime: 'Boarding time', seat: 'Seat', airportLocalTime: 'Airport local time', timeUntilDeparture: 'Until expected departure', journeyTimeline: 'View this journey timeline', scenarioTitle: 'Simulated scenarios', scenarioLead: 'For local journey demonstrations only.', conflictTitle: 'Critical details conflict — stop here', conflictLead: 'Confirm the document and live screen with airport staff before continuing.', confirmConflicts: 'I confirmed with staff; continue', checkInArea: 'Check-in / bag-drop area', close: 'Close', familyTitle: 'Share journey with family', familyLead: 'Create a read-only link that remains active for 48 hours. It includes only essential flight and current-step details.', localhostWarning: 'This is localhost. Other devices cannot open this address. Deploy the app to an accessible URL before sharing.', shareLink: 'Share link', sharePng: 'Share image'
    },
    es: {
      eyebrow: 'Tu asistente de vuelo', welcomeTitle: 'Ahora, solo el siguiente paso', welcomeLead: 'Sube un documento de viaje o introduce los datos del vuelo. Mostramos una sola acción cada vez.', upload: 'Subir confirmación de reserva, billete electrónico o tarjeta de embarque', uploadHint: 'Se admiten PDF, JPG, JPEG y PNG, incluidas capturas de reserva, itinerarios electrónicos y tarjetas de embarque.', manual: 'Introducir el vuelo manualmente', family: 'Compartir el viaje con la familia', privacy: 'Tu documento solo se usa para crear las indicaciones del viaje', languageLabel: 'Elegir idioma', back: 'Volver', backStep: 'Volver', fileLabel: 'Elegir confirmación, billete electrónico o tarjeta de embarque', loadingTitle: 'Preparando tu siguiente paso', checkDetails: 'Confirma los datos', confirmTitle: '¿Es este tu vuelo?', departure: 'Salida programada', terminal: 'Terminal', gate: 'Puerta', status: 'Estado', notPublished: 'Aún no publicado', demoDisclaimer: 'Datos de demostración', yesContinue: 'Sí, continuar', editFlight: 'No, modificar', manualContext: 'Datos del vuelo', baggageContext: 'Confirmar el equipaje', manualTitle: 'Introduce los datos de tu vuelo', manualLead: 'Introduce el número de vuelo y la fecha de salida.', flightLabel: 'Número de vuelo', dateLabel: 'Fecha de salida', flightPlaceholder: 'Por ejemplo BA 117', searchLiveFlight: 'Buscar vuelo real', useDemoFlight: 'Usar vuelo BA 117 de demostración', demoHint: 'Datos de demostración', nextAction: 'Tu siguiente acción', baggageYes: 'Sí, tengo equipaje para facturar', baggageNo: 'No', baggageUnsure: 'No estoy seguro', speak: 'Leer este paso', staff: 'Pedir ayuda al personal', contactFamily: 'Compartir el viaje con la familia', endJourney: 'Finalizar este viaje', askForHelp: 'Pedir ayuda al personal', destination: 'Ve ahora a', couldNotLoad: 'No se puede cargar', errorTitle: 'No podemos continuar ahora', errorLead: 'Comprueba los datos o la conexión y vuelve a intentarlo. También puedes introducir el vuelo o pedir ayuda.', retry: 'Reintentar', enterManually: 'Introducir vuelo manualmente', askStaff: 'Pedir ayuda al personal', technicalDetails: 'Ver detalles técnicos', returnHome: 'Volver al inicio', endConfirmTitle: '¿Finalizar este viaje?', endConfirmLead: 'El historial del paso actual se borrará de este dispositivo.', continueJourney: 'Continuar el viaje', confirmEnd: 'Finalizar y volver al inicio', refreshLive: 'Actualizar información', refreshArrival: 'Volver a consultar la cinta', reviewNeeded: 'Confirma este dato', reviewCorrect: 'Este dato es correcto', reviewEdit: 'No es correcto; modificar', reviewOnSite: 'No encontrado; confirmar en el aeropuerto', reviewWrongOnSite: 'No es correcto; confirmar en el aeropuerto', currentStep: 'Paso actual', boardConfirmTitle: '¿Ya has embarcado?', boardConfirmLead: 'Tras confirmar, el modo a bordo seguirá guiándote hasta la llegada.', confirmBoard: 'Sí, ya he embarcado', cancelBoard: 'Volver y comprobar', landedConfirmTitle: '¿Ya has bajado del avión?', landedConfirmLead: 'Tras confirmar, te guiaremos a la recogida de equipaje o a la salida.', confirmLanded: 'Sí, ya he bajado', cancelLanded: 'Volver y comprobar', flightDuration: 'Duración estimada', estimatedArrival: 'Llegada estimada', timeUntilArrival: 'Tiempo hasta la llegada', destinationLocalTime: 'Hora local del destino', estimatedDeparture: 'Salida estimada', boardingTime: 'Hora de embarque', seat: 'Asiento', airportLocalTime: 'Hora local del aeropuerto', timeUntilDeparture: 'Tiempo hasta la salida', journeyTimeline: 'Ver el historial del viaje', scenarioTitle: 'Escenarios simulados', scenarioLead: 'Solo para probar el recorrido local.', conflictTitle: 'Hay un conflicto importante: detente', conflictLead: 'Confirma el documento y la pantalla con el personal antes de continuar.', confirmConflicts: 'Lo confirmé con el personal; continuar', checkInArea: 'Zona de facturación / entrega', close: 'Cerrar', familyTitle: 'Compartir el viaje con la familia', familyLead: 'Crea un enlace de solo lectura válido durante 48 horas. Solo incluye el vuelo y el paso actual imprescindibles.', localhostWarning: 'Esta dirección es localhost y no se puede abrir en otro dispositivo. Despliega la aplicación en una URL accesible antes de compartir.', shareLink: 'Compartir enlace', sharePng: 'Compartir imagen'
    }
  };
  translations.zh.technicalDetails = '调试详情';
  translations.en.technicalDetails = 'Debug details';
  translations.es.technicalDetails = 'Detalles de depuración';
  Object.assign(translations.zh, {
    journeyProblem: '航程出问题', journeyProblemCloseAria: '关闭航程问题卡片',
    journeyProblemConnectionQuestion: '这班之后还要转机吗？', journeyProblemConnectionYes: '要转机',
    journeyProblemConnectionYesAria: '要转机，这班之后我还有另一班航班',
    journeyProblemConnectionNo: '不转机', journeyProblemConnectionNoAria: '不转机，这是我的最后一班航班',
    journeyProblemTransferPrompt: '请尽力说明下一班的起飞时间、航班号和目的地。',
    journeyProblemStatementLabel: '请说明遇到的情况', journeyProblemPlaceholder: '请说明遇到的情况',
    journeyProblemVoice: '语音输入', journeyProblemVoiceAria: '使用语音说明情况', journeyProblemListening: '正在听',
    journeyProblemSpeechFailed: '未识别成功', journeyProblemSubmit: '提交情况', journeyProblemLoading: '正在寻找解决方案……',
    journeyProblemAlert: '可点击页面最下方的“航程出问题”。',
    journeyProblemAlertDeparted: '如果你还没有上飞机，请点击页面最下方的“航程出问题”。',
    journeyProblemConfirmStaff: '请与航空公司工作人员确认。',
    journeyProblemFallback: '先别着急，请保留登机牌和航班信息，并把你刚才说明的情况告诉航空公司工作人员，请他们协助确认下一步安排。'
  });
  Object.assign(translations.en, {
    journeyProblem: 'Flight problem', journeyProblemCloseAria: 'Close flight problem card',
    journeyProblemConnectionQuestion: 'Do you have another flight after this one?',
    journeyProblemConnectionYes: 'Yes', journeyProblemConnectionYesAria: 'Yes, I have another flight after this one',
    journeyProblemConnectionNo: 'No', journeyProblemConnectionNoAria: 'No, this is my last flight',
    journeyProblemTransferPrompt: 'Please give the next flight’s departure time, flight number, and destination as fully as possible.',
    journeyProblemStatementLabel: 'Describe what happened', journeyProblemPlaceholder: 'Describe what happened',
    journeyProblemVoice: 'Voice input', journeyProblemVoiceAria: 'Describe the problem by voice', journeyProblemListening: 'Listening',
    journeyProblemSpeechFailed: 'Not recognized', journeyProblemSubmit: 'Submit details', journeyProblemLoading: 'Finding a solution…',
    journeyProblemAlert: 'Select “Flight problem” at the bottom of the page.',
    journeyProblemAlertDeparted: 'If you have not boarded, select “Flight problem” at the bottom of the page.',
    journeyProblemConfirmStaff: 'Please confirm with airline staff.',
    journeyProblemFallback: 'Please don’t worry. Keep your boarding pass and flight details, and explain the situation you described to airline staff so they can help confirm the next step.'
  });
  Object.assign(translations.es, {
    journeyProblem: 'Problema con el viaje', journeyProblemCloseAria: 'Cerrar la tarjeta del problema de viaje',
    journeyProblemConnectionQuestion: '¿Tienes que hacer conexión después de este vuelo?',
    journeyProblemConnectionYes: 'Sí', journeyProblemConnectionYesAria: 'Sí, tengo otro vuelo después de este',
    journeyProblemConnectionNo: 'No', journeyProblemConnectionNoAria: 'No, este es mi último vuelo',
    journeyProblemTransferPrompt: 'Indica lo mejor posible la hora de salida, el número de vuelo y el destino del siguiente vuelo.',
    journeyProblemStatementLabel: 'Describe lo ocurrido', journeyProblemPlaceholder: 'Describe lo ocurrido',
    journeyProblemVoice: 'Entrada por voz', journeyProblemVoiceAria: 'Describe el problema por voz', journeyProblemListening: 'Escuchando',
    journeyProblemSpeechFailed: 'No se reconoció', journeyProblemSubmit: 'Enviar información', journeyProblemLoading: 'Buscando una solución…',
    journeyProblemAlert: 'Selecciona «Problema con el viaje» al final de la página.',
    journeyProblemAlertDeparted: 'Si todavía no has subido al avión, selecciona «Problema con el viaje» al final de la página.',
    journeyProblemConfirmStaff: 'Confírmalo con el personal de la aerolínea.',
    journeyProblemFallback: 'No te preocupes. Conserva tu tarjeta de embarque y los datos del vuelo, y explica al personal de la aerolínea la situación que has descrito para que pueda confirmar el siguiente paso.'
  });

  var staffContent = {
    titleZh: '请帮我完成下一步', titleEn: 'Please help with my next step', titleEs: 'Ayúdeme con el siguiente paso',
    bodyZh: '请帮我确认现在应该做什么。',
    bodyEn: 'Please help me confirm what I should do now.',
    bodyEs: 'Ayúdeme a confirmar qué debo hacer ahora.'
  };

  var fieldStaffContent = {
    flight_number: {
      titleZh: '请帮我确认航班号',
      titleEn: 'Please help me confirm my flight number',
      titleEs: 'Ayúdeme a confirmar mi número de vuelo',
      bodyZh: '您好，我无法确认这份旅行文件中的航班号。\n请帮我查看这份文件，并告诉我正确的航班号。',
      bodyEn: 'Hello. I cannot confirm the flight number in this travel document.\nPlease check the document and tell me the correct flight number.',
      bodyEs: 'Hola. No puedo confirmar el número de vuelo de este documento de viaje.\nPor favor, revise el documento y dígame el número de vuelo correcto.'
    },
    departure_date: {
      titleZh: '请帮我确认出发日期',
      titleEn: 'Please help me confirm my departure date',
      titleEs: 'Ayúdeme a confirmar mi fecha de salida',
      bodyZh: '您好，我无法确认这份旅行文件中的出发日期。\n请帮我查看这份文件，并告诉我正确的出发日期。',
      bodyEn: 'Hello. I cannot confirm the departure date in this travel document.\nPlease check the document and tell me the correct departure date.',
      bodyEs: 'Hola. No puedo confirmar la fecha de salida de este documento de viaje.\nPor favor, revise el documento y dígame la fecha de salida correcta.'
    },
    flight_information: {
      titleZh: '请帮我核对航班信息',
      titleEn: 'Please help me check my flight information',
      titleEs: 'Ayúdeme a comprobar la información de mi vuelo',
      bodyZh: '系统从旅行文件中读到了多个结果，或缺少航班号和出发日期。请帮我确认正确的航班号和出发日期。',
      bodyEn: 'The system found different results in my travel document, or the flight number and departure date are missing. Please help me confirm the correct flight number and departure date.',
      bodyEs: 'El sistema encontró resultados diferentes en mi documento de viaje, o faltan el número de vuelo y la fecha de salida. Ayúdeme a confirmar el número de vuelo y la fecha de salida correctos.'
    }
  };

  Object.assign(translations.zh, {
    upload: '上传出行文件',
    uploadHint: '支持行程单、电子客票、订单截图或登机牌',
    errorTitle: '实时航班状态暂未更新', errorLead: '请检查输入或网络连接，然后重试。你也可以手动输入航班。',
    fileLabel: '选择行程单、电子客票、订单截图或登机牌',
    welcomeSpeechNotice: '接下来会播放语音提示，请留意音量。你可以随时暂停或关闭声音。',
    speak: '朗读', speakAria: '朗读当前内容', stopSpeaking: '停止', stopSpeakingAria: '停止朗读当前内容',
    helpShort: '求助', helpAria: '请工作人员帮助我', shareShort: '分享', familyAria: '与家属分享实时行程',
    scenarioNormal: '正常', scenarioGateChange: '登机口变更', scenarioDelay: '延误', scenarioCancel: '取消', scenarioStale: '过期数据',
    backStep: '返回上一步', departureLocalTime: '起飞时间（出发地当地时间）', arrivalLocalTime: '到达时间（目的地当地时间）',
    scheduledDepartureLocal: '计划起飞 · 出发地当地时间', estimatedDepartureLocal: '预计起飞 · 出发地当地时间', actualDepartureLocal: '实际起飞 · 出发地当地时间',
    scheduledArrivalLocal: '计划到达 · 目的地当地时间', estimatedArrivalLocal: '预计到达 · 目的地当地时间', actualArrivalLocal: '实际到达 · 目的地当地时间',
    selectedSegment: '当前航段', chooseSegment: '选择这一段', readySegment: '可以查询', incompleteSegment: '补全这一段', pendingSegment: '请确认航班号', confirmSegment: '处理字段冲突',
    itineraryContext: '行程航段', itineraryTitle: '这份文件中的全部航段', itineraryLead: '以下航段按文件中的顺序显示。当前航段已标出。',
    segmentSelectionContext: '行程航段', segmentSelectionTitle: '请选择航段', segmentSelectionLead: '请选择需要查看的航段。',
    itineraryReadyContext: '所有航段均可查询', itineraryReadyTitle: '所有航段均可查询', itineraryReadyLead: '航班号可信、出发日期完整，且没有未解决的关键冲突。请选择一段，再点击“查询真实航班”。',
    itineraryPendingContext: '存在待确认字段', itineraryPendingTitle: '请先确认部分航班号', itineraryPendingLead: '请核对识别到的信息。',
    itineraryMissingContext: '存在缺失字段', itineraryMissingTitle: '请补全部分航段', itineraryMissingLead: '部分航班信息缺失，请补全后继续。',
    itineraryConflictContext: '存在字段冲突', itineraryConflictTitle: '请处理识别冲突', itineraryConflictLead: '识别到了不同的结果，请选择正确的信息。',
    flightCorrectionContext: '重新查找航班', flightCorrectionTitle: '请选择正确的航班', flightCorrectionLead: '没有可靠的其他候选航班。请重新上传文件，或手动输入航班号和日期。', flightCorrectionCandidatesLead: '请选择下面经过实时查询确认的候选航班，或重新上传文件、手动输入。',
    liveStatusUnavailable: '暂时没有更新到实时航班状态。请确认下面是不是你的航班。',
    checkInContext: '确认值机状态', checkInQuestion: '你已经完成值机了吗？', checkInQuestionLead: '已经拿到登机牌也算已完成值机。',
    checkInYes: '是', checkInNo: '否', checkInUnsure: '不确定',
    checkInYesAria: '是，我已经完成值机或拿到登机牌', checkInNoAria: '否，我还没有完成值机', checkInUnsureAria: '我不确定是否已经完成值机',
    baggageYes: '是', baggageNo: '否', baggageUnsure: '不确定', baggageYesAria: '是，我有需要托运的行李', baggageNoAria: '否，我没有需要托运的行李', baggageUnsureAria: '我不确定是否有需要托运的行李',
    yesContinue: '是', editFlight: '不是', yesContinueAria: '是，这是我的航班，继续', editFlightAria: '不是，这不是我的航班，修改信息',
    completeShort: '完成', backStep: '返回', backStepAria: '返回上一步', shareLink: '分享链接', sharePng: '分享图片',
    shareLinkAria: '使用系统分享面板分享安全链接', sharePngAria: '使用系统分享面板分享脱敏行程图片',
    refreshShort: '刷新', refreshLiveAria: '刷新实时航班信息', refreshArrivalAria: '重新查询到达行李转盘',
    staffCardInstruction: '请把这个页面给工作人员看。需要时可联系机场。', fieldStaffHelp: '请工作人员帮忙', familyLead: '通过系统分享面板分享 48 小时有效的安全链接或脱敏行程图片。', globalFooter: '© 2026 OneStep Flight · 无障碍乘机指引',
    allFlightInfo: '查看全部航班信息', flightSourceLabel: '航班信息来源',
    departurePassedTitle: '这趟航班已经离港', departurePassedLead: '点击下方“请工作人员帮忙”，并前往航空公司服务柜台出示给工作人员。', departurePassedStatus: '已离港',
    cancelledFlightTitle: '这趟航班已取消', cancelledFlightLead: '请前往航空公司服务柜台；也可以点击下方“航程出问题”说明你的情况。', cancelledFlightStatus: '已取消',
    departureTimePassedTitle: '这趟航班的起飞时间已过', departureTimePassedLead: '如果你还没有登机，请前往航空公司服务柜台；也可以点击下方“航程出问题”。', departureTimePassedStatus: '起飞时间已过',
    enterNewFlight: '输入新的航班信息', backHome: '返回首页', uploadContext: '选择旅行文件', uploadTitle: '选择或上传文件', chooseFile: '选择文件',
    manualItineraryTitle: '文件中已读到的航段', manualItineraryLead: '请选择需要查看或补全的航段。',
    readySegmentLead: '这一段已具备高置信度或已确认的航班号、完整日期，且没有关键冲突。请点击“查询真实航班”。',
    partialDateLead: '部分航班信息缺失，请补全后继续。', partialFlightLead: '部分航班信息缺失，请补全后继续。',
    pendingFlightLead: '请核对识别到的信息。',
    confirmSegmentLead: '识别到了不同的结果，请选择正确的信息。',
    saveCompletion: '保存补全', confirmFlightNumberLabel: '我已核对并确认这个航班号', confirmDepartureDateLabel: '我已核对并确认这个出发日期',
    readyDocumentNotice: '此航段信息完整。', pendingDocumentNotice: '请核对识别到的信息。', missingDocumentNotice: '部分航班信息缺失，请补全后继续。', conflictDocumentNotice: '识别到了不同的结果，请选择正确的信息。'
  });
  Object.assign(translations.en, {
    upload: 'Upload travel document',
    uploadHint: 'Supports itineraries, e-tickets, order screenshots, or boarding passes',
    errorTitle: 'Flight information is unavailable right now', errorLead: 'Check the details or connection, then try again. You can also enter the flight manually.',
    fileLabel: 'Choose an itinerary, e-ticket, order screenshot, or boarding pass',
    welcomeSpeechNotice: 'Voice guidance will play as you continue. Please check your volume. You can pause or turn it off at any time.',
    speak: 'Read', speakAria: 'Read the current content aloud', stopSpeaking: 'Stop', stopSpeakingAria: 'Stop reading aloud',
    helpShort: 'Help', helpAria: 'Ask a member of staff for help', shareShort: 'Share', familyAria: 'Share the live journey with family',
    scenarioNormal: 'Normal', scenarioGateChange: 'Gate change', scenarioDelay: 'Delay', scenarioCancel: 'Cancellation', scenarioStale: 'Stale data',
    backStep: 'Go back', departureLocalTime: 'Departure (origin local time)', arrivalLocalTime: 'Arrival (destination local time)',
    scheduledDepartureLocal: 'Scheduled departure · Origin local time', estimatedDepartureLocal: 'Estimated departure · Origin local time', actualDepartureLocal: 'Actual departure · Origin local time',
    scheduledArrivalLocal: 'Scheduled arrival · Destination local time', estimatedArrivalLocal: 'Estimated arrival · Destination local time', actualArrivalLocal: 'Actual arrival · Destination local time',
    selectedSegment: 'Current segment', chooseSegment: 'Select this segment', readySegment: 'Ready to search', incompleteSegment: 'Complete this segment', pendingSegment: 'Confirm flight number', confirmSegment: 'Resolve field conflict',
    itineraryContext: 'Journey segments', itineraryTitle: 'Every segment in this document', itineraryLead: 'Segments are shown in document order. Your current segment is marked.',
    segmentSelectionContext: 'Journey segments', segmentSelectionTitle: 'Choose a segment', segmentSelectionLead: 'Choose the segment you want to review.',
    itineraryReadyContext: 'All segments are searchable', itineraryReadyTitle: 'All segments are ready to search', itineraryReadyLead: 'Each flight number is trusted, each departure date is complete, and no critical conflict remains. Choose a segment, then select Search live flight.',
    itineraryPendingContext: 'Some fields need confirmation', itineraryPendingTitle: 'Confirm some flight numbers first', itineraryPendingLead: 'Please check the information we found.',
    itineraryMissingContext: 'Some fields are missing', itineraryMissingTitle: 'Complete some segments first', itineraryMissingLead: 'Some flight information is missing. Please complete it to continue.',
    itineraryConflictContext: 'Some fields conflict', itineraryConflictTitle: 'Resolve recognition conflicts', itineraryConflictLead: 'We found different results. Please choose the correct information.',
    flightCorrectionContext: 'Find another flight', flightCorrectionTitle: 'Choose the correct flight', flightCorrectionLead: 'No reliable alternative flight was found. Upload another file or enter the flight number and date manually.', flightCorrectionCandidatesLead: 'Choose a candidate confirmed by live lookup below, upload another file, or enter the details manually.',
    liveStatusUnavailable: 'Live flight status has not updated yet. Please confirm whether the itinerary below is yours.',
    checkInContext: 'Confirm check-in status', checkInQuestion: 'Have you checked in?', checkInQuestionLead: 'Already having a boarding pass also counts as checked in.',
    checkInYes: 'Yes', checkInNo: 'No', checkInUnsure: 'Unsure',
    checkInYesAria: 'Yes, I have checked in or received my boarding pass', checkInNoAria: 'No, I have not checked in yet', checkInUnsureAria: 'I am unsure whether I have checked in',
    baggageYes: 'Yes', baggageNo: 'No', baggageUnsure: 'Unsure', baggageYesAria: 'Yes, I have baggage to check', baggageNoAria: 'No, I do not have baggage to check', baggageUnsureAria: 'I am unsure whether I have baggage to check',
    yesContinue: 'Yes', editFlight: 'No', yesContinueAria: 'Yes, this is my flight; continue', editFlightAria: 'No, this is not my flight; edit the details',
    completeShort: 'Done', backStep: 'Back', backStepAria: 'Go back to the previous step', shareLink: 'Share link', sharePng: 'Share image',
    shareLinkAria: 'Share the secure link with the system share panel', sharePngAria: 'Share the redacted journey image with the system share panel',
    refreshShort: 'Refresh', refreshLiveAria: 'Refresh live flight information', refreshArrivalAria: 'Check the arrival baggage belt again',
    staffCardInstruction: 'Please show this screen to a member of staff. Contact the airport if needed.', fieldStaffHelp: 'Ask staff for help', familyLead: 'Use the system share panel for a secure link valid for 48 hours or a redacted journey image.', globalFooter: '© 2026 OneStep Flight · Accessible air-travel guidance',
    allFlightInfo: 'View all flight information', flightSourceLabel: 'Flight information source',
    departurePassedTitle: 'This flight has departed', departurePassedLead: 'Select “Ask a member of staff” below, then go to the airline service desk and show it to a staff member.', departurePassedStatus: 'Departed',
    cancelledFlightTitle: 'This flight is cancelled', cancelledFlightLead: 'Go to the airline service desk, or select “Flight problem” below to explain what happened.', cancelledFlightStatus: 'Cancelled',
    departureTimePassedTitle: 'This flight’s departure time has passed', departureTimePassedLead: 'If you have not boarded, go to the airline service desk or select “Flight problem” below.', departureTimePassedStatus: 'Departure time passed',
    enterNewFlight: 'Enter new flight information', backHome: 'Return home', uploadContext: 'Choose a travel document', uploadTitle: 'Choose or upload a file', chooseFile: 'Choose file',
    manualItineraryTitle: 'Segments found in the file', manualItineraryLead: 'Choose a segment to review or complete.',
    readySegmentLead: 'This segment has a high-confidence or user-confirmed flight number, a complete date, and no critical conflict. Select Search live flight.',
    partialDateLead: 'Some flight information is missing. Please complete it to continue.', partialFlightLead: 'Some flight information is missing. Please complete it to continue.',
    pendingFlightLead: 'Please check the information we found.',
    confirmSegmentLead: 'We found different results. Please choose the correct information.',
    saveCompletion: 'Save completion', confirmFlightNumberLabel: 'I checked and confirm this flight number', confirmDepartureDateLabel: 'I checked and confirm this departure date',
    readyDocumentNotice: 'This segment is complete.', pendingDocumentNotice: 'Please check the information we found.', missingDocumentNotice: 'Some flight information is missing. Please complete it to continue.', conflictDocumentNotice: 'We found different results. Please choose the correct information.'
  });
  Object.assign(translations.es, {
    upload: 'Subir documento de viaje',
    uploadHint: 'Admite itinerarios, billetes electrónicos, capturas del pedido o tarjetas de embarque',
    errorTitle: 'La información del vuelo no está disponible ahora', errorLead: 'Comprueba los datos o la conexión y vuelve a intentarlo. También puedes introducir el vuelo manualmente.',
    fileLabel: 'Elegir un itinerario, billete electrónico, captura del pedido o tarjeta de embarque',
    welcomeSpeechNotice: 'La guía de voz se reproducirá durante el proceso. Compruebe el volumen. Puede pausarla o desactivarla en cualquier momento.',
    speak: 'Leer', speakAria: 'Leer en voz alta el contenido actual', stopSpeaking: 'Detener', stopSpeakingAria: 'Detener la lectura en voz alta',
    helpShort: 'Ayuda', helpAria: 'Pedir ayuda al personal', shareShort: 'Familia', familyAria: 'Compartir el viaje en directo con la familia',
    scenarioNormal: 'Normal', scenarioGateChange: 'Cambio de puerta', scenarioDelay: 'Retraso', scenarioCancel: 'Cancelación', scenarioStale: 'Datos antiguos',
    backStep: 'Volver', departureLocalTime: 'Salida (hora local de origen)', arrivalLocalTime: 'Llegada (hora local de destino)',
    scheduledDepartureLocal: 'Salida programada · Hora local de origen', estimatedDepartureLocal: 'Salida estimada · Hora local de origen', actualDepartureLocal: 'Salida real · Hora local de origen',
    scheduledArrivalLocal: 'Llegada programada · Hora local de destino', estimatedArrivalLocal: 'Llegada estimada · Hora local de destino', actualArrivalLocal: 'Llegada real · Hora local de destino',
    selectedSegment: 'Tramo actual', chooseSegment: 'Elegir este tramo', readySegment: 'Listo para buscar', incompleteSegment: 'Completar este tramo', pendingSegment: 'Confirmar número de vuelo', confirmSegment: 'Resolver conflicto',
    itineraryContext: 'Tramos del viaje', itineraryTitle: 'Todos los tramos de este documento', itineraryLead: 'Los tramos aparecen en el orden del documento. El tramo actual está marcado.',
    segmentSelectionContext: 'Tramos del viaje', segmentSelectionTitle: 'Elige un tramo', segmentSelectionLead: 'Elige el tramo que quieras revisar.',
    itineraryReadyContext: 'Todos los tramos se pueden buscar', itineraryReadyTitle: 'Todos los tramos están listos', itineraryReadyLead: 'Cada número de vuelo es fiable, cada fecha está completa y no quedan conflictos críticos. Elige un tramo y pulsa Buscar vuelo real.',
    itineraryPendingContext: 'Hay campos por confirmar', itineraryPendingTitle: 'Confirma algunos números de vuelo', itineraryPendingLead: 'Compruebe la información encontrada.',
    itineraryMissingContext: 'Hay campos incompletos', itineraryMissingTitle: 'Completa algunos tramos', itineraryMissingLead: 'Falta parte de la información del vuelo. Complétela para continuar.',
    itineraryConflictContext: 'Hay campos en conflicto', itineraryConflictTitle: 'Resuelve los conflictos de reconocimiento', itineraryConflictLead: 'Encontramos resultados diferentes. Elija la información correcta.',
    flightCorrectionContext: 'Buscar otro vuelo', flightCorrectionTitle: 'Elige el vuelo correcto', flightCorrectionLead: 'No se encontró otro vuelo fiable. Sube otro archivo o introduce manualmente el vuelo y la fecha.', flightCorrectionCandidatesLead: 'Elige abajo un candidato confirmado mediante consulta en directo, sube otro archivo o introduce los datos manualmente.',
    liveStatusUnavailable: 'El estado del vuelo en directo aún no se ha actualizado. Confirma si el itinerario de abajo es el tuyo.',
    checkInContext: 'Confirmar la facturación', checkInQuestion: '¿Ya has facturado?', checkInQuestionLead: 'Tener ya la tarjeta de embarque también cuenta como haber facturado.',
    checkInYes: 'Sí', checkInNo: 'No', checkInUnsure: 'No sé',
    checkInYesAria: 'Sí, ya he facturado o recibido mi tarjeta de embarque', checkInNoAria: 'No, todavía no he facturado', checkInUnsureAria: 'No sé si ya he facturado',
    baggageYes: 'Sí', baggageNo: 'No', baggageUnsure: 'No sé', baggageYesAria: 'Sí, tengo equipaje para facturar', baggageNoAria: 'No, no tengo equipaje para facturar', baggageUnsureAria: 'No sé si tengo equipaje para facturar',
    yesContinue: 'Sí', editFlight: 'No', yesContinueAria: 'Sí, este es mi vuelo; continuar', editFlightAria: 'No, este no es mi vuelo; modificar los datos',
    completeShort: 'Hecho', backStep: 'Volver', backStepAria: 'Volver al paso anterior', shareLink: 'Compartir enlace', sharePng: 'Compartir imagen',
    shareLinkAria: 'Compartir el enlace seguro con el panel del sistema', sharePngAria: 'Compartir la imagen anonimizada del viaje con el panel del sistema',
    refreshShort: 'Actualizar', refreshLiveAria: 'Actualizar la información del vuelo en directo', refreshArrivalAria: 'Volver a consultar la cinta de equipaje',
    staffCardInstruction: 'Muestre esta pantalla a un miembro del personal. Contacte con el aeropuerto si lo necesita.', fieldStaffHelp: 'Pedir ayuda al personal', familyLead: 'Usa el panel del sistema para compartir un enlace seguro válido durante 48 horas o una imagen anonimizada del viaje.', globalFooter: '© 2026 OneStep Flight · Guía accesible para viajar en avión',
    allFlightInfo: 'Ver toda la información del vuelo', flightSourceLabel: 'Fuente de información del vuelo',
    departurePassedTitle: 'Este vuelo ya ha salido', departurePassedLead: 'Pulsa «Pedir ayuda al personal» y ve al mostrador de atención de la aerolínea para mostrárselo al personal.', departurePassedStatus: 'Ya ha salido',
    cancelledFlightTitle: 'Este vuelo está cancelado', cancelledFlightLead: 'Ve al mostrador de la aerolínea o selecciona «Problema con el viaje» para explicar lo ocurrido.', cancelledFlightStatus: 'Cancelado',
    departureTimePassedTitle: 'La hora de salida de este vuelo ya pasó', departureTimePassedLead: 'Si no has embarcado, ve al mostrador de la aerolínea o selecciona «Problema con el viaje».', departureTimePassedStatus: 'Hora de salida pasada',
    enterNewFlight: 'Introducir un vuelo nuevo', backHome: 'Volver al inicio', uploadContext: 'Elegir un documento de viaje', uploadTitle: 'Elegir o subir un archivo', chooseFile: 'Elegir archivo',
    manualItineraryTitle: 'Tramos encontrados en el archivo', manualItineraryLead: 'Elija un tramo para revisarlo o completarlo.',
    readySegmentLead: 'Este tramo tiene un número de vuelo fiable o confirmado, una fecha completa y ningún conflicto crítico. Pulsa Buscar vuelo real.',
    partialDateLead: 'Falta parte de la información del vuelo. Complétela para continuar.', partialFlightLead: 'Falta parte de la información del vuelo. Complétela para continuar.',
    pendingFlightLead: 'Compruebe la información encontrada.',
    confirmSegmentLead: 'Encontramos resultados diferentes. Elija la información correcta.',
    saveCompletion: 'Guardar datos', confirmFlightNumberLabel: 'He comprobado y confirmo este número de vuelo', confirmDepartureDateLabel: 'He comprobado y confirmo esta fecha de salida',
    readyDocumentNotice: 'Este tramo está completo.', pendingDocumentNotice: 'Compruebe la información encontrada.', missingDocumentNotice: 'Falta parte de la información del vuelo. Complétela para continuar.', conflictDocumentNotice: 'Encontramos resultados diferentes. Elija la información correcta.'
  });
  Object.assign(translations.zh, {
    scenarioTitle: '模拟场景（不是真实航班）', scenarioLead: '选择场景后先加载刷新前快照，再用测试卡触发真实刷新链路。',
    scenarioGroupLocation: '位置变化', scenarioGroupTimeStatus: '时间与状态', scenarioGroupArrival: '到达阶段',
    scenarioNormal: '无变化', scenarioTerminalChange: '航站楼变更', scenarioGateChange: '登机口变更',
    scenarioCheckInCounterChange: '值机柜台变更', scenarioBagDropDeskChange: '托运柜台变更', scenarioBoardingTimeChange: '登机时间变更',
    scenarioDepartureTimeChange: '起飞时间变更', scenarioDelay: '航班延误', scenarioBoarding: '开始登机',
    scenarioCancel: '航班取消', scenarioDeparted: '航班起飞', scenarioDiverted: '航班备降', scenarioReturned: '航班返航',
    scenarioArrivalTimeChange: '到达时间变更', scenarioArrived: '航班到达', scenarioBaggageBeltChange: '行李转盘变更', scenarioStale: '数据过期',
    scenarioTestEyebrow: '演示场景测试', scenarioTestTitle: '当前场景：', scenarioBeforeLabel: '刷新前', scenarioExpectedLabel: '预期变化',
    scenarioTriggerRefresh: '触发模拟刷新', scenarioRestart: '重新开始这个场景',
    scenarioBeforeBaseline: 'BA117 基线业务字段保持不变', scenarioBeforeTerminal: '航站楼 5', scenarioBeforeGate: '登机口 B32',
    scenarioBeforeCheckIn: 'A 区 1–4 号值机柜台', scenarioBeforeBagDrop: '5 号行李托运柜台', scenarioBeforeBoardingTime: '登机时间为起飞前 45 分钟',
    scenarioBeforeDepartureTime: '按计划起飞时间显示', scenarioBeforeOnTime: '状态为准点，预计延误 0 分钟',
    scenarioBeforeArrivalTime: '按计划到达时间显示，预计晚到 0 分钟', scenarioBeforeBelt: '5 号行李转盘', scenarioBeforeFresh: '实时数据为最新状态',
    scenarioExpectedNone: '刷新后没有运营变化', scenarioExpectedTerminal: '航站楼变为 3', scenarioExpectedGate: '登机口变为 C18',
    scenarioExpectedCheckIn: '值机柜台变为 B 区 21–24 号', scenarioExpectedBagDrop: '行李托运柜台变为 26 号', scenarioExpectedBoardingTime: '登机时间推迟 20 分钟',
    scenarioExpectedDepartureTime: '预计起飞时间推迟 20 分钟', scenarioExpectedDelay: '预计起飞推迟并晚点 60 分钟', scenarioExpectedBoarding: '开始登机，下一步变为立即登机',
    scenarioExpectedCancelled: '航班状态变为取消', scenarioExpectedDeparted: '航班状态变为已经起飞', scenarioExpectedDiverted: '航班状态变为备降', scenarioExpectedReturned: '航班状态变为返航',
    scenarioExpectedArrivalTime: '预计到达推迟并晚到 40 分钟', scenarioExpectedArrived: '航班状态变为已经到达', scenarioExpectedBelt: '行李转盘变为 8 号', scenarioExpectedStale: '实时数据变为过期'
  });
  Object.assign(translations.en, {
    scenarioTitle: 'Simulated scenarios (not a real flight)', scenarioLead: 'Each scenario loads a before snapshot first. Use the test card to run the real refresh path.',
    scenarioGroupLocation: 'Location changes', scenarioGroupTimeStatus: 'Time and status', scenarioGroupArrival: 'Arrival stage',
    scenarioNormal: 'No change', scenarioTerminalChange: 'Terminal change', scenarioGateChange: 'Gate change',
    scenarioCheckInCounterChange: 'Check-in counter change', scenarioBagDropDeskChange: 'Bag drop change', scenarioBoardingTimeChange: 'Boarding time change',
    scenarioDepartureTimeChange: 'Departure time change', scenarioDelay: 'Flight delay', scenarioBoarding: 'Boarding starts',
    scenarioCancel: 'Flight cancelled', scenarioDeparted: 'Flight departed', scenarioDiverted: 'Flight diverted', scenarioReturned: 'Flight returned',
    scenarioArrivalTimeChange: 'Arrival time change', scenarioArrived: 'Flight arrived', scenarioBaggageBeltChange: 'Baggage belt change', scenarioStale: 'Stale data',
    scenarioTestEyebrow: 'Scenario test', scenarioTestTitle: 'Current scenario: ', scenarioBeforeLabel: 'Before refresh', scenarioExpectedLabel: 'Expected change',
    scenarioTriggerRefresh: 'Trigger simulated refresh', scenarioRestart: 'Restart this scenario',
    scenarioBeforeBaseline: 'BA117 baseline operational fields stay unchanged', scenarioBeforeTerminal: 'Terminal 5', scenarioBeforeGate: 'Gate B32',
    scenarioBeforeCheckIn: 'Zone A, check-in counters 1–4', scenarioBeforeBagDrop: 'Bag drop counter 5', scenarioBeforeBoardingTime: 'Boarding is 45 minutes before departure',
    scenarioBeforeDepartureTime: 'The scheduled departure time is shown', scenarioBeforeOnTime: 'Status is on time with 0 minutes expected delay',
    scenarioBeforeArrivalTime: 'The scheduled arrival is shown with 0 minutes expected delay', scenarioBeforeBelt: 'Baggage belt 5', scenarioBeforeFresh: 'Live data is up to date',
    scenarioExpectedNone: 'No operational change after refresh', scenarioExpectedTerminal: 'Terminal changes to 3', scenarioExpectedGate: 'Gate changes to C18',
    scenarioExpectedCheckIn: 'Check-in moves to Zone B, counters 21–24', scenarioExpectedBagDrop: 'Bag drop moves to counter 26', scenarioExpectedBoardingTime: 'Boarding time moves 20 minutes later',
    scenarioExpectedDepartureTime: 'Estimated departure moves 20 minutes later', scenarioExpectedDelay: 'Estimated departure moves and delay becomes 60 minutes', scenarioExpectedBoarding: 'Boarding starts and the next action becomes Board now',
    scenarioExpectedCancelled: 'Flight status changes to cancelled', scenarioExpectedDeparted: 'Flight status changes to departed', scenarioExpectedDiverted: 'Flight status changes to diverted', scenarioExpectedReturned: 'Flight status changes to returned',
    scenarioExpectedArrivalTime: 'Estimated arrival moves and delay becomes 40 minutes', scenarioExpectedArrived: 'Flight status changes to arrived', scenarioExpectedBelt: 'Baggage belt changes to 8', scenarioExpectedStale: 'Live data becomes out of date'
  });
  Object.assign(translations.es, {
    scenarioTitle: 'Escenarios simulados (no es un vuelo real)', scenarioLead: 'Cada escenario carga primero la instantánea anterior. Usa la tarjeta para ejecutar la ruta real de actualización.',
    scenarioGroupLocation: 'Cambios de ubicación', scenarioGroupTimeStatus: 'Hora y estado', scenarioGroupArrival: 'Etapa de llegada',
    scenarioNormal: 'Sin cambios', scenarioTerminalChange: 'Cambio de terminal', scenarioGateChange: 'Cambio de puerta',
    scenarioCheckInCounterChange: 'Cambio de mostrador de facturación', scenarioBagDropDeskChange: 'Cambio de entrega de equipaje', scenarioBoardingTimeChange: 'Cambio de hora de embarque',
    scenarioDepartureTimeChange: 'Cambio de hora de salida', scenarioDelay: 'Retraso del vuelo', scenarioBoarding: 'Inicio del embarque',
    scenarioCancel: 'Vuelo cancelado', scenarioDeparted: 'Vuelo despegado', scenarioDiverted: 'Vuelo desviado', scenarioReturned: 'Vuelo de regreso',
    scenarioArrivalTimeChange: 'Cambio de hora de llegada', scenarioArrived: 'Vuelo llegado', scenarioBaggageBeltChange: 'Cambio de cinta de equipaje', scenarioStale: 'Datos desactualizados',
    scenarioTestEyebrow: 'Prueba del escenario', scenarioTestTitle: 'Escenario actual: ', scenarioBeforeLabel: 'Antes de actualizar', scenarioExpectedLabel: 'Cambio esperado',
    scenarioTriggerRefresh: 'Activar actualización simulada', scenarioRestart: 'Reiniciar este escenario',
    scenarioBeforeBaseline: 'Los datos operativos base de BA117 no cambian', scenarioBeforeTerminal: 'Terminal 5', scenarioBeforeGate: 'Puerta B32',
    scenarioBeforeCheckIn: 'Zona A, mostradores de facturación 1–4', scenarioBeforeBagDrop: 'Mostrador de entrega de equipaje 5', scenarioBeforeBoardingTime: 'El embarque es 45 minutos antes de la salida',
    scenarioBeforeDepartureTime: 'Se muestra la salida programada', scenarioBeforeOnTime: 'El estado es puntual, con 0 minutos de retraso previsto',
    scenarioBeforeArrivalTime: 'Se muestra la llegada programada, con 0 minutos de retraso previsto', scenarioBeforeBelt: 'Cinta de equipaje 5', scenarioBeforeFresh: 'Los datos en directo están actualizados',
    scenarioExpectedNone: 'No hay cambios operativos tras actualizar', scenarioExpectedTerminal: 'La terminal cambia a 3', scenarioExpectedGate: 'La puerta cambia a C18',
    scenarioExpectedCheckIn: 'La facturación cambia a la zona B, mostradores 21–24', scenarioExpectedBagDrop: 'La entrega de equipaje cambia al mostrador 26', scenarioExpectedBoardingTime: 'La hora de embarque se retrasa 20 minutos',
    scenarioExpectedDepartureTime: 'La salida estimada se retrasa 20 minutos', scenarioExpectedDelay: 'La salida estimada cambia y el retraso pasa a 60 minutos', scenarioExpectedBoarding: 'Comienza el embarque y el siguiente paso cambia a Embarcar ahora',
    scenarioExpectedCancelled: 'El estado cambia a cancelado', scenarioExpectedDeparted: 'El estado cambia a despegado', scenarioExpectedDiverted: 'El estado cambia a desviado', scenarioExpectedReturned: 'El estado cambia a regreso',
    scenarioExpectedArrivalTime: 'La llegada estimada cambia y el retraso pasa a 40 minutos', scenarioExpectedArrived: 'El estado cambia a llegado', scenarioExpectedBelt: 'La cinta cambia a 8', scenarioExpectedStale: 'Los datos en directo quedan desactualizados'
  });

  Object.assign(translations.zh, {
    viewAirportMap: '查看机场地图', contactAirport: '联系机场', airportMapTitle: '机场地图', airportContactTitle: '联系机场',
    journeyAirportMap: '机场地图', journeyAirportMapAria: '机场地图，查看机场官方地图或位置',
    journeyContactAirport: '联系机场', journeyContactAirportAria: '联系机场，查找机场官方联系方式',
    findingAirportMap: '正在查找机场地图……', findingAirportContact: '正在查找机场联系方式……',
    airportResourceWaitHint: '可能需要等候近一分钟。',
    mapFound: '已找到机场地图。', openOfficialMap: '打开机场地图', mapPageOnly: '已找到机场官方导航页面。',
    viewAirportWebsite: '打开官方导航页面', airportLocationOnly: '暂未找到官方平面图，已找到准确的机场位置。',
    openAirportLocation: '在 Google 地图中查看机场位置', mapNotFound: '暂未找到可核实的机场地图。',
    mapTemporarilyUnavailable: '机场地图暂时不可用，请稍后重试。',
    phoneFound: '已找到机场联系电话。', callAirport: '拨打机场电话',
    phoneNotFound: '暂未找到可核实的通用电话', openAirportContactPage: '打开机场联系页面',
    viewAirportContactPage: '查看机场官方联系页面', contactNotFound: '暂时无法取得机场官方联系方式',
    contactTemporarilyUnavailable: '机场联系方式暂时不可用，请稍后重试。',
    backToCard: '返回大字卡', closeStaffAria: '关闭帮助窗口',
    staffCardInstruction: '请把这个页面给工作人员看。需要时可联系机场。',
    qaAirportTitle: '机场信息查询', qaAirportCode: '机场代码',
    qaAirportCodeHint: '三字母',
    qaAirportTerminalOptional: '航站楼（可选）', qaAirportQuery: '查询',
    qaAirportLoading: '查询中……', qaAirportFailed: '查询失败，请稍后重试。',
    qaAirportContactHeading: '联系机场', qaAirportMapHeading: '机场地图',
    qaAirportTerminalLabel: '航站楼'
  });
  Object.assign(translations.en, {
    viewAirportMap: 'View airport map', contactAirport: 'Contact airport', airportMapTitle: 'Airport map', airportContactTitle: 'Contact airport',
    journeyAirportMap: 'Airport map', journeyAirportMapAria: 'Airport map, view the official airport map or location',
    journeyContactAirport: 'Contact airport', journeyContactAirportAria: 'Contact airport, find official airport contact details',
    findingAirportMap: 'Finding the airport map…', findingAirportContact: 'Finding airport contact details…',
    airportResourceWaitHint: 'This may take up to a minute.',
    mapFound: 'The airport map is ready.', openOfficialMap: 'Open airport map', mapPageOnly: 'An official airport navigation page is ready.',
    viewAirportWebsite: 'Open official navigation page', airportLocationOnly: 'No official floor plan was found. The exact airport location is available.',
    openAirportLocation: 'View airport location in Google Maps', mapNotFound: 'No verified airport map was found.',
    mapTemporarilyUnavailable: 'The airport map is temporarily unavailable. Try again shortly.',
    phoneFound: 'The airport phone number is ready.', callAirport: 'Call airport',
    phoneNotFound: 'No verified general phone number was found.', openAirportContactPage: 'Open airport contact page',
    viewAirportContactPage: 'View official airport contact page', contactNotFound: 'Official airport contact details are unavailable.',
    contactTemporarilyUnavailable: 'Airport contact details are temporarily unavailable. Try again shortly.',
    backToCard: 'Back to large card', closeStaffAria: 'Close help window',
    staffCardInstruction: 'Please show this screen to a member of staff. Contact the airport if needed.',
    qaAirportTitle: 'Airport information lookup', qaAirportCode: 'Airport code',
    qaAirportCodeHint: '3 letters',
    qaAirportTerminalOptional: 'Terminal (optional)', qaAirportQuery: 'Search',
    qaAirportLoading: 'Searching…', qaAirportFailed: 'The lookup failed. Please try again shortly.',
    qaAirportContactHeading: 'Contact airport', qaAirportMapHeading: 'Airport map',
    qaAirportTerminalLabel: 'Terminal'
  });
  Object.assign(translations.es, {
    viewAirportMap: 'Ver mapa del aeropuerto', contactAirport: 'Contactar con el aeropuerto', airportMapTitle: 'Mapa del aeropuerto', airportContactTitle: 'Contactar con el aeropuerto',
    journeyAirportMap: 'Ver mapa', journeyAirportMapAria: 'Ver mapa del aeropuerto',
    journeyContactAirport: 'Contactar', journeyContactAirportAria: 'Contactar con el aeropuerto',
    findingAirportMap: 'Buscando el mapa del aeropuerto…', findingAirportContact: 'Buscando los datos de contacto del aeropuerto…',
    airportResourceWaitHint: 'Esto puede tardar hasta un minuto.',
    mapFound: 'El mapa del aeropuerto está listo.', openOfficialMap: 'Abrir mapa del aeropuerto', mapPageOnly: 'Hay una página oficial de orientación del aeropuerto.',
    viewAirportWebsite: 'Abrir la página oficial de orientación', airportLocationOnly: 'No se encontró un plano oficial. Está disponible la ubicación exacta del aeropuerto.',
    openAirportLocation: 'Ver la ubicación en Google Maps', mapNotFound: 'No se encontró un mapa verificado del aeropuerto.',
    mapTemporarilyUnavailable: 'El mapa del aeropuerto no está disponible temporalmente. Inténtelo de nuevo pronto.',
    phoneFound: 'Se ha encontrado el teléfono del aeropuerto.', callAirport: 'Llamar al aeropuerto',
    phoneNotFound: 'No se encontró un teléfono general verificado.', openAirportContactPage: 'Abrir la página de contacto del aeropuerto',
    viewAirportContactPage: 'Ver la página oficial de contacto', contactNotFound: 'No se pudieron obtener los datos de contacto oficiales del aeropuerto.',
    contactTemporarilyUnavailable: 'Los datos de contacto del aeropuerto no están disponibles temporalmente. Inténtelo de nuevo pronto.',
    backToCard: 'Volver a la tarjeta grande', closeStaffAria: 'Cerrar la ventana de ayuda',
    staffCardInstruction: 'Muestre esta pantalla a un miembro del personal. Contacte con el aeropuerto si lo necesita.',
    qaAirportTitle: 'Consulta de información del aeropuerto', qaAirportCode: 'Código del aeropuerto',
    qaAirportCodeHint: '3 letras',
    qaAirportTerminalOptional: 'Terminal (opcional)', qaAirportQuery: 'Consultar',
    qaAirportLoading: 'Consultando…', qaAirportFailed: 'La consulta falló. Inténtalo de nuevo en breve.',
    qaAirportContactHeading: 'Contactar con el aeropuerto', qaAirportMapHeading: 'Mapa del aeropuerto',
    qaAirportTerminalLabel: 'Terminal'
  });

  Object.assign(translations.zh, { datePlaceholder: '请选择出发日期', preparingPng: '正在准备图片…' });
  Object.assign(translations.en, { datePlaceholder: 'Choose departure date', preparingPng: 'Preparing image…' });
  Object.assign(translations.es, { datePlaceholder: 'Elige la fecha de salida', preparingPng: 'Preparando imagen…' });
  Object.assign(translations.zh, {
    documentUnusableContext: '文件无法识别', documentUnusableTitle: '没有读到航班信息',
    documentUnusableLead: '这份文件中没有读到可用于查询的航班号和出发日期。请换一份更完整、清晰的旅行文件，或手动输入。',
    documentUnusableHint: '可以上传航空公司行程单、预订确认、电子客票、登机牌，或这些文件的完整截图。',
    chooseOtherFile: '选择其他文件', manualFlightNumber: '手动输入航班号', documentUnusableWhy: '为什么没有读到？',
    documentUnusableReason: '文件可能不完整、文字过小或模糊，或者不是受支持的航空公司旅行文件。系统不会猜测缺失的航班号和日期。',
    partialDocumentNotice: '只读到部分信息，请补全后查询'
  });
  Object.assign(translations.en, {
    documentUnusableContext: 'File not recognized', documentUnusableTitle: 'No flight information was found',
    documentUnusableLead: 'No flight number and departure date that can be used for a search were found in this file. Choose a clearer, more complete travel document or enter the details manually.',
    documentUnusableHint: 'You can upload an airline itinerary, booking confirmation, e-ticket, boarding pass, or a complete screenshot of one of these documents.',
    chooseOtherFile: 'Choose another file', manualFlightNumber: 'Enter flight number manually', documentUnusableWhy: 'Why was nothing found?',
    documentUnusableReason: 'The file may be incomplete, the text may be too small or blurred, or it may not be a supported airline travel document. The system will not guess a missing flight number or date.',
    partialDocumentNotice: 'Only some information was found. Complete the details before searching.'
  });
  Object.assign(translations.es, {
    documentUnusableContext: 'No se pudo reconocer el archivo', documentUnusableTitle: 'No se encontró información del vuelo',
    documentUnusableLead: 'No se encontraron en este archivo un número de vuelo y una fecha de salida que puedan usarse para la búsqueda. Elige un documento de viaje más completo y claro o introduce los datos manualmente.',
    documentUnusableHint: 'Puedes subir un itinerario de la aerolínea, una confirmación de reserva, un billete electrónico, una tarjeta de embarque o una captura completa de uno de estos documentos.',
    chooseOtherFile: 'Elegir otro archivo', manualFlightNumber: 'Introducir el número de vuelo', documentUnusableWhy: '¿Por qué no se encontró nada?',
    documentUnusableReason: 'Es posible que el archivo esté incompleto, que el texto sea demasiado pequeño o borroso, o que no sea un documento de viaje de una aerolínea compatible. El sistema no inventará el número de vuelo ni la fecha que falten.',
    partialDocumentNotice: 'Solo se encontró parte de la información. Completa los datos antes de buscar.'
  });

  Object.assign(translations.zh, {
    documentRecognitionWaiting: '正在识别，请耐心等待',
    liveFlightLookupWaiting: '正在查询实时航班，请耐心等待',
    confidenceAdvisory: '请核对识别到的信息。',
    auxiliaryAdvisory: '请核对识别到的信息。',
    advisorySegment: '请确认',
    reviewCandidatesTitle: '识别到了不同的结果，请选择正确的信息。',
    flightCandidatePrompt: '航班号候选',
    dateCandidatePrompt: '出发日期候选',
    manualConflictPrompt: '没有可显示的候选值，请在下方输入并确认。',
    queryTarget: '查询 {flight} · {date}',
    itineraryReadyLead: '航班号和出发日期已完整。请选择要继续的航段。',
    readySegmentLead: '请核对航班号和出发日期。',
    pendingFlightLead: '请核对识别到的信息。',
    partialFlightLead: '部分航班信息缺失，请补全后继续。'
  });
  Object.assign(translations.en, {
    documentRecognitionWaiting: 'Recognizing your document. Please wait.',
    liveFlightLookupWaiting: 'Looking up live flight information. Please wait.',
    confidenceAdvisory: 'Please check the information we found.',
    auxiliaryAdvisory: 'Please check the information we found.',
    advisorySegment: 'Please confirm',
    reviewCandidatesTitle: 'We found different results. Please choose the correct information.',
    flightCandidatePrompt: 'Flight-number candidates',
    dateCandidatePrompt: 'Departure-date candidates',
    manualConflictPrompt: 'No candidate value can be displayed. Enter and confirm the value below.',
    queryTarget: 'Search {flight} · {date}',
    itineraryReadyLead: 'The flight number and departure date are complete. Choose a segment to continue.',
    readySegmentLead: 'Check the flight number and departure date.',
    pendingFlightLead: 'Please check the information we found.',
    partialFlightLead: 'Some flight information is missing. Please complete it to continue.'
  });
  Object.assign(translations.es, {
    documentRecognitionWaiting: 'Reconociendo el documento. Espera un momento.',
    liveFlightLookupWaiting: 'Consultando el vuelo en tiempo real. Espera un momento.',
    confidenceAdvisory: 'Compruebe la información encontrada.',
    auxiliaryAdvisory: 'Compruebe la información encontrada.',
    advisorySegment: 'Confirmar',
    reviewCandidatesTitle: 'Encontramos resultados diferentes. Elija la información correcta.',
    flightCandidatePrompt: 'Candidatos de número de vuelo',
    dateCandidatePrompt: 'Candidatos de fecha de salida',
    manualConflictPrompt: 'No hay ningún valor candidato visible. Introduce y confirma el valor abajo.',
    queryTarget: 'Buscar {flight} · {date}',
    itineraryReadyLead: 'El número de vuelo y la fecha de salida están completos. Elija un tramo para continuar.',
    readySegmentLead: 'Compruebe el número de vuelo y la fecha de salida.',
    pendingFlightLead: 'Compruebe la información encontrada.',
    partialFlightLead: 'Falta parte de la información del vuelo. Complétela para continuar.'
  });

  Object.assign(translations.zh, {
    manualTitle: '输入航班信息', manualTitleOrdinary: '输入航班信息',
    manualTitleComplete: '补全航班信息', manualTitleCheck: '核对航班信息',
    manualStatusOrdinary: '请输入航班号和出发日期。',
    manualStatusConflict: '识别到了不同的结果，请选择正确的信息。',
    manualStatusMissing: '部分航班信息缺失，请补全后继续。',
    manualStatusReview: '请核对识别到的信息。',
    manualStatusReady: '信息已补全，可以查询真实航班。',
    manualBackHome: '返回首页', manualBackSegments: '返回航段选择',
    flightCandidatePrompt: '请选择正确的航班号', dateCandidatePrompt: '请选择正确的出发日期',
    manualCandidateEntry: '以上都不对，手动输入', completeDocument: '补全', searchFlightPartial: '查询航班'
  });
  Object.assign(translations.en, {
    manualTitle: 'Enter flight information', manualTitleOrdinary: 'Enter flight information',
    manualTitleComplete: 'Complete flight information', manualTitleCheck: 'Check flight information',
    manualStatusOrdinary: 'Enter the flight number and departure date.',
    manualStatusConflict: 'We found different results. Choose the correct information.',
    manualStatusMissing: 'Some flight information is missing. Complete it to continue.',
    manualStatusReview: 'Check the information we found.',
    manualStatusReady: 'The information is complete. You can search the live flight.',
    manualBackHome: 'Back to home', manualBackSegments: 'Back to segment selection',
    flightCandidatePrompt: 'Choose the correct flight number', dateCandidatePrompt: 'Choose the correct departure date',
    manualCandidateEntry: 'None of these — enter manually', completeDocument: 'Complete', searchFlightPartial: 'Search flight'
  });
  Object.assign(translations.es, {
    manualTitle: 'Introduce la información del vuelo', manualTitleOrdinary: 'Introduce la información del vuelo',
    manualTitleComplete: 'Completa la información del vuelo', manualTitleCheck: 'Comprueba la información del vuelo',
    manualStatusOrdinary: 'Introduce el número de vuelo y la fecha de salida.',
    manualStatusConflict: 'Encontramos resultados diferentes. Elige la información correcta.',
    manualStatusMissing: 'Falta parte de la información del vuelo. Complétala para continuar.',
    manualStatusReview: 'Comprueba la información encontrada.',
    manualStatusReady: 'La información está completa. Ya puedes buscar el vuelo en directo.',
    manualBackHome: 'Volver al inicio', manualBackSegments: 'Volver a elegir tramo',
    flightCandidatePrompt: 'Elige el número de vuelo correcto', dateCandidatePrompt: 'Elige la fecha de salida correcta',
    manualCandidateEntry: 'Ninguna es correcta; introducir manualmente', completeDocument: 'Completar', searchFlightPartial: 'Buscar vuelo'
  });

  // Production-facing copy is kept together so the three languages stay aligned.
  Object.assign(translations.zh, {
    welcomeLead: '上传旅行文件，或手动输入航班。',
    upload: '上传旅行文件',
    uploadHint: '支持行程单、电子客票、预订截图或登机牌',
    fileLabel: '选择行程单、电子客票、预订截图或登机牌',
    welcomeSpeechNotice: '接下来会自动朗读；朗读时可点“停止”。',
    documentRecognitionWaiting: '正在读取航班信息',
    liveFlightLookupWaiting: '正在查询航班',
    documentUnusableContext: '文件不可用',
    documentUnusableTitle: '文件暂时无法使用',
    documentUnusableLead: '请换一份清晰、完整的旅行文件，或手动输入。',
    documentUnusableHint: '支持行程单、电子客票、预订截图或登机牌',
    documentUnusableReason: '文件可能不完整、模糊，或不是航空公司旅行文件。',
    readySegment: '选择航段', incompleteSegment: '补全航段', pendingSegment: '核对航段', confirmSegment: '核对信息',
    conflictTitle: '核对信息',
    itineraryReadyContext: '', itineraryReadyTitle: '选择航段', itineraryReadyLead: '请选择要查询的航段。',
    itineraryMissingContext: '', itineraryMissingTitle: '补全航段', itineraryMissingLead: '请选择需要补全的航段。',
    itineraryConflictContext: '', itineraryConflictTitle: '核对航段', itineraryConflictLead: '请选择需要核对的航段。',
    itineraryPendingContext: '', itineraryPendingTitle: '核对航段', itineraryPendingLead: '请选择需要核对的航段。',
    readySegmentLead: '请核对航班号和出发日期。', confirmSegmentLead: '请选择正确的信息。',
    manualStatusReady: '信息已补全。', searchLiveFlight: '查询航班', searchFlightPartial: '查询航班',
    flightPlaceholder: '例如 AB 123', enterManually: '手动输入',
    checkInArea: '值机/托运',
    liveStatusUnavailable: '实时状态暂不可用，请核对下方行程。',
    allFlightInfo: '查看详情',
    departureLocalTime: '起飞 · 当地时间', arrivalLocalTime: '到达 · 当地时间',
    checkInQuestion: '已经值机了吗？', checkInQuestionLead: '拿到登机牌也算。',
    checkInUnsure: '不确定', baggageUnsure: '不确定',
    shareShort: '分享',
    familyLead: '链接和图片只含必要行程信息，48 小时有效。',
    shareLinkAria: '使用手机分享菜单分享链接', sharePngAria: '使用手机分享菜单分享不含个人信息的图片',
    journeyProblem: '行程出问题', journeyProblemCloseAria: '关闭行程问题卡片',
    journeyProblemSpeechFailed: '没有听清，请再说一次，或者打字告诉我。',
    journeyProblemLoading: '正在查找建议……',
    journeyProblemFallback: '请保留登机牌，并把刚才的情况告诉航空公司工作人员，请他们确认下一步。',
    journeyProblemAlert: '行程出问题', journeyProblemAlertDeparted: '行程出问题',
    boardConfirmLead: '确认后，抵达时继续指引。', confirmBoard: '已登机', cancelBoard: '还没有',
    landedConfirmLead: '确认后，继续指引你领取行李或前往出口。', confirmLanded: '已下飞机', cancelLanded: '还没有',
    endConfirmLead: '结束后将清除本次进度。',
    backStep: '上一步',
    staffCardInstruction: '请把这个页面给工作人员看。',
    preparingPng: '正在准备图片…'
  });
  Object.assign(translations.en, {
    welcomeLead: 'Upload a travel document or enter a flight.',
    upload: 'Upload a travel document',
    uploadHint: 'Supports itineraries, e-tickets, booking screenshots, or boarding passes',
    fileLabel: 'Choose an itinerary, e-ticket, booking screenshot, or boarding pass',
    welcomeSpeechNotice: 'Voice guidance will play automatically. Select “Stop” while it is reading.',
    documentRecognitionWaiting: 'Reading flight information',
    liveFlightLookupWaiting: 'Searching for the flight',
    documentUnusableContext: 'File unavailable',
    documentUnusableTitle: 'The file is unavailable',
    documentUnusableLead: 'Choose a clear, complete travel document or enter the flight manually.',
    documentUnusableHint: 'Supports itineraries, e-tickets, booking screenshots, or boarding passes',
    documentUnusableReason: 'The file may be incomplete, blurred, or not an airline travel document.',
    readySegment: 'Choose a segment', incompleteSegment: 'Complete a segment', pendingSegment: 'Check a segment', confirmSegment: 'Review details',
    conflictTitle: 'Review details',
    itineraryReadyContext: '', itineraryReadyTitle: 'Choose a segment', itineraryReadyLead: 'Choose the segment you want to search.',
    itineraryMissingContext: '', itineraryMissingTitle: 'Complete a segment', itineraryMissingLead: 'Choose the segment that needs information.',
    itineraryConflictContext: '', itineraryConflictTitle: 'Check a segment', itineraryConflictLead: 'Choose the segment that needs review.',
    itineraryPendingContext: '', itineraryPendingTitle: 'Check a segment', itineraryPendingLead: 'Choose the segment that needs review.',
    readySegmentLead: 'Check the flight number and departure date.', confirmSegmentLead: 'Choose the correct information.',
    manualStatusReady: 'Information complete.', searchLiveFlight: 'Search flight', searchFlightPartial: 'Search flight',
    flightPlaceholder: 'For example AB 123', enterManually: 'Enter manually',
    checkInArea: 'Check-in / bag drop',
    liveStatusUnavailable: 'Live status is unavailable. Check the journey below.',
    allFlightInfo: 'View details',
    departureLocalTime: 'Departure · local time', arrivalLocalTime: 'Arrival · local time',
    checkInQuestion: 'Have you checked in?', checkInQuestionLead: 'A boarding pass counts as checked in.',
    checkInUnsure: 'Not sure', baggageUnsure: 'Not sure',
    familyLead: 'Links and images show only essential journey details and remain valid for 48 hours.',
    shareLinkAria: 'Use the mobile share menu to share the link', sharePngAria: 'Use the mobile share menu to share an image without personal information',
    journeyProblem: 'Journey problem', journeyProblemCloseAria: 'Close journey problem card',
    journeyProblemSpeechFailed: 'I didn’t catch that. Try again or type it.',
    journeyProblemLoading: 'Finding guidance…',
    journeyProblemFallback: 'Keep your boarding pass and tell airline staff what happened so they can confirm the next step.',
    journeyProblemAlert: 'Journey problem', journeyProblemAlertDeparted: 'Journey problem',
    boardConfirmLead: 'Guidance will continue after arrival.', confirmBoard: 'Boarded', cancelBoard: 'Not yet',
    landedConfirmLead: 'Guidance will continue to baggage claim or the exit.', confirmLanded: 'Left the aircraft', cancelLanded: 'Not yet',
    endConfirmLead: 'This journey’s progress will be cleared.',
    backStep: 'Back',
    staffCardInstruction: 'Show this screen to a member of staff.',
    preparingPng: 'Preparing image…'
  });
  Object.assign(translations.es, {
    welcomeLead: 'Sube un documento o introduce un vuelo.',
    upload: 'Subir un documento de viaje',
    uploadHint: 'Admite itinerarios, billetes electrónicos, capturas de reserva o tarjetas de embarque',
    fileLabel: 'Elige un itinerario, billete electrónico, captura de reserva o tarjeta de embarque',
    welcomeSpeechNotice: 'La guía de voz se reproducirá automáticamente. Pulsa «Detener» durante la lectura.',
    documentRecognitionWaiting: 'Leyendo la información del vuelo',
    liveFlightLookupWaiting: 'Buscando el vuelo',
    documentUnusableContext: 'Archivo no disponible',
    documentUnusableTitle: 'El archivo no está disponible',
    documentUnusableLead: 'Elige un documento claro y completo o introduce el vuelo manualmente.',
    documentUnusableHint: 'Admite itinerarios, billetes electrónicos, capturas de reserva o tarjetas de embarque',
    documentUnusableReason: 'El archivo puede estar incompleto, borroso o no ser un documento de viaje de una aerolínea.',
    readySegment: 'Elige un tramo', incompleteSegment: 'Completa un tramo', pendingSegment: 'Revisa un tramo', confirmSegment: 'Revisar datos',
    conflictTitle: 'Revisar datos',
    itineraryReadyContext: '', itineraryReadyTitle: 'Elige un tramo', itineraryReadyLead: 'Elige el tramo que quieres buscar.',
    itineraryMissingContext: '', itineraryMissingTitle: 'Completa un tramo', itineraryMissingLead: 'Elige el tramo que necesita información.',
    itineraryConflictContext: '', itineraryConflictTitle: 'Revisa un tramo', itineraryConflictLead: 'Elige el tramo que debes revisar.',
    itineraryPendingContext: '', itineraryPendingTitle: 'Revisa un tramo', itineraryPendingLead: 'Elige el tramo que debes revisar.',
    readySegmentLead: 'Comprueba el número de vuelo y la fecha de salida.', confirmSegmentLead: 'Elige la información correcta.',
    manualStatusReady: 'Información completa.', searchLiveFlight: 'Buscar vuelo', searchFlightPartial: 'Buscar vuelo',
    flightPlaceholder: 'Por ejemplo AB 123', enterManually: 'Introducir manualmente',
    manualItineraryLead: 'Elige un tramo para revisarlo o completarlo.',
    confidenceAdvisory: 'Comprueba la información encontrada.',
    auxiliaryAdvisory: 'Comprueba la información encontrada.',
    reviewCandidatesTitle: 'Encontramos resultados diferentes. Elige la información correcta.',
    pendingFlightLead: 'Comprueba la información encontrada.',
    partialDateLead: 'Falta parte de la información del vuelo. Complétala para continuar.',
    partialFlightLead: 'Falta parte de la información del vuelo. Complétala para continuar.',
    pendingDocumentNotice: 'Comprueba la información encontrada.',
    missingDocumentNotice: 'Falta parte de la información del vuelo. Complétala para continuar.',
    conflictDocumentNotice: 'Encontramos resultados diferentes. Elige la información correcta.',
    checkInArea: 'Facturación / equipaje',
    liveStatusUnavailable: 'El estado en directo no está disponible. Revisa el viaje.',
    allFlightInfo: 'Ver detalles',
    departureLocalTime: 'Salida · hora local', arrivalLocalTime: 'Llegada · hora local',
    checkInQuestion: '¿Ya has facturado?', checkInQuestionLead: 'Tener la tarjeta de embarque cuenta como facturar.',
    checkInUnsure: 'No lo sé', baggageUnsure: 'No lo sé',
    shareShort: 'Familia', familyAria: 'Familia, compartir el viaje en directo',
    familyLead: 'Los enlaces y las imágenes solo muestran los datos necesarios y son válidos durante 48 horas.',
    shareLinkAria: 'Usar el menú del móvil para compartir el enlace', sharePngAria: 'Usar el menú del móvil para compartir una imagen sin datos personales',
    journeyProblem: 'Problema con el viaje', journeyProblemCloseAria: 'Cerrar la tarjeta del problema del viaje',
    journeyProblemSpeechFailed: 'No se entendió. Inténtalo de nuevo o escríbelo.',
    journeyProblemLoading: 'Buscando orientación…',
    journeyProblemFallback: 'Conserva la tarjeta de embarque y explica al personal de la aerolínea lo ocurrido para que confirme el siguiente paso.',
    journeyProblemAlert: 'Problema con el viaje', journeyProblemAlertDeparted: 'Problema con el viaje',
    boardConfirmLead: 'Las indicaciones continuarán después de llegar.', confirmBoard: 'Ya he embarcado', cancelBoard: 'Todavía no',
    landedConfirmLead: 'Las indicaciones continuarán hacia el equipaje o la salida.', confirmLanded: 'Ya he bajado', cancelLanded: 'Todavía no',
    endConfirmLead: 'Se borrará el progreso de este viaje.',
    backStep: 'Atrás',
    staffCardInstruction: 'Muestre esta pantalla al personal.',
    mapTemporarilyUnavailable: 'El mapa del aeropuerto no está disponible temporalmente. Inténtalo de nuevo pronto.',
    contactTemporarilyUnavailable: 'Los datos de contacto del aeropuerto no están disponibles temporalmente. Inténtalo de nuevo pronto.',
    preparingPng: 'Preparando imagen…'
  });

  var completionLabels = {
    CHECK_IN: ['我已经完成值机，继续', 'I have checked in; continue', 'Ya he facturado; continuar'],
    BAG_DROP: ['我已经托运行李，继续', 'I have dropped off my bags; continue', 'Ya entregué el equipaje; continuar'],
    SECURITY: ['我已经通过安检，继续', 'I have passed security; continue', 'Ya pasé el control; continuar'],
    GO_TO_GATE: ['我已经到达登机口，继续', 'I have reached the gate; continue', 'Ya llegué a la puerta; continuar'],
    WAIT: ['刷新实时信息', 'Refresh live information', 'Actualizar información'],
    BOARD_NOW: ['我已经登机', 'I have boarded', 'Ya he embarcado'],
    IN_FLIGHT: ['我已经下飞机', 'I have left the aircraft', 'Ya he bajado del avión'],
    BAGGAGE_CLAIM: ['我已经拿到行李', 'I have collected my baggage', 'Ya recogí mi equipaje'],
    EXIT_AIRPORT: ['我已经到达出口', 'I have reached the exit', 'Ya llegué a la salida']
  };

  var DEMO_SCENARIO_METADATA = [
    { id: 'NORMAL', group: 'time-status', phase: 'departure', labelKey: 'scenarioNormal', beforeKey: 'scenarioBeforeBaseline', expectedKey: 'scenarioExpectedNone', expectedTypes: [] },
    { id: 'TERMINAL_CHANGE', group: 'location', phase: 'departure', labelKey: 'scenarioTerminalChange', beforeKey: 'scenarioBeforeTerminal', expectedKey: 'scenarioExpectedTerminal', expectedTypes: ['TERMINAL_CHANGED'] },
    { id: 'GATE_CHANGE', group: 'location', phase: 'departure', labelKey: 'scenarioGateChange', beforeKey: 'scenarioBeforeGate', expectedKey: 'scenarioExpectedGate', expectedTypes: ['GATE_CHANGED'] },
    { id: 'CHECK_IN_COUNTER_CHANGE', group: 'location', phase: 'departure', labelKey: 'scenarioCheckInCounterChange', beforeKey: 'scenarioBeforeCheckIn', expectedKey: 'scenarioExpectedCheckIn', expectedTypes: ['CHECK_IN_COUNTER_CHANGED'] },
    { id: 'BAG_DROP_DESK_CHANGE', group: 'location', phase: 'departure', labelKey: 'scenarioBagDropDeskChange', beforeKey: 'scenarioBeforeBagDrop', expectedKey: 'scenarioExpectedBagDrop', expectedTypes: ['BAG_DROP_DESK_CHANGED'] },
    { id: 'BOARDING_TIME_CHANGE', group: 'location', phase: 'departure', labelKey: 'scenarioBoardingTimeChange', beforeKey: 'scenarioBeforeBoardingTime', expectedKey: 'scenarioExpectedBoardingTime', expectedTypes: ['BOARDING_TIME_CHANGED'] },
    { id: 'DEPARTURE_TIME_CHANGE', group: 'time-status', phase: 'departure', labelKey: 'scenarioDepartureTimeChange', beforeKey: 'scenarioBeforeDepartureTime', expectedKey: 'scenarioExpectedDepartureTime', expectedTypes: ['DEPARTURE_TIME_CHANGED'] },
    { id: 'DELAY', group: 'time-status', phase: 'departure', labelKey: 'scenarioDelay', beforeKey: 'scenarioBeforeOnTime', expectedKey: 'scenarioExpectedDelay', expectedTypes: ['STATUS_CHANGED', 'DEPARTURE_TIME_CHANGED', 'DEPARTURE_DELAY_CHANGED'] },
    { id: 'BOARDING', group: 'time-status', phase: 'boarding', labelKey: 'scenarioBoarding', beforeKey: 'scenarioBeforeOnTime', expectedKey: 'scenarioExpectedBoarding', expectedTypes: ['STATUS_CHANGED'] },
    { id: 'CANCEL', group: 'time-status', phase: 'departure', labelKey: 'scenarioCancel', beforeKey: 'scenarioBeforeOnTime', expectedKey: 'scenarioExpectedCancelled', expectedTypes: ['STATUS_CHANGED'] },
    { id: 'DEPARTED', group: 'time-status', phase: 'departure', labelKey: 'scenarioDeparted', beforeKey: 'scenarioBeforeOnTime', expectedKey: 'scenarioExpectedDeparted', expectedTypes: ['STATUS_CHANGED'] },
    { id: 'DIVERTED', group: 'time-status', phase: 'departure', labelKey: 'scenarioDiverted', beforeKey: 'scenarioBeforeOnTime', expectedKey: 'scenarioExpectedDiverted', expectedTypes: ['STATUS_CHANGED'] },
    { id: 'RETURNED', group: 'time-status', phase: 'departure', labelKey: 'scenarioReturned', beforeKey: 'scenarioBeforeOnTime', expectedKey: 'scenarioExpectedReturned', expectedTypes: ['STATUS_CHANGED'] },
    { id: 'ARRIVAL_TIME_CHANGE', group: 'arrival', phase: 'arrival', labelKey: 'scenarioArrivalTimeChange', beforeKey: 'scenarioBeforeArrivalTime', expectedKey: 'scenarioExpectedArrivalTime', expectedTypes: ['ARRIVAL_TIME_CHANGED', 'ARRIVAL_DELAY_CHANGED'] },
    { id: 'ARRIVED', group: 'arrival', phase: 'arrival', labelKey: 'scenarioArrived', beforeKey: 'scenarioBeforeOnTime', expectedKey: 'scenarioExpectedArrived', expectedTypes: ['STATUS_CHANGED'] },
    { id: 'BAGGAGE_BELT_CHANGE', group: 'arrival', phase: 'arrival', labelKey: 'scenarioBaggageBeltChange', beforeKey: 'scenarioBeforeBelt', expectedKey: 'scenarioExpectedBelt', expectedTypes: ['BAGGAGE_BELT_CHANGED'] },
    { id: 'STALE', group: 'time-status', phase: 'departure', labelKey: 'scenarioStale', beforeKey: 'scenarioBeforeFresh', expectedKey: 'scenarioExpectedStale', expectedTypes: ['DATA_FRESHNESS_CHANGED'] }
  ];

  function demoScenarioMetadata(id) {
    return DEMO_SCENARIO_METADATA.find(function (scenario) { return scenario.id === id; }) || null;
  }

  var statusLabels = {
    ON_TIME: ['准时', 'On time', 'A tiempo'], BOARDING: ['正在登机', 'Boarding', 'Embarcando'],
    SCHEDULED: ['计划中', 'Scheduled', 'Programado'], EXPECTED: ['预计正常', 'Expected', 'Previsto'], NORMAL: ['计划正常', 'On schedule', 'Según lo previsto'],
    DELAYED: ['延误', 'Delayed', 'Retrasado'], CANCELLED: ['已取消', 'Cancelled', 'Cancelado'],
    DEPARTED: ['已离港', 'Departed', 'Despegó'], ARRIVED: ['已到达', 'Arrived', 'Llegó'],
    DIVERTED: ['已备降', 'Diverted', 'Desviado'], RETURNED: ['已返航', 'Returned', 'De regreso'],
    UNKNOWN: ['待确认', 'Confirm on site', 'Confirmar en el aeropuerto']
  };

  var loadingMessages = [
    ['正在查询航班', 'Searching for the flight', 'Buscando el vuelo'],
    ['正在查询航班', 'Searching for the flight', 'Buscando el vuelo'],
    ['正在准备下一步', 'Preparing the next step', 'Preparando el siguiente paso']
  ];

  var uploadLoadingMessages = [
    ['正在上传文件', 'Uploading the file', 'Subiendo el archivo'],
    ['正在读取航班信息', 'Reading flight information', 'Leyendo la información del vuelo'],
    ['正在准备下一步', 'Preparing the next step', 'Preparando el siguiente paso']
  ];

  function normalizeLocale(value) {
    return window.OneStepLocale.normalizeLocale(value);
  }

  function detectInitialLocale() {
    var stored = null;
    try { stored = localStorage.getItem(LOCALE_KEY); } catch (error) { /* Use browser language. */ }
    return window.OneStepLocale.resolveLocalePreference(stored, navigator.languages, navigator.language);
  }

  function storeLocale(locale) {
    try { localStorage.setItem(LOCALE_KEY, locale); } catch (error) { /* Page remains usable. */ }
  }

  function readStoredJson(key) {
    try { return JSON.parse(localStorage.getItem(key) || 'null'); } catch (error) { return null; }
  }

  function localeIndex() { return currentLocale === 'en' ? 1 : currentLocale === 'es' ? 2 : 0; }

  function textFor(values) {
    if (!values) return '';
    if (Array.isArray(values)) return values[localeIndex()] || values[1] || values[0] || '';
    return values[currentLocale] || values.en || values.zh || '';
  }

  function t(key, params) {
    var value = translations[currentLocale][key] || translations.en[key] || translations.zh[key] || key;
    return Object.keys(params || {}).reduce(function (result, name) {
      return result.replace(new RegExp('\\{' + name + '\\}', 'g'), String(params[name]));
    }, value);
  }

  function localeText(zh, en, es) {
    return currentLocale === 'zh' ? zh : currentLocale === 'es' ? (es || en) : en;
  }

  function localeLanguageTag(locale) {
    return locale === 'zh' ? 'zh-CN' : locale;
  }

  function setBilingualContent(element, primaryText, englishText, heading) {
    if (!element) return;
    element.replaceChildren();
    var primary = document.createElement('span');
    primary.className = heading ? 'bilingual-heading-primary' : 'bilingual-primary';
    primary.lang = localeLanguageTag(currentLocale);
    primary.textContent = primaryText || '';
    element.appendChild(primary);
  }

  function setHidden(element, hidden) {
    if (element) element.hidden = hidden;
  }

  function setText(key) {
    document.querySelectorAll('[data-i18n="' + key + '"]').forEach(function (element) {
      element.textContent = t(key);
    });
  }

  function updateLanguage() {
    Object.keys(translations[currentLocale]).forEach(setText);
    document.querySelectorAll('[data-i18n-aria]').forEach(function (element) {
      element.setAttribute('aria-label', t(element.dataset.i18nAria));
    });
    document.querySelectorAll('[data-placeholder]').forEach(function (element) {
      element.placeholder = t(element.dataset.placeholder);
    });
    document.documentElement.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    var dateInput = document.getElementById('date-input');
    if (dateInput) dateInput.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    syncVisibleDates();
    invalidatePngCard();
    var familyOverlay = document.querySelector('[data-family-overlay]');
    if (familyOverlay && !familyOverlay.hidden && currentFlight) prepareFamilyCard().catch(function () {});
    document.title = localeText('OneStep Flight | 一步乘机', 'OneStep Flight', 'OneStep Flight | Un paso para volar');
    var localeSelect = document.querySelector('[data-locale-select]');
    if (localeSelect) {
      window.OneStepLocale.orderLocaleOptions(localeSelect, currentLocale);
      localeSelect.setAttribute('aria-label', t('languageLabel'));
    }
    updateSpeechButtons();
    document.querySelector('[data-action="home"]').setAttribute('aria-label', localeText('返回欢迎页', 'Return to welcome page', 'Volver a la bienvenida'));
    document.querySelector('.brand-copy small').textContent = localeText('一步乘机', 'Flight assistant', 'Asistente de vuelo');
    var progressBar = document.querySelector('.progress-track');
    if (progressBar) progressBar.setAttribute('aria-label', localeText('读取进度', 'Loading progress', 'Progreso de carga'));
    if (currentScreen === 'loading') renderActiveLoadingMessage();
    var flightCard = document.querySelector('.flight-card');
    if (flightCard) flightCard.setAttribute('aria-label', localeText('航班信息', 'Flight information', 'Información del vuelo'));
    var qaBanner = document.querySelector('[data-qa-banner]');
    if (qaJourneyEnabled && !qaBanner) {
      qaBanner = document.createElement('p');
      qaBanner.className = 'qa-banner';
      qaBanner.dataset.qaBanner = '';
      document.getElementById('main-content').prepend(qaBanner);
    }
    if (qaBanner) qaBanner.textContent = localeText('本地流程验收', 'Local journey QA', 'Prueba local del viaje');
    if (currentFlight) renderFlight(currentFlight);
    else if (awaitingDocumentSelection) renderSegmentSelector();
    if (currentAction) renderJourneyResponse();
    if (currentError) renderErrorMessage();
    renderRefreshStatus();
    renderConnectivityStatus();
    renderManualDocumentEntry();
    renderStaffCard();
    renderStaffOverlayView();
    renderShareEntryVisibility();
    renderScenarioTestCards();
    persistJourney();
  }

  function formatVisibleDate(value) {
    if (!value) return t('datePlaceholder');
    var parts = value.split('-').map(Number);
    if (parts.length !== 3 || !parts[0] || !parts[1] || !parts[2]) return t('datePlaceholder');
    var date = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
    return new Intl.DateTimeFormat(currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-GB', {
      day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC'
    }).format(date);
  }

  function syncVisibleDates() {
    document.querySelectorAll('input[type="date"]').forEach(function (input) {
      input.lang = localeLanguageTag(currentLocale);
      var display = input.parentElement && input.parentElement.querySelector('[data-date-display]');
      if (display) {
        display.textContent = formatVisibleDate(input.value);
        input.parentElement.dataset.empty = String(!input.value);
      }
    });
  }

  function setDateInputValue(input, value) {
    if (!input) return;
    input.value = value || '';
    syncVisibleDates();
  }

  function setLookupBusy(busy) {
    lookupInFlight = Boolean(busy);
    document.querySelectorAll('[data-action="upload"], [data-action="demo-flight"], [data-action="manual-submit"] button[type="submit"], [data-action="continue"], [data-action="new-flight-info"], [data-action="select-segment"]').forEach(function (button) {
      var action = button.dataset.action;
      var continueBlocked = action === 'continue' && (awaitingDocumentSelection || !currentFlight
		|| isDocumentFallbackFlight(currentFlight)
        || departureStopReason(currentFlight)
        || currentFlight && currentFlight.requiresUserConfirmation && !conflictsConfirmed);
      button.disabled = lookupInFlight || Boolean(continueBlocked);
    });
    document.getElementById('main-content').setAttribute('aria-busy', String(lookupInFlight));
  }

  function announce(message) {
    var region = document.querySelector('[data-live-region]');
    region.textContent = '';
    window.setTimeout(function () { region.textContent = message; }, 30);
  }

  function showScreen(name, options) {
    var settings = options || {};
    if (currentScreen !== name) {
      dismissFlightChangeNotice();
      if (!settings.silent) stopSpeech(false);
    }
    currentScreen = name;
    screens.forEach(function (screen) {
      var active = screen.dataset.screen === name;
      screen.hidden = !active;
      screen.classList.toggle('is-active', active);
    });
    if (name !== 'loading' && !settings.silent) {
      document.getElementById('main-content').focus({ preventScroll: true });
    }
    renderShareEntryVisibility();
    renderFlightChanges();
    syncAirportResourceQaVisibility();
    if (speechViewKey() == null) lastAutomaticSpeechKey = null;
    if (!settings.silent) queueAutomaticSpeech();
    syncAutoRefreshForScreen();
  }

  function navigate(name) {
    if (currentScreen !== name && currentScreen !== 'loading') navigationHistory.push(currentScreen);
    showScreen(name);
  }

  function normalizeFlightNumber(value) {
    return String(value || '').replace(/[\s\u002D\u2010-\u2015\u2212]/g, '').toUpperCase();
  }

  function fetchJson(url, options) {
    var settings = options || {};
    var onResponse = settings.onResponse;
    delete settings.onResponse;
    settings.headers = settings.headers || {};
    settings.headers.Accept = 'application/json';
    return apiFetch(url, settings).then(function (response) {
      if (onResponse) onResponse(response);
      return response.json().catch(function () { return null; }).then(function (payload) {
        if (!response.ok) {
          var error = new Error('Request failed with status ' + response.status);
          error.apiError = payload;
          error.status = response.status;
          error.requestUrl = url;
          throw error;
        }
        return payload;
      });
    }).catch(function (error) {
      if (error && !error.requestUrl) error.requestUrl = url;
      throw error;
    });
  }

  function postFlightJson(url, payload, onResponse) {
    if (flightRequestController) flightRequestController.abort();
    var controller = new AbortController();
    flightRequestController = controller;
    var didTimeout = false;
    var timeout = window.setTimeout(function () {
      didTimeout = true;
      controller.abort();
    }, 20000);
    return fetchJson(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
      signal: controller.signal,
      onResponse: onResponse
    }).catch(function (error) {
      if (didTimeout || error && error.name === 'AbortError') {
        var timeoutError = new Error('Flight request timed out after 20 seconds');
        timeoutError.code = 'CLIENT_FLIGHT_TIMEOUT';
        timeoutError.requestUrl = url;
        throw timeoutError;
      }
      throw error;
    }).finally(function () {
      window.clearTimeout(timeout);
      if (flightRequestController === controller) flightRequestController = null;
    });
  }

  function postJson(url, payload) {
    return fetchJson(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
  }

  function formatFlightNumber(flightNumber) {
    return String(flightNumber || '').replace(/^([A-Z0-9]{2,3}?)(\d+)$/, '$1 $2');
  }

  function setAll(selector, value) {
    document.querySelectorAll(selector).forEach(function (element) { element.textContent = value; });
  }

  function departureOffsetMinutes() {
    var value = currentFlight && currentFlight.timeContext && currentFlight.timeContext.departureUtcOffset;
    if (!value || value === 'Z') return 0;
    var sign = value.charAt(0) === '-' ? -1 : 1;
    var parts = value.substring(1).split(':');
    var hours = Number(parts[0]);
    var minutes = Number(parts[1] || 0);
    return Number.isFinite(hours) && Number.isFinite(minutes) ? sign * (hours * 60 + minutes) : 0;
  }

  function offsetMinutesFromDateTime(value) {
    var match = String(value || '').match(/(Z|[+-]\d{2}:?\d{2})$/i);
    if (!match || match[1].toUpperCase() === 'Z') return 0;
    var offset = match[1].replace(':', '');
    var sign = offset.charAt(0) === '-' ? -1 : 1;
    return sign * (Number(offset.substring(1, 3)) * 60 + Number(offset.substring(3, 5)));
  }

  function arrivalOffsetMinutes() {
    if (!currentFlight) return 0;
    return offsetMinutesFromDateTime(currentFlight.actualArrival
      || currentFlight.estimatedArrival || currentFlight.scheduledArrival);
  }

  function isArrivalPhase() {
    return Boolean(journeyContext && journeyContext.boarded)
      || Boolean(currentAction && ['IN_FLIGHT', 'BAGGAGE_CLAIM', 'EXIT_AIRPORT'].indexOf(currentAction.actionCode) >= 0);
  }

  function formatAtOffset(value, includeDate, offsetMinutes) {
    if (!value) return '—';
    var raw = String(value);
    var instant;
    var hasFlightTimeContext = Boolean(currentFlight && currentFlight.timeContext);
    if (!hasFlightTimeContext && raw.indexOf('T') > 0 && !/(Z|[+-]\d{2}:?\d{2})$/i.test(raw)) {
      var localParts = raw.split('T');
      var dateParts = localParts[0].split('-').map(Number);
      var timeParts = localParts[1].split(':').map(Number);
      instant = Date.UTC(dateParts[0], dateParts[1] - 1, dateParts[2], timeParts[0] || 0, timeParts[1] || 0);
    } else if (!hasFlightTimeContext && raw.indexOf('T') < 0 && raw.indexOf(':') > 0) {
      var printedTime = raw.split(':').map(Number);
      instant = Date.UTC(2000, 0, 1, printedTime[0] || 0, printedTime[1] || 0);
    } else {
      instant = Date.parse(raw);
    }
    if (!Number.isFinite(instant)) return '—';
    var shifted = new Date(instant + offsetMinutes * 60000);
    var options = includeDate
      ? { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }
      : { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'UTC' };
    return new Intl.DateTimeFormat(currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-US', options).format(shifted);
  }

  function formatAtDeparture(value, includeDate) {
    return formatAtOffset(value, includeDate, departureOffsetMinutes());
  }

  function formatAtArrival(value, includeDate) {
    return formatAtOffset(value, includeDate, arrivalOffsetMinutes());
  }

  function formatDate(value) {
    if (!value) return '—';
    var instant = Date.parse(value + 'T00:00:00Z');
    return Number.isFinite(instant)
      ? new Intl.DateTimeFormat(currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-US', { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(instant))
      : '—';
  }

  function formatDateForLocale(value, locale) {
    if (!value) return locale === 'en-GB' ? 'date not provided' : locale === 'es-ES' ? 'fecha no disponible' : '日期未提供';
    var instant = Date.parse(value + 'T00:00:00Z');
    return Number.isFinite(instant)
      ? new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(instant))
      : value;
  }

  function formatRetrieved(value) {
    if (!value) return localeText('缺失', 'missing', 'no disponible');
    var instant = Date.parse(value);
    return Number.isFinite(instant)
      ? new Intl.DateTimeFormat(currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(instant))
      : localeText('缺失', 'missing', 'no disponible');
  }

  function stripFieldPrefix(value, pattern) {
    return String(value || '').replace(pattern, '').trim();
  }

  function formatTerminalForLocale(value, locale) {
    if (!value) return null;
    var clean = stripFieldPrefix(value, /^(?:(?:terminal|航站楼|航廈)\s*)+/i);
    clean = clean.replace(/^t\s*(?=\d)/i, '').replace(/\s+/g, ' ').trim();
    if (!clean) return null;
    var normalized = normalizeLocale(locale || currentLocale);
    return normalized === 'zh' ? clean + '号航站楼' : 'Terminal ' + clean;
  }

  function formatTerminalValue(value) { return formatTerminalForLocale(value, currentLocale); }

  function formatGateForLocale(value, locale) {
    if (!value) return null;
    var clean = stripFieldPrefix(value, /^(gate|登机口|puerta)\s*/i);
    var normalized = normalizeLocale(locale || currentLocale);
    return normalized === 'zh' ? clean + ' 登机口' : normalized === 'es' ? 'Puerta ' + clean : 'Gate ' + clean;
  }

  function formatGateValue(value) { return formatGateForLocale(value, currentLocale); }

  function parseCounterLocation(value) {
    var raw = String(value || '').trim();
    var simplified = raw.replace(/^(check\s*in\s*(desk|counter|area|zone)?|check[- ]?in\s*(desk|counter|area|zone)?|counter|desk|值机(柜台|区)?|mostradores?|zona de facturación)\s*/i, '');
    var areaMatch = raw.match(/(?:area|zone|check[- ]?in|值机区|zona)\s*([A-Z])\b/i)
      || simplified.match(/^([A-Z])(?:\s*[,;/]|$)/i);
    var rangeMatch = raw.match(/\b(\d+\s*[-–—]\s*\d+|\d+)\b/);
    return {
      raw: raw,
      area: areaMatch ? areaMatch[1].toUpperCase() : null,
      counters: rangeMatch ? rangeMatch[1].replace(/\s*[-–—]\s*/g, '–') : null
    };
  }

  function formatCheckInForLocale(value, locale, genericCounter) {
    if (!value) return null;
    var parsed = parseCounterLocation(value);
    var normalized = normalizeLocale(locale || currentLocale);
    if (!parsed.area && !parsed.counters) return stripFieldPrefix(parsed.raw,
      /^(check[- ]?in\s*(desk|counter|area|zone)?|counter|desk|值机(柜台|区)?|mostradores?|zona de facturación)\s*/i);
    if (normalized === 'zh') {
      return (parsed.area ? parsed.area + '区' : '')
        + (parsed.area && parsed.counters ? ' ' : '')
        + (parsed.counters ? parsed.counters + '号' + (genericCounter ? '柜台' : '值机柜台') : '');
    }
    if (normalized === 'es') {
      return (parsed.area ? 'zona ' + parsed.area : '')
        + (parsed.area && parsed.counters ? ', ' : '')
        + (parsed.counters ? 'mostradores de facturación ' + parsed.counters : '');
    }
    return (parsed.area ? 'Zone ' + parsed.area : '')
      + (parsed.area && parsed.counters ? ', ' : '')
      + (parsed.counters ? 'check-in counters ' + parsed.counters : '');
  }

  function formatCheckInValue(value) { return formatCheckInForLocale(value, currentLocale, false); }

  function formatBagDropForLocale(value, locale) {
    if (!value) return null;
    var parsed = parseCounterLocation(value);
    var normalized = normalizeLocale(locale || currentLocale);
    if (parsed.area || parsed.counters) {
      if (normalized === 'zh') return (parsed.area ? parsed.area + '区' : '') + (parsed.area && parsed.counters ? ' ' : '') + (parsed.counters ? parsed.counters + '号' : '') + '行李托运柜台';
      if (normalized === 'es') return (parsed.area ? 'zona ' + parsed.area + ', ' : '')
        + (parsed.counters ? 'mostrador de entrega de equipaje ' + parsed.counters : 'entrega de equipaje');
      return (parsed.area ? 'Zone ' + parsed.area + ', ' : '')
        + (parsed.counters ? 'bag drop counter' + (parsed.counters.indexOf('–') >= 0 ? 's ' : ' ') + parsed.counters : 'bag drop');
    }
    var clean = stripFieldPrefix(value, /^(bag\s*drop|baggage\s*drop|托运(柜台|区)?|entrega de equipaje)\s*/i);
    return normalized === 'zh' ? '行李托运柜台 ' + clean : normalized === 'es' ? 'Entrega de equipaje ' + clean : 'Bag drop ' + clean;
  }

  function formatBagDropValue(value) { return formatBagDropForLocale(value, currentLocale); }

  function formatBeltForLocale(value, locale) {
    if (!value) return null;
    var clean = stripFieldPrefix(value, /^(?:(?:baggage\s*)?belt\s*|carousel\s*|行李转盘\s*|cinta(?:\s+de\s+equipaje)?\s*)+/i);
    var normalized = normalizeLocale(locale || currentLocale);
    return normalized === 'zh' ? clean + '号行李转盘' : normalized === 'es' ? 'Cinta ' + clean : 'Baggage belt ' + clean;
  }

  function formatBeltValue(value) { return formatBeltForLocale(value, currentLocale); }

  function formatBaggageBeltValue(value) { return formatBeltValue(value); }

  function airportResourceUsesArrival(action, context) {
    if (!action || !context) return false;
    if (['IN_FLIGHT', 'BAGGAGE_CLAIM', 'EXIT_AIRPORT'].indexOf(action.actionCode) >= 0) return true;
    return Boolean(context.boarded || context.landed || context.baggageCollected || context.exitedAirport);
  }

  function currentAirportResourcesContext() {
    if (!currentFlight || !currentAction || !journeyContext) return null;
    var recognized = currentAction.stepType === 'CHECK_IN_QUESTION'
      || currentAction.stepType === 'BAGGAGE_QUESTION'
      || currentAction.stepType === 'COMPLETE'
      || ['CHECK_IN', 'BAG_DROP', 'SECURITY', 'GO_TO_GATE', 'WAIT', 'BOARD_NOW', 'IN_FLIGHT',
        'BAGGAGE_CLAIM', 'EXIT_AIRPORT', 'ASK_FOR_HELP'].indexOf(currentAction.actionCode) >= 0;
    if (!recognized) return null;
    var arrival = airportResourceUsesArrival(currentAction, journeyContext);
    var code = arrival ? currentFlight.arrivalAirport : currentFlight.departureAirport;
    if (!/^[A-Za-z]{3}$/.test(String(code || ''))) return null;
    var airportCode = String(code).toUpperCase();
    var terminal = arrival ? currentFlight.arrivalTerminal || null : currentFlight.terminal || null;
    var rawAirportName = String((arrival ? currentFlight.arrivalAirportName : currentFlight.departureAirportName) || '').trim();
    var airportName = rawAirportName && rawAirportName.toUpperCase() !== airportCode ? rawAirportName : null;
    return {
      airportCode: airportCode,
      airportName: airportName,
      terminal: terminal,
      arrival: arrival,
      contactKey: airportCode,
      mapKey: airportCode + ':' + String(terminal || '').toUpperCase().replace(/^TERMINAL\s*/, '').replace(/[^A-Z0-9]/g, ''),
      key: airportCode + ':' + String(terminal || '').toUpperCase().replace(/^TERMINAL\s*/, '').replace(/[^A-Z0-9]/g, ''),
      payload: { airportCode: airportCode, airportName: airportName, terminal: terminal, forceRefresh: false }
    };
  }

  function preferredOperationalTime(flight, phase) {
    var prefix = phase === 'arrival' ? 'Arrival' : 'Departure';
    var actual = flight['actual' + prefix];
    if (actual) return { value: actual, label: 'actual' + prefix + 'Local' };
    var estimated = flight['estimated' + prefix];
    if (estimated) return { value: estimated, label: 'estimated' + prefix + 'Local' };
    var scheduled = flight['scheduled' + prefix];
    if (phase === 'departure' && !scheduled) scheduled = flight.documentScheduledDeparture;
    return { value: scheduled, label: 'scheduled' + prefix + 'Local' };
  }

  function isDepartureStopReason(reason) {
    return reason === 'FLIGHT_CANCELLED' || reason === 'DEPARTURE_TIME_PASSED'
      || reason === 'FLIGHT_ALREADY_DEPARTED';
  }

  function isDepartureStopAction(action) {
    return Boolean(action && isDepartureStopReason(action.reason));
  }

  function utcOffsetMinutes(value) {
    var normalized = String(value || '').trim().toUpperCase();
    if (normalized === 'Z') return 0;
    var match = normalized.match(/^([+-])(\d{2}):?(\d{2})$/);
    if (!match) return null;
    var hours = Number(match[2]);
    var minutes = Number(match[3]);
    if (hours > 18 || minutes > 59) return null;
    return (match[1] === '-' ? -1 : 1) * (hours * 60 + minutes);
  }

  function departureLocalDate(value, departureOffset) {
    var raw = String(value || '').trim();
    if (!/T.*(?:Z|[+-]\d{2}:?\d{2})$/i.test(raw)) return null;
    var instant = Date.parse(raw);
    if (!Number.isFinite(instant)) return null;
    var offsetMinutes = utcOffsetMinutes(departureOffset);
    if (offsetMinutes == null) return raw.slice(0, 10);
    return new Date(instant + offsetMinutes * 60000).toISOString().slice(0, 10);
  }

  function departureStopReason(flight) {
    if (isDocumentFallbackFlight(flight)) return null;
    var selectedDate = journeyContext && journeyContext.departureDate
      || lastLookupContext && lastLookupContext.departureDate
      || flight && flight.departureDate;
    if (!flight || !/^\d{4}-\d{2}-\d{2}$/.test(String(selectedDate || ''))) return null;
    if (flight.departureDate && flight.departureDate !== selectedDate) return null;
    var departureOffset = flight.timeContext && flight.timeContext.departureUtcOffset;
    var effective = flight.actualDeparture || flight.estimatedDeparture || flight.scheduledDeparture;
    var dateAnchor = flight.scheduledDeparture || effective;
    if (dateAnchor) {
      var anchorDate = departureLocalDate(dateAnchor, departureOffset);
      if (!anchorDate || anchorDate !== selectedDate) return null;
    }
    if (effective) {
      var effectiveDate = departureLocalDate(effective, departureOffset);
      if (!effectiveDate || effectiveDate !== selectedDate) return null;
    }
    var status = String(flight.status || '').trim().toUpperCase();
    if (flight.actualDeparture || status.indexOf('DEPARTED') === 0 || status.indexOf('ARRIVED') === 0) {
      return 'FLIGHT_ALREADY_DEPARTED';
    }
    if (status.indexOf('CANCEL') === 0) return 'FLIGHT_CANCELLED';
    if (!effective || status === 'BOARDING') return null;
    var serverNow = flight.timeContext && flight.timeContext.serverNow;
    var effectiveMs = Date.parse(effective);
    var serverNowMs = Date.parse(serverNow || '');
    return Number.isFinite(effectiveMs) && Number.isFinite(serverNowMs)
      && serverNowMs - effectiveMs >= 15 * 60 * 1000
      ? 'DEPARTURE_TIME_PASSED' : null;
  }

  function departureStopCopy(reason) {
    if (reason === 'FLIGHT_CANCELLED') return {
      title: t('cancelledFlightTitle'), lead: t('cancelledFlightLead'), status: t('cancelledFlightStatus')
    };
    if (reason === 'DEPARTURE_TIME_PASSED') return {
      title: t('departureTimePassedTitle'), lead: t('departureTimePassedLead'), status: t('departureTimePassedStatus')
    };
    return { title: t('departurePassedTitle'), lead: t('departurePassedLead'), status: t('departurePassedStatus') };
  }

  function departureReferenceText(flight) {
    var terminal = locationCode(flight && flight.terminal);
    if (!terminal) return '';
    return localeText(
      '参考：' + formatTerminalForLocale(terminal, 'zh') + '；请在现场确认航空公司服务柜台位置。',
      'Reference: ' + formatTerminalForLocale(terminal, 'en') + '; confirm the airline service-desk location on site.',
      'Referencia: ' + formatTerminalForLocale(terminal, 'es') + '; confirma allí la ubicación del mostrador de atención de la aerolínea.');
  }

  function renderDeparturePassedConfirmation(flight) {
    var screen = document.querySelector('.screen-confirm');
    var panel = document.querySelector('[data-departure-passed-confirmation]');
    var reason = departureStopReason(flight);
    screen.classList.toggle('is-departure-passed', Boolean(reason));
    screen.setAttribute('aria-labelledby', reason ? 'departure-passed-title' : 'confirm-title');
    panel.hidden = !reason;
    if (!reason) return null;
    var copy = departureStopCopy(reason);
    document.getElementById('departure-passed-title').textContent = copy.title;
    panel.querySelector('.lead').textContent = copy.lead;
    document.querySelector('[data-departure-passed-flight]').textContent = formatFlightNumber(flight.flightNumber);
    document.querySelector('[data-departure-passed-date]').textContent = formatDate(flight.departureDate);
    document.querySelector('[data-departure-passed-status]').textContent = copy.status;
    var reference = document.querySelector('[data-departure-passed-reference]');
    reference.textContent = departureReferenceText(flight);
    reference.hidden = !reference.textContent;
    setDeparturePassedStaffContent(reason);
    return reason;
  }

  function renderFlight(flight, options) {
    var settings = options || {};
    var documentFallback = isDocumentFallbackFlight(flight);
    document.querySelector('[data-flight-number]').textContent = formatFlightNumber(flight.flightNumber);
    document.querySelector('[data-departure-airport]').textContent = flight.departureAirport || '—';
    document.querySelector('[data-departure-name]').textContent = flight.departureAirportName || flight.departureAirport || '—';
    document.querySelector('[data-arrival-airport]').textContent = flight.arrivalAirport || '—';
    document.querySelector('[data-arrival-name]').textContent = flight.arrivalAirportName || flight.arrivalAirport || '—';
    document.querySelector('[data-departure-date]').textContent = formatDate(flight.departureDate);
    document.querySelector('[data-scheduled-departure]').textContent = formatAtDeparture(
      flight.scheduledDeparture || flight.documentScheduledDeparture, false);
    document.querySelector('[data-estimated-departure]').textContent = formatAtDeparture(flight.estimatedDeparture, false);
    var departureLocal = preferredOperationalTime(flight, 'departure');
    var arrivalLocal = preferredOperationalTime(flight, 'arrival');
    document.querySelector('[data-route-departure-label]').textContent = t('departureLocalTime');
    document.querySelector('[data-route-departure-time]').textContent = formatAtDeparture(departureLocal.value, true);
    document.querySelector('[data-route-arrival-label]').textContent = t('arrivalLocalTime');
    document.querySelector('[data-route-arrival-time]').textContent = formatAtArrival(arrivalLocal.value, true);
    document.querySelector('[data-boarding-time]').textContent = formatAtDeparture(
      flight.boardingTime || (flight.timeContext && flight.timeContext.boardingTime) || flight.documentBoardingTime, false);
    document.querySelector('[data-terminal]').textContent = formatTerminalValue(
      flight.terminal || (documentFallback ? flight.documentTerminal : null)) || '—';
    document.querySelector('[data-gate]').textContent = formatGateValue(
      flight.gate || (documentFallback ? flight.documentGate : null)) || t('notPublished');
    document.querySelector('[data-check-in-area]').textContent = formatCheckInValue(flight.checkInDeskOrZone || flight.checkInCounter) || t('notPublished');
    document.querySelector('[data-seat]').textContent = flight.seat || (pendingDocument && pendingDocument.seat) || '—';
    var simulated = Boolean(flight.demoScenario);
    document.querySelectorAll('[data-demo-marker]').forEach(function (marker) {
      marker.hidden = flight.demo !== true;
    });
    var unavailableNotice = document.querySelector('[data-live-status-unavailable]');
    if (unavailableNotice) unavailableNotice.hidden = !documentFallback;
    var confirmRefresh = document.querySelector('.screen-confirm [data-action="refresh-flight"]');
    if (confirmRefresh) confirmRefresh.hidden = documentFallback
      ? !pendingDocument : !flight.timeContext || (!flight.live && !simulated);
    var status = statusLabels[flight.status] || statusLabels.UNKNOWN;
    document.querySelector('[data-flight-status]').textContent = textFor(status);
    var departureBlock = renderDeparturePassedConfirmation(flight);
    var continueButton = document.querySelector('[data-action="continue"]');
    continueButton.disabled = Boolean(documentFallback || departureBlock
      || flight.requiresUserConfirmation && !conflictsConfirmed);
    var continueLabel = continueButton.querySelector('[data-button-label]') || continueButton;
    continueLabel.textContent = t('yesContinue');
    continueButton.setAttribute('aria-label', t('yesContinueAria'));
    renderConflictCard();
    if (!settings.skipChanges) renderFlightChanges();
    updateClockAnchor();
    renderTimeStatus();
    renderConnectivityStatus();
    renderSegmentSelector();
    renderFlightCorrection();
    renderShareEntryVisibility();
    renderScenarioTestCards();
    syncAirportResourcesContext();
    if (!settings.silent) queueAutomaticSpeech();
  }

  function renderActiveLoadingMessage() {
    var fixed = document.querySelector('[data-loading-fixed]');
    var message = activeLoadingMessages && activeLoadingMessages[activeLoadingMessageIndex];
    if (fixed && message) fixed.textContent = textFor(message);
  }

  function runLoading(messages, fixedMessageKey) {
    showScreen('loading');
    var fixed = document.querySelector('[data-loading-fixed]');
    var status = document.querySelector('[data-loading-status]');
    var progress = document.querySelector('[data-progress]');
    var progressBar = document.querySelector('.progress-track');
    activeLoadingMessages = messages || null;
    activeLoadingMessageIndex = 0;
    if (fixed) {
      fixed.removeAttribute('data-i18n');
      renderActiveLoadingMessage();
    }
    if (status) {
      status.textContent = '';
      status.hidden = true;
    }
    progress.style.width = '10%';
    progressBar.setAttribute('aria-valuenow', '10');
    clearInterval(loadingTimer);
    clearTimeout(loadingTimeout);
  }

  function updateLoadingProgress(percent, messages, messageIndex) {
    var value = Math.max(0, Math.min(100, Number(percent) || 0));
    var progress = document.querySelector('[data-progress]');
    var progressBar = document.querySelector('.progress-track');
    var fixed = document.querySelector('[data-loading-fixed]');
    if (messages) activeLoadingMessages = messages;
    if (Number.isInteger(messageIndex) && messageIndex >= 0
        && activeLoadingMessages && activeLoadingMessages[messageIndex]) {
      activeLoadingMessageIndex = messageIndex;
    }
    if (progress) progress.style.width = value + '%';
    if (progressBar) progressBar.setAttribute('aria-valuenow', String(value));
    if (fixed) renderActiveLoadingMessage();
  }

  function resolvePayload(flightNumber, departureDate, demo, documentResult, demoScenario, refresh) {
    var payload = {
      flightNumber: normalizeFlightNumber(flightNumber),
      departureDate: departureDate,
      demo: Boolean(demo),
      demoScenario: demoScenario || null,
      demoSessionId: typeof demoRecordingSessionId !== 'undefined' ? demoRecordingSessionId || null : null,
      document: documentResult || null,
      refresh: Boolean(refresh)
    };
    if (documentResult && Number.isInteger(selectedDocumentSegmentIndex)) {
      payload.documentSegmentIndex = selectedDocumentSegmentIndex;
    }
    return payload;
  }

  function extractionToFlight(result) {
    return {
      flightNumber: result.flightNumber,
      departureDate: result.departureDate,
      departureMonthDay: result.departureMonthDay || null,
      departureAirport: result.departureAirport || null,
      departureAirportName: result.departureAirport || null,
      arrivalAirport: result.arrivalAirport || null,
      arrivalAirportName: result.arrivalAirport || null,
      scheduledDeparture: result.scheduledDeparture || null,
      scheduledArrival: result.scheduledArrival || null,
      documentBoardingTime: result.boardingTime || null,
      terminal: result.terminal || null,
      gate: result.gate || null,
      seat: result.seat || null,
      status: 'UNKNOWN',
      delayMinutes: null,
      lastUpdated: result.extractedAt,
      dataSource: result.dataSource,
      documentType: result.documentType || 'UNKNOWN',
      requiresDocumentConfirmation: Boolean(result.requiresDocumentConfirmation),
      boardingPassDetected: false,
      live: false,
      demo: false
    };
  }

  function isDocumentFallbackFlight(flight) {
    return Boolean(flight && (flight.liveDataSource === 'DOCUMENT_FALLBACK'
      || flight.documentFallback === true || flight.liveStatusUnavailable === true));
  }

  function selectedDocumentResult(source, index) {
    var segment = documentSegments[index];
    if (!source || !segment) return null;
    var selectedFields = Object.assign({}, segment.fields || {});
    if (documentRootFields.document_type) selectedFields.document_type = documentRootFields.document_type;
    var reviewPrefix = 'segments[' + index + '].';
    var selectedReviewFields = documentRootReviewFields
      .filter(function (field) {
        field = String(field);
        return field.indexOf('segments[') !== 0 || field.indexOf(reviewPrefix) === 0;
      })
      .map(function (field) {
        field = String(field);
        return field.indexOf(reviewPrefix) === 0 ? field.substring(reviewPrefix.length) : field;
      });
    var selectedReviewIssues = documentRootReviewIssues.map(normalizeReviewIssue)
      .filter(function (issue) {
        return issue && (issue.segmentIndex == null || issue.segmentIndex === index);
      }).map(function (issue) { return reviewIssuePayload(issue, index); });
    return Object.assign({}, source, {
      flightNumber: segment.flightNumber || null,
      flightNumberConfidence: segment.flightNumberConfidence || null,
      flightNumberConfirmedByUser: Boolean(segment.flightNumberConfirmedByUser),
      requiresFlightNumberConfirmation: Boolean(segment.requiresFlightNumberConfirmation),
      departureDate: segment.departureDate || null,
      departureMonthDay: segment.departureMonthDay || null,
      dateSource: segment.dateSource || null,
      departureAirport: segment.departureAirport || null,
      arrivalAirport: segment.arrivalAirport || null,
      scheduledDeparture: segment.scheduledDeparture || null,
      scheduledArrival: segment.scheduledArrival || null,
      boardingTime: segment.boardingTime || null,
      terminal: segment.terminal || null,
      gate: segment.gate || null,
      seat: segment.seat || null,
      fields: selectedFields,
      needsReviewFields: selectedReviewFields,
      reviewIssues: selectedReviewIssues,
      segments: documentSegments.slice()
    });
  }

  function createSegmentDisplay(segment, index) {
    segment = segment || {};
    var state = documentSegmentState(segment, index);
    var conflict = state === 'CONFLICT';
    var incomplete = !validDocumentDepartureDate(segment.departureDate)
      || !validDocumentFlightNumber(segment.flightNumber);
    var advisory = documentSegmentHasAdvisory(index, segment);
    var item = document.createElement('button');
    item.type = 'button';
    item.dataset.action = 'select-segment';
    item.dataset.segmentIndex = String(index);
    item.className = 'segment-option' + (incomplete ? ' is-incomplete' : '')
      + (state === 'NEEDS_CONFIRMATION' ? ' is-pending' : '')
      + (conflict ? ' is-conflict' : '')
      + (advisory && !conflict ? ' is-advisory' : '')
      + (index === selectedDocumentSegmentIndex ? ' is-selected' : '');
    if (index === selectedDocumentSegmentIndex) item.setAttribute('aria-current', 'true');
    item.disabled = lookupInFlight;
    var route = (segment.departureAirport || '—') + ' → ' + (segment.arrivalAirport || '—');
    var heading = document.createElement('strong');
    heading.textContent = formatFlightNumber(segment.flightNumber || '—') + ' · ' + route;
    var details = document.createElement('span');
    details.textContent = formatDocumentSegmentDateTime(segment);
    var choice = document.createElement('span');
    choice.className = 'segment-choice-label';
    choice.textContent = state === 'CONFLICT' ? t('confirmSegment')
      : state === 'MISSING' ? t('incompleteSegment')
        : state === 'NEEDS_CONFIRMATION' ? t('pendingSegment')
          : advisory ? t('advisorySegment')
            : index === selectedDocumentSegmentIndex ? t('selectedSegment') : t('readySegment');
    item.append(heading, details, choice);
    return item;
  }

  function renderSegmentSelector() {
    var selector = document.querySelector('[data-segment-selector]');
    var options = document.querySelector('[data-segment-options]');
    if (!selector || !options) return;
    var showItinerary = documentSegments.length > 0
      && (awaitingDocumentSelection || selectedDocumentSegmentIndex != null);
    selector.hidden = flightCorrectionOpen || !showItinerary;
    options.replaceChildren();
    if (!selector.hidden) {
      var context = document.querySelector('[data-segment-context]');
      var title = document.querySelector('[data-segment-title]');
      var lead = document.querySelector('[data-segment-lead]');
      var state = documentItineraryState();
      var stateKey = state === 'CONFLICT' ? 'Conflict' : state === 'MISSING' ? 'Missing'
        : state === 'NEEDS_CONFIRMATION' ? 'Pending' : 'Ready';
      context.textContent = '';
      context.hidden = true;
      title.textContent = t('itinerary' + stateKey + 'Title');
      lead.textContent = t('itinerary' + stateKey + 'Lead');
      documentSegments.forEach(function (segment, index) {
        options.append(createSegmentDisplay(segment, index));
      });
    }
    updateConfirmModeVisibility();
    var continueButton = document.querySelector('[data-action="continue"]');
    if (continueButton) continueButton.disabled = lookupInFlight || awaitingDocumentSelection || !currentFlight
	  || isDocumentFallbackFlight(currentFlight)
      || Boolean(departureStopReason(currentFlight)
        || currentFlight.requiresUserConfirmation && !conflictsConfirmed);
  }

  function explicitFlightAlternatives() {
    var providerCandidates = currentFlight && currentFlight.providerResults
      ? Object.keys(currentFlight.providerResults).map(function (provider) {
        return currentFlight.providerResults[provider];
      }).filter(function (candidate) {
        return candidate && candidate.outcome === 'SUCCESS';
      }) : [];
    var collections = [
      currentFlight && currentFlight.flightAlternatives,
      currentFlight && currentFlight.candidateFlights,
      providerCandidates,
      pendingDocument && pendingDocument.flightAlternatives,
      pendingDocument && pendingDocument.alternatives
    ];
    var candidates = [];
    collections.forEach(function (collection) {
      if (Array.isArray(collection)) candidates = candidates.concat(collection);
    });
    var seen = {};
    return candidates.filter(function (candidate) {
      if (!candidate || candidate.demo || candidate.live !== true && candidate.verified !== true) return false;
      var number = normalizeFlightNumber(candidate.flightNumber);
      var date = candidate.departureDate;
      var key = number + ':' + date;
      if (!validDocumentFlightNumber(number) || !validDocumentDepartureDate(date)
          || (currentFlight && number === normalizeFlightNumber(currentFlight.flightNumber)
            && date === currentFlight.departureDate) || seen[key]) return false;
      seen[key] = true;
      return true;
    }).slice(0, 3);
  }

  function renderFlightCorrection() {
    var panel = document.querySelector('[data-flight-correction]');
    var list = document.querySelector('[data-flight-candidate-list]');
    if (!panel || !list) return;
    panel.hidden = !flightCorrectionOpen;
    list.replaceChildren();
    var candidates = flightCorrectionOpen ? explicitFlightAlternatives() : [];
    candidates.forEach(function (candidate) {
      var button = document.createElement('button');
      button.type = 'button';
      button.className = 'flight-candidate';
      button.dataset.action = 'select-flight-candidate';
      button.dataset.flightNumber = normalizeFlightNumber(candidate.flightNumber);
      button.dataset.departureDate = candidate.departureDate;
      var title = document.createElement('strong');
      title.textContent = formatFlightNumber(candidate.flightNumber);
      var details = document.createElement('span');
      details.textContent = (candidate.departureAirport || '—') + ' → '
        + (candidate.arrivalAirport || '—') + ' · ' + formatDate(candidate.departureDate);
      button.append(title, details);
      list.append(button);
    });
    list.hidden = !candidates.length;
    var lead = document.querySelector('[data-flight-correction-lead]');
    if (lead) lead.textContent = t(candidates.length ? 'flightCorrectionCandidatesLead' : 'flightCorrectionLead');
    updateConfirmModeVisibility();
  }

  function updateConfirmModeVisibility() {
    var screen = document.querySelector('.screen-confirm');
    if (!screen) return;
    screen.classList.toggle('is-correcting-flight', flightCorrectionOpen);
    screen.classList.toggle('is-awaiting-segment', awaitingDocumentSelection);
    screen.setAttribute('aria-labelledby', flightCorrectionOpen ? 'flight-correction-title'
      : awaitingDocumentSelection ? 'segment-selector-title' : 'confirm-title');
  }

  function openFlightCorrection() {
    if (!currentFlight || currentScreen !== 'confirm') return;
    stopAutoRefresh(true);
    stopSpeech(false);
    flightCorrectionOpen = true;
    renderSegmentSelector();
    renderFlightCorrection();
    persistJourney();
    queueAutomaticSpeech();
  }

  function startFlightLookup(flightNumber, departureDate, demo, documentResult, demoScenario) {
    if (lookupInFlight) return;
    if (!navigator.onLine) {
      showToast(localeText('新航班需要联网查询。已保存的行程仍可查看。',
        'Connect to look up a new flight. Your saved journey remains available.',
        'Conéctate para buscar un vuelo nuevo. El viaje guardado sigue disponible.'));
      return;
    }
    var normalized = normalizeFlightNumber(flightNumber);
    var guardReason = lookupGuardReason(normalized, departureDate, demo, documentResult);
    if (guardReason) {
      announce(guardReason);
      return;
    }
    resetFlightChangePresentation();
    activeDemoScenarioId = demo && demoScenariosEnabled && demoScenario ? demoScenario : null;
    arrivalRefreshFlightKey = null;
    if (!documentResult) {
      documentSegments = [];
      usableDocumentSegmentIndexes = [];
      selectedDocumentSegmentIndex = null;
      awaitingDocumentSelection = false;
      documentRootFields = {};
      documentRootReviewFields = [];
      documentRootReviewIssues = [];
      confirmedDocumentFlightNumberIndexes = [];
    }
    flightCorrectionOpen = false;
    var activeRequest = ++requestSequence;
    var origin = currentScreen === 'loading' ? 'welcome' : currentScreen;
    lastLookupContext = {
      flightNumber: normalized,
      departureDate: departureDate || null,
      departureAirport: documentResult && documentResult.departureAirport || null,
      arrivalAirport: documentResult && documentResult.arrivalAirport || null,
      documentSegmentIndex: documentResult && Number.isInteger(selectedDocumentSegmentIndex)
        ? selectedDocumentSegmentIndex : null
    };
    currentError = null;
    retryRequest = function () { startFlightLookup(normalized, departureDate, demo, documentResult, demoScenario); };
    setLookupBusy(true);
    runLoading(loadingMessages, 'liveFlightLookupWaiting');

    postFlightJson('/api/journeys/resolve-flight',
      resolvePayload(normalized, departureDate, demo, documentResult, demoScenario, false), function () {
        updateLoadingProgress(72, loadingMessages, 1);
      }).then(function (flight) {
      if (activeRequest !== requestSequence) return;
      updateLoadingProgress(100, loadingMessages, 2);
      beginFlight(flight, documentResult);
      recordLiveChanges(flight.latestChanges);
      navigationHistory.push(origin);
      if (enterDemoScenarioPhase()) {
        presentInitialDelayNotice(flight);
        return;
      }
      showScreen('confirm');
      if (!presentInitialDelayNotice(flight)) {
        announce(localeText('航班信息已准备好，请确认。', 'Flight details ready. Please confirm.', 'Los datos del vuelo están listos. Confírmalos.'));
      }
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      clearInterval(loadingTimer);
      clearTimeout(loadingTimeout);
      showError(error, retryRequest);
    }).finally(function () { setLookupBusy(false); });
  }

  function validDocumentFlightNumber(value) {
    return /^[A-Z0-9]{2,3}\d{1,4}[A-Z]?$/.test(normalizeFlightNumber(value));
  }

  function normalizeFlightNumberConfidence(segment) {
    segment = segment || {};
    var explicit = String(segment.flightNumberConfidence || '').trim().toUpperCase();
    if (explicit === 'HIGH' || explicit === 'MEDIUM' || explicit === 'LOW') return explicit;
    if (!validDocumentFlightNumber(segment.flightNumber)) return 'LOW';
    if (segment.requiresFlightNumberConfirmation === true) return 'MEDIUM';
    var evidence = segment.fields && segment.fields.flight_number;
    var confidence = evidence && Number(evidence.confidence);
    if (Number.isFinite(confidence) && confidence > 0 && confidence < 0.60) return 'LOW';
    if (Number.isFinite(confidence) && confidence >= 0.60 && confidence < 0.85) return 'MEDIUM';
    return 'HIGH';
  }

  function validDocumentDepartureDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || ''))) return false;
    var parts = String(value).split('-').map(Number);
    var date = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
    return date.getUTCFullYear() === parts[0] && date.getUTCMonth() === parts[1] - 1
      && date.getUTCDate() === parts[2];
  }

  function validDocumentMonthDay(value) {
    value = String(value || '').trim();
    return /^\d{2}-\d{2}$/.test(value) && validDocumentDepartureDate('2000-' + value);
  }

  function documentMonthDayFromDate(value) {
    return validDocumentDepartureDate(value) ? String(value).substring(5) : null;
  }

  function formatDocumentMonthDay(value) {
    if (!validDocumentMonthDay(value)) return '—';
    var parts = String(value).split('-').map(Number);
    if (currentLocale === 'zh') return parts[0] + '月' + parts[1] + '日';
    var date = new Date(Date.UTC(2000, parts[0] - 1, parts[1]));
    return new Intl.DateTimeFormat(currentLocale === 'es' ? 'es-ES' : 'en-GB', {
      day: 'numeric', month: 'long', timeZone: 'UTC'
    }).format(date);
  }

  function formatDocumentSegmentDate(segment) {
    segment = segment || {};
    return validDocumentDepartureDate(segment.departureDate) ? formatDate(segment.departureDate)
      : formatDocumentMonthDay(segment.departureMonthDay);
  }

  function formatDocumentSegmentTime(segment) {
    segment = segment || {};
    var scheduled = String(segment.scheduledDeparture || '').trim();
    var timeOnly = /^([01]\d|2[0-3]):([0-5]\d)(?::[0-5]\d)?$/.exec(scheduled);
    if (timeOnly) return timeOnly[1] + ':' + timeOnly[2];
    if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(scheduled)
        || !Number.isFinite(Date.parse(scheduled))) return '';
    return formatAtOffset(scheduled, false, offsetMinutesFromDateTime(scheduled));
  }

  function formatDocumentSegmentDateTime(segment) {
    var date = formatDocumentSegmentDate(segment);
    var time = formatDocumentSegmentTime(segment);
    if (date === '—') date = '';
    return date && time ? date + ' · ' + time : date || time || '—';
  }

  function documentSegmentFlightNumberConfirmed(segment, index) {
    return Boolean(segment && segment.flightNumberConfirmedByUser)
      || Number.isInteger(index) && confirmedDocumentFlightNumberIndexes.indexOf(index) >= 0;
  }

  function documentSegmentFlightNumberTrusted(segment, index) {
    if (!validDocumentFlightNumber(segment && segment.flightNumber)) return false;
    return normalizeFlightNumberConfidence(segment) === 'HIGH'
      || documentSegmentFlightNumberConfirmed(segment, index);
  }

  function canonicalReviewField(value) {
    value = String(value || '').replace(/^segments\[(\d+)\]\./, '');
    return value.replace(/^conflict\./i, '').replace(/([a-z0-9])([A-Z])/g, '$1_$2')
      .replace(/[.\s-]+/g, '_').toLowerCase();
  }

  function reviewIssueSegmentIndex(issue) {
    if (!issue || typeof issue !== 'object') return null;
    if (issue.segmentIndex != null && String(issue.segmentIndex).trim() !== '') {
      var explicit = Number(issue.segmentIndex);
      if (Number.isInteger(explicit) && explicit >= 0) return explicit;
    }
    var match = /^segments\[(\d+)\]\./.exec(String(issue.field || ''));
    return match ? Number(match[1]) : null;
  }

  function reviewCandidateValue(candidate) {
    if (candidate == null) return '';
    if (typeof candidate !== 'object') return String(candidate).trim();
    var value = candidate.normalizedValue != null ? candidate.normalizedValue
      : candidate.value != null ? candidate.value : candidate.rawValue;
    return value == null ? '' : String(value).trim();
  }

  function normalizeReviewCandidate(candidate) {
    var value = reviewCandidateValue(candidate);
    if (!value) return null;
    var source = candidate && typeof candidate === 'object' ? candidate.source : null;
    var confidence = candidate && typeof candidate === 'object' ? Number(candidate.confidence) : NaN;
    return Object.assign({}, candidate && typeof candidate === 'object' ? candidate : {}, {
      value: value,
      normalizedValue: value,
      source: source == null ? null : String(source),
      confidence: Number.isFinite(confidence) ? confidence : null
    });
  }

  function normalizeReviewIssue(issue) {
    if (!issue || typeof issue !== 'object') return null;
    var seen = {};
    var candidates = (Array.isArray(issue.candidates) ? issue.candidates : [])
      .map(normalizeReviewCandidate).filter(function (candidate) {
        if (!candidate || seen[candidate.value]) return false;
        seen[candidate.value] = true;
        return true;
      });
    var selectedValue = reviewCandidateValue(issue.selectedValue);
    var status = String(issue.status || '').trim().toUpperCase();
    return Object.assign({}, issue, {
      segmentIndex: reviewIssueSegmentIndex(issue),
      field: canonicalReviewField(issue.field),
      kind: String(issue.kind || 'REVIEW').trim().toUpperCase(),
      blocking: issue.blocking === true,
      candidates: candidates,
      selectedValue: selectedValue || null,
      reason: issue.reason == null ? null : String(issue.reason),
      resolved: issue.resolved === true || status === 'RESOLVED' || Boolean(selectedValue),
      legacy: issue.legacy === true
    });
  }

  function reviewIssuePayload(issue, segmentIndex) {
    return {
      segmentIndex: segmentIndex,
      field: issue.field,
      kind: issue.kind,
      blocking: issue.blocking,
      candidates: issue.candidates.map(function (candidate) {
        return {
          field: candidate.field || issue.field,
          rawValue: candidate.rawValue == null ? candidate.value : candidate.rawValue,
          normalizedValue: candidate.normalizedValue || candidate.value,
          source: candidate.source,
          confidence: candidate.confidence,
          page: candidate.page == null ? null : candidate.page,
          boundingBox: candidate.boundingBox || null,
          nearbyAnchor: candidate.nearbyAnchor || 'UNKNOWN',
          segmentIndex: segmentIndex,
          disposition: candidate.disposition || 'CANDIDATE',
          reason: candidate.reason || issue.reason || 'UNSPECIFIED'
        };
      }),
      selectedValue: issue.selectedValue,
      reason: issue.reason
    };
  }

  function reviewFieldCurrentValue(field, segment) {
    segment = segment || {};
    if (field === 'flight_number') return normalizeFlightNumber(segment.flightNumber);
    if (field === 'departure_date') return String(segment.departureDate || '');
    return String(segment[field] || '');
  }

  function legacyReviewIssue(field, index, segment) {
    var local = String(field || '').replace(/^segments\[\d+\]\./, '');
    if (local.toLowerCase().indexOf('conflict.') !== 0) return null;
    var canonical = canonicalReviewField(local);
    var value = reviewFieldCurrentValue(canonical, segment);
    return normalizeReviewIssue({
      segmentIndex: index,
      field: canonical,
      kind: 'LEGACY_CONFLICT',
      blocking: canonical === 'flight_number' || canonical === 'departure_date',
      candidates: value ? [{ value: value, source: 'LEGACY' }] : [],
      legacy: true
    });
  }

  function reviewIssueIsCritical(issue) {
    return issue && (issue.field === 'flight_number' || issue.field === 'departure_date');
  }

  function reviewIssueIsBlockingCritical(issue) {
    if (!reviewIssueIsCritical(issue) || issue.blocking !== true || issue.resolved) return false;
    return true;
  }

  function documentSegmentReviewIssues(index, reviewIssues, reviewFields, segment) {
    segment = segment || documentSegments[index] || {};
    var issueSource = Array.isArray(reviewIssues) ? reviewIssues : documentRootReviewIssues;
    var normalized = issueSource.map(normalizeReviewIssue).filter(function (issue) {
      return issue && (issue.segmentIndex == null || issue.segmentIndex === index);
    });
    var legacyFields = documentSegmentReviewFields(index, reviewFields);
    legacyFields.forEach(function (field) {
      var legacy = legacyReviewIssue(field, index, segment);
      if (legacy && !normalized.some(function (issue) {
        return issue.field === legacy.field;
      })) normalized.push(legacy);
    });
    if (String(segment.dateSource || '').toUpperCase() === 'CONFLICT'
        && !normalized.some(function (issue) { return issue.field === 'departure_date'; })) {
      normalized.push(legacyReviewIssue('conflict.departure_date', index, segment));
    }
    return normalized.filter(Boolean);
  }

  function reviewFieldIsCriticalConflict(field) {
    field = String(field || '').replace(/^segments\[\d+\]\./, '').toLowerCase();
    return field === 'conflict.flight_number' || field === 'conflict.flightnumber'
      || field === 'conflict.departure_date' || field === 'conflict.departuredate';
  }

  function reviewFieldIsFlightNumber(field) {
    field = String(field || '').replace(/^segments\[\d+\]\./, '').toLowerCase();
    return field === 'flight_number' || field === 'flightnumber'
      || field === 'conflict.flight_number' || field === 'conflict.flightnumber';
  }

  function reviewFieldIsDepartureDate(field) {
    field = String(field || '').replace(/^segments\[\d+\]\./, '').toLowerCase();
    return field === 'departure_date' || field === 'departuredate'
      || field === 'conflict.departure_date' || field === 'conflict.departuredate';
  }

  function documentSegmentHasCriticalConflict(index, segment, reviewFields, reviewIssues) {
    return documentSegmentReviewIssues(index, reviewIssues, reviewFields, segment)
      .some(reviewIssueIsBlockingCritical);
  }

  function documentSegmentHasAdvisory(index, segment, reviewFields, reviewIssues) {
    var confidence = normalizeFlightNumberConfidence(segment);
    if (validDocumentFlightNumber(segment && segment.flightNumber)
		&& confidence === 'LOW') return true;
    return documentSegmentReviewIssues(index, reviewIssues, reviewFields, segment).some(function (issue) {
		return issue.field === 'departure_date' && !issue.resolved && !reviewIssueIsBlockingCritical(issue)
        && issue.kind !== 'INTERNAL' && issue.kind !== 'RESOLVED';
    });
  }

  function documentSegmentLookupReady(segment, index, reviewFields, reviewIssues) {
    segment = segment || {};
    return validDocumentFlightNumber(segment.flightNumber)
      && validDocumentDepartureDate(segment.departureDate)
      && !documentSegmentHasCriticalConflict(index, segment, reviewFields, reviewIssues);
  }

  function documentSegmentReviewFields(index, reviewFields) {
    var prefix = 'segments[' + index + '].';
    var source = Array.isArray(reviewFields) ? reviewFields : documentRootReviewFields;
    return source.filter(function (field) {
      field = String(field || '');
      return field.indexOf('segments[') !== 0 || field.indexOf(prefix) === 0;
    }).map(function (field) {
      field = String(field || '');
      return field.indexOf(prefix) === 0 ? field.substring(prefix.length) : field;
    });
  }

  function documentSegmentHasConflict(index) {
    return documentSegmentHasCriticalConflict(index, documentSegments[index]);
  }

  function documentSegmentState(segment, index) {
    segment = segment || {};
    if (documentSegmentHasCriticalConflict(index, segment)) return 'CONFLICT';
    if (!validDocumentDepartureDate(segment.departureDate)
        || !validDocumentFlightNumber(segment.flightNumber)) return 'MISSING';
    return 'QUERYABLE';
  }

  function documentItineraryState() {
    var states = documentSegments.map(documentSegmentState);
    if (states.indexOf('CONFLICT') >= 0) return 'CONFLICT';
    if (states.indexOf('MISSING') >= 0) return 'MISSING';
    if (states.indexOf('NEEDS_CONFIRMATION') >= 0) return 'NEEDS_CONFIRMATION';
    return states.length ? 'QUERYABLE' : 'MISSING';
  }

  function lookupGuardReason(flightNumber, departureDate, demo, documentResult) {
    if (!validDocumentFlightNumber(flightNumber)) return localeText(
      '请输入有效航班号。', 'Enter a valid flight number.', 'Introduce un número de vuelo válido.');
    if (!validDocumentDepartureDate(departureDate)) return localeText(
      '请输入真实存在的完整出发日期。', 'Enter a complete, valid departure date.', 'Introduce una fecha de salida completa y válida.');
    if (demo || !documentResult) return null;
    var index = selectedDocumentSegmentIndex;
    var segment = Number.isInteger(index) ? documentSegments[index] : null;
    if (!segment || normalizeFlightNumber(segment.flightNumber) !== flightNumber
        || segment.departureDate !== departureDate) return localeText(
      '当前航段已变化，请重新选择并核对。', 'This segment changed. Select and review it again.', 'Este tramo cambió. Selecciónalo y revísalo de nuevo.');
    if (documentSegmentHasCriticalConflict(index, segment,
        documentResult.needsReviewFields, documentResult.reviewIssues)) return localeText(
      '请先解决航班号或出发日期冲突。', 'Resolve the flight-number or departure-date conflict first.', 'Resuelve primero el conflicto del número de vuelo o la fecha de salida.');
    return null;
  }

  function normalizeDocumentSegment(segment) {
    if (!segment || typeof segment !== 'object') return {};
    var normalized = Object.assign({}, segment);
    normalized.flightNumber = validDocumentFlightNumber(segment.flightNumber)
      ? normalizeFlightNumber(segment.flightNumber) : null;
    normalized.flightNumberConfidence = normalizeFlightNumberConfidence(segment);
    normalized.flightNumberConfirmedByUser = Boolean(segment.flightNumberConfirmedByUser);
	normalized.requiresFlightNumberConfirmation = normalized.flightNumberConfidence === 'LOW'
      && !normalized.flightNumberConfirmedByUser;
    normalized.dateSource = String(segment.dateSource || '').trim().toUpperCase() || null;
    var departureDate = String(segment.departureDate || '').trim();
    if (validDocumentDepartureDate(departureDate)) {
      normalized.departureDate = departureDate;
      normalized.departureMonthDay = documentMonthDayFromDate(departureDate);
      return normalized;
    }
    normalized.departureDate = null;
    normalized.departureMonthDay = validDocumentMonthDay(segment.departureMonthDay)
      ? String(segment.departureMonthDay).trim() : null;
    var match = /^(\d{4}-\d{2}-\d{2})T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)?$/.exec(
      String(segment.scheduledDeparture || '').trim());
    if (!match || !validDocumentDepartureDate(match[1])) return normalized;
    normalized.departureDate = match[1];
    normalized.departureMonthDay = documentMonthDayFromDate(match[1]);
    return normalized;
  }

  function normalizeDocumentResult(result) {
    var normalized = result && typeof result === 'object' ? Object.assign({}, result) : {};
    normalized.segments = Array.isArray(normalized.segments)
      ? normalized.segments.map(normalizeDocumentSegment) : [];
    normalized.reviewIssues = Array.isArray(normalized.reviewIssues)
      ? normalized.reviewIssues.map(normalizeReviewIssue).filter(Boolean) : [];
    return normalized;
  }

  function classifyDocumentResult(result) {
    var segments = result && Array.isArray(result.segments) ? result.segments.slice() : [];
    if (!segments.length) return { outcome: 'UNUSABLE', segments: [], usableIndexes: [], partialSegment: null, partialIndex: null };
    var usableIndexes = [];
    segments.forEach(function (segment, index) {
      segment = segment || {};
      if (documentSegmentLookupReady(segment, index, result && result.needsReviewFields,
          result && result.reviewIssues)) {
        usableIndexes.push(index);
      }
    });
    if (usableIndexes.length === segments.length) return { outcome: 'USABLE', segments: segments, usableIndexes: usableIndexes, partialSegment: null, partialIndex: null };
    var partialIndex = segments.findIndex(function (segment) {
      segment = segment || {};
      return validDocumentFlightNumber(segment.flightNumber)
        || validDocumentDepartureDate(segment.departureDate)
        || validDocumentMonthDay(segment.departureMonthDay);
    });
    return partialIndex >= 0
      ? { outcome: 'PARTIAL', segments: segments, usableIndexes: usableIndexes, partialSegment: segments[partialIndex], partialIndex: partialIndex }
      : { outcome: 'UNUSABLE', segments: segments, usableIndexes: [], partialSegment: null, partialIndex: null };
  }

  function resetDocumentResultState() {
    clearJourney();
  }

  function showUnusableDocument() {
    resetDocumentResultState();
    setStaffContent(
      '请帮我找到正确的旅行文件', 'Please help me find the right travel document',
      '这个文件没有读到航班号和出发日期。请告诉我应该使用哪一份航空公司行程单、预订确认、电子客票或登机牌。',
      'No flight number and departure date were found in this file. Please tell me which airline itinerary, booking confirmation, e-ticket, or boarding pass I should use.',
      'Ayúdeme a encontrar el documento de viaje correcto',
      'No se encontraron el número de vuelo ni la fecha de salida. Dígame qué itinerario, confirmación, billete electrónico o tarjeta de embarque debo usar.');
    navigationHistory.push('welcome');
    showScreen('document-unusable');
    announce(t('documentUnusableTitle'));
  }

  function resetManualDocumentEntry() {
    partialDocumentEntry = false;
    manualCandidateEntryFields = { flight_number: false, departure_date: false };
    var panel = document.querySelector('[data-manual-document-segments]');
    if (panel) panel.hidden = true;
    var candidates = document.querySelector('[data-review-candidates]');
    if (candidates) {
      candidates.hidden = true;
      var candidateFields = candidates.querySelector('[data-review-candidate-fields]');
      if (candidateFields) candidateFields.replaceChildren();
    }
    var flightField = document.querySelector('[data-manual-flight-field]');
    var dateField = document.querySelector('[data-manual-date-field]');
    setManualFieldState(flightField, document.getElementById('flight-input'), true);
    setManualFieldState(dateField, document.getElementById('date-input'), true);
    setManualConfirmationState(document.querySelector('[data-manual-flight-confirmation]'),
      document.getElementById('flight-confirm-input'), false);
    setManualConfirmationState(document.querySelector('[data-manual-date-confirmation]'),
      document.getElementById('date-confirm-input'), false);
    var primary = document.querySelector('[data-manual-primary]');
    if (primary) {
      primary.dataset.mode = 'query';
      primary.dataset.i18n = 'searchLiveFlight';
      primary.textContent = t('searchLiveFlight');
    }
    var help = document.querySelector('[data-manual-document-help]');
    if (help) help.hidden = true;
    var demo = document.querySelector('.manual-actions [data-action="demo-flight"]');
    if (demo) demo.hidden = !demoFlightEnabled;
    var actions = document.querySelector('.manual-actions');
    if (actions) actions.classList.remove('is-partial-document');
    renderManualPageCopy('ORDINARY');
  }

  function setManualFieldState(field, input, visible, enabled) {
    if (field) field.hidden = !visible;
    if (input) {
      var inputEnabled = enabled == null ? visible : enabled;
      input.disabled = !inputEnabled;
      input.required = inputEnabled;
    }
  }

  function setManualConfirmationState(field, input, visible, enabled) {
    if (field) field.hidden = !visible;
    if (input) {
      var inputEnabled = enabled == null ? visible : enabled;
      input.disabled = !inputEnabled;
      if (!inputEnabled) input.checked = false;
      input.setCustomValidity('');
    }
  }

  function populateManualDocumentEntry() {
    manualCandidateEntryFields = { flight_number: false, departure_date: false };
    var selected = Number.isInteger(selectedDocumentSegmentIndex)
      ? documentSegments[selectedDocumentSegmentIndex] || {} : {};
    var flightInput = document.getElementById('flight-input');
    var dateInput = document.getElementById('date-input');
    if (flightInput) {
      flightInput.value = validDocumentFlightNumber(selected.flightNumber)
        ? formatFlightNumber(selected.flightNumber) : '';
      flightInput.setCustomValidity('');
    }
    setDateInputValue(dateInput, validDocumentDepartureDate(selected.departureDate)
      ? selected.departureDate : '');
    if (dateInput) dateInput.setCustomValidity('');
    setManualConfirmationState(document.querySelector('[data-manual-flight-confirmation]'),
      document.getElementById('flight-confirm-input'), false);
    setManualConfirmationState(document.querySelector('[data-manual-date-confirmation]'),
      document.getElementById('date-confirm-input'), false);
  }

  function renderManualReviewCandidates(index, segment) {
    var panel = document.querySelector('[data-review-candidates]');
    var fields = document.querySelector('[data-review-candidate-fields]');
    var candidateModes = { flight_number: false, departure_date: false };
    if (!panel || !fields) return candidateModes;
    var issues = documentSegmentReviewIssues(index, null, null, segment)
      .filter(reviewIssueIsBlockingCritical)
      .filter(function (issue) { return issue.candidates.length > 0; });
    fields.replaceChildren();
    panel.hidden = !issues.length;
    issues.forEach(function (issue) {
      candidateModes[issue.field] = true;
      var group = document.createElement('section');
      group.className = 'review-candidate-group';
      group.dataset.reviewCandidateGroup = issue.field;
      var heading = document.createElement('h2');
      heading.textContent = t(issue.field === 'flight_number' ? 'flightCandidatePrompt' : 'dateCandidatePrompt');
      group.append(heading);
      var list = document.createElement('div');
      list.className = 'review-candidate-list';
      var confirmation = document.getElementById(issue.field === 'flight_number'
        ? 'flight-confirm-input' : 'date-confirm-input');
      issue.candidates.forEach(function (candidate) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'review-candidate';
        button.dataset.action = 'select-review-candidate';
        button.dataset.segmentIndex = String(index);
        button.dataset.reviewField = issue.field;
        button.dataset.candidateValue = candidate.value;
        var value = document.createElement('strong');
        value.textContent = issue.field === 'flight_number'
          ? formatFlightNumber(candidate.value) : formatVisibleDate(candidate.value);
        button.append(value);
        var currentInput = document.getElementById(issue.field === 'flight_number'
          ? 'flight-input' : 'date-input');
        var currentValue = issue.field === 'flight_number'
          ? normalizeFlightNumber(currentInput && currentInput.value) : currentInput && currentInput.value;
        button.setAttribute('aria-pressed', String(Boolean(confirmation && confirmation.checked)
          && currentValue === candidate.value));
        list.append(button);
      });
      var manualButton = document.createElement('button');
      manualButton.type = 'button';
      manualButton.className = 'review-candidate-manual';
      manualButton.dataset.action = 'manual-review-entry';
      manualButton.dataset.reviewField = issue.field;
      manualButton.textContent = t('manualCandidateEntry');
      list.append(manualButton);
      group.append(list);
      fields.append(group);
    });
    return candidateModes;
  }

  function selectDocumentReviewCandidate(button) {
    var index = Number(button && button.dataset.segmentIndex);
    var field = button && button.dataset.reviewField;
    var value = button && button.dataset.candidateValue || '';
    if (!Number.isInteger(index) || index !== selectedDocumentSegmentIndex
        || (field !== 'flight_number' && field !== 'departure_date')) return;
    var input = document.getElementById(field === 'flight_number' ? 'flight-input' : 'date-input');
    var confirmation = document.getElementById(field === 'flight_number'
      ? 'flight-confirm-input' : 'date-confirm-input');
    if (!input) return;
    if (field === 'flight_number') input.value = formatFlightNumber(value);
    else setDateInputValue(input, value);
    input.setCustomValidity('');
    if (confirmation && !confirmation.disabled) confirmation.checked = true;
    document.querySelectorAll('[data-action="select-review-candidate"][data-review-field="'
        + field + '"]').forEach(function (candidateButton) {
      candidateButton.setAttribute('aria-pressed', String(candidateButton === button));
    });
  }

  function showManualReviewEntry(button) {
    var field = button && button.dataset.reviewField;
    if (field !== 'flight_number' && field !== 'departure_date') return;
    manualCandidateEntryFields[field] = true;
    var input = document.getElementById(field === 'flight_number' ? 'flight-input' : 'date-input');
    var confirmation = document.getElementById(field === 'flight_number'
      ? 'flight-confirm-input' : 'date-confirm-input');
    if (field === 'flight_number') input.value = '';
    else setDateInputValue(input, '');
    if (confirmation) confirmation.checked = false;
    renderManualDocumentEntry();
    input.focus({ preventScroll: true });
  }

  function renderManualPageCopy(state) {
    var titleKey = state === 'MISSING' ? 'manualTitleComplete'
      : state === 'ORDINARY' ? 'manualTitleOrdinary' : 'manualTitleCheck';
    var statusKey = state === 'CONFLICT' ? 'manualStatusConflict'
      : state === 'MISSING' ? 'manualStatusMissing'
        : state === 'REVIEW' ? 'manualStatusReview'
          : state === 'READY' ? 'manualStatusReady' : 'manualStatusOrdinary';
    var title = document.querySelector('[data-manual-title]');
    var status = document.querySelector('[data-manual-status]');
    var back = document.querySelector('[data-manual-back]');
    if (title) {
      title.dataset.i18n = titleKey;
      title.textContent = t(titleKey);
    }
    if (status) {
      status.dataset.i18n = statusKey;
      status.textContent = t(statusKey);
    }
    if (back) {
      var backKey = partialDocumentEntry ? 'manualBackSegments' : 'manualBackHome';
      back.dataset.i18n = backKey;
      back.textContent = t(backKey);
    }
  }

  function manualDocumentProblemFields(index, segment) {
    var issues = documentSegmentReviewIssues(index, null, null, segment);
    var legacyReviewFields = documentSegmentReviewFields(index);
    var flightProblem = !validDocumentFlightNumber(segment && segment.flightNumber)
      || normalizeFlightNumberConfidence(segment) === 'LOW'
      || issues.some(function (issue) { return issue.field === 'flight_number' && !issue.resolved; })
      || legacyReviewFields.some(reviewFieldIsFlightNumber);
    var dateProblem = !validDocumentDepartureDate(segment && segment.departureDate)
      || issues.some(function (issue) { return issue.field === 'departure_date' && !issue.resolved; })
      || legacyReviewFields.some(reviewFieldIsDepartureDate);
    return { flight_number: flightProblem, departure_date: dateProblem };
  }

  function showManualDocumentHelp() {
    if (!partialDocumentEntry || !Number.isInteger(selectedDocumentSegmentIndex)) return;
    var problems = manualDocumentProblemFields(selectedDocumentSegmentIndex,
      documentSegments[selectedDocumentSegmentIndex] || {});
    var field = problems.flight_number && problems.departure_date ? 'flight_information'
      : problems.flight_number ? 'flight_number'
        : problems.departure_date ? 'departure_date' : 'flight_information';
    showStaff({ field: field });
  }

  function documentQueryTargetLabel(segment) {
    return t('queryTarget', {
      flight: normalizeFlightNumber(segment && segment.flightNumber || ''),
      date: formatDate(segment && segment.departureDate)
    });
  }

  function renderManualDocumentEntry() {
    var panel = document.querySelector('[data-manual-document-segments]');
    var options = document.querySelector('[data-manual-segment-options]');
    if (!panel || !options) return;
    if (!partialDocumentEntry) {
      panel.hidden = true;
      options.replaceChildren();
      var ordinaryCandidates = document.querySelector('[data-review-candidates]');
      if (ordinaryCandidates) ordinaryCandidates.hidden = true;
      setManualFieldState(document.querySelector('[data-manual-flight-field]'),
        document.getElementById('flight-input'), true);
      setManualFieldState(document.querySelector('[data-manual-date-field]'),
        document.getElementById('date-input'), true);
      setManualConfirmationState(document.querySelector('[data-manual-flight-confirmation]'),
        document.getElementById('flight-confirm-input'), false);
      setManualConfirmationState(document.querySelector('[data-manual-date-confirmation]'),
        document.getElementById('date-confirm-input'), false);
      var ordinaryHelp = document.querySelector('[data-manual-document-help]');
      if (ordinaryHelp) ordinaryHelp.hidden = true;
      var ordinaryDemo = document.querySelector('.manual-actions [data-action="demo-flight"]');
      if (ordinaryDemo) ordinaryDemo.hidden = !demoFlightEnabled;
      var ordinaryActions = document.querySelector('.manual-actions');
      if (ordinaryActions) ordinaryActions.classList.remove('is-partial-document');
      var ordinaryPrimary = document.querySelector('[data-manual-primary]');
      if (ordinaryPrimary) {
        ordinaryPrimary.dataset.mode = 'query';
        ordinaryPrimary.dataset.i18n = 'searchLiveFlight';
        ordinaryPrimary.textContent = t('searchLiveFlight');
        ordinaryPrimary.removeAttribute('aria-label');
      }
      renderManualPageCopy('ORDINARY');
      return;
    }
    panel.hidden = !partialDocumentEntry || !documentSegments.length;
    options.replaceChildren();
    if (!panel.hidden) documentSegments.forEach(function (segment, index) {
      options.append(createSegmentDisplay(segment, index));
    });
    var selected = Number.isInteger(selectedDocumentSegmentIndex)
      ? documentSegments[selectedDocumentSegmentIndex] || {} : {};
    var hasFlightNumber = validDocumentFlightNumber(selected.flightNumber);
    var hasDepartureDate = validDocumentDepartureDate(selected.departureDate);
    var blockingIssues = documentSegmentReviewIssues(selectedDocumentSegmentIndex, null, null, selected)
      .filter(reviewIssueIsBlockingCritical);
    var allCriticalIssues = documentSegmentReviewIssues(selectedDocumentSegmentIndex, null, null, selected)
      .filter(reviewIssueIsCritical);
    var legacyReviewFields = documentSegmentReviewFields(selectedDocumentSegmentIndex);
    var flightConflict = blockingIssues.some(function (issue) { return issue.field === 'flight_number'; });
    var dateConflict = blockingIssues.some(function (issue) { return issue.field === 'departure_date'; });
    var flightReview = allCriticalIssues.some(function (issue) {
		return issue.field === 'flight_number' && !issue.resolved && !reviewIssueIsBlockingCritical(issue);
	}) && normalizeFlightNumberConfidence(selected) === 'LOW'
		|| legacyReviewFields.some(reviewFieldIsFlightNumber)
			&& normalizeFlightNumberConfidence(selected) === 'LOW'
		|| hasFlightNumber && normalizeFlightNumberConfidence(selected) === 'LOW';
    var dateReview = allCriticalIssues.some(function (issue) {
		return issue.field === 'departure_date' && !issue.resolved && !reviewIssueIsBlockingCritical(issue);
    }) || legacyReviewFields.some(reviewFieldIsDepartureDate);
    var flightField = document.querySelector('[data-manual-flight-field]');
    var dateField = document.querySelector('[data-manual-date-field]');
    var candidateModes = renderManualReviewCandidates(selectedDocumentSegmentIndex, selected);
    var flightCandidateMode = flightConflict && candidateModes.flight_number
      && !manualCandidateEntryFields.flight_number;
    var dateCandidateMode = dateConflict && candidateModes.departure_date
      && !manualCandidateEntryFields.departure_date;
    var flightNeedsInput = !hasFlightNumber || flightConflict || flightReview;
    var dateNeedsInput = !hasDepartureDate || dateConflict || dateReview;
    setManualFieldState(flightField, document.getElementById('flight-input'),
      flightNeedsInput && !flightCandidateMode, flightNeedsInput);
    setManualFieldState(dateField, document.getElementById('date-input'),
      dateNeedsInput && !dateCandidateMode, dateNeedsInput);
    setManualConfirmationState(document.querySelector('[data-manual-flight-confirmation]'),
      document.getElementById('flight-confirm-input'),
      (flightConflict || flightReview) && !flightCandidateMode, flightConflict || flightReview);
    setManualConfirmationState(document.querySelector('[data-manual-date-confirmation]'),
      document.getElementById('date-confirm-input'),
      (dateConflict || dateReview) && !dateCandidateMode, dateConflict || dateReview);
    var state = flightConflict || dateConflict ? 'CONFLICT'
      : !hasFlightNumber || !hasDepartureDate ? 'MISSING'
        : flightReview || dateReview ? 'REVIEW' : 'READY';
    var ready = state === 'READY' && documentSegmentLookupReady(selected, selectedDocumentSegmentIndex);
    renderManualPageCopy(state);
    var primary = document.querySelector('[data-manual-primary]');
    if (primary) {
      var mode = ready ? 'query' : 'save';
      primary.dataset.mode = mode;
      if (ready) {
        primary.dataset.i18n = 'searchFlightPartial';
        primary.textContent = t('searchFlightPartial');
        primary.removeAttribute('aria-label');
      } else {
        primary.dataset.i18n = 'completeDocument';
        primary.textContent = t('completeDocument');
        primary.removeAttribute('aria-label');
      }
    }
    var help = document.querySelector('[data-manual-document-help]');
    if (help) help.hidden = false;
    var demo = document.querySelector('.manual-actions [data-action="demo-flight"]');
    if (demo) demo.hidden = true;
    var actions = document.querySelector('.manual-actions');
    if (actions) actions.classList.add('is-partial-document');
  }

  function syncUsableDocumentSegmentIndexes() {
    usableDocumentSegmentIndexes = documentSegments.reduce(function (indexes, segment, index) {
      if (documentSegmentLookupReady(segment, index)) indexes.push(index);
      return indexes;
    }, []);
  }

  function clearDocumentSegmentReviewFields(index, predicate) {
    var prefix = documentSegments.length === 1 ? '' : 'segments[' + index + '].';
    documentRootReviewFields = documentRootReviewFields.filter(function (field) {
      field = String(field || '');
      if (prefix && field.indexOf(prefix) !== 0) return true;
      var local = prefix ? field.substring(prefix.length) : field;
      return !predicate(local);
    });
  }

  function clearDocumentSegmentReviewIssues(index, predicate) {
    documentRootReviewIssues = documentRootReviewIssues.filter(function (rawIssue) {
      var issue = normalizeReviewIssue(rawIssue);
      if (!issue) return false;
      if (issue.segmentIndex != null && issue.segmentIndex !== index) return true;
      if (issue.segmentIndex == null && documentSegments.length > 1) return true;
      return !predicate(issue.field);
    });
  }

  function applyDocumentSegmentCompletion(index, flightNumber, departureDate, confirmations) {
    index = Number(index);
    if (!Number.isInteger(index) || index < 0 || index >= documentSegments.length) return null;
    var current = documentSegments[index] || {};
    confirmations = confirmations || {};
    var blockingIssues = documentSegmentReviewIssues(index, null, null, current)
      .filter(reviewIssueIsBlockingCritical);
    var flightConflict = blockingIssues.some(function (issue) { return issue.field === 'flight_number'; });
    var dateConflict = blockingIssues.some(function (issue) { return issue.field === 'departure_date'; });
    var flightNeedsUpdate = !validDocumentFlightNumber(current.flightNumber) || flightConflict;
    var dateNeedsUpdate = !validDocumentDepartureDate(current.departureDate)
      || dateConflict;
    var completedFlightNumber = flightNeedsUpdate ? normalizeFlightNumber(flightNumber)
      : normalizeFlightNumber(current.flightNumber);
    var completedDepartureDate = dateNeedsUpdate ? departureDate : current.departureDate;
    if (!validDocumentFlightNumber(completedFlightNumber)
        || !validDocumentDepartureDate(completedDepartureDate)) return null;
    if (!dateConflict && validDocumentMonthDay(current.departureMonthDay)
        && documentMonthDayFromDate(completedDepartureDate) !== current.departureMonthDay) return null;
    var updated = Object.assign({}, current);
    if (flightNeedsUpdate) {
      if (flightConflict && !confirmations.flightNumber) return null;
      updated.flightNumber = completedFlightNumber;
      updated.flightNumberConfirmedByUser = true;
      updated.requiresFlightNumberConfirmation = false;
      confirmedDocumentFlightNumberIndexes = confirmedDocumentFlightNumberIndexes
        .filter(function (value) { return value !== index; }).concat(index);
      clearDocumentSegmentReviewFields(index, reviewFieldIsFlightNumber);
      clearDocumentSegmentReviewIssues(index, function (field) { return field === 'flight_number'; });
    }
    if (dateNeedsUpdate) {
      if (dateConflict && !confirmations.departureDate) return null;
      updated.departureDate = completedDepartureDate;
      updated.departureMonthDay = documentMonthDayFromDate(completedDepartureDate);
      updated.dateSource = 'EXPLICIT_FULL_DATE';
      clearDocumentSegmentReviewFields(index, reviewFieldIsDepartureDate);
      clearDocumentSegmentReviewIssues(index, function (field) { return field === 'departure_date'; });
    }
    documentSegments[index] = updated;
    syncUsableDocumentSegmentIndexes();
    if (pendingDocument) pendingDocument = Object.assign({}, pendingDocument, {
      segments: documentSegments.slice(),
      needsReviewFields: documentRootReviewFields.slice(),
      reviewIssues: documentRootReviewIssues.slice()
    });
    return updated;
  }

  function setDocumentExtractionState(result, assessment) {
    documentSegments = assessment.segments.slice();
    usableDocumentSegmentIndexes = assessment.usableIndexes.slice();
    documentRootFields = Object.assign({}, result.fields || {});
    documentRootReviewFields = Array.isArray(result.needsReviewFields) ? result.needsReviewFields.slice() : [];
    documentRootReviewIssues = Array.isArray(result.reviewIssues)
      ? result.reviewIssues.map(normalizeReviewIssue).filter(Boolean) : [];
    confirmedDocumentFlightNumberIndexes = documentSegments.reduce(function (indexes, segment, index) {
      if (segment && segment.flightNumberConfirmedByUser) indexes.push(index);
      return indexes;
    }, []);
    pendingDocument = Object.assign({}, result, {
      segments: documentSegments.slice(), reviewIssues: documentRootReviewIssues.slice()
    });
    syncUsableDocumentSegmentIndexes();
  }

  function showDocumentSegmentSelection(result, assessment) {
    resetDocumentResultState();
    setDocumentExtractionState(result, assessment);
    selectedDocumentSegmentIndex = null;
    awaitingDocumentSelection = true;
    currentFlight = null;
    currentAction = null;
    stepChangeAlert = null;
    journeyContext = null;
    currentError = null;
    lastLookupContext = null;
    flightCorrectionOpen = false;
    renderDeparturePassedConfirmation(null);
    navigationHistory.push('welcome');
    renderSegmentSelector();
    showScreen('confirm');
    persistJourney();
    announce(t('segmentSelectionTitle'));
  }

  function selectDocumentSegment(index) {
    index = Number(index);
    if (lookupInFlight || !Number.isInteger(index) || index < 0
        || index >= documentSegments.length || !pendingDocument) return null;
    var selectedResult = selectedDocumentResult(pendingDocument, index);
    if (!selectedResult) return null;
    selectedDocumentSegmentIndex = index;
    awaitingDocumentSelection = false;
    partialDocumentEntry = true;
    currentFlight = null;
    currentAction = null;
    journeyContext = null;
    lastLookupContext = null;
    populateManualDocumentEntry();
    renderManualDocumentEntry();
    showScreen('manual');
    persistJourney();
    announce(documentSegmentHasConflict(index) ? t('confirmSegment')
      : documentSegmentLookupReady(documentSegments[index], index) ? t('selectedSegment')
        : t('incompleteSegment'));
    return selectedResult;
  }

  function uploadDocument(file) {
    if (lookupInFlight) return;
    if (/\.(heic|heif)$/i.test(file.name || '') || /image\/hei[cf]/i.test(file.type || '')) {
      showToast(localeText('请将 HEIC 照片导出为 JPEG 或 PNG，或选择 PDF。',
        'Export the HEIC photo as JPEG or PNG, or choose a PDF.',
        'Exporta la foto HEIC como JPEG o PNG, o elige un PDF.'));
      fileInput.value = '';
      return;
    }
    var activeRequest = ++requestSequence;
    var formData = new FormData();
    formData.append('file', file);
    currentError = null;
    retryRequest = function () { uploadDocument(file); };
    setLookupBusy(true);
    runLoading(uploadLoadingMessages, 'documentRecognitionWaiting');
    var extraction = fetchJson('/api/documents/travel-document', {
      method: 'POST', body: formData,
      onResponse: function () { updateLoadingProgress(72, uploadLoadingMessages, 1); }
    });

    extraction.then(function (result) {
      if (activeRequest !== requestSequence) return;
      updateLoadingProgress(100, uploadLoadingMessages, 2);
      result = normalizeDocumentResult(result);
      var assessment = classifyDocumentResult(result);
      if (assessment.segments.length) {
        showDocumentSegmentSelection(result, assessment);
        return;
      }
      showUnusableDocument();
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      clearInterval(loadingTimer);
      clearTimeout(loadingTimeout);
      showError(error, retryRequest);
    }).finally(function () { setLookupBusy(false); });
  }

  function beginFlight(flight, documentResult) {
    restoredFromOffline = false;
    if (liveActivity && currentFlight && familyShareJourneyKey(currentFlight) !== familyShareJourneyKey(flight)) {
      endLiveJourney(familyShareJourneyKey(currentFlight));
    }
    var requestedDepartureDate = lastLookupContext && lastLookupContext.departureDate;
    var requestedSegmentIndex = lastLookupContext && lastLookupContext.documentSegmentIndex;
    currentFlight = flight;
    if (shareSession && !shareSessionMatchesFlight(shareSession, flight)) resetFamilyShareSession();
    lastLookupContext = {
      flightNumber: normalizeFlightNumber(flight.flightNumber),
      departureDate: requestedDepartureDate || flight.departureDate || null,
      departureAirport: flight.departureAirport || null,
      arrivalAirport: flight.arrivalAirport || null,
      documentSegmentIndex: Number.isInteger(requestedSegmentIndex) ? requestedSegmentIndex : null
    };
    pendingDocument = documentResult || null;
    currentAction = null;
    stepChangeAlert = null;
    journeyHistory = [];
    journeyEvents = [];
    journeyContext = null;
    conflictsConfirmed = false;
    currentError = null;
    checkInStatusAnswer = null;
    flightCorrectionOpen = false;
    renderFlight(flight);
    persistJourney();
  }

  function renderScenarioTestCards() {
    var scenario = demoScenariosEnabled ? demoScenarioMetadata(activeDemoScenarioId) : null;
    document.querySelectorAll('[data-scenario-test-card]').forEach(function (card) {
      card.hidden = !scenario;
      if (!scenario) return;
      card.querySelector('[data-scenario-test-name]').textContent = t(scenario.labelKey);
      card.querySelector('[data-scenario-test-before]').textContent = t(scenario.beforeKey);
      card.querySelector('[data-scenario-test-expected]').textContent = t(scenario.expectedKey);
    });
  }

  function scenarioJourneyContext(phase) {
    var context = createJourneyContext();
    context.checkedIn = true;
    context.hasCheckedBaggage = phase === 'arrival';
    context.bagDropped = true;
    context.securityCleared = true;
    context.atGate = true;
    context.boarded = phase === 'arrival';
    context.landed = phase === 'arrival';
    context.previousTerminal = currentFlight.terminal;
    context.previousGate = currentFlight.gate;
    return context;
  }

  function enterDemoScenarioPhase() {
    var scenario = demoScenariosEnabled ? demoScenarioMetadata(activeDemoScenarioId) : null;
    if (!scenario) return false;
    if (scenario.phase === 'boarding') {
      journeyContext = scenarioJourneyContext('boarding');
      showScreen('step', { silent: true });
      requestNextAction({ silent: true, source: 'scenario-entry' });
    } else if (scenario.phase === 'arrival') {
      journeyContext = scenarioJourneyContext('arrival');
      showScreen('step', { silent: true });
      renderLocalPostBoardAction();
    } else {
      showScreen('confirm', { silent: true });
      renderScenarioTestCards();
    }
    return true;
  }

  function triggerDemoScenarioRefresh() {
    var scenario = demoScenariosEnabled ? demoScenarioMetadata(activeDemoScenarioId) : null;
    if (!scenario || !currentFlight) return;
    if (scenario.phase === 'boarding') {
      requestNextAction({ refresh: true, silent: true, source: 'scenario-test' });
    } else if (scenario.phase === 'arrival') {
      refreshArrivalInfo({ silent: true, source: 'scenario-test' });
    } else {
      refreshFlight({ silent: true, source: 'scenario-test' });
    }
  }

  function restartDemoScenario() {
    var scenario = demoScenarioMetadata(activeDemoScenarioId);
    if (!demoScenariosEnabled || !scenario) return;
    startFlightLookup('BA117', new Date().toISOString().slice(0, 10), true, null, scenario.id);
  }

  function clearDemoRecordingCountdown() {
    if (demoRecordingCountdownTimer) window.clearInterval(demoRecordingCountdownTimer);
    demoRecordingCountdownTimer = null;
    demoRecordingCountdownSeconds = null;
  }

  function clearDemoRecordingBackendState() {
    if (!demoRecordingSessionId) return Promise.resolve();
    return apiFetch('/api/demo-recording/sessions/' + encodeURIComponent(demoRecordingSessionId)
      + '/scenario-state', { method: 'DELETE', cache: 'no-store' }).then(function (response) {
        if (!response.ok) throw new Error('无法清除当前录屏会话的场景状态。');
      });
  }

  function demoRecordingJourneyContext(target) {
    var context = createJourneyContext();
    context.checkedIn = target !== 'CHECK_IN';
    context.hasCheckedBaggage = target === 'BAG_DROP';
    context.bagDropped = target !== 'BAG_DROP' && target !== 'CHECK_IN';
    context.securityCleared = ['GO_TO_GATE', 'BOARD_NOW', 'COMPLETE'].indexOf(target) >= 0;
    context.atGate = ['BOARD_NOW', 'COMPLETE'].indexOf(target) >= 0;
    context.boarded = target === 'COMPLETE';
    context.landed = target === 'COMPLETE';
    context.baggageCollected = target === 'COMPLETE';
    context.exitedAirport = target === 'COMPLETE';
    context.previousTerminal = currentFlight.terminal;
    context.previousGate = currentFlight.gate;
    return context;
  }

  function calculateDemoRecordingAction(silent) {
    journeyContext.flightSnapshot = currentFlight;
    journeyContext.refreshRequested = false;
    return postFlightJson('/api/journeys/next-action', journeyContext).then(function (response) {
      currentFlight = response.flight;
      restoredFromOffline = false;
      journeyContext.flightSnapshot = currentFlight;
      currentAction = response.nextAction;
      currentError = null;
      renderFlight(currentFlight, { silent: Boolean(silent), skipChanges: true });
      renderJourneyResponse({ silent: Boolean(silent) });
      persistJourney();
      return response;
    });
  }

  function restoreDemoRecordingStep(target, scenario) {
    if (!demoRecordingSessionId) return Promise.reject(new Error('录屏会话未启用。'));
    clearDemoRecordingCountdown();
    stopAutoRefresh(true);
    clearJourney();
    resetFlightChangePresentation();
    return clearDemoRecordingBackendState().then(function () {
      var requestedScenario = scenario || (target === 'BOARD_NOW' ? 'BOARDING' : 'GATE_CHANGE');
      return postFlightJson('/api/journeys/resolve-flight', resolvePayload(
        'BA117', new Date().toISOString().slice(0, 10), true, null, requestedScenario, false));
    }).then(function (flight) {
      currentFlight = flight;
      lastLookupContext = {
        flightNumber: normalizeFlightNumber(flight.flightNumber), departureDate: flight.departureDate,
        departureAirport: flight.departureAirport || null, arrivalAirport: flight.arrivalAirport || null,
        documentSegmentIndex: null
      };
      if (target !== 'BOARD_NOW') return flight;
      return postFlightJson('/api/journeys/refresh-flight', flight).then(function (boardingFlight) {
        boardingFlight.latestChanges = [];
        return boardingFlight;
      });
    }).then(function (flight) {
      currentFlight = flight;
      journeyContext = demoRecordingJourneyContext(target);
      journeyHistory = [];
      journeyEvents = [];
      return calculateDemoRecordingAction(false);
    });
  }

  function triggerDemoRecordingGateChange() {
    if (!currentFlight || currentFlight.demoScenario !== 'GATE_CHANGE') {
      return restoreDemoRecordingStep('GO_TO_GATE', 'GATE_CHANGE').then(triggerDemoRecordingGateChange);
    }
    clearDemoRecordingCountdown();
    return refreshFlight({ silent: true, source: 'demo-recording' }).then(function (flight) {
      if (!flight) throw new Error('登机口变化刷新失败。');
      journeyContext.flightSnapshot = flight;
      journeyContext.refreshRequested = false;
      return postFlightJson('/api/journeys/next-action', journeyContext).then(function (response) {
        currentAction = response.nextAction;
        renderJourneyResponse({ silent: true });
        persistJourney();
        return response;
      });
    });
  }

  function scheduleDemoRecordingGateChange(delayMs) {
    clearDemoRecordingCountdown();
    var remaining = Math.max(1, Math.ceil((Number(delayMs) || 5000) / 1000));
    demoRecordingCountdownSeconds = remaining;
    publishDemoRecordingState();
    return new Promise(function (resolve, reject) {
      demoRecordingCountdownTimer = window.setInterval(function () {
        remaining -= 1;
        demoRecordingCountdownSeconds = Math.max(0, remaining);
        publishDemoRecordingState();
        if (remaining > 0) return;
        clearDemoRecordingCountdown();
        triggerDemoRecordingGateChange().then(resolve, reject);
      }, 1000);
    });
  }

  function publishDemoRecordingState() {
    if (!demoRecordingChannel || !demoRecordingSessionId) return;
    demoRecordingChannel.postMessage({
      type: 'STATE', demoSessionId: demoRecordingSessionId,
      state: {
        connected: true,
        screen: currentScreen,
        stepType: currentAction && currentAction.stepType || null,
        actionCode: currentAction && currentAction.actionCode || null,
        gate: currentFlight && currentFlight.gate || null,
        shareUrl: shareViewUrl(),
        countdownSeconds: demoRecordingCountdownSeconds
      }
    });
  }

  function demoRecordingResult(requestId, ok, message) {
    if (!demoRecordingChannel) return;
    demoRecordingChannel.postMessage({ type: 'RESULT', demoSessionId: demoRecordingSessionId,
      requestId: requestId, ok: ok, message: message });
    publishDemoRecordingState();
  }

  function handleDemoRecordingCommand(message) {
    var command = message.command;
    var task;
    if (command === 'RESTORE_STEP') task = restoreDemoRecordingStep(message.payload && message.payload.target);
    else if (command === 'PREPARE_GATE_CHANGE' || command === 'RESTORE_GATE_CHANGE') {
      task = restoreDemoRecordingStep('GO_TO_GATE', 'GATE_CHANGE');
    } else if (command === 'TRIGGER_GATE_CHANGE') task = triggerDemoRecordingGateChange();
    else if (command === 'SCHEDULE_GATE_CHANGE') {
      task = scheduleDemoRecordingGateChange(message.payload && message.payload.delayMs);
    } else if (command === 'RESET_SESSION' || command === 'CLEAR_SESSION') {
      clearDemoRecordingCountdown();
      task = Promise.all([clearDemoRecordingBackendState(), revokeDemoRecordingShare()]).then(function () {
        clearJourney();
        if (command === 'CLEAR_SESSION') localStorage.removeItem(DEMO_CONTROL_PREFIX + demoRecordingSessionId);
        showScreen('welcome');
      });
    } else task = Promise.reject(new Error('未知录屏命令。'));
    Promise.resolve(task).then(function () {
      demoRecordingResult(message.requestId, true, '已完成：' + command);
    }).catch(function (error) {
      demoRecordingResult(message.requestId, false, error && error.message || '命令执行失败。');
    });
  }

  function setupDemoRecordingChannel() {
    if (!demoRecordingSessionId || typeof BroadcastChannel !== 'function') return;
    demoRecordingChannel = new BroadcastChannel('onestep-flight-demo-' + demoRecordingSessionId);
    demoRecordingChannel.addEventListener('message', function (event) {
      var message = event.data || {};
      if (message.demoSessionId !== demoRecordingSessionId) return;
      if (message.type === 'REQUEST_STATE') publishDemoRecordingState();
      if (message.type === 'COMMAND') handleDemoRecordingCommand(message);
    });
    publishDemoRecordingState();
  }

  function qaJourneyDepartureDate() {
    return new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString().slice(0, 10);
  }

  function qaJourneyFlightIsUsable(flight) {
    if (!flight || normalizeFlightNumber(flight.flightNumber) !== 'BA117'
        || flight.demo !== true || flight.demoScenario !== 'NORMAL' || !flight.timeContext) return false;
    var effectiveDeparture = flight.timeContext.effectiveDeparture
      || flight.effectiveDeparture || flight.estimatedDeparture || flight.scheduledDeparture;
    var departureMs = Date.parse(effectiveDeparture || '');
    return Number.isFinite(departureMs) && departureMs > Date.now();
  }

  function resolveUploadedFlight(options) {
    var settings = options || {};
    if (!currentFlight || !pendingDocument) return;
    if (lookupInFlight && !settings.fromUpload) return;
    var activeRequest = settings.activeRequest || ++requestSequence;
    var previousEvents = journeyEvents.slice();
    currentError = null;
    retryRequest = resolveUploadedFlight;
    setLookupBusy(true);
    runLoading(loadingMessages, 'liveFlightLookupWaiting');
    var request = resolvePayload(currentFlight.flightNumber, currentFlight.departureDate, false, pendingDocument, null, false);
    return postFlightJson('/api/journeys/resolve-flight', request, function () {
      updateLoadingProgress(72, loadingMessages, 1);
    }).then(function (flight) {
      if (activeRequest !== requestSequence) return;
      updateLoadingProgress(100, loadingMessages, 2);
      currentFlight = flight;
      journeyEvents = previousEvents;
      conflictsConfirmed = false;
      recordLiveChanges(currentFlight.latestChanges);
      renderFlight(currentFlight);
      showScreen('confirm');
      presentInitialDelayNotice(currentFlight);
      persistJourney();
      announce(localeText('航班信息已准备好，请确认。', 'Flight details are ready. Please confirm.', 'Los datos del vuelo están listos. Confírmalos.'));
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      clearInterval(loadingTimer);
      clearTimeout(loadingTimeout);
      showError(error, retryRequest);
    }).finally(function () { setLookupBusy(false); });
  }

  function createJourneyContext() {
    var gateChange = latestFlightChange('GATE_CHANGED');
    var terminalChange = latestFlightChange('TERMINAL_CHANGED');
    return {
      flightNumber: currentFlight.flightNumber,
      departureDate: lastLookupContext && lastLookupContext.departureDate || currentFlight.departureDate,
      flightSnapshot: currentFlight,
      demo: Boolean(currentFlight.demo),
      demoScenario: currentFlight.demoScenario || null,
      documentType: currentFlight.documentType || (pendingDocument && pendingDocument.documentType) || 'MANUAL_ENTRY',
      boardingPassDetected: false,
      checkedIn: false,
      hasCheckedBaggage: null,
      bagDropped: false,
      securityCleared: false,
      atGate: false,
      boarded: false,
      landed: false,
      baggageCollected: false,
      exitedAirport: false,
      needsAssistance: false,
      previousTerminal: terminalChange ? terminalChange.oldValue : null,
      previousGate: gateChange ? gateChange.oldValue : null,
      conflictsConfirmed: conflictsConfirmed,
      refreshRequested: false
    };
  }

  function buildCheckInQuestion() {
    return {
      stepType: 'CHECK_IN_QUESTION',
      actionCode: null,
      titleZh: translations.zh.checkInQuestion,
      titleEn: translations.en.checkInQuestion,
      titleEs: translations.es.checkInQuestion,
      descriptionZh: translations.zh.checkInQuestionLead,
      descriptionEn: translations.en.checkInQuestionLead,
      descriptionEs: translations.es.checkInQuestionLead,
      urgency: 'NORMAL',
      reason: 'TRAVELER_CHECK_IN_CONFIRMATION',
      lastUpdated: calibratedNowIso()
    };
  }

  function showCheckInQuestion() {
    dismissFlightChangeNotice();
    currentAction = buildCheckInQuestion();
    renderJourneyResponse();
    persistJourney();
  }

  function buildQaAction() {
    var code = !journeyContext.checkedIn ? 'CHECK_IN'
      : journeyContext.hasCheckedBaggage == null ? 'BAGGAGE_QUESTION'
        : journeyContext.hasCheckedBaggage && !journeyContext.bagDropped ? 'BAG_DROP'
          : !journeyContext.securityCleared ? 'SECURITY'
            : !journeyContext.atGate ? 'GO_TO_GATE'
              : !journeyContext.boarded ? 'BOARD_NOW'
                : !journeyContext.landed ? 'IN_FLIGHT'
                  : journeyContext.hasCheckedBaggage && !journeyContext.baggageCollected ? 'BAGGAGE_CLAIM'
                    : !journeyContext.exitedAirport ? 'EXIT_AIRPORT' : null;
    if (code === 'BAGGAGE_QUESTION') return {
      stepType: 'BAGGAGE_QUESTION', actionCode: null,
      titleZh: '你有需要托运的行李吗？',
      descriptionZh: '随身携带的小包不算托运行李。请选择最符合你情况的一项。',
      titleEn: 'Do you have baggage to check?',
      descriptionEn: 'A small carry-on bag does not count as checked baggage. Choose the option that fits you.',
      titleEs: '¿Tienes equipaje para facturar?',
      descriptionEs: 'El equipaje de mano no cuenta como equipaje facturado. Elige la opción que corresponda.',
      urgency: 'NORMAL', reason: 'BAGGAGE_STATUS_REQUIRED', lastUpdated: calibratedNowIso()
    };
    if (!code) return {
      stepType: 'COMPLETE', actionCode: null,
      titleZh: '本地流程验收完成', titleEn: 'Local journey QA complete', titleEs: 'Prueba local del viaje completada',
      descriptionZh: '所有阶段都已在本机完成。', descriptionEn: 'All journey stages were completed locally.', descriptionEs: 'Todas las etapas se completaron localmente.',
      urgency: 'NORMAL', lastUpdated: calibratedNowIso()
    };
    var zhTitles = { CHECK_IN: '现在办理值机', BAG_DROP: '现在托运行李', SECURITY: '现在前往安检', GO_TO_GATE: '现在前往登机口', BOARD_NOW: '现在登机', IN_FLIGHT: '已经登机', BAGGAGE_CLAIM: '现在领取托运行李', EXIT_AIRPORT: '现在前往到达出口' };
    var enTitles = { CHECK_IN: 'Check in now', BAG_DROP: 'Drop off your baggage now', SECURITY: 'Go to security now', GO_TO_GATE: 'Go to the gate now', BOARD_NOW: 'Board now', IN_FLIGHT: 'You are on board', BAGGAGE_CLAIM: 'Collect your baggage now', EXIT_AIRPORT: 'Go to the arrivals exit now' };
    return {
      stepType: 'ACTION', actionCode: code, titleZh: zhTitles[code], titleEn: enTitles[code],
      titleEs: spanishActionCopy[code].title, descriptionZh: '本地流程验收：使用动态演示航班数据。',
      descriptionEn: 'Local journey QA using dynamic demo flight data.',
      descriptionEs: 'Prueba local con datos dinámicos de un vuelo de demostración.',
      urgency: 'NORMAL', reason: 'LOCAL_QA_JOURNEY', lastUpdated: calibratedNowIso()
    };
  }

  function autoRefreshDelayMs(flight, nowMs) {
    var departure = flight && flight.timeContext && flight.timeContext.effectiveDeparture
      || flight && flight.effectiveDeparture;
    var departureMs = Date.parse(departure || '');
    if (!Number.isFinite(departureMs)) return AUTO_REFRESH_FAR_MS;
    var untilDeparture = departureMs - nowMs;
    if (untilDeparture <= AUTO_REFRESH_BOUNDARY_MS) return AUTO_REFRESH_NEAR_MS;
    return Math.min(AUTO_REFRESH_FAR_MS, untilDeparture - AUTO_REFRESH_BOUNDARY_MS);
  }

  function autoRefreshEligible(state) {
    state = state || {};
    if (qaJourneyEnabled || typeof demoRecordingSessionId !== 'undefined' && demoRecordingSessionId) return false;
    var flight = state.currentFlight || state.flight;
    if (!flight || (state.currentScreen !== 'confirm' && state.currentScreen !== 'step')) return false;
    if (state.awaitingDocumentSelection || state.partialDocumentEntry || state.flightCorrectionOpen) return false;
    if (state.online === false || state.visible === false || state.requestInFlight) return false;
    if (state.journeyContext && state.journeyContext.boarded === true || state.boarded === true) return false;
    return Boolean(flight.live || flight.demoScenario || state.documentFallback || flight.documentFallback);
  }

  function runtimeAutoRefreshState(includeTransientState) {
    return {
      currentFlight: currentFlight,
      currentScreen: currentScreen,
      awaitingDocumentSelection: awaitingDocumentSelection,
      partialDocumentEntry: partialDocumentEntry,
      flightCorrectionOpen: flightCorrectionOpen,
      journeyContext: journeyContext,
      documentFallback: isDocumentFallbackFlight(currentFlight),
      online: includeTransientState ? navigator.onLine : true,
      visible: includeTransientState ? !document.hidden && (!platform || platform.isActive()) : true,
      requestInFlight: includeTransientState ? Boolean(flightRequestController || lookupInFlight) : false
    };
  }

  function stopAutoRefresh(clearDeadline) {
    window.clearTimeout(autoRefreshTimer);
    autoRefreshTimer = null;
    if (clearDeadline !== false) autoRefreshDueAt = null;
  }

  function scheduleAutoRefresh(resetDeadline) {
    window.clearTimeout(autoRefreshTimer);
    autoRefreshTimer = null;
    if (!autoRefreshEligible(runtimeAutoRefreshState(false))) {
      autoRefreshDueAt = null;
      return;
    }
    var now = calibratedNowMs();
    if (resetDeadline || !Number.isFinite(autoRefreshDueAt)) {
      autoRefreshDueAt = now + autoRefreshDelayMs(currentFlight, now);
    }
    if (!autoRefreshEligible(runtimeAutoRefreshState(true))) return;
    autoRefreshTimer = window.setTimeout(runDueAutoRefresh,
      Math.max(0, autoRefreshDueAt - now));
  }

  function runDueAutoRefresh() {
    autoRefreshTimer = null;
    if (!autoRefreshEligible(runtimeAutoRefreshState(false))) {
      stopAutoRefresh(true);
      return;
    }
    if (!autoRefreshEligible(runtimeAutoRefreshState(true))) return;
    if (Number.isFinite(autoRefreshDueAt) && autoRefreshDueAt > calibratedNowMs()) {
      scheduleAutoRefresh(false);
      return;
    }
    autoRefreshDueAt = null;
    if (currentScreen === 'step' && journeyContext && currentAction) {
      requestNextAction({ refresh: true, silent: true, source: 'auto' });
    } else if (currentScreen === 'confirm') {
      refreshFlight({ silent: true, source: 'auto' });
    }
  }

  function resumeAutoRefresh() {
    if (!autoRefreshEligible(runtimeAutoRefreshState(false))) {
      stopAutoRefresh(true);
      return;
    }
    if (!Number.isFinite(autoRefreshDueAt)) {
      scheduleAutoRefresh(true);
      return;
    }
    if (autoRefreshDueAt <= calibratedNowMs()) runDueAutoRefresh();
    else scheduleAutoRefresh(false);
  }

  function syncAutoRefreshForScreen() {
    if (currentScreen !== 'confirm' && currentScreen !== 'step') {
      stopAutoRefresh(true);
      return;
    }
    scheduleAutoRefresh(false);
  }

  function actionIdentity(action) {
    if (!action) return '';
    return String(action.stepType || '') + ':' + String(action.actionCode || '');
  }

  function dismissFlightChangeNotice() {
    if (flightChangeDismissTimer) {
      window.clearTimeout(flightChangeDismissTimer);
      flightChangeDismissTimer = null;
    }
    stepChangeAlert = null;
    displayedFlightChanges = [];
    displayedFlightChangeFlight = null;
    pendingFlightChangeSpeechBatch = null;
    lastFlightChangeSignature = '';
    renderFlightChanges();
  }

  function resetFlightChangePresentation() {
    stopSpeech(false);
    dismissFlightChangeNotice();
    presentedFlightChangeSignatures = Object.create(null);
  }

  function isUnseenFlightChangeSignature(signature, presentedSignatures) {
    return Boolean(signature && !(presentedSignatures || {})[signature]);
  }

  function updateRefreshChangeState(previousAction) {
    var changes = presentableFlightChanges(currentFlight);
    var oldIdentity = actionIdentity(previousAction);
    var newIdentity = actionIdentity(currentAction);
    var nextStepChange = changes.length && oldIdentity && newIdentity && oldIdentity !== newIdentity
      ? { previousIdentity: oldIdentity, action: currentAction } : null;
    var newBatch = unseenFlightChangeBatch(changes, nextStepChange,
      presentedFlightChangeSignatures);
    if (!newBatch) {
      pendingFlightChangeSpeechBatch = null;
      renderFlightChanges();
      return false;
    }
    displayedFlightChanges = retainFlightChangeBatch(displayedFlightChanges, newBatch.changes);
    displayedFlightChangeFlight = Object.assign({}, currentFlight || {});
    if (newBatch.nextStepChange) stepChangeAlert = newBatch.nextStepChange;
    pendingFlightChangeSpeechBatch = newBatch;
    lastFlightChangeSignature = flightChangesSignature(newBatch.changes,
      newBatch.nextStepChange);
    newBatch.signatures.forEach(function (signature) {
      presentedFlightChangeSignatures[signature] = true;
    });
    if (newBatch.nextStepChange) {
      recordJourneyEvent('STEP_UPDATED', {
        reversible: false,
        params: { actionCode: currentAction && currentAction.actionCode || null,
          reason: currentAction && currentAction.reason || null,
          titleZh: actionTextForLocale(currentAction, 'title', 'zh'),
          titleEn: actionTextForLocale(currentAction, 'title', 'en'),
          titleEs: actionTextForLocale(currentAction, 'title', 'es') },
        stableId: 'STEP:' + oldIdentity + ':' + newIdentity + ':'
          + String(currentAction && currentAction.lastUpdated || calibratedNowIso())
      });
    }
    return true;
  }

  var PRESENTABLE_FLIGHT_CHANGE_TYPES = [
    'TERMINAL_CHANGED', 'GATE_CHANGED', 'CHECK_IN_COUNTER_CHANGED', 'BAG_DROP_DESK_CHANGED',
    'BOARDING_TIME_CHANGED', 'DEPARTURE_TIME_CHANGED', 'DEPARTURE_DELAY_CHANGED', 'DELAY_CHANGED',
    'STATUS_CHANGED', 'FLIGHT_CANCELLED', 'ARRIVAL_TIME_CHANGED', 'ARRIVAL_DELAY_CHANGED',
    'BAGGAGE_BELT_CHANGED', 'DATA_FRESHNESS_CHANGED'
  ];

  function meaningfulFlightChangeValue(value) {
    if (value == null) return '';
    var normalized = String(value).trim();
    return normalized === '—' ? '' : normalized;
  }

  function isMeaningfulFlightChange(change) {
    if (!change || PRESENTABLE_FLIGHT_CHANGE_TYPES.indexOf(change.type) < 0) return false;
    var oldValue = meaningfulFlightChangeValue(change.oldValue);
    var newValue = meaningfulFlightChangeValue(change.newValue);
    return Boolean(oldValue && newValue && oldValue !== newValue);
  }

  function presentableFlightChanges(flight) {
    var seen = {};
    return flight && Array.isArray(flight.latestChanges)
      ? flight.latestChanges.filter(function (change) {
        if (!isMeaningfulFlightChange(change)) return false;
        var signature = [change.type, change.oldValue == null ? '' : change.oldValue,
          change.newValue == null ? '' : change.newValue].join(':');
        if (seen[signature]) return false;
        seen[signature] = true;
        return true;
      }) : [];
  }

  function retainFlightChangeBatch(activeBatch, incomingChanges) {
    var retained = Array.isArray(activeBatch) ? activeBatch.slice() : [];
    var seen = Object.create(null);
    retained.forEach(function (change) {
      seen[[change.type, change.oldValue == null ? '' : change.oldValue,
        change.newValue == null ? '' : change.newValue].join(':')] = true;
    });
    (Array.isArray(incomingChanges) ? incomingChanges : []).forEach(function (change) {
      var signature = [change.type, change.oldValue == null ? '' : change.oldValue,
        change.newValue == null ? '' : change.newValue].join(':');
      if (!seen[signature]) {
        retained.push(change);
        seen[signature] = true;
      }
    });
    return retained;
  }

  function flightChangesSignature(changes, nextStepChange) {
    var signatures = (Array.isArray(changes) ? changes : []).map(flightChangeItemSignature);
    var stepSignature = nextStepChangeItemSignature(nextStepChange);
    if (stepSignature) signatures.push(stepSignature);
    return signatures.join('|');
  }

  function flightChangeItemSignature(change) {
    return 'CHANGE:' + [change && change.type || '', change && change.oldValue != null ? change.oldValue : '',
      change && change.newValue != null ? change.newValue : ''].join(':');
  }

  function nextStepChangeItemSignature(nextStepChange) {
    if (!nextStepChange || !nextStepChange.action) return '';
    var action = nextStepChange.action;
    var nextIdentity = String(action.stepType || '') + ':' + String(action.actionCode || '');
    return 'STEP:' + String(nextStepChange.previousIdentity || '') + '→' + nextIdentity;
  }

  function unseenFlightChangeBatch(changes, nextStepChange, presentedSignatures) {
    var signatures = [];
    var newChanges = (Array.isArray(changes) ? changes : []).filter(function (change) {
      var signature = flightChangeItemSignature(change);
      if (!isUnseenFlightChangeSignature(signature, presentedSignatures)) return false;
      signatures.push(signature);
      return true;
    });
    var newStepChange = null;
    var stepSignature = nextStepChangeItemSignature(nextStepChange);
    if (isUnseenFlightChangeSignature(stepSignature, presentedSignatures)) {
      signatures.push(stepSignature);
      newStepChange = nextStepChange;
    }
    return signatures.length ? {
      changes: newChanges,
      nextStepChange: newStepChange,
      signatures: signatures
    } : null;
  }

  function initialDelayAnnouncement(flight) {
    var minutes = Number(flight && flight.delayMinutes);
    if (!Number.isFinite(minutes) || minutes <= 0) return null;
    return { type: 'INITIAL_DELAY_ANNOUNCED', oldValue: 'BASELINE', newValue: String(minutes),
      critical: minutes >= 45 };
  }

  function presentInitialDelayNotice(flight) {
    var change = initialDelayAnnouncement(flight);
    if (!change) return false;
    var signature = 'INITIAL_DELAY:' + String(flight.flightNumber || '') + ':'
      + String(flight.departureDate || '') + ':' + change.newValue;
    if (!isUnseenFlightChangeSignature(signature, presentedFlightChangeSignatures)) return false;
    displayedFlightChanges = [change];
    displayedFlightChangeFlight = Object.assign({}, flight);
    stepChangeAlert = null;
    lastFlightChangeSignature = signature;
    presentedFlightChangeSignatures[signature] = true;
    renderFlightChanges();
    presentNewFlightChangeBatch();
    return true;
  }

  function refreshFlight(options) {
    var settings = options || {};
    var silent = settings.silent === true;
    if (!currentFlight || flightRequestController) return Promise.resolve(null);
    if (!navigator.onLine) {
      if (!silent) {
        setRefreshStatus('OFFLINE_SNAPSHOT');
        renderConnectivityStatus();
      }
      return Promise.resolve(null);
    }
    var documentFallback = isDocumentFallbackFlight(currentFlight);
    if (documentFallback && !pendingDocument) return Promise.resolve(null);
    if (!documentFallback && (!currentFlight.timeContext
        || (!currentFlight.live && !currentFlight.demoScenario))) return Promise.resolve(null);
    stopAutoRefresh(false);
    var activeRequest = ++requestSequence;
    var previousAction = currentAction;
    var request;
    if (!silent) {
      retryRequest = function () { refreshFlight({ source: 'manual' }); };
      document.querySelectorAll('[data-action="refresh-flight"]').forEach(function (button) { button.disabled = true; });
      setRefreshStatus('CHECKING');
    }
    request = documentFallback
      ? postFlightJson('/api/journeys/resolve-flight', resolvePayload(
        currentFlight.flightNumber, currentFlight.departureDate, false, pendingDocument, null, true))
      : postFlightJson('/api/journeys/refresh-flight', currentFlight);
    return request.then(function (flight) {
      if (activeRequest !== requestSequence) return null;
      currentFlight = flight;
      restoredFromOffline = false;
      if (journeyContext) journeyContext.flightSnapshot = flight;
      recordLiveChanges(flight.latestChanges);
      var hasNewChangeBatch = updateRefreshChangeState(previousAction);
      renderFlight(flight, { silent: silent || hasNewChangeBatch });
      persistJourney();
      if (hasNewChangeBatch) presentNewFlightChangeBatch();
      if (!silent) {
        if (isDocumentFallbackFlight(flight)) {
          setRefreshStatus('DOCUMENT_FALLBACK_RETAINED');
          announce(t('liveStatusUnavailable'));
        } else if (hasNewChangeBatch) {
          setRefreshStatus(null);
        } else {
          setRefreshStatus('REFRESH_SUCCESS_LATEST_RETAINED');
          announceLatestChangesOr(localeText('实时航班信息已刷新。', 'Live flight information refreshed.', 'Información del vuelo actualizada.'));
        }
      }
      return flight;
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return null;
      if (!silent) {
        setRefreshStatus(documentFallback ? 'DOCUMENT_FALLBACK_RETAINED' : 'REFRESH_FAILED_LATEST_RETAINED');
        announce(documentFallback ? t('liveStatusUnavailable') : localeText(
          '刷新失败，原有航班信息仍然保留。',
          'Refresh failed. The previous flight information is still available.',
          'No se pudo actualizar. La información anterior sigue disponible.'));
      }
      return null;
    }).finally(function () {
      if (!silent) updateRefreshControls();
      scheduleAutoRefresh(true);
    });
  }

  function beginNewFlightInformation() {
    requestSequence += 1;
    clearInterval(loadingTimer);
    clearTimeout(loadingTimeout);
    clearJourney();
    resetManualDocumentEntry();
    fileInput.value = '';
    showScreen('manual');
    var flightField = document.getElementById('flight-input');
    var dateField = document.getElementById('date-input');
    flightField.value = '';
    setDateInputValue(dateField, '');
    flightField.focus({ preventScroll: true });
    announce(localeText('请输入新的航班号和出发日期。', 'Enter the new flight number and departure date.', 'Introduce el nuevo número de vuelo y la fecha de salida.'));
  }

  function refreshArrivalInfo(options) {
    var settings = options || {};
    var silent = settings.silent === true;
    if (!currentFlight || !journeyContext || !journeyContext.boarded || flightRequestController) return Promise.resolve(null);
    if (!navigator.onLine) {
      if (!silent) {
        setRefreshStatus('OFFLINE_SNAPSHOT');
        renderConnectivityStatus();
      }
      return Promise.resolve(null);
    }
    if (!currentFlight.live && !currentFlight.demoScenario) return Promise.resolve(null);
    var previousAction = currentAction;
    if (!silent) {
      document.querySelectorAll('[data-action="refresh-arrival"]').forEach(function (button) { button.disabled = true; });
      setRefreshStatus('CHECKING_ARRIVAL');
    }
    return postFlightJson('/api/journeys/refresh-flight', currentFlight).then(function (flight) {
      currentFlight = flight;
      restoredFromOffline = false;
      journeyContext.flightSnapshot = flight;
      recordLiveChanges(flight.latestChanges);
      currentAction = buildPostBoardAction();
      var hasNewChangeBatch = updateRefreshChangeState(previousAction);
      renderFlight(flight, { silent: silent || hasNewChangeBatch, skipChanges: true });
      renderJourneyResponse({ silent: silent || hasNewChangeBatch });
      persistJourney();
      if (hasNewChangeBatch) presentNewFlightChangeBatch();
      if (!silent) {
        if (hasNewChangeBatch) setRefreshStatus(null);
        else {
          setRefreshStatus('ARRIVAL_REFRESH_SUCCESS');
          announce(localeText('到达信息已刷新。', 'Arrival information refreshed.', 'Información de llegada actualizada.'));
        }
      }
      return flight;
    }).catch(function () {
      if (!silent) {
        setRefreshStatus('ARRIVAL_REFRESH_FAILED');
        announce(localeText('到达信息刷新失败，已保存的行程仍然可用。', 'Arrival refresh failed. Your saved journey is still available.', 'No se pudo actualizar la llegada. El viaje guardado sigue disponible.'));
      }
      return null;
    }).finally(function () {
      if (!silent) updateRefreshControls();
    });
  }

  function maybeRefreshArrivalOnEntry() {
    if (!currentAction || currentAction.actionCode !== 'BAGGAGE_CLAIM' || !journeyContext
        || !journeyContext.landed || !navigator.onLine || (!currentFlight.live && !currentFlight.demoScenario)) return;
    if (demoScenariosEnabled && activeDemoScenarioId) return;
    var key = [currentFlight.flightNumber, currentFlight.departureDate, journeyContext.landed].join('|');
    if (arrivalRefreshFlightKey === key) return;
    arrivalRefreshFlightKey = key;
    window.setTimeout(function () {
      refreshArrivalInfo({ silent: true, source: 'arrival-entry' });
    }, 0);
  }

  function buildPostBoardAction() {
    var now = calibratedNowIso();
    if (journeyContext.exitedAirport) {
      return {
        stepType: 'COMPLETE', actionCode: null,
        titleZh: '你已经完成本次到达指引',
        descriptionZh: '你已经到达机场出口。祝你接下来的行程顺利。',
        titleEn: 'Your arrival guidance is complete',
        descriptionEn: 'You have reached the airport exit. Have a safe onward journey.',
        titleEs: 'Has completado las indicaciones de llegada',
        descriptionEs: 'Has llegado a la salida del aeropuerto. Buen viaje.',
        urgency: 'NORMAL', lastUpdated: now
      };
    }
    if (journeyContext.boarded && !journeyContext.landed) {
      return {
        stepType: 'ACTION', actionCode: 'IN_FLIGHT',
        titleZh: '已经登机',
        descriptionZh: journeyContext.hasCheckedBaggage
          ? '抵达后，先查看行李提取屏幕。'
          : '抵达后，按照“到达 / 出口”指示前往出口。',
        titleEn: 'You are on board',
        descriptionEn: journeyContext.hasCheckedBaggage
          ? 'After arrival, check the baggage reclaim screens first.'
          : 'After arrival, follow the Arrivals / Exit signs.',
        titleEs: 'Ya estás a bordo',
        descriptionEs: journeyContext.hasCheckedBaggage
          ? 'Al llegar, consulta primero las pantallas de recogida de equipaje.'
          : 'Al llegar, sigue las señales de Llegadas / Salida.',
        urgency: 'NORMAL', reason: 'ONBOARD_SAVED_JOURNEY', lastUpdated: now
      };
    }
    if (journeyContext.landed && journeyContext.hasCheckedBaggage && !journeyContext.baggageCollected) {
      return {
        stepType: 'ACTION', actionCode: 'BAGGAGE_CLAIM',
        titleZh: '现在领取托运行李',
        descriptionZh: currentFlight.arrivalBaggageBelt
          ? '请先核对屏幕上的航班号，再在对应转盘等待行李。'
          : '请查看到达屏幕上 ' + formatFlightNumber(currentFlight.flightNumber) + ' 对应的行李转盘。',
        titleEn: 'Collect your checked baggage now',
        descriptionEn: currentFlight.arrivalBaggageBelt
          ? 'Check the flight number on the screen before waiting for your baggage.'
          : 'Find the baggage belt for ' + formatFlightNumber(currentFlight.flightNumber) + ' on the arrivals screen.',
        titleEs: 'Recoge ahora el equipaje facturado',
        descriptionEs: currentFlight.arrivalBaggageBelt
          ? 'Comprueba el número de vuelo en la pantalla antes de esperar junto a la cinta.'
          : 'Busca la cinta del vuelo ' + formatFlightNumber(currentFlight.flightNumber) + ' en la pantalla de llegadas.',
        urgency: 'NORMAL', reason: 'CHECKED_BAGGAGE_AFTER_LANDING', lastUpdated: now
      };
    }
    return {
      stepType: 'ACTION', actionCode: 'EXIT_AIRPORT',
      titleZh: '现在前往到达出口',
      descriptionZh: '请跟随机场内的“到达 / 出口”指示；不确定时把大字卡给工作人员看。',
      titleEn: 'Go to the arrivals exit now',
      descriptionEn: 'Follow the Arrivals / Exit signs. Show the large card to staff if you are unsure.',
      titleEs: 'Ve ahora a la salida de llegadas',
      descriptionEs: 'Sigue las señales de Llegadas / Salida. Muestra la tarjeta grande al personal si lo necesitas.',
      urgency: 'NORMAL', reason: 'ARRIVAL_EXIT_NOT_REACHED', lastUpdated: now
    };
  }

  function renderLocalPostBoardAction() {
    currentAction = buildPostBoardAction();
    currentError = null;
    renderFlight(currentFlight);
    renderJourneyResponse();
    persistJourney();
  }

  function requestNextAction(options) {
    var settings = options || {};
    var refreshRequested = settings.refresh === true;
    var silent = settings.silent === true;
    if (!journeyContext || !currentFlight) return Promise.resolve(null);
    if (!refreshRequested) dismissFlightChangeNotice();
    if (qaJourneyEnabled) {
      journeyContext.refreshRequested = false;
      currentAction = buildQaAction();
      renderFlight(currentFlight);
      renderJourneyResponse();
      persistJourney();
      return Promise.resolve(currentAction);
    }
    if (journeyContext.boarded && !refreshRequested) {
      renderLocalPostBoardAction();
      return Promise.resolve(currentAction);
    }
    if (!navigator.onLine) {
      if (!silent) {
        setRefreshStatus('OFFLINE_SNAPSHOT');
        renderConnectivityStatus();
        announce(localeText('当前离线，已保存的当前步骤仍然可用。', 'You are offline. The saved current step is still available.', 'No hay conexión. El paso guardado sigue disponible.'));
      }
      return Promise.resolve(null);
    }
    if (flightRequestController) return Promise.resolve(null);
    stopAutoRefresh(false);
    var activeRequest = ++requestSequence;
    var previousAction = currentAction;
    journeyContext.refreshRequested = Boolean(refreshRequested);
    journeyContext.flightSnapshot = currentFlight;
    journeyContext.conflictsConfirmed = conflictsConfirmed;
    if (!silent) {
      document.querySelector('.screen-step').setAttribute('aria-busy', 'true');
      document.querySelectorAll('.screen-step [data-action="complete-action"], .screen-step [data-action="baggage"], .screen-step [data-action="checkin-status"]').forEach(function (button) { button.disabled = true; });
      retryRequest = function () { requestNextAction({ refresh: refreshRequested, source: 'manual' }); };
      if (refreshRequested) setRefreshStatus('CHECKING');
    }

    return postFlightJson('/api/journeys/next-action', journeyContext).then(function (response) {
      if (activeRequest !== requestSequence) return;
      journeyContext.refreshRequested = false;
      currentFlight = response.flight;
      restoredFromOffline = false;
      journeyContext.flightSnapshot = currentFlight;
      currentAction = response.nextAction;
      recordLiveChanges(currentFlight.latestChanges);
      var hasNewChangeBatch = false;
      if (refreshRequested) hasNewChangeBatch = updateRefreshChangeState(previousAction);
      currentError = null;
      renderFlight(currentFlight, { silent: silent || hasNewChangeBatch, skipChanges: true });
      renderJourneyResponse({ silent: silent || hasNewChangeBatch });
      if (hasNewChangeBatch) presentNewFlightChangeBatch();
      if (refreshRequested && !silent) {
        if (hasNewChangeBatch) setRefreshStatus(null);
        else setRefreshStatus('REFRESH_SUCCESS_LATEST_RETAINED');
      }
      persistJourney();
      return response;
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      journeyContext.refreshRequested = false;
      if (silent) return null;
      if (refreshRequested && currentAction) {
        setRefreshStatus('REFRESH_FAILED_STEP_RETAINED');
        announce(localeText('刷新失败，当前步骤没有改变。', 'Refresh failed. Your current step is unchanged.', 'No se pudo actualizar. El paso actual no cambió.'));
      } else {
        showError(error, retryRequest);
      }
      return null;
    }).finally(function () {
      if (!silent) {
        document.querySelector('.screen-step').removeAttribute('aria-busy');
        document.querySelectorAll('.screen-step [data-action="baggage"], .screen-step [data-action="checkin-status"]').forEach(function (button) { button.disabled = false; });
        updateRefreshControls();
      }
      scheduleAutoRefresh(true);
    });
  }

  var spanishActionCopy = {
    BAGGAGE_QUESTION: {
      title: '¿Tienes equipaje para facturar?',
      description: 'Elige “Sí” si debes entregar el equipaje a la aerolínea. El equipaje de mano no cuenta.'
    },
    CHECK_IN: { title: 'Haz la facturación ahora', description: 'Ve a la zona de facturación de la aerolínea. Si no aparece la ubicación, pregunta al personal.' },
    BAG_DROP: { title: 'Entrega ahora el equipaje', description: 'Ve al mostrador de entrega de equipaje y conserva tu resguardo.' },
    SECURITY: { title: 'Pasa ahora el control de seguridad', description: 'Sigue las señales del control de seguridad y ten preparado tu documento de viaje.' },
    GO_TO_GATE: { title: 'Ve ahora a la puerta de embarque', description: 'Comprueba el número de vuelo en la pantalla antes de esperar allí.' },
    WAIT: { title: 'Espera y revisa las pantallas', description: 'No cambies de lugar hasta que aparezca nueva información.' },
    BOARD_NOW: { title: 'Embarca ahora', description: 'Acércate a la puerta y sigue las instrucciones del personal.' },
    ASK_FOR_HELP: { title: 'Pide ayuda ahora', description: 'Muestra la tarjeta de texto grande a un miembro del personal.' },
    IN_FLIGHT: { title: 'Ya estás a bordo', description: 'Al llegar, sigue las indicaciones de la tripulación y las pantallas del aeropuerto.' },
    BAGGAGE_CLAIM: { title: 'Recoge ahora el equipaje facturado', description: 'Comprueba el número de vuelo en la pantalla de llegadas antes de esperar junto a la cinta.' },
    EXIT_AIRPORT: { title: 'Ve ahora a la salida de llegadas', description: 'Sigue las señales de Llegadas / Salida. Pide ayuda si no estás seguro.' },
    COMPLETE: { title: 'Has completado las indicaciones', description: 'Buen viaje.' }
  };

  var standardActionCopy = {
    CHECK_IN: {
      title: ['办理值机', 'Check in', 'Facturar'],
      description: ['准备好护照或旅行证件。', 'Have your passport or travel document ready.', 'Ten preparado el pasaporte o documento de viaje.']
    },
    BAG_DROP: {
      title: ['托运行李', 'Drop off baggage', 'Entregar equipaje'],
      description: ['保留行李凭证。', 'Keep your baggage receipt.', 'Conserva el resguardo del equipaje.']
    },
    SECURITY: {
      title: ['前往安检', 'Go to security', 'Ir al control'],
      description: ['准备好登机牌和旅行证件。', 'Have your boarding pass and travel document ready.', 'Ten preparados la tarjeta de embarque y el documento de viaje.']
    },
    GO_TO_GATE: {
      title: ['前往登机口', 'Go to the gate', 'Ir a la puerta'],
      description: ['请在屏幕上核对航班号。', 'Check the flight number on the screen.', 'Comprueba el número de vuelo en la pantalla.']
    },
    WAIT: {
      title: ['等候', 'Wait', 'Esperar'],
      description: ['留意屏幕和广播。', 'Watch the screens and listen for announcements.', 'Mira las pantallas y escucha los avisos.']
    },
    BOARD_NOW: {
      title: ['现在登机', 'Board now', 'Embarcar ahora'],
      description: ['准备好登机牌和旅行证件。', 'Have your boarding pass and travel document ready.', 'Ten preparados la tarjeta de embarque y el documento de viaje.']
    },
    ASK_FOR_HELP: {
      title: ['请工作人员帮忙', 'Ask staff for help', 'Pedir ayuda'],
      description: ['把大字卡给工作人员看。', 'Show the large card to a member of staff.', 'Muestra la tarjeta grande al personal.']
    },
    IN_FLIGHT: {
      title: ['已登机', 'On board', 'A bordo'],
      description: ['留意机上广播和机组人员指引。', 'Follow cabin crew announcements and instructions.', 'Sigue los avisos y las indicaciones de la tripulación.']
    },
    BAGGAGE_CLAIM: {
      title: ['领取托运行李', 'Collect baggage', 'Recoger equipaje'],
      description: ['请在到达屏幕上核对航班号。', 'Check the flight number on the arrivals screen.', 'Comprueba el número de vuelo en la pantalla de llegadas.']
    },
    EXIT_AIRPORT: {
      title: ['前往出口', 'Go to the exit', 'Ir a la salida'],
      description: ['带好随身物品和行李。', 'Take all your belongings and baggage.', 'Lleva contigo tus objetos personales y el equipaje.']
    }
  };

  var warningActionReasons = [
    'FLIGHT_CANCELLED', 'FLIGHT_DATE_TIME_MISMATCH', 'FLIGHT_ALREADY_DEPARTED',
    'DEPARTURE_TIME_PASSED', 'STALE_DATA_NEAR_DEPARTURE',
    'SIGNIFICANT_DELAY_AFTER_REQUIRED_STEPS', 'GATE_MISSING_NEAR_DEPARTURE', 'GATE_NOT_PUBLISHED',
    'TERMINAL_CHANGED', 'GATE_CHANGED'
  ];

  function directActionText(action, part, locale) {
    if (locale === 'zh') return action[part + 'Zh'] || '';
    if (locale === 'es') return action[part + 'Es'] || action[part + 'En'] || '';
    return action[part + 'En'] || '';
  }

  function actionTextForLocale(action, part, locale) {
    if (!action) return '';
    if (action.stepType === 'COMPLETE') {
      var completeCopy = part === 'title'
        ? ['指引已完成', 'Guidance complete', 'Indicaciones completadas']
        : ['你已到达机场出口，祝你旅途顺利。', 'You have reached the airport exit. Have a safe journey.', 'Has llegado a la salida del aeropuerto. Buen viaje.'];
      return completeCopy[locale === 'zh' ? 0 : locale === 'es' ? 2 : 1];
    }
    if (action.stepType === 'BAGGAGE_QUESTION' && part === 'description') {
      var baggageCopy = ['随身携带的小包不算托运行李。',
        'A small carry-on does not count as checked baggage.',
        'El equipaje de mano no cuenta como equipaje facturado.'];
      return baggageCopy[locale === 'zh' ? 0 : locale === 'es' ? 2 : 1];
    }
    if (action.reason === 'CHECKED_BAGGAGE_NOT_DROPPED' && action.needsOnSiteConfirmation) {
      if (part === 'title') {
        return standardActionCopy.BAG_DROP.title[locale === 'zh' ? 0 : locale === 'es' ? 2 : 1];
      }
      var bagDropMissing = [
        '专用托运柜台尚未公布，请在值机/托运区域向工作人员确认。',
        'A dedicated bag-drop desk is not posted. Ask staff in the check-in / bag-drop area.',
        'No se ha publicado un mostrador específico. Pregunta al personal en la zona de facturación / equipaje.'
      ];
      return bagDropMissing[locale === 'zh' ? 0 : locale === 'es' ? 2 : 1];
    }
    if (warningActionReasons.indexOf(action.reason) >= 0) return directActionText(action, part, locale);
    var copy = standardActionCopy[action.actionCode];
    if (copy && copy[part]) return copy[part][locale === 'zh' ? 0 : locale === 'es' ? 2 : 1];
    return directActionText(action, part, locale);
  }

  function actionLocaleText(action, part) {
    return actionTextForLocale(action, part, currentLocale);
  }

  function setBilingualActionText(element, action, part, heading) {
    setBilingualContent(element, actionTextForLocale(action, part, currentLocale),
      actionTextForLocale(action, part, 'en'), heading);
  }

  function setLocalizedStepText(element, action, part) {
    if (!element) return;
    element.textContent = actionTextForLocale(action, part, currentLocale);
    element.lang = localeLanguageTag(currentLocale);
  }

  function renderJourneyResponse(options) {
    var settings = options || {};
    if (!currentAction) return;
    if (currentAction.stepType === 'COMPLETE') {
      setBilingualActionText(document.querySelector('[data-complete-title]'), currentAction, 'title', true);
      setBilingualActionText(document.querySelector('[data-complete-description]'), currentAction, 'description', false);
      document.querySelector('[data-complete-flight]').textContent = formatFlightNumber(currentFlight.flightNumber)
        + ' · ' + (currentFlight.arrivalAirport || '—');
      setStaffContent(
        '请帮我确认到达出口后的路线', 'Please help me confirm the route after the arrivals exit',
        '我已经到达机场出口。请帮我确认接下来前往接机区或公共交通的路线。',
        'I have reached the airport exit. Please help me find the pickup area or public transport.',
        'Ayúdeme a confirmar la ruta después de la salida de llegadas',
        'He llegado a la salida del aeropuerto. Ayúdeme a encontrar la zona de recogida o el transporte público.'
      );
      showScreen('complete', { silent: settings.silent });
      var completeBack = document.querySelector('.screen-complete [data-action="journey-back"]');
      if (completeBack) completeBack.hidden = !journeyHistory.length;
      renderJourneyTimeline();
      renderConnectivityStatus();
      if (!settings.silent) announceLatestChangesOr(actionLocaleText(currentAction, 'title'));
      renderShareEntryVisibility();
      syncAirportResourcesContext();
      if (!settings.silent) queueAutomaticSpeech();
      return;
    }

    showScreen('step', { silent: settings.silent });
    var baggageQuestion = currentAction.stepType === 'BAGGAGE_QUESTION';
    var checkInQuestion = currentAction.stepType === 'CHECK_IN_QUESTION';
    var question = baggageQuestion || checkInQuestion;
    var departureException = isDepartureStopAction(currentAction);
    var helpOnlyAction = currentAction.actionCode === 'ASK_FOR_HELP';
    document.querySelector('[data-step-question]').hidden = !baggageQuestion;
    document.querySelector('[data-checkin-question]').hidden = !checkInQuestion;
    document.querySelector('[data-step-result]').hidden = question;
    document.querySelector('.screen-step').classList.toggle('is-departure-exception', departureException);
    var exceptionActions = document.querySelector('[data-departure-exception-actions]');
    if (exceptionActions) exceptionActions.hidden = !departureException;
    document.querySelector('.screen-step .step-assistance-actions').hidden = false;
    var completeButton = document.querySelector('[data-complete-action]');
    completeButton.hidden = question || helpOnlyAction;
    completeButton.disabled = !question && currentAction.actionCode === 'WAIT'
      && (!navigator.onLine || Boolean(flightRequestController));
    document.querySelectorAll('.screen-step [data-action="journey-back"]').forEach(function (backButton) {
      backButton.hidden = !currentFlight || departureException;
    });
    document.querySelector('[data-step-label]').textContent = t('currentStep');
    var completedStages = completedStageCount();
    document.querySelector('[data-step-count]').textContent = localeText(
      '已完成 ' + completedStages + ' 步',
      completedStages + (completedStages === 1 ? ' step completed' : ' steps completed'),
      completedStages + (completedStages === 1 ? ' paso completado' : ' pasos completados'));

    if (checkInQuestion) {
      document.querySelector('[data-checkin-choices]').hidden = false;
      setLocalizedStepText(document.getElementById('checkin-title'), currentAction, 'title');
      setLocalizedStepText(document.querySelector('[data-checkin-question] .lead'), currentAction, 'description');
      document.querySelectorAll('[data-action="checkin-status"]').forEach(function (button) {
        button.setAttribute('aria-pressed', String(button.dataset.answer === checkInStatusAnswer));
      });
      document.querySelector('.screen-step').setAttribute('aria-labelledby', 'checkin-title');
      setStaffContent(
        '请帮我确认是否已经值机', 'Please help me confirm whether I am checked in',
        '您好，我不确定自己是否已经办理值机。请帮我确认登机牌或旅行文件，并告诉我下一步。',
        'Hello. I am not sure whether I have checked in. Please check my boarding pass or travel document and tell me the next step.',
        'Ayúdeme a confirmar si ya he facturado',
        'No sé si ya he facturado. Compruebe mi tarjeta de embarque o documento de viaje y dígame el siguiente paso.'
      );
    } else if (baggageQuestion) {
      setLocalizedStepText(document.querySelector('[data-question-title]'), currentAction, 'title');
      setLocalizedStepText(document.querySelector('[data-question-description]'), currentAction, 'description');
      document.querySelector('.screen-step').setAttribute('aria-labelledby', 'step-title');
      setBaggageStaffContent();
    } else {
      setLocalizedStepText(document.querySelector('[data-action-title]'), currentAction, 'title');
      setLocalizedStepText(document.querySelector('[data-action-description]'), currentAction, 'description');
      renderActionLocation();
      renderActionTime();
      renderInFlightDetails();
      if (!helpOnlyAction) {
        var completionLabel = completeButton.querySelector('[data-complete-action-label]') || completeButton;
        var completionAria = textFor(completionLabels[currentAction.actionCode]
          || ['完成当前行动', 'Complete the current action', 'Completar la acción actual']);
        completionLabel.textContent = currentAction.actionCode === 'WAIT'
          ? localeText('刷新', 'Refresh', 'Actualizar') : t('completeShort');
        completeButton.setAttribute('aria-label', completionAria + localeText(
          '：' + actionLocaleText(currentAction, 'title'),
          ': ' + actionLocaleText(currentAction, 'title'),
          ': ' + actionLocaleText(currentAction, 'title')));
      }
      document.querySelector('.screen-step').setAttribute('aria-labelledby', 'action-title');
      setStaffForAction(currentAction);
    }
    var regularStaffButton = document.querySelector('.screen-step .step-assistance-actions .staff-help-button');
    if (regularStaffButton) regularStaffButton.hidden = departureException;
    var arrivalRefresh = document.querySelector('[data-action="refresh-arrival"]');
    if (arrivalRefresh) {
      arrivalRefresh.hidden = question || currentAction.actionCode !== 'BAGGAGE_CLAIM'
        || !journeyContext || !journeyContext.landed
        || (!currentFlight.live && !currentFlight.demoScenario);
    }
    renderJourneyTimeline();
    renderTimeStatus();
    renderFlightChanges();
    renderRefreshStatus();
    renderConnectivityStatus();
    if (!settings.silent) announceLatestChangesOr(actionLocaleText(currentAction, 'title'));
    maybeRefreshArrivalOnEntry();
    renderShareEntryVisibility();
    renderScenarioTestCards();
    syncAirportResourcesContext();
    if (!settings.silent) queueAutomaticSpeech();
  }

  function locationCode(value) {
    if (!value) return null;
    return String(value).replace(/^terminal\s*/i, '').replace(/^gate\s*/i, '').trim() || null;
  }

  function checkInAreaCode() {
    var value = currentFlight && (currentFlight.checkInDeskOrZone || currentFlight.checkInCounter);
    if (!value) return null;
    return String(value).replace(/^(check[- ]?in\s*(desk|counter|area|zone)?|desk|counter|area|zone)\s*/i, '').trim() || value;
  }

  function baggageBeltCode() {
    var value = currentFlight && currentFlight.arrivalBaggageBelt;
    if (!value) return null;
    return String(value).replace(/^(baggage\s*)?(belt|carousel)\s*/i, '').trim() || value;
  }

  function renderActionDestination() {
    var primary = document.querySelector('[data-destination-primary]');
    var primaryEn = document.querySelector('[data-destination-primary-en]');
    var context = document.querySelector('[data-destination-context]');
    var contextEn = document.querySelector('[data-destination-context-en]');
    var code = currentAction && currentAction.actionCode;
    var terminal = locationCode(currentFlight && currentFlight.terminal);
    var gate = locationCode(currentFlight && currentFlight.gate);
    var checkIn = checkInAreaCode();
    var dedicatedBagDrop = locationCode(currentFlight && currentFlight.dedicatedBagDropDesk);
    var baggageBelt = baggageBeltCode();
    var arrival = currentFlight && (currentFlight.arrivalAirportName || currentFlight.arrivalAirport);
    var airlineValue = currentFlight && (currentFlight.airlineName || currentFlight.airline);
    var airlineName = typeof airlineValue === 'string' ? airlineValue.trim() || null : null;
    var airlineZh = airlineName ? airlineName + ' ' : '航空公司 ';
    var airlineEn = airlineName || 'airline';
    var airlineEs = airlineName || 'la aerolínea';
    var terminalZh = formatTerminalForLocale(terminal, 'zh');
    var terminalEn = formatTerminalForLocale(terminal, 'en');
    var terminalEs = formatTerminalForLocale(terminal, 'es');
    var gateZh = formatGateForLocale(gate, 'zh');
    var gateEn = formatGateForLocale(gate, 'en');
    var gateEs = formatGateForLocale(gate, 'es');
    var gateEsSentence = gateEs && gateEs.replace(/^Puerta/, 'puerta');
    var checkInZh = formatCheckInForLocale(checkIn, 'zh');
    var checkInEn = formatCheckInForLocale(checkIn, 'en');
    var checkInEs = formatCheckInForLocale(checkIn, 'es');
    var copy = {
      primaryZh: '向工作人员确认具体位置',
      primaryEn: 'Ask staff to confirm the exact location',
      primaryEs: 'Pide al personal que confirme el lugar exacto',
      contextZh: terminalZh || '',
      contextEn: terminalEn || '',
      contextEs: terminalEs || ''
    };

    if (isDepartureStopAction(currentAction)) {
      copy.primaryZh = '前往航空公司服务柜台，请工作人员帮忙';
      copy.primaryEn = 'Go to the airline service desk and ask for help';
      copy.primaryEs = 'Ve al mostrador de atención de la aerolínea y pide ayuda';
      copy.contextZh = terminalZh
        ? '参考：' + terminalZh + '；请在现场确认服务柜台位置。'
        : '请在现场确认航空公司服务柜台位置。';
      copy.contextEn = terminalEn
        ? 'Reference: ' + terminalEn + '; confirm the service-desk location on site.'
        : 'Confirm the airline service-desk location on site.';
      copy.contextEs = terminalEs
        ? 'Referencia: ' + terminalEs + '; confirma allí la ubicación del mostrador de atención.'
        : 'Confirma allí la ubicación del mostrador de atención de la aerolínea.';
    } else if (code === 'CHECK_IN') {
      if (checkIn) {
        copy.primaryZh = '前往' + airlineZh + checkInZh + '办理值机';
        copy.primaryEn = 'Go to ' + airlineEn + ' ' + checkInEn.replace(/^Check-in/, 'check-in') + ' to check in';
        copy.primaryEs = 'Ve a la ' + checkInEs.replace(/^Zona/, 'zona') + ' de ' + airlineEs + ' para facturar';
      } else if (terminal) {
        copy.primaryZh = '前往' + terminalZh + '的' + airlineZh.trim() + '值机区办理值机';
        copy.primaryEn = 'Go to the ' + airlineEn + ' check-in area in ' + terminalEn + ' to check in';
        copy.primaryEs = 'Ve a la zona de facturación de ' + airlineEs + ' en la ' + terminalEs + ' para facturar';
        copy.contextZh = copy.contextEn = copy.contextEs = '';
      } else {
        copy.primaryZh = '请航空公司工作人员确认值机位置并协助办理值机';
        copy.primaryEn = 'Ask airline staff to confirm where to check in and help you complete check-in';
        copy.primaryEs = 'Pide al personal de la aerolínea que confirme dónde facturar y te ayude a hacerlo';
      }
    } else if (code === 'BAG_DROP') {
      if (dedicatedBagDrop) {
        copy.primaryZh = '前往' + airlineZh + formatBagDropForLocale(dedicatedBagDrop, 'zh') + '托运行李';
        copy.primaryEn = 'Go to ' + airlineEn + ' ' + formatBagDropForLocale(dedicatedBagDrop, 'en').replace(/^Bag/, 'bag') + ' to drop off your baggage';
        copy.primaryEs = 'Ve a la ' + formatBagDropForLocale(dedicatedBagDrop, 'es').replace(/^Entrega/, 'entrega') + ' de ' + airlineEs + ' para entregar el equipaje';
      } else if (checkIn) {
        copy.primaryZh = '前往' + airlineZh + formatCheckInForLocale(checkIn, 'zh', true) + '，询问托运位置并托运行李';
        copy.primaryEn = 'Go to ' + airlineEn + ' ' + checkInEn.replace(/^Check-in/, 'check-in') + ' and ask where to drop off your baggage';
        copy.primaryEs = 'Ve a la ' + checkInEs.replace(/^Zona/, 'zona') + ' de ' + airlineEs + ' y pregunta dónde entregar el equipaje';
      } else if (terminal) {
        copy.primaryZh = '前往' + terminalZh + '的' + airlineZh.trim() + '柜台，询问托运位置并托运行李';
        copy.primaryEn = 'Go to the ' + airlineEn + ' counters in ' + terminalEn + ' and ask where to drop off your baggage';
        copy.primaryEs = 'Ve a los mostradores de ' + airlineEs + ' en la ' + terminalEs + ' y pregunta dónde entregar el equipaje';
        copy.contextZh = copy.contextEn = copy.contextEs = '';
      } else {
        copy.primaryZh = '请工作人员确认行李托运位置并协助托运行李';
        copy.primaryEn = 'Ask staff to confirm the baggage drop location and help you drop off your baggage';
        copy.primaryEs = 'Pide al personal que confirme dónde entregar el equipaje y que te ayude a hacerlo';
      }
    } else if (code === 'SECURITY') {
      copy.primaryZh = terminal ? '前往' + terminalZh + '安检入口接受安检' : '前往机场安检入口接受安检';
      copy.primaryEn = terminal ? 'Go to the security entrance in ' + terminalEn + ' and pass through security' : 'Go to airport security and pass through screening';
      copy.primaryEs = terminal ? 'Ve al control de seguridad de la ' + terminalEs + ' y pasa el control' : 'Ve al control de seguridad del aeropuerto y pasa el control';
      copy.contextZh = copy.contextEn = copy.contextEs = '';
    } else if (code === 'GO_TO_GATE') {
      copy.primaryZh = gate ? '前往' + (terminalZh ? terminalZh + '的' : '') + gateZh + '，查看屏幕并等候登机' : '请工作人员确认登机口，然后前往该登机口等候登机';
      copy.primaryEn = gate ? 'Go to ' + gateEn + (terminalEn ? ' in ' + terminalEn : '') + ', check the screen, and wait to board' : 'Ask staff to confirm the gate, then go there and wait to board';
      copy.primaryEs = gate ? 'Ve a la ' + gateEsSentence + (terminalEs ? ' de la ' + terminalEs : '') + ', comprueba la pantalla y espera para embarcar' : 'Pide al personal que confirme la puerta; después ve allí y espera para embarcar';
      copy.contextZh = copy.contextEn = copy.contextEs = '';
    } else if (code === 'WAIT') {
      copy.primaryZh = gate ? '在' + formatGateValue(gate) + '附近等候' : '在航班信息屏幕附近等候';
      copy.primaryEn = gate ? 'Wait near gate ' + gate : 'Wait near a flight information screen';
      copy.primaryEs = gate ? 'Espera cerca de la puerta ' + gate : 'Espera cerca de una pantalla de vuelos';
    } else if (code === 'BOARD_NOW') {
      copy.primaryZh = '前往' + (terminalZh ? terminalZh + '的' : '') + (gateZh || '当前航班登机口') + '，排队并出示登机牌登机';
      copy.primaryEn = 'Go to ' + (gateEn || 'your flight gate') + (terminalEn ? ' in ' + terminalEn : '') + ', queue, and show your boarding pass to board';
      copy.primaryEs = 'Ve a la ' + (gateEsSentence || 'puerta de tu vuelo') + (terminalEs ? ' de la ' + terminalEs : '') + ', haz la fila y muestra tu tarjeta de embarque';
      copy.contextZh = copy.contextEn = copy.contextEs = '';
    } else if (code === 'ASK_FOR_HELP') {
      copy.primaryZh = '请工作人员查看大字卡并提供帮助';
      copy.primaryEn = 'Ask a member of staff to read the card and help you';
      copy.primaryEs = 'Pide al personal que lea la tarjeta y te ayude';
    } else if (code === 'IN_FLIGHT') {
      copy.primaryZh = '飞往 ' + (arrival || '目的地机场');
      copy.primaryEn = 'Flying to ' + (arrival || 'the destination airport');
      copy.primaryEs = 'Volando a ' + (arrival || 'el aeropuerto de destino');
      copy.contextZh = currentFlight.arrivalAirport ? '到达机场：' + currentFlight.arrivalAirport : '';
      copy.contextEn = currentFlight.arrivalAirport ? 'Arrival airport: ' + currentFlight.arrivalAirport : '';
      copy.contextEs = currentFlight.arrivalAirport ? 'Aeropuerto de llegada: ' + currentFlight.arrivalAirport : '';
    } else if (code === 'BAGGAGE_CLAIM') {
      copy.primaryZh = baggageBelt ? '前往' + formatBeltForLocale(baggageBelt, 'zh') + '，核对航班号并领取行李' : '查看到达屏幕确认行李转盘，再前往领取行李';
      copy.primaryEn = baggageBelt ? 'Go to ' + formatBeltForLocale(baggageBelt, 'en').replace(/^Baggage/, 'baggage') + ', check the flight number, and collect your baggage' : 'Check the arrivals screen for the baggage belt, then go there to collect your baggage';
      copy.primaryEs = baggageBelt ? 'Ve a la ' + formatBeltForLocale(baggageBelt, 'es').replace(/^Cinta/, 'cinta') + ', comprueba el vuelo y recoge tu equipaje' : 'Consulta la pantalla de llegadas para saber la cinta y ve allí a recoger tu equipaje';
      copy.contextZh = '请在到达屏幕上核对航班 ' + formatFlightNumber(currentFlight.flightNumber);
      copy.contextEn = 'Check flight ' + formatFlightNumber(currentFlight.flightNumber) + ' on the arrivals screen';
      copy.contextEs = 'Comprueba el vuelo ' + formatFlightNumber(currentFlight.flightNumber) + ' en la pantalla de llegadas';
    } else if (code === 'EXIT_AIRPORT') {
      copy.primaryZh = '按照“到达 / 出口”指示前往出口并离开机场';
      copy.primaryEn = 'Follow the Arrivals / Exit signs to the exit and leave the airport';
      copy.primaryEs = 'Sigue las señales de Llegadas / Salida hasta la salida y abandona el aeropuerto';
      copy.contextZh = currentFlight.arrivalAirport ? '到达机场：' + currentFlight.arrivalAirport : '';
      copy.contextEn = currentFlight.arrivalAirport ? 'Arrival airport: ' + currentFlight.arrivalAirport : '';
      copy.contextEs = currentFlight.arrivalAirport ? 'Aeropuerto de llegada: ' + currentFlight.arrivalAirport : '';
    } else if (terminal) {
      copy.primaryZh = '先前往' + formatTerminalValue(terminal);
      copy.primaryEn = 'Go to Terminal ' + terminal + ' first';
      copy.primaryEs = 'Ve primero a la Terminal ' + terminal;
      copy.contextZh = '';
      copy.contextEn = '';
      copy.contextEs = '';
    }

    currentDestinationCopy = copy;
    var primaryCopy = currentLocale === 'zh' ? copy.primaryZh : currentLocale === 'es' ? copy.primaryEs : copy.primaryEn;
    var contextCopy = currentLocale === 'zh' ? copy.contextZh : currentLocale === 'es' ? copy.contextEs : copy.contextEn;
    primary.textContent = primaryCopy;
    primary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    primaryEn.textContent = copy.primaryEn;
    primaryEn.hidden = true;
    context.textContent = contextCopy;
    context.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    context.hidden = !contextCopy;
    contextEn.textContent = copy.contextEn;
    contextEn.hidden = true;
  }

  function renderActionLocation() {
    var container = document.querySelector('[data-action-destination]');
    var primary = document.querySelector('[data-destination-primary]');
    var primaryEn = document.querySelector('[data-destination-primary-en]');
    var context = document.querySelector('[data-destination-context]');
    var contextEn = document.querySelector('[data-destination-context-en]');
    var code = currentAction && currentAction.actionCode;
    var hideLocation = !code || code === 'WAIT' || code === 'IN_FLIGHT';
    if (container) container.hidden = hideLocation;
    if (hideLocation) {
      currentDestinationCopy = null;
      if (primary) primary.textContent = '';
      if (primaryEn) primaryEn.textContent = '';
      if (context) context.textContent = '';
      if (contextEn) contextEn.textContent = '';
      return;
    }

    var terminal = locationCode(currentFlight && currentFlight.terminal);
    var gate = locationCode(currentFlight && currentFlight.gate);
    var checkIn = checkInAreaCode();
    var bagDrop = locationCode(currentFlight && currentFlight.dedicatedBagDropDesk);
    var belt = baggageBeltCode();
    var airlineValue = currentFlight && (currentFlight.airlineName || currentFlight.airline);
    var airline = typeof airlineValue === 'string' ? airlineValue.trim() : '';

    function valueFor(locale) {
      var terminalValue = formatTerminalForLocale(terminal, locale);
      var gateValue = formatGateForLocale(gate, locale);
      var checkInValue = formatCheckInForLocale(checkIn, locale);
      var bagDropValue = formatBagDropForLocale(bagDrop, locale);
      var beltValue = formatBeltForLocale(belt, locale);
      var separator = ' · ';
      if (code === 'CHECK_IN') {
        return [airline, checkInValue || terminalValue
          || (locale === 'zh' ? '值机区域' : locale === 'es' ? 'Zona de facturación' : 'Check-in area')]
          .filter(Boolean).join(separator);
      }
      if (code === 'BAG_DROP') {
        return [airline, bagDropValue || checkInValue || terminalValue
          || (locale === 'zh' ? '行李托运' : locale === 'es' ? 'Entrega de equipaje' : 'Bag drop')]
          .filter(Boolean).join(separator);
      }
      if (code === 'SECURITY') {
        return [locale === 'zh' ? '安检' : locale === 'es' ? 'Control de seguridad' : 'Security', terminalValue]
          .filter(Boolean).join(separator);
      }
      if (code === 'GO_TO_GATE' || code === 'BOARD_NOW') {
        return [gateValue || (locale === 'zh' ? '登机口待确认' : locale === 'es' ? 'Puerta por confirmar' : 'Gate to be confirmed'), terminalValue]
          .filter(Boolean).join(separator);
      }
      if (code === 'BAGGAGE_CLAIM') {
        return beltValue || (locale === 'zh' ? '行李提取区' : locale === 'es' ? 'Recogida de equipaje' : 'Baggage claim');
      }
      if (code === 'EXIT_AIRPORT') {
        return locale === 'zh' ? '到达出口' : locale === 'es' ? 'Salida de llegadas' : 'Arrivals exit';
      }
      if (code === 'ASK_FOR_HELP') {
        return isDepartureStopAction(currentAction)
          ? (locale === 'zh' ? '航空公司服务柜台' : locale === 'es' ? 'Mostrador de atención de la aerolínea' : 'Airline service desk')
          : (locale === 'zh' ? '附近工作人员' : locale === 'es' ? 'Personal cercano' : 'Nearby staff');
      }
      return terminalValue || (locale === 'zh' ? '现场确认' : locale === 'es' ? 'Confirmar allí' : 'Confirm on site');
    }

    currentDestinationCopy = {
      primaryZh: valueFor('zh'), primaryEn: valueFor('en'), primaryEs: valueFor('es'),
      contextZh: '', contextEn: '', contextEs: ''
    };
    var localized = currentLocale === 'zh' ? currentDestinationCopy.primaryZh
      : currentLocale === 'es' ? currentDestinationCopy.primaryEs : currentDestinationCopy.primaryEn;
    primary.textContent = localized;
    primary.lang = localeLanguageTag(currentLocale);
    primaryEn.textContent = currentDestinationCopy.primaryEn;
    primaryEn.hidden = true;
    context.textContent = '';
    context.hidden = true;
    contextEn.textContent = '';
    contextEn.hidden = true;
  }

  function renderActionTime() {
    var element = document.querySelector('[data-action-time]');
    var primary = document.querySelector('[data-action-time-primary]');
    var countdownElement = document.querySelector('[data-action-time-countdown]');
    var time = currentFlight && currentFlight.timeContext;
    var arrivalPhase = isArrivalPhase();
    var arrivalTarget = currentFlight && (currentFlight.actualArrival
      || currentFlight.estimatedArrival || currentFlight.scheduledArrival);
    if (arrivalPhase && currentAction.actionCode !== 'IN_FLIGHT') {
      primary.textContent = localeText('目的地当地时间 ', 'Destination local time ', 'Hora local del destino ')
        + formatDestinationLocalNow();
      countdownElement.textContent = localeText('请留意到达屏幕和机场指示。', 'Follow the arrivals screens and signs.', 'Sigue las pantallas y señales de llegadas.');
      element.classList.remove('is-overdue');
      return;
    }
    if (currentAction.actionCode === 'IN_FLIGHT') {
      if (!arrivalTarget) {
        primary.textContent = localeText('到达时间暂时无法确认', 'Arrival time is not available', 'La hora de llegada no está disponible');
        countdownElement.textContent = localeText('请留意机上广播。', 'Listen for announcements on board.', 'Escucha los avisos a bordo.');
        element.classList.remove('is-overdue');
        return;
      }
      primary.textContent = localeText('预计到达 ', 'Expected arrival ', 'Llegada estimada ')
        + formatAtArrival(arrivalTarget, false);
      countdownElement.textContent = Date.parse(arrivalTarget) > calibratedNowMs()
        ? localeText('还有 ' + formatCountdown(arrivalTarget), formatCountdown(arrivalTarget) + ' remaining', 'Faltan ' + formatCountdown(arrivalTarget))
        : localeText('已到预计到达时间，请听从机组人员指引。', 'Arrival time reached; follow cabin crew instructions.', 'Ya es la hora prevista de llegada; sigue las instrucciones de la tripulación.');
      element.classList.remove('is-overdue');
      return;
    }
    if (!time) {
      primary.textContent = localeText('时间尚未取得', 'Time not available', 'Hora no disponible');
      countdownElement.textContent = localeText('请向机场工作人员确认时间。', 'Confirm the time with airport staff.', 'Confirma la hora con el personal del aeropuerto.');
      element.classList.remove('is-overdue');
      return;
    }
    var target = time.boardingTime || time.effectiveDeparture;
    var targetLabel = time.boardingTime ? localeText('登机', 'Boarding', 'Embarque') : localeText('预计起飞', 'Expected departure', 'Salida estimada');
    var targetTime = formatAtDeparture(target, false);
    var countdown = formatCountdown(target);
    var overdue = Number.isFinite(Date.parse(target)) && Date.parse(target) < calibratedNowMs();
    primary.textContent = localeText(targetLabel + '时间 ' + targetTime, targetLabel + ' time ' + targetTime, targetLabel + ' ' + targetTime);
    countdownElement.textContent = overdue
      ? localeText(countdown + '，请现在向工作人员求助。', countdown + '. Ask a member of staff now.', countdown + '. Pide ayuda al personal ahora.')
      : localeText('还有 ' + countdown, countdown + ' remaining', 'Faltan ' + countdown);
    element.classList.toggle('is-overdue', overdue);
  }

  function effectiveArrival() {
    return currentFlight && (currentFlight.actualArrival
      || currentFlight.estimatedArrival || currentFlight.scheduledArrival);
  }

  function formatDestinationLocalNow() {
    return formatLocalNowFor(currentLocale, arrivalOffsetMinutes());
  }

  function formatLocalNowFor(locale, offsetMinutes) {
    var normalizedLocale = locale === true ? 'en' : locale === false ? 'zh' : normalizeLocale(locale);
    var shifted = new Date(calibratedNowMs() + offsetMinutes * 60000);
    return new Intl.DateTimeFormat(normalizedLocale === 'zh' ? 'zh-CN' : normalizedLocale === 'es' ? 'es-ES' : 'en-US', {
      dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC'
    }).format(shifted);
  }

  function formatFlightDuration() {
    var departure = currentFlight && (currentFlight.actualDeparture
      || currentFlight.estimatedDeparture || currentFlight.scheduledDeparture);
    var arrival = effectiveArrival();
    var minutes = Math.round((Date.parse(arrival || '') - Date.parse(departure || '')) / 60000);
    if (!Number.isFinite(minutes) || minutes <= 0) return localeText('暂时无法确认', 'Not available', 'No disponible');
    var hours = Math.floor(minutes / 60);
    var remainder = minutes % 60;
    return localeText(hours + ' 小时 ' + remainder + ' 分钟',
      hours + (hours === 1 ? ' hour ' : ' hours ') + remainder + (remainder === 1 ? ' minute' : ' minutes'),
      hours + (hours === 1 ? ' hora ' : ' horas ') + remainder + (remainder === 1 ? ' minuto' : ' minutos'));
  }

  function renderInFlightDetails() {
    var panel = document.querySelector('[data-in-flight-details]');
    if (!panel) return;
    var visible = currentAction && currentAction.actionCode === 'IN_FLIGHT';
    panel.hidden = !visible;
    if (!visible) return;
    var arrival = effectiveArrival();
    setAll('[data-in-flight-duration]', formatFlightDuration());
    setAll('[data-in-flight-arrival-time]', arrival
      ? formatAtArrival(arrival, true)
      : localeText('请留意机上广播', 'Listen for onboard announcements', 'Escucha los avisos a bordo'));
    setAll('[data-in-flight-arrival-countdown]', arrival
      ? formatCountdown(arrival)
      : localeText('暂时无法确认', 'Not available', 'No disponible'));
    setAll('[data-destination-local-time]', formatDestinationLocalNow());
  }

  function completedStageCount() {
    if (!journeyContext) return 0;
    var count = 0;
    if (journeyContext.checkedIn) count += 1;
    if (journeyContext.hasCheckedBaggage && journeyContext.bagDropped) count += 1;
    if (journeyContext.securityCleared) count += 1;
    if (journeyContext.atGate) count += 1;
    if (journeyContext.boarded) count += 1;
    if (journeyContext.landed) count += 1;
    if (journeyContext.baggageCollected) count += 1;
    if (journeyContext.exitedAirport) count += 1;
    return count;
  }

  function updateClockAnchor() {
    if (isDocumentFallbackFlight(currentFlight)) return;
    var serverNow = currentFlight && currentFlight.timeContext && currentFlight.timeContext.serverNow;
    if (!serverNow) return;
    if (clockAnchor && clockAnchor.source === serverNow) {
      ensureClockTimer();
      return;
    }
    var serverMs = Date.parse(serverNow);
    if (!Number.isFinite(serverMs)) return;
    clockAnchor = { source: serverNow, serverMs: serverMs, clientMs: Date.now() };
    ensureClockTimer();
  }

  function ensureClockTimer() {
    if (nativeApp && !platform.isActive()) return;
    if (!clockTimer) {
      clockTimer = window.setInterval(function () {
        renderTimeStatus();
        if (currentAction && currentAction.stepType === 'ACTION') {
          renderActionTime();
          renderInFlightDetails();
        }
      }, 1000);
    }
  }

  function calibratedNowMs() {
    return clockAnchor ? clockAnchor.serverMs + (Date.now() - clockAnchor.clientMs) : Date.now();
  }

  function calibratedNowIso() {
    return new Date(calibratedNowMs()).toISOString();
  }

  function formatCountdown(target) {
    return formatCountdownFor(target, currentLocale);
  }

  function formatCountdownFor(target, locale) {
    var normalizedLocale = locale === true ? 'en' : locale === false ? 'zh' : normalizeLocale(locale);
    var english = normalizedLocale === 'en';
    var spanish = normalizedLocale === 'es';
    var targetMs = Date.parse(target || '');
    if (!Number.isFinite(targetMs)) return normalizedLocale === 'zh' ? '时间缺失' : spanish ? 'Hora no disponible' : 'Time unavailable';
    var totalMinutes = Math.ceil((targetMs - calibratedNowMs()) / 60000);
    var passed = totalMinutes < 0;
    var absolute = Math.abs(totalMinutes);
    var hours = Math.floor(absolute / 60);
    var minutes = absolute % 60;
    var duration = hours
      ? (english ? hours + (hours === 1 ? ' hour ' : ' hours ') + minutes + (minutes === 1 ? ' minute' : ' minutes')
        : spanish ? hours + (hours === 1 ? ' hora ' : ' horas ') + minutes + (minutes === 1 ? ' minuto' : ' minutos')
          : hours + ' 小时 ' + minutes + ' 分')
      : (english ? minutes + (minutes === 1 ? ' minute' : ' minutes')
        : spanish ? minutes + (minutes === 1 ? ' minuto' : ' minutos') : minutes + ' 分钟');
    return passed
      ? (english ? duration + ' past' : spanish ? 'Han pasado ' + duration : '已过 ' + duration)
      : duration;
  }

  function renderTimeStatus() {
    var time = currentFlight && !isDocumentFallbackFlight(currentFlight) && currentFlight.timeContext;
    document.querySelectorAll('[data-time-status]').forEach(function (panel) { panel.hidden = !time; });
    if (!time) return;
    var arrivalPhase = isArrivalPhase();
    var offsetMinutes = arrivalPhase ? arrivalOffsetMinutes() : departureOffsetMinutes();
    var localText = formatLocalNowFor(currentLocale, offsetMinutes);
    document.querySelectorAll('[data-i18n="airportLocalTime"]').forEach(function (label) {
      label.textContent = arrivalPhase
        ? t('destinationLocalTime')
        : t('airportLocalTime');
    });
    setAll('[data-airport-local-time]', localText);
    var countdownTarget = arrivalPhase ? effectiveArrival() : time.effectiveDeparture;
    setAll('[data-departure-countdown]', formatCountdown(countdownTarget));
    document.querySelectorAll('[data-departure-countdown]').forEach(function (value) {
      var label = value.parentElement && value.parentElement.querySelector('span');
      if (label) label.textContent = arrivalPhase
        ? localeText('距离预计到达', 'Until expected arrival', 'Tiempo hasta la llegada')
        : t('timeUntilDeparture');
    });

    var ageReference = time.dataLastUpdatedAt || time.dataRetrievedAt;
    var ageMinutes = ageReference
      ? Math.max(0, Math.floor((calibratedNowMs() - Date.parse(ageReference)) / 60000))
      : null;
    var stale = !arrivalPhase && ageMinutes != null && ageMinutes > 10;
    var ageText = ageMinutes == null
      ? localeText('更新时间缺失', 'Update time unavailable', 'Hora de actualización no disponible')
      : ageMinutes < 1
        ? localeText('刚刚更新', 'Just updated', 'Actualizado ahora')
        : localeText(ageMinutes + ' 分钟前更新', 'Updated ' + ageMinutes + ' minutes ago', 'Actualizado hace ' + ageMinutes + ' minutos');
    if (arrivalPhase) {
      ageText = navigator.onLine
        ? localeText('正在使用登机前保存的行程；下飞机后可按需刷新到达信息。', 'Using the saved pre-departure journey; refresh arrival information after landing if needed.', 'Se usa el viaje guardado antes de la salida; actualiza la llegada después de aterrizar si hace falta.')
        : localeText('当前离线，正在显示已保存的行程。', 'Offline · showing the saved journey.', 'Sin conexión · mostrando el viaje guardado.');
    }
    if (stale) ageText = localeText('数据较旧，请刷新 · ', 'Data is old — refresh now · ', 'Los datos son antiguos; actualiza · ') + ageText;
    setAll('[data-data-freshness]', ageText);
    document.querySelectorAll('[data-time-status]').forEach(function (panel) {
      panel.classList.toggle('is-stale', stale);
    });
  }

  function renderConflictCard() {
    var card = document.querySelector('[data-conflict-card]');
    var list = document.querySelector('[data-conflict-list]');
    var conflicts = currentFlight && Array.isArray(currentFlight.conflicts)
      ? currentFlight.conflicts.filter(function (conflict) { return conflict.blocking; }) : [];
    card.hidden = !conflicts.length;
    list.replaceChildren();
    conflicts.forEach(function (conflict) {
      var item = document.createElement('div');
      item.className = 'conflict-item';
      var title = document.createElement('strong');
      title.textContent = currentLocale === 'zh' ? conflict.messageZh
        : currentLocale === 'en' ? conflict.messageEn
          : 'Los datos importantes del vuelo no coinciden. Confírmalos con el personal.';
      var values = document.createElement('span');
      var providerConflict = /AERODATABOX|SERPAPI|VARIFLIGHT/.test(conflict.documentValue || '');
      values.textContent = providerConflict
        ? localeText('当前采用：', 'Selected: ', 'Seleccionado: ') + (conflict.liveValue || '—')
        : localeText('文件：', 'Document: ', 'Documento: ') + (conflict.documentValue || '—')
          + localeText(' · 实时：', ' · Live: ', ' · En directo: ') + (conflict.liveValue || '—');
      item.append(title, values);
      list.appendChild(item);
    });
    var continueButton = document.querySelector('[data-action="continue"]');
    if (currentFlight && currentFlight.requiresUserConfirmation) {
      continueButton.disabled = !conflictsConfirmed;
      document.querySelector('[data-action="confirm-conflicts"]').hidden = conflictsConfirmed;
    }
  }

  function renderFlightChanges() {
    var groups = currentFlightChangeGroups();
    var critical = displayedFlightChanges.some(function (change) { return change.critical === true; });
    var problemButtonVisible = Boolean(activeJourneyProblemButton());
    var problemAlertKey = !journeyContext || !journeyContext.boarded && departureStopReason(currentFlight)
      ? 'journeyProblemAlertDeparted' : 'journeyProblemAlert';
    var renderSignature = JSON.stringify([currentLocale, critical, groups, problemButtonVisible, problemAlertKey]);
    document.querySelectorAll('[data-flight-change-alert]').forEach(function (alert) {
      var ownerScreen = alert.closest('.screen');
      var active = ownerScreen && ownerScreen.dataset.screen === currentScreen;
      if (!active || !groups.length) {
        alert.hidden = true;
        alert.replaceChildren();
        delete alert.dataset.renderSignature;
        alert.classList.remove('is-critical');
        return;
      }
      if (alert.dataset.renderSignature === renderSignature) return;
      alert.dataset.renderSignature = renderSignature;
      alert.replaceChildren();
      alert.hidden = false;
      alert.classList.toggle('is-critical', critical);
      groups.forEach(function (lines) {
        var group = document.createElement('div');
        group.className = 'flight-change-group';
        lines.filter(Boolean).forEach(function (text) {
          var line = document.createElement('p');
          line.textContent = text;
          group.appendChild(line);
        });
        alert.appendChild(group);
      });
      if (problemButtonVisible) {
        var problemLink = document.createElement('button');
        problemLink.type = 'button';
        problemLink.className = 'flight-change-journey-problem-link';
        problemLink.dataset.action = 'scroll-to-journey-problem';
        problemLink.textContent = t('journeyProblem');
        alert.appendChild(problemLink);
      }
      var dismissButton = document.createElement('button');
      dismissButton.type = 'button';
      dismissButton.className = 'flight-change-dismiss';
      dismissButton.dataset.action = 'dismiss-flight-change';
      var dismissLabel = localeText(
        '关闭航班变化提示',
        'Dismiss flight change notice',
        'Cerrar aviso de cambio de vuelo'
      );
      dismissButton.setAttribute('aria-label', dismissLabel);
      dismissButton.title = dismissLabel;
      var dismissMark = document.createElement('span');
      dismissMark.className = 'flight-change-dismiss-mark';
      dismissMark.setAttribute('aria-hidden', 'true');
      dismissMark.textContent = '×';
      dismissButton.appendChild(dismissMark);
      alert.appendChild(dismissButton);
    });
  }

  function flightChangeCopyContext() {
    return {
      flight: displayedFlightChangeFlight || currentFlight || {},
      formatTerminal: formatTerminalValue,
      formatGate: formatGateValue,
      formatCheckIn: formatCheckInValue,
      formatBagDrop: formatBagDropValue,
      formatBaggageBelt: formatBaggageBeltValue,
      formatDepartureTime: function (value) { return formatAtDeparture(value, true); },
      formatArrivalTime: function (value) { return formatAtArrival(value, true); },
      formatStatus: function (value) {
        return textFor(statusLabels[flightChangeStatusGroup(value)] || statusLabels.UNKNOWN);
      },
      actionTitle: function (action, locale) { return actionTextForLocale(action, 'title', locale); }
    };
  }

  function currentFlightChangeGroups() {
    return buildFlightChangeGroups(displayedFlightChanges, stepChangeAlert, currentLocale,
      flightChangeCopyContext());
  }

  function flightChangeSpeechText(groups, locale) {
    var lines = (Array.isArray(groups) ? groups : []).reduce(function (all, group) {
      return all.concat((Array.isArray(group) ? group : []).filter(Boolean));
    }, []);
    if (!lines.length) return '';
    return locale === 'zh' ? lines.join('。') + '。' : lines.join('. ') + '.';
  }

  function presentNewFlightChangeBatch() {
    var batch = pendingFlightChangeSpeechBatch;
    pendingFlightChangeSpeechBatch = null;
    var groups = batch
      ? buildFlightChangeGroups(batch.changes, batch.nextStepChange, currentLocale,
        flightChangeCopyContext())
      : currentFlightChangeGroups();
    if (!groups.length) return;
    startSpeechText(flightChangeSpeechText(groups, currentLocale), true);
  }

  function localizedAlertText(zh, en, es, locale) {
    return locale === 'zh' ? zh : locale === 'es' ? es : en;
  }

  function formattedChangeValue(formatter, value) {
    var formatted = typeof formatter === 'function' ? formatter(value) : value;
    return String(formatted == null ? value == null ? '' : value : formatted).trim();
  }

  function flightChangeGateDestination(value, context) {
    return formattedChangeValue(context && context.formatGate, value)
      .replace(/^(?:gate|puerta|登机口)\s*/i, '').replace(/\s*登机口$/i, '').trim();
  }

  function flightChangeDestinationInstruction(kind, value, locale) {
    value = String(value || '').trim();
    if (locale === 'zh') return '现在前往 ' + value;
    if (locale === 'en') {
      if (kind === 'bag-drop' || kind === 'baggage-belt') value = value.charAt(0).toLowerCase() + value.slice(1);
      return 'Go to ' + value + ' now';
    }
    if (kind === 'terminal') return 'Dirígete ahora a la ' + value;
    if (kind === 'check-in' || kind === 'baggage-belt') return 'Dirígete ahora a la ' + value.charAt(0).toLowerCase() + value.slice(1);
    if (kind === 'bag-drop') return 'Dirígete ahora al ' + value.charAt(0).toLowerCase() + value.slice(1);
    return 'Dirígete ahora a ' + value;
  }

  function flightChangeStatusGroup(value) {
    var status = String(value || '').trim().toUpperCase();
    if (/CANCEL|取消/.test(status)) return 'CANCELLED';
    if (/DIVERT|备降|備降/.test(status)) return 'DIVERTED';
    if (/RETURN|返航/.test(status)) return 'RETURNED';
    if (/BOARD|登机|登機/.test(status)) return 'BOARDING';
    if (/DEPART|起飞|起飛|离港|離港/.test(status)) return 'DEPARTED';
    if (/ARRIV|到达|到達|抵达|抵達/.test(status)) return 'ARRIVED';
    if (/DELAY|LATE|延误|延誤/.test(status)) return 'DELAYED';
    if (/SCHEDULE|EXPECTED|ON\s*TIME|ONTIME|计划|計劃|预计|預計|准点|準點|NORMAL/.test(status)) return 'NORMAL';
    return status;
  }

  function flightChangeSortRank(change) {
    var type = String(change && change.type || '');
    if ((type === 'FLIGHT_CANCELLED')
        || type === 'STATUS_CHANGED' && ['CANCELLED', 'DIVERTED', 'RETURNED']
          .indexOf(flightChangeStatusGroup(change.newValue)) >= 0) return 0;
    var ranks = {
      TERMINAL_CHANGED: 1, GATE_CHANGED: 2, CHECK_IN_COUNTER_CHANGED: 3,
      BAG_DROP_DESK_CHANGED: 4, BOARDING_TIME_CHANGED: 5,
      DEPARTURE_TIME_CHANGED: 6, DEPARTURE_DELAY_CHANGED: 6, DELAY_CHANGED: 6,
      DEPARTURE_TIME_AND_DELAY_CHANGED: 6, STATUS_CHANGED: 7,
      ARRIVAL_TIME_CHANGED: 8, ARRIVAL_DELAY_CHANGED: 8,
      ARRIVAL_TIME_AND_DELAY_CHANGED: 8, BAGGAGE_BELT_CHANGED: 9,
      DATA_FRESHNESS_CHANGED: 10
    };
    return ranks[type] == null ? 7 : ranks[type];
  }

  function mergePresentableFlightChanges(changes, nextStepChange) {
    var source = (Array.isArray(changes) ? changes : []).slice();
    var departureTime = source.find(function (change) { return change.type === 'DEPARTURE_TIME_CHANGED'; });
    var departureDelay = source.find(function (change) {
      return change.type === 'DEPARTURE_DELAY_CHANGED' || change.type === 'DELAY_CHANGED';
    });
    var arrivalTime = source.find(function (change) { return change.type === 'ARRIVAL_TIME_CHANGED'; });
    var arrivalDelay = source.find(function (change) { return change.type === 'ARRIVAL_DELAY_CHANGED'; });
    if (departureTime && departureDelay) {
      source = source.filter(function (change) { return change !== departureTime && change !== departureDelay; });
      source.push({ type: 'DEPARTURE_TIME_AND_DELAY_CHANGED', timeChange: departureTime,
        delayChange: departureDelay, critical: departureTime.critical || departureDelay.critical });
    }
    if (arrivalTime && arrivalDelay) {
      source = source.filter(function (change) { return change !== arrivalTime && change !== arrivalDelay; });
      source.push({ type: 'ARRIVAL_TIME_AND_DELAY_CHANGED', timeChange: arrivalTime,
        delayChange: arrivalDelay, critical: arrivalTime.critical || arrivalDelay.critical });
    }
    var hasDepartureGroup = source.some(function (change) {
      return ['DEPARTURE_TIME_CHANGED', 'DEPARTURE_DELAY_CHANGED', 'DELAY_CHANGED',
        'DEPARTURE_TIME_AND_DELAY_CHANGED'].indexOf(change.type) >= 0;
    });
    if (hasDepartureGroup) {
      source = source.filter(function (change) {
        return !(change.type === 'STATUS_CHANGED' && flightChangeStatusGroup(change.newValue) === 'DELAYED');
      });
    }
    var actionCode = nextStepChange && nextStepChange.action && nextStepChange.action.actionCode;
    var boardingStatus = source.some(function (change) {
      return change.type === 'STATUS_CHANGED' && flightChangeStatusGroup(change.newValue) === 'BOARDING';
    });
    var gateChange = source.some(function (change) { return change.type === 'GATE_CHANGED'; });
    var includeNextStep = Boolean(nextStepChange && nextStepChange.action);
    if (boardingStatus && actionCode === 'BOARD_NOW') includeNextStep = false;
    if (gateChange && (actionCode === 'GO_TO_GATE' || actionCode === 'BOARD_NOW')) includeNextStep = false;
    return { changes: source.sort(function (left, right) {
      return flightChangeSortRank(left) - flightChangeSortRank(right);
    }), includeNextStep: includeNextStep };
  }

  function buildFlightChangeGroups(changes, nextStepChange, locale, context) {
    var merged = mergePresentableFlightChanges(changes, nextStepChange);
    var groups = merged.changes.map(function (change) {
      return flightChangeAlertCopy(change, locale, context);
    });
    if (merged.includeNextStep) groups.push(nextStepChangeAlertCopy(nextStepChange, locale, context));
    return groups;
  }

  function flightChangeAlertCopy(change, locale, context) {
    change = change || {};
    context = context || {};
    var type = String(change.type || '');
    var oldValue = String(change.oldValue || '').trim();
    var newValue = String(change.newValue || '').trim();
    var hadOldValue = Boolean(oldValue && oldValue !== '—');
    if (type === 'INITIAL_DELAY_ANNOUNCED') return [
      localizedAlertText('航班已公布延误', 'A flight delay has been announced',
        'Se ha anunciado un retraso del vuelo', locale),
      localizedAlertText('当前预计晚点 ', 'Current estimated delay: ', 'Retraso estimado actual: ', locale)
        + newValue + localizedAlertText(' 分钟', ' minutes', ' minutos', locale)
    ];
    if (type === 'GATE_CHANGED') return [
      localizedAlertText(hadOldValue ? '登机口已更改' : '登机口已公布',
        hadOldValue ? 'Gate changed' : 'Gate announced',
        hadOldValue ? 'La puerta de embarque ha cambiado' : 'Se ha anunciado la puerta de embarque', locale),
      flightChangeDestinationInstruction('gate', flightChangeGateDestination(newValue, context), locale)
    ];
    if (type === 'TERMINAL_CHANGED') return [
      localizedAlertText(hadOldValue ? '航站楼已更改' : '航站楼已公布',
        hadOldValue ? 'Terminal changed' : 'Terminal announced',
        hadOldValue ? 'La terminal ha cambiado' : 'Se ha anunciado la terminal', locale),
      flightChangeDestinationInstruction('terminal', formattedChangeValue(context.formatTerminal, newValue), locale)
    ];
    if (type === 'CHECK_IN_COUNTER_CHANGED') return [
      localizedAlertText(hadOldValue ? '值机柜台已更改' : '值机柜台已公布',
        hadOldValue ? 'Check-in counter changed' : 'Check-in counter announced',
        hadOldValue ? 'El mostrador de facturación ha cambiado' : 'Se ha anunciado el mostrador de facturación', locale),
      flightChangeDestinationInstruction('check-in', formattedChangeValue(context.formatCheckIn, newValue), locale)
    ];
    if (type === 'BAG_DROP_DESK_CHANGED') return [
      localizedAlertText(hadOldValue ? '行李托运柜台已更改' : '行李托运柜台已公布',
        hadOldValue ? 'Bag drop counter changed' : 'Bag drop counter announced',
        hadOldValue ? 'El mostrador de entrega de equipaje ha cambiado' : 'Se ha anunciado el mostrador de entrega de equipaje', locale),
      flightChangeDestinationInstruction('bag-drop', formattedChangeValue(context.formatBagDrop, newValue), locale)
    ];
    if (type === 'BAGGAGE_BELT_CHANGED') return [
      localizedAlertText(hadOldValue ? '行李转盘已更改' : '行李转盘已公布',
        hadOldValue ? 'Baggage belt changed' : 'Baggage belt announced',
        hadOldValue ? 'La cinta de equipaje ha cambiado' : 'Se ha anunciado la cinta de equipaje', locale),
      flightChangeDestinationInstruction('baggage-belt', formattedChangeValue(context.formatBaggageBelt, newValue), locale)
    ];
    if (type === 'BOARDING_TIME_CHANGED') return [
      localizedAlertText(hadOldValue ? '登机时间已更改' : '登机时间已公布',
        hadOldValue ? 'Boarding time changed' : 'Boarding time announced',
        hadOldValue ? 'La hora de embarque ha cambiado' : 'Se ha anunciado la hora de embarque', locale),
      localizedAlertText(hadOldValue ? '新的登机时间：' : '请在 ', hadOldValue ? 'New boarding time: ' : 'Go to the gate at ',
        hadOldValue ? 'Nueva hora de embarque: ' : 'Ve a la puerta a las ', locale)
        + formattedChangeValue(context.formatDepartureTime, newValue)
        + (!hadOldValue && locale === 'zh' ? ' 前往登机口' : '')
    ];
    if (type === 'DEPARTURE_TIME_CHANGED') return [
      localizedAlertText('起飞时间已更改', 'Departure time changed', 'La hora de salida ha cambiado', locale),
      localizedAlertText('新的预计起飞时间：', 'New estimated departure: ', 'Nueva hora estimada de salida: ', locale)
        + formattedChangeValue(context.formatDepartureTime, newValue)
    ];
    if (type === 'DEPARTURE_DELAY_CHANGED' || type === 'DELAY_CHANGED') return delayChangeAlertCopy(change, locale, false);
    if (type === 'DEPARTURE_TIME_AND_DELAY_CHANGED') {
      var departureDelay = Number(change.delayChange && change.delayChange.newValue);
      var departureDelayText = departureDelay === 0
        ? localizedAlertText('当前没有预计延误', 'no expected delay', 'sin retraso estimado', locale)
        : localizedAlertText('预计晚点 ' + departureDelay + ' 分钟', 'estimated delay: ' + departureDelay + ' minutes',
          'retraso estimado: ' + departureDelay + ' minutos', locale);
      return [localizedAlertText('起飞时间和延误信息已更改', 'Departure time and delay changed',
        'La hora de salida y el retraso han cambiado', locale),
      localizedAlertText('新的预计起飞时间：', 'New estimated departure: ', 'Nueva hora estimada de salida: ', locale)
        + formattedChangeValue(context.formatDepartureTime, change.timeChange && change.timeChange.newValue)
        + (locale === 'zh' ? '（' + departureDelayText + '）' : ' (' + departureDelayText + ')')];
    }
    if (type === 'ARRIVAL_TIME_CHANGED') return [
      localizedAlertText('预计到达时间已更改', 'Expected arrival changed', 'La hora prevista de llegada ha cambiado', locale),
      localizedAlertText('新的预计到达时间：', 'New estimated arrival: ', 'Nueva hora estimada de llegada: ', locale)
        + formattedChangeValue(context.formatArrivalTime, newValue)
    ];
    if (type === 'ARRIVAL_DELAY_CHANGED') return delayChangeAlertCopy(change, locale, true);
    if (type === 'ARRIVAL_TIME_AND_DELAY_CHANGED') {
      var arrivalDelay = Number(change.delayChange && change.delayChange.newValue);
      var arrivalDelayText = arrivalDelay === 0
        ? localizedAlertText('当前没有预计到达延误', 'no expected arrival delay', 'sin retraso estimado de llegada', locale)
        : localizedAlertText('预计晚到 ' + arrivalDelay + ' 分钟', 'estimated delay: ' + arrivalDelay + ' minutes',
          'retraso estimado: ' + arrivalDelay + ' minutos', locale);
      return [localizedAlertText('到达时间和延误信息已更改', 'Arrival time and delay changed',
        'La hora de llegada y el retraso han cambiado', locale),
      localizedAlertText('新的预计到达时间：', 'New estimated arrival: ', 'Nueva hora estimada de llegada: ', locale)
        + formattedChangeValue(context.formatArrivalTime, change.timeChange && change.timeChange.newValue)
        + (locale === 'zh' ? '（' + arrivalDelayText + '）' : ' (' + arrivalDelayText + ')')];
    }
    if (type === 'DATA_FRESHNESS_CHANGED') {
      return String(newValue).toUpperCase() === 'STALE'
        ? [localizedAlertText('实时航班信息已过期', 'Live flight information is out of date',
          'La información del vuelo está desactualizada', locale),
        localizedAlertText('请查看机场屏幕，或向工作人员确认', 'Check the airport screens or ask a member of staff',
          'Consulta las pantallas del aeropuerto o pregunta al personal', locale)]
        : [localizedAlertText('实时航班信息已恢复更新', 'Live flight information is updating again',
          'La información del vuelo vuelve a estar actualizada', locale),
        localizedAlertText('请按当前显示的信息继续', 'Continue using the currently displayed information',
          'Continúa según la información que se muestra', locale)];
    }
    if (type === 'FLIGHT_CANCELLED') return statusChangeAlertCopy('CANCELLED', newValue, locale, context);
    if (type === 'STATUS_CHANGED') return statusChangeAlertCopy(flightChangeStatusGroup(newValue), newValue, locale, context);
    return [localizedAlertText(change.titleZh || '重要航班信息已更改',
      change.titleEn || 'Important flight information changed', spanishFlightChange(change), locale), newValue];
  }

  function delayChangeAlertCopy(change, locale, arrival) {
    var oldDelay = Number(change.oldValue);
    var newDelay = Number(change.newValue);
    if (newDelay === 0) return [
      localizedAlertText(arrival ? '到达延误已解除' : '航班延误已解除',
        arrival ? 'Arrival delay cleared' : 'Flight delay cleared',
        arrival ? 'El retraso de llegada ha finalizado' : 'El retraso del vuelo ha finalizado', locale),
      localizedAlertText(arrival ? '当前没有预计到达延误' : '当前没有预计延误',
        arrival ? 'There is currently no expected arrival delay' : 'There is currently no expected delay',
        arrival ? 'Actualmente no hay retraso previsto de llegada' : 'Actualmente no hay retraso previsto', locale)
    ];
    var increased = !Number.isFinite(oldDelay) || newDelay > oldDelay;
    return [localizedAlertText(arrival ? increased ? '到达延误增加' : '到达延误缩短'
      : increased ? '航班延误增加' : '航班延误缩短',
    arrival ? increased ? 'Arrival delay increased' : 'Arrival delay reduced'
      : increased ? 'Flight delay increased' : 'Flight delay reduced',
    arrival ? increased ? 'El retraso de llegada ha aumentado' : 'El retraso de llegada se ha reducido'
      : increased ? 'El retraso del vuelo ha aumentado' : 'El retraso del vuelo se ha reducido', locale),
    localizedAlertText(arrival ? '当前预计晚到 ' : '当前预计晚点 ',
      arrival ? 'Currently expected to arrive ' : 'Currently expected ',
      arrival ? 'Retraso previsto de llegada: ' : 'Retraso previsto actual: ', locale)
      + newDelay + localizedAlertText(' 分钟', arrival ? ' minutes late' : ' minutes late', ' minutos', locale)];
  }

  function statusChangeAlertCopy(group, newValue, locale, context) {
    var copy = {
      CANCELLED: ['航班已取消', '请立即向工作人员求助', 'Flight cancelled', 'Ask airport staff for help now', 'Vuelo cancelado', 'Pide ayuda ahora al personal del aeropuerto'],
      DIVERTED: ['航班已备降', '请立即向机组或机场工作人员确认下一步', 'Flight diverted', 'Ask the crew or airport staff what to do next', 'El vuelo ha sido desviado', 'Pregunta a la tripulación o al personal del aeropuerto qué hacer ahora'],
      RETURNED: ['航班已返航', '请听从机组安排并向工作人员求助', 'Flight has returned', "Follow the crew's instructions and ask staff for help", 'El vuelo ha regresado', 'Sigue las instrucciones de la tripulación y pide ayuda al personal'],
      DEPARTED: ['航班已经起飞', '如果你还没有登机，请立即求助', 'Flight has departed', 'If you have not boarded, ask for help immediately', 'El vuelo ya ha despegado', 'Si no has embarcado, pide ayuda inmediatamente'],
      ARRIVED: ['航班已经到达', '下飞机后请听从机组和机场指引', 'Flight has arrived', 'After leaving the aircraft, follow crew and airport instructions', 'El vuelo ha llegado', 'Al bajar del avión, sigue las indicaciones de la tripulación y del aeropuerto'],
      DELAYED: ['航班状态变为延误', '请查看新的起飞时间', 'Flight status changed to delayed', 'Check the new departure time', 'El vuelo aparece con retraso', 'Consulta la nueva hora de salida'],
      NORMAL: ['航班状态已更新', '请按当前显示的时间继续', 'Flight status updated', 'Continue using the currently displayed time', 'El estado del vuelo se ha actualizado', 'Continúa según la hora que se muestra']
    };
    if (group === 'BOARDING') {
      var gate = flightChangeGateDestination(context && context.flight && context.flight.gate, context);
      return [localizedAlertText('航班已开始登机', 'Boarding has started', 'Ha comenzado el embarque', locale),
        gate ? flightChangeDestinationInstruction('gate', gate, locale)
          : localizedAlertText('现在前往登机口并查看机场屏幕', 'Go to the gate now and check the airport screens',
            'Ve ahora a la puerta y consulta las pantallas del aeropuerto', locale)];
    }
    var values = copy[group];
    if (values) return [localizedAlertText(values[0], values[2], values[4], locale),
      localizedAlertText(values[1], values[3], values[5], locale)];
    return [localizedAlertText('航班状态已更改', 'Flight status changed', 'El estado del vuelo ha cambiado', locale),
      localizedAlertText('当前状态：', 'Current status: ', 'Estado actual: ', locale)
        + formattedChangeValue(context.formatStatus, newValue)];
  }

  function nextStepChangeAlertCopy(nextStepChange, locale, context) {
    var action = nextStepChange && nextStepChange.action || {};
    var title = context && typeof context.actionTitle === 'function'
      ? context.actionTitle(action, locale) : String(action.title || action.titleZh || action.titleEn || '—');
    return [localizedAlertText('乘机步骤已更新', 'Your next step has changed', 'Tu siguiente paso ha cambiado', locale), title];
  }

  function displayChangeValue(change, value) {
    if (!value) return '—';
    if (change.type === 'DEPARTURE_TIME_CHANGED' || change.type === 'BOARDING_TIME_CHANGED') {
      return formatAtDeparture(value, true);
    }
    if (change.type === 'ARRIVAL_TIME_CHANGED') return formatAtArrival(value, true);
    if (change.type === 'DELAY_CHANGED' || change.type === 'DEPARTURE_DELAY_CHANGED'
        || change.type === 'ARRIVAL_DELAY_CHANGED') return value + localeText(' 分钟', ' min', ' min');
    return value;
  }

  function spanishFlightChange(change) {
    var labels = {
      GATE_CHANGED: 'La puerta de embarque ha cambiado', TERMINAL_CHANGED: 'La terminal ha cambiado',
      CHECK_IN_COUNTER_CHANGED: 'El mostrador de facturación ha cambiado',
      BAG_DROP_DESK_CHANGED: 'El mostrador de entrega de equipaje ha cambiado',
      BOARDING_TIME_CHANGED: 'La hora de embarque ha cambiado',
      DEPARTURE_TIME_CHANGED: 'La hora de salida ha cambiado',
      DEPARTURE_DELAY_CHANGED: 'El retraso de salida ha cambiado', DELAY_CHANGED: 'El retraso ha cambiado',
      STATUS_CHANGED: 'El estado del vuelo ha cambiado', ARRIVAL_TIME_CHANGED: 'La hora de llegada ha cambiado',
      ARRIVAL_DELAY_CHANGED: 'El retraso de llegada ha cambiado',
      BAGGAGE_BELT_CHANGED: 'La cinta de equipaje ha cambiado',
      DATA_FRESHNESS_CHANGED: 'La vigencia de la información del vuelo ha cambiado',
      FLIGHT_CANCELLED: 'El vuelo está cancelado'
    };
    return labels[change && change.type] || 'La información importante del vuelo ha cambiado';
  }

  function latestFlightChange(type) {
    var changes = currentFlight && Array.isArray(currentFlight.latestChanges) ? currentFlight.latestChanges : [];
    return changes.find(function (change) { return change.type === type; }) || null;
  }

  function recordJourneyEvent(type, options) {
    var settings = options || {};
    var occurredAt = settings.occurredAt || calibratedNowIso();
    var id = settings.stableId || type + ':' + occurredAt;
    if (journeyEvents.some(function (event) { return event.id === id; })) return;
    journeyEvents.push({
      id: id,
      type: type,
      field: settings.field || null,
      oldValue: settings.oldValue == null ? null : settings.oldValue,
      newValue: settings.newValue == null ? null : settings.newValue,
      params: settings.params && typeof settings.params === 'object' ? settings.params : {},
      occurredAt: occurredAt,
      reversible: Boolean(settings.reversible)
    });
    renderJourneyTimeline();
  }

  function recordLiveChanges(changes) {
    presentableFlightChanges({ latestChanges: Array.isArray(changes) ? changes : [] }).forEach(function (change) {
      recordJourneyEvent(change.type, {
        field: change.field || change.type,
        oldValue: change.oldValue,
        newValue: change.newValue,
        occurredAt: change.detectedAt || calibratedNowIso(),
        stableId: 'LIVE:' + change.type + ':' + (change.oldValue || '') + ':'
          + (change.newValue || '') + ':' + (change.detectedAt || '')
      });
    });
  }

  function journeyEventPresentation(event) {
    event = event || {};
    var params = event.params || {};
    var languageIndex = currentLocale === 'zh' ? 0 : currentLocale === 'es' ? 2 : 1;
    if (PRESENTABLE_FLIGHT_CHANGE_TYPES.indexOf(event.type) >= 0
        || event.type === 'INITIAL_DELAY_ANNOUNCED') {
      var lines = flightChangeAlertCopy({ type: event.type, oldValue: event.oldValue,
        newValue: event.newValue }, currentLocale, flightChangeCopyContext()).filter(Boolean);
      return legacyJourneyEventPresentation(event, {
        title: lines[0] || localeText('航班信息已更新', 'Flight information updated', 'Información del vuelo actualizada'),
        detail: lines.slice(1).join(' · ')
      });
    }
    if (event.type === 'STEP_UPDATED') {
      var actionCopy = standardActionCopy[params.actionCode];
      var storedTitle = currentLocale === 'zh' ? params.titleZh
        : currentLocale === 'es' ? params.titleEs : params.titleEn;
      var actionTitle = storedTitle || (actionCopy ? actionCopy.title[languageIndex] : '');
      return legacyJourneyEventPresentation(event, {
        title: localeText('乘机步骤已更新', 'Journey step updated', 'Paso del viaje actualizado'),
        detail: actionTitle
      });
    }
    if (event.type === 'CHECK_IN_STATUS_CONFIRMED') {
      var checkInLabels = {
        yes: ['已确认：已经值机', 'Confirmed: checked in', 'Confirmado: ya has facturado'],
        no: ['已确认：还没有值机', 'Confirmed: not checked in', 'Confirmado: aún no has facturado'],
        unsure: ['已确认：不确定是否值机', 'Confirmed: not sure about check-in', 'Confirmado: no sabes si has facturado']
      };
      return legacyJourneyEventPresentation(event, {
        title: (checkInLabels[params.answer] || ['值机状态已确认', 'Check-in status confirmed', 'Estado de facturación confirmado'])[languageIndex], detail: ''
      });
    }
    if (event.type === 'BAGGAGE_ANSWERED' || event.type === 'BAGGAGE_UNSURE') {
      var baggageLabels = {
        yes: ['已确认：有托运行李', 'Confirmed: checked baggage', 'Confirmado: tienes equipaje facturado'],
        no: ['已确认：没有托运行李', 'Confirmed: no checked baggage', 'Confirmado: no tienes equipaje facturado'],
        unsure: ['已确认：不确定行李情况', 'Confirmed: not sure about baggage', 'Confirmado: no sabes si debes facturar equipaje']
      };
      return legacyJourneyEventPresentation(event, {
        title: (baggageLabels[params.answer] || ['行李情况已确认', 'Baggage status confirmed', 'Equipaje confirmado'])[languageIndex], detail: ''
      });
    }
    if (event.type === 'ACTION_COMPLETED' || String(event.type || '').indexOf('ACTION_COMPLETED_') === 0) {
      var code = params.actionCode || String(event.type || '').replace('ACTION_COMPLETED_', '');
      var completedLabels = {
        CHECK_IN: ['已完成值机', 'Check-in completed', 'Facturación completada'],
        BAG_DROP: ['已托运行李', 'Baggage dropped off', 'Equipaje entregado'],
        SECURITY: ['已通过安检', 'Security completed', 'Control de seguridad completado'],
        GO_TO_GATE: ['已到达登机口', 'Gate reached', 'Has llegado a la puerta'],
        BOARD_NOW: ['已登机', 'Boarded', 'Has embarcado'],
        IN_FLIGHT: ['已下飞机', 'Left the aircraft', 'Has bajado del avión'],
        BAGGAGE_CLAIM: ['已领取行李', 'Baggage collected', 'Equipaje recogido'],
        EXIT_AIRPORT: ['已到达出口', 'Exit reached', 'Has llegado a la salida']
      };
      return legacyJourneyEventPresentation(event, {
        title: (completedLabels[code] || ['已完成当前步骤', 'Step completed', 'Paso completado'])[languageIndex], detail: ''
      });
    }
    var labels = {
      DOCUMENT_EXTRACTED: ['已读取旅行文件', 'Travel document read', 'Documento de viaje leído'],
      FLIGHT_CONFIRMED: ['已确认航班', 'Flight confirmed', 'Vuelo confirmado'],
      ASSISTANCE_REQUESTED: ['已请求工作人员帮助', 'Staff help requested', 'Ayuda del personal solicitada'],
      CONFLICT_CONFIRMED_WITH_STAFF: ['已向工作人员核对信息', 'Details checked with staff', 'Datos comprobados con el personal']
    };
    return legacyJourneyEventPresentation(event, {
      title: (labels[event.type] || ['行程已更新', 'Journey updated', 'Viaje actualizado'])[languageIndex],
      detail: params.flightNumber ? formatFlightNumber(params.flightNumber) : ''
    });
  }

  function legacyJourneyEventPresentation(event, presentation) {
    var legacyTitle = currentLocale === 'zh' ? event.titleZh
      : currentLocale === 'es' ? event.titleEs : event.titleEn;
    var hasLegacyTitle = typeof legacyTitle === 'string' && legacyTitle.trim();
    var hasLegacyDetails = event.details !== undefined && event.details !== null;
    if (!hasLegacyTitle && !hasLegacyDetails) return presentation;
    return {
      title: hasLegacyTitle ? legacyTitle : presentation.title,
      detail: hasLegacyDetails ? String(event.details) : presentation.detail
    };
  }

  function renderJourneyTimeline() {
    var list = document.querySelector('[data-journey-timeline]');
    if (!list) return;
    list.replaceChildren();
    if (!journeyEvents.length) {
      var empty = document.createElement('li');
      empty.textContent = localeText('还没有行程事件。', 'No journey events yet.', 'Aún no hay eventos del viaje.');
      list.appendChild(empty);
      return;
    }
    journeyEvents.forEach(function (event) {
      var item = document.createElement('li');
      var presentation = journeyEventPresentation(event);
      var title = document.createElement('strong');
      title.textContent = presentation.title;
      var time = document.createElement('time');
      time.dateTime = event.occurredAt;
      time.textContent = formatAtDeparture(event.occurredAt, true);
      item.append(title, time);
      if (presentation.detail) {
        var detail = document.createElement('span');
        detail.className = 'journey-event-detail';
        detail.textContent = presentation.detail;
        item.appendChild(detail);
      }
      list.appendChild(item);
    });
  }

  function announceLatestChangesOr(fallback) {
    if (!displayedFlightChanges.length && !stepChangeAlert) announce(fallback);
  }

  function setRefreshStatus(code, details) {
    refreshStatusState = code ? { code: code, details: details || {} } : null;
    renderRefreshStatus();
  }

  function restorableRefreshStatus(state) {
    var codes = ['CHECKING', 'CHECKING_ARRIVAL', 'REFRESH_SUCCESS_LATEST_RETAINED',
      'DOCUMENT_FALLBACK_RETAINED', 'REFRESH_FAILED_LATEST_RETAINED',
      'REFRESH_FAILED_STEP_RETAINED', 'ARRIVAL_REFRESH_SUCCESS',
      'ARRIVAL_REFRESH_FAILED', 'OFFLINE_SNAPSHOT'];
    return state && codes.indexOf(state.code) >= 0 ? state : null;
  }

  function renderRefreshStatus() {
    var state = refreshStatusState;
    var text = '';
    if (state) {
      var messages = {
        CHECKING: ['正在检查新的实时信息……', 'Checking for new live information…', 'Comprobando información nueva…'],
        CHECKING_ARRIVAL: ['正在查询行李转盘……', 'Checking the baggage belt…', 'Consultando la cinta de equipaje…'],
        REFRESH_SUCCESS_LATEST_RETAINED: ['实时检查完成，正在显示最近一次成功取得的信息。', 'Live check complete. The latest successful information is shown.', 'Consulta completada. Se muestra la información más reciente.'],
        DOCUMENT_FALLBACK_RETAINED: ['实时状态仍暂未更新，继续显示上传文件中的信息。', 'Live status is still unavailable. Information from the uploaded document remains visible.', 'El estado en directo sigue sin estar disponible. Se mantiene la información del documento subido.'],
        REFRESH_FAILED_LATEST_RETAINED: ['刷新失败，已保留最近一次成功取得的航班信息。', 'Refresh failed. The latest successful flight information is retained.', 'No se pudo actualizar. Se mantiene la última información disponible.'],
        REFRESH_FAILED_STEP_RETAINED: ['刷新失败，已保留航班信息和当前步骤。', 'Refresh failed. Your flight information and current step are retained.', 'No se pudo actualizar. El vuelo y el paso actual se mantienen.'],
        ARRIVAL_REFRESH_SUCCESS: ['到达信息已刷新。', 'Arrival information refreshed.', 'Información de llegada actualizada.'],
        ARRIVAL_REFRESH_FAILED: ['到达信息刷新失败，已保存的行程仍然可用。', 'Arrival refresh failed. Your saved journey is still available.', 'No se pudo actualizar la llegada. El viaje guardado sigue disponible.'],
        OFFLINE_SNAPSHOT: ['当前离线，正在显示已保存的行程。', 'You are offline. Showing the saved journey.', 'Sin conexión. Mostrando el viaje guardado.']
      };
      text = textFor(messages[state.code] || ['', '']);
    }
    setAll('[data-refresh-status]', text);
  }

  function renderConnectivityStatus() {
    var offline = !navigator.onLine || Boolean(platform && !platform.isOnline()) || restoredFromOffline;
    document.querySelectorAll('[data-offline-status]').forEach(function (status) {
      status.hidden = !offline;
      status.textContent = offline
        ? localeText('显示最后保存的信息，请联网重新核验。', 'Showing saved information. Reconnect to verify.', 'Mostrando información guardada. Conéctate para verificar.')
          + (currentFlight && (currentFlight.dataRetrievedAt || currentFlight.dataLastUpdatedAt)
            ? ' ' + formatRetrieved(currentFlight.dataRetrievedAt || currentFlight.dataLastUpdatedAt) : '')
        : '';
    });
    updateRefreshControls();
  }

  function updateRefreshControls() {
    var disabled = !navigator.onLine || Boolean(flightRequestController);
    document.querySelectorAll('[data-action="refresh-flight"], [data-action="refresh-arrival"]').forEach(function (refreshButton) {
      if (!refreshButton.hidden) refreshButton.disabled = disabled;
    });
    var waitButton = currentAction && currentAction.actionCode === 'WAIT'
      ? document.querySelector('[data-complete-action]') : null;
    if (waitButton) waitButton.disabled = disabled;
  }

  function setDeparturePassedStaffContent(reason) {
    var flight = formatFlightNumber(currentFlight && currentFlight.flightNumber) || '—';
    if (reason === 'FLIGHT_CANCELLED') {
      setStaffContent(
        '航班已取消，请告诉我下一步', 'My flight is cancelled. What should I do?',
        '我的航班 ' + flight + ' 显示已经取消。请问我现在应该怎么办？',
        'My flight ' + flight + ' is shown as cancelled. What should I do now?',
        'Mi vuelo está cancelado. ¿Qué debo hacer?',
        'Mi vuelo ' + flight + ' aparece cancelado. ¿Qué debo hacer ahora?');
      return;
    }
    setStaffContent(
      '航班已经离港，请帮我改签或重新预订',
      'This flight has departed. Please help me rebook',
      '航班 ' + flight + '，状态：已离港。请问能否为我改签或重新预订，并告诉我下一步应该去哪里？',
      'Flight ' + flight + ', status: departed. Can you rebook me or make a new reservation, and tell me where to go next?',
      'Este vuelo ya ha salido. Ayúdeme a cambiar la reserva',
      'Vuelo ' + flight + ', estado: ya ha salido. ¿Puede cambiar mi reserva o hacer una nueva y decirme adónde debo ir ahora?');
  }

  function setStaffForAction(action) {
    var flight = formatFlightNumber(currentFlight && currentFlight.flightNumber);
    var gate = currentFlight && currentFlight.gate;
    var reason = action.reason || '';
    if (isDepartureStopReason(reason)) {
      setDeparturePassedStaffContent(reason);
      return;
    }
    var content = {
      CHECK_IN: ['请帮我找到值机位置', 'Please help me find check-in',
        '请问 ' + flight + ' 应该在哪里办理值机？值机或行李托运区域在哪里？',
        'Where should I check in for ' + flight + '? Where is the check-in or baggage area?',
        'Ayúdeme a encontrar la zona de facturación',
        '¿Dónde debo facturar para el vuelo ' + flight + '? ¿Dónde está la zona de facturación o entrega de equipaje?'],
      BAG_DROP: ['请帮我找到行李托运位置', 'Please help me find bag drop',
        '请问我的托运行李应该交到哪一个柜台或区域？',
        'Which counter or area should I use for my checked baggage?',
        'Ayúdeme a encontrar la entrega de equipaje',
        '¿En qué mostrador o zona debo entregar el equipaje facturado?'],
      SECURITY: ['请帮我找到正确的安检入口', 'Please help me find the correct security entrance',
        '请问前往 ' + flight + ' 登机口应该从哪一个安检入口进入？',
        'Which security entrance should I use to reach the gate for ' + flight + '?',
        'Ayúdeme a encontrar el control de seguridad correcto',
        '¿Qué control de seguridad debo usar para llegar a la puerta del vuelo ' + flight + '?'],
      GO_TO_GATE: ['请帮我确认登机口和路线', 'Please confirm my gate and route',
        '请问 ' + flight + ' 现在的登机口是 ' + (gate || '尚未确认') + ' 吗？我应该怎么走？',
        'Is the current gate for ' + flight + ' ' + (gate || 'not yet confirmed') + '? How should I get there?',
        'Ayúdeme a confirmar la puerta y la ruta',
        '¿La puerta actual del vuelo ' + flight + ' es ' + (gate || 'aún no confirmada') + '? ¿Cómo llego hasta allí?'],
      WAIT: ['请帮我确认是否已经开始登机', 'Please confirm whether boarding has started',
        '请问 ' + flight + ' 现在是否已经开始登机？',
        'Has boarding started for ' + flight + '?',
        'Ayúdeme a confirmar si ha comenzado el embarque',
        '¿Ha comenzado el embarque del vuelo ' + flight + '?'],
      BOARD_NOW: ['请帮我确认现在是否可以登机', 'Please confirm whether I can board now',
        '请问 ' + flight + ' 现在是否可以登机？',
        'Can I board ' + flight + ' now?',
        'Ayúdeme a confirmar si puedo embarcar',
        '¿Puedo embarcar ahora en el vuelo ' + flight + '?'],
      IN_FLIGHT: ['请帮我确认抵达后的路线', 'Please help me confirm what to do after arrival',
        journeyContext && journeyContext.hasCheckedBaggage
          ? '我有托运行李。抵达后，请告诉我怎样找到行李提取屏幕。'
          : '我没有托运行李。抵达后，请告诉我怎样前往到达出口。',
        journeyContext && journeyContext.hasCheckedBaggage
          ? 'I have checked baggage. After arrival, please help me find the baggage reclaim screens.'
          : 'I do not have checked baggage. After arrival, please help me find Arrivals / Exit.',
        'Ayúdeme a confirmar qué debo hacer después de llegar',
        journeyContext && journeyContext.hasCheckedBaggage
          ? 'Tengo equipaje facturado. Después de llegar, ayúdeme a encontrar las pantallas de recogida de equipaje.'
          : 'No tengo equipaje facturado. Después de llegar, ayúdeme a encontrar Llegadas / Salida.'],
      BAGGAGE_CLAIM: ['请帮我找到到达行李转盘', 'Please help me find the arrival baggage belt',
        '我已下飞机。请问 ' + flight + ' 的托运行李在哪一个转盘领取？',
        'I have left the aircraft. Which baggage belt has the checked baggage from ' + flight + '?',
        'Ayúdeme a encontrar la cinta de equipaje',
        'Ya he bajado del avión. ¿En qué cinta se recoge el equipaje facturado del vuelo ' + flight + '?'],
      EXIT_AIRPORT: ['请帮我找到到达出口', 'Please help me find the arrivals exit',
        '我已经完成到达后的必要步骤。请问到达出口应该怎么走？',
        'I have completed the required arrival steps. How do I reach Arrivals / Exit?',
        'Ayúdeme a encontrar la salida de llegadas',
        'Ya he completado los pasos necesarios de llegada. ¿Cómo llego a Llegadas / Salida?'],
      ASK_FOR_HELP: ['请帮助我确认下一步', 'Please help me confirm what to do next',
        '请帮我核对 ' + flight + ' 的最新信息，并告诉我现在只需要做哪一件事。',
        'Please check the latest information for ' + flight + ' and tell me the one thing I should do now.',
        'Ayúdeme a confirmar el siguiente paso',
        'Compruebe la información más reciente del vuelo ' + flight + ' y dígame qué debo hacer ahora.']
    }[action.actionCode];
    content = content || ['请帮我完成下一步', 'Please help with my next step',
      '请帮我确认现在应该做什么。', 'Please help me confirm what I should do now.',
      'Ayúdeme con el siguiente paso', 'Ayúdeme a confirmar qué debo hacer ahora.'];
    setStaffContent(content[0], content[1], content[2], content[3], content[4], content[5]);
  }

  function setStaffContent(titleZh, titleEn, bodyZh, bodyEn, titleEs, bodyEs) {
    staffContent = {
      titleZh: titleZh, titleEn: titleEn,
      titleEs: titleEs || 'Ayúdeme con el siguiente paso',
      bodyZh: bodyZh, bodyEn: bodyEn,
      bodyEs: bodyEs || 'Necesito ayuda con mi vuelo. Compruebe la información y dígame adónde debo ir ahora.'
    };
    renderStaffCard();
  }

  function setBaggageStaffContent() {
    setStaffContent(
      '请帮我确认行李', 'Please help me check my baggage',
      '您好，我不确定这件行李是否需要托运。如果需要托运，请告诉我应该去哪一个柜台或区域。',
      'Hello. I am not sure whether this bag needs to be checked. If it does, please tell me which counter or area I should go to.',
      'Ayúdeme a comprobar el equipaje',
      'No sé si este equipaje debe facturarse. Si es así, dígame a qué mostrador o zona debo ir.'
    );
  }

  function fieldStaffReading(context, locale) {
    if (!context || !context.currentReading) return '';
    if (context.field === 'departure_date' && locale === 'en' && currentLocale !== 'en') return '';
    var value = context.field === 'departure_date'
      ? formatStaffDate(context.currentReading)
      : formatFlightNumber(context.currentReading);
    if (!value || value === '—') return '';
    return (locale === 'zh' ? '当前识别：' : locale === 'es' ? 'Lectura actual: ' : 'Current reading: ') + value;
  }

  function setStaffParagraph(element, body, reading) {
    if (!element) return;
    element.replaceChildren(document.createTextNode(body));
    if (reading) {
      element.append(document.createElement('br'), document.createElement('br'),
        document.createTextNode(reading));
    }
  }

  function renderFieldStaffCard(context) {
    var content = fieldStaffContent[context.field];
    if (!content) return false;
    var instructionEn = document.querySelector('[data-staff-instruction-en]');
    var instructionPrimary = document.querySelector('[data-staff-instruction-primary]');
    if (instructionEn) instructionEn.hidden = true;
    if (instructionPrimary) {
      instructionPrimary.textContent = t('staffCardInstruction');
      instructionPrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
      instructionPrimary.hidden = false;
    }
    var closeButton = document.querySelector('[data-action="close-staff"]');
    if (closeButton) closeButton.setAttribute('aria-label', t('closeStaffAria'));
    var completedPanel = document.querySelector('[data-staff-completed]');
    if (completedPanel) completedPanel.hidden = true;
    var flightStrip = document.querySelector('[data-staff-flight-strip]');
    if (flightStrip) flightStrip.hidden = true;
    var facts = document.querySelector('[data-staff-facts]');
    if (facts) facts.replaceChildren();
    var titlePrimary = document.querySelector('[data-staff-title-primary]');
    var titleEn = document.querySelector('[data-staff-title-en]');
    var bodyPrimary = document.querySelector('[data-staff-primary]');
    var bodyEn = document.querySelector('[data-staff-en]');
    var primarySuffix = currentLocale === 'zh' ? 'Zh' : currentLocale === 'es' ? 'Es' : 'En';
    titlePrimary.textContent = content['title' + primarySuffix];
    titlePrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    titleEn.textContent = content.titleEn;
    titleEn.hidden = currentLocale === 'en';
    setStaffParagraph(bodyPrimary, content['body' + primarySuffix],
      fieldStaffReading(context, currentLocale));
    bodyPrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    setStaffParagraph(bodyEn, content.bodyEn, fieldStaffReading(context, 'en'));
    bodyEn.hidden = currentLocale === 'en';
    return true;
  }

  function renderStaffCard() {
    var cardResourceButton = document.querySelector('[data-staff-contact-button]');
    if (cardResourceButton) cardResourceButton.hidden = !currentAirportResourcesContext();
    if (fieldStaffHelpContext && renderFieldStaffCard(fieldStaffHelpContext)) return;
    var instructionEn = document.querySelector('[data-staff-instruction-en]');
    var instructionPrimary = document.querySelector('[data-staff-instruction-primary]');
    if (instructionEn) {
      instructionEn.textContent = translations.en.staffCardInstruction;
      instructionEn.hidden = true;
    }
    if (instructionPrimary) {
      instructionPrimary.textContent = t('staffCardInstruction');
      instructionPrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
      instructionPrimary.hidden = false;
    }
    var closeButton = document.querySelector('[data-action="close-staff"]');
    if (closeButton) closeButton.setAttribute('aria-label', t('closeStaffAria'));
    var completedPanel = document.querySelector('[data-staff-completed]');
    if (completedPanel) completedPanel.hidden = false;
    var titlePrimary = document.querySelector('[data-staff-title-primary]');
    var titleEn = document.querySelector('[data-staff-title-en]');
    titlePrimary.textContent = currentLocale === 'zh' ? staffContent.titleZh : currentLocale === 'es' ? staffContent.titleEs : staffContent.titleEn;
    titlePrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    titleEn.textContent = staffContent.titleEn;
    titleEn.hidden = currentLocale === 'en';
    var bodyPrimary = document.querySelector('[data-staff-primary]');
    var bodyEn = document.querySelector('[data-staff-en]');
    bodyPrimary.textContent = currentLocale === 'zh' ? staffContent.bodyZh : currentLocale === 'es' ? staffContent.bodyEs : staffContent.bodyEn;
    bodyPrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    bodyEn.textContent = staffContent.bodyEn;
    bodyEn.hidden = currentLocale === 'en';
    var completed = completedProgressText();
    var progressPrimary = document.querySelector('[data-staff-completed-primary]');
    var progressEn = document.querySelector('[data-staff-completed-en]');
    if (progressPrimary) {
      progressPrimary.textContent = completed[currentLocale];
      progressPrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    }
    if (progressEn) {
      progressEn.textContent = completed.en;
      progressEn.hidden = currentLocale === 'en';
    }
    var flightStrip = document.querySelector('[data-staff-flight-strip]');
    if (flightStrip) flightStrip.hidden = !currentFlight && !(currentError && currentError.lookupContext);
    var lookup = currentFlight || currentError && currentError.lookupContext || lastLookupContext;
    var staffFlight = document.querySelector('[data-staff-flight]');
    if (staffFlight) staffFlight.textContent = lookup && lookup.flightNumber
      ? formatFlightNumber(lookup.flightNumber) : '—';
    var staffDatePrimary = document.querySelector('[data-staff-date-primary]');
    var staffDateEn = document.querySelector('[data-staff-date-en]');
    var date = lookup && lookup.departureDate;
    if (staffDatePrimary) {
      staffDatePrimary.textContent = formatStaffDate(date);
      staffDatePrimary.lang = 'en';
    }
    if (staffDateEn) {
      staffDateEn.textContent = '';
      staffDateEn.hidden = true;
    }
    var facts = document.querySelector('[data-staff-facts]');
    facts.replaceChildren();
    assistanceFacts().forEach(function (fact) {
      var term = document.createElement('dt');
      term.textContent = fact[0];
      var detail = document.createElement('dd');
      detail.textContent = fact[1];
      facts.append(term, detail);
    });
  }

  function formatStaffDate(value) {
    var match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(value || ''));
    if (!match) return '—';
    var months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN',
      'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
    var month = months[Number(match[2]) - 1];
    return month ? Number(match[3]) + ' ' + month + ' ' + match[1] : '—';
  }

  function completedProgressText() {
    var zh = [];
    var en = [];
    var es = [];
    if (journeyContext) {
      if (journeyContext.checkedIn) { zh.push('值机'); en.push('check-in'); es.push('facturación'); }
      if (journeyContext.bagDropped) { zh.push('托运行李'); en.push('bag drop'); es.push('entrega de equipaje'); }
      if (journeyContext.securityCleared) { zh.push('安检'); en.push('security'); es.push('control de seguridad'); }
      if (journeyContext.atGate) { zh.push('到达登机口'); en.push('reached the gate'); es.push('llegada a la puerta'); }
      if (journeyContext.boarded) { zh.push('登机'); en.push('boarding'); es.push('embarque'); }
      if (journeyContext.landed) { zh.push('下飞机'); en.push('left the aircraft'); es.push('salida del avión'); }
      if (journeyContext.baggageCollected) { zh.push('领取行李'); en.push('baggage collection'); es.push('recogida de equipaje'); }
      if (journeyContext.exitedAirport) { zh.push('到达出口'); en.push('reached the exit'); es.push('llegada a la salida'); }
    }
    return {
      zh: '已完成：' + (zh.length ? zh.join(' · ') : '尚未开始'),
      en: 'Done: ' + (en.length ? en.join(' · ') : 'not started'),
      es: 'Completado: ' + (es.length ? es.join(' · ') : 'sin empezar')
    };
  }

  function assistanceFacts() {
    if (!currentFlight) {
      var lookup = currentError && currentError.lookupContext || lastLookupContext;
      if (!lookup) return [];
      var lookupFacts = [];
      if (lookup.departureAirport || lookup.arrivalAirport) {
        lookupFacts.push([localeText('航线', 'Route', 'Ruta'), (lookup.departureAirport || localeText('待确认', 'Confirm', 'Confirmar'))
          + ' → ' + (lookup.arrivalAirport || localeText('待确认', 'Confirm', 'Confirmar'))]);
      }
      return lookupFacts;
    }
    var time = currentFlight.timeContext || {};
    var step = currentAction ? actionLocaleText(currentAction, 'title')
      : localeText('航班查询', 'Flight lookup', 'Consulta del vuelo');
    var facts = [
      [localeText('航线', 'Route', 'Ruta'), (currentFlight.departureAirport || '—') + ' → ' + (currentFlight.arrivalAirport || '—')],
      [localeText('当前步骤', 'Current step', 'Paso actual'), step]
    ];
    if (isArrivalPhase()) {
      facts.push([localeText('行李转盘', 'Baggage belt', 'Cinta de equipaje'), formatBeltValue(currentFlight.arrivalBaggageBelt) || '—']);
      facts.push([localeText('目的地当地时间', 'Destination local time', 'Hora local del destino'), formatDestinationLocalNow()]);
    } else {
      facts.push([localeText('航站楼', 'Terminal', 'Terminal'), formatTerminalValue(currentFlight.terminal) || '—']);
      facts.push([localeText('登机口', 'Gate', 'Puerta'), formatGateValue(currentFlight.gate) || '—']);
      facts.push([localeText('值机区', 'Check-in area', 'Zona de facturación'), formatCheckInValue(currentFlight.checkInDeskOrZone || currentFlight.checkInCounter) || '—']);
      facts.push([localeText('当地时间', 'Local time', 'Hora local'), document.querySelector('[data-airport-local-time]')
        ? document.querySelector('[data-airport-local-time]').textContent : '—']);
      facts.push([localeText('倒计时', 'Countdown', 'Cuenta atrás'), formatCountdown(time.boardingTime || time.effectiveDeparture)]);
    }
    return facts;
  }

  function safeAirportHttpsUrl(value) {
    try {
      var url = new URL(value);
      if (url.protocol !== 'https:' || url.username || url.password || url.port) return null;
      return url.href;
    } catch (error) { return null; }
  }

  function verifiedAirportResourcesResponse(response, context) {
    if (!response || response.airportCode !== context.airportCode) return null;
    var terminal = function (value) {
      return String(value || '').toUpperCase().replace(/^TERMINAL\s*/, '').replace(/[^A-Z0-9]/g, '');
    };
    if (terminal(response.terminal) !== terminal(context.terminal) || !response.map || !response.contact) return null;
    var mapStatus = ['FOUND', 'OFFICIAL_PAGE_ONLY', 'AIRPORT_LOCATION', 'NOT_FOUND', 'TEMPORARILY_UNAVAILABLE']
      .indexOf(response.map.status) >= 0 ? response.map.status : 'TEMPORARILY_UNAVAILABLE';
    var mapUrl = ['FOUND', 'OFFICIAL_PAGE_ONLY', 'AIRPORT_LOCATION'].indexOf(mapStatus) >= 0
      ? safeAirportHttpsUrl(response.map.url) : null;
    var mapSource = mapUrl ? safeAirportHttpsUrl(response.map.sourceUrl) : null;
    if (mapUrl && !mapSource) { mapStatus = 'TEMPORARILY_UNAVAILABLE'; mapUrl = null; }
    var mapTypes = ['DIRECT_INTERACTIVE_MAP', 'DIRECT_MAP_DOCUMENT', 'OFFICIAL_MAP_LANDING_PAGE',
      'GOOGLE_MAPS_AIRPORT_LOCATION', 'GUIDE_OR_GENERIC_PAGE', 'NOT_FOUND', 'TEMPORARILY_UNAVAILABLE'];
    var mapResultType = mapTypes.indexOf(response.map.resultType) >= 0
      ? response.map.resultType : 'TEMPORARILY_UNAVAILABLE';

    var status = ['PHONE_FOUND', 'CONTACT_PAGE_ONLY', 'NOT_FOUND', 'TEMPORARILY_UNAVAILABLE']
      .indexOf(response.contact.status) >= 0 ? response.contact.status : 'TEMPORARILY_UNAVAILABLE';
    var sourceUrl = response.contact.sourceUrl ? safeAirportHttpsUrl(response.contact.sourceUrl) : null;
    var contactUrl = response.contact.contactUrl ? safeAirportHttpsUrl(response.contact.contactUrl) : null;
    var telUri = /^tel:\+?\d{5,18}$/.test(response.contact.telUri || '') ? response.contact.telUri : null;
    var phoneDisplay = String(response.contact.phoneDisplay || '').trim();
    var exactMapsPhone = response.contact.sourceType === 'GOOGLE_MAPS'
      && Boolean(response.contact.airportIdentityConfirmed)
      && Boolean(response.contact.mapsStructuredPhonePresent);
    if (status === 'PHONE_FOUND' && (!telUri || !phoneDisplay || !sourceUrl && !exactMapsPhone)) {
      status = 'TEMPORARILY_UNAVAILABLE';
    }
    if (status === 'CONTACT_PAGE_ONLY' && (!contactUrl || !sourceUrl)) status = 'TEMPORARILY_UNAVAILABLE';
    if (status === 'NOT_FOUND' || status === 'TEMPORARILY_UNAVAILABLE') {
      telUri = null;
      phoneDisplay = '';
      contactUrl = null;
      sourceUrl = null;
    }
    return {
      airportCode: response.airportCode,
      airportName: String(response.airportName || context.airportName),
      terminal: response.terminal || null,
      localAirportName: String(response.localAirportName || ''),
      countryOrRegion: String(response.countryOrRegion || ''),
      map: { status: mapStatus, url: mapUrl, sourceUrl: mapSource,
        sourceTitle: String(response.map.sourceTitle || ''), resultType: mapResultType,
        sourceLanguage: String(response.map.sourceLanguage || ''), mapScope: String(response.map.mapScope || ''),
        verificationReason: String(response.map.verificationReason || ''),
        rejectedCandidateTypes: Array.isArray(response.map.rejectedCandidateTypes)
          ? response.map.rejectedCandidateTypes.map(String) : [], fromCache: Boolean(response.map.fromCache) },
      contact: { status: status, phoneDisplay: phoneDisplay, telUri: telUri,
        contactUrl: contactUrl, sourceUrl: sourceUrl, sourceTitle: String(response.contact.sourceTitle || ''),
        sourceLanguage: String(response.contact.sourceLanguage || ''), sourceType: String(response.contact.sourceType || ''),
        phoneRole: String(response.contact.phoneRole || ''), evidenceTier: String(response.contact.evidenceTier || ''),
        airportIdentityConfirmed: Boolean(response.contact.airportIdentityConfirmed),
        aiDedicatedPhoneField: Boolean(response.contact.aiDedicatedPhoneField),
        mapsStructuredPhonePresent: Boolean(response.contact.mapsStructuredPhonePresent),
        phoneConflict: Boolean(response.contact.phoneConflict), fromCache: Boolean(response.contact.fromCache) },
      serpApiRequestCount: Number.isInteger(response.serpApiRequestCount) ? response.serpApiRequestCount : 0,
      rejectionReasons: Array.isArray(response.rejectionReasons) ? response.rejectionReasons.map(String) : [],
      retrievedAt: response.retrievedAt || null,
      fromCache: Boolean(response.fromCache)
    };
  }

  function renderStaffOverlayView() {
    var overlay = document.querySelector('[data-staff-overlay]');
    var cardView = document.querySelector('[data-staff-card-view]');
    var resourceView = document.querySelector('[data-airport-resource-view]');
    var resource = staffOverlayView === 'RESOURCE';
    if (cardView) cardView.hidden = resource;
    if (resourceView) resourceView.hidden = !resource;
    var contactButton = document.querySelector('[data-staff-contact-button]');
    if (contactButton) {
      contactButton.disabled = resource;
      contactButton.hidden = resource || !currentAirportResourcesContext();
    }
    if (overlay) overlay.setAttribute('aria-labelledby', resource ? 'airport-resource-title' : 'staff-title');
    if (resource) renderAirportResourceView();
  }

  function setAirportResourceLink(link, href, label) {
    if (!link) return;
    link.hidden = !href;
    link.textContent = href ? label : '';
    if (href) link.href = href;
    else link.removeAttribute('href');
  }

  function airportResourceDisplayName(result, context) {
    var code = String(result && result.airportCode || context && context.airportCode || '').toUpperCase();
    var localName = String(result && result.localAirportName || '').trim();
    var suppliedName = String(result && result.airportName || context && context.airportName || '').trim();
    var name = localName && localName.toUpperCase() !== code ? localName
      : suppliedName && suppliedName.toUpperCase() !== code ? suppliedName : '';
    return name ? name + ' (' + code + ')' : code;
  }

  function renderAirportResourceView() {
    var view = document.querySelector('[data-airport-resource-view]');
    if (!view) return;
    var context = airportResourceContext;
    var result = airportResourceResult;
    var loading = airportResourceState === 'LOADING';
    var title = document.querySelector('[data-airport-resource-title]');
    var airport = document.querySelector('[data-airport-resource-airport]');
    var message = document.querySelector('[data-airport-resource-message]');
    var detail = document.querySelector('[data-airport-resource-detail]');
    var phone = document.querySelector('[data-airport-phone-display]');
    var phoneIcon = document.querySelector('[data-airport-phone-icon]');
    var mapLink = document.querySelector('[data-airport-map-result]');
    var phoneLink = document.querySelector('[data-airport-phone-result]');
    var contactLink = document.querySelector('[data-airport-contact-result]');
    var retry = document.querySelector('[data-action="retry-airport-resource"]');
    var back = document.querySelector('[data-airport-resource-back]');
    var mapMode = airportResourceMode === 'MAP';
    view.setAttribute('aria-busy', String(loading));
    title.textContent = t(mapMode ? 'airportMapTitle' : 'airportContactTitle');
    airport.textContent = '';
    airport.hidden = true;
    message.textContent = '';
    detail.textContent = '';
    phone.textContent = '';
    phone.hidden = true;
    phoneIcon.hidden = true;
    setAirportResourceLink(mapLink, null, '');
    setAirportResourceLink(phoneLink, null, '');
    setAirportResourceLink(contactLink, null, '');
    contactLink.classList.remove('button-primary');
    contactLink.classList.add('button-secondary');
    retry.hidden = true;
    back.hidden = false;
    back.textContent = t(airportResourceOrigin === 'HELP_CARD' ? 'backToCard' : 'backStep');
    if (!context) return;

    if (loading) {
      message.textContent = t(mapMode ? 'findingAirportMap' : 'findingAirportContact');
      detail.textContent = t('airportResourceWaitHint');
      return;
    }

    if (!result) {
      message.textContent = t(mapMode ? 'mapTemporarilyUnavailable' : 'contactTemporarilyUnavailable');
      retry.hidden = false;
      return;
    }
	airport.textContent = airportResourceDisplayName(result, context);
	airport.hidden = false;
    if (mapMode && result.map.status === 'FOUND') {
      message.textContent = t('mapFound');
      setAirportResourceLink(mapLink, result.map.url, t('openOfficialMap'));
    } else if (mapMode && result.map.status === 'OFFICIAL_PAGE_ONLY') {
      message.textContent = t('mapPageOnly');
      setAirportResourceLink(mapLink, result.map.url, t('viewAirportWebsite'));
    } else if (mapMode && result.map.status === 'AIRPORT_LOCATION') {
      message.textContent = t('airportLocationOnly');
      setAirportResourceLink(mapLink, result.map.url, t('openAirportLocation'));
    } else if (mapMode) {
      message.textContent = t(result.map.status === 'TEMPORARILY_UNAVAILABLE'
        ? 'mapTemporarilyUnavailable' : 'mapNotFound');
      retry.hidden = false;
    } else if (result.contact.status === 'PHONE_FOUND') {
      message.textContent = t('phoneFound');
      phone.textContent = result.contact.phoneDisplay;
      phone.hidden = false;
      phoneIcon.hidden = false;
      setAirportResourceLink(phoneLink, result.contact.telUri, t('callAirport'));
      if (result.contact.contactUrl) setAirportResourceLink(contactLink, result.contact.contactUrl, t('viewAirportContactPage'));
    } else if (result.contact.status === 'CONTACT_PAGE_ONLY') {
      message.textContent = t('phoneNotFound');
      contactLink.classList.remove('button-secondary');
      contactLink.classList.add('button-primary');
      setAirportResourceLink(contactLink, result.contact.contactUrl, t('openAirportContactPage'));
    } else {
      message.textContent = t(result.contact.status === 'TEMPORARILY_UNAVAILABLE'
        ? 'contactTemporarilyUnavailable' : 'contactNotFound');
      retry.hidden = false;
    }
  }

  function airportResourceLoadingSpeech() {
    return t(airportResourceMode === 'MAP' ? 'findingAirportMap' : 'findingAirportContact');
  }

  function emptyAirportMapResult() {
    return { status: 'NOT_FOUND', url: null, sourceUrl: null, sourceTitle: '', resultType: 'NOT_FOUND',
      sourceLanguage: '', mapScope: '', verificationReason: '', rejectedCandidateTypes: [], fromCache: true };
  }

  function emptyAirportContactResult() {
    return { status: 'NOT_FOUND', phoneDisplay: '', telUri: null, contactUrl: null, sourceUrl: null,
      sourceTitle: '', sourceLanguage: '', sourceType: '', phoneRole: '', evidenceTier: '',
      airportIdentityConfirmed: false, aiDedicatedPhoneField: false, mapsStructuredPhonePresent: false,
      phoneConflict: false, fromCache: true };
  }

  function cachedAirportResources(context) {
    var map = airportMapCache[context.mapKey];
    var contact = airportContactCache[context.contactKey];
    var identity = airportIdentityCache[context.contactKey] || {};
    if (!map && !contact) return null;
    return { airportCode: context.airportCode, airportName: identity.airportName || context.airportName || context.airportCode,
      terminal: context.terminal, localAirportName: identity.localAirportName || '',
      countryOrRegion: identity.countryOrRegion || '',
      map: map || emptyAirportMapResult(), contact: contact && contact.resource || emptyAirportContactResult(),
      serpApiRequestCount: 0, rejectionReasons: contact && contact.rejectionReasons || [], fromCache: true };
  }

  function hasAirportResourceForMode(context, mode) {
    if (mode === 'BOTH') {
      return Boolean(airportMapCache[context.mapKey]) && Boolean(airportContactCache[context.contactKey]);
    }
    return mode === 'MAP' ? Boolean(airportMapCache[context.mapKey])
      : Boolean(airportContactCache[context.contactKey]);
  }

  function storeAirportResources(context, verified, target) {
    if (!verified) return;
    airportIdentityCache[context.contactKey] = { airportName: verified.airportName,
      localAirportName: verified.localAirportName, countryOrRegion: verified.countryOrRegion };
    if (target !== 'CONTACT' && verified.map.status !== 'TEMPORARILY_UNAVAILABLE') {
      airportMapCache[context.mapKey] = verified.map;
    }
    if (target !== 'MAP' && verified.contact.status !== 'TEMPORARILY_UNAVAILABLE') {
      airportContactCache[context.contactKey] = { resource: verified.contact,
        localAirportName: verified.localAirportName, countryOrRegion: verified.countryOrRegion,
        rejectionReasons: verified.rejectionReasons };
    }
  }

  function requestAirportResources(context, forceRefresh, refreshTarget) {
    if (!context) return Promise.resolve(null);
	var target = ['MAP', 'CONTACT', 'BOTH'].indexOf(refreshTarget || airportResourceMode) >= 0
	  ? refreshTarget || airportResourceMode : 'BOTH';
	if (forceRefresh && target !== 'CONTACT') delete airportMapCache[context.mapKey];
	if (forceRefresh && target !== 'MAP') delete airportContactCache[context.contactKey];
    if (!forceRefresh && hasAirportResourceForMode(context, target)) {
      return Promise.resolve(cachedAirportResources(context));
    }
    var inFlightKey = context.key + ':' + target;
    if (airportResourceInFlight[inFlightKey]) return airportResourceInFlight[inFlightKey].promise;
    var controller = new AbortController();
    var timeout = window.setTimeout(function () { controller.abort(); }, 60000);
    var payload = {
      airportCode: context.airportCode,
      airportName: context.airportName,
      terminal: context.terminal,
      forceRefresh: Boolean(forceRefresh),
      refreshTarget: target
    };
    var entry = { controller: controller, promise: null };
    entry.promise = fetchJson('/api/assistance/airport-resources', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), signal: controller.signal
    }).then(function (response) {
      var verified = verifiedAirportResourcesResponse(response, context);
      storeAirportResources(context, verified, target);
      return verified || cachedAirportResources(context);
    }).catch(function () {
      return null;
    }).finally(function () {
      window.clearTimeout(timeout);
      if (airportResourceInFlight[inFlightKey] === entry) delete airportResourceInFlight[inFlightKey];
    });
    airportResourceInFlight[inFlightKey] = entry;
    return entry.promise;
  }

  function pushAirportResourceHistory() {
    if (airportResourceHistoryActive) return;
    var state = Object.assign({}, window.history.state || {}, { oneStepAirportContact: true });
    window.history.pushState(state, '', window.location.href);
    airportResourceHistoryActive = true;
  }

  function openAirportResource(mode, trigger) {
    var context = currentAirportResourcesContext();
    if (!context) return;
    var origin = document.querySelector('[data-staff-overlay]').hidden ? 'JOURNEY_STEP' : 'HELP_CARD';
    if (mode === 'MAP' && origin !== 'JOURNEY_STEP') return;
    var cached = hasAirportResourceForMode(context, mode) ? cachedAirportResources(context) : null;
    airportResourceReturnFocus = trigger || document.activeElement;
    airportResourceOrigin = origin;
    if (airportResourceOrigin === 'JOURNEY_STEP') {
      staffReturnFocusElement = airportResourceReturnFocus;
      openDialog('[data-staff-overlay]', '[data-action="close-staff"]');
    }
    stopSpeech(false);
    staffOverlayView = 'RESOURCE';
    airportResourceMode = mode;
    airportResourceContext = context;
    airportResourceResult = cached || null;
    airportResourceState = cached ? 'RESULT' : 'LOADING';
    var generation = ++airportResourceGeneration;
    pushAirportResourceHistory();
    renderStaffOverlayView();
    var title = document.querySelector('[data-airport-resource-title]');
    if (title) title.focus({ preventScroll: true });
    if (cached) return;
    startSpeechText(airportResourceLoadingSpeech(), true);
    requestAirportResources(context, false, mode).then(function (result) {
      if (generation !== airportResourceGeneration || staffOverlayView !== 'RESOURCE') return;
      var latest = currentAirportResourcesContext();
      if (!latest || latest.key !== context.key || airportResourceContext.key !== context.key) return;
      airportResourceResult = result;
      airportResourceState = 'RESULT';
      renderAirportResourceView();
    });
  }

  function retryAirportResource() {
    var context = airportResourceContext || currentAirportResourcesContext();
    if (!context) return;
    airportResourceState = 'LOADING';
    airportResourceResult = null;
    var generation = ++airportResourceGeneration;
    renderAirportResourceView();
    startSpeechText(airportResourceLoadingSpeech(), true);
    requestAirportResources(context, true, airportResourceMode).then(function (result) {
      if (generation !== airportResourceGeneration || staffOverlayView !== 'RESOURCE') return;
      airportResourceResult = result;
      airportResourceState = 'RESULT';
      renderAirportResourceView();
    });
  }

  function cancelAirportResourceViewRequest() {
    airportResourceGeneration += 1;
    var inFlightKey = airportResourceContext && airportResourceMode
      ? airportResourceContext.key + ':' + airportResourceMode : null;
    if (inFlightKey && airportResourceInFlight[inFlightKey]) {
      airportResourceInFlight[inFlightKey].controller.abort();
      delete airportResourceInFlight[inFlightKey];
    }
  }

  function returnToStaffCard(fromHistory) {
    if (!fromHistory && airportResourceHistoryActive && window.history.state
        && window.history.state.oneStepAirportContact) {
      window.history.back();
      return;
    }
    if (airportResourceOrigin === 'JOURNEY_STEP') {
      finishCloseStaff();
      return;
    }
    var returnFocus = airportResourceReturnFocus;
    stopSpeech(false);
    cancelAirportResourceViewRequest();
    staffOverlayView = 'CARD';
    airportResourceMode = null;
    airportResourceOrigin = null;
    airportResourceState = 'IDLE';
    airportResourceResult = null;
    airportResourceContext = null;
    renderStaffCard();
    renderStaffOverlayView();
    var cardContact = document.querySelector('[data-staff-contact-button]');
    if (cardContact && !cardContact.hidden) cardContact.focus({ preventScroll: true });
    else if (returnFocus && document.contains(returnFocus) && !returnFocus.disabled) returnFocus.focus({ preventScroll: true });
  }

  function syncAirportResourcesContext() {
    var context = currentAirportResourcesContext();
    document.querySelectorAll('[data-airport-resource-row]').forEach(function (row) {
      row.hidden = !context;
    });
    if (staffOverlayView !== 'RESOURCE') {
      renderStaffOverlayView();
      return;
    }
    if (!context) {
      if (airportResourceOrigin === 'HELP_CARD') returnToStaffCard();
      else closeStaff();
      return;
    }
    if (!airportResourceContext || context.key !== airportResourceContext.key) {
      airportResourceContext = context;
      airportResourceResult = hasAirportResourceForMode(context, airportResourceMode)
        ? cachedAirportResources(context) : null;
      airportResourceState = airportResourceResult ? 'RESULT' : 'LOADING';
      var generation = ++airportResourceGeneration;
      renderAirportResourceView();
      if (!airportResourceResult) requestAirportResources(context, false, airportResourceMode).then(function (result) {
        if (generation !== airportResourceGeneration || staffOverlayView !== 'RESOURCE') return;
        airportResourceResult = result;
        airportResourceState = 'RESULT';
        renderAirportResourceView();
      });
    }
  }

  function airportResourceQaAllowed(locationValue) {
    var host = String(locationValue && locationValue.hostname || '').toLowerCase();
    if (host === '[::1]') host = '::1';
    var local = ['localhost', '127.0.0.1', '::1'].indexOf(host) >= 0;
    return local && new URLSearchParams(String(locationValue && locationValue.search || '')).get('qaAirport') === '1';
  }

  function syncAirportResourceQaVisibility() {
    var panel = document.querySelector('[data-qa-airport-panel]');
    if (!panel) return;
    panel.hidden = !(airportResourceQaAllowed(window.location) && currentScreen === 'confirm' && !activeDialog);
  }

  function setupAirportResourceQaPanel() {
    var panel = document.querySelector('[data-qa-airport-panel]');
    if (!panel) return;
    syncAirportResourceQaVisibility();
    var form = panel.querySelector('[data-qa-airport-form]');
    var output = panel.querySelector('[data-qa-airport-result]');
    var codeInput = form.elements.airportCode;
    codeInput.addEventListener('input', function () {
      codeInput.value = String(codeInput.value || '').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 3);
      codeInput.setCustomValidity('');
    });
    form.addEventListener('submit', function (event) {
      event.preventDefault();
      var code = String(codeInput.value || '').trim().toUpperCase();
      codeInput.value = code;
      codeInput.setCustomValidity(/^[A-Z]{3}$/.test(code) ? '' : localeText(
        '请输入三个英文字母的机场代码', 'Enter a three-letter airport code',
        'Introduce un código de aeropuerto de tres letras'));
      if (!codeInput.reportValidity()) return;
      var terminal = String(form.elements.terminal.value || '').trim() || null;
      var context = {
        airportCode: code,
        airportName: null,
        terminal: terminal,
        contactKey: code,
        mapKey: code + ':' + String(terminal || '').toUpperCase().replace(/^TERMINAL\s*/, '').replace(/[^A-Z0-9]/g, ''),
        key: code + ':' + String(terminal || '').toUpperCase().replace(/^TERMINAL\s*/, '').replace(/[^A-Z0-9]/g, '')
      };
      output.textContent = t('qaAirportLoading');
      requestAirportResources(context, true, 'BOTH').then(function (result) {
        output.textContent = '';
        if (!result) {
          output.textContent = t('qaAirportFailed');
          return;
        }
        renderAirportQaResult(output, result, context);
      });
    });
  }

  function renderAirportQaResult(output, result, context) {
    var name = document.createElement('h3');
    name.className = 'qa-airport-name';
    name.textContent = airportResourceDisplayName(result, context);
    output.appendChild(name);
    if (context.terminal) appendQaPlain(output, t('qaAirportTerminalLabel') + ': ' + context.terminal);

    appendQaHeading(output, t('qaAirportContactHeading'));
    if (result.contact.status === 'PHONE_FOUND') {
      var phone = document.createElement('p');
      phone.className = 'qa-airport-phone';
      phone.textContent = result.contact.phoneDisplay;
      output.appendChild(phone);
      appendQaAction(output, t('callAirport'), result.contact.telUri);
    } else {
      appendQaPlain(output, t('phoneNotFound'));
      if (result.contact.contactUrl) appendQaAction(output, t('openAirportContactPage'), result.contact.contactUrl);
    }

    appendQaHeading(output, t('qaAirportMapHeading'));
    if (result.map.status === 'FOUND' || result.map.status === 'OFFICIAL_PAGE_ONLY') {
      appendQaAction(output, t('openOfficialMap'), result.map.url);
    } else if (result.map.status === 'AIRPORT_LOCATION') {
      appendQaPlain(output, t('airportLocationOnly'));
      appendQaAction(output, t('openAirportLocation'), result.map.url);
    } else {
      appendQaPlain(output, t('mapNotFound'));
    }

  }

  function appendQaHeading(output, value) {
    var heading = document.createElement('h4');
    heading.textContent = value;
    output.appendChild(heading);
  }

  function appendQaPlain(output, value) {
    var row = document.createElement('p');
    row.textContent = value;
    output.appendChild(row);
  }

  function appendQaAction(output, label, href) {
    if (!href) return;
    var link = document.createElement('a');
    link.className = 'button button-primary qa-airport-action';
    link.textContent = label;
    link.href = href;
    if (String(href).startsWith('https://')) {
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
    }
    output.appendChild(link);
  }

  function snapshotJourney() {
    journeyHistory.push({
      context: JSON.parse(JSON.stringify(journeyContext)),
      action: currentAction,
      checkInStatusAnswer: checkInStatusAnswer,
      reversibleEvents: journeyEvents.filter(function (event) { return event.reversible; })
        .map(function (event) { return JSON.parse(JSON.stringify(event)); })
    });
  }

  function answerCheckIn(answer) {
    if (!journeyContext || !currentAction || currentAction.stepType !== 'CHECK_IN_QUESTION') return;
    var checkedIn = answer === 'yes';
    checkInStatusAnswer = answer;
    journeyContext.checkedIn = checkedIn;
    journeyContext.needsAssistance = answer === 'unsure';
    if (answer !== 'unsure') snapshotJourney();
    recordJourneyEvent('CHECK_IN_STATUS_CONFIRMED', {
      reversible: true,
      params: { answer: answer }
    });
    if (answer === 'unsure') {
      setStaffContent(
        '请帮我确认值机状态', 'Please help me confirm my check-in status',
        '请帮我确认我是否已经完成值机，是否还需要领取登机牌。',
        'Please help me confirm whether I have checked in and whether I still need to collect a boarding pass.',
        'Ayúdeme a confirmar mi estado de facturación',
        'Ayúdeme a confirmar si ya he facturado y si todavía necesito recoger una tarjeta de embarque.'
      );
      persistJourney();
      showStaff();
      return;
    }
    journeyContext.needsAssistance = false;
    persistJourney();
    requestNextAction({ source: 'action' });
  }

  function restoreReversibleEvents(events) {
    var liveEvents = journeyEvents.filter(function (event) { return !event.reversible; });
    journeyEvents = liveEvents.concat(Array.isArray(events) ? events : []);
    journeyEvents.sort(function (first, second) { return Date.parse(first.occurredAt) - Date.parse(second.occurredAt); });
  }

  function answerBaggage(answer) {
    if (answer === 'unsure') {
      journeyContext.needsAssistance = false;
      journeyContext.hasCheckedBaggage = null;
      recordJourneyEvent('BAGGAGE_UNSURE', { reversible: true, params: { answer: 'unsure' } });
      setBaggageStaffContent();
      persistJourney();
      showStaff();
      return;
    }
    snapshotJourney();
    journeyContext.needsAssistance = false;
    journeyContext.hasCheckedBaggage = answer === 'yes';
    recordJourneyEvent('BAGGAGE_ANSWERED', { reversible: true, params: { answer: answer } });
    requestNextAction({ source: 'action' });
  }

  function completeCurrentAction() {
    if (!currentAction || currentAction.stepType !== 'ACTION') return;
    var code = currentAction.actionCode;
    if (code === 'WAIT') {
      requestNextAction({ refresh: true, source: 'manual' });
      return;
    }
    if (code === 'ASK_FOR_HELP') {
      showStaff();
      return;
    }

    if (code === 'BOARD_NOW') {
      showBoardConfirmation();
      return;
    }
    if (code === 'IN_FLIGHT') {
      showLandedConfirmation();
      return;
    }

    applyActionCompletion(code);
  }

  function applyActionCompletion(code) {
    snapshotJourney();
    if (code === 'CHECK_IN') journeyContext.checkedIn = true;
    if (code === 'BAG_DROP') journeyContext.bagDropped = true;
    if (code === 'SECURITY') journeyContext.securityCleared = true;
    if (code === 'GO_TO_GATE') {
      journeyContext.previousGate = currentFlight.gate;
      journeyContext.previousTerminal = currentFlight.terminal;
      journeyContext.atGate = true;
    }
    if (code === 'BOARD_NOW') journeyContext.boarded = true;
    if (code === 'IN_FLIGHT') journeyContext.landed = true;
    if (code === 'BAGGAGE_CLAIM') journeyContext.baggageCollected = true;
    if (code === 'EXIT_AIRPORT') journeyContext.exitedAirport = true;
    if (journeyContext.boarded) stopAutoRefresh(true);
    recordJourneyEvent('ACTION_COMPLETED', {
      reversible: true,
      params: { actionCode: code }
    });
    requestNextAction({ source: 'action' });
  }

  function requestStaffHelp() {
    if (!journeyContext || !currentAction) {
      showStaff();
      return;
    }
    if (currentAction.actionCode === 'ASK_FOR_HELP') {
      showStaff();
      return;
    }
    snapshotJourney();
    journeyContext.needsAssistance = true;
    recordJourneyEvent('ASSISTANCE_REQUESTED', { reversible: true });
    requestNextAction({ source: 'action' });
  }

  function goBack() {
    dismissFlightChangeNotice();
    requestSequence += 1;
    if (flightRequestController) flightRequestController.abort();
    setLookupBusy(false);
    stopSpeech(false);
    clearInterval(loadingTimer);
    clearTimeout(loadingTimeout);
    if ((currentScreen === 'confirm' || currentScreen === 'error')
        && documentSegments.length && selectedDocumentSegmentIndex != null) {
      returnToDocumentSegmentSelection();
      return;
    }
    if (currentScreen === 'confirm' && awaitingDocumentSelection) {
      returnHome();
      return;
    }
    if ((currentScreen === 'step' || currentScreen === 'complete') && journeyHistory.length) {
      var previous = journeyHistory.pop();
      journeyContext = previous.context;
      journeyContext.flightSnapshot = currentFlight;
      currentAction = previous.action;
      checkInStatusAnswer = previous.checkInStatusAnswer == null ? null : previous.checkInStatusAnswer;
      restoreReversibleEvents(previous.reversibleEvents);
      renderJourneyResponse();
      persistJourney();
      return;
    }
    if (currentScreen === 'step' || currentScreen === 'complete') {
      currentAction = null;
      journeyEvents = journeyEvents.filter(function (event) {
        return !event.reversible || event.type !== 'FLIGHT_CONFIRMED';
      });
      journeyContext = null;
      showScreen('confirm');
      persistJourney();
      return;
    }
    showScreen(navigationHistory.pop() || 'welcome');
  }

  function returnToDocumentSegmentSelection() {
    if (!documentSegments.length || !pendingDocument) return false;
    lastLockState = null; pushSyncFingerprint = null; serverSnapshotVersion = 0;
    endLiveJourney(familyShareJourneyKey(currentFlight));
    localStorage.removeItem(activeOfflineJourneyStorageKey());
    resetFamilyShareSession();
    requestSequence += 1;
    if (flightRequestController) flightRequestController.abort();
    flightRequestController = null;
    setLookupBusy(false);
    stopSpeech(false);
    currentFlight = null;
    currentAction = null;
    journeyContext = null;
    journeyHistory = [];
    currentError = null;
    lastLookupContext = null;
    conflictsConfirmed = false;
    resetManualDocumentEntry();
    selectedDocumentSegmentIndex = null;
    awaitingDocumentSelection = true;
    flightCorrectionOpen = false;
    renderDeparturePassedConfirmation(null);
    renderSegmentSelector();
    showScreen('confirm');
    persistJourney();
    announce(t('segmentSelectionTitle'));
    return true;
  }

  function isLocalDebugEnvironment() {
    return /^(localhost|127\.0\.0\.1|\[::1\])$/i.test(window.location.hostname);
  }

  function isLiveFlightRequest(error) {
    var url = String(error && error.requestUrl || '');
    return /^\/api\/flights(?:\/|\?|$)/.test(url)
      || /^\/api\/journeys\/(?:resolve-flight|refresh-flight|next-action)(?:\?|$)/.test(url);
  }

  function isDocumentRequest(error) {
    return /^\/api\/documents\/(?:[^?]+)(?:\?|$)/.test(String(error && error.requestUrl || ''));
  }

  function liveFlightErrorCopy(error, details, code) {
    var status = Number(error && error.status) || 0;
    var normalizedCode = String(code || '').toUpperCase();
    if (isDocumentRequest(error)) return {
      titleZh: '文件暂时无法读取', titleEn: 'The file can’t be read right now', titleEs: 'No se puede leer el archivo ahora',
      zh: '请重试，或换一个文件。', en: 'Try again or choose another file.', es: 'Inténtalo de nuevo o elige otro archivo.'
    };
    if (normalizedCode === 'FLIGHT_NOT_FOUND' || status === 404) return {
      titleZh: '没有找到这个航班', titleEn: 'Flight not found', titleEs: 'No se encontró el vuelo',
      zh: '请核对航班号和日期。', en: 'Check the flight number and date.', es: 'Comprueba el número de vuelo y la fecha.'
    };
    if (normalizedCode.indexOf('INVALID_') === 0 || status === 400) return {
      titleZh: '航班号或日期有误', titleEn: 'The flight number or date is invalid', titleEs: 'El número de vuelo o la fecha no son válidos',
      zh: '请核对航班号和日期。', en: 'Check the flight number and date.', es: 'Comprueba el número de vuelo y la fecha.'
    };
    if (normalizedCode === 'CLIENT_FLIGHT_TIMEOUT' || normalizedCode === 'LIVE_FLIGHT_TIMEOUT'
        || status === 504) return {
      titleZh: '查询超时', titleEn: 'Search timed out', titleEs: 'La búsqueda tardó demasiado',
      zh: '暂时没有查到，请稍后重试。', en: 'No result yet. Try again later.', es: 'Aún no hay resultados. Inténtalo más tarde.'
    };
    if (normalizedCode === 'LIVE_FLIGHT_RATE_LIMITED' || status === 429) return {
      titleZh: '查询次数达到限制', titleEn: 'Search limit reached', titleEs: 'Se alcanzó el límite de búsquedas',
      zh: '请稍后再试。', en: 'Try again later.', es: 'Inténtalo más tarde.'
    };
    return {
      titleZh: '航班信息暂时不可用', titleEn: 'Flight information is unavailable', titleEs: 'La información del vuelo no está disponible',
      zh: '请稍后重试，或请工作人员帮忙。',
      en: 'Try again later or ask staff for help.',
      es: 'Inténtalo más tarde o pide ayuda al personal.'
    };
  }

  function safeProviderDebugResult(result) {
    if (!result || typeof result !== 'object') return null;
    var safe = {};
    ['provider', 'outcome', 'errorCode', 'flightNumber', 'departureDate', 'departureAirport',
      'arrivalAirport', 'scheduledDeparture', 'estimatedDeparture', 'actualDeparture',
      'scheduledArrival', 'estimatedArrival', 'actualArrival', 'status', 'quality',
      'retrievedAt', 'live', 'fromCache'].forEach(function (field) {
      if (result[field] != null) safe[field] = result[field];
    });
    return Object.keys(safe).length ? safe : null;
  }

  function namedProviderResult(details, providerName) {
    var results = details && details.providerResults;
    if (!results || typeof results !== 'object') return null;
    var normalized = providerName.toUpperCase();
    var key = Object.keys(results).find(function (candidate) {
      return candidate.replace(/[^A-Z0-9]/gi, '').toUpperCase() === normalized;
    });
    return key ? results[key] : null;
  }

  function buildLiveFlightDebugLines(error, details, code) {
    if (!isLocalDebugEnvironment() || !isLiveFlightRequest(error)) return [];
    var aeroDataBox = safeProviderDebugResult(namedProviderResult(details, 'AERODATABOX'));
    var serpApi = safeProviderDebugResult(namedProviderResult(details, 'SERPAPI'));
    var lines = [
      'HTTP status: ' + (error && error.status != null ? error.status : 'unavailable'),
      'Error code: ' + code,
      'AeroDataBox provider result: ' + JSON.stringify(aeroDataBox || 'not provided', null, 2),
      'Secondary flight-data provider result: ' + JSON.stringify(serpApi || 'not provided', null, 2),
      'Backend message (zh): ' + (details && details.messageZh || 'not provided'),
      'Backend message (en): ' + (details && details.messageEn || 'not provided')
    ];
    if (details && details.correlationId) lines.push('Reference: ' + details.correlationId);
    return lines;
  }

  function errorTitleForLocale(errorState) {
    if (!errorState) return t('errorTitle');
    return currentLocale === 'zh' ? errorState.titleZh
      : currentLocale === 'es' ? errorState.titleEs : errorState.titleEn;
  }

  function renderErrorMessage() {
    var message = currentError ? currentError[currentLocale] || currentError.en : t('errorLead');
    document.getElementById('error-title').textContent = errorTitleForLocale(currentError);
    document.querySelector('[data-error-message]').textContent = message;
    var details = document.querySelector('[data-error-technical]');
    var content = document.querySelector('[data-error-technical-content]');
    // Keep provider diagnostics out of the customer UI, including localhost/WebView.
    details.hidden = true;
    details.open = false;
    content.textContent = '';
  }

  function showError(error, retry) {
    var details = error && error.apiError;
    var lookup = lastLookupContext ? JSON.parse(JSON.stringify(lastLookupContext)) : null;
    var code = details && details.code || error && error.code || 'REQUEST_FAILED';
    var copy = liveFlightErrorCopy(error, details, code);
    currentError = Object.assign({
      code: code,
      lookupContext: lookup,
      technicalLines: buildLiveFlightDebugLines(error, details, code)
    }, copy);
    setStaffContent(
      '请帮我完成下一步', 'Please help with my next step',
      '请帮我确认现在应该做什么。', 'Please help me confirm what I should do now.',
      'Ayúdeme con el siguiente paso', 'Ayúdeme a confirmar qué debo hacer ahora.'
    );
    retryRequest = retry;
    renderErrorMessage();
    showScreen('error');
    announce(currentError[currentLocale] || currentError.en);
  }

  function speechIsSupported() {
    return 'speechSynthesis' in window && typeof window.SpeechSynthesisUtterance === 'function';
  }

  function speechViewKey() {
    if (activeDialog && !activeDialog.hidden) {
      if (activeDialog.matches('[data-board-overlay]')) return 'dialog:confirm-board';
      if (activeDialog.matches('[data-landed-overlay]')) return 'dialog:confirm-landed';
    }
    if (currentScreen === 'confirm' && currentFlight) {
      if (flightCorrectionOpen) return 'confirm:flight-correction';
      if (departureStopReason(currentFlight)) return 'confirm:departure-exception';
      return 'confirm:flight:' + normalizeFlightNumber(currentFlight.flightNumber) + ':' + (currentFlight.departureDate || '');
    }
    if (currentScreen === 'step' && currentAction) {
      if (isDepartureStopAction(currentAction)) return 'step:departure-exception';
      if (currentAction.stepType === 'CHECK_IN_QUESTION') return 'step:check-in-question';
      if (currentAction.stepType === 'BAGGAGE_QUESTION') return 'step:baggage-question';
      if (currentAction.stepType === 'ACTION' && currentAction.actionCode) return 'step:action:' + currentAction.actionCode;
    }
    if (currentScreen === 'complete' && currentAction && currentAction.stepType === 'COMPLETE') return 'complete';
    return null;
  }

  function compactSpeechParts(parts) {
    var result = [];
    parts.forEach(function (part) {
      var value = String(part || '').trim();
      if (value && result.indexOf(value) < 0) result.push(value);
    });
    return result.join(currentLocale === 'zh' ? '。' : '. ');
  }

  function spokenConfirmValue(selector, fallbackValue) {
    var element = document.querySelector(selector);
    var value = String(element && element.textContent || fallbackValue || '').trim();
    return !value || value === '—' || value === '-' ? t('notPublished') : value;
  }

  function spokenOperationalFact(label, value, valueAlreadyIncludesLabel) {
    if (valueAlreadyIncludesLabel && value !== t('notPublished')) return value;
    return label + ' ' + value;
  }

  function buildSpeechTextForCurrentView() {
    if (activeDialog && !activeDialog.hidden) {
      if (activeDialog.matches('[data-board-overlay]')) return compactSpeechParts([t('boardConfirmTitle'), t('boardConfirmLead')]);
      if (activeDialog.matches('[data-landed-overlay]')) return compactSpeechParts([t('landedConfirmTitle'), t('landedConfirmLead')]);
    }
    if (currentScreen === 'confirm' && currentFlight) {
      if (flightCorrectionOpen) {
        return compactSpeechParts([t('flightCorrectionTitle'),
          document.querySelector('[data-flight-correction-lead]').textContent]);
      }
      if (departureStopReason(currentFlight)) {
        var stopCopy = departureStopCopy(departureStopReason(currentFlight));
        return compactSpeechParts([stopCopy.title, stopCopy.lead,
          formatFlightNumber(currentFlight.flightNumber), formatDate(currentFlight.departureDate)]);
      }
      var route = (currentFlight.departureAirport || '') + ' ' + localeText('到', 'to', 'a') + ' ' + (currentFlight.arrivalAirport || '');
      var date = formatDateForLocale(currentFlight.departureDate,
        currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-GB');
      var documentFallback = isDocumentFallbackFlight(currentFlight);
      var terminal = spokenConfirmValue('[data-terminal]', formatTerminalValue(
        currentFlight.terminal || (documentFallback ? currentFlight.documentTerminal : null)));
      var gate = spokenConfirmValue('[data-gate]', formatGateValue(
        currentFlight.gate || (documentFallback ? currentFlight.documentGate : null)));
      var boardingTime = spokenConfirmValue('[data-boarding-time]', formatAtDeparture(
        currentFlight.documentBoardingTime || currentFlight.timeContext && currentFlight.timeContext.boardingTime, false));
      return compactSpeechParts([
        t('confirmTitle'),
        localeText('航班号 ', 'Flight ', 'Vuelo ') + formatFlightNumber(currentFlight.flightNumber),
        route,
        localeText('出发日期 ', 'Departure date ', 'Fecha de salida ') + date,
        spokenOperationalFact(t('terminal'), terminal, true),
        spokenOperationalFact(t('gate'), gate, true),
        spokenOperationalFact(t('boardingTime'), boardingTime, false)
      ]);
    }
    if (currentScreen === 'step' && currentAction) {
      var title = actionLocaleText(currentAction, 'title');
      var description = actionLocaleText(currentAction, 'description');
      if (currentAction.stepType !== 'ACTION') return compactSpeechParts([title, description]);
      var destination = currentDestinationCopy
        ? (currentLocale === 'zh' ? currentDestinationCopy.primaryZh : currentLocale === 'es' ? currentDestinationCopy.primaryEs : currentDestinationCopy.primaryEn)
        : '';
      var time = document.querySelector('[data-action-time-primary]');
      return compactSpeechParts([title, isDepartureStopAction(currentAction) ? '' : destination,
        description, time && time.textContent]);
    }
    if (currentScreen === 'complete' && currentAction) {
      return compactSpeechParts([actionLocaleText(currentAction, 'title'), actionLocaleText(currentAction, 'description')]);
    }
    return '';
  }

  function updateSpeechButtons() {
    document.querySelectorAll('[data-action="speak"]').forEach(function (button) {
      button.setAttribute('aria-pressed', String(speakingActive));
      button.setAttribute('aria-label', speakingActive ? t('stopSpeakingAria') : t('speakAria'));
      var label = button.querySelector('[data-speech-label], span');
      if (label) label.textContent = speakingActive ? t('stopSpeaking') : t('speak');
    });
  }

  function stopSpeech(showMessage) {
    speechGeneration += 1;
    window.clearTimeout(automaticSpeechTimer);
    automaticSpeechTimer = null;
    var synthesis = speechIsSupported() ? window.speechSynthesis : null;
    var wasSpeaking = speakingActive || Boolean(synthesis && (synthesis.speaking || synthesis.pending));
    if (synthesis) {
      try { synthesis.cancel(); } catch (error) { /* Speech is optional. */ }
    }
    speakingActive = false;
    updateSpeechButtons();
    if (showMessage && wasSpeaking) showToast(localeText('朗读已停止。', 'Reading stopped.', 'Lectura detenida.'));
  }

  function startSpeechText(text, automatic, localeOverride) {
    var speechLocale = normalizeLocale(localeOverride || currentLocale);
    text = normalizeSpeechText(text, speechLocale);
    if (!text) return;
    stopSpeech(false);
    if (!speechIsSupported()) {
      if (!automatic) showToast(localeText('当前浏览器不支持朗读。', 'Speech is not supported in this browser.', 'Este navegador no admite la lectura en voz alta.'));
      return;
    }
    var generation = ++speechGeneration;
    var utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = speechLocale === 'zh' ? 'zh-CN' : speechLocale === 'es' ? 'es-ES' : 'en-US';
    var voicePrefix = speechLocale === 'zh' ? 'zh' : speechLocale === 'es' ? 'es' : 'en';
    var voices = typeof window.speechSynthesis.getVoices === 'function' ? window.speechSynthesis.getVoices() : [];
    var matchingVoice = voices.find(function (voice) {
      return String(voice.lang || '').toLowerCase().indexOf(voicePrefix) === 0;
    });
    if (matchingVoice) utterance.voice = matchingVoice;
    utterance.rate = 0.88;
    utterance.onend = function () { if (generation === speechGeneration) stopSpeech(false); };
    utterance.onerror = function () { if (generation === speechGeneration) stopSpeech(false); };
    speakingActive = true;
    updateSpeechButtons();
    try {
      window.speechSynthesis.speak(utterance);
    } catch (error) {
      speakingActive = false;
      updateSpeechButtons();
      if (!automatic) showToast(localeText('暂时无法开始朗读。', 'Reading could not start.', 'No se pudo iniciar la lectura.'));
    }
  }

  function normalizeSpeechText(text, locale) {
    var value = String(text || '').trim();
    if (normalizeLocale(locale) !== 'zh') return value;
    return value
      .replace(/(\d+)\s*[-–—]\s*(\d+)/g, '$1 到 $2')
      .replace(/\b([A-Z])(\d+)\s*登机口/gi, '英文字母 $1，$2 号登机口')
      .replace(/\b([A-Z])\s*区/gi, '英文字母 $1 区')
      .replace(/\b([A-Z])(\d+)\b/gi, '英文字母 $1，$2')
      .replace(/[：:]/g, '，');
  }

  function startSpeech(automatic) {
    startSpeechText(buildSpeechTextForCurrentView(), automatic);
  }

  function queueAutomaticSpeech() {
    var key = speechViewKey();
    if (!key) {
      window.clearTimeout(automaticSpeechTimer);
      automaticSpeechTimer = null;
      return;
    }
    if (languageChangeInProgress) {
      window.clearTimeout(automaticSpeechTimer);
      automaticSpeechTimer = null;
      lastAutomaticSpeechKey = key;
      return;
    }
    if (key === lastAutomaticSpeechKey) return;
    window.clearTimeout(automaticSpeechTimer);
    automaticSpeechTimer = null;
    stopSpeech(false);
    lastAutomaticSpeechKey = key;
    automaticSpeechTimer = window.setTimeout(function () {
      automaticSpeechTimer = null;
      if (speechViewKey() === key) startSpeech(true);
    }, 180);
  }

  function speak() {
    if (speakingActive || speechIsSupported() && (window.speechSynthesis.speaking || window.speechSynthesis.pending)) {
      stopSpeech(true);
      return;
    }
    window.clearTimeout(automaticSpeechTimer);
    automaticSpeechTimer = null;
    lastAutomaticSpeechKey = speechViewKey();
    startSpeech(false);
  }

  function showToast(message) {
    var toast = document.querySelector('[data-toast]');
    toast.textContent = message;
    toast.classList.add('is-visible');
    setTimeout(function () { toast.classList.remove('is-visible'); }, 2600);
  }

  function visibleStaffTrigger() {
    return Array.from(document.querySelectorAll('[data-action="staff"]')).find(function (button) {
      return !button.hidden && !button.disabled && button.offsetParent !== null;
    }) || null;
  }

  function showStaff(context) {
    fieldStaffHelpContext = context && fieldStaffContent[context.field] ? context : null;
    staffReturnFocusElement = document.activeElement
      && document.activeElement.matches('[data-action="staff"]')
      ? document.activeElement : visibleStaffTrigger();
    stopSpeech(false);
    cancelAirportResourceViewRequest();
    staffOverlayView = 'CARD';
    airportResourceMode = null;
    airportResourceOrigin = null;
    airportResourceState = 'IDLE';
    airportResourceResult = null;
    airportResourceContext = null;
    renderStaffCard();
    renderStaffOverlayView();
    openDialog('[data-staff-overlay]', '[data-action="close-staff"]');
    startSpeechText(t('staffCardInstruction'), true);
  }

  function closeStaff() {
    if (staffOverlayView === 'RESOURCE' && airportResourceHistoryActive && window.history.state
        && window.history.state.oneStepAirportContact) {
      airportResourceHistoryClosing = true;
      window.history.back();
      return;
    }
    finishCloseStaff();
  }

  function finishCloseStaff() {
    var returnFocus = airportResourceOrigin === 'JOURNEY_STEP' ? airportResourceReturnFocus : staffReturnFocusElement;
    stopSpeech(false);
    cancelAirportResourceViewRequest();
    staffOverlayView = 'CARD';
    airportResourceMode = null;
    airportResourceOrigin = null;
    airportResourceState = 'IDLE';
    airportResourceResult = null;
    airportResourceContext = null;
    airportResourceReturnFocus = null;
    closeDialog('[data-staff-overlay]');
    staffReturnFocusElement = null;
    if (returnFocus && document.contains(returnFocus) && !returnFocus.disabled) returnFocus.focus();
  }

  function resetJourneyProblemState() {
    journeyProblemState = { hasConnection: null, busy: false, answer: null, responseLocale: null };
    var card = document.querySelector('[data-journey-problem-card]');
    var connectionStep = document.querySelector('[data-journey-problem-connection-step]');
    var form = document.querySelector('[data-journey-problem-form]');
    var result = document.querySelector('[data-journey-problem-result]');
    var textarea = document.querySelector('[data-journey-problem-statement]');
    var voice = document.querySelector('[data-action="journey-problem-voice"]');
    var status = document.querySelector('[data-journey-problem-input-status]');
    var answer = document.querySelector('[data-journey-problem-answer]');
    var confirmation = document.querySelector('[data-journey-problem-confirmation]');
    if (card) card.setAttribute('aria-busy', 'false');
    setHidden(connectionStep, false);
    setHidden(form, true);
    setHidden(result, true);
    setHidden(document.querySelector('[data-journey-problem-transfer-prompt]'), true);
    if (textarea) { textarea.value = ''; textarea.disabled = false; }
    if (voice) voice.disabled = false;
    if (status) status.textContent = '';
    if (answer) answer.textContent = '';
    if (confirmation) confirmation.textContent = '';
    var voiceLabel = document.querySelector('[data-journey-problem-voice-label]');
    if (voiceLabel) voiceLabel.textContent = t('journeyProblemVoice');
    var submit = document.querySelector('[data-journey-problem-submit]');
    if (submit) { submit.textContent = t('journeyProblemSubmit'); submit.disabled = true; }
  }

  function openJourneyProblem() {
    if (!currentFlight || !activeJourneyProblemButton()) return;
    resetJourneyProblemState();
    openDialog('[data-journey-problem-overlay]', '[data-action="journey-problem-connection"]');
  }

  function chooseJourneyProblemConnection(hasConnection) {
    journeyProblemState.hasConnection = Boolean(hasConnection);
    setHidden(document.querySelector('[data-journey-problem-connection-step]'), true);
    setHidden(document.querySelector('[data-journey-problem-form]'), false);
    setHidden(document.querySelector('[data-journey-problem-transfer-prompt]'), !journeyProblemState.hasConnection);
    var textarea = document.querySelector('[data-journey-problem-statement]');
    if (textarea) textarea.focus();
    updateJourneyProblemSubmitState();
  }

  function stopJourneyProblemVoiceInput() {
    if (journeyProblemRecognition) {
      var recognition = journeyProblemRecognition;
      journeyProblemRecognition = null;
      recognition.onend = null;
      try { recognition.abort(); } catch (error) { /* Speech recognition is optional. */ }
    }
    var label = document.querySelector('[data-journey-problem-voice-label]');
    if (label) label.textContent = t('journeyProblemVoice');
  }

  function startJourneyProblemVoiceInput() {
    stopSpeech(false);
    stopJourneyProblemVoiceInput();
    var status = document.querySelector('[data-journey-problem-input-status]');
    var label = document.querySelector('[data-journey-problem-voice-label]');
    if (status) status.textContent = '';
    var Recognition = nativeApp ? null : window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!Recognition) {
      if (status) status.textContent = t('journeyProblemSpeechFailed');
      return;
    }
    var recognition = new Recognition();
    journeyProblemRecognition = recognition;
    recognition.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-US';
    recognition.continuous = false;
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;
    if (label) label.textContent = t('journeyProblemListening');
    var restore = function () {
      if (journeyProblemRecognition === recognition) journeyProblemRecognition = null;
      if (label) label.textContent = t('journeyProblemVoice');
    };
    recognition.onresult = function (event) {
      var transcript = event.results && event.results[0] && event.results[0][0]
        ? String(event.results[0][0].transcript || '').trim() : '';
      var textarea = document.querySelector('[data-journey-problem-statement]');
      if (transcript && textarea) textarea.value = (textarea.value.trim() ? textarea.value + ' ' : '') + transcript;
      if (!transcript && status) status.textContent = t('journeyProblemSpeechFailed');
      updateJourneyProblemSubmitState();
    };
    recognition.onerror = function () { if (status) status.textContent = t('journeyProblemSpeechFailed'); restore(); };
    recognition.onnomatch = function () { if (status) status.textContent = t('journeyProblemSpeechFailed'); };
    recognition.onend = restore;
    try { recognition.start(); } catch (error) { if (status) status.textContent = t('journeyProblemSpeechFailed'); restore(); }
  }

  function updateJourneyProblemSubmitState() {
    var textarea = document.querySelector('[data-journey-problem-statement]');
    var submit = document.querySelector('[data-journey-problem-submit]');
    if (submit) submit.disabled = journeyProblemState.busy || !textarea || !textarea.value.trim();
  }

  function currentJourneyProblemAirport() {
    var arrival = isArrivalPhase();
    var preferredCode = arrival ? currentFlight.arrivalAirport : currentFlight.departureAirport;
    var fallbackCode = arrival ? currentFlight.departureAirport : currentFlight.arrivalAirport;
    var preferredName = arrival ? currentFlight.arrivalAirportName : currentFlight.departureAirportName;
    var fallbackName = arrival ? currentFlight.departureAirportName : currentFlight.arrivalAirportName;
    return {
      problemAirport: preferredCode || fallbackCode,
      problemAirportName: preferredCode ? preferredName : fallbackName,
      offsetMinutes: arrival ? arrivalOffsetMinutes() : departureOffsetMinutes(),
      phase: arrival ? 'ARRIVAL' : 'DEPARTURE'
    };
  }

  function buildJourneyProblemPayload() {
    var textarea = document.querySelector('[data-journey-problem-statement]');
    var airport = currentJourneyProblemAirport();
    var time = currentFlight.timeContext || {};
    return {
      hasConnection: Boolean(journeyProblemState.hasConnection),
      passengerStatement: textarea ? textarea.value.trim() : '',
      locale: currentLocale,
      currentFlight: {
        flightNumber: currentFlight.flightNumber, departureDate: currentFlight.departureDate,
        departureAirport: currentFlight.departureAirport, departureAirportName: currentFlight.departureAirportName,
        arrivalAirport: currentFlight.arrivalAirport, arrivalAirportName: currentFlight.arrivalAirportName,
        problemAirport: airport.problemAirport, problemAirportName: airport.problemAirportName,
        problemPhase: airport.phase, scheduledDeparture: currentFlight.scheduledDeparture,
        estimatedDeparture: currentFlight.estimatedDeparture, actualDeparture: currentFlight.actualDeparture,
        effectiveDeparture: time.effectiveDeparture || currentFlight.effectiveDeparture,
        scheduledArrival: currentFlight.scheduledArrival, estimatedArrival: currentFlight.estimatedArrival,
        actualArrival: currentFlight.actualArrival, boardingTime: time.boardingTime || currentFlight.boardingTime,
        terminal: currentFlight.terminal, arrivalTerminal: currentFlight.arrivalTerminal,
        gate: currentFlight.gate, status: currentFlight.status, delayMinutes: currentFlight.delayMinutes,
        arrivalDelayMinutes: currentFlight.arrivalDelayMinutes, codeshare: currentFlight.codeshare,
        dataLastUpdatedAt: currentFlight.dataLastUpdatedAt || time.dataLastUpdatedAt,
        dataRetrievedAt: currentFlight.dataRetrievedAt || time.dataRetrievedAt,
        currentTime: calibratedNowIso(), currentLocalTime: formatLocalNowFor(currentLocale, airport.offsetMinutes),
        latestChanges: (currentFlight.latestChanges || []).map(function (change) {
          return { type: change.type, oldValue: change.oldValue, newValue: change.newValue,
            critical: change.critical, detectedAt: change.detectedAt };
        })
      }
    };
  }

  function journeyProblemFallback(locale) {
    return translations[normalizeLocale(locale)].journeyProblemFallback;
  }

  function limitJourneyProblemSentences(value) {
    var text = String(value || '').replace(/\s+/g, ' ').trim();
    var matches = text.match(/[^。！？.!?]+[。！？.!?]+(?:[”’"']+)?/g);
    return matches && matches.length ? matches.slice(0, 2).map(function (sentence) {
      return sentence.trim();
    }).join(' ') : text;
  }

  function renderJourneyProblemResult(answer, responseLocale, payload) {
    var locale = normalizeLocale(responseLocale);
    var safeAnswer = limitJourneyProblemSentences(answer) || journeyProblemFallback(locale);
    var confirmation = translations[locale].journeyProblemConfirmStaff;
    journeyProblemState.busy = false;
    journeyProblemState.answer = safeAnswer;
    journeyProblemState.responseLocale = locale;
    document.querySelector('[data-journey-problem-card]').setAttribute('aria-busy', 'false');
    setHidden(document.querySelector('[data-journey-problem-connection-step]'), true);
    setHidden(document.querySelector('[data-journey-problem-form]'), true);
    setHidden(document.querySelector('[data-journey-problem-result]'), false);
    document.querySelector('[data-journey-problem-answer]').textContent = safeAnswer;
    document.querySelector('[data-journey-problem-confirmation]').textContent = confirmation;
    startSpeechText(safeAnswer + ' ' + confirmation, true, locale);
  }

  function submitJourneyProblem() {
    if (journeyProblemState.busy || journeyProblemState.hasConnection == null || !currentFlight) return;
    var payload = buildJourneyProblemPayload();
    if (!payload.passengerStatement) return;
    stopJourneyProblemVoiceInput();
    journeyProblemState.busy = true;
    document.querySelector('[data-journey-problem-card]').setAttribute('aria-busy', 'true');
    var textarea = document.querySelector('[data-journey-problem-statement]');
    var voice = document.querySelector('[data-action="journey-problem-voice"]');
    var submit = document.querySelector('[data-journey-problem-submit]');
    textarea.disabled = true;
    voice.disabled = true;
    submit.disabled = true;
    submit.textContent = translations[payload.locale].journeyProblemLoading;
    var controller = new AbortController();
    journeyProblemRequestController = controller;
    var generation = ++journeyProblemRequestGeneration;
    var timeout = window.setTimeout(function () { controller.abort(); }, 45000);
    apiFetch('/api/assistance/journey-problem', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload), signal: controller.signal
    }).then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.json();
    }).then(function (response) {
      if (generation !== journeyProblemRequestGeneration) return;
      if (!response || !String(response.answer || '').trim()) {
        renderJourneyProblemResult(journeyProblemFallback(payload.locale), payload.locale, payload);
        return;
      }
      renderJourneyProblemResult(response.answer, payload.locale, payload);
    }).catch(function () {
      if (generation === journeyProblemRequestGeneration) {
        renderJourneyProblemResult(journeyProblemFallback(payload.locale), payload.locale, payload);
      }
    }).finally(function () {
      window.clearTimeout(timeout);
      if (journeyProblemRequestController === controller) journeyProblemRequestController = null;
    });
  }

  function closeJourneyProblem() {
    stopJourneyProblemVoiceInput();
    if (journeyProblemRequestController) journeyProblemRequestController.abort();
    journeyProblemRequestController = null;
    journeyProblemRequestGeneration += 1;
    stopSpeech(false);
    closeDialog('[data-journey-problem-overlay]');
    resetJourneyProblemState();
  }

  function activeJourneyProblemButton() {
    var buttons = Array.from(document.querySelectorAll('[data-action="open-journey-problem"]'));
    return buttons.find(function (button) {
      var screen = button.closest('.screen');
      return screen && screen.dataset.screen === currentScreen && !button.closest('[hidden]');
    }) || null;
  }

  function scrollToJourneyProblemButton() {
    var button = activeJourneyProblemButton();
    if (!button) return;
    var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    button.scrollIntoView({ behavior: reduced ? 'auto' : 'smooth', block: 'center' });
    button.focus({ preventScroll: true });
  }

  function openDialog(dialogSelector, focusSelector) {
    overlayReturnFocus = document.activeElement;
    activeDialog = document.querySelector(dialogSelector);
    document.querySelector('.site-header').inert = true;
    document.getElementById('main-content').inert = true;
    activeDialog.hidden = false;
    syncAirportResourceQaVisibility();
    var focusTarget = activeDialog.querySelector(focusSelector);
    if (focusTarget) focusTarget.focus();
  }

  function closeDialog(dialogSelector) {
    var dialog = document.querySelector(dialogSelector);
    dialog.hidden = true;
    document.querySelector('.site-header').inert = false;
    document.getElementById('main-content').inert = false;
    activeDialog = null;
    syncAirportResourceQaVisibility();
    if (overlayReturnFocus && document.contains(overlayReturnFocus)) overlayReturnFocus.focus();
  }

  function completedStepCodes() {
    if (!journeyContext) return [];
    var steps = [];
    if (journeyContext.checkedIn) steps.push('CHECK_IN');
    if (journeyContext.bagDropped) steps.push('BAG_DROP');
    if (journeyContext.securityCleared) steps.push('SECURITY');
    if (journeyContext.atGate) steps.push('GO_TO_GATE');
    if (journeyContext.boarded) steps.push('BOARD_NOW');
    if (journeyContext.landed) steps.push('IN_FLIGHT');
    if (journeyContext.baggageCollected) steps.push('BAGGAGE_CLAIM');
    if (journeyContext.exitedAirport) steps.push('EXIT_AIRPORT');
    return steps;
  }

  function effectiveShareActionCode() {
    if (currentScreen === 'confirm' && currentFlight) return 'CONFIRM_FLIGHT';
    if (!currentAction) return null;
    if (currentAction.actionCode) return currentAction.actionCode;
    if (currentAction.stepType === 'CHECK_IN_QUESTION') return 'CHECK_IN_QUESTION';
    if (currentAction.stepType === 'BAGGAGE_QUESTION') return 'BAGGAGE_QUESTION';
    if (currentAction.stepType === 'COMPLETE') return 'EXIT_AIRPORT';
    return null;
  }

  function canShareCurrentState() {
    var actionCode = effectiveShareActionCode();
    var shareableCodes = [
      'CONFIRM_FLIGHT', 'CHECK_IN_QUESTION', 'BAGGAGE_QUESTION', 'CHECK_IN', 'BAG_DROP',
      'SECURITY', 'GO_TO_GATE', 'WAIT', 'BOARD_NOW', 'IN_FLIGHT', 'BAGGAGE_CLAIM',
      'EXIT_AIRPORT', 'ASK_FOR_HELP'
    ];
    return Boolean(currentFlight
      && validDocumentFlightNumber(currentFlight.flightNumber)
      && validDocumentDepartureDate(currentFlight.departureDate)
      && /^[A-Z]{3}$/.test(String(currentFlight.departureAirport || '').trim().toUpperCase())
      && /^[A-Z]{3}$/.test(String(currentFlight.arrivalAirport || '').trim().toUpperCase())
      && shareableCodes.indexOf(actionCode) >= 0);
  }

  function renderShareEntryVisibility() {
    var available = canShareCurrentState();
    document.querySelectorAll('[data-share-entry]').forEach(function (button) {
      button.hidden = !available;
    });
    document.querySelectorAll('.confirm-assistance-actions, .step-assistance-actions').forEach(function (group) {
      group.classList.toggle('share-unavailable', !available);
    });
  }

  function shareDestinationValue(actionCode) {
    if (!currentAction || !currentFlight) return null;
    var code = actionCode || currentAction.actionCode;
    if (code === 'CHECK_IN') return currentFlight.checkInDeskOrZone || currentFlight.checkInCounter || null;
    if (code === 'BAG_DROP') return currentFlight.dedicatedBagDropDesk || currentFlight.checkInDeskOrZone || null;
    if (['GO_TO_GATE', 'WAIT', 'BOARD_NOW'].indexOf(code) >= 0) return currentFlight.gate || null;
    if (code === 'BAGGAGE_CLAIM') return currentFlight.arrivalBaggageBelt || null;
    if (['IN_FLIGHT', 'EXIT_AIRPORT'].indexOf(code) >= 0) return currentFlight.arrivalAirport || null;
    return currentAction.destination || null;
  }

  function safeSharePayload() {
    var shareActionCode = effectiveShareActionCode();
    if (!canShareCurrentState() || !shareActionCode) return null;
    return {
      flightNumber: normalizeFlightNumber(currentFlight.flightNumber),
      departureDate: currentFlight.departureDate || null,
      departureAirport: currentFlight.departureAirport || null,
      arrivalAirport: currentFlight.arrivalAirport || null,
      actionCode: shareActionCode,
      urgency: currentAction && currentAction.urgency || 'NORMAL',
      destination: shareDestinationValue(shareActionCode),
      completedSteps: completedStepCodes(),
      terminal: currentFlight.terminal || null,
      gate: currentFlight.gate || null,
      checkInDeskOrZone: currentFlight.checkInDeskOrZone || currentFlight.checkInCounter || null,
      dedicatedBagDropDesk: currentFlight.dedicatedBagDropDesk || null,
      arrivalBaggageBelt: currentFlight.arrivalBaggageBelt || null,
      status: currentFlight.status || null,
      lastUpdated: currentFlight.dataLastUpdatedAt || currentFlight.lastUpdated || new Date().toISOString(),
      completed: Boolean(currentAction && currentAction.stepType === 'COMPLETE') || Boolean(journeyContext && journeyContext.exitedAirport)
    };
  }

  function familyMessageForLocale(locale) {
    if (!currentFlight) {
      return locale === 'zh' ? '我正在使用一步乘机。'
        : locale === 'es' ? 'Estoy usando OneStep Flight.' : 'I am using OneStep Flight.';
    }
    var sharedFlight = formatFlightNumber(currentFlight.flightNumber);
    var route = (currentFlight.departureAirport || '—') + ' → ' + (currentFlight.arrivalAirport || '—');
    var step = currentAction ? actionTextForLocale(currentAction, 'title', locale)
      : locale === 'zh' ? '正在确认航班' : locale === 'es' ? 'Confirmando el vuelo' : 'Confirming the flight';
    if (locale === 'zh') return '航班 ' + sharedFlight + ' · ' + route + '\n当前步骤：' + step;
    if (locale === 'es') return 'Vuelo ' + sharedFlight + ' · ' + route + '\nPaso actual: ' + step;
    return 'Flight ' + sharedFlight + ' · ' + route + '\nCurrent step: ' + step;
  }

  function familyMessage() {
    var primary = familyMessageForLocale(currentLocale);
    return currentLocale === 'en' ? primary : primary + '\n\n' + familyMessageForLocale('en');
  }

  function familyShareJourneyKey(payload) {
    if (!payload) return '';
    return JSON.stringify({
      flightNumber: normalizeFlightNumber(payload.flightNumber),
      departureDate: payload.departureDate || null,
      departureAirport: payload.departureAirport || null,
      arrivalAirport: payload.arrivalAirport || null
    });
  }

  function shareSessionMatchesPayload(session, payload) {
    if (!session || !session.viewToken || !session.updateToken || !session.expiresAt || !payload) return false;
    if (demoRecordingSessionId && session.demoSessionId !== demoRecordingSessionId) return false;
    if (!demoRecordingSessionId && session.demoSessionId) return false;
    var expiresAt = Date.parse(session.expiresAt);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) return false;
    try {
      return familyShareJourneyKey(JSON.parse(session.lastFingerprint || 'null')) === familyShareJourneyKey(payload);
    } catch (error) {
      return false;
    }
  }

  function shareSessionMatchesFlight(session, flight) {
    if (!session || !flight) return false;
    try {
      return familyShareJourneyKey(JSON.parse(session.lastFingerprint || 'null'))
        === familyShareJourneyKey(flight);
    } catch (error) {
      return false;
    }
  }

  function pendingShareRevocations(storageKey) {
    var stored = readStoredJson(storageKey);
    var entries = Array.isArray(stored) ? stored : stored ? [stored] : [];
    return entries.filter(function (entry) {
      return entry && typeof entry.viewToken === 'string' && entry.viewToken
        && typeof entry.updateToken === 'string' && entry.updateToken;
    }).map(function (entry) {
      return { demoSessionId: entry.demoSessionId || null,
        viewToken: entry.viewToken, updateToken: entry.updateToken };
    });
  }

  function writePendingShareRevocations(storageKey, entries) {
    if (entries.length) localStorage.setItem(storageKey, JSON.stringify(entries));
    else localStorage.removeItem(storageKey);
  }

  function queueShareRevocation(session) {
    if (!session || !session.viewToken || !session.updateToken) return null;
    var storageKey = activeShareRevocationPendingStorageKey();
    var entry = { demoSessionId: session.demoSessionId || null,
      viewToken: session.viewToken, updateToken: session.updateToken };
    var entries = pendingShareRevocations(storageKey).filter(function (candidate) {
      return candidate.viewToken !== entry.viewToken;
    });
    entries.push(entry);
    writePendingShareRevocations(storageKey, entries);
    return { storageKey: storageKey, entry: entry };
  }

  function completeShareRevocation(storageKey, entry) {
    var entries = pendingShareRevocations(storageKey).filter(function (candidate) {
      return candidate.viewToken !== entry.viewToken;
    });
    writePendingShareRevocations(storageKey, entries);
  }

  function attemptShareRevocation(storageKey, entry) {
    if (!entry || !navigator.onLine) return Promise.resolve(false);
    return apiFetch('/api/share-sessions/' + encodeURIComponent(entry.viewToken), {
      method: 'DELETE', headers: { 'X-Share-Update-Token': entry.updateToken }
    }).then(function (response) {
      if (response.ok || response.status === 404 || response.status === 410) {
        completeShareRevocation(storageKey, entry);
        return true;
      }
      return false;
    }).catch(function () { return false; });
  }

  function revokeDiscardedShareSession(session) {
    var pending = queueShareRevocation(session);
    return pending ? attemptShareRevocation(pending.storageKey, pending.entry) : Promise.resolve(false);
  }

  function retryPendingShareRevocations() {
    var storageKey = activeShareRevocationPendingStorageKey();
    if (!navigator.onLine) return Promise.resolve([]);
    return Promise.all(pendingShareRevocations(storageKey).map(function (entry) {
      return attemptShareRevocation(storageKey, entry);
    }));
  }

  function resetFamilyShareSession() {
    shareCreationGeneration += 1;
    shareCreationInFlight = false;
    shareCreationPromise = null;
    shareCreationJourneyKey = null;
    window.clearTimeout(shareSyncTimer);
    shareSyncTimer = null;
    var previousSession = shareSession;
    if (previousSession && previousSession.viewToken && previousSession.updateToken) {
      revokeDiscardedShareSession(previousSession);
    }
    shareSession = null;
    localStorage.removeItem(activeShareSessionStorageKey());
    localStorage.removeItem(activeSharePendingStorageKey());
    publishDemoRecordingState();
  }

  function revokeDemoRecordingShare() {
    if (!demoRecordingSessionId || !shareSession || !shareSession.viewToken || !shareSession.updateToken) {
      return Promise.resolve();
    }
    return apiFetch('/api/share-sessions/' + encodeURIComponent(shareSession.viewToken), {
      method: 'DELETE', headers: { 'X-Share-Update-Token': shareSession.updateToken }
    }).then(function (response) {
      if (response.ok || response.status === 404 || response.status === 410) return;
      throw new Error('当前演示分享链接撤销失败。');
    });
  }

  function shareViewUrl() {
    if (!shareSessionMatchesPayload(shareSession, safeSharePayload())) return '';
    if (platform) return platform.publicShareUrl(shareSession.viewToken, shareSession.viewUrl);
    return new URL('/share/' + encodeURIComponent(shareSession.viewToken), window.location.origin).href;
  }

  function renderFamilyShareControls() {
    var active = shareSessionMatchesPayload(shareSession, safeSharePayload());
    var linkButton = document.querySelector('[data-action="share-family"]');
    if (linkButton) linkButton.disabled = !canShareCurrentState();
    var expiry = document.querySelector('[data-share-expiry]');
    if (expiry) expiry.textContent = active && shareSession.expiresAt
      ? localeText('有效至：', 'Active until: ', 'Activo hasta: ') + formatRetrieved(shareSession.expiresAt) : '';
    var warning = document.querySelector('[data-share-local-warning]');
    if (warning) warning.hidden = nativeApp || !/^(localhost|127\.0\.0\.1|\[::1\])$/i.test(window.location.hostname);
  }

  function setShareStatus(message) {
    var status = document.querySelector('[data-share-status]');
    if (status) status.textContent = message || '';
  }

  function showFamily() {
    if (!canShareCurrentState()) return;
    stopSpeech(false);
    renderFamilyShareControls();
    setShareStatus('');
    openDialog('[data-family-overlay]', '[data-action="close-family"]');
    createFamilyShare().catch(function () {});
    if (currentFlight) prepareFamilyCard().catch(function () {});
    else setPngPreparing(false);
  }

  function closeFamily() {
    closeDialog('[data-family-overlay]');
  }

  function createFamilyShare() {
    var payload = safeSharePayload();
    if (!payload) {
      setShareStatus(localeText('当前行程暂时无法分享。', 'This journey cannot be shared right now.', 'Este viaje no se puede compartir ahora.'));
      return Promise.resolve(null);
    }
    var journeyKey = familyShareJourneyKey(payload);
    if (shareSessionMatchesPayload(shareSession, payload)) return Promise.resolve(shareSession);
    if (shareSession) resetFamilyShareSession();
    if (shareCreationInFlight && shareCreationPromise && shareCreationJourneyKey === journeyKey) {
      return shareCreationPromise;
    }
    if (shareCreationInFlight) resetFamilyShareSession();
    var generation = ++shareCreationGeneration;
    shareCreationInFlight = true;
    shareCreationJourneyKey = journeyKey;
    setShareStatus(localeText('正在创建分享链接…', 'Creating the share link…', 'Creando el enlace para compartir…'));
    shareCreationPromise = postJson('/api/share-sessions', payload).then(function (result) {
      if (generation !== shareCreationGeneration || familyShareJourneyKey(safeSharePayload()) !== journeyKey) {
        revokeDiscardedShareSession(result);
        return null;
      }
      shareSession = {
        demoSessionId: demoRecordingSessionId || null,
        viewToken: result.viewToken,
        updateToken: result.updateToken,
        viewUrl: result.viewUrl,
        expiresAt: result.expiresAt,
        lastFingerprint: JSON.stringify(payload)
      };
      localStorage.setItem(activeShareSessionStorageKey(), JSON.stringify(shareSession));
      localStorage.removeItem(activeSharePendingStorageKey());
      renderFamilyShareControls();
      setShareStatus(localeText('分享链接已准备好。', 'The share link is ready.', 'El enlace para compartir está listo.'));
      publishDemoRecordingState();
      return shareSession;
    }).catch(function (error) {
      if (generation !== shareCreationGeneration) return null;
      var status = Number(error && error.status) || 0;
      if (status >= 500 && status <= 599) {
        setShareStatus(localeText('分享服务暂时不可用，请稍后重试。', 'The sharing service is temporarily unavailable. Please try again later.', 'El servicio para compartir no está disponible temporalmente. Inténtalo de nuevo más tarde.'));
      } else if (!status) {
        setShareStatus(localeText('网络连接失败，请检查网络后重试。', 'The network connection failed. Check your connection and try again.', 'Falló la conexión de red. Comprueba la conexión e inténtalo de nuevo.'));
      } else {
        setShareStatus(localeText('无法创建分享链接，请稍后重试。', 'The sharing link could not be created. Please try again later.', 'No se pudo crear el enlace para compartir. Inténtalo de nuevo más tarde.'));
      }
      return null;
    }).finally(function () {
      if (generation !== shareCreationGeneration) return;
      shareCreationInFlight = false;
      shareCreationPromise = null;
      shareCreationJourneyKey = null;
    });
    return shareCreationPromise;
  }

  function shareFamily() {
    var url = shareViewUrl();
    if (!nativeApp && !navigator.share) {
      setShareStatus(localeText('当前浏览器不支持手机分享菜单。', 'The mobile share menu is not supported in this browser.', 'Este navegador no admite el menú para compartir del móvil.'));
      return;
    }
    if (!url) {
      createFamilyShare().catch(function () {});
      setShareStatus(localeText('正在准备分享链接。准备好后，请再点一次“分享链接”。',
        'The share link is being prepared. When it is ready, select “Share link” again.',
        'Se está preparando el enlace para compartir. Cuando esté listo, pulsa de nuevo «Compartir enlace».'));
      return;
    }
    try {
      var shareData = { title: 'OneStep Flight', text: familyMessage(), url: url };
      var result = nativeApp ? platform.shareLink(shareData) : navigator.share(shareData);
      if (result && typeof result.catch === 'function') result.catch(function (error) {
        if (error && error.name === 'AbortError') return;
        setShareStatus(localeText('手机分享菜单暂时无法打开。', 'The mobile share menu could not be opened.', 'No se pudo abrir el menú para compartir del móvil.'));
      });
    } catch (error) {
      if (error && error.name === 'AbortError') return;
      setShareStatus(localeText('手机分享菜单暂时无法打开。', 'The mobile share menu could not be opened.', 'No se pudo abrir el menú para compartir del móvil.'));
    }
  }

  function syncFamilyShare() {
    if (!shareSession || !shareSession.viewToken || !shareSession.updateToken) return;
    var session = shareSession;
    var payload = safeSharePayload();
    if (!payload) return;
    if (!shareSessionMatchesPayload(session, payload)) {
      if (shareSession === session) resetFamilyShareSession();
      return;
    }
    var fingerprint = JSON.stringify(payload);
    if (fingerprint === session.lastFingerprint) return;
    if (!navigator.onLine || nativeApp && !platform.isActive()) {
      localStorage.setItem(activeSharePendingStorageKey(), JSON.stringify({
        demoSessionId: demoRecordingSessionId || null,
        viewToken: session.viewToken, updateToken: session.updateToken, payload: payload
      }));
      return;
    }
    fetchJson('/api/share-sessions/' + encodeURIComponent(session.viewToken), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Share-Update-Token': session.updateToken },
      body: JSON.stringify(payload)
    }).then(function () {
      if (shareSession !== session) return;
      session.lastFingerprint = fingerprint;
      localStorage.setItem(activeShareSessionStorageKey(), JSON.stringify(session));
      localStorage.removeItem(activeSharePendingStorageKey());
    }).catch(function () {
      if (shareSession !== session) return;
      localStorage.setItem(activeSharePendingStorageKey(), JSON.stringify({
        demoSessionId: demoRecordingSessionId || null,
        viewToken: session.viewToken, updateToken: session.updateToken, payload: payload
      }));
    });
  }

  function scheduleFamilyShareSync() {
    if (!shareSession) return;
    window.clearTimeout(shareSyncTimer);
    if (nativeApp && !platform.isActive()) { syncFamilyShare(); return; }
    shareSyncTimer = window.setTimeout(syncFamilyShare, 500);
  }

  function retryPendingFamilyShare() {
    var pending = readStoredJson(activeSharePendingStorageKey());
    if (!pending || !navigator.onLine) return;
    if (demoRecordingSessionId && pending.demoSessionId !== demoRecordingSessionId
        || !demoRecordingSessionId && pending.demoSessionId
        || !shareSession || pending.viewToken !== shareSession.viewToken) {
      localStorage.removeItem(activeSharePendingStorageKey());
      return;
    }
    syncFamilyShare();
  }

  function wrapCanvasText(context, text, x, y, maxWidth, lineHeight) {
    var source = String(text || '');
    var words = /\s/.test(source) ? source.split(/\s+/) : Array.from(source);
    var separator = /\s/.test(source) ? ' ' : '';
    var line = '';
    words.forEach(function (word) {
      var trial = line ? line + separator + word : word;
      if (line && context.measureText(trial).width > maxWidth) {
        context.fillText(line, x, y);
        y += lineHeight;
        line = word;
      } else line = trial;
    });
    if (line) context.fillText(line, x, y);
    return y + lineHeight;
  }

  function pngError(code, cause) {
    var error = new Error(code);
    error.code = code;
    error.cause = cause || null;
    return error;
  }

  function cardPayload() {
    var payload = safeSharePayload();
    if (payload) return payload;
    if (!currentFlight) return null;
    return {
      flightNumber: normalizeFlightNumber(currentFlight.flightNumber),
      departureDate: currentFlight.departureDate || null,
      departureAirport: currentFlight.departureAirport || null,
      arrivalAirport: currentFlight.arrivalAirport || null,
      terminal: currentFlight.terminal || null,
      gate: currentFlight.gate || null,
      checkInDeskOrZone: currentFlight.checkInDeskOrZone || currentFlight.checkInCounter || null,
      arrivalBaggageBelt: currentFlight.arrivalBaggageBelt || null
    };
  }

  function pngCacheKey() {
    var payload = cardPayload();
    return payload ? currentLocale + ':' + JSON.stringify(payload) + ':' + JSON.stringify(currentAction || null) : '';
  }

  function invalidatePngCard() {
    pngCardGeneration += 1;
    if (pngCardCache && pngCardCache.url) URL.revokeObjectURL(pngCardCache.url);
    pngCardCache = null;
    pngCardPreparing = null;
  }

  function dataUrlToPngBlob(dataUrl) {
    try {
      var encoded = String(dataUrl || '').split(',')[1];
      if (!encoded) throw new Error('missing PNG data');
      var binary = atob(encoded);
      var bytes = new Uint8Array(binary.length);
      for (var index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
      return new Blob([bytes], { type: 'image/png' });
    } catch (error) {
      throw pngError('PNG_TO_BLOB_FAILED', error);
    }
  }

  function canvasToPngBlob(canvas) {
    return new Promise(function (resolve, reject) {
      var settled = false;
      function fallback(cause) {
        if (settled) return;
        try {
          var blob = dataUrlToPngBlob(canvas.toDataURL('image/png'));
          settled = true;
          resolve(blob);
        } catch (error) {
          settled = true;
          reject(error.code ? error : pngError('PNG_TO_BLOB_FAILED', cause || error));
        }
      }
      var timer = window.setTimeout(function () { fallback(new Error('toBlob timeout')); }, 3000);
      try {
        if (typeof canvas.toBlob !== 'function') return fallback(new Error('toBlob unavailable'));
        canvas.toBlob(function (blob) {
          window.clearTimeout(timer);
          if (settled) return;
          if (blob) {
            settled = true;
            resolve(blob);
          } else fallback(new Error('toBlob returned null'));
        }, 'image/png');
      } catch (error) {
        window.clearTimeout(timer);
        fallback(error);
      }
    });
  }

  function createFamilyCardBlob() {
    var payload = cardPayload();
    if (!payload) return Promise.reject(pngError('PNG_RENDER_FAILED', new Error('journey unavailable')));
    var canvas;
    var context;
    try {
      canvas = document.createElement('canvas');
      canvas.width = 1080;
      canvas.height = 1350;
      context = canvas.getContext('2d');
      if (!context) throw new Error('2D canvas unavailable');
    } catch (error) {
      return Promise.reject(pngError('PNG_RENDER_FAILED', error));
    }
    try {
    context.fillStyle = '#F3F4F7';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#1B2537';
    context.fillRect(0, 0, canvas.width, 180);
    context.fillStyle = '#EAEBF2';
    context.font = '800 58px Arial, sans-serif';
    context.fillText('OneStep Flight', 80, 112);
    context.fillStyle = '#2C648E';
    context.fillRect(70, 235, 14, 930);
    context.fillStyle = '#1B2537';
    context.font = '800 62px Arial, sans-serif';
    context.fillText(formatFlightNumber(payload.flightNumber), 120, 330);
    context.font = '700 42px Arial, sans-serif';
    context.fillStyle = '#2A3747';
    context.fillText((payload.departureAirport || '—') + '  →  ' + (payload.arrivalAirport || '—'), 120, 405);
    context.font = '800 72px Arial, sans-serif';
    context.fillStyle = '#1B2537';
    var cardAction = currentAction ? actionTextForLocale(currentAction, 'title', currentLocale)
      : localeText('确认航班信息', 'Confirm flight information', 'Confirmar la información del vuelo');
    var y = wrapCanvasText(context, cardAction, 120, 545, 840, 88);
    context.font = '650 40px Arial, sans-serif';
    context.fillStyle = '#2A3747';
    y += 35;
    var safeFacts = [];
    if (payload.terminal) safeFacts.push(formatTerminalValue(payload.terminal));
    if (payload.checkInDeskOrZone) safeFacts.push(formatCheckInValue(payload.checkInDeskOrZone));
    if (payload.gate) safeFacts.push(formatGateValue(payload.gate));
    if (payload.arrivalBaggageBelt) safeFacts.push(formatBeltValue(payload.arrivalBaggageBelt));
    context.font = '700 42px Arial, sans-serif';
    context.fillStyle = '#2C648E';
    wrapCanvasText(context, safeFacts.join(' · '), 120, y + 45, 840, 58);
    context.fillStyle = '#576777';
    context.font = '600 30px Arial, sans-serif';
    wrapCanvasText(context, localeText('生成时间：', 'Generated: ', 'Generado: ') + new Intl.DateTimeFormat(localeLanguageTag(currentLocale), { dateStyle: 'medium', timeStyle: 'short' }).format(new Date()), 120, 1200, 840, 40);
    wrapCanvasText(context, localeText('请以机场屏幕和工作人员为准', 'Confirm with airport screens and staff', 'Confirma con las pantallas y el personal'), 120, 1260, 840, 40);
    } catch (error) {
      return Promise.reject(pngError('PNG_RENDER_FAILED', error));
    }
    return canvasToPngBlob(canvas);
  }

  function setPngPreparing(preparing) {
    var button = document.querySelector('[data-action="share-family-card"]');
    if (button) {
      button.disabled = preparing || !pngCardCache;
      button.textContent = preparing ? t('preparingPng') : t('sharePng');
    }
  }

  function reportPngError(error) {
    var code = error && error.code || 'PNG_RENDER_FAILED';
    console.error(code, error && error.cause ? error.cause.name || 'Error' : 'Error');
    var message = ['图片准备失败', 'Image could not be prepared', 'No se pudo preparar la imagen'];
    showToast(currentLocale === 'zh' ? message[0] : currentLocale === 'es' ? message[2] : message[1]);
  }

  function prepareFamilyCard() {
    var key = pngCacheKey();
    if (!key) {
      invalidatePngCard();
      setPngPreparing(false);
      return Promise.reject(pngError('PNG_RENDER_FAILED', new Error('journey unavailable')));
    }
    if (pngCardCache && pngCardCache.key === key) return Promise.resolve(pngCardCache);
    if (pngCardCache && pngCardCache.key !== key) invalidatePngCard();
    if (pngCardPreparing) return pngCardPreparing;
    setPngPreparing(true);
    var generation = pngCardGeneration;
    var preparation = createFamilyCardBlob().then(function (blob) {
      if (generation !== pngCardGeneration) {
        var cancelled = pngError('PNG_RENDER_FAILED', new Error('stale card generation'));
        cancelled.cancelled = true;
        throw cancelled;
      }
      var file;
      try {
        file = new File([blob], 'onestep-flight-journey.png', { type: 'image/png' });
      } catch (error) {
        throw pngError('PNG_FILE_FAILED', error);
      }
      pngCardCache = { key: key, blob: blob, file: file, url: URL.createObjectURL(blob) };
      return pngCardCache;
    }).catch(function (error) {
      if (generation === pngCardGeneration) pngCardCache = null;
      if (!error.cancelled && generation === pngCardGeneration) reportPngError(error);
      throw error;
    }).finally(function () {
      if (pngCardPreparing === preparation) {
        pngCardPreparing = null;
        setPngPreparing(false);
      }
    });
    pngCardPreparing = preparation;
    return preparation;
  }

  function isIosBrowser() {
    return /iPad|iPhone|iPod/.test(navigator.userAgent)
      || navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1;
  }

  function saveCachedPngFallback() {
    if (nativeApp) return platform.sharePng(pngCardCache.file).catch(function (error) {
      reportPngError(pngError('PNG_SHARE_FAILED', error));
    });
    if (isIosBrowser()) {
      setShareStatus(localeText('iOS 当前无法通过此浏览器分享图片文件。',
        'This browser on iOS cannot share the image file.',
        'Este navegador en iOS no puede compartir el archivo de imagen.'));
      return;
    }
    var link = document.createElement('a');
    link.href = pngCardCache.url;
    link.download = pngCardCache.file.name;
    document.body.appendChild(link);
    link.click();
    link.remove();
    showToast(localeText('图片已下载', 'Image downloaded', 'Imagen descargada'));
  }

  function shareFamilyCard() {
    if (!pngCardCache) {
      prepareFamilyCard().catch(function () {});
      setShareStatus(localeText('正在准备不含个人信息的图片，请稍后再点一次“分享图片”。',
        'The image without personal information is being prepared. Select “Share image” again when it is ready.',
        'Se está preparando la imagen sin información personal. Pulsa de nuevo «Compartir imagen» cuando esté lista.'));
      return;
    }
    try {
      if (nativeApp) { saveCachedPngFallback(); return; }
      if (navigator.share && navigator.canShare && navigator.canShare({ files: [pngCardCache.file] })) {
        var shareResult = navigator.share({ files: [pngCardCache.file], title: 'OneStep Flight' });
        if (shareResult && typeof shareResult.catch === 'function') shareResult.catch(function (error) {
          if (error && error.name === 'AbortError') return;
          reportPngError(pngError('PNG_SHARE_FAILED', error));
        });
        return;
      }
      saveCachedPngFallback();
    } catch (error) {
      if (error && error.name === 'AbortError') return;
      reportPngError(pngError('PNG_SHARE_FAILED', error));
    }
  }

  function showEndConfirmation() {
    stopSpeech(false);
    openDialog('[data-end-overlay]', '[data-action="cancel-end"]');
  }

  function closeEndConfirmation() {
    closeDialog('[data-end-overlay]');
  }

  function showBoardConfirmation() {
    openDialog('[data-board-overlay]', '[data-action="confirm-board"]');
    queueAutomaticSpeech();
  }

  function closeBoardConfirmation() {
    stopSpeech(false);
    closeDialog('[data-board-overlay]');
    lastAutomaticSpeechKey = speechViewKey();
  }

  function confirmBoarding() {
    closeBoardConfirmation();
    stopAutoRefresh(true);
    applyActionCompletion('BOARD_NOW');
  }

  function showLandedConfirmation() {
    openDialog('[data-landed-overlay]', '[data-action="confirm-landed"]');
    queueAutomaticSpeech();
  }

  function closeLandedConfirmation() {
    stopSpeech(false);
    closeDialog('[data-landed-overlay]');
    lastAutomaticSpeechKey = speechViewKey();
  }

  function confirmLanding() {
    closeLandedConfirmation();
    applyActionCompletion('IN_FLIGHT');
  }

  function activeJourneyStorageKey() {
    if (typeof demoRecordingSessionId !== 'undefined' && demoRecordingSessionId) {
      return DEMO_JOURNEY_PREFIX + demoRecordingSessionId;
    }
    return qaJourneyEnabled ? QA_STORAGE_KEY : STORAGE_KEY;
  }

  function activeOfflineJourneyStorageKey() {
    if (typeof demoRecordingSessionId !== 'undefined' && demoRecordingSessionId) {
      return DEMO_OFFLINE_PREFIX + demoRecordingSessionId;
    }
    return qaJourneyEnabled ? QA_OFFLINE_STORAGE_KEY : OFFLINE_STORAGE_KEY;
  }

  function activeShareSessionStorageKey() {
    if (typeof demoRecordingSessionId !== 'undefined' && demoRecordingSessionId) {
      return DEMO_SHARE_PREFIX + demoRecordingSessionId;
    }
    return qaJourneyEnabled ? QA_SHARE_SESSION_KEY : SHARE_SESSION_KEY;
  }

  function activeSharePendingStorageKey() {
    if (typeof demoRecordingSessionId !== 'undefined' && demoRecordingSessionId) {
      return DEMO_SHARE_PENDING_PREFIX + demoRecordingSessionId;
    }
    return qaJourneyEnabled ? QA_SHARE_PENDING_KEY : SHARE_PENDING_KEY;
  }

  function activeShareRevocationPendingStorageKey() {
    if (typeof demoRecordingSessionId !== 'undefined' && demoRecordingSessionId) {
      return DEMO_SHARE_REVOCATION_PENDING_PREFIX + demoRecordingSessionId;
    }
    return qaJourneyEnabled ? QA_SHARE_REVOCATION_PENDING_KEY : SHARE_REVOCATION_PENDING_KEY;
  }

  function savedJourneyModeMatches(saved) {
    if (typeof demoRecordingSessionId !== 'undefined' && demoRecordingSessionId) {
      return Boolean(saved && saved.demoSessionId === demoRecordingSessionId);
    }
    if (saved && saved.demoSessionId) return false;
    return !saved || typeof saved.qaJourney !== 'boolean'
      || saved.qaJourney === Boolean(qaJourneyEnabled);
  }

  function isLegacyQaJourneySnapshot(saved) {
    if (qaJourneyEnabled || demoFlightEnabled || demoScenariosEnabled || !saved
        || Object.prototype.hasOwnProperty.call(saved, 'qaJourney')) return false;
    var flight = saved.flight;
    return Boolean(flight && normalizeFlightNumber(flight.flightNumber) === 'BA117'
      && flight.demo === true && flight.demoScenario === 'NORMAL');
  }

  function clearJourney() {
    stopAutoRefresh(true);
    lastLockState = null; pushSyncFingerprint = null; serverSnapshotVersion = 0;
    endLiveJourney(familyShareJourneyKey(currentFlight));
    sessionStorage.removeItem(activeJourneyStorageKey());
    localStorage.removeItem(activeOfflineJourneyStorageKey());
    resetFamilyShareSession();
    invalidatePngCard();
    currentFlight = null;
    currentAction = null;
    activeDemoScenarioId = null;
    resetFlightChangePresentation();
    renderScenarioTestCards();
    journeyContext = null;
    pendingDocument = null;
    journeyHistory = [];
    journeyEvents = [];
    navigationHistory = [];
    currentError = null;
    lastLookupContext = null;
    conflictsConfirmed = false;
    clockAnchor = null;
    refreshStatusState = null;
    restoredFromOffline = false;
    offlineSnapshotSavedAt = null;
    currentDestinationCopy = null;
    checkInStatusAnswer = null;
    documentSegments = [];
    usableDocumentSegmentIndexes = [];
    selectedDocumentSegmentIndex = null;
    awaitingDocumentSelection = false;
    documentRootFields = {};
    documentRootReviewFields = [];
    documentRootReviewIssues = [];
    confirmedDocumentFlightNumberIndexes = [];
    flightCorrectionOpen = false;
    partialDocumentEntry = false;
    renderDeparturePassedConfirmation(null);
    if (flightRequestController) flightRequestController.abort();
    flightRequestController = null;
    setLookupBusy(false);
    stopSpeech(false);
  }

  function returnHome() {
    requestSequence += 1;
    clearInterval(loadingTimer);
    clearTimeout(loadingTimeout);
    clearJourney();
    fileInput.value = '';
    showScreen('welcome');
  }

  function persistJourney() {
    if (!currentFlight && !awaitingDocumentSelection && !partialDocumentEntry) return;
    try {
      if (!nativeApp) sessionStorage.setItem(activeJourneyStorageKey(), JSON.stringify({
        savedAt: Date.now(),
        demoSessionId: typeof demoRecordingSessionId !== 'undefined' ? demoRecordingSessionId || null : null,
        qaJourney: Boolean(qaJourneyEnabled),
        flight: currentFlight,
        action: currentAction,
        context: journeyContext,
        history: journeyHistory,
        events: journeyEvents,
        screen: currentScreen,
        locale: currentLocale,
        document: pendingDocument,
        documentSegments: documentSegments,
        usableDocumentSegmentIndexes: usableDocumentSegmentIndexes,
        selectedDocumentSegmentIndex: selectedDocumentSegmentIndex,
        awaitingDocumentSelection: awaitingDocumentSelection,
        partialDocumentEntry: partialDocumentEntry,
        documentRootFields: documentRootFields,
        documentRootReviewFields: documentRootReviewFields,
        documentRootReviewIssues: documentRootReviewIssues,
        conflictsConfirmed: conflictsConfirmed,
        clockAnchor: clockAnchor,
        refreshStatusState: refreshStatusState,
        checkInStatusAnswer: checkInStatusAnswer
      }));
    } catch (error) {
      // The current page remains usable if private browsing blocks session storage.
    }
    persistOfflineJourney();
    syncLiveActivity();
    scheduleFamilyShareSync();
    if (pngCardCache && pngCardCache.key !== pngCacheKey()) invalidatePngCard();
    var familyOverlay = document.querySelector('[data-family-overlay]');
    if (familyOverlay && !familyOverlay.hidden && currentFlight && !pngCardCache) prepareFamilyCard().catch(function () {});
    if (typeof publishDemoRecordingState === 'function') publishDemoRecordingState();
  }

  function pick(source, fields) {
    var result = {};
    fields.forEach(function (field) {
      if (source && source[field] !== undefined) result[field] = source[field];
    });
    return result;
  }

  function safeFlightSnapshot(flight) {
    if (!flight) return null;
    var safe = pick(flight, [
      'flightNumber', 'departureDate', 'departureAirport', 'departureAirportName',
      'arrivalAirport', 'arrivalAirportName', 'scheduledDeparture', 'estimatedDeparture',
      'actualDeparture', 'effectiveDeparture', 'scheduledArrival', 'estimatedArrival',
      'actualArrival', 'boardingTime', 'documentScheduledDeparture', 'documentBoardingTime',
      'documentTerminal', 'documentGate', 'documentDataSource', 'liveDataSource', 'terminal', 'arrivalTerminal', 'gate',
      'checkInCounter', 'checkInDeskOrZone', 'dedicatedBagDropDesk', 'arrivalBaggageBelt',
      'status', 'delayMinutes', 'arrivalDelayMinutes', 'dataLastUpdatedAt', 'dataRetrievedAt',
      'retrievedAt', 'freshestSourceUpdatedAt', 'live', 'demo', 'demoScenario',
      'demoSessionId', 'simulatedDemoChange',
      'documentFallback', 'liveStatusUnavailable', 'checkInCounterNeedsConfirmation', 'stale'
    ]);
    safe.timeContext = flight.timeContext ? pick(flight.timeContext, [
      'serverNow', 'departureLocalNow', 'departureUtcOffset', 'boardingTime',
      'scheduledDeparture', 'estimatedDeparture', 'actualDeparture', 'effectiveDeparture',
      'minutesUntilBoarding', 'minutesUntilScheduledDeparture', 'minutesUntilEstimatedDeparture',
      'minutesUntilEffectiveDeparture', 'dataAgeMinutes', 'dataLastUpdatedAt',
      'dataRetrievedAt', 'sourceUpdatedAtAvailable', 'stale'
    ]) : null;
    safe.latestChanges = [];
    safe.conflicts = [];
    safe.requiresUserConfirmation = false;
    return safe;
  }

  function normalizeSavedFlight(flight) {
    if (!flight) return null;
    var normalized = Object.assign({}, flight, { boardingPassDetected: false, latestChanges: [] });
    if (!normalized.liveDataSource
        && (normalized.documentFallback === true || normalized.liveStatusUnavailable === true)) {
      normalized.liveDataSource = 'DOCUMENT_FALLBACK';
    }
    if (isDocumentFallbackFlight(normalized)) {
      normalized.live = false;
      normalized.demo = false;
    }
    return normalized;
  }

  function safeActionSnapshot(action) {
    return action ? pick(action, ['stepType', 'actionCode', 'titleZh', 'descriptionZh',
      'titleEn', 'descriptionEn', 'titleEs', 'descriptionEs', 'destination', 'urgency', 'reason',
      'needsOnSiteConfirmation', 'lastUpdated']) : null;
  }

  function normalizeJourneyContext(context, flight) {
    if (!context) return null;
    var normalized = Object.assign({}, context);
    normalized.boardingPassDetected = false;
    ['checkedIn', 'bagDropped', 'securityCleared', 'atGate',
      'boarded', 'landed', 'baggageCollected', 'exitedAirport', 'needsAssistance',
      'conflictsConfirmed', 'refreshRequested'].forEach(function (field) {
      normalized[field] = Boolean(context[field]);
    });
    normalized.hasCheckedBaggage = context.hasCheckedBaggage == null
      ? null : Boolean(context.hasCheckedBaggage);
    normalized.flightSnapshot = flight;
    return normalized;
  }

  function safeContextSnapshot(context, flight) {
    if (!context) return null;
    var safe = pick(context, ['flightNumber', 'departureDate', 'demo', 'demoScenario',
      'documentType', 'checkedIn', 'hasCheckedBaggage', 'bagDropped',
      'securityCleared', 'atGate', 'boarded', 'landed', 'baggageCollected', 'exitedAirport',
      'needsAssistance', 'previousTerminal', 'previousGate', 'conflictsConfirmed', 'refreshRequested']);
    safe.flightSnapshot = flight;
    return normalizeJourneyContext(safe, flight);
  }

  function safeEventSnapshot(event) {
    var safe = pick(event, ['id', 'type', 'field', 'oldValue', 'newValue', 'occurredAt', 'reversible',
      'titleZh', 'titleEn', 'titleEs', 'details']);
    safe.params = event && event.params && typeof event.params === 'object'
      ? pick(event.params, ['answer', 'actionCode', 'reason', 'flightNumber', 'titleZh', 'titleEn', 'titleEs']) : {};
    return safe;
  }

  function normalizeJourneyEvent(event) {
    if (!event || typeof event !== 'object' || !event.type) return null;
    var normalized = safeEventSnapshot(event);
    if (String(normalized.type).indexOf('ACTION_COMPLETED_') === 0) {
      normalized.params.actionCode = String(normalized.type).substring('ACTION_COMPLETED_'.length);
      normalized.type = 'ACTION_COMPLETED';
    }
    normalized.id = normalized.id || normalized.type + ':' + (normalized.occurredAt || calibratedNowIso());
    normalized.occurredAt = normalized.occurredAt || calibratedNowIso();
    normalized.reversible = Boolean(normalized.reversible);
    return normalized;
  }

  function safeHistorySnapshot(history, safeFlight) {
    return (Array.isArray(history) ? history : []).map(function (entry) {
      return {
        context: safeContextSnapshot(entry.context, safeFlight),
        action: safeActionSnapshot(entry.action),
        checkInStatusAnswer: entry.checkInStatusAnswer == null ? null : entry.checkInStatusAnswer,
        reversibleEvents: (Array.isArray(entry.reversibleEvents) ? entry.reversibleEvents : [])
          .map(normalizeJourneyEvent).filter(Boolean)
      };
    });
  }

  function persistOfflineJourney() {
    if (!currentFlight || ((!currentAction || !journeyContext) && !nativeApp)) return;
    try {
      var safeFlight = safeFlightSnapshot(currentFlight);
      // Native snapshots also cover the confirmation screen. Preserve its stop condition
      // without retaining OCR values or document contents from an unresolved conflict.
      if (nativeApp && currentFlight.requiresUserConfirmation && !conflictsConfirmed) {
        safeFlight.requiresUserConfirmation = true;
        safeFlight.conflicts = [{ field: (currentFlight.conflicts || []).filter(function (c) { return c.blocking; })[0]?.field || 'flightNumber', blocking: true,
          messageZh: '航班信息仍有冲突，请与机场工作人员核对后再继续。',
          messageEn: 'Flight details still conflict. Confirm with airport staff before continuing.' }];
      }
      localStorage.setItem(activeOfflineJourneyStorageKey(), JSON.stringify({
        version: 1,
        savedAt: Math.min(Date.now(), restoredFromOffline && offlineSnapshotSavedAt || Date.now(),
          Date.parse(currentFlight && (currentFlight.dataRetrievedAt || currentFlight.retrievedAt
            || currentFlight.timeContext && currentFlight.timeContext.dataRetrievedAt) || '') || Date.now()),
        demoSessionId: typeof demoRecordingSessionId !== 'undefined' ? demoRecordingSessionId || null : null,
        qaJourney: Boolean(qaJourneyEnabled),
        flight: safeFlight,
        lockState: lastLockState,
        snapshotVersion: serverSnapshotVersion,
        action: safeActionSnapshot(currentAction),
        context: safeContextSnapshot(journeyContext, safeFlight),
        history: safeHistorySnapshot(journeyHistory, safeFlight),
        events: journeyEvents.map(safeEventSnapshot),
        screen: currentScreen,
        locale: currentLocale,
        conflictsConfirmed: conflictsConfirmed,
        checkInStatusAnswer: checkInStatusAnswer,
        clockAnchor: clockAnchor
      }));
    } catch (error) {
      // Storage can be unavailable or full; online guidance still works.
    }
  }

  function applySavedJourney(saved, fromOfflineStorage) {
    var restoringDocumentWorkflow = Boolean(saved && !fromOfflineStorage && saved.document
      && Array.isArray(saved.documentSegments) && saved.documentSegments.length
      && (saved.awaitingDocumentSelection || saved.partialDocumentEntry));
    var restoringDocumentSelection = restoringDocumentWorkflow && Boolean(saved.awaitingDocumentSelection);
    if (!saved || !savedJourneyModeMatches(saved)
        || !saved.flight && !restoringDocumentWorkflow) return false;
    if (saved.savedAt && Date.now() - Number(saved.savedAt) > OFFLINE_MAX_AGE_MS
        && (!saved.lockState || saved.lockState.journeyEndsAt * 1000 <= Date.now())) return false;
    currentFlight = normalizeSavedFlight(saved.flight);
    activeDemoScenarioId = demoScenariosEnabled && currentFlight ? currentFlight.demoScenario || null : null;
    journeyContext = normalizeJourneyContext(saved.context, currentFlight);
    currentAction = saved.action || null;
    lastLockState = saved.lockState || null;
    serverSnapshotVersion = saved.snapshotVersion || 0;
    journeyHistory = (Array.isArray(saved.history) ? saved.history : []).map(function (entry) {
      return {
        context: normalizeJourneyContext(entry.context, currentFlight),
        action: entry.action || null,
        checkInStatusAnswer: entry.checkInStatusAnswer == null ? null : entry.checkInStatusAnswer,
        reversibleEvents: (Array.isArray(entry.reversibleEvents) ? entry.reversibleEvents : [])
          .map(normalizeJourneyEvent).filter(Boolean)
      };
    });
    journeyEvents = (Array.isArray(saved.events) ? saved.events : [])
      .map(normalizeJourneyEvent).filter(Boolean);
    pendingDocument = fromOfflineStorage ? null : saved.document || null;
    documentSegments = (Array.isArray(saved.documentSegments) ? saved.documentSegments
      : pendingDocument && Array.isArray(pendingDocument.segments) ? pendingDocument.segments : [])
      .map(normalizeDocumentSegment);
    usableDocumentSegmentIndexes = Array.isArray(saved.usableDocumentSegmentIndexes)
      ? saved.usableDocumentSegmentIndexes.filter(function (index) {
        return Number.isInteger(index) && index >= 0 && index < documentSegments.length;
      })
      : documentSegments.reduce(function (indexes, segment, index) {
        if (validDocumentFlightNumber(segment && segment.flightNumber)
            && validDocumentDepartureDate(segment && segment.departureDate)) indexes.push(index);
        return indexes;
      }, []);
    awaitingDocumentSelection = restoringDocumentSelection;
    partialDocumentEntry = restoringDocumentWorkflow && Boolean(saved.partialDocumentEntry);
    selectedDocumentSegmentIndex = awaitingDocumentSelection ? null
      : Number.isInteger(saved.selectedDocumentSegmentIndex)
        ? saved.selectedDocumentSegmentIndex
        : usableDocumentSegmentIndexes.length ? usableDocumentSegmentIndexes[0] : null;
    documentRootFields = Object.assign({}, saved.documentRootFields || pendingDocument && pendingDocument.fields || {});
    documentRootReviewFields = Array.isArray(saved.documentRootReviewFields)
      ? saved.documentRootReviewFields.slice()
      : pendingDocument && Array.isArray(pendingDocument.needsReviewFields) ? pendingDocument.needsReviewFields.slice() : [];
    documentRootReviewIssues = Array.isArray(saved.documentRootReviewIssues)
      ? saved.documentRootReviewIssues.map(normalizeReviewIssue).filter(Boolean)
      : pendingDocument && Array.isArray(pendingDocument.reviewIssues)
        ? pendingDocument.reviewIssues.map(normalizeReviewIssue).filter(Boolean) : [];
    confirmedDocumentFlightNumberIndexes = documentSegments.reduce(function (indexes, segment, index) {
      if (segment && segment.flightNumberConfirmedByUser) indexes.push(index);
      return indexes;
    }, []);
    syncUsableDocumentSegmentIndexes();
    if (!awaitingDocumentSelection && !Number.isInteger(selectedDocumentSegmentIndex)
        && usableDocumentSegmentIndexes.length) selectedDocumentSegmentIndex = usableDocumentSegmentIndexes[0];
    conflictsConfirmed = Boolean(saved.conflictsConfirmed);
    clockAnchor = saved.clockAnchor || null;
    refreshStatusState = fromOfflineStorage && !navigator.onLine ? { code: 'OFFLINE_SNAPSHOT', details: {} }
      : restorableRefreshStatus(saved.refreshStatusState);
    checkInStatusAnswer = journeyContext && journeyContext.checkedIn ? 'yes'
      : saved.checkInStatusAnswer === 'no' || saved.checkInStatusAnswer === 'unsure'
        ? saved.checkInStatusAnswer : null;
    restoredFromOffline = fromOfflineStorage;
    offlineSnapshotSavedAt = fromOfflineStorage ? Number(saved.savedAt) : null;
    flightCorrectionOpen = false;
    lastLookupContext = currentFlight ? {
      flightNumber: normalizeFlightNumber(currentFlight.flightNumber),
      departureDate: journeyContext && journeyContext.departureDate || currentFlight.departureDate || null,
      departureAirport: currentFlight.departureAirport || null,
      arrivalAirport: currentFlight.arrivalAirport || null,
      documentSegmentIndex: selectedDocumentSegmentIndex
    } : null;
    if (awaitingDocumentSelection) {
      renderDeparturePassedConfirmation(null);
      renderSegmentSelector();
      showScreen('confirm');
      renderConnectivityStatus();
      return true;
    }
    if (partialDocumentEntry && Number.isInteger(selectedDocumentSegmentIndex)) {
      populateManualDocumentEntry();
      renderManualDocumentEntry();
      showScreen('manual');
      renderConnectivityStatus();
      return true;
    }
    if (journeyContext && journeyContext.boarded
        && (!currentAction || currentAction.stepType === 'COMPLETE' && !journeyContext.exitedAirport)) {
      currentAction = buildPostBoardAction();
    }
    renderFlight(currentFlight);
    if (currentAction) renderJourneyResponse();
    else showScreen('confirm');
    renderConnectivityStatus();
    return true;
  }

  function restoreJourney() {
    var journeyStorageKey = activeJourneyStorageKey();
    try {
      var saved = nativeApp ? null : JSON.parse(sessionStorage.getItem(journeyStorageKey));
      if (saved && savedJourneyModeMatches(saved) && !isLegacyQaJourneySnapshot(saved)
          && applySavedJourney(saved, false)) return true;
      if (saved) sessionStorage.removeItem(journeyStorageKey);
    } catch (error) {
      sessionStorage.removeItem(journeyStorageKey);
    }
    var offlineJourneyStorageKey = activeOfflineJourneyStorageKey();
    try {
      var offline = JSON.parse(localStorage.getItem(offlineJourneyStorageKey));
      if (!offline || !Number.isFinite(Number(offline.savedAt)) || Number(offline.savedAt) <= 0
          || Number(offline.savedAt) > Date.now() + 60000 || Date.now() - Number(offline.savedAt) > OFFLINE_MAX_AGE_MS
          || !savedJourneyModeMatches(offline) || isLegacyQaJourneySnapshot(offline)) {
        if (nativeApp && offline && Date.now() - Number(offline.savedAt) > OFFLINE_MAX_AGE_MS) expiredJourneyDiscarded = true;
        localStorage.removeItem(offlineJourneyStorageKey);
        return false;
      }
      if (applySavedJourney(offline, true)) return true;
      localStorage.removeItem(offlineJourneyStorageKey);
      return false;
    } catch (error) {
      localStorage.removeItem(offlineJourneyStorageKey);
      return false;
    }
  }

  document.addEventListener('click', function (event) {
    if (nativeApp) {
      var link = event.target.closest('a[href]');
      if (link && !link.getAttribute('href').startsWith('#')) {
        event.preventDefault();
        platform.openExternal(link.href).catch(function () {
          showToast(localeText('无法打开此链接。', 'Unable to open this link.', 'No se puede abrir este enlace.'));
        });
        return;
      }
    }
    var actionElement = event.target.closest('[data-action]');
    if (!actionElement) return;
    var action = actionElement.dataset.action;
    if (action === 'open-live-settings' && nativeApp) platform.push.openSettings().catch(function () {});
    if (action === 'upload') {
      fileInput.value = '';
      fileInput.click();
    }
    if (action === 'choose-file') fileInput.click();
    if (action === 'choose-other-file') {
      fileInput.value = '';
      fileInput.click();
    }
    if (action === 'home') {
      if (currentScreen === 'manual' && partialDocumentEntry && documentSegments.length) {
        returnToDocumentSegmentSelection();
      } else if (currentScreen === 'confirm' && documentSegments.length
          && selectedDocumentSegmentIndex != null) returnToDocumentSegmentSelection();
      else if ((journeyContext || currentAction) && currentScreen !== 'welcome') showEndConfirmation();
      else returnHome();
    }
    if (action === 'journey-back') goBack();
    if (action === 'select-segment') selectDocumentSegment(actionElement.dataset.segmentIndex);
    if (action === 'select-review-candidate') selectDocumentReviewCandidate(actionElement);
    if (action === 'manual-review-entry') showManualReviewEntry(actionElement);
    if (action === 'manual-document-help') showManualDocumentHelp();
    if (action === 'manual') {
      resetManualDocumentEntry();
      if (currentFlight) {
        document.getElementById('flight-input').value = currentFlight.flightNumber || '';
        setDateInputValue(document.getElementById('date-input'), currentFlight.departureDate);
      }
      navigate('manual');
    }
    if (action === 'unusable-manual') {
      resetManualDocumentEntry();
      document.getElementById('flight-input').value = '';
      setDateInputValue(document.getElementById('date-input'), '');
      navigate('manual');
    }
    if (action === 'demo-flight' && demoFlightEnabled) {
      startFlightLookup('BA117', qaJourneyDepartureDate(), true, null, 'NORMAL');
    }
    if (action === 'scenario') {
      var today = new Date().toISOString().slice(0, 10);
      startFlightLookup('BA117', today, true, null, actionElement.dataset.scenario);
    }
    if (action === 'scenario-refresh') triggerDemoScenarioRefresh();
    if (action === 'scenario-restart') restartDemoScenario();
    if (action === 'continue') {
      if (awaitingDocumentSelection || !currentFlight || selectedDocumentSegmentIndex == null && documentSegments.length > 1) {
        announce(t('segmentSelectionTitle'));
        return;
      }
	  if (isDocumentFallbackFlight(currentFlight)) {
		announce(localeText('上传文件中的信息尚未经过实时航班验证，不能进入值机流程。',
		  'The uploaded document has not been verified against a live flight, so check-in guidance cannot start.',
		  'El documento subido no se ha verificado con un vuelo real, por lo que no se puede iniciar la guía de facturación.'));
		return;
	  }
      if (departureStopReason(currentFlight)) {
        renderDeparturePassedConfirmation(currentFlight);
        showScreen('confirm');
        var stopCopy = departureStopCopy(departureStopReason(currentFlight));
        announce(compactSpeechParts([stopCopy.title, stopCopy.lead]));
        return;
      }
      if (!currentFlight.timeContext && !isDocumentFallbackFlight(currentFlight)) {
        announce(localeText('请等待航班查询完成。', 'Wait for the flight lookup to finish.', 'Espera a que termine la búsqueda del vuelo.'));
        return;
      }
      if (currentFlight.requiresUserConfirmation && !conflictsConfirmed) {
        announce(localeText('请先停止操作，并向机场工作人员核对冲突。', 'Stop and confirm the conflict with airport staff first.', 'Detente y confirma el conflicto con el personal del aeropuerto.'));
        return;
      }
      journeyContext = journeyContext || createJourneyContext();
      if (!journeyEvents.some(function (item) { return item.type === 'FLIGHT_CONFIRMED'; })) {
        recordJourneyEvent('FLIGHT_CONFIRMED', {
          reversible: true,
          params: { flightNumber: currentFlight.flightNumber }
        });
      }
      persistJourney();
      showCheckInQuestion();
    }
    if (action === 'confirm-conflicts') {
      conflictsConfirmed = true;
      if (journeyContext) journeyContext.conflictsConfirmed = true;
      recordJourneyEvent('CONFLICT_CONFIRMED_WITH_STAFF', { reversible: true });
      renderFlight(currentFlight);
      persistJourney();
      announce(localeText('已记录冲突核对，现在可以继续。', 'Conflict confirmation saved. You may continue.', 'Conflicto confirmado. Ya puedes continuar.'));
    }
    if (action === 'reject-flight') openFlightCorrection();
    if (action === 'select-flight-candidate') startFlightLookup(
      actionElement.dataset.flightNumber, actionElement.dataset.departureDate, false, null);
    if (action === 'checkin-status') answerCheckIn(actionElement.dataset.answer);
    if (action === 'baggage') answerBaggage(actionElement.dataset.answer);
    if (action === 'complete-action') completeCurrentAction();
    if (action === 'retry' && retryRequest) retryRequest();
    if (action === 'speak') speak();
    if (action === 'staff') showStaff();
    if (action === 'open-airport-map') openAirportResource('MAP', actionElement);
    if (action === 'contact-airport-resource') openAirportResource('CONTACT', actionElement);
    if (action === 'close-staff') closeStaff();
    if (action === 'back-to-staff-card') returnToStaffCard();
    if (action === 'retry-airport-resource') retryAirportResource();
    if (action === 'family') showFamily();
    if (action === 'close-family') closeFamily();
    if (action === 'share-family') shareFamily();
    if (action === 'share-family-card') shareFamilyCard();
    if (action === 'end-journey') showEndConfirmation();
    if (action === 'cancel-end') closeEndConfirmation();
    if (action === 'confirm-end') { closeEndConfirmation(); returnHome(); }
    if (action === 'cancel-board') closeBoardConfirmation();
    if (action === 'confirm-board') confirmBoarding();
    if (action === 'cancel-landed') closeLandedConfirmation();
    if (action === 'confirm-landed') confirmLanding();
    if (action === 'finish-home') returnHome();
    if (action === 'refresh-flight') refreshFlight({ source: 'manual' });
    if (action === 'refresh-arrival') refreshArrivalInfo({ source: 'manual' });
    if (action === 'dismiss-flight-change') dismissFlightChangeNotice();
    if (action === 'new-flight-info') beginNewFlightInformation();
    if (action === 'open-journey-problem') openJourneyProblem();
    if (action === 'close-journey-problem') closeJourneyProblem();
    if (action === 'journey-problem-connection') {
      chooseJourneyProblemConnection(actionElement.dataset.connection === 'true');
    }
    if (action === 'journey-problem-voice') startJourneyProblemVoiceInput();
    if (action === 'scroll-to-journey-problem') scrollToJourneyProblemButton();
  });

  fileInput.addEventListener('change', function () {
    if (fileInput.files.length) uploadDocument(fileInput.files[0]);
  });

  document.querySelectorAll('input[type="date"]').forEach(function (input) {
    input.addEventListener('input', syncVisibleDates);
    input.addEventListener('change', syncVisibleDates);
  });

  document.querySelector('[data-locale-select]').addEventListener('change', function (event) {
    languageChangeInProgress = true;
    stopSpeech(false);
    currentLocale = normalizeLocale(event.target.value);
    storeLocale(currentLocale);
    updateLanguage();
    if (staffOverlayView === 'RESOURCE') renderAirportResourceView();
    lastAutomaticSpeechKey = speechViewKey();
    languageChangeInProgress = false;
    syncAirportResourcesContext();
  });

  document.querySelector('[data-action="manual-submit"]').addEventListener('submit', function (event) {
    event.preventDefault();
    var flightInput = document.getElementById('flight-input');
    var dateInput = document.getElementById('date-input');
    var flightConfirmation = document.getElementById('flight-confirm-input');
    var dateConfirmation = document.getElementById('date-confirm-input');
    flightInput.setCustomValidity('');
    dateInput.setCustomValidity('');
    if (flightConfirmation) flightConfirmation.setCustomValidity('');
    if (dateConfirmation) dateConfirmation.setCustomValidity('');
    var flightNumber = flightInput.value;
    var departureDate = dateInput.value;
    if (partialDocumentEntry && pendingDocument && Number.isInteger(selectedDocumentSegmentIndex)) {
      var index = selectedDocumentSegmentIndex;
      var segment = documentSegments[index] || {};
      var manualPrimary = document.querySelector('[data-manual-primary]');
      if (documentSegmentLookupReady(segment, index)
          && manualPrimary && manualPrimary.dataset.mode === 'query') {
        var readyResult = selectedDocumentResult(pendingDocument, index);
        startFlightLookup(segment.flightNumber, segment.departureDate, false, readyResult);
        return;
      }
      var blockingIssues = documentSegmentReviewIssues(index, null, null, segment)
        .filter(reviewIssueIsBlockingCritical);
      var flightNeedsUpdate = !validDocumentFlightNumber(segment.flightNumber)
        || blockingIssues.some(function (issue) { return issue.field === 'flight_number'; });
      var dateNeedsUpdate = !validDocumentDepartureDate(segment.departureDate)
        || blockingIssues.some(function (issue) { return issue.field === 'departure_date'; });
      flightNumber = flightNeedsUpdate ? flightNumber : segment.flightNumber;
      departureDate = dateNeedsUpdate ? departureDate : segment.departureDate;
      if (!validDocumentFlightNumber(flightNumber)) {
        flightInput.setCustomValidity(localeText(
          '请输入有效航班号。', 'Enter a valid flight number.', 'Introduce un número de vuelo válido.'));
        flightInput.reportValidity();
        return;
      }
      if (!validDocumentDepartureDate(departureDate)) {
        dateInput.setCustomValidity(localeText(
          '请输入真实存在的出发日期。', 'Enter a real departure date.', 'Introduce una fecha de salida válida.'));
        dateInput.reportValidity();
        return;
      }
      if (flightConfirmation && !flightConfirmation.disabled && !flightConfirmation.checked) {
        flightConfirmation.setCustomValidity(localeText(
          '请明确确认这个航班号。', 'Explicitly confirm this flight number.', 'Confirma expresamente este número de vuelo.'));
        var flightCandidate = document.querySelector('[data-review-candidate-group="flight_number"] .review-candidate');
        if (flightCandidate && document.querySelector('[data-manual-flight-field]').hidden) {
          flightCandidate.focus();
          announce(flightConfirmation.validationMessage);
        } else flightConfirmation.reportValidity();
        return;
      }
      if (dateConfirmation && !dateConfirmation.disabled && !dateConfirmation.checked) {
        dateConfirmation.setCustomValidity(localeText(
          '请明确确认这个出发日期。', 'Explicitly confirm this departure date.', 'Confirma expresamente esta fecha de salida.'));
        var dateCandidate = document.querySelector('[data-review-candidate-group="departure_date"] .review-candidate');
        if (dateCandidate && document.querySelector('[data-manual-date-field]').hidden) {
          dateCandidate.focus();
          announce(dateConfirmation.validationMessage);
        } else dateConfirmation.reportValidity();
        return;
      }
      var completed = applyDocumentSegmentCompletion(index, flightNumber, departureDate, {
        flightNumber: Boolean(flightConfirmation && flightConfirmation.checked),
        departureDate: Boolean(dateConfirmation && dateConfirmation.checked)
      });
      if (!completed) {
        dateInput.setCustomValidity(localeText(
          '请输入与文件月日一致的真实出发日期。',
          'Enter a valid departure date matching the recognized month and day.',
          'Introduce una fecha válida que coincida con el mes y el día reconocidos.'));
        dateInput.reportValidity();
        return;
      }
      returnToDocumentSegmentSelection();
      return;
    }
    startFlightLookup(flightNumber, departureDate, false, null);
  });

  document.querySelector('[data-journey-problem-form]').addEventListener('submit', function (event) {
    event.preventDefault();
    submitJourneyProblem();
  });
  document.querySelector('[data-journey-problem-statement]').addEventListener('input', updateJourneyProblemSubmitState);

  ['flight-input', 'date-input', 'flight-confirm-input', 'date-confirm-input'].forEach(function (id) {
    var input = document.getElementById(id);
    if (input) input.addEventListener('input', function () { input.setCustomValidity(''); });
  });

  ['flight-input', 'date-input'].forEach(function (id) {
    var input = document.getElementById(id);
    input.addEventListener('input', function () {
      if (!partialDocumentEntry) return;
      var field = id === 'flight-input' ? 'flight_number' : 'departure_date';
      var confirmation = document.getElementById(field === 'flight_number'
        ? 'flight-confirm-input' : 'date-confirm-input');
      if (confirmation) confirmation.checked = false;
      document.querySelectorAll('[data-action="select-review-candidate"][data-review-field="'
        + field + '"]').forEach(function (button) { button.setAttribute('aria-pressed', 'false'); });
    });
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Tab' && activeDialog) {
      var focusable = Array.from(activeDialog.querySelectorAll(
        'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'))
        .filter(function (element) { return !element.hidden && element.offsetParent !== null; });
      if (focusable.length) {
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first.focus();
        }
      }
      return;
    }
    if (event.key !== 'Escape') return;
    if (!document.querySelector('[data-journey-problem-overlay]').hidden) closeJourneyProblem();
    else if (!document.querySelector('[data-staff-overlay]').hidden) closeStaff();
    else if (!document.querySelector('[data-family-overlay]').hidden) closeFamily();
    else if (!document.querySelector('[data-board-overlay]').hidden) closeBoardConfirmation();
    else if (!document.querySelector('[data-landed-overlay]').hidden) closeLandedConfirmation();
    else if (!document.querySelector('[data-end-overlay]').hidden) closeEndConfirmation();
  });

  function refreshAfterReturnToPage() {
    if (nativeApp) { platform.resume(); return; }
    resumeAutoRefresh();
  }

  function liveActivityContent() {
    if (!currentFlight || !journeyContext || journeyContext.exitedAirport || currentAction && currentAction.stepType === 'COMPLETE') return null;
    var identityConflict = (currentFlight.conflicts || []).some(function (c) {
      return c.blocking && !c.field || ['flightNumber', 'departureDate', 'departureAirport', 'arrivalAirport'].indexOf(c.field) >= 0;
    });
    if (identityConflict && !conflictsConfirmed) return null;
    var key = nativeJourneyKey(currentFlight);
    if (lastLockState && lastLockState.journeyKey !== key) lastLockState = null;
    var nativeSnapshot = platform.push && platform.push.current();
    var nativeDisplay = nativeSnapshot && nativeSnapshot.displayState;
    var retrieved = Math.floor(Date.parse(currentFlight.dataRetrievedAt || currentFlight.retrievedAt || '') / 1000) || 0;
    if (nativeDisplay && nativeSnapshot.journeyKey === key && nativeDisplay.snapshotVersion >= serverSnapshotVersion
        && nativeDisplay.updatedAt >= retrieved) {
      serverSnapshotVersion = nativeDisplay.snapshotVersion;
      var facts = Object.assign({}, lastLockState && lastLockState.facts, nativeDisplay.facts);
      var factsChanged = false;
      ['gate','terminal','arrivalTerminal','arrivalBaggageBelt','status','boardingTime','documentBoardingTime','documentScheduledDeparture',
        'scheduledDeparture','estimatedDeparture','actualDeparture','scheduledArrival','estimatedArrival','actualArrival'].forEach(function (field) {
        if (facts[field]) { if (currentFlight[field] !== facts[field]) factsChanged = true; currentFlight[field] = facts[field]; }
      });
      if (facts.boardingTime) currentFlight.timeContext = Object.assign({}, currentFlight.timeContext, { boardingTime: facts.boardingTime });
      if (nativeDisplay.updatedAt > 0) currentFlight.dataRetrievedAt = new Date(nativeDisplay.updatedAt * 1000).toISOString();
      if (nativeDisplay.staleAt > 600) currentFlight.dataLastUpdatedAt = new Date((nativeDisplay.staleAt - 600) * 1000).toISOString();
      lastLockState = Object.assign({}, nativeDisplay, { facts: facts });
      if (factsChanged) {
        renderFlight(currentFlight, { silent: true, skipChanges: true });
        if (currentScreen === 'step' && currentAction) renderJourneyResponse({ silent: true });
      }
    }
    lastLockState = window.OneStepFlightDisplay.build(currentFlight, currentAction, journeyContext, {
      previous: lastLockState, journeyKey: key, locale: currentLocale, conflictsConfirmed: conflictsConfirmed,
      offline: !platform.isOnline(), stale: restoredFromOffline, snapshotVersion: serverSnapshotVersion,
      clientRevision: lastLockState && lastLockState.clientRevision || 0
    });
    lastLockState.demo = Boolean(currentFlight.demo || qaJourneyEnabled || demoRecordingSessionId);
    persistOfflineJourney();
    return lastLockState;
  }

  function nativeJourneyKey(flight) {
    var key = familyShareJourneyKey(flight);
    return key && flight && (flight.demo || qaJourneyEnabled || demoRecordingSessionId) ? 'demo:' + key : key;
  }

  function renderLiveActivityStatus() {
    var notice = document.querySelector('[data-live-activity-status]');
    if (!notice || !liveActivity) return;
    var state = liveActivity.current();
    var disabled = state.supported && !state.enabled;
    var dismissed = state.dismissedJourneyKey === nativeJourneyKey(currentFlight);
    var active = state.active && state.journeyKey === nativeJourneyKey(currentFlight);
    notice.hidden = !journeyContext;
    var message = disabled
      ? platform.nativePlatform === 'android' ? localeText('通知权限或行程渠道已关闭。', 'Notifications or the journey channel are disabled.', 'Las notificaciones o el canal del viaje están desactivados.') : localeText('实时活动已被系统禁用。', 'Live Activities are disabled in system settings.', 'Las actividades en directo están desactivadas en los ajustes.')
      : dismissed ? localeText('此行程的锁屏活动已被移除。', 'The Lock Screen activity for this journey was dismissed.', 'Se ha descartado la actividad de este viaje en la pantalla bloqueada.')
      : active && !state.errorCode ? platform.nativePlatform === 'android' ? localeText('行程通知已发布；锁屏内容由系统设置决定。', 'Journey notification posted. Lock Screen visibility follows system settings.', 'Notificación del viaje publicada. La pantalla bloqueada depende de los ajustes.') : localeText('锁屏行程已交给系统，可锁屏查看。', 'Journey sent to iOS. Lock your screen to view it.', 'Viaje enviado a iOS. Bloquea la pantalla para verlo.')
      : localeText('锁屏行程暂未显示，回到 App 后会自动重试。', 'The Lock Screen journey is unavailable. Returning to the app retries automatically.', 'El viaje no aparece en la pantalla bloqueada. Se reintentará al volver a la app.');
    if (!disabled && !dismissed && state.errorCode) message += ' (' + (state.errorReference || state.errorCode) + ')';
    if (platform.nativePlatform === 'android' && state.pushStatus === 'FIREBASE_NOT_CONFIGURED') message += ' ' + localeText('后台推送尚未配置。', 'Background push is not configured.', 'Las notificaciones en segundo plano no están configuradas.');
    if (platform.nativePlatform === 'android' && state.pushStatus === 'GOOGLE_SERVICES_UNAVAILABLE') message += ' ' + localeText('此设备的 Google 服务不可用。', 'Google services are unavailable on this device.', 'Los servicios de Google no están disponibles en este dispositivo.');
    notice.querySelector('span').textContent = message;
    notice.querySelector('button').hidden = !disabled;
    notice.querySelector('button').textContent = localeText('前往系统设置', 'Open system settings', 'Abrir ajustes');
  }

  function endLiveJourney(key) {
    if (!liveActivity) return;
    if (key === familyShareJourneyKey(currentFlight)) key = nativeJourneyKey(currentFlight);
    liveActivity.end(key, function () {
      return platform.push.clearJourney({ journeyKey: key }).catch(function () { pushSyncFingerprint = null; });
    }).then(renderLiveActivityStatus).catch(renderLiveActivityStatus);
  }

  function syncLiveActivity() {
    if (!liveActivity || !pageInitialized) return;
    var content;
    try { content = liveActivityContent(); }
    catch (_) {
      liveActivity.reportFailure('LIVE_ACTIVITY_DISPLAY_FAILED');
      renderLiveActivityStatus();
      return;
    }
    if (!content) {
      if (!journeyContext || journeyContext.exitedAirport || currentAction && currentAction.stepType === 'COMPLETE') {
        endLiveJourney(familyShareJourneyKey(currentFlight));
      }
      renderLiveActivityStatus();
      return;
    }
    if (currentFlight.demo || qaJourneyEnabled || demoRecordingSessionId) {
      liveActivity.sync(content, function () {
        var marker = 'demo:' + content.journeyKey;
        if (pushSyncFingerprint === marker) return;
        pushSyncFingerprint = marker;
        return platform.push.syncJourney({ journeyKey: content.journeyKey, flight: { demo: true } })
          .catch(function () { pushSyncFingerprint = null; });
      }).then(renderLiveActivityStatus).catch(renderLiveActivityStatus);
      return;
    }
    var progress = safeContextSnapshot(journeyContext, null);
    delete progress.flightSnapshot;
    var payload = { journeyKey: content.journeyKey, flight: Object.assign(safeFlightSnapshot(currentFlight), { conflicts: currentFlight.conflicts || [], fieldProvenance: currentFlight.fieldProvenance || {} }), progress: progress,
      locale: currentLocale, snapshotVersion: serverSnapshotVersion };
    var fingerprint = JSON.stringify(payload);
    liveActivity.sync(content, function () {
      if (fingerprint === pushSyncFingerprint) return;
      pushSyncFingerprint = fingerprint;
      // Native sync persists identity/progress and schedules URLSession work;
      // it returns without waiting for the backend or notification permission.
      return platform.push.syncJourney(payload).then(function (result) {
        if (!result || !result.displayState || result.journeyKey !== familyShareJourneyKey(currentFlight)) return;
        serverSnapshotVersion = Math.max(serverSnapshotVersion, result.snapshotVersion || 0);
        lastLockState = Object.assign({}, result.displayState, { facts: Object.assign({}, lastLockState && lastLockState.facts, result.displayState.facts) });
      }).catch(function () { pushSyncFingerprint = null; });
    }).then(renderLiveActivityStatus).catch(renderLiveActivityStatus);
  }

  function handleNativeRoute() {
    if (!pageInitialized) return;
    var route = platform.takeRoute();
    if (!route) return;
    if (route.journeyKey && route.journeyKey !== nativeJourneyKey(currentFlight)) return;
    if (journeyContext && currentFlight) requestNextAction({ refresh: true, silent: true, source: 'notification' });
    // The URL carries no journey data; only navigate after persisted state has been restored.
    if (currentAction) renderJourneyResponse();
    else if (currentFlight) showScreen('confirm');
  }

  function handlePlatformEvent(type) {
    if (!pageInitialized) return;
    if (type === 'back') {
      platform.dismissKeyboard().then(function (dismissed) {
        var input = document.activeElement;
        if (input && /^(INPUT|TEXTAREA|SELECT)$/.test(input.tagName)) input.blur();
        if (dismissed) return;
        var overlay = Array.from(document.querySelectorAll('[data-overlay], [role="dialog"]')).find(function (el) { return !el.hidden && el.getClientRects().length; });
        if (overlay) { document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })); return; }
        var screen = document.querySelector('[data-screen].is-active');
        var back = screen && screen.querySelector('[data-action="back"], [data-action="home"]');
        if (back) { back.click(); return; }
        platform.exitApp();
      }); return;
    }
    if (type === 'push-status') { syncLiveActivity(); return; }
    if (type === 'route') { handleNativeRoute(); return; }
    if (type === 'pause') {
      stopAutoRefresh(false);
      window.clearInterval(clockTimer);
      clockTimer = null;
      window.clearTimeout(shareSyncTimer);
      stopSpeech(false);
      persistOfflineJourney();
      return;
    }
    if (type === 'offline' || type === 'api-offline') {
      setRefreshStatus('OFFLINE_SNAPSHOT');
      renderConnectivityStatus();
      syncLiveActivity();
      return;
    }
    if (type === 'api-online') { renderConnectivityStatus(); return; }
    if (type === 'storage-error') {
      showToast(localeText('无法保存最新进度，请保留 App 并稍后重试。',
        'Unable to save the latest progress. Keep the app open and try again.',
        'No se pudo guardar el progreso. Mantén la app abierta e inténtalo de nuevo.'));
      return;
    }
    if (type !== 'resume' || document.hidden || !platform.isActive() || Date.now() - lastRecheckAt < 1500) return;
    lastRecheckAt = Date.now();
    if (platform.push) platform.push.status().then(syncLiveActivity).catch(function () {});
    if (currentFlight) ensureClockTimer();
    retryPendingShareRevocations();
    retryPendingFamilyShare();
    syncLiveActivity();
    if (!currentFlight || lookupInFlight || flightRequestController || qaJourneyEnabled) return;
    var request = journeyContext && currentAction
      ? requestNextAction({ refresh: true, silent: true, source: 'resume' })
      : refreshFlight({ silent: true, source: 'resume' });
    Promise.resolve(request).then(function () { renderConnectivityStatus(); syncLiveActivity(); });
  }

  window.addEventListener('focus', refreshAfterReturnToPage);
  window.addEventListener('popstate', function () {
    if (!airportResourceHistoryActive) return;
    if (window.history.state && window.history.state.oneStepAirportContact) return;
    airportResourceHistoryActive = false;
    if (airportResourceHistoryClosing) {
      airportResourceHistoryClosing = false;
      finishCloseStaff();
    } else if (staffOverlayView === 'RESOURCE') {
      returnToStaffCard(true);
    }
  });
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) stopAutoRefresh(false);
    else refreshAfterReturnToPage();
  });

  window.addEventListener('offline', function () {
    stopAutoRefresh(false);
    setRefreshStatus('OFFLINE_SNAPSHOT');
    renderConnectivityStatus();
    persistJourney();
  });
  window.addEventListener('online', function () {
    if (nativeApp) { platform.resume(); return; }
    if (refreshStatusState && refreshStatusState.code === 'OFFLINE_SNAPSHOT') setRefreshStatus(null);
    renderConnectivityStatus();
    retryPendingShareRevocations();
    retryPendingFamilyShare();
    announce(localeText('网络已恢复。', 'You are back online.', 'La conexión se ha restablecido.'));
    resumeAutoRefresh();
  });

  if (!nativeApp && 'serviceWorker' in navigator) {
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('/service-worker.js', { updateViaCache: 'none' }).catch(function () {
        // Offline support is optional; the online journey remains available.
      });
    });
  }

  function initializePage() {
    currentLocale = detectInitialLocale();
    shareSession = readStoredJson(activeShareSessionStorageKey());
    document.querySelector('[data-scenario-panel]').hidden = !demoScenariosEnabled || Boolean(demoRecordingSessionId);
    document.querySelectorAll('[data-scenario-test-card], [data-qa-airport-panel]').forEach(function (element) {
      if (demoRecordingSessionId) element.hidden = true;
    });
    document.querySelectorAll('[data-demo-only]').forEach(function (element) {
      element.hidden = !demoFlightEnabled || Boolean(demoRecordingSessionId);
    });
    setupAirportResourceQaPanel();
    setupDemoRecordingChannel();
    updateLanguage();
    var restoredJourney = restoreJourney();
    pageInitialized = true;
    document.getElementById('main-content').inert = false;
    if (nativeApp) document.querySelector('[data-action="journey-problem-voice"]').hidden = true;
    if (shareSession && (!restoredJourney
        || !shareSessionMatchesPayload(shareSession, safeSharePayload()))) resetFamilyShareSession();
    retryPendingShareRevocations();
    updateLanguage();
    if (qaJourneyEnabled && (!restoredJourney || !qaJourneyFlightIsUsable(currentFlight))) {
      startFlightLookup('BA117', qaJourneyDepartureDate(), true, null, 'NORMAL');
    }
    publishDemoRecordingState();
    if (nativeApp) {
      if (expiredJourneyDiscarded) showToast(localeText('已保存的行程超过 48 小时，请联网重新查询。',
        'The saved journey is older than 48 hours. Reconnect and look it up again.',
        'El viaje guardado tiene más de 48 horas. Conéctate y vuelve a buscarlo.'));
      liveActivity.status().then(function () { syncLiveActivity(); }).catch(renderLiveActivityStatus);
      handleNativeRoute();
      platform.resume();
    }
  }

  function startPage() {
  var demoSessionPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  if (demoSessionPattern.test(requestedDemoSessionId || '')) {
    fetchJson('/api/demo-recording/sessions/' + encodeURIComponent(requestedDemoSessionId), {
      cache: 'no-store'
    }).then(function (status) {
      if (status && status.enabled && status.demoSessionId === requestedDemoSessionId.toLowerCase()) {
        demoRecordingSessionId = status.demoSessionId;
      }
      initializePage();
    }).catch(function () {
      demoRecordingSessionId = null;
      initializePage();
    });
  } else initializePage();
  }
  if (nativeApp && window.visualViewport) {
    var updateViewport = function () {
      if (window.visualViewport.scale !== 1) return;
      document.documentElement.style.setProperty('--visible-height', window.visualViewport.height + 'px');
      document.documentElement.style.setProperty('--visible-top', window.visualViewport.offsetTop + 'px');
    };
    window.visualViewport.addEventListener('resize', updateViewport);
    window.visualViewport.addEventListener('scroll', updateViewport);
    updateViewport();
  }
  if (platform) {
    platform.on(handlePlatformEvent);
    platform.init().then(startPage).catch(function () {
      document.getElementById('main-content').inert = true;
      var retry = document.createElement('button');
      retry.className = 'button button-primary';
      retry.textContent = localeText('无法读取已保存的行程，点此重试', 'Cannot read your saved journey. Tap to retry.', 'No se puede leer el viaje guardado. Toca para reintentar.');
      retry.onclick = function () { window.location.reload(); };
      document.querySelector('.site-header').after(retry);
    });
  } else startPage();
}());
