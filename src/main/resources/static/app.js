(function () {
  'use strict';

  var STORAGE_KEY = 'onestep-flight-journey-v2';
  var OFFLINE_STORAGE_KEY = 'onestep-flight-offline-journey-v1';
  var LOCALE_KEY = 'onestep-flight-locale';
  var SHARE_SESSION_KEY = 'onestep-flight-share-session-v1';
  var SHARE_PENDING_KEY = 'onestep-flight-share-pending-v1';
  var OFFLINE_MAX_AGE_MS = 48 * 60 * 60 * 1000;
  var currentScreen = 'welcome';
  var navigationHistory = [];
  var journeyHistory = [];
  var currentLocale = detectInitialLocale();
  var loadingTimer;
  var loadingTimeout;
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
  var currentDestinationCopy = null;
  var shareSession = readStoredJson(SHARE_SESSION_KEY);
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
  var qaJourneyEnabled = new URLSearchParams(window.location.search).get('qaJourney') === 'true';
  var activeDialog = null;
  var screens = document.querySelectorAll('.screen');
  var fileInput = document.getElementById('ticket-file');

  var translations = {
    zh: {
      eyebrow: '你的乘机助手', welcomeTitle: '现在，只做下一步', welcomeLead: '上传旅行文件或输入航班信息，我们每次只告诉你现在应该做什么。',
      upload: '上传预订确认、电子客票或登机牌', uploadHint: '支持 PDF、JPG、JPEG、PNG，也可以上传航空公司预订确认页截图、电子客票行程单或登机牌。', manual: '手动输入航班号', family: '分享行程给家属', privacy: '旅行文件只用于生成乘机指引',
      languageLabel: '选择语言', back: '返回', backStep: '返回上一步', fileLabel: '选择预订确认、电子客票或登机牌文件', loadingTitle: '正在准备你的乘机步骤', checkDetails: '请确认', confirmTitle: '这是你的航班吗？', departure: '计划起飞', terminal: '航站楼', gate: '登机口', status: '状态', notPublished: '尚未公布', demoDisclaimer: '演示数据', yesContinue: '是，继续', editFlight: '不是，我要修改', manualContext: '填写航班信息', baggageContext: '确认行李情况', manualTitle: '输入你的航班信息', manualLead: '请输入航班号和出发日期。', flightLabel: '航班号', dateLabel: '出发日期', flightPlaceholder: '例如 BA 117', searchLiveFlight: '查询真实航班', useDemoFlight: '使用 BA 117 演示航班', demoHint: '演示数据', nextAction: '现在要做的事', baggageYes: '有，需要托运', baggageNo: '没有', baggageUnsure: '我不确定', speak: '朗读当前步骤', staff: '请工作人员帮忙', contactFamily: '分享行程给家属', endJourney: '结束本次指引', askForHelp: '请工作人员帮忙', destination: '现在前往', couldNotLoad: '暂时无法读取', errorTitle: '实时航班状态暂未更新', errorLead: '请检查输入或网络连接，然后重试。你也可以手动输入航班。', retry: '重试', enterManually: '手动输入航班', askStaff: '请工作人员帮忙', technicalDetails: '查看技术详情', returnHome: '返回首页', endConfirmTitle: '确定要结束本次指引吗？', endConfirmLead: '结束后，当前步骤记录会从这台设备清除。', continueJourney: '继续指引', confirmEnd: '结束并返回首页', refreshLive: '刷新实时信息', refreshArrival: '重新查询行李转盘', reviewNeeded: '需要你确认', reviewCorrect: '这项信息正确', reviewEdit: '信息不对，手动修改', reviewOnSite: '这项未读取到，稍后现场确认', reviewWrongOnSite: '这项不对，标记为现场确认', currentStep: '当前步骤', boardConfirmTitle: '确认你已经登机吗？', boardConfirmLead: '确认后将进入机上模式，抵达后仍会继续指引。', confirmBoard: '是，我已经登机', cancelBoard: '返回，再检查一下', landedConfirmTitle: '确认你已经下飞机吗？', landedConfirmLead: '确认后，我们会继续指引你领取行李或前往出口。', confirmLanded: '是，我已经下飞机', cancelLanded: '返回，再检查一下', flightDuration: '预计飞行时间', estimatedArrival: '预计到达', timeUntilArrival: '距离预计到达', destinationLocalTime: '目的地当地时间', estimatedDeparture: '预计起飞', boardingTime: '登机时间', seat: '座位', airportLocalTime: '机场当地时间', timeUntilDeparture: '距离预计起飞', journeyTimeline: '查看本次行程记录', scenarioTitle: '模拟场景', scenarioLead: '仅供本地流程演示。', conflictTitle: '信息有关键冲突，请先停下来', conflictLead: '请向机场工作人员核对文件和实时屏幕后，再继续。', confirmConflicts: '我已向工作人员核对，继续', checkInArea: '值机 / 托运区域', close: '关闭', copyCard: '复制大字卡内容', familyTitle: '分享行程给家属', familyLead: '通过系统分享面板分享 48 小时有效的安全链接或脱敏行程图片。', localhostWarning: '当前是 localhost：其他设备无法打开这个地址。请部署到可访问的网址后再分享。', shareLink: '分享链接', sharePng: '分享图片'
    },
    en: {
      eyebrow: 'Your flight companion', welcomeTitle: 'Now, take the next step', welcomeLead: 'Upload a travel document or enter flight details. We show one action at a time.', upload: 'Upload booking confirmation, e-ticket, or boarding pass', uploadHint: 'PDF, JPG, JPEG, and PNG are supported, including booking screenshots, e-ticket itineraries, and boarding passes.', manual: 'Enter flight number manually', family: 'Share journey with family', privacy: 'Your travel document is only used to create flight guidance', languageLabel: 'Choose language', back: 'Back', backStep: 'Go back', fileLabel: 'Choose a booking confirmation, e-ticket, or boarding pass', loadingTitle: 'Preparing your next step', checkDetails: 'Please confirm', confirmTitle: 'Is this your flight?', departure: 'Scheduled departure', terminal: 'Terminal', gate: 'Gate', status: 'Status', notPublished: 'Not published', demoDisclaimer: 'Demo data', yesContinue: 'Yes, continue', editFlight: 'No, edit it', manualContext: 'Enter flight details', baggageContext: 'Confirm your baggage', manualTitle: 'Enter your flight details', manualLead: 'Enter the flight number and departure date.', flightLabel: 'Flight number', dateLabel: 'Departure date', flightPlaceholder: 'For example BA 117', searchLiveFlight: 'Search live flight', useDemoFlight: 'Use BA 117 demo flight', demoHint: 'Demo data', nextAction: 'Your next action', baggageYes: 'Yes, I have bags to check', baggageNo: 'No', baggageUnsure: 'I am not sure', speak: 'Read this step aloud', staff: 'Ask a member of staff', contactFamily: 'Share journey with family', endJourney: 'End this journey', askForHelp: 'Ask a member of staff', destination: 'Go now to', couldNotLoad: 'Unable to load', errorTitle: 'We cannot continue right now', errorLead: 'Check your details or connection, then try again. You can also enter the flight manually or ask staff for help.', retry: 'Try again', enterManually: 'Enter flight manually', askStaff: 'Ask a member of staff', technicalDetails: 'View technical details', returnHome: 'Return home', endConfirmTitle: 'End this journey?', endConfirmLead: 'Your current step history will be cleared from this device.', continueJourney: 'Continue journey', confirmEnd: 'End and return home', refreshLive: 'Refresh live information', refreshArrival: 'Check baggage belt again', reviewNeeded: 'Please confirm this', reviewCorrect: 'This information is correct', reviewEdit: 'This is wrong; edit manually', reviewOnSite: 'Not found; confirm it at the airport', reviewWrongOnSite: 'This is wrong; confirm it at the airport', currentStep: 'Current step', boardConfirmTitle: 'Have you boarded the aircraft?', boardConfirmLead: 'After confirmation, onboard mode will continue guiding you through arrival.', confirmBoard: 'Yes, I have boarded', cancelBoard: 'Go back and check again', landedConfirmTitle: 'Have you left the aircraft?', landedConfirmLead: 'After confirmation, guidance will continue to baggage claim or the exit.', confirmLanded: 'Yes, I have left the aircraft', cancelLanded: 'Go back and check again', flightDuration: 'Estimated flight time', estimatedArrival: 'Expected arrival', timeUntilArrival: 'Until expected arrival', destinationLocalTime: 'Destination local time', estimatedDeparture: 'Estimated departure', boardingTime: 'Boarding time', seat: 'Seat', airportLocalTime: 'Airport local time', timeUntilDeparture: 'Until expected departure', journeyTimeline: 'View this journey timeline', scenarioTitle: 'Simulated scenarios', scenarioLead: 'For local journey demonstrations only.', conflictTitle: 'Critical details conflict — stop here', conflictLead: 'Confirm the document and live screen with airport staff before continuing.', confirmConflicts: 'I confirmed with staff; continue', checkInArea: 'Check-in / bag-drop area', close: 'Close', copyCard: 'Copy card text', familyTitle: 'Share journey with family', familyLead: 'Create a read-only link that remains active for 48 hours. It includes only essential flight and current-step details.', localhostWarning: 'This is localhost. Other devices cannot open this address. Deploy the app to an accessible URL before sharing.', shareLink: 'Share link', sharePng: 'Share image'
    },
    es: {
      eyebrow: 'Tu asistente de vuelo', welcomeTitle: 'Ahora, solo el siguiente paso', welcomeLead: 'Sube un documento de viaje o introduce los datos del vuelo. Mostramos una sola acción cada vez.', upload: 'Subir confirmación de reserva, billete electrónico o tarjeta de embarque', uploadHint: 'Se admiten PDF, JPG, JPEG y PNG, incluidas capturas de reserva, itinerarios electrónicos y tarjetas de embarque.', manual: 'Introducir el vuelo manualmente', family: 'Compartir el viaje con la familia', privacy: 'Tu documento solo se usa para crear las indicaciones del viaje', languageLabel: 'Elegir idioma', back: 'Volver', backStep: 'Volver', fileLabel: 'Elegir confirmación, billete electrónico o tarjeta de embarque', loadingTitle: 'Preparando tu siguiente paso', checkDetails: 'Confirma los datos', confirmTitle: '¿Es este tu vuelo?', departure: 'Salida programada', terminal: 'Terminal', gate: 'Puerta', status: 'Estado', notPublished: 'Aún no publicado', demoDisclaimer: 'Datos de demostración', yesContinue: 'Sí, continuar', editFlight: 'No, modificar', manualContext: 'Datos del vuelo', baggageContext: 'Confirmar el equipaje', manualTitle: 'Introduce los datos de tu vuelo', manualLead: 'Introduce el número de vuelo y la fecha de salida.', flightLabel: 'Número de vuelo', dateLabel: 'Fecha de salida', flightPlaceholder: 'Por ejemplo BA 117', searchLiveFlight: 'Buscar vuelo real', useDemoFlight: 'Usar vuelo BA 117 de demostración', demoHint: 'Datos de demostración', nextAction: 'Tu siguiente acción', baggageYes: 'Sí, tengo equipaje para facturar', baggageNo: 'No', baggageUnsure: 'No estoy seguro', speak: 'Leer este paso', staff: 'Pedir ayuda al personal', contactFamily: 'Compartir el viaje con la familia', endJourney: 'Finalizar este viaje', askForHelp: 'Pedir ayuda al personal', destination: 'Ve ahora a', couldNotLoad: 'No se puede cargar', errorTitle: 'No podemos continuar ahora', errorLead: 'Comprueba los datos o la conexión y vuelve a intentarlo. También puedes introducir el vuelo o pedir ayuda.', retry: 'Reintentar', enterManually: 'Introducir vuelo manualmente', askStaff: 'Pedir ayuda al personal', technicalDetails: 'Ver detalles técnicos', returnHome: 'Volver al inicio', endConfirmTitle: '¿Finalizar este viaje?', endConfirmLead: 'El historial del paso actual se borrará de este dispositivo.', continueJourney: 'Continuar el viaje', confirmEnd: 'Finalizar y volver al inicio', refreshLive: 'Actualizar información', refreshArrival: 'Volver a consultar la cinta', reviewNeeded: 'Confirma este dato', reviewCorrect: 'Este dato es correcto', reviewEdit: 'No es correcto; modificar', reviewOnSite: 'No encontrado; confirmar en el aeropuerto', reviewWrongOnSite: 'No es correcto; confirmar en el aeropuerto', currentStep: 'Paso actual', boardConfirmTitle: '¿Ya has embarcado?', boardConfirmLead: 'Tras confirmar, el modo a bordo seguirá guiándote hasta la llegada.', confirmBoard: 'Sí, ya he embarcado', cancelBoard: 'Volver y comprobar', landedConfirmTitle: '¿Ya has bajado del avión?', landedConfirmLead: 'Tras confirmar, te guiaremos a la recogida de equipaje o a la salida.', confirmLanded: 'Sí, ya he bajado', cancelLanded: 'Volver y comprobar', flightDuration: 'Duración estimada', estimatedArrival: 'Llegada estimada', timeUntilArrival: 'Tiempo hasta la llegada', destinationLocalTime: 'Hora local del destino', estimatedDeparture: 'Salida estimada', boardingTime: 'Hora de embarque', seat: 'Asiento', airportLocalTime: 'Hora local del aeropuerto', timeUntilDeparture: 'Tiempo hasta la salida', journeyTimeline: 'Ver el historial del viaje', scenarioTitle: 'Escenarios simulados', scenarioLead: 'Solo para probar el recorrido local.', conflictTitle: 'Hay un conflicto importante: detente', conflictLead: 'Confirma el documento y la pantalla con el personal antes de continuar.', confirmConflicts: 'Lo confirmé con el personal; continuar', checkInArea: 'Zona de facturación / entrega', close: 'Cerrar', copyCard: 'Copiar el texto de la tarjeta', familyTitle: 'Compartir el viaje con la familia', familyLead: 'Crea un enlace de solo lectura válido durante 48 horas. Solo incluye el vuelo y el paso actual imprescindibles.', localhostWarning: 'Esta dirección es localhost y no se puede abrir en otro dispositivo. Despliega la aplicación en una URL accesible antes de compartir.', shareLink: 'Compartir enlace', sharePng: 'Compartir imagen'
    }
  };
  translations.zh.technicalDetails = '调试详情';
  translations.en.technicalDetails = 'Debug details';
  translations.es.technicalDetails = 'Detalles de depuración';

  var staffContent = {
    titleZh: '请帮助我', titleEn: 'Please help me', titleEs: 'Por favor, ayúdeme',
    bodyZh: '您好，我需要乘机帮助。可以请您帮我吗？',
    bodyEn: 'Hello. I need help with my flight. Could you please help me?',
    bodyEs: 'Hola. Necesito ayuda con mi vuelo. ¿Puede ayudarme?'
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
    selectedSegment: '当前航段', chooseSegment: '选择这一段',
    itineraryContext: '完整行程', itineraryTitle: '这份文件中的全部航段', itineraryLead: '以下航段按文件中的顺序显示。当前航段已标出。', incompleteSegment: '信息不完整，不能用于查询',
    segmentSelectionContext: '完整行程', segmentSelectionTitle: '请选择要查询的航段', segmentSelectionLead: '已按文件顺序保留全部航段。选择后才会查询该航段的实时状态。',
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
    staffCardInstruction: '请把这个页面拿给工作人员看', familyLead: '通过系统分享面板分享 48 小时有效的安全链接或脱敏行程图片。', globalFooter: '© 2026 OneStep Flight · 无障碍乘机指引',
    allFlightInfo: '查看全部航班信息', flightSourceLabel: '航班信息来源',
    departurePassedTitle: '这趟航班已经离港', departurePassedLead: '点击下方“请工作人员帮忙”，并前往航空公司服务柜台出示给工作人员。',
    enterNewFlight: '输入新的航班信息', backHome: '返回首页', uploadContext: '选择旅行文件', uploadTitle: '选择或上传文件', chooseFile: '选择文件',
    manualItineraryTitle: '文件中已读到的航段', manualItineraryLead: '所有航段按文件顺序保留；信息不完整的航段也不会被删除。',
    partialDateLead: '已读到航班号。系统未能唯一确定日期，请只选择出发日期。', partialFlightLead: '已读到出发日期。请只输入航班号。'
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
    selectedSegment: 'Current segment', chooseSegment: 'Select this segment',
    itineraryContext: 'Full itinerary', itineraryTitle: 'Every segment in this document', itineraryLead: 'Segments are shown in document order. Your current segment is marked.', incompleteSegment: 'Incomplete details; unavailable for lookup',
    segmentSelectionContext: 'Full itinerary', segmentSelectionTitle: 'Choose a segment to look up', segmentSelectionLead: 'Every segment remains in document order. Live status is requested only after you choose one.',
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
    staffCardInstruction: 'Please show this page to a member of staff.', familyLead: 'Use the system share panel for a secure link valid for 48 hours or a redacted journey image.', globalFooter: '© 2026 OneStep Flight · Accessible air-travel guidance',
    allFlightInfo: 'View all flight information', flightSourceLabel: 'Flight information source',
    departurePassedTitle: 'This flight has departed', departurePassedLead: 'Select “Ask a member of staff” below, then go to the airline service desk and show it to a staff member.',
    enterNewFlight: 'Enter new flight information', backHome: 'Return home', uploadContext: 'Choose a travel document', uploadTitle: 'Choose or upload a file', chooseFile: 'Choose file',
    manualItineraryTitle: 'Segments found in the file', manualItineraryLead: 'Every segment stays in document order, including segments with incomplete details.',
    partialDateLead: 'The flight number was found, but the date was not unique. Choose only the departure date.', partialFlightLead: 'The departure date was found. Enter only the flight number.'
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
    selectedSegment: 'Tramo actual', chooseSegment: 'Elegir este tramo',
    itineraryContext: 'Itinerario completo', itineraryTitle: 'Todos los tramos de este documento', itineraryLead: 'Los tramos aparecen en el orden del documento. El tramo actual está marcado.', incompleteSegment: 'Datos incompletos; no se puede consultar',
    segmentSelectionContext: 'Itinerario completo', segmentSelectionTitle: 'Elige el tramo que quieres consultar', segmentSelectionLead: 'Todos los tramos permanecen en el orden del documento. El estado en directo solo se consulta después de elegir uno.',
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
    staffCardInstruction: 'Por favor, muestra esta página a un miembro del personal.', familyLead: 'Usa el panel del sistema para compartir un enlace seguro válido durante 48 horas o una imagen anonimizada del viaje.', globalFooter: '© 2026 OneStep Flight · Guía accesible para viajar en avión',
    allFlightInfo: 'Ver toda la información del vuelo', flightSourceLabel: 'Fuente de información del vuelo',
    departurePassedTitle: 'Este vuelo ya ha salido', departurePassedLead: 'Pulsa «Pedir ayuda al personal» y ve al mostrador de atención de la aerolínea para mostrárselo al personal.',
    enterNewFlight: 'Introducir un vuelo nuevo', backHome: 'Volver al inicio', uploadContext: 'Elegir un documento de viaje', uploadTitle: 'Elegir o subir un archivo', chooseFile: 'Elegir archivo',
    manualItineraryTitle: 'Tramos encontrados en el archivo', manualItineraryLead: 'Se conservan todos los tramos en el orden del documento, incluso los incompletos.',
    partialDateLead: 'Se encontró el número de vuelo, pero no una fecha única. Elige solo la fecha de salida.', partialFlightLead: 'Se encontró la fecha de salida. Introduce solo el número de vuelo.'
  });

  translations.zh.copyCard = '复制';
  translations.en.copyCard = 'Copy';
  translations.es.copyCard = 'Copiar';
  translations.zh.copyCardAria = '复制工作人员大字卡内容';
  translations.en.copyCardAria = 'Copy the staff assistance card';
  translations.es.copyCardAria = 'Copiar la tarjeta de ayuda para el personal';
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

  var statusLabels = {
    ON_TIME: ['准时', 'On time', 'A tiempo'], BOARDING: ['正在登机', 'Boarding', 'Embarcando'],
    SCHEDULED: ['计划中', 'Scheduled', 'Programado'], EXPECTED: ['预计正常', 'Expected', 'Previsto'], NORMAL: ['计划正常', 'On schedule', 'Según lo previsto'],
    DELAYED: ['延误', 'Delayed', 'Retrasado'], CANCELLED: ['已取消', 'Cancelled', 'Cancelado'],
    DEPARTED: ['已离港', 'Departed', 'Despegó'], ARRIVED: ['已到达', 'Arrived', 'Llegó'], UNKNOWN: ['待确认', 'Confirm on site', 'Confirmar en el aeropuerto']
  };

  var loadingMessages = [
    ['正在查找航班', 'Finding your flight', 'Buscando tu vuelo'],
    ['正在核对航站楼和时间', 'Checking the terminal and times', 'Comprobando la terminal y los horarios'],
    ['正在整理下一步', 'Preparing your next step', 'Preparando tu siguiente paso']
  ];

  var uploadLoadingMessages = [
    ['正在安全发送文件', 'Securely sending the file', 'Enviando el archivo de forma segura'],
    ['正在提取航班字段', 'Extracting flight fields', 'Extrayendo los datos del vuelo'],
    ['正在准备你的乘机步骤', 'Preparing your next step', 'Preparando tu siguiente paso']
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
    if (partialDocumentEntry) renderManualDocumentEntry();
    renderStaffCard();
    renderShareEntryVisibility();
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
        || departureStopReason(currentFlight)
        || currentFlight && currentFlight.requiresUserConfirmation && !conflictsConfirmed);
      var segmentBlocked = action === 'select-segment'
        && usableDocumentSegmentIndexes.indexOf(Number(button.dataset.segmentIndex)) < 0;
      button.disabled = lookupInFlight || Boolean(continueBlocked || segmentBlocked);
    });
    document.getElementById('main-content').setAttribute('aria-busy', String(lookupInFlight));
  }

  function announce(message) {
    var region = document.querySelector('[data-live-region]');
    region.textContent = '';
    window.setTimeout(function () { region.textContent = message; }, 30);
  }

  function showScreen(name) {
    if (currentScreen !== name) stopSpeech(false);
    currentScreen = name;
    screens.forEach(function (screen) {
      var active = screen.dataset.screen === name;
      screen.hidden = !active;
      screen.classList.toggle('is-active', active);
    });
    if (name !== 'loading') {
      document.getElementById('main-content').focus({ preventScroll: true });
    }
    renderShareEntryVisibility();
    if (speechViewKey() == null) lastAutomaticSpeechKey = null;
    queueAutomaticSpeech();
  }

  function navigate(name) {
    if (currentScreen !== name && currentScreen !== 'loading') navigationHistory.push(currentScreen);
    showScreen(name);
  }

  function normalizeFlightNumber(value) {
    return String(value || '').replace(/[\s-]/g, '').toUpperCase();
  }

  function fetchJson(url, options) {
    var settings = options || {};
    var onResponse = settings.onResponse;
    delete settings.onResponse;
    settings.headers = settings.headers || {};
    settings.headers.Accept = 'application/json';
    return fetch(url, settings).then(function (response) {
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
    if (!currentFlight.timeContext && raw.indexOf('T') > 0 && !/(Z|[+-]\d{2}:?\d{2})$/i.test(raw)) {
      var localParts = raw.split('T');
      var dateParts = localParts[0].split('-').map(Number);
      var timeParts = localParts[1].split(':').map(Number);
      instant = Date.UTC(dateParts[0], dateParts[1] - 1, dateParts[2], timeParts[0] || 0, timeParts[1] || 0);
    } else if (!currentFlight.timeContext && raw.indexOf('T') < 0 && raw.indexOf(':') > 0) {
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
    var clean = stripFieldPrefix(value, /^(terminal|航站楼|航廈)\s*/i);
    clean = clean.replace(/^t\s*(?=\d)/i, '').replace(/\s+/g, ' ').trim();
    if (!clean) return null;
    var normalized = normalizeLocale(locale || currentLocale);
    return normalized === 'zh' ? clean + ' 号航站楼' : 'Terminal ' + clean;
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
      return (parsed.area ? parsed.area + ' 区' : '')
        + (parsed.area && parsed.counters ? ' ' : '')
        + (parsed.counters ? parsed.counters + ' 号' + (genericCounter ? '柜台' : '值机柜台') : '');
    }
    if (normalized === 'es') {
      return (parsed.area ? 'Zona ' + parsed.area + ' de facturación' : '')
        + (parsed.area && parsed.counters ? ', ' : '')
        + (parsed.counters ? 'mostradores ' + parsed.counters : '');
    }
    return (parsed.area ? 'Check-in area ' + parsed.area : '')
      + (parsed.area && parsed.counters ? ', ' : '')
      + (parsed.counters ? 'counters ' + parsed.counters : '');
  }

  function formatCheckInValue(value) { return formatCheckInForLocale(value, currentLocale, false); }

  function formatBagDropForLocale(value, locale) {
    if (!value) return null;
    var parsed = parseCounterLocation(value);
    var normalized = normalizeLocale(locale || currentLocale);
    if (parsed.area || parsed.counters) {
      if (normalized === 'zh') return (parsed.area ? parsed.area + ' 区' : '') + (parsed.area && parsed.counters ? ' ' : '') + (parsed.counters ? parsed.counters + ' 号' : '') + '行李托运柜台';
      if (normalized === 'es') return 'Entrega de equipaje ' + (parsed.area ? 'en la zona ' + parsed.area : '') + (parsed.area && parsed.counters ? ', ' : '') + (parsed.counters ? 'mostradores ' + parsed.counters : '');
      return 'Bag drop ' + (parsed.area ? 'area ' + parsed.area : '') + (parsed.area && parsed.counters ? ', ' : '') + (parsed.counters ? 'counters ' + parsed.counters : '');
    }
    var clean = stripFieldPrefix(value, /^(bag\s*drop|baggage\s*drop|托运(柜台|区)?|entrega de equipaje)\s*/i);
    return normalized === 'zh' ? '行李托运柜台 ' + clean : normalized === 'es' ? 'Entrega de equipaje ' + clean : 'Bag drop ' + clean;
  }

  function formatBagDropValue(value) { return formatBagDropForLocale(value, currentLocale); }

  function formatBeltForLocale(value, locale) {
    if (!value) return null;
    var clean = stripFieldPrefix(value, /^(belt|carousel|行李转盘|cinta)\s*/i);
    var normalized = normalizeLocale(locale || currentLocale);
    return normalized === 'zh' ? clean + ' 号行李转盘' : normalized === 'es' ? 'Cinta ' + clean : 'Baggage belt ' + clean;
  }

  function formatBeltValue(value) { return formatBeltForLocale(value, currentLocale); }

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
    return reason === 'DEPARTURE_TIME_PASSED' || reason === 'FLIGHT_ALREADY_DEPARTED';
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
    var effective = flight.actualDeparture || flight.estimatedDeparture || flight.scheduledDeparture;
    var dateAnchor = flight.scheduledDeparture || effective;
    var departureOffset = flight.timeContext && flight.timeContext.departureUtcOffset;
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
    if (!effective || status === 'BOARDING') return null;
    var serverNow = flight.timeContext && flight.timeContext.serverNow;
    var effectiveMs = Date.parse(effective);
    var serverNowMs = Date.parse(serverNow || '');
    return Number.isFinite(effectiveMs) && Number.isFinite(serverNowMs)
      && serverNowMs - effectiveMs >= 15 * 60 * 1000
      ? 'DEPARTURE_TIME_PASSED' : null;
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
    document.querySelector('[data-departure-passed-flight]').textContent = formatFlightNumber(flight.flightNumber);
    document.querySelector('[data-departure-passed-date]').textContent = formatDate(flight.departureDate);
    document.querySelector('[data-departure-passed-status]').textContent = localeText('已离港', 'Departed', 'Ya ha salido');
    var reference = document.querySelector('[data-departure-passed-reference]');
    reference.textContent = departureReferenceText(flight);
    reference.hidden = !reference.textContent;
    setDeparturePassedStaffContent();
    return reason;
  }

  function renderFlight(flight) {
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
    document.querySelector('[data-route-departure-label]').textContent = t(departureLocal.label);
    document.querySelector('[data-route-departure-time]').textContent = formatAtDeparture(departureLocal.value, true);
    document.querySelector('[data-route-arrival-label]').textContent = t(arrivalLocal.label);
    document.querySelector('[data-route-arrival-time]').textContent = formatAtArrival(arrivalLocal.value, true);
    document.querySelector('[data-boarding-time]').textContent = formatAtDeparture(
      flight.documentBoardingTime || (flight.timeContext && flight.timeContext.boardingTime), false);
    document.querySelector('[data-terminal]').textContent = formatTerminalValue(
      flight.terminal || (documentFallback ? flight.documentTerminal : null)) || '—';
    document.querySelector('[data-gate]').textContent = formatGateValue(
      flight.gate || (documentFallback ? flight.documentGate : null)) || t('notPublished');
    document.querySelector('[data-check-in-area]').textContent = formatCheckInValue(flight.checkInDeskOrZone || flight.checkInCounter) || t('notPublished');
    document.querySelector('[data-seat]').textContent = flight.seat || (pendingDocument && pendingDocument.seat) || '—';
    var simulated = Boolean(flight.demoScenario);
    var unavailableNotice = document.querySelector('[data-live-status-unavailable]');
    if (unavailableNotice) unavailableNotice.hidden = !documentFallback;
    var confirmRefresh = document.querySelector('.screen-confirm [data-action="refresh-flight"]');
    if (confirmRefresh) confirmRefresh.hidden = documentFallback
      ? !pendingDocument : !flight.timeContext || (!flight.live && !simulated);
    var status = statusLabels[flight.status] || statusLabels.UNKNOWN;
    document.querySelector('[data-flight-status]').textContent = textFor(status);
    var departureBlock = renderDeparturePassedConfirmation(flight);
    var continueButton = document.querySelector('[data-action="continue"]');
    continueButton.disabled = Boolean(departureBlock || flight.requiresUserConfirmation && !conflictsConfirmed);
    var continueLabel = continueButton.querySelector('[data-button-label]') || continueButton;
    continueLabel.textContent = t('yesContinue');
    continueButton.setAttribute('aria-label', t('yesContinueAria'));
    renderConflictCard();
    renderFlightChanges();
    updateClockAnchor();
    renderTimeStatus();
    renderConnectivityStatus();
    renderSegmentSelector();
    renderFlightCorrection();
    renderShareEntryVisibility();
    queueAutomaticSpeech();
  }

  function runLoading(messages) {
    showScreen('loading');
    var status = document.querySelector('[data-loading-status]');
    var progress = document.querySelector('[data-progress]');
    var progressBar = document.querySelector('.progress-track');
    status.textContent = textFor(messages[0]);
    progress.style.width = '10%';
    progressBar.setAttribute('aria-valuenow', '10');
    clearInterval(loadingTimer);
    clearTimeout(loadingTimeout);
  }

  function updateLoadingProgress(percent, messages, messageIndex) {
    var value = Math.max(0, Math.min(100, Number(percent) || 0));
    var progress = document.querySelector('[data-progress]');
    var progressBar = document.querySelector('.progress-track');
    var status = document.querySelector('[data-loading-status]');
    if (progress) progress.style.width = value + '%';
    if (progressBar) progressBar.setAttribute('aria-valuenow', String(value));
    if (status && messages && messages[messageIndex]) status.textContent = textFor(messages[messageIndex]);
  }

  function resolvePayload(flightNumber, departureDate, demo, documentResult, demoScenario, refresh) {
    var payload = {
      flightNumber: normalizeFlightNumber(flightNumber),
      departureDate: departureDate,
      demo: Boolean(demo),
      demoScenario: demoScenario || null,
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
    if (!source || !segment || usableDocumentSegmentIndexes.indexOf(index) < 0) return null;
    var selectedFields = Object.assign({}, segment.fields || {});
    if (documentRootFields.document_type) selectedFields.document_type = documentRootFields.document_type;
    var reviewPrefix = 'segments[' + index + '].';
    var selectedReviewFields = documentRootReviewFields
      .filter(function (field) { return String(field).indexOf(reviewPrefix) === 0; })
      .map(function (field) { return String(field).substring(reviewPrefix.length); });
    return Object.assign({}, source, {
      flightNumber: segment.flightNumber || null,
      departureDate: segment.departureDate || null,
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
      segments: documentSegments.slice()
    });
  }

  function createSegmentDisplay(segment, index) {
    segment = segment || {};
    var usable = usableDocumentSegmentIndexes.indexOf(index) >= 0;
    var item = document.createElement('button');
    item.type = 'button';
    item.dataset.action = 'select-segment';
    item.dataset.segmentIndex = String(index);
    item.className = 'segment-option' + (!usable ? ' is-incomplete' : '')
      + (index === selectedDocumentSegmentIndex ? ' is-selected' : '');
    if (index === selectedDocumentSegmentIndex) item.setAttribute('aria-current', 'true');
    item.disabled = !usable || lookupInFlight;
    var route = (segment.departureAirport || '—') + ' → ' + (segment.arrivalAirport || '—');
    var heading = document.createElement('strong');
    heading.textContent = formatFlightNumber(segment.flightNumber || '—') + ' · ' + route;
    var details = document.createElement('span');
    details.textContent = formatDate(segment.departureDate) + (segment.scheduledDeparture
      ? ' · ' + String(segment.scheduledDeparture).replace('T', ' ') : '');
    var choice = document.createElement('span');
    choice.className = 'segment-choice-label';
    choice.textContent = !usable ? t('incompleteSegment')
      : index === selectedDocumentSegmentIndex ? t('selectedSegment') : t('chooseSegment');
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
      context.textContent = t(awaitingDocumentSelection ? 'segmentSelectionContext' : 'itineraryContext');
      title.textContent = t(awaitingDocumentSelection ? 'segmentSelectionTitle' : 'itineraryTitle');
      lead.textContent = t(awaitingDocumentSelection ? 'segmentSelectionLead' : 'itineraryLead');
      documentSegments.forEach(function (segment, index) {
        options.append(createSegmentDisplay(segment, index));
      });
    }
    updateConfirmModeVisibility();
    var continueButton = document.querySelector('[data-action="continue"]');
    if (continueButton) continueButton.disabled = lookupInFlight || awaitingDocumentSelection || !currentFlight
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
    stopSpeech(false);
    flightCorrectionOpen = true;
    renderSegmentSelector();
    renderFlightCorrection();
    persistJourney();
    queueAutomaticSpeech();
  }

  function startFlightLookup(flightNumber, departureDate, demo, documentResult, demoScenario) {
    if (lookupInFlight) return;
    if (!documentResult) {
      documentSegments = [];
      usableDocumentSegmentIndexes = [];
      selectedDocumentSegmentIndex = null;
      awaitingDocumentSelection = false;
      documentRootFields = {};
      documentRootReviewFields = [];
    }
    flightCorrectionOpen = false;
    var normalized = normalizeFlightNumber(flightNumber);
    var activeRequest = ++requestSequence;
    var origin = currentScreen === 'loading' ? 'welcome' : currentScreen;
    lastLookupContext = {
      flightNumber: normalized,
      departureDate: departureDate || null,
      departureAirport: documentResult && documentResult.departureAirport || null,
      arrivalAirport: documentResult && documentResult.arrivalAirport || null
    };
    currentError = null;
    retryRequest = function () { startFlightLookup(normalized, departureDate, demo, documentResult, demoScenario); };
    setLookupBusy(true);
    runLoading(loadingMessages);

    postFlightJson('/api/journeys/resolve-flight',
      resolvePayload(normalized, departureDate, demo, documentResult, demoScenario, false), function () {
        updateLoadingProgress(72, loadingMessages, 1);
      }).then(function (flight) {
      if (activeRequest !== requestSequence) return;
      updateLoadingProgress(100, loadingMessages, 2);
      beginFlight(flight, documentResult);
      recordLiveChanges(flight.latestChanges);
      navigationHistory.push(origin);
      showScreen('confirm');
      announce(localeText('航班信息已准备好，请确认。', 'Flight details ready. Please confirm.', 'Los datos del vuelo están listos. Confírmalos.'));
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

  function validDocumentDepartureDate(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || ''))) return false;
    var parts = String(value).split('-').map(Number);
    var date = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2]));
    return date.getUTCFullYear() === parts[0] && date.getUTCMonth() === parts[1] - 1
      && date.getUTCDate() === parts[2];
  }

  function normalizeDocumentSegment(segment) {
    if (!segment || typeof segment !== 'object') return {};
    if (segment.departureDate != null && String(segment.departureDate).trim() !== '') return segment;
    var match = /^(\d{4}-\d{2}-\d{2})T(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?(?:Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)?$/.exec(
      String(segment.scheduledDeparture || '').trim());
    if (!match || !validDocumentDepartureDate(match[1])) return segment;
    return Object.assign({}, segment, { departureDate: match[1] });
  }

  function normalizeDocumentResult(result) {
    var normalized = result && typeof result === 'object' ? Object.assign({}, result) : {};
    normalized.segments = Array.isArray(normalized.segments)
      ? normalized.segments.map(normalizeDocumentSegment) : [];
    return normalized;
  }

  function classifyDocumentResult(result) {
    var segments = result && Array.isArray(result.segments) ? result.segments.slice() : [];
    if (!segments.length) return { outcome: 'UNUSABLE', segments: [], usableIndexes: [], partialSegment: null, partialIndex: null };
    var usableIndexes = [];
    segments.forEach(function (segment, index) {
      segment = segment || {};
      if (validDocumentFlightNumber(segment.flightNumber) && validDocumentDepartureDate(segment.departureDate)) {
        usableIndexes.push(index);
      }
    });
    if (usableIndexes.length) return { outcome: 'USABLE', segments: segments, usableIndexes: usableIndexes, partialSegment: null, partialIndex: null };
    var partialIndex = segments.findIndex(function (segment) {
      segment = segment || {};
      return validDocumentFlightNumber(segment.flightNumber) || validDocumentDepartureDate(segment.departureDate);
    });
    return partialIndex >= 0
      ? { outcome: 'PARTIAL', segments: segments, usableIndexes: [], partialSegment: segments[partialIndex], partialIndex: partialIndex }
      : { outcome: 'UNUSABLE', segments: segments, usableIndexes: [], partialSegment: null, partialIndex: null };
  }

  function discoverMissingDocumentDates(result, assessment) {
    var segments = assessment.segments.slice();
    var indexes = [];
    segments.forEach(function (segment, index) {
      if (validDocumentFlightNumber(segment && segment.flightNumber)
          && !validDocumentDepartureDate(segment && segment.departureDate)) indexes.push(index);
    });
    if (!indexes.length) return Promise.resolve(Object.assign({}, result, { segments: segments }));
    var chain = Promise.resolve();
    indexes.forEach(function (index) {
      chain = chain.then(function () {
        var documentForDiscovery = Object.assign({}, result, { segments: segments.slice() });
        return postFlightJson('/api/journeys/discover-flight-date', {
          flightNumber: normalizeFlightNumber(segments[index].flightNumber),
          document: documentForDiscovery,
          segmentIndex: index
        }).then(function (response) {
          if (response && !response.dateRequired && validDocumentDepartureDate(response.departureDate)) {
            segments[index] = Object.assign({}, segments[index], { departureDate: response.departureDate });
          }
        }).catch(function () {
          // Date discovery is optional. The user is only asked for the missing date if it is not unique.
        });
      });
    });
    return chain.then(function () { return Object.assign({}, result, { segments: segments }); });
  }

  function resetDocumentResultState() {
    clearJourney();
    document.querySelector('[data-manual-partial-notice]').hidden = true;
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
    var panel = document.querySelector('[data-manual-document-segments]');
    if (panel) panel.hidden = true;
    var flightField = document.querySelector('[data-manual-flight-field]');
    var dateField = document.querySelector('[data-manual-date-field]');
    if (flightField) flightField.hidden = false;
    if (dateField) dateField.hidden = false;
    var lead = document.querySelector('[data-manual-lead]');
    if (lead) {
      lead.dataset.i18n = 'manualLead';
      lead.textContent = t('manualLead');
    }
  }

  function renderManualDocumentEntry() {
    var panel = document.querySelector('[data-manual-document-segments]');
    var options = document.querySelector('[data-manual-segment-options]');
    if (!panel || !options) return;
    panel.hidden = !partialDocumentEntry || !documentSegments.length;
    options.replaceChildren();
    if (!panel.hidden) documentSegments.forEach(function (segment, index) {
      options.append(createSegmentDisplay(segment, index));
    });
    var selected = Number.isInteger(selectedDocumentSegmentIndex)
      ? documentSegments[selectedDocumentSegmentIndex] || {} : {};
    var hasFlightNumber = validDocumentFlightNumber(selected.flightNumber);
    var hasDepartureDate = validDocumentDepartureDate(selected.departureDate);
    var flightField = document.querySelector('[data-manual-flight-field]');
    var dateField = document.querySelector('[data-manual-date-field]');
    if (flightField) flightField.hidden = hasFlightNumber && !hasDepartureDate;
    if (dateField) dateField.hidden = hasDepartureDate && !hasFlightNumber;
    var leadKey = hasFlightNumber && !hasDepartureDate ? 'partialDateLead'
      : hasDepartureDate && !hasFlightNumber ? 'partialFlightLead' : 'manualLead';
    var lead = document.querySelector('[data-manual-lead]');
    if (lead) {
      lead.dataset.i18n = leadKey;
      lead.textContent = t(leadKey);
    }
  }

  function showPartialDocument(result, assessment) {
    resetDocumentResultState();
    partialDocumentEntry = true;
    documentSegments = assessment.segments.slice();
    usableDocumentSegmentIndexes = [];
    selectedDocumentSegmentIndex = assessment.partialIndex;
    documentRootFields = Object.assign({}, result.fields || {});
    documentRootReviewFields = Array.isArray(result.needsReviewFields) ? result.needsReviewFields.slice() : [];
    pendingDocument = Object.assign({}, result, { segments: documentSegments.slice() });
    var segment = assessment.partialSegment;
    var flightInput = document.getElementById('flight-input');
    var dateInput = document.getElementById('date-input');
    flightInput.value = validDocumentFlightNumber(segment && segment.flightNumber)
      ? formatFlightNumber(segment.flightNumber) : '';
    setDateInputValue(dateInput, validDocumentDepartureDate(segment && segment.departureDate)
      ? segment.departureDate : '');
    document.querySelector('[data-manual-partial-notice]').hidden = false;
    renderManualDocumentEntry();
    navigationHistory.push('welcome');
    showScreen('manual');
    announce(t('partialDocumentNotice'));
  }

  function setDocumentExtractionState(result, assessment) {
    documentSegments = assessment.segments.slice();
    usableDocumentSegmentIndexes = assessment.usableIndexes.slice();
    documentRootFields = Object.assign({}, result.fields || {});
    documentRootReviewFields = Array.isArray(result.needsReviewFields) ? result.needsReviewFields.slice() : [];
    pendingDocument = Object.assign({}, result, { segments: documentSegments.slice() });
  }

  function showDocumentSegmentSelection(result, assessment) {
    resetDocumentResultState();
    setDocumentExtractionState(result, assessment);
    selectedDocumentSegmentIndex = null;
    awaitingDocumentSelection = true;
    currentFlight = null;
    currentAction = null;
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

  function selectDocumentSegment(index, options) {
    var settings = options || {};
    index = Number(index);
    if ((lookupInFlight && !settings.fromUpload) || !Number.isInteger(index)
        || usableDocumentSegmentIndexes.indexOf(index) < 0 || !pendingDocument) return null;
    var selectedResult = selectedDocumentResult(pendingDocument, index);
    if (!selectedResult) return null;
    selectedDocumentSegmentIndex = index;
    awaitingDocumentSelection = false;
    lastLookupContext = {
      flightNumber: normalizeFlightNumber(selectedResult.flightNumber),
      departureDate: selectedResult.departureDate || null,
      departureAirport: selectedResult.departureAirport || null,
      arrivalAirport: selectedResult.arrivalAirport || null,
      documentSegmentIndex: index
    };
    var extractedFlight = extractionToFlight(selectedResult);
    beginFlight(extractedFlight, selectedResult);
    recordJourneyEvent('DOCUMENT_SEGMENT_SELECTED',
      '已选择航段 ' + formatFlightNumber(selectedResult.flightNumber),
      'Selected segment ' + formatFlightNumber(selectedResult.flightNumber), true);
    return resolveUploadedFlight({ activeRequest: settings.activeRequest, fromUpload: Boolean(settings.fromUpload) });
  }

  function uploadDocument(file) {
    if (lookupInFlight) return;
    var activeRequest = ++requestSequence;
    var formData = new FormData();
    formData.append('file', file);
    currentError = null;
    retryRequest = function () { uploadDocument(file); };
    setLookupBusy(true);
    runLoading(uploadLoadingMessages);
    var extraction = fetchJson('/api/documents/travel-document', {
      method: 'POST', body: formData,
      onResponse: function () { updateLoadingProgress(72, uploadLoadingMessages, 1); }
    });

    extraction.then(function (result) {
      if (activeRequest !== requestSequence) return;
      updateLoadingProgress(100, uploadLoadingMessages, 2);
      result = normalizeDocumentResult(result);
      var assessment = classifyDocumentResult(result);
      if (assessment.segments.length > 1) {
        showDocumentSegmentSelection(result, assessment);
        return;
      }
      if (assessment.outcome === 'UNUSABLE') {
        showUnusableDocument();
        return;
      }
      if (assessment.outcome === 'PARTIAL') {
        showPartialDocument(result, assessment);
        return;
      }
      setDocumentExtractionState(result, assessment);
      selectedDocumentSegmentIndex = null;
      awaitingDocumentSelection = false;
      navigationHistory.push('welcome');
      return selectDocumentSegment(usableDocumentSegmentIndexes[0], {
        activeRequest: activeRequest,
        fromUpload: true
      });
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      clearInterval(loadingTimer);
      clearTimeout(loadingTimeout);
      showError(error, retryRequest);
    }).finally(function () { setLookupBusy(false); });
  }

  function beginFlight(flight, documentResult) {
    var requestedDepartureDate = lastLookupContext && lastLookupContext.departureDate;
    var requestedSegmentIndex = lastLookupContext && lastLookupContext.documentSegmentIndex;
    currentFlight = flight;
    lastLookupContext = {
      flightNumber: normalizeFlightNumber(flight.flightNumber),
      departureDate: requestedDepartureDate || flight.departureDate || null,
      departureAirport: flight.departureAirport || null,
      arrivalAirport: flight.arrivalAirport || null,
      documentSegmentIndex: Number.isInteger(requestedSegmentIndex) ? requestedSegmentIndex : null
    };
    pendingDocument = documentResult || null;
    currentAction = null;
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

  function resolveUploadedFlight(options) {
    var settings = options || {};
    if (!currentFlight || !pendingDocument) return;
    if (lookupInFlight && !settings.fromUpload) return;
    var activeRequest = settings.activeRequest || ++requestSequence;
    var previousEvents = journeyEvents.slice();
    currentError = null;
    retryRequest = resolveUploadedFlight;
    setLookupBusy(true);
    runLoading(loadingMessages);
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
      hasCheckedBaggage: qaJourneyEnabled ? true : null,
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
    currentAction = buildCheckInQuestion();
    renderJourneyResponse();
    persistJourney();
  }

  function buildQaAction() {
    var code = !journeyContext.checkedIn ? 'CHECK_IN'
      : !journeyContext.bagDropped ? 'BAG_DROP'
        : !journeyContext.securityCleared ? 'SECURITY'
          : !journeyContext.atGate ? 'GO_TO_GATE'
            : !journeyContext.landed ? 'IN_FLIGHT'
              : !journeyContext.baggageCollected ? 'BAGGAGE_CLAIM'
                : !journeyContext.exitedAirport ? 'EXIT_AIRPORT' : null;
    if (!code) return {
      stepType: 'COMPLETE', actionCode: null,
      titleZh: '本地流程验收完成', titleEn: 'Local journey QA complete', titleEs: 'Prueba local del viaje completada',
      descriptionZh: '所有阶段都已在本机完成。', descriptionEn: 'All journey stages were completed locally.', descriptionEs: 'Todas las etapas se completaron localmente.',
      urgency: 'NORMAL', lastUpdated: calibratedNowIso()
    };
    var zhTitles = { CHECK_IN: '现在办理值机', BAG_DROP: '现在托运行李', SECURITY: '现在前往安检', GO_TO_GATE: '现在前往登机口', IN_FLIGHT: '已经登机', BAGGAGE_CLAIM: '现在领取托运行李', EXIT_AIRPORT: '现在前往到达出口' };
    var enTitles = { CHECK_IN: 'Check in now', BAG_DROP: 'Drop off your baggage now', SECURITY: 'Go to security now', GO_TO_GATE: 'Go to the gate now', IN_FLIGHT: 'You are on board', BAGGAGE_CLAIM: 'Collect your baggage now', EXIT_AIRPORT: 'Go to the arrivals exit now' };
    return {
      stepType: 'ACTION', actionCode: code, titleZh: zhTitles[code], titleEn: enTitles[code],
      titleEs: spanishActionCopy[code].title, descriptionZh: '本地流程验收：只使用真实航班快照中已公布的值。',
      descriptionEn: 'Local journey QA: only published values from the real flight snapshot are used.',
      descriptionEs: 'Prueba local: solo se usan valores publicados de la consulta real.',
      urgency: 'NORMAL', reason: 'LOCAL_QA_JOURNEY', lastUpdated: calibratedNowIso()
    };
  }

  function refreshFlight() {
    if (!currentFlight || flightRequestController) return;
    if (!navigator.onLine) {
      setRefreshStatus('OFFLINE_SNAPSHOT');
      renderConnectivityStatus();
      return;
    }
    if (isDocumentFallbackFlight(currentFlight)) {
      if (!pendingDocument) return;
      var fallbackRequest = ++requestSequence;
      retryRequest = refreshFlight;
      document.querySelectorAll('[data-action="refresh-flight"]').forEach(function (button) { button.disabled = true; });
      setRefreshStatus('CHECKING');
      postFlightJson('/api/journeys/resolve-flight', resolvePayload(
        currentFlight.flightNumber, currentFlight.departureDate, false, pendingDocument, null, true)).then(function (flight) {
        if (fallbackRequest !== requestSequence) return;
        currentFlight = flight;
        if (journeyContext) journeyContext.flightSnapshot = flight;
        recordLiveChanges(flight.latestChanges);
        renderFlight(flight);
        persistJourney();
        if (isDocumentFallbackFlight(flight)) {
          setRefreshStatus('DOCUMENT_FALLBACK_RETAINED');
          announce(t('liveStatusUnavailable'));
        } else {
          setRefreshStatus('REFRESH_SUCCESS_LATEST_RETAINED');
          announceLatestChangesOr(localeText('实时航班信息已刷新。', 'Live flight information refreshed.', 'Información del vuelo actualizada.'));
        }
      }).catch(function () {
        if (fallbackRequest !== requestSequence) return;
        setRefreshStatus('DOCUMENT_FALLBACK_RETAINED');
        announce(t('liveStatusUnavailable'));
      }).finally(updateRefreshControls);
      return;
    }
    if (!currentFlight.timeContext || (!currentFlight.live && !currentFlight.demoScenario)) return;
    document.querySelectorAll('[data-action="refresh-flight"]').forEach(function (button) { button.disabled = true; });
    setRefreshStatus('CHECKING');
    postFlightJson('/api/journeys/refresh-flight', currentFlight).then(function (flight) {
      currentFlight = flight;
      if (journeyContext) journeyContext.flightSnapshot = flight;
      recordLiveChanges(flight.latestChanges);
      renderFlight(flight);
      persistJourney();
      setRefreshStatus('REFRESH_SUCCESS_LATEST_RETAINED');
      announceLatestChangesOr(localeText('实时航班信息已刷新。', 'Live flight information refreshed.', 'Información del vuelo actualizada.'));
    }).catch(function (error) {
      setRefreshStatus('REFRESH_FAILED_LATEST_RETAINED');
      announce(localeText('刷新失败，原有航班信息仍然保留。', 'Refresh failed. The previous flight information is still available.', 'No se pudo actualizar. La información anterior sigue disponible.'));
      retryRequest = refreshFlight;
    }).finally(updateRefreshControls);
  }

  function beginNewFlightInformation() {
    requestSequence += 1;
    clearInterval(loadingTimer);
    clearTimeout(loadingTimeout);
    clearJourney();
    fileInput.value = '';
    showScreen('manual');
    var flightField = document.getElementById('flight-input');
    var dateField = document.getElementById('date-input');
    document.querySelector('[data-manual-partial-notice]').hidden = true;
    flightField.value = '';
    setDateInputValue(dateField, '');
    flightField.focus({ preventScroll: true });
    announce(localeText('请输入新的航班号和出发日期。', 'Enter the new flight number and departure date.', 'Introduce el nuevo número de vuelo y la fecha de salida.'));
  }

  function refreshArrivalInfo() {
    if (!currentFlight || !journeyContext || !journeyContext.boarded || flightRequestController) return;
    if (!navigator.onLine) {
      setRefreshStatus('OFFLINE_SNAPSHOT');
      renderConnectivityStatus();
      return;
    }
    if (!currentFlight.live && !currentFlight.demoScenario) return;
    document.querySelectorAll('[data-action="refresh-arrival"]').forEach(function (button) { button.disabled = true; });
    setRefreshStatus('CHECKING_ARRIVAL');
    postFlightJson('/api/journeys/refresh-flight', currentFlight).then(function (flight) {
      currentFlight = flight;
      journeyContext.flightSnapshot = flight;
      recordLiveChanges(flight.latestChanges);
      currentAction = buildPostBoardAction();
      renderFlight(flight);
      renderJourneyResponse();
      persistJourney();
      setRefreshStatus('ARRIVAL_REFRESH_SUCCESS');
      announce(localeText('到达信息已刷新。', 'Arrival information refreshed.', 'Información de llegada actualizada.'));
    }).catch(function () {
      setRefreshStatus('ARRIVAL_REFRESH_FAILED');
      announce(localeText('到达信息刷新失败，已保存的行程仍然可用。', 'Arrival refresh failed. Your saved journey is still available.', 'No se pudo actualizar la llegada. El viaje guardado sigue disponible.'));
    }).finally(updateRefreshControls);
  }

  function maybeRefreshArrivalOnEntry() {
    if (!currentAction || currentAction.actionCode !== 'BAGGAGE_CLAIM' || !journeyContext
        || !journeyContext.landed || !navigator.onLine || (!currentFlight.live && !currentFlight.demoScenario)) return;
    var key = [currentFlight.flightNumber, currentFlight.departureDate, journeyContext.landed].join('|');
    if (arrivalRefreshFlightKey === key) return;
    arrivalRefreshFlightKey = key;
    window.setTimeout(refreshArrivalInfo, 0);
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

  function requestNextAction(refreshRequested) {
    if (!journeyContext || !currentFlight) return;
    if (qaJourneyEnabled && !refreshRequested) {
      currentAction = buildQaAction();
      renderFlight(currentFlight);
      renderJourneyResponse();
      persistJourney();
      return;
    }
    if (journeyContext.boarded && !refreshRequested) {
      renderLocalPostBoardAction();
      return;
    }
    if (!navigator.onLine) {
      setRefreshStatus('OFFLINE_SNAPSHOT');
      renderConnectivityStatus();
      announce(localeText('当前离线，已保存的当前步骤仍然可用。', 'You are offline. The saved current step is still available.', 'No hay conexión. El paso guardado sigue disponible.'));
      return;
    }
    if (refreshRequested && flightRequestController) return;
    var activeRequest = ++requestSequence;
    journeyContext.refreshRequested = Boolean(refreshRequested);
    journeyContext.flightSnapshot = currentFlight;
    journeyContext.conflictsConfirmed = conflictsConfirmed;
    document.querySelector('.screen-step').setAttribute('aria-busy', 'true');
    document.querySelectorAll('.screen-step [data-action="complete-action"], .screen-step [data-action="baggage"], .screen-step [data-action="checkin-status"]').forEach(function (button) { button.disabled = true; });
    retryRequest = function () { requestNextAction(refreshRequested); };
    if (refreshRequested) setRefreshStatus('CHECKING');

    postFlightJson('/api/journeys/next-action', journeyContext).then(function (response) {
      if (activeRequest !== requestSequence) return;
      journeyContext.refreshRequested = false;
      currentFlight = response.flight;
      journeyContext.flightSnapshot = currentFlight;
      currentAction = response.nextAction;
      recordLiveChanges(currentFlight.latestChanges);
      currentError = null;
      renderFlight(currentFlight);
      renderJourneyResponse();
      if (refreshRequested) setRefreshStatus('REFRESH_SUCCESS_LATEST_RETAINED');
      persistJourney();
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      journeyContext.refreshRequested = false;
      if (refreshRequested && currentAction) {
        setRefreshStatus('REFRESH_FAILED_STEP_RETAINED');
        announce(localeText('刷新失败，当前步骤没有改变。', 'Refresh failed. Your current step is unchanged.', 'No se pudo actualizar. El paso actual no cambió.'));
      } else {
        showError(error, retryRequest);
      }
    }).finally(function () {
      document.querySelector('.screen-step').removeAttribute('aria-busy');
      document.querySelectorAll('.screen-step [data-action="baggage"], .screen-step [data-action="checkin-status"]').forEach(function (button) { button.disabled = false; });
      updateRefreshControls();
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

  function actionTextForLocale(action, part, locale) {
    if (!action) return '';
    if (isDepartureStopAction(action)) {
      var departureCopy = {
        title: {
          zh: '这趟航班已经离港',
          en: 'This flight has departed',
          es: 'Este vuelo ya ha salido'
        },
        description: {
          zh: '点击下方“请工作人员帮忙”，并前往航空公司服务柜台出示给工作人员。',
          en: 'Select “Ask a member of staff” below, then go to the airline service desk and show it to a staff member.',
          es: 'Pulsa «Pedir ayuda al personal» y ve al mostrador de atención de la aerolínea para mostrárselo al personal.'
        }
      };
      return departureCopy[part] && departureCopy[part][locale] || '';
    }
    if (locale === 'zh') return action[part + 'Zh'] || '';
    if (locale === 'en') return action[part + 'En'] || '';
    var key = action.stepType === 'BAGGAGE_QUESTION' ? 'BAGGAGE_QUESTION' : action.actionCode || 'COMPLETE';
    return action[part + 'Es'] || spanishActionCopy[key] && spanishActionCopy[key][part] || action[part + 'En'] || '';
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

  function renderJourneyResponse() {
    if (!currentAction) return;
    if (currentAction.stepType === 'COMPLETE') {
      setBilingualActionText(document.querySelector('[data-complete-title]'), currentAction, 'title', true);
      setBilingualActionText(document.querySelector('[data-complete-description]'), currentAction, 'description', false);
      document.querySelector('[data-complete-flight]').textContent = formatFlightNumber(currentFlight.flightNumber)
        + ' · ' + (currentFlight.arrivalAirport || '—');
      setStaffContent(
        '请帮我确认到达出口后的路线', 'Please help me confirm the route after the arrivals exit',
        '我已经到达机场出口。请帮我确认接下来前往接机区或公共交通的路线。',
        'I have reached the airport exit. Please help me find the pickup area or public transport.'
      );
      showScreen('complete');
      var completeBack = document.querySelector('.screen-complete [data-action="journey-back"]');
      if (completeBack) completeBack.hidden = !journeyHistory.length;
      renderJourneyTimeline();
      renderConnectivityStatus();
      announceLatestChangesOr(actionLocaleText(currentAction, 'title'));
      renderShareEntryVisibility();
      queueAutomaticSpeech();
      return;
    }

    showScreen('step');
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
      '已完成 ' + completedStages + ' 个阶段',
      completedStages + (completedStages === 1 ? ' stage completed' : ' stages completed'),
      completedStages + (completedStages === 1 ? ' etapa completada' : ' etapas completadas'));

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
        'Hello. I am not sure whether I have checked in. Please check my boarding pass or travel document and tell me the next step.'
      );
    } else if (baggageQuestion) {
      setLocalizedStepText(document.querySelector('[data-question-title]'), currentAction, 'title');
      setLocalizedStepText(document.querySelector('[data-question-description]'), currentAction, 'description');
      document.querySelector('.screen-step').setAttribute('aria-labelledby', 'step-title');
      setBaggageStaffContent();
    } else {
      setLocalizedStepText(document.querySelector('[data-action-title]'), currentAction, 'title');
      setLocalizedStepText(document.querySelector('[data-action-description]'), currentAction, 'description');
      renderActionDestination();
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
    announceLatestChangesOr(actionLocaleText(currentAction, 'title'));
    maybeRefreshArrivalOnEntry();
    renderShareEntryVisibility();
    queueAutomaticSpeech();
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
    var offsetText = arrivalPhase
      ? String(currentFlight.actualArrival || currentFlight.estimatedArrival || currentFlight.scheduledArrival || '').match(/(Z|[+-]\d{2}:?\d{2})$/i)
      : null;
    var displayOffset = arrivalPhase && offsetText
      ? offsetText[1].toUpperCase() === 'Z' ? '+00:00' : offsetText[1]
      : time.departureUtcOffset === 'Z' ? '+00:00' : time.departureUtcOffset;
    var localNow = new Date(calibratedNowMs() + offsetMinutes * 60000);
    var localText = new Intl.DateTimeFormat(currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-US', {
      dateStyle: 'medium', timeStyle: 'medium', timeZone: 'UTC'
    }).format(localNow) + ' UTC' + displayOffset;
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
    } else if (!time.sourceUpdatedAtAvailable) {
      ageText += localeText(' · 来源更新时间缺失，按取得时间判断', ' · source update time missing; using retrieval time', ' · sin hora de la fuente; se usa la hora de consulta');
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
    var changes = currentFlight && Array.isArray(currentFlight.latestChanges)
      ? currentFlight.latestChanges.filter(function (change) { return change.critical; }) : [];
    document.querySelectorAll('[data-flight-change-alert]').forEach(function (alert) {
      alert.replaceChildren();
      alert.hidden = !changes.length;
      alert.classList.toggle('is-critical', changes.length > 0);
      changes.forEach(function (change) {
        var line = document.createElement('p');
        line.textContent = (currentLocale === 'zh' ? change.titleZh : currentLocale === 'en' ? change.titleEn : spanishFlightChange(change))
          + ': ' + displayChangeValue(change, change.oldValue) + ' → ' + displayChangeValue(change, change.newValue);
        alert.appendChild(line);
      });
    });
  }

  function displayChangeValue(change, value) {
    if (!value) return '—';
    if (change.type === 'DEPARTURE_TIME_CHANGED') return formatAtDeparture(value, true);
    if (change.type === 'DELAY_CHANGED') return value + localeText(' 分钟', ' min', ' min');
    return value;
  }

  function spanishFlightChange(change) {
    var labels = {
      GATE_CHANGED: 'La puerta de embarque ha cambiado', TERMINAL_CHANGED: 'La terminal ha cambiado',
      DEPARTURE_TIME_CHANGED: 'La hora de salida ha cambiado', DELAY_CHANGED: 'El retraso ha cambiado',
      STATUS_CHANGED: 'El estado del vuelo ha cambiado', FLIGHT_CANCELLED: 'El vuelo está cancelado'
    };
    return labels[change && change.type] || 'La información importante del vuelo ha cambiado';
  }

  function latestFlightChange(type) {
    var changes = currentFlight && Array.isArray(currentFlight.latestChanges) ? currentFlight.latestChanges : [];
    return changes.find(function (change) { return change.type === type; }) || null;
  }

  function recordJourneyEvent(type, titleZh, titleEn, reversible, details, stableId) {
    var id = stableId || type + ':' + calibratedNowIso();
    if (journeyEvents.some(function (event) { return event.id === id; })) return;
    journeyEvents.push({
      id: id,
      type: type,
      titleZh: titleZh,
      titleEn: titleEn,
      details: details || '',
      occurredAt: calibratedNowIso(),
      reversible: Boolean(reversible)
    });
    renderJourneyTimeline();
  }

  function recordLiveChanges(changes) {
    (Array.isArray(changes) ? changes : []).forEach(function (change) {
      recordJourneyEvent(change.type, change.titleZh, change.titleEn, false,
        displayChangeValue(change, change.oldValue) + ' → ' + displayChangeValue(change, change.newValue),
        'LIVE:' + change.type + ':' + (change.oldValue || '') + ':' + (change.newValue || '') + ':' + (change.detectedAt || ''));
    });
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
      var title = document.createElement('strong');
      title.textContent = currentLocale === 'zh' ? event.titleZh : currentLocale === 'en' ? event.titleEn : spanishJourneyEvent(event);
      var time = document.createElement('time');
      time.dateTime = event.occurredAt;
      time.textContent = formatAtDeparture(event.occurredAt, true) + (event.details ? ' · ' + event.details : '');
      item.append(title, time);
      list.appendChild(item);
    });
  }

  function spanishJourneyEvent(event) {
    if (event.type && event.type.indexOf('ACTION_COMPLETED_') === 0) return 'Paso completado';
    if (event.type && /GATE|TERMINAL|DELAY|STATUS|CANCELLED|DEPARTURE_TIME/.test(event.type)) return spanishFlightChange(event);
    var labels = {
      DOCUMENT_EXTRACTED: 'Documento de viaje leído', FLIGHT_CONFIRMED: 'Vuelo confirmado',
      BAGGAGE_ANSWERED: 'Equipaje confirmado', BAGGAGE_UNSURE: 'Equipaje por confirmar',
      ASSISTANCE_REQUESTED: 'Ayuda solicitada', CONFLICT_CONFIRMED_WITH_STAFF: 'Conflicto confirmado con el personal'
    };
    return labels[event.type] || 'Viaje actualizado';
  }

  function announceLatestChangesOr(fallback) {
    var changes = currentFlight && Array.isArray(currentFlight.latestChanges) ? currentFlight.latestChanges : [];
    var critical = changes.filter(function (change) { return change.critical; });
    announce(critical.length
      ? critical.map(function (change) { return currentLocale === 'zh' ? change.titleZh : currentLocale === 'en' ? change.titleEn : spanishFlightChange(change); }).join('. ')
      : fallback);
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
    var offline = !navigator.onLine;
    document.querySelectorAll('[data-offline-status]').forEach(function (status) {
      status.hidden = !offline;
      status.textContent = offline
        ? localeText('当前离线，正在显示已保存的行程。', 'You are offline. Showing the saved journey.', 'Sin conexión. Mostrando el viaje guardado.')
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

  function setDeparturePassedStaffContent() {
    var flight = formatFlightNumber(currentFlight && currentFlight.flightNumber) || '—';
    var date = currentFlight && currentFlight.departureDate;
    var dateZh = formatDateForLocale(date, 'zh-CN');
    var dateEn = formatDateForLocale(date, 'en-GB');
    var dateEs = formatDateForLocale(date, 'es-ES');
    setStaffContent(
      '航班已经离港，请帮我改签或重新预订',
      'This flight has departed. Please help me rebook',
      '航班 ' + flight + '，出发日期 ' + dateZh + '，状态：已离港。请问能否为我改签或重新预订，并告诉我下一步应该去哪里？',
      'Flight ' + flight + ', departure date ' + dateEn + ', status: departed. Can you rebook me or make a new reservation, and tell me where to go next?',
      'Este vuelo ya ha salido. Ayúdeme a cambiar la reserva',
      'Vuelo ' + flight + ', fecha de salida ' + dateEs + ', estado: ya ha salido. ¿Puede cambiar mi reserva o hacer una nueva y decirme adónde debo ir ahora?');
  }

  function setStaffForAction(action) {
    var flight = formatFlightNumber(currentFlight && currentFlight.flightNumber);
    var gate = currentFlight && currentFlight.gate;
    var reason = action.reason || '';
    if (reason === 'FLIGHT_CANCELLED') {
      setStaffContent('航班已取消，请告诉我下一步', 'My flight is cancelled. What should I do?',
        '我的航班 ' + flight + ' 显示已经取消。请问我现在应该怎么办？',
        'My flight ' + flight + ' is shown as cancelled. What should I do now?');
      return;
    }
    if (reason === 'DEPARTURE_TIME_PASSED' || reason === 'FLIGHT_ALREADY_DEPARTED') {
      setDeparturePassedStaffContent();
      return;
    }
    var content = {
      CHECK_IN: ['请帮我找到值机位置', 'Please help me find check-in',
        '请问 ' + flight + ' 应该在哪里办理值机？值机或行李托运区域在哪里？',
        'Where should I check in for ' + flight + '? Where is the check-in or baggage area?'],
      BAG_DROP: ['请帮我找到行李托运位置', 'Please help me find bag drop',
        '请问我的托运行李应该交到哪一个柜台或区域？',
        'Which counter or area should I use for my checked baggage?'],
      SECURITY: ['请帮我找到正确的安检入口', 'Please help me find the correct security entrance',
        '请问前往 ' + flight + ' 登机口应该从哪一个安检入口进入？',
        'Which security entrance should I use to reach the gate for ' + flight + '?'],
      GO_TO_GATE: ['请帮我确认登机口和路线', 'Please confirm my gate and route',
        '请问 ' + flight + ' 现在的登机口是 ' + (gate || '尚未确认') + ' 吗？我应该怎么走？',
        'Is the current gate for ' + flight + ' ' + (gate || 'not yet confirmed') + '? How should I get there?'],
      WAIT: ['请帮我确认是否已经开始登机', 'Please confirm whether boarding has started',
        '请问 ' + flight + ' 现在是否已经开始登机？',
        'Has boarding started for ' + flight + '?'],
      BOARD_NOW: ['请帮我确认现在是否可以登机', 'Please confirm whether I can board now',
        '请问 ' + flight + ' 现在是否可以登机？',
        'Can I board ' + flight + ' now?'],
      IN_FLIGHT: ['请帮我确认抵达后的路线', 'Please help me confirm what to do after arrival',
        journeyContext && journeyContext.hasCheckedBaggage
          ? '我有托运行李。抵达后，请告诉我怎样找到行李提取屏幕。'
          : '我没有托运行李。抵达后，请告诉我怎样前往到达出口。',
        journeyContext && journeyContext.hasCheckedBaggage
          ? 'I have checked baggage. After arrival, please help me find the baggage reclaim screens.'
          : 'I do not have checked baggage. After arrival, please help me find Arrivals / Exit.'],
      BAGGAGE_CLAIM: ['请帮我找到到达行李转盘', 'Please help me find the arrival baggage belt',
        '我已下飞机。请问 ' + flight + ' 的托运行李在哪一个转盘领取？',
        'I have left the aircraft. Which baggage belt has the checked baggage from ' + flight + '?'],
      EXIT_AIRPORT: ['请帮我找到到达出口', 'Please help me find the arrivals exit',
        '我已经完成到达后的必要步骤。请问到达出口应该怎么走？',
        'I have completed the required arrival steps. How do I reach Arrivals / Exit?'],
      ASK_FOR_HELP: ['请帮助我确认下一步', 'Please help me confirm what to do next',
        '请帮我核对 ' + flight + ' 的最新信息，并告诉我现在只需要做哪一件事。',
        'Please check the latest information for ' + flight + ' and tell me the one thing I should do now.']
    }[action.actionCode];
    content = content || ['请帮助我确认下一步', 'Please help me confirm the next step',
      '请帮我确认现在应该做什么。', 'Please help me confirm what I should do now.'];
    setStaffContent(content[0], content[1], content[2], content[3]);
  }

  function setStaffContent(titleZh, titleEn, bodyZh, bodyEn, titleEs, bodyEs) {
    staffContent = {
      titleZh: titleZh, titleEn: titleEn,
      titleEs: titleEs || 'Por favor, ayúdeme con el siguiente paso',
      bodyZh: bodyZh, bodyEn: bodyEn,
      bodyEs: bodyEs || 'Necesito ayuda con mi vuelo. Compruebe la información y dígame adónde debo ir ahora.'
    };
    renderStaffCard();
  }

  function setBaggageStaffContent() {
    setStaffContent(
      '请帮我确认行李', 'Please help me check my baggage',
      '您好，我不确定这件行李是否需要托运。如果需要托运，请告诉我应该去哪一个柜台或区域。',
      'Hello. I am not sure whether this bag needs to be checked. If it does, please tell me which counter or area I should go to.'
    );
  }

  function renderStaffCard() {
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
    if (closeButton) closeButton.setAttribute('aria-label', t('close'));
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
      staffDatePrimary.textContent = formatDateForLocale(date, currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-GB');
      staffDatePrimary.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale;
    }
    if (staffDateEn) {
      staffDateEn.textContent = formatDateForLocale(date, 'en-GB');
      staffDateEn.hidden = currentLocale === 'en';
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
      zh: '我已完成：' + (zh.length ? zh.join(' · ') : '尚未开始'),
      en: 'Completed: ' + (en.length ? en.join(' · ') : 'not started'),
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
    facts.push([localeText('更新时间', 'Updated', 'Actualizado'),
      formatRetrieved(currentFlight.freshestSourceUpdatedAt || currentFlight.dataLastUpdatedAt || currentFlight.dataRetrievedAt)]);
    return facts;
  }

  function staffCardText() {
    var progress = completedProgressText();
    var primaryTitle = currentLocale === 'zh' ? staffContent.titleZh : currentLocale === 'es' ? staffContent.titleEs : staffContent.titleEn;
    var primaryBody = currentLocale === 'zh' ? staffContent.bodyZh : currentLocale === 'es' ? staffContent.bodyEs : staffContent.bodyEn;
    var heading = t('staffCardInstruction') + '\n\n' + progress[currentLocale]
      + '\n' + primaryTitle + '\n\n' + primaryBody;
    if (currentLocale !== 'en') heading += '\n\n' + progress.en
      + '\n' + staffContent.titleEn + '\n\n' + staffContent.bodyEn;
    return heading + '\n\n'
      + assistanceFacts().map(function (fact) { return fact[0] + ': ' + fact[1]; }).join('\n');
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
    recordJourneyEvent('CHECK_IN_STATUS_CONFIRMED',
      answer === 'yes' ? '已回答：已经值机' : answer === 'no' ? '已回答：还没有值机' : '已回答：不确定是否已经值机',
      answer === 'yes' ? 'Answered: already checked in' : answer === 'no' ? 'Answered: not checked in yet' : 'Answered: unsure about check-in',
      true);
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
    requestNextAction(false);
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
      recordJourneyEvent('BAGGAGE_UNSURE', '已选择：不确定行李', 'Selected: unsure about baggage', true);
      setBaggageStaffContent();
      persistJourney();
      showStaff();
      return;
    }
    snapshotJourney();
    journeyContext.needsAssistance = false;
    journeyContext.hasCheckedBaggage = answer === 'yes';
    recordJourneyEvent('BAGGAGE_ANSWERED',
      answer === 'yes' ? '已回答：有托运行李' : '已回答：没有托运行李',
      answer === 'yes' ? 'Answered: checked baggage' : 'Answered: no checked baggage',
      true);
    requestNextAction(false);
  }

  function completeCurrentAction() {
    if (!currentAction || currentAction.stepType !== 'ACTION') return;
    var code = currentAction.actionCode;
    if (code === 'WAIT') {
      requestNextAction(true);
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
      if (qaJourneyEnabled) journeyContext.boarded = true;
    }
    if (code === 'BOARD_NOW') journeyContext.boarded = true;
    if (code === 'IN_FLIGHT') journeyContext.landed = true;
    if (code === 'BAGGAGE_CLAIM') journeyContext.baggageCollected = true;
    if (code === 'EXIT_AIRPORT') journeyContext.exitedAirport = true;
    var titles = {
      CHECK_IN: ['已完成值机', 'Completed check-in'],
      BAG_DROP: ['已完成行李托运', 'Completed bag drop'],
      SECURITY: ['已通过安检', 'Passed security'],
      GO_TO_GATE: ['已到达登机口', 'Reached the gate'],
      BOARD_NOW: ['已确认登机', 'Confirmed boarding'],
      IN_FLIGHT: ['已确认下飞机', 'Confirmed leaving the aircraft'],
      BAGGAGE_CLAIM: ['已领取托运行李', 'Collected checked baggage'],
      EXIT_AIRPORT: ['已到达机场出口', 'Reached the airport exit']
    };
    var title = titles[code] || ['已完成当前行动', 'Completed the current action'];
    recordJourneyEvent('ACTION_COMPLETED_' + code, title[0], title[1], true,
      currentAction && currentAction.destination ? currentAction.destination : '');
    requestNextAction(false);
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
    recordJourneyEvent('ASSISTANCE_REQUESTED', '已请求工作人员帮助', 'Requested staff assistance', true);
    requestNextAction(false);
  }

  function goBack() {
    requestSequence += 1;
    if (flightRequestController) flightRequestController.abort();
    setLookupBusy(false);
    stopSpeech(false);
    clearInterval(loadingTimer);
    clearTimeout(loadingTimeout);
    if ((currentScreen === 'confirm' || currentScreen === 'error')
        && documentSegments.length > 1 && selectedDocumentSegmentIndex != null) {
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
    if (documentSegments.length < 2 || !pendingDocument) return false;
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
      || /^\/api\/journeys\/(?:discover-flight-date|resolve-flight|refresh-flight|next-action)(?:\?|$)/.test(url);
  }

  function providerErrorSignal(details) {
    var results = details && details.providerResults;
    if (!results || typeof results !== 'object') return '';
    return Object.keys(results).map(function (key) {
      var result = results[key] || {};
      return [key, result.provider, result.outcome, result.errorCode].filter(Boolean).join(' ');
    }).join(' ').toUpperCase();
  }

  function liveFlightErrorCopy(error, details, code) {
    var status = Number(error && error.status) || 0;
    var normalizedCode = String(code || '').toUpperCase();
    var signal = providerErrorSignal(details);
    var generic = {
      titleZh: '实时航班状态暂未更新', titleEn: 'Flight information is unavailable right now',
      titleEs: 'La información del vuelo no está disponible ahora',
      zh: '实时航班状态暂未更新。请重试，或手动输入航班。',
      en: 'We cannot get this flight information right now. Try again or enter the flight manually.',
      es: 'No podemos obtener este vuelo ahora. Reinténtalo o introduce el vuelo manualmente.'
    };
    if (!isLiveFlightRequest(error)) return generic;
    if (normalizedCode === 'CLIENT_FLIGHT_TIMEOUT') return {
      titleZh: '实时数据源响应超时', titleEn: 'The live data source timed out', titleEs: 'La fuente de datos en tiempo real agotó el tiempo',
      zh: '航班查询超过 20 秒，已安全停止。请检查网络后重试。',
      en: 'The flight lookup took more than 20 seconds and was stopped safely. Check your connection and try again.',
      es: 'La consulta tardó más de 20 segundos y se detuvo de forma segura. Comprueba la conexión y vuelve a intentarlo.'
    };
    if (normalizedCode === 'FLIGHT_NOT_FOUND') return {
      titleZh: '未找到该日期的航班', titleEn: 'No flight was found for this date', titleEs: 'No se encontró el vuelo para esta fecha',
      zh: '请确认航班号和出发日期后重试。',
      en: 'Check the flight number and departure date, then try again.',
      es: 'Comprueba el número de vuelo y la fecha de salida y vuelve a intentarlo.'
    };
    if (normalizedCode.indexOf('INVALID_') === 0 || status === 400) return {
      titleZh: '航班号或日期有误', titleEn: 'The flight number or date is invalid', titleEs: 'El número de vuelo o la fecha no son válidos',
      zh: '请检查航班号和出发日期，然后重试。',
      en: 'Check the flight number and departure date, then try again.',
      es: 'Comprueba el número de vuelo y la fecha de salida y vuelve a intentarlo.'
    };
    if (status === 429 || /(RATE.?LIMIT|QUOTA|HTTP.?429|TOO.?MANY)/.test(normalizedCode + ' ' + signal)) return {
      titleZh: '实时航班查询暂时受限', titleEn: 'Live-flight lookup is temporarily limited', titleEs: 'La consulta de vuelos está limitada temporalmente',
      zh: '查询过于频繁，或外部数据源已达到限制。请稍后重试。',
      en: 'There were too many requests, or an external data source reached its limit. Try again later.',
      es: 'Hubo demasiadas consultas o una fuente externa alcanzó su límite. Inténtalo de nuevo más tarde.'
    };
    if (/TIMEOUT/.test(normalizedCode + ' ' + signal)) return {
      titleZh: '实时数据源响应超时', titleEn: 'The live data source timed out', titleEs: 'La fuente de datos en tiempo real agotó el tiempo',
      zh: '实时数据源未在规定时间内响应。请稍后重试。',
      en: 'The live data source did not respond in time. Try again later.',
      es: 'La fuente de datos en tiempo real no respondió a tiempo. Inténtalo de nuevo más tarde.'
    };
    if (normalizedCode === 'ALL_LIVE_FLIGHT_PROVIDERS_FAILED' || status === 502) return {
      titleZh: '实时航班数据源暂时不可用', titleEn: 'Live-flight data sources are temporarily unavailable',
      titleEs: 'Las fuentes de datos de vuelos no están disponibles temporalmente',
      zh: '实时航班数据源暂时无法返回结果。请稍后重试。',
      en: 'Live-flight data sources cannot return a result right now. Try again later.',
      es: 'Las fuentes de datos de vuelos no pueden devolver resultados ahora. Inténtalo de nuevo más tarde.'
    };
    return generic;
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
      'SerpApi provider result: ' + JSON.stringify(serpApi || 'not provided', null, 2),
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
    var lines = currentError && currentError.technicalLines || [];
    var showDebug = isLocalDebugEnvironment() && lines.length > 0;
    details.hidden = !showDebug;
    details.open = false;
    content.textContent = showDebug ? lines.join('\n') : '';
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
        return compactSpeechParts([t('departurePassedTitle'), t('departurePassedLead'),
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
      return compactSpeechParts([title, destination, time && time.textContent]);
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

  function startSpeechText(text, automatic) {
    text = String(text || '').trim();
    if (!text) return;
    stopSpeech(false);
    if (!speechIsSupported()) {
      if (!automatic) showToast(localeText('当前浏览器不支持朗读。', 'Speech is not supported in this browser.', 'Este navegador no admite la lectura en voz alta.'));
      return;
    }
    var generation = ++speechGeneration;
    var utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-US';
    var voicePrefix = currentLocale === 'zh' ? 'zh' : currentLocale === 'es' ? 'es' : 'en';
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

  function showStaff() {
    stopSpeech(false);
    renderStaffCard();
    openDialog('[data-staff-overlay]', '[data-action="close-staff"]');
    startSpeechText(t('staffCardInstruction'), true);
  }

  function closeStaff() {
    stopSpeech(false);
    closeDialog('[data-staff-overlay]');
  }

  function openDialog(dialogSelector, focusSelector) {
    overlayReturnFocus = document.activeElement;
    activeDialog = document.querySelector(dialogSelector);
    document.querySelector('.site-header').inert = true;
    document.getElementById('main-content').inert = true;
    activeDialog.hidden = false;
    var focusTarget = activeDialog.querySelector(focusSelector);
    if (focusTarget) focusTarget.focus();
  }

  function closeDialog(dialogSelector) {
    var dialog = document.querySelector(dialogSelector);
    dialog.hidden = true;
    document.querySelector('.site-header').inert = false;
    document.getElementById('main-content').inert = false;
    activeDialog = null;
    if (overlayReturnFocus && document.contains(overlayReturnFocus)) overlayReturnFocus.focus();
  }

  function copyText(text, successMessage) {
    var copy = navigator.clipboard && navigator.clipboard.writeText
      ? navigator.clipboard.writeText(text)
      : new Promise(function (resolve, reject) {
        var area = document.createElement('textarea');
        area.value = text;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        try { document.execCommand('copy') ? resolve() : reject(new Error('copy failed')); }
        catch (error) { reject(error); }
        area.remove();
      });
    return copy.then(function () { showToast(successMessage); }).catch(function () {
      showToast(localeText('暂时无法复制，请直接展示屏幕。', 'Copy is unavailable. Please show the card on screen.', 'No se puede copiar. Muestra la tarjeta en pantalla.'));
    });
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
    var expiresAt = Date.parse(session.expiresAt);
    if (!Number.isFinite(expiresAt) || expiresAt <= Date.now()) return false;
    try {
      return familyShareJourneyKey(JSON.parse(session.lastFingerprint || 'null')) === familyShareJourneyKey(payload);
    } catch (error) {
      return false;
    }
  }

  function resetFamilyShareSession() {
    shareCreationGeneration += 1;
    shareCreationInFlight = false;
    shareCreationPromise = null;
    shareCreationJourneyKey = null;
    window.clearTimeout(shareSyncTimer);
    shareSyncTimer = null;
    shareSession = null;
    localStorage.removeItem(SHARE_SESSION_KEY);
    localStorage.removeItem(SHARE_PENDING_KEY);
  }

  function shareViewUrl() {
    if (!shareSessionMatchesPayload(shareSession, safeSharePayload())) return '';
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
    if (warning) warning.hidden = !/^(localhost|127\.0\.0\.1|\[::1\])$/i.test(window.location.hostname);
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
      setShareStatus(localeText('当前状态无法安全共享。', 'This state cannot be shared safely.', 'Este estado no se puede compartir de forma segura.'));
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
    setShareStatus(localeText('正在创建安全链接…', 'Creating a secure link…', 'Creando un enlace seguro…'));
    shareCreationPromise = postJson('/api/share-sessions', payload).then(function (result) {
      if (generation !== shareCreationGeneration || familyShareJourneyKey(safeSharePayload()) !== journeyKey) return null;
      shareSession = {
        viewToken: result.viewToken,
        updateToken: result.updateToken,
        viewUrl: result.viewUrl,
        expiresAt: result.expiresAt,
        lastFingerprint: JSON.stringify(payload)
      };
      localStorage.setItem(SHARE_SESSION_KEY, JSON.stringify(shareSession));
      localStorage.removeItem(SHARE_PENDING_KEY);
      renderFamilyShareControls();
      setShareStatus(localeText('安全链接已准备好。', 'The secure link is ready.', 'El enlace seguro está listo.'));
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
    if (!navigator.share) {
      setShareStatus(localeText('当前浏览器不支持系统分享链接。', 'System link sharing is not supported in this browser.', 'Este navegador no admite compartir enlaces con el sistema.'));
      return;
    }
    if (!url) {
      createFamilyShare().catch(function () {});
      setShareStatus(localeText('正在准备安全链接。准备好后，请再点一次“分享链接”。',
        'The secure link is being prepared. When it is ready, select “Share link” again.',
        'Se está preparando el enlace seguro. Cuando esté listo, pulsa de nuevo «Compartir enlace».'));
      return;
    }
    try {
      var result = navigator.share({ title: 'OneStep Flight', text: familyMessage(), url: url });
      if (result && typeof result.catch === 'function') result.catch(function (error) {
        if (error && error.name === 'AbortError') return;
        setShareStatus(localeText('系统分享面板暂时无法打开。', 'The system share panel could not be opened.', 'No se pudo abrir el panel del sistema.'));
      });
    } catch (error) {
      if (error && error.name === 'AbortError') return;
      setShareStatus(localeText('系统分享面板暂时无法打开。', 'The system share panel could not be opened.', 'No se pudo abrir el panel del sistema.'));
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
    if (!navigator.onLine) {
      localStorage.setItem(SHARE_PENDING_KEY, JSON.stringify({
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
      localStorage.setItem(SHARE_SESSION_KEY, JSON.stringify(session));
      localStorage.removeItem(SHARE_PENDING_KEY);
    }).catch(function () {
      if (shareSession !== session) return;
      localStorage.setItem(SHARE_PENDING_KEY, JSON.stringify({
        viewToken: session.viewToken, updateToken: session.updateToken, payload: payload
      }));
    });
  }

  function scheduleFamilyShareSync() {
    if (!shareSession) return;
    window.clearTimeout(shareSyncTimer);
    shareSyncTimer = window.setTimeout(syncFamilyShare, 500);
  }

  function retryPendingFamilyShare() {
    var pending = readStoredJson(SHARE_PENDING_KEY);
    if (!pending || !navigator.onLine) return;
    if (!shareSession || pending.viewToken !== shareSession.viewToken) {
      localStorage.removeItem(SHARE_PENDING_KEY);
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
    y = wrapCanvasText(context, familyMessageForLocale(currentLocale).split('\n').slice(1).join(' · '), 120, y + 35, 840, 58);
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
    var messages = {
      PNG_RENDER_FAILED: ['卡片绘制失败，请重试。', 'The card could not be drawn. Try again.', 'No se pudo dibujar la tarjeta. Inténtalo de nuevo.'],
      PNG_TO_BLOB_FAILED: ['卡片图片编码失败，请重试。', 'The card image could not be encoded. Try again.', 'No se pudo codificar la imagen. Inténtalo de nuevo.'],
      PNG_FILE_FAILED: ['无法准备分享文件，请重试。', 'The sharing file could not be prepared. Try again.', 'No se pudo preparar el archivo. Inténtalo de nuevo.'],
      PNG_SHARE_FAILED: ['系统分享失败，请重试。', 'System sharing failed. Try again.', 'No se pudo compartir. Inténtalo de nuevo.']
    };
    var message = messages[code] || messages.PNG_RENDER_FAILED;
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
    showToast(localeText('PNG 卡片已下载。', 'PNG card downloaded.', 'Tarjeta PNG descargada.'));
  }

  function shareFamilyCard() {
    if (!pngCardCache) {
      prepareFamilyCard().catch(function () {});
      setShareStatus(localeText('正在准备脱敏图片，请稍后再点一次“分享图片”。',
        'The redacted image is being prepared. Select “Share image” again when it is ready.',
        'Se está preparando la imagen anonimizada. Pulsa de nuevo «Compartir imagen» cuando esté lista.'));
      return;
    }
    try {
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

  function clearJourney() {
    sessionStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(OFFLINE_STORAGE_KEY);
    resetFamilyShareSession();
    invalidatePngCard();
    currentFlight = null;
    currentAction = null;
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
    currentDestinationCopy = null;
    checkInStatusAnswer = null;
    documentSegments = [];
    usableDocumentSegmentIndexes = [];
    selectedDocumentSegmentIndex = null;
    awaitingDocumentSelection = false;
    documentRootFields = {};
    documentRootReviewFields = [];
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
    if (!currentFlight && !awaitingDocumentSelection) return;
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
        savedAt: Date.now(),
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
        documentRootFields: documentRootFields,
        documentRootReviewFields: documentRootReviewFields,
        conflictsConfirmed: conflictsConfirmed,
        clockAnchor: clockAnchor,
        refreshStatusState: refreshStatusState,
        checkInStatusAnswer: checkInStatusAnswer
      }));
    } catch (error) {
      // The current page remains usable if private browsing blocks session storage.
    }
    persistOfflineJourney();
    scheduleFamilyShareSync();
    if (pngCardCache && pngCardCache.key !== pngCacheKey()) invalidatePngCard();
    var familyOverlay = document.querySelector('[data-family-overlay]');
    if (familyOverlay && !familyOverlay.hidden && currentFlight && !pngCardCache) prepareFamilyCard().catch(function () {});
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
      'documentTerminal', 'documentGate', 'documentDataSource', 'liveDataSource', 'terminal', 'gate',
      'checkInCounter', 'checkInDeskOrZone', 'dedicatedBagDropDesk', 'arrivalBaggageBelt',
      'status', 'delayMinutes', 'arrivalDelayMinutes', 'dataLastUpdatedAt', 'dataRetrievedAt',
      'retrievedAt', 'freshestSourceUpdatedAt', 'live', 'demo', 'demoScenario',
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
    var normalized = Object.assign({}, flight, { boardingPassDetected: false });
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
      'titleEn', 'descriptionEn', 'destination', 'urgency', 'reason',
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
    return pick(event, ['id', 'type', 'titleZh', 'titleEn', 'details', 'occurredAt', 'reversible']);
  }

  function safeHistorySnapshot(history, safeFlight) {
    return (Array.isArray(history) ? history : []).map(function (entry) {
      return {
        context: safeContextSnapshot(entry.context, safeFlight),
        action: safeActionSnapshot(entry.action),
        checkInStatusAnswer: entry.checkInStatusAnswer == null ? null : entry.checkInStatusAnswer,
        reversibleEvents: (Array.isArray(entry.reversibleEvents) ? entry.reversibleEvents : []).map(safeEventSnapshot)
      };
    });
  }

  function persistOfflineJourney() {
    if (!currentAction || !journeyContext) return;
    try {
      var safeFlight = safeFlightSnapshot(currentFlight);
      localStorage.setItem(OFFLINE_STORAGE_KEY, JSON.stringify({
        version: 1,
        savedAt: Date.now(),
        flight: safeFlight,
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
    var restoringDocumentSelection = Boolean(saved && !fromOfflineStorage
      && saved.awaitingDocumentSelection && saved.document
      && Array.isArray(saved.documentSegments) && saved.documentSegments.length > 1);
    if (!saved || !saved.flight && !restoringDocumentSelection) return false;
    if (saved.savedAt && Date.now() - Number(saved.savedAt) > OFFLINE_MAX_AGE_MS) return false;
    currentFlight = normalizeSavedFlight(saved.flight);
    journeyContext = normalizeJourneyContext(saved.context, currentFlight);
    currentAction = saved.action || null;
    journeyHistory = (Array.isArray(saved.history) ? saved.history : []).map(function (entry) {
      return {
        context: normalizeJourneyContext(entry.context, currentFlight),
        action: entry.action || null,
        checkInStatusAnswer: entry.checkInStatusAnswer == null ? null : entry.checkInStatusAnswer,
        reversibleEvents: Array.isArray(entry.reversibleEvents) ? entry.reversibleEvents : []
      };
    });
    journeyEvents = Array.isArray(saved.events) ? saved.events : [];
    pendingDocument = fromOfflineStorage ? null : saved.document || null;
    documentSegments = Array.isArray(saved.documentSegments) ? saved.documentSegments.slice()
      : pendingDocument && Array.isArray(pendingDocument.segments) ? pendingDocument.segments.slice() : [];
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
    selectedDocumentSegmentIndex = awaitingDocumentSelection ? null
      : Number.isInteger(saved.selectedDocumentSegmentIndex)
        ? saved.selectedDocumentSegmentIndex
        : usableDocumentSegmentIndexes.length ? usableDocumentSegmentIndexes[0] : null;
    documentRootFields = Object.assign({}, saved.documentRootFields || pendingDocument && pendingDocument.fields || {});
    documentRootReviewFields = Array.isArray(saved.documentRootReviewFields)
      ? saved.documentRootReviewFields.slice()
      : pendingDocument && Array.isArray(pendingDocument.needsReviewFields) ? pendingDocument.needsReviewFields.slice() : [];
    conflictsConfirmed = Boolean(saved.conflictsConfirmed);
    clockAnchor = saved.clockAnchor || null;
    refreshStatusState = fromOfflineStorage && !navigator.onLine ? { code: 'OFFLINE_SNAPSHOT', details: {} }
      : restorableRefreshStatus(saved.refreshStatusState);
    checkInStatusAnswer = journeyContext && journeyContext.checkedIn ? 'yes'
      : saved.checkInStatusAnswer === 'no' || saved.checkInStatusAnswer === 'unsure'
        ? saved.checkInStatusAnswer : null;
    restoredFromOffline = fromOfflineStorage;
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
    try {
      var saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
      if (applySavedJourney(saved, false)) return true;
      if (saved) sessionStorage.removeItem(STORAGE_KEY);
    } catch (error) {
      sessionStorage.removeItem(STORAGE_KEY);
    }
    try {
      var offline = JSON.parse(localStorage.getItem(OFFLINE_STORAGE_KEY));
      if (!offline || !offline.savedAt || Date.now() - Number(offline.savedAt) > OFFLINE_MAX_AGE_MS) {
        localStorage.removeItem(OFFLINE_STORAGE_KEY);
        return false;
      }
      return applySavedJourney(offline, true);
    } catch (error) {
      localStorage.removeItem(OFFLINE_STORAGE_KEY);
      return false;
    }
  }

  document.addEventListener('click', function (event) {
    var actionElement = event.target.closest('[data-action]');
    if (!actionElement) return;
    var action = actionElement.dataset.action;
    if (action === 'upload') navigate('upload');
    if (action === 'choose-file') fileInput.click();
    if (action === 'choose-other-file') {
      fileInput.value = '';
      fileInput.click();
    }
    if (action === 'home') {
      if (currentScreen === 'confirm' && documentSegments.length > 1
          && selectedDocumentSegmentIndex != null) returnToDocumentSegmentSelection();
      else if ((journeyContext || currentAction) && currentScreen !== 'welcome') showEndConfirmation();
      else returnHome();
    }
    if (action === 'journey-back') goBack();
    if (action === 'select-segment') selectDocumentSegment(actionElement.dataset.segmentIndex);
    if (action === 'manual') {
      resetManualDocumentEntry();
      document.querySelector('[data-manual-partial-notice]').hidden = true;
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
      document.querySelector('[data-manual-partial-notice]').hidden = true;
      navigate('manual');
    }
    if (action === 'demo-flight') startFlightLookup('BA117', '2026-09-03', true, null);
    if (action === 'scenario') {
      var today = new Date().toISOString().slice(0, 10);
      startFlightLookup('BA117', today, true, null, actionElement.dataset.scenario);
    }
    if (action === 'continue') {
      if (awaitingDocumentSelection || !currentFlight || selectedDocumentSegmentIndex == null && documentSegments.length > 1) {
        announce(t('segmentSelectionTitle'));
        return;
      }
      if (departureStopReason(currentFlight)) {
        renderDeparturePassedConfirmation(currentFlight);
        showScreen('confirm');
        announce(localeText('这趟航班已经离港，请不要开始普通乘机步骤。', 'This flight has departed. Do not start the normal airport steps.', 'Este vuelo ya ha salido. No inicies los pasos normales del aeropuerto.'));
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
        recordJourneyEvent('FLIGHT_CONFIRMED', '已确认合并后的航班', 'Confirmed the merged flight', true,
          formatFlightNumber(currentFlight.flightNumber));
      }
      persistJourney();
      showCheckInQuestion();
    }
    if (action === 'confirm-conflicts') {
      conflictsConfirmed = true;
      if (journeyContext) journeyContext.conflictsConfirmed = true;
      recordJourneyEvent('CONFLICT_CONFIRMED_WITH_STAFF', '已向工作人员核对关键冲突', 'Confirmed the critical conflict with staff', true);
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
    if (action === 'close-staff') closeStaff();
    if (action === 'copy-staff') copyText(staffCardText(), localeText('大字卡内容已复制。', 'Staff card copied.', 'Texto de la tarjeta copiado.'));
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
    if (action === 'refresh-flight') refreshFlight();
    if (action === 'refresh-arrival') refreshArrivalInfo();
    if (action === 'new-flight-info') beginNewFlightInformation();
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
    lastAutomaticSpeechKey = speechViewKey();
    languageChangeInProgress = false;
  });

  document.querySelector('[data-action="manual-submit"]').addEventListener('submit', function (event) {
    event.preventDefault();
    var flightNumber = document.getElementById('flight-input').value;
    var departureDate = document.getElementById('date-input').value;
    if (partialDocumentEntry && pendingDocument && Number.isInteger(selectedDocumentSegmentIndex)) {
      var index = selectedDocumentSegmentIndex;
      documentSegments[index] = Object.assign({}, documentSegments[index] || {}, {
        flightNumber: normalizeFlightNumber(flightNumber),
        departureDate: departureDate
      });
      usableDocumentSegmentIndexes = [index];
      var selectedResult = selectedDocumentResult(pendingDocument, index);
      resetManualDocumentEntry();
      startFlightLookup(flightNumber, departureDate, false, selectedResult);
      return;
    }
    startFlightLookup(flightNumber, departureDate, false, null);
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Tab' && activeDialog) {
      var focusable = Array.from(activeDialog.querySelectorAll(
        'button:not([disabled]), input:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'))
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
    if (!document.querySelector('[data-staff-overlay]').hidden) closeStaff();
    else if (!document.querySelector('[data-family-overlay]').hidden) closeFamily();
    else if (!document.querySelector('[data-board-overlay]').hidden) closeBoardConfirmation();
    else if (!document.querySelector('[data-landed-overlay]').hidden) closeLandedConfirmation();
    else if (!document.querySelector('[data-end-overlay]').hidden) closeEndConfirmation();
  });

  function refreshAfterReturnToPage() {
    if (document.hidden || !navigator.onLine || !currentFlight || !currentFlight.timeContext
        || (!currentFlight.live && !currentFlight.demoScenario) || journeyContext && journeyContext.boarded
        || flightRequestController) return;
    if (currentScreen === 'confirm') refreshFlight();
    if (currentScreen === 'step' && journeyContext && currentAction) requestNextAction(true);
  }

  window.addEventListener('focus', refreshAfterReturnToPage);
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) refreshAfterReturnToPage();
  });

  window.addEventListener('offline', function () {
    setRefreshStatus('OFFLINE_SNAPSHOT');
    renderConnectivityStatus();
    persistJourney();
  });
  window.addEventListener('online', function () {
    if (refreshStatusState && refreshStatusState.code === 'OFFLINE_SNAPSHOT') setRefreshStatus(null);
    renderConnectivityStatus();
    retryPendingFamilyShare();
    announce(localeText('网络已恢复。', 'You are back online.', 'La conexión se ha restablecido.'));
  });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', function () {
      navigator.serviceWorker.register('/service-worker.js', { updateViaCache: 'none' }).catch(function () {
        // Offline support is optional; the online journey remains available.
      });
    });
  }

  document.querySelector('[data-scenario-panel]').hidden = new URLSearchParams(window.location.search).get('demoScenarios') !== 'true';

  updateLanguage();
  var restoredJourney = restoreJourney();
  updateLanguage();
  if (qaJourneyEnabled && (!restoredJourney || !currentFlight
      || normalizeFlightNumber(currentFlight.flightNumber) !== 'BA117'
      || currentFlight.departureDate !== '2026-08-24' || currentFlight.demo)) {
    startFlightLookup('BA117', '2026-08-24', false, null);
  }
}());
