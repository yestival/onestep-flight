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
  var lastRefreshAttemptAt = 0;
  var refreshStatusState = null;
  var restoredFromOffline = false;
  var currentDestinationCopy = null;
  var shareSession = readStoredJson(SHARE_SESSION_KEY);
  var shareSyncTimer = null;
  var shareCreationInFlight = false;
  var pngCardCache = null;
  var pngCardPreparing = null;
  var pngCardGeneration = 0;
  var arrivalRefreshFlightKey = null;
  var documentSegments = [];
  var selectedDocumentSegmentIndex = null;
  var documentRootFields = {};
  var documentRootReviewFields = [];
  var flightRequestController = null;
  var lookupInFlight = false;
  var speakingActive = false;
  var checkInStatusAnswer = null;
  var qaJourneyEnabled = new URLSearchParams(window.location.search).get('qaJourney') === 'true';
  var activeDialog = null;
  var REFRESH_COOLDOWN_MS = 60000;
  var screens = document.querySelectorAll('.screen');
  var fileInput = document.getElementById('ticket-file');

  var translations = {
    zh: {
      eyebrow: '你的乘机助手', welcomeTitle: '现在，只做下一步', welcomeLead: '上传旅行文件或输入航班信息，我们每次只告诉你现在应该做什么。',
      upload: '上传预订确认、电子客票或登机牌', uploadHint: '支持 PDF、JPG、JPEG、PNG，也可以上传航空公司预订确认页截图、电子客票行程单或登机牌。', manual: '手动输入航班号', family: '分享行程给家属', privacy: '旅行文件只用于生成乘机指引',
      languageLabel: '选择语言', back: '返回', backStep: '返回上一步', fileLabel: '选择预订确认、电子客票或登机牌文件', loadingTitle: '正在准备你的乘机步骤', checkDetails: '请确认', confirmTitle: '这是你的航班吗？', departure: '计划起飞', terminal: '航站楼', gate: '登机口', status: '状态', notPublished: '尚未公布', demoDisclaimer: '演示数据', yesContinue: '是，继续', editFlight: '不是，我要修改', manualContext: '填写航班信息', baggageContext: '确认行李情况', manualTitle: '输入你的航班信息', manualLead: '请输入航班号和出发日期。', flightLabel: '航班号', dateLabel: '出发日期', flightPlaceholder: '例如 BA 117', searchLiveFlight: '查询真实航班', useDemoFlight: '使用 BA 117 演示航班', demoHint: '演示数据', nextAction: '现在要做的事', baggageYes: '有，需要托运', baggageNo: '没有', baggageUnsure: '我不确定', speak: '朗读当前步骤', staff: '请工作人员帮忙', contactFamily: '分享行程给家属', endJourney: '结束本次指引', askForHelp: '请工作人员帮忙', destination: '现在前往', couldNotLoad: '暂时无法读取', errorTitle: '暂时无法继续', errorLead: '请检查输入或网络连接，然后重试。你也可以手动输入，或向工作人员求助。', retry: '重试', enterManually: '手动输入航班', askStaff: '请工作人员帮忙', technicalDetails: '查看技术详情', returnHome: '返回首页', endConfirmTitle: '确定要结束本次指引吗？', endConfirmLead: '结束后，当前步骤记录会从这台设备清除。', continueJourney: '继续指引', confirmEnd: '结束并返回首页', refreshLive: '刷新实时信息', refreshArrival: '重新查询行李转盘', reviewNeeded: '需要你确认', reviewCorrect: '这项信息正确', reviewEdit: '信息不对，手动修改', reviewOnSite: '这项未读取到，稍后现场确认', reviewWrongOnSite: '这项不对，标记为现场确认', currentStep: '当前步骤', boardConfirmTitle: '确认你已经登机吗？', boardConfirmLead: '确认后将进入机上模式，抵达后仍会继续指引。', confirmBoard: '是，我已经登机', cancelBoard: '返回，再检查一下', landedConfirmTitle: '确认你已经下飞机吗？', landedConfirmLead: '确认后，我们会继续指引你领取行李或前往出口。', confirmLanded: '是，我已经下飞机', cancelLanded: '返回，再检查一下', flightDuration: '预计飞行时间', estimatedArrival: '预计到达', timeUntilArrival: '距离预计到达', destinationLocalTime: '目的地当地时间', estimatedDeparture: '预计起飞', boardingTime: '登机时间', seat: '座位', airportLocalTime: '机场当地时间', timeUntilDeparture: '距离预计起飞', whyThisStep: '为什么是这一步？', journeyTimeline: '查看本次行程记录', scenarioTitle: '模拟场景', scenarioLead: '仅供本地流程演示。', conflictTitle: '信息有关键冲突，请先停下来', conflictLead: '请向机场工作人员核对文件和实时屏幕后，再继续。', confirmConflicts: '我已向工作人员核对，继续', checkInArea: '值机 / 托运区域', close: '关闭', copyCard: '复制大字卡内容', familyTitle: '分享行程给家属', familyLead: '创建一个 48 小时有效的只读链接。链接只包含必要的航班与当前步骤信息。', localhostWarning: '当前是 localhost：其他设备无法打开这个地址。请部署到可访问的网址后再分享。', shareLinkLabel: '家属查看链接', createShare: '创建实时共享链接', previewShare: '预览家属页面', shareLink: '分享实时链接', sharePng: '分享或保存 PNG 卡片', copyCurrent: '复制当前步骤', stopShare: '停止共享'
    },
    en: {
      eyebrow: 'Your flight companion', welcomeTitle: 'Now, take the next step', welcomeLead: 'Upload a travel document or enter flight details. We show one action at a time.', upload: 'Upload booking confirmation, e-ticket, or boarding pass', uploadHint: 'PDF, JPG, JPEG, and PNG are supported, including booking screenshots, e-ticket itineraries, and boarding passes.', manual: 'Enter flight number manually', family: 'Share journey with family', privacy: 'Your travel document is only used to create flight guidance', languageLabel: 'Choose language', back: 'Back', backStep: 'Go back', fileLabel: 'Choose a booking confirmation, e-ticket, or boarding pass', loadingTitle: 'Preparing your next step', checkDetails: 'Please confirm', confirmTitle: 'Is this your flight?', departure: 'Scheduled departure', terminal: 'Terminal', gate: 'Gate', status: 'Status', notPublished: 'Not published', demoDisclaimer: 'Demo data', yesContinue: 'Yes, continue', editFlight: 'No, edit it', manualContext: 'Enter flight details', baggageContext: 'Confirm your baggage', manualTitle: 'Enter your flight details', manualLead: 'Enter the flight number and departure date.', flightLabel: 'Flight number', dateLabel: 'Departure date', flightPlaceholder: 'For example BA 117', searchLiveFlight: 'Search live flight', useDemoFlight: 'Use BA 117 demo flight', demoHint: 'Demo data', nextAction: 'Your next action', baggageYes: 'Yes, I have bags to check', baggageNo: 'No', baggageUnsure: 'I am not sure', speak: 'Read this step aloud', staff: 'Ask a member of staff', contactFamily: 'Share journey with family', endJourney: 'End this journey', askForHelp: 'Ask a member of staff', destination: 'Go now to', couldNotLoad: 'Unable to load', errorTitle: 'We cannot continue right now', errorLead: 'Check your details or connection, then try again. You can also enter the flight manually or ask staff for help.', retry: 'Try again', enterManually: 'Enter flight manually', askStaff: 'Ask a member of staff', technicalDetails: 'View technical details', returnHome: 'Return home', endConfirmTitle: 'End this journey?', endConfirmLead: 'Your current step history will be cleared from this device.', continueJourney: 'Continue journey', confirmEnd: 'End and return home', refreshLive: 'Refresh live information', refreshArrival: 'Check baggage belt again', reviewNeeded: 'Please confirm this', reviewCorrect: 'This information is correct', reviewEdit: 'This is wrong; edit manually', reviewOnSite: 'Not found; confirm it at the airport', reviewWrongOnSite: 'This is wrong; confirm it at the airport', currentStep: 'Current step', boardConfirmTitle: 'Have you boarded the aircraft?', boardConfirmLead: 'After confirmation, onboard mode will continue guiding you through arrival.', confirmBoard: 'Yes, I have boarded', cancelBoard: 'Go back and check again', landedConfirmTitle: 'Have you left the aircraft?', landedConfirmLead: 'After confirmation, guidance will continue to baggage claim or the exit.', confirmLanded: 'Yes, I have left the aircraft', cancelLanded: 'Go back and check again', flightDuration: 'Estimated flight time', estimatedArrival: 'Expected arrival', timeUntilArrival: 'Until expected arrival', destinationLocalTime: 'Destination local time', estimatedDeparture: 'Estimated departure', boardingTime: 'Boarding time', seat: 'Seat', airportLocalTime: 'Airport local time', timeUntilDeparture: 'Until expected departure', whyThisStep: 'Why this step?', journeyTimeline: 'View this journey timeline', scenarioTitle: 'Simulated scenarios', scenarioLead: 'For local journey demonstrations only.', conflictTitle: 'Critical details conflict — stop here', conflictLead: 'Confirm the document and live screen with airport staff before continuing.', confirmConflicts: 'I confirmed with staff; continue', checkInArea: 'Check-in / bag-drop area', close: 'Close', copyCard: 'Copy card text', familyTitle: 'Share journey with family', familyLead: 'Create a read-only link that remains active for 48 hours. It includes only essential flight and current-step details.', localhostWarning: 'This is localhost. Other devices cannot open this address. Deploy the app to an accessible URL before sharing.', shareLinkLabel: 'Family viewing link', createShare: 'Create live sharing link', previewShare: 'Preview family page', shareLink: 'Share live link', sharePng: 'Share or save PNG card', copyCurrent: 'Copy current step', stopShare: 'Stop sharing'
    },
    es: {
      eyebrow: 'Tu asistente de vuelo', welcomeTitle: 'Ahora, solo el siguiente paso', welcomeLead: 'Sube un documento de viaje o introduce los datos del vuelo. Mostramos una sola acción cada vez.', upload: 'Subir confirmación de reserva, billete electrónico o tarjeta de embarque', uploadHint: 'Se admiten PDF, JPG, JPEG y PNG, incluidas capturas de reserva, itinerarios electrónicos y tarjetas de embarque.', manual: 'Introducir el vuelo manualmente', family: 'Compartir el viaje con la familia', privacy: 'Tu documento solo se usa para crear las indicaciones del viaje', languageLabel: 'Elegir idioma', back: 'Volver', backStep: 'Volver', fileLabel: 'Elegir confirmación, billete electrónico o tarjeta de embarque', loadingTitle: 'Preparando tu siguiente paso', checkDetails: 'Confirma los datos', confirmTitle: '¿Es este tu vuelo?', departure: 'Salida programada', terminal: 'Terminal', gate: 'Puerta', status: 'Estado', notPublished: 'Aún no publicado', demoDisclaimer: 'Datos de demostración', yesContinue: 'Sí, continuar', editFlight: 'No, modificar', manualContext: 'Datos del vuelo', baggageContext: 'Confirmar el equipaje', manualTitle: 'Introduce los datos de tu vuelo', manualLead: 'Introduce el número de vuelo y la fecha de salida.', flightLabel: 'Número de vuelo', dateLabel: 'Fecha de salida', flightPlaceholder: 'Por ejemplo BA 117', searchLiveFlight: 'Buscar vuelo real', useDemoFlight: 'Usar vuelo BA 117 de demostración', demoHint: 'Datos de demostración', nextAction: 'Tu siguiente acción', baggageYes: 'Sí, tengo equipaje para facturar', baggageNo: 'No', baggageUnsure: 'No estoy seguro', speak: 'Leer este paso', staff: 'Pedir ayuda al personal', contactFamily: 'Compartir el viaje con la familia', endJourney: 'Finalizar este viaje', askForHelp: 'Pedir ayuda al personal', destination: 'Ve ahora a', couldNotLoad: 'No se puede cargar', errorTitle: 'No podemos continuar ahora', errorLead: 'Comprueba los datos o la conexión y vuelve a intentarlo. También puedes introducir el vuelo o pedir ayuda.', retry: 'Reintentar', enterManually: 'Introducir vuelo manualmente', askStaff: 'Pedir ayuda al personal', technicalDetails: 'Ver detalles técnicos', returnHome: 'Volver al inicio', endConfirmTitle: '¿Finalizar este viaje?', endConfirmLead: 'El historial del paso actual se borrará de este dispositivo.', continueJourney: 'Continuar el viaje', confirmEnd: 'Finalizar y volver al inicio', refreshLive: 'Actualizar información', refreshArrival: 'Volver a consultar la cinta', reviewNeeded: 'Confirma este dato', reviewCorrect: 'Este dato es correcto', reviewEdit: 'No es correcto; modificar', reviewOnSite: 'No encontrado; confirmar en el aeropuerto', reviewWrongOnSite: 'No es correcto; confirmar en el aeropuerto', currentStep: 'Paso actual', boardConfirmTitle: '¿Ya has embarcado?', boardConfirmLead: 'Tras confirmar, el modo a bordo seguirá guiándote hasta la llegada.', confirmBoard: 'Sí, ya he embarcado', cancelBoard: 'Volver y comprobar', landedConfirmTitle: '¿Ya has bajado del avión?', landedConfirmLead: 'Tras confirmar, te guiaremos a la recogida de equipaje o a la salida.', confirmLanded: 'Sí, ya he bajado', cancelLanded: 'Volver y comprobar', flightDuration: 'Duración estimada', estimatedArrival: 'Llegada estimada', timeUntilArrival: 'Tiempo hasta la llegada', destinationLocalTime: 'Hora local del destino', estimatedDeparture: 'Salida estimada', boardingTime: 'Hora de embarque', seat: 'Asiento', airportLocalTime: 'Hora local del aeropuerto', timeUntilDeparture: 'Tiempo hasta la salida', whyThisStep: '¿Por qué este paso?', journeyTimeline: 'Ver el historial del viaje', scenarioTitle: 'Escenarios simulados', scenarioLead: 'Solo para probar el recorrido local.', conflictTitle: 'Hay un conflicto importante: detente', conflictLead: 'Confirma el documento y la pantalla con el personal antes de continuar.', confirmConflicts: 'Lo confirmé con el personal; continuar', checkInArea: 'Zona de facturación / entrega', close: 'Cerrar', copyCard: 'Copiar el texto de la tarjeta', familyTitle: 'Compartir el viaje con la familia', familyLead: 'Crea un enlace de solo lectura válido durante 48 horas. Solo incluye el vuelo y el paso actual imprescindibles.', localhostWarning: 'Esta dirección es localhost y no se puede abrir en otro dispositivo. Despliega la aplicación en una URL accesible antes de compartir.', shareLinkLabel: 'Enlace para la familia', createShare: 'Crear enlace en directo', previewShare: 'Vista previa familiar', shareLink: 'Compartir enlace en directo', sharePng: 'Compartir o guardar tarjeta PNG', copyCurrent: 'Copiar el paso actual', stopShare: 'Dejar de compartir'
    }
  };

  var staffContent = {
    titleZh: '请帮助我', titleEn: 'Please help me', titleEs: 'Por favor, ayúdeme',
    bodyZh: '您好，我需要乘机帮助。可以请您帮我吗？',
    bodyEn: 'Hello. I need help with my flight. Could you please help me?',
    bodyEs: 'Hola. Necesito ayuda con mi vuelo. ¿Puede ayudarme?'
  };

  Object.assign(translations.zh, {
    upload: '上传出行行程单或登机牌',
    uploadHint: '支持航空公司行程单、电子客票、预订确认或登机牌 PDF，也支持这些文件的截图（JPG、JPEG、PNG）。',
    fileLabel: '选择航空公司行程单、电子客票、预订确认或登机牌',
    speak: '朗读这一步',
    scenarioNormal: '正常', scenarioGateChange: '登机口变更', scenarioDelay: '延误', scenarioCancel: '取消', scenarioStale: '过期数据',
    backStep: '返回上一步', departureLocalTime: '起飞时间（出发地当地时间）', arrivalLocalTime: '到达时间（目的地当地时间）',
    scheduledDepartureLocal: '计划起飞 · 出发地当地时间', estimatedDepartureLocal: '预计起飞 · 出发地当地时间', actualDepartureLocal: '实际起飞 · 出发地当地时间',
    scheduledArrivalLocal: '计划到达 · 目的地当地时间', estimatedArrivalLocal: '预计到达 · 目的地当地时间', actualArrivalLocal: '实际到达 · 目的地当地时间',
    multipleSegments: '多个航段', chooseSegment: '请选择你现在要乘坐的航班', chooseSegmentLead: '选择后才会查询这一个航段的实时信息。', selectSegment: '选择这个航段', selectedSegment: '已选择',
    checkInContext: '确认值机状态', checkInQuestion: '你已经完成值机，或已经拿到登机牌了吗？', checkInQuestionLead: '如果你已经取得登机牌，通常表示已经值机。',
    boardingPassCheckIn: '已从登机牌判断：你已经值机', boardingPassCheckInLead: '请确认后继续；如果实际情况不同，可以更改。', checkInPresetContinue: '是，继续', thisIsWrong: '这不对',
    checkInYes: '✓ 是，我已经完成值机', checkInNo: '还没有', checkInUnsure: '我不确定', stopSpeaking: '停止朗读',
    staffCardInstruction: '请把这个页面拿给工作人员看', globalFooter: '© 2026 OneStep Flight · 无障碍乘机指引',
    allFlightInfo: '查看全部航班信息', flightSourceLabel: '航班信息来源',
    departurePassedTitle: '这趟航班已经离港', departurePassedLead: '点击下方“请工作人员帮忙”，并前往航空公司服务柜台出示给工作人员。',
    enterNewFlight: '输入新的航班信息', backHome: '返回首页', uploadContext: '选择旅行文件', uploadTitle: '选择或上传文件', chooseFile: '选择文件', previewPng: '预览 PNG 卡片'
  });
  Object.assign(translations.en, {
    upload: 'Upload travel itinerary or boarding pass',
    uploadHint: 'Upload an airline itinerary, e-ticket, booking confirmation, or boarding pass as a PDF or screenshot (JPG, JPEG, or PNG).',
    fileLabel: 'Choose an airline itinerary, e-ticket, booking confirmation, or boarding pass',
    speak: 'Read this step aloud',
    scenarioNormal: 'Normal', scenarioGateChange: 'Gate change', scenarioDelay: 'Delay', scenarioCancel: 'Cancellation', scenarioStale: 'Stale data',
    backStep: 'Go back', departureLocalTime: 'Departure (origin local time)', arrivalLocalTime: 'Arrival (destination local time)',
    scheduledDepartureLocal: 'Scheduled departure · Origin local time', estimatedDepartureLocal: 'Estimated departure · Origin local time', actualDepartureLocal: 'Actual departure · Origin local time',
    scheduledArrivalLocal: 'Scheduled arrival · Destination local time', estimatedArrivalLocal: 'Estimated arrival · Destination local time', actualArrivalLocal: 'Actual arrival · Destination local time',
    multipleSegments: 'Multiple flight segments', chooseSegment: 'Choose the flight you are taking now', chooseSegmentLead: 'We will look up live information only after you choose a segment.', selectSegment: 'Choose this segment', selectedSegment: 'Selected',
    checkInContext: 'Confirm check-in status', checkInQuestion: 'Have you checked in or received your boarding pass?', checkInQuestionLead: 'If you already have a boarding pass, you are usually checked in.',
    boardingPassCheckIn: 'Your boarding pass indicates that you are checked in', boardingPassCheckInLead: 'Confirm to continue, or change this if it is not correct.', checkInPresetContinue: 'Yes, continue', thisIsWrong: 'This is not correct',
    checkInYes: '✓ Yes, I have checked in', checkInNo: 'Not yet', checkInUnsure: 'I am not sure', stopSpeaking: 'Stop reading',
    staffCardInstruction: 'Show this page to a member of staff', globalFooter: '© 2026 OneStep Flight · Accessible air-travel guidance',
    allFlightInfo: 'View all flight information', flightSourceLabel: 'Flight information source',
    departurePassedTitle: 'This flight has departed', departurePassedLead: 'Select “Ask a member of staff” below, then go to the airline service desk and show it to a staff member.',
    enterNewFlight: 'Enter new flight information', backHome: 'Return home', uploadContext: 'Choose a travel document', uploadTitle: 'Choose or upload a file', chooseFile: 'Choose file', previewPng: 'Preview PNG card'
  });
  Object.assign(translations.es, {
    upload: 'Subir itinerario de viaje o tarjeta de embarque',
    uploadHint: 'Sube un itinerario de la aerolínea, billete electrónico, confirmación de reserva o tarjeta de embarque en PDF o como captura (JPG, JPEG o PNG).',
    fileLabel: 'Elegir itinerario, billete electrónico, confirmación de reserva o tarjeta de embarque',
    speak: 'Leer este paso en voz alta',
    scenarioNormal: 'Normal', scenarioGateChange: 'Cambio de puerta', scenarioDelay: 'Retraso', scenarioCancel: 'Cancelación', scenarioStale: 'Datos antiguos',
    backStep: 'Volver', departureLocalTime: 'Salida (hora local de origen)', arrivalLocalTime: 'Llegada (hora local de destino)',
    scheduledDepartureLocal: 'Salida programada · Hora local de origen', estimatedDepartureLocal: 'Salida estimada · Hora local de origen', actualDepartureLocal: 'Salida real · Hora local de origen',
    scheduledArrivalLocal: 'Llegada programada · Hora local de destino', estimatedArrivalLocal: 'Llegada estimada · Hora local de destino', actualArrivalLocal: 'Llegada real · Hora local de destino',
    multipleSegments: 'Varios tramos', chooseSegment: 'Elige el vuelo que vas a tomar ahora', chooseSegmentLead: 'Solo consultaremos la información en directo después de elegir un tramo.', selectSegment: 'Elegir este tramo', selectedSegment: 'Seleccionado',
    checkInContext: 'Confirmar la facturación', checkInQuestion: '¿Ya has facturado o recibido tu tarjeta de embarque?', checkInQuestionLead: 'Si ya tienes una tarjeta de embarque, normalmente ya has facturado.',
    boardingPassCheckIn: 'La tarjeta de embarque indica que ya has facturado', boardingPassCheckInLead: 'Confirma para continuar o cambia esta respuesta si no es correcta.', checkInPresetContinue: 'Sí, continuar', thisIsWrong: 'Esto no es correcto',
    checkInYes: '✓ Sí, ya he facturado', checkInNo: 'Todavía no', checkInUnsure: 'No estoy seguro', stopSpeaking: 'Detener la lectura',
    staffCardInstruction: 'Muestre esta página a un miembro del personal', globalFooter: '© 2026 OneStep Flight · Guía accesible para viajar en avión',
    allFlightInfo: 'Ver toda la información del vuelo', flightSourceLabel: 'Fuente de información del vuelo',
    departurePassedTitle: 'Este vuelo ya ha salido', departurePassedLead: 'Pulsa «Pedir ayuda al personal» y ve al mostrador de atención de la aerolínea para mostrárselo al personal.',
    enterNewFlight: 'Introducir un vuelo nuevo', backHome: 'Volver al inicio', uploadContext: 'Elegir un documento de viaje', uploadTitle: 'Elegir o subir un archivo', chooseFile: 'Elegir archivo', previewPng: 'Vista previa de la tarjeta PNG'
  });

  translations.zh.copyCard = '复制卡片内容';
  translations.en.copyCard = 'Copy card content';
  translations.es.copyCard = 'Copiar contenido de la tarjeta';
  Object.assign(translations.zh, { datePlaceholder: '请选择出发日期', preparingPng: '正在准备卡片…' });
  Object.assign(translations.en, { datePlaceholder: 'Choose departure date', preparingPng: 'Preparing card…' });
  Object.assign(translations.es, { datePlaceholder: 'Elige la fecha de salida', preparingPng: 'Preparando tarjeta…' });
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

  var documentFieldLabels = {
    document_type: ['文件类型', 'Document type', 'Tipo de documento'],
    flight_number: ['航班号', 'Flight number', 'Número de vuelo'],
    departure_date: ['出发日期', 'Departure date', 'Fecha de salida'],
    scheduled_departure: ['计划起飞时间', 'Scheduled departure', 'Salida programada'],
    scheduled_arrival: ['计划到达时间', 'Scheduled arrival', 'Llegada programada'],
    boarding_time: ['登机时间', 'Boarding time', 'Hora de embarque'],
    departure_airport: ['出发机场', 'Departure airport', 'Aeropuerto de salida'],
    arrival_airport: ['到达机场', 'Arrival airport', 'Aeropuerto de llegada'],
    terminal: ['航站楼', 'Terminal', 'Terminal'],
    gate: ['登机口', 'Gate', 'Puerta'],
    seat: ['座位', 'Seat', 'Asiento']
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
    var locale = String(value || '').trim().toLowerCase();
    return ['zh', 'en', 'es'].indexOf(locale) >= 0 ? locale : 'en';
  }

  function localeFromLanguageTag(value) {
    var primaryLanguage = String(value || '').trim().toLowerCase().split(/[-_]/)[0];
    return ['zh', 'en', 'es'].indexOf(primaryLanguage) >= 0 ? primaryLanguage : null;
  }

  function resolveLocalePreference(stored, languages, language) {
    var storedLocale = String(stored || '').trim().toLowerCase();
    if (['zh', 'en', 'es'].indexOf(storedLocale) >= 0) return storedLocale;

    var preferences = Array.isArray(languages) ? languages : [];
    for (var index = 0; index < preferences.length; index += 1) {
      var matchedLocale = localeFromLanguageTag(preferences[index]);
      if (matchedLocale) return matchedLocale;
    }

    return localeFromLanguageTag(language) || 'en';
  }

  function detectInitialLocale() {
    var stored = null;
    try { stored = localStorage.getItem(LOCALE_KEY); } catch (error) { /* Use browser language. */ }
    return resolveLocalePreference(stored, navigator.languages, navigator.language);
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
      localeSelect.value = currentLocale;
      localeSelect.setAttribute('aria-label', t('languageLabel'));
    }
    var speakButton = document.querySelector('[data-action="speak"]');
    if (speakButton) {
      speakButton.setAttribute('aria-label', speakingActive ? t('stopSpeaking') : t('speak'));
      speakButton.setAttribute('aria-pressed', String(speakingActive));
      var speakLabel = speakButton.querySelector('span');
      if (speakLabel) speakLabel.textContent = speakingActive ? t('stopSpeaking') : t('speak');
    }
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
    if (currentAction) renderJourneyResponse();
    if (currentError) renderErrorMessage();
    renderRefreshStatus();
    renderConnectivityStatus();
    renderStaffCard();
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
    document.querySelectorAll('[data-action="upload"], [data-action="demo-flight"], [data-action="manual-submit"] button[type="submit"], [data-action="continue"], [data-action="new-flight-info"]').forEach(function (button) {
      var continueBlocked = button.dataset.action === 'continue' && (documentSegments.length > 1
        && selectedDocumentSegmentIndex == null || departureStopReason(currentFlight)
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
          throw error;
        }
        return payload;
      });
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

  var documentTypeCopy = {
    BOARDING_PASS: ['登机牌', 'Boarding pass', 'Tarjeta de embarque'],
    ITINERARY: ['行程单', 'Itinerary', 'Itinerario'],
    E_TICKET: ['电子客票', 'E-ticket', 'Billete electrónico'],
    BOOKING_CONFIRMATION: ['预订确认', 'Booking confirmation', 'Confirmación de reserva'],
    UNKNOWN: ['暂未识别的文件', 'Unrecognized document', 'Documento no reconocido']
  };

  function documentTypeName(value) {
    return textFor(documentTypeCopy[String(value || 'UNKNOWN').toUpperCase()] || documentTypeCopy.UNKNOWN);
  }

  function preferredOperationalTime(flight, phase) {
    var prefix = phase === 'arrival' ? 'Arrival' : 'Departure';
    var actual = flight['actual' + prefix];
    if (actual) return { value: actual, label: 'actual' + prefix + 'Local' };
    var estimated = flight['estimated' + prefix];
    if (estimated) return { value: estimated, label: 'estimated' + prefix + 'Local' };
    return { value: flight['scheduled' + prefix], label: 'scheduled' + prefix + 'Local' };
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
    if (documentSegments.length > 1 && selectedDocumentSegmentIndex == null) return null;
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
    document.querySelector('[data-flight-number]').textContent = formatFlightNumber(flight.flightNumber);
    document.querySelector('[data-departure-airport]').textContent = flight.departureAirport || '—';
    document.querySelector('[data-departure-name]').textContent = flight.departureAirportName || flight.departureAirport || '—';
    document.querySelector('[data-arrival-airport]').textContent = flight.arrivalAirport || '—';
    document.querySelector('[data-arrival-name]').textContent = flight.arrivalAirportName || flight.arrivalAirport || '—';
    document.querySelector('[data-departure-date]').textContent = formatDate(flight.departureDate);
    document.querySelector('[data-scheduled-departure]').textContent = formatAtDeparture(flight.scheduledDeparture, false);
    document.querySelector('[data-estimated-departure]').textContent = formatAtDeparture(flight.estimatedDeparture, false);
    var departureLocal = preferredOperationalTime(flight, 'departure');
    var arrivalLocal = preferredOperationalTime(flight, 'arrival');
    document.querySelector('[data-route-departure-label]').textContent = t(departureLocal.label);
    document.querySelector('[data-route-departure-time]').textContent = formatAtDeparture(departureLocal.value, true);
    document.querySelector('[data-route-arrival-label]').textContent = t(arrivalLocal.label);
    document.querySelector('[data-route-arrival-time]').textContent = formatAtArrival(arrivalLocal.value, true);
    document.querySelector('[data-boarding-time]').textContent = formatAtDeparture(
      flight.documentBoardingTime || (flight.timeContext && flight.timeContext.boardingTime), false);
    document.querySelector('[data-terminal]').textContent = formatTerminalValue(flight.terminal) || '—';
    document.querySelector('[data-gate]').textContent = formatGateValue(flight.gate) || t('notPublished');
    document.querySelector('[data-check-in-area]').textContent = formatCheckInValue(flight.checkInDeskOrZone || flight.checkInCounter) || t('notPublished');
    document.querySelector('[data-seat]').textContent = flight.seat || (pendingDocument && pendingDocument.seat) || '—';
    var source = document.querySelector('[data-flight-source]');
    var fromDocument = !flight.timeContext && flight.dataSource === 'NUTRIENT_DWS_DATA_EXTRACTION';
    var simulated = Boolean(flight.demoScenario);
    source.textContent = simulated
      ? localeText('模拟 · ' + scenarioDisplayName(flight.demoScenario), 'Simulated · ' + scenarioDisplayName(flight.demoScenario), 'Simulado · ' + scenarioDisplayName(flight.demoScenario))
      : flight.demo
        ? localeText('演示数据', 'Demo data', 'Datos de demostración')
        : fromDocument
          ? localeText('由 Nutrient 读取', 'Read by Nutrient', 'Leído por Nutrient')
          : flight.documentDataSource
            ? localeText('Nutrient 文件 + 实时来源', 'Nutrient document + live sources', 'Documento Nutrient + fuentes en directo')
            : localeText('多源实时信息', 'Cross-checked live data', 'Datos contrastados en directo');
    source.classList.toggle('demo-tag', flight.demo);
    var extractionNote = document.querySelector('[data-extraction-note]');
    extractionNote.hidden = !fromDocument;
    if (fromDocument) {
      var review = pendingDocument && pendingDocument.needsReviewFields ? pendingDocument.needsReviewFields : [];
      var documentType = String(pendingDocument && pendingDocument.documentType || 'UNKNOWN').toUpperCase();
      var typeName = documentTypeName(documentType);
      var confirmedBoardingPass = documentType === 'BOARDING_PASS'
        && pendingDocument.boardingPassDetected && !pendingDocument.requiresDocumentConfirmation;
      var doesNotConfirmCheckIn = ['BOOKING_CONFIRMATION', 'E_TICKET', 'ITINERARY'].indexOf(documentType) >= 0;
      extractionNote.textContent = confirmedBoardingPass
        ? localeText('Nutrient 已识别为高置信登机牌，将跳过值机步骤。请确认航班信息和仍需核对的字段。', 'Nutrient identified a high-confidence boarding pass, so the check-in step will be skipped. Confirm the flight and any fields that still need review.', 'Nutrient identificó una tarjeta de embarque de alta confianza, por lo que se omitirá la facturación. Confirma el vuelo y cualquier dato que aún deba revisarse.')
        : doesNotConfirmCheckIn
          ? localeText('Nutrient 识别为“' + typeName + '”。这份文件不能证明已经值机，指引仍会包含值机步骤。', 'Nutrient classified this as “' + typeName + '”. This document does not prove that check-in is complete, so the guidance will still include check-in.', 'Nutrient lo clasificó como «' + typeName + '». Este documento no demuestra que la facturación esté completa, por lo que las indicaciones aún incluirán ese paso.')
          : localeText('Nutrient 识别为“' + typeName + '”。请核对不确定字段；只有高置信登机牌才表示已经值机。', 'Nutrient classified this as “' + typeName + '”. Review uncertain fields; only a high-confidence boarding pass means check-in is complete.', 'Nutrient lo clasificó como «' + typeName + '». Comprueba los datos dudosos; solo una tarjeta de embarque de alta confianza confirma la facturación.');
    }
    renderDocumentReview(fromDocument);
    var liveNote = document.querySelector('[data-live-source-note]');
    liveNote.hidden = !flight.timeContext || (!flight.live && !simulated);
    var confirmRefresh = document.querySelector('.screen-confirm [data-action="refresh-flight"]');
    if (confirmRefresh) confirmRefresh.hidden = !flight.timeContext || (!flight.live && !simulated);
    if (flight.timeContext && (flight.live || simulated)) {
      var sourceUpdated = flight.dataLastUpdatedAt;
      var retrieved = flight.dataRetrievedAt;
      document.querySelector('[data-live-updated]').textContent = sourceUpdated
        ? localeText('来源更新：' + formatRetrieved(sourceUpdated) + '；本次取得：' + formatRetrieved(retrieved) + '。', 'Source updated ' + formatRetrieved(sourceUpdated) + '; retrieved ' + formatRetrieved(retrieved) + '.', 'Fuente actualizada: ' + formatRetrieved(sourceUpdated) + '; consultada: ' + formatRetrieved(retrieved) + '.')
        : localeText('来源更新时间缺失；本次取得：' + formatRetrieved(retrieved) + '。', 'Source update time unavailable; retrieved ' + formatRetrieved(retrieved) + '.', 'Hora de actualización no disponible; consultada: ' + formatRetrieved(retrieved) + '.');
      var sourceLink = document.querySelector('[data-live-source-link]');
      sourceLink.hidden = !flight.sourceUrl;
      sourceLink.href = flight.sourceUrl || '#';
      sourceLink.textContent = localeText('查看数据来源', 'View source', 'Ver fuente');
    }
    var status = statusLabels[flight.status] || statusLabels.UNKNOWN;
    document.querySelector('[data-flight-status]').textContent = textFor(status);
    renderProviderSummary(flight.providerResults);
    var departureBlock = renderDeparturePassedConfirmation(flight);
    var continueButton = document.querySelector('[data-action="continue"]');
    continueButton.disabled = Boolean(departureBlock || flight.requiresUserConfirmation && !conflictsConfirmed);
    var continueLabel = continueButton.querySelector('[data-button-label]') || continueButton;
    continueLabel.textContent = fromDocument
      ? localeText('确认并查询实时航班', 'Confirm and check live flight', 'Confirmar y consultar el vuelo real')
      : t('yesContinue');
    renderConflictCard();
    renderFlightChanges();
    updateClockAnchor();
    renderTimeStatus();
    renderEvidence();
    renderConnectivityStatus();
    renderSegmentSelector();
  }

  function scenarioDisplayName(value) {
    var normalized = String(value || '').replaceAll('_', ' ').toLowerCase();
    return normalized ? normalized.charAt(0).toUpperCase() + normalized.slice(1) : '';
  }

  function renderFieldSourceNotes(flight) {
    var fields = [
      ['[data-departure-airport]', 'departureAirport'],
      ['[data-arrival-airport]', 'arrivalAirport'],
      ['[data-departure-date]', 'departureDate'],
      ['[data-scheduled-departure]', 'scheduledDeparture'],
      ['[data-estimated-departure]', 'estimatedDeparture'],
      ['[data-boarding-time]', 'boardingTime'],
      ['[data-terminal]', 'terminal'],
      ['[data-gate]', 'gate'],
      ['[data-check-in-area]', 'checkInDeskOrZone'],
      ['[data-flight-status]', 'status']
    ];
    fields.forEach(function (entry) {
      var target = document.querySelector('.screen-confirm ' + entry[0]);
      if (!target) return;
      var note = target.parentElement.querySelector('[data-source-field="' + entry[1] + '"]');
      if (!note) {
        note = document.createElement('small');
        note.className = 'field-source-note';
        note.dataset.sourceField = entry[1];
        target.insertAdjacentElement('afterend', note);
      }
      var source = flight.fieldProvenance && flight.fieldProvenance[entry[1]];
      note.hidden = !source;
      if (!source) return;
      var selected = selectedFieldCandidate(source);
      note.textContent = localeText('来源：', 'Source: ', 'Fuente: ') + providerDisplayName(source.provider)
        + ' · ' + qualityDisplayName(selected && selected.quality)
        + ' · ' + relativeSourceUpdate(source.sourceUpdatedAt || source.retrievedAt);
    });
  }

  function selectedFieldCandidate(source) {
    var candidates = source && Array.isArray(source.candidateValues) ? source.candidateValues : [];
    return candidates.find(function (candidate) { return candidate.provider === source.provider; }) || candidates[0] || null;
  }

  function providerDisplayName(provider) {
    if (provider === 'AERODATABOX') return 'AeroDataBox';
    if (provider === 'SERPAPI') return 'SerpApi';
    if (provider === 'VARIFLIGHT') return 'VariFlight';
    if (provider === 'NUTRIENT_DWS_DATA_EXTRACTION') return 'Nutrient DWS';
    return provider || localeText('未知来源', 'Unknown source', 'Fuente desconocida');
  }

  function qualityDisplayName(quality) {
    var normalized = String(quality || '').toUpperCase();
    if (normalized.indexOf('DOCUMENT') >= 0) return localeText('登机牌识别', 'boarding-pass extraction', 'lectura del documento');
    if (normalized.indexOf('LIVE') >= 0 || normalized.indexOf('REAL') >= 0 || normalized.indexOf('ESTIMATED') >= 0) {
      return localeText('实时运营数据', 'live operational data', 'datos operativos en directo');
    }
    if (normalized.indexOf('STATUS_METADATA') >= 0) return localeText('状态摘要', 'status summary', 'resumen de estado');
    if (normalized.indexOf('SCHEDULE') >= 0) return localeText('计划航班', 'scheduled flight', 'vuelo programado');
    if (normalized.indexOf('BASIC') >= 0) return localeText('基础运营数据', 'basic operational data', 'datos operativos básicos');
    return localeText('来源数据', 'provider data', 'datos de la fuente');
  }

  function relativeSourceUpdate(value) {
    var updated = Date.parse(value || '');
    if (!Number.isFinite(updated)) return localeText('更新时间缺失', 'update time unavailable', 'hora de actualización no disponible');
    var minutes = Math.max(0, Math.floor((Date.now() - updated) / 60000));
    if (minutes < 1) return localeText('刚刚更新', 'just updated', 'actualizado ahora');
    if (minutes < 60) return localeText(minutes + '分钟前更新', minutes + ' min ago', 'hace ' + minutes + ' min');
    var hours = Math.floor(minutes / 60);
    if (hours < 24) return localeText(hours + '小时前更新', hours + ' hr ago', 'hace ' + hours + ' h');
    return formatRetrieved(value);
  }

  function renderProviderSummary(providerResults) {
    var container = document.querySelector('[data-provider-summary]');
    container.replaceChildren();
    if (!providerResults || !Object.keys(providerResults).length) {
      container.hidden = true;
      return;
    }
    container.hidden = false;
    ['SERPAPI', 'AERODATABOX', 'VARIFLIGHT'].forEach(function (provider) {
      var result = providerResults[provider];
      if (!result) return;
      var chip = document.createElement('span');
      chip.className = 'provider-chip';
      chip.dataset.outcome = result.outcome;
      var label = provider === 'SERPAPI' ? 'SerpApi' : provider === 'AERODATABOX' ? 'AeroDataBox' : 'VariFlight';
      var quality = String(result.quality || '').toUpperCase();
      var errorCode = String(result.errorCode || '').toUpperCase();
      var state = providerOutcomeDescription(result);
      chip.textContent = label + ' · ' + state + (result.fromCache ? localeText(' · 缓存', ' · cached', ' · caché') : '');
      container.appendChild(chip);
    });
  }

  function providerOutcomeDescription(result) {
    var quality = String(result && result.quality || '').toUpperCase();
    var errorCode = String(result && result.errorCode || '').toUpperCase();
    if (result && result.outcome === 'SUCCESS') {
      if (quality.indexOf('STATUS_METADATA') >= 0) return localeText('已取得航班状态摘要', 'flight status summary available', 'resumen del estado obtenido');
      if (quality.indexOf('SCHEDULE') >= 0) return localeText('已取得计划航班', 'scheduled flight available', 'vuelo programado obtenido');
      if (quality.indexOf('LIVE') >= 0 || quality.indexOf('REAL') >= 0) return localeText('已取得实时数据', 'live data available', 'datos en directo obtenidos');
      return localeText('已取得数据', 'data available', 'datos obtenidos');
    }
    if (result && (result.outcome === 'PENDING' || errorCode.indexOf('_PENDING') >= 0)) {
      return localeText('已有可用结果，本次未继续等待', 'not awaited after a usable result', 'no se esperó tras obtener un resultado utilizable');
    }
    if (result && result.outcome === 'NOT_CONFIGURED') return localeText('尚未配置', 'not configured', 'sin configurar');
    if (errorCode.indexOf('NO_RESULTS') >= 0) return localeText('该来源没有这条记录', 'no record for this flight', 'esta fuente no tiene este vuelo');
    if (errorCode.indexOf('IDENTITY_MISMATCH') >= 0) return localeText('该来源返回了其他航班', 'returned another flight', 'la fuente devolvió otro vuelo');
    if (result && result.outcome === 'PARSE_ERROR') return localeText('返回格式暂时无法读取', 'response format cannot be read right now', 'el formato no se puede leer ahora');
    if (result && result.outcome === 'TIMEOUT') return localeText('请求超时', 'request timed out', 'la solicitud agotó el tiempo');
    if (result && result.outcome === 'RATE_LIMITED') return localeText('请求受限', 'rate limited', 'límite de solicitudes');
    if (result && (result.outcome === 'AUTHENTICATION_FAILED' || result.outcome === 'FORBIDDEN_OR_QUOTA')) return localeText('来源授权异常', 'source authorization issue', 'problema de autorización');
    if (result && result.outcome === 'NOT_FOUND') return localeText('该来源未确认这条记录', 'this source could not confirm this flight', 'esta fuente no confirmó el vuelo');
    return localeText('暂时不可用', 'temporarily unavailable', 'no disponible temporalmente');
  }

  function renderDocumentReview(fromDocument) {
    var panel = document.querySelector('[data-field-review]');
    var continueButton = document.querySelector('[data-action="continue"]');
    if (!fromDocument || !pendingDocument) {
      panel.hidden = true;
      continueButton.disabled = false;
      return;
    }

    var confirmed = pendingDocument.confirmedReviewFields || [];
    var onSite = pendingDocument.onSiteReviewFields || [];
    var reviewFields = (pendingDocument.needsReviewFields || []).slice();
    if (pendingDocument.requiresDocumentConfirmation && reviewFields.indexOf('document_type') < 0) reviewFields.unshift('document_type');
    var remaining = reviewFields.filter(function (field) {
      return confirmed.indexOf(field) < 0 && onSite.indexOf(field) < 0;
    });
    var hasCriticalFlightDetails = Boolean(currentFlight.flightNumber && currentFlight.departureDate);
    if (!remaining.length) {
      panel.hidden = true;
      continueButton.disabled = !hasCriticalFlightDetails;
      return;
    }

    var fieldName = remaining[0];
    var field = pendingDocument.fields && pendingDocument.fields[fieldName];
    var value = fieldName === 'document_type' ? documentTypeName(pendingDocument.documentType)
      : field && field.value ? field.value : null;
    var critical = fieldName === 'flight_number' || fieldName === 'departure_date';
    panel.hidden = false;
    panel.dataset.reviewField = fieldName;
    document.querySelector('[data-review-question]').textContent = localeText('请核对：', 'Please check: ', 'Comprueba: ')
      + textFor(documentFieldLabels[fieldName] || [fieldName, fieldName]);
    document.querySelector('[data-review-value]').textContent = value || localeText('文件中未读取到', 'Not found in the document', 'No se encontró en el documento');
    var confidence = field && Number.isFinite(Number(field.confidence)) ? Math.round(Number(field.confidence) * 100) : 0;
    document.querySelector('[data-review-confidence]').textContent = value
      ? localeText('读取置信度：' + confidence + '%', 'Extraction confidence: ' + confidence + '%', 'Confianza de extracción: ' + confidence + '%')
      : localeText('这项不会被静默猜测。', 'This field will not be guessed.', 'Este dato no se adivinará.');
    var confirmButton = document.querySelector('[data-action="review-confirm"]');
    confirmButton.hidden = critical && !value;
    confirmButton.textContent = value ? t('reviewCorrect') : t('reviewOnSite');
    document.querySelector('[data-action="review-edit"]').textContent = critical
      ? t('reviewEdit')
      : t('reviewWrongOnSite');
    continueButton.disabled = true;
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
    return {
      flightNumber: normalizeFlightNumber(flightNumber),
      departureDate: departureDate,
      demo: Boolean(demo),
      demoScenario: demoScenario || null,
      document: documentResult || null,
      refresh: Boolean(refresh)
    };
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
      boardingPassDetected: Boolean(result.boardingPassDetected),
      live: false,
      demo: false
    };
  }

  function renderSegmentSelector() {
    var selector = document.querySelector('[data-segment-selector]');
    var options = document.querySelector('[data-segment-options]');
    if (!selector || !options) return;
    var multiple = documentSegments.length > 1;
    selector.hidden = !multiple;
    options.replaceChildren();
    if (multiple) {
      documentSegments.forEach(function (segment, index) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'segment-option';
        button.dataset.action = 'select-segment';
        button.dataset.segmentIndex = String(index);
        button.setAttribute('aria-pressed', String(index === selectedDocumentSegmentIndex));
        var route = (segment.departureAirport || '—') + ' → ' + (segment.arrivalAirport || '—');
        var heading = document.createElement('strong');
        heading.textContent = formatFlightNumber(segment.flightNumber || '—') + ' · ' + route;
        var details = document.createElement('span');
        details.textContent = formatDate(segment.departureDate) + (segment.scheduledDeparture
          ? ' · ' + String(segment.scheduledDeparture).replace('T', ' ') : '');
        var choice = document.createElement('span');
        choice.className = 'segment-choice-label';
        choice.textContent = index === selectedDocumentSegmentIndex ? t('selectedSegment') : t('selectSegment');
        button.append(heading, details, choice);
        options.append(button);
      });
    }
    var awaitingSelection = multiple && selectedDocumentSegmentIndex == null;
    var flightCard = document.querySelector('.screen-confirm .flight-card');
    var choices = document.querySelector('.screen-confirm .confirm-choice-actions');
    if (flightCard) flightCard.hidden = awaitingSelection;
    if (choices) choices.hidden = awaitingSelection;
    if (awaitingSelection) {
      var fieldReview = document.querySelector('.screen-confirm [data-field-review]');
      var conflictCard = document.querySelector('.screen-confirm [data-conflict-card]');
      if (fieldReview) fieldReview.hidden = true;
      if (conflictCard) conflictCard.hidden = true;
    }
    var continueButton = document.querySelector('[data-action="continue"]');
    if (continueButton) continueButton.disabled = lookupInFlight || awaitingSelection
      || Boolean(currentFlight && (departureStopReason(currentFlight)
        || currentFlight.requiresUserConfirmation && !conflictsConfirmed));
  }

  function selectDocumentSegment(index) {
    if (!pendingDocument || !documentSegments[index]) return;
    selectedDocumentSegmentIndex = index;
    var segment = documentSegments[index];
    var selectedFields = Object.assign({}, segment.fields || {});
    if (documentRootFields.document_type) {
      selectedFields.document_type = documentRootFields.document_type;
    }
    var reviewPrefix = 'segments[' + index + '].';
    var selectedReviewFields = documentRootReviewFields
      .filter(function (field) { return String(field).indexOf(reviewPrefix) === 0; })
      .map(function (field) { return String(field).substring(reviewPrefix.length); });
    pendingDocument = Object.assign({}, pendingDocument, {
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
      confirmedReviewFields: (pendingDocument.confirmedReviewFields || []).indexOf('document_type') >= 0 ? ['document_type'] : [],
      onSiteReviewFields: (pendingDocument.onSiteReviewFields || []).indexOf('document_type') >= 0 ? ['document_type'] : [],
      segments: [segment]
    });
    beginFlight(extractionToFlight(pendingDocument), pendingDocument);
    renderSegmentSelector();
    persistJourney();
    announce(localeText('已选择航段，请确认后查询实时信息。', 'Segment selected. Confirm it to look up live information.', 'Tramo seleccionado. Confírmalo para consultar la información en directo.'));
  }

  function startFlightLookup(flightNumber, departureDate, demo, documentResult, demoScenario) {
    if (lookupInFlight) return;
    if (!documentResult) {
      documentSegments = [];
      selectedDocumentSegmentIndex = null;
      documentRootFields = {};
      documentRootReviewFields = [];
    }
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

  function classifyDocumentResult(result) {
    var segments = result && Array.isArray(result.segments) ? result.segments.filter(Boolean) : [];
    if (!segments.length) return { outcome: 'UNUSABLE', usableSegments: [], partialSegment: null };
    var usableSegments = segments.filter(function (segment) {
      return validDocumentFlightNumber(segment.flightNumber) && validDocumentDepartureDate(segment.departureDate);
    });
    if (usableSegments.length) return { outcome: 'USABLE', usableSegments: usableSegments, partialSegment: null };
    var partialSegments = segments.filter(function (segment) {
      return validDocumentFlightNumber(segment.flightNumber) || validDocumentDepartureDate(segment.departureDate);
    });
    return partialSegments.length
      ? { outcome: 'PARTIAL', usableSegments: [], partialSegment: partialSegments[0] }
      : { outcome: 'UNUSABLE', usableSegments: [], partialSegment: null };
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

  function showPartialDocument(segment) {
    resetDocumentResultState();
    var flightInput = document.getElementById('flight-input');
    var dateInput = document.getElementById('date-input');
    flightInput.value = validDocumentFlightNumber(segment && segment.flightNumber)
      ? formatFlightNumber(segment.flightNumber) : '';
    setDateInputValue(dateInput, validDocumentDepartureDate(segment && segment.departureDate)
      ? segment.departureDate : '');
    document.querySelector('[data-manual-partial-notice]').hidden = false;
    navigationHistory.push('welcome');
    showScreen('manual');
    announce(t('partialDocumentNotice'));
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
    var extraction = fetchJson('/api/documents/boarding-pass', {
      method: 'POST', body: formData,
      onResponse: function () { updateLoadingProgress(72, uploadLoadingMessages, 1); }
    });

    extraction.then(function (result) {
      if (activeRequest !== requestSequence) return;
      updateLoadingProgress(100, uploadLoadingMessages, 2);
      var assessment = classifyDocumentResult(result);
      if (assessment.outcome === 'UNUSABLE') {
        showUnusableDocument();
        return;
      }
      if (assessment.outcome === 'PARTIAL') {
        showPartialDocument(assessment.partialSegment);
        return;
      }
      var usableSegments = assessment.usableSegments;
      var selectedResult = result;
      if (usableSegments.length === 1) {
        selectedResult = Object.assign({}, result, usableSegments[0], {
          fields: result.fields || {}, segments: [usableSegments[0]]
        });
      }
      lastLookupContext = {
        flightNumber: normalizeFlightNumber(selectedResult.flightNumber),
        departureDate: selectedResult.departureDate || null,
        departureAirport: selectedResult.departureAirport || null,
        arrivalAirport: selectedResult.arrivalAirport || null
      };
      documentSegments = usableSegments.slice();
      selectedDocumentSegmentIndex = documentSegments.length === 1 ? 0 : null;
      documentRootFields = Object.assign({}, result.fields || {});
      documentRootReviewFields = Array.isArray(result.needsReviewFields) ? result.needsReviewFields.slice() : [];
      var extractedFlight = extractionToFlight(selectedResult);
      beginFlight(extractedFlight, selectedResult);
      recordJourneyEvent('DOCUMENT_EXTRACTED',
        'Nutrient 已从隐私安全文件提取字段',
        'Nutrient extracted fields from the privacy-safe document', true);
      navigationHistory.push('welcome');
      showScreen('confirm');
      announce(localeText('文件字段已提取，请确认航班。', 'Document fields extracted. Please confirm the flight.', 'Se extrajeron los datos del documento. Confirma el vuelo.'));
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      clearInterval(loadingTimer);
      clearTimeout(loadingTimeout);
      showError(error, retryRequest);
    }).finally(function () { setLookupBusy(false); });
  }

  function beginFlight(flight, documentResult) {
    var requestedDepartureDate = lastLookupContext && lastLookupContext.departureDate;
    currentFlight = flight;
    lastLookupContext = {
      flightNumber: normalizeFlightNumber(flight.flightNumber),
      departureDate: requestedDepartureDate || flight.departureDate || null,
      departureAirport: flight.departureAirport || null,
      arrivalAirport: flight.arrivalAirport || null
    };
    pendingDocument = documentResult || null;
    currentAction = null;
    journeyHistory = [];
    journeyEvents = [];
    journeyContext = null;
    conflictsConfirmed = false;
    lastRefreshAttemptAt = 0;
    currentError = null;
    checkInStatusAnswer = null;
    renderFlight(flight);
    persistJourney();
  }

  function resolveUploadedFlight() {
    if (!currentFlight || !pendingDocument) return;
    if (documentSegments.length > 1 && selectedDocumentSegmentIndex == null) {
      announce(t('chooseSegment'));
      return;
    }
    if (lookupInFlight) return;
    var activeRequest = ++requestSequence;
    var previousEvents = journeyEvents.slice();
    currentError = null;
    retryRequest = resolveUploadedFlight;
    setLookupBusy(true);
    runLoading(loadingMessages);
    var request = resolvePayload(currentFlight.flightNumber, currentFlight.departureDate, false, pendingDocument, null, false);
    postFlightJson('/api/journeys/resolve-flight', request, function () {
      updateLoadingProgress(72, loadingMessages, 1);
    }).then(function (flight) {
      if (activeRequest !== requestSequence) return;
      updateLoadingProgress(100, loadingMessages, 2);
      currentFlight = flight;
      journeyEvents = previousEvents;
      conflictsConfirmed = false;
      lastRefreshAttemptAt = 0;
      recordLiveChanges(currentFlight.latestChanges);
      renderFlight(currentFlight);
      showScreen('confirm');
      persistJourney();
      announce(localeText('文件信息与实时航班已经合并，请再次确认。', 'Document and live flight details are now combined. Please confirm.', 'El documento y los datos del vuelo se han combinado. Confírmalos.'));
    }).catch(function (error) {
      if (activeRequest !== requestSequence) return;
      clearInterval(loadingTimer);
      clearTimeout(loadingTimeout);
      showError(error, retryRequest);
    }).finally(function () { setLookupBusy(false); });
  }

  function confirmDocumentField(markForOnSiteReview) {
    if (!pendingDocument) return;
    var panel = document.querySelector('[data-field-review]');
    var fieldName = panel.dataset.reviewField;
    if (!fieldName) return;
    var critical = fieldName === 'flight_number' || fieldName === 'departure_date';
    if (markForOnSiteReview && critical) {
      document.getElementById('flight-input').value = currentFlight.flightNumber || '';
      setDateInputValue(document.getElementById('date-input'), currentFlight.departureDate);
      navigate('manual');
      return;
    }

    var field = pendingDocument.fields && pendingDocument.fields[fieldName];
    var hasValue = Boolean(field && field.value);
    var collectionName = markForOnSiteReview || !hasValue ? 'onSiteReviewFields' : 'confirmedReviewFields';
    pendingDocument[collectionName] = pendingDocument[collectionName] || [];
    if (pendingDocument[collectionName].indexOf(fieldName) < 0) pendingDocument[collectionName].push(fieldName);
    renderFlight(currentFlight);
    persistJourney();
    announce(localeText('已记录，请核对下一项。', 'Saved. Please check the next field.', 'Guardado. Comprueba el siguiente dato.'));
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
      boardingPassDetected: Boolean(currentFlight.boardingPassDetected || (pendingDocument && pendingDocument.boardingPassDetected)),
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
      presetDismissed: false,
      urgency: 'NORMAL',
      reason: 'TRAVELER_CHECK_IN_CONFIRMATION',
      lastUpdated: calibratedNowIso()
    };
  }

  function showCheckInQuestion() {
    currentAction = buildCheckInQuestion();
    checkInStatusAnswer = journeyContext && journeyContext.boardingPassDetected ? 'yes' : null;
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
    if (!currentFlight || !currentFlight.timeContext || (!currentFlight.live && !currentFlight.demoScenario)) return;
    if (!navigator.onLine) {
      setRefreshStatus('OFFLINE_SNAPSHOT');
      renderConnectivityStatus();
      return;
    }
    if (!beginRefreshAttempt()) return;
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
    }).finally(updateRefreshButtonCooldown);
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

  function refreshArrivalInfo(force) {
    if (!currentFlight || !journeyContext || !journeyContext.boarded) return;
    if (!navigator.onLine) {
      setRefreshStatus('OFFLINE_SNAPSHOT');
      renderConnectivityStatus();
      return;
    }
    if (!currentFlight.live && !currentFlight.demoScenario) return;
    if (!force && !beginRefreshAttempt()) return;
    if (force) lastRefreshAttemptAt = Date.now();
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
    }).finally(updateRefreshButtonCooldown);
  }

  function maybeRefreshArrivalOnEntry() {
    if (!currentAction || currentAction.actionCode !== 'BAGGAGE_CLAIM' || !journeyContext
        || !journeyContext.landed || !navigator.onLine || (!currentFlight.live && !currentFlight.demoScenario)) return;
    var key = [currentFlight.flightNumber, currentFlight.departureDate, journeyContext.landed].join('|');
    if (arrivalRefreshFlightKey === key) return;
    arrivalRefreshFlightKey = key;
    setRefreshStatus('CHECKING_ARRIVAL');
    window.setTimeout(function () { refreshArrivalInfo(true); }, 0);
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
    if (refreshRequested && !beginRefreshAttempt()) return;
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
      updateRefreshButtonCooldown();
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
    stopSpeech(false);
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
    document.querySelector('.screen-step .step-assistance-actions').hidden = departureException;
    var completeButton = document.querySelector('[data-complete-action]');
    completeButton.hidden = question || helpOnlyAction;
    var refreshCooldownRemaining = Math.max(0, REFRESH_COOLDOWN_MS - (Date.now() - lastRefreshAttemptAt));
    completeButton.disabled = !question && currentAction.actionCode === 'WAIT'
      && (!navigator.onLine || Boolean(lastRefreshAttemptAt && refreshCooldownRemaining > 0));
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
      var usePreset = Boolean(journeyContext && journeyContext.boardingPassDetected && !currentAction.presetDismissed);
      document.querySelector('[data-checkin-preset]').hidden = !usePreset;
      document.querySelector('[data-checkin-choices]').hidden = usePreset;
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
        completionLabel.textContent = textFor(completionLabels[currentAction.actionCode]
          || ['我已完成', 'I have completed this', 'He completado este paso']);
      }
      var sourceNote = document.querySelector('[data-action-source]');
      if (sourceNote) sourceNote.hidden = true;
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
    renderEvidence();
    renderJourneyTimeline();
    renderTimeStatus();
    renderFlightChanges();
    renderRefreshStatus();
    renderConnectivityStatus();
    announceLatestChangesOr(actionLocaleText(currentAction, 'title'));
    maybeRefreshArrivalOnEntry();
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
    if (journeyContext.checkedIn || journeyContext.boardingPassDetected) count += 1;
    if (journeyContext.hasCheckedBaggage && journeyContext.bagDropped) count += 1;
    if (journeyContext.securityCleared) count += 1;
    if (journeyContext.atGate) count += 1;
    if (journeyContext.boarded) count += 1;
    if (journeyContext.landed) count += 1;
    if (journeyContext.baggageCollected) count += 1;
    if (journeyContext.exitedAirport) count += 1;
    return count;
  }

  function sourceDescription(action) {
    var reason = String(action && action.reason || '').trim();
    if (!reason) return '';
    if (isDepartureStopReason(reason)) {
      return localeText(
        '所选航班的有效起飞时间已经过去，或实时状态已确认离港；普通乘机步骤已经停止。',
        'The selected flight’s effective departure time has passed, or live status confirms departure. Normal airport steps have stopped.',
        'La hora de salida efectiva ya pasó o el estado en directo confirma la salida. Se detuvieron los pasos normales.');
    }
    if (currentFlight && currentFlight.demo) {
      return currentFlight.demoScenario
        ? localeText('模拟数据', 'Simulated data', 'Datos simulados')
        : localeText('演示数据', 'Demo data', 'Datos de demostración');
    }
    return localeText('依据：你已经完成的步骤、航班时间和最新机场信息。',
      'Based on your completed steps, flight timing, and the latest available airport information.',
      'Se basa en los pasos completados, los horarios y la información aeroportuaria disponible.');
  }

  function updateClockAnchor() {
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
        updateRefreshButtonCooldown();
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
    var time = currentFlight && currentFlight.timeContext;
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
      values.textContent = (providerConflict
        ? localeText('来源值：', 'Source values: ', 'Valores de las fuentes: ')
        : localeText('文件：', 'Document: ', 'Documento: ')) + (conflict.documentValue || '—')
        + (providerConflict
          ? localeText(' · 当前采用：', ' · Selected: ', ' · Seleccionado: ')
          : localeText(' · 实时：', ' · Live: ', ' · En directo: ')) + (conflict.liveValue || '—');
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

  function renderEvidence() {
    var containers = document.querySelectorAll('[data-evidence-list]');
    var evidence = currentFlight && currentFlight.documentEvidence
      ? currentFlight.documentEvidence : pendingDocument && pendingDocument.fields;
    document.querySelectorAll('[data-evidence-panel]').forEach(function (panel) {
      panel.hidden = !currentFlight;
      var summary = panel.querySelector('summary');
      if (summary && panel.closest('[data-screen="confirm"]')) {
        summary.textContent = localeText('查看数据来源', 'View data sources', 'Ver fuentes de datos');
      }
    });
    containers.forEach(function (container) {
      container.replaceChildren();
      if (evidence && Object.keys(evidence).length) {
        var documentGroup = evidenceGroup(localeText('Nutrient 文件证据', 'Nutrient document evidence', 'Evidencia del documento Nutrient'));
        var fields = ['flight_number', 'departure_date', 'departure_airport', 'arrival_airport', 'boarding_time', 'terminal', 'gate', 'seat'];
        var list = document.createElement('ul');
        fields.forEach(function (name) {
          var field = evidence[name];
          if (!field || !field.value) return;
          var confidence = Number(field.confidence);
          var reliability = confidence >= 0.85
            ? localeText('较高读取置信度', 'higher extraction confidence', 'confianza de extracción alta')
            : confidence >= 0.65
              ? localeText('需要核对', 'needs review', 'requiere revisión')
              : localeText('较低读取置信度', 'low extraction confidence', 'confianza de extracción baja');
          var page = field.page == null ? localeText('页码缺失', 'page missing', 'página no disponible')
            : localeText('第 ' + (field.page + 1) + ' 页', 'page ' + (field.page + 1), 'página ' + (field.page + 1));
          var box = field.boundingBox
            ? 'bbox (' + [field.boundingBox.x, field.boundingBox.y, field.boundingBox.width, field.boundingBox.height]
              .map(function (value) { return Math.round(Number(value) * 100) / 100; }).join(', ') + ')'
            : localeText('边界框缺失', 'bbox missing', 'recuadro no disponible');
          var item = document.createElement('li');
          item.textContent = textFor(documentFieldLabels[name] || [name, name]) + ': ' + field.value + ' · '
            + reliability + ' · ' + page + ' · ' + box
            + ' · ' + (field.sourceLabel || localeText('证据标签缺失', 'evidence label missing', 'etiqueta de evidencia no disponible'));
          list.appendChild(item);
        });
        if (list.childElementCount) {
          documentGroup.appendChild(list);
          container.appendChild(documentGroup);
        }
      }

      if (currentFlight && (currentFlight.live || currentFlight.demo)) {
        var liveGroup = evidenceGroup(currentFlight.demo
          ? localeText('模拟 / 固定数据来源', 'Simulation / fixture source', 'Fuente simulada / fija')
          : localeText('实时来源证据', 'Live provider evidence', 'Evidencia de fuentes en directo'));
        var sourceText = document.createElement('p');
        sourceText.textContent = (currentFlight.providerResults
          ? localeText('航班信息已通过可用来源交叉核对', 'Flight information cross-checked across available providers', 'Información contrastada entre las fuentes disponibles')
          : currentFlight.demo
            ? localeText('演示数据', 'Demonstration data', 'Datos de demostración')
            : localeText('可用航班信息', 'Available flight information', 'Información de vuelo disponible'))
          + ' · ' + (currentFlight.dataLastUpdatedAt
            ? localeText('来源更新 ', 'source updated ', 'fuente actualizada ') + formatRetrieved(currentFlight.dataLastUpdatedAt)
            : localeText('来源更新时间缺失', 'source update time missing', 'hora de actualización no disponible'))
          + ' · ' + localeText('取得 ', 'retrieved ', 'consultado ') + formatRetrieved(currentFlight.dataRetrievedAt);
        liveGroup.appendChild(sourceText);
        if (currentFlight.sourceUrl) {
          var link = document.createElement('a');
          link.href = currentFlight.sourceUrl;
          link.target = '_blank';
          link.rel = 'noopener noreferrer';
          link.textContent = localeText('打开数据来源', 'Open provider source', 'Abrir la fuente');
          liveGroup.appendChild(link);
        }
        if (!currentFlight.demo && currentFlight.providerResults) {
          var providerList = document.createElement('ul');
          Object.keys(currentFlight.providerResults).sort().forEach(function (name) {
            var result = currentFlight.providerResults[name];
            var item = document.createElement('li');
            item.textContent = providerDisplayName(name) + ': ' + providerOutcomeDescription(result)
              + (result.quality ? ' · ' + qualityDisplayName(result.quality) : '')
              + (result.sourceUpdatedAt ? ' · ' + localeText('来源更新 ', 'source updated ', 'fuente actualizada ') + formatRetrieved(result.sourceUpdatedAt) : '')
              + (result.retrievedAt ? ' · ' + localeText('取得 ', 'retrieved ', 'consultado ') + formatRetrieved(result.retrievedAt) : '')
              + (result.fromCache ? localeText(' · 缓存', ' · cached', ' · caché') : '');
            providerList.appendChild(item);
          });
          liveGroup.appendChild(providerList);
        }
        if (!currentFlight.demo && currentFlight.fieldProvenance) {
          var selectedList = document.createElement('ul');
          selectedList.className = 'source-field-list';
          ['flightNumber', 'departureDate', 'departureAirport', 'departureAirportName', 'arrivalAirport',
            'arrivalAirportName', 'scheduledDeparture', 'scheduledArrival', 'estimatedDeparture',
            'actualDeparture', 'estimatedArrival', 'actualArrival', 'terminal', 'gate',
            'checkInDeskOrZone', 'dedicatedBagDropDesk', 'arrivalBaggageBelt', 'status', 'departureDelayMinutes',
            'arrivalDelayMinutes'].forEach(function (field) {
            var selected = currentFlight.fieldProvenance[field];
            if (!selected || selected.value == null) return;
            var selectedCandidate = selectedFieldCandidate(selected);
            var otherCandidates = (selected.candidateValues || []).filter(function (candidate) {
              return candidate.provider !== selected.provider;
            });
            var item = document.createElement('li');
            item.className = 'source-field-item';
            var adopted = document.createElement('strong');
            adopted.textContent = provenanceFieldName(field) + ': ' + formatProvenanceValue(field, selected.value);
            var sourceLine = document.createElement('span');
            sourceLine.textContent = localeText('采用来源：', 'Selected source: ', 'Fuente seleccionada: ')
              + providerDisplayName(selected.provider) + ' · '
              + qualityDisplayName(selectedCandidate && selectedCandidate.quality);
            var updateLine = document.createElement('span');
            updateLine.textContent = localeText('更新时间：', 'Updated: ', 'Actualizado: ')
              + (selected.sourceUpdatedAt || selected.retrievedAt
                ? formatRetrieved(selected.sourceUpdatedAt || selected.retrievedAt)
                : localeText('缺失', 'unavailable', 'no disponible'));
            var alternatives = document.createElement('span');
            alternatives.textContent = localeText('其他来源值：', 'Other source values: ', 'Valores de otras fuentes: ')
              + (otherCandidates.length ? otherCandidates.map(function (candidate) {
                return providerDisplayName(candidate.provider) + ' = ' + formatProvenanceValue(field, candidate.value);
              }).join(' · ') : localeText('无', 'none', 'ninguno'));
            var reason = document.createElement('span');
            reason.textContent = localeText('选择原因：', 'Why selected: ', 'Motivo de selección: ')
              + fieldSelectionReason(field, selected, selectedCandidate);
            item.append(adopted, sourceLine, updateLine, alternatives, reason);
            selectedList.appendChild(item);
          });
          liveGroup.appendChild(selectedList);
        }
        container.appendChild(liveGroup);
      }

      var decisionText = currentAction ? String(sourceDescription(currentAction) || '').trim() : '';
      if (decisionText) {
        var decisionGroup = evidenceGroup(localeText('行动决策依据', 'Decision evidence', 'Motivo de la decisión'));
        var decision = document.createElement('p');
        decision.textContent = decisionText;
        decisionGroup.appendChild(decision);
        container.appendChild(decisionGroup);
      }
      var stepPanel = container.closest('.step-evidence-panel');
      if (stepPanel) stepPanel.hidden = !decisionText;
    });
  }

  function provenanceFieldName(field) {
    var labels = {
      flightNumber: ['航班号', 'Flight number', 'Número de vuelo'], departureDate: ['出发日期', 'Departure date', 'Fecha de salida'],
      departureAirport: ['出发机场', 'Departure airport', 'Aeropuerto de salida'], departureAirportName: ['出发机场名称', 'Departure airport name', 'Nombre del aeropuerto de salida'],
      arrivalAirport: ['到达机场', 'Arrival airport', 'Aeropuerto de llegada'], arrivalAirportName: ['到达机场名称', 'Arrival airport name', 'Nombre del aeropuerto de llegada'],
      scheduledDeparture: ['计划起飞', 'Scheduled departure', 'Salida programada'], scheduledArrival: ['计划到达', 'Scheduled arrival', 'Llegada programada'],
      estimatedDeparture: ['预计起飞', 'Estimated departure', 'Salida estimada'], actualDeparture: ['实际起飞', 'Actual departure', 'Salida real'],
      estimatedArrival: ['预计到达', 'Estimated arrival', 'Llegada estimada'], actualArrival: ['实际到达', 'Actual arrival', 'Llegada real'],
      terminal: ['航站楼', 'Terminal', 'Terminal'], gate: ['登机口', 'Gate', 'Puerta'],
      checkInDeskOrZone: ['值机 / 托运区域', 'Check-in / bag drop area', 'Zona de facturación / entrega'], dedicatedBagDropDesk: ['专用托运柜台', 'Bag-drop desk', 'Mostrador de entrega'],
      arrivalBaggageBelt: ['到达行李转盘', 'Arrival baggage belt', 'Cinta de equipaje'],
      status: ['航班状态', 'Flight status', 'Estado del vuelo'], departureDelayMinutes: ['起飞延误', 'Departure delay', 'Retraso de salida'],
      arrivalDelayMinutes: ['到达延误', 'Arrival delay', 'Retraso de llegada']
    };
    return textFor(labels[field] || [field, field]);
  }

  function formatProvenanceValue(field, value) {
    if (value == null || value === '') return '—';
    if (/Arrival/.test(field)) return formatAtArrival(value, true);
    if (/Departure|boardingTime/.test(field) && field !== 'departureDate') return formatAtDeparture(value, true);
    if (field === 'departureDate') return formatDate(value);
    if (/DelayMinutes$/.test(field)) return value + localeText(' 分钟', ' min', ' min');
    return value;
  }

  function fieldSelectionReason(field, selected, selectedCandidate) {
    if (!selected.candidateValues || selected.candidateValues.length < 2) {
      return localeText('这是唯一可用的来源值。', 'This is the only usable source value.', 'Es el único valor utilizable.');
    }
    if (field === 'departureAirportName' || field === 'arrivalAirportName') {
      return localeText('机场身份按 IATA 代码匹配，名称语言和全称差异不构成冲突。',
        'Airport identity is matched by IATA code; wording and language differences do not create a conflict.',
        'La identidad del aeropuerto se compara por código IATA; las diferencias de nombre o idioma no son conflictos.');
    }
    var operational = ['estimatedDeparture', 'actualDeparture', 'estimatedArrival', 'actualArrival',
      'terminal', 'gate', 'checkInDeskOrZone', 'dedicatedBagDropDesk', 'arrivalBaggageBelt', 'status',
      'departureDelayMinutes', 'arrivalDelayMinutes'].indexOf(field) >= 0;
    if (operational) {
      return localeText('运营字段优先采用实时运营数据；仅计划航班的来源不能提供此字段。',
        'Operational fields prefer live operational data; schedule-only sources cannot supply this field.',
        'Los datos operativos prefieren fuentes en directo; una fuente de horarios no puede aportar este campo.');
    }
    return localeText('计划字段先按该字段的来源质量选择；质量相同时采用更新时间较新的来源。',
      'Planned fields use field-specific source quality, then the newer source update when quality is equal.',
      'Los campos programados usan la calidad específica; si es igual, se elige la fuente más reciente.');
  }

  function evidenceGroup(titleText) {
    var group = document.createElement('section');
    group.className = 'evidence-group';
    var title = document.createElement('h3');
    title.textContent = titleText;
    group.appendChild(title);
    return group;
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
      DOCUMENT_EXTRACTED: 'Documento leído por Nutrient', FLIGHT_CONFIRMED: 'Vuelo confirmado',
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

  function renderRefreshStatus() {
    var state = refreshStatusState;
    var text = '';
    if (state) {
      var seconds = state.details && state.details.seconds;
      var messages = {
        CHECKING: ['正在检查新的实时信息……', 'Checking for new live information…', 'Comprobando información nueva…'],
        CHECKING_ARRIVAL: ['正在查询行李转盘……', 'Checking the baggage belt…', 'Consultando la cinta de equipaje…'],
        REFRESH_SUCCESS_LATEST_RETAINED: ['实时检查完成，正在显示最近一次成功取得的信息。', 'Live check complete. The latest successful information is shown.', 'Consulta completada. Se muestra la información más reciente.'],
        REFRESH_FAILED_LATEST_RETAINED: ['刷新失败，已保留最近一次成功取得的航班信息。', 'Refresh failed. The latest successful flight information is retained.', 'No se pudo actualizar. Se mantiene la última información disponible.'],
        REFRESH_FAILED_STEP_RETAINED: ['刷新失败，已保留航班信息和当前步骤。', 'Refresh failed. Your flight information and current step are retained.', 'No se pudo actualizar. El vuelo y el paso actual se mantienen.'],
        ARRIVAL_REFRESH_SUCCESS: ['到达信息已刷新。', 'Arrival information refreshed.', 'Información de llegada actualizada.'],
        ARRIVAL_REFRESH_FAILED: ['到达信息刷新失败，已保存的行程仍然可用。', 'Arrival refresh failed. Your saved journey is still available.', 'No se pudo actualizar la llegada. El viaje guardado sigue disponible.'],
        OFFLINE_SNAPSHOT: ['当前离线，正在显示已保存的行程。', 'You are offline. Showing the saved journey.', 'Sin conexión. Mostrando el viaje guardado.'],
        REFRESH_COOLDOWN: ['请等待 ' + seconds + ' 秒后再刷新。', 'Please wait ' + seconds + ' seconds before refreshing again.', 'Espera ' + seconds + ' segundos antes de actualizar.']
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
    document.querySelectorAll('[data-action="refresh-flight"], [data-action="refresh-arrival"]').forEach(function (button) {
      if (!button.hidden) button.disabled = offline;
    });
  }

  function beginRefreshAttempt() {
    var remaining = REFRESH_COOLDOWN_MS - (Date.now() - lastRefreshAttemptAt);
    if (lastRefreshAttemptAt && remaining > 0) {
      var seconds = Math.ceil(remaining / 1000);
      setRefreshStatus('REFRESH_COOLDOWN', { seconds: seconds });
      var message = document.querySelector('[data-refresh-status]').textContent;
      announce(message);
      return false;
    }
    lastRefreshAttemptAt = Date.now();
    updateRefreshButtonCooldown();
    return true;
  }

  function updateRefreshButtonCooldown() {
    var remaining = Math.max(0, REFRESH_COOLDOWN_MS - (Date.now() - lastRefreshAttemptAt));
    var disabled = !navigator.onLine || Boolean(lastRefreshAttemptAt && remaining > 0);
    document.querySelectorAll('[data-action="refresh-flight"], [data-action="refresh-arrival"]').forEach(function (refreshButton) {
      if (!refreshButton.hidden) refreshButton.disabled = disabled;
    });
    var waitButton = currentAction && currentAction.actionCode === 'WAIT'
      ? document.querySelector('[data-complete-action]') : null;
    if (waitButton) waitButton.disabled = disabled;
    if (refreshStatusState && refreshStatusState.code === 'REFRESH_COOLDOWN') {
      if (remaining > 0) {
        refreshStatusState.details.seconds = Math.ceil(remaining / 1000);
        renderRefreshStatus();
      } else {
        setRefreshStatus(null);
      }
    }
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
      if (journeyContext.checkedIn || journeyContext.boardingPassDetected) { zh.push('值机'); en.push('check-in'); es.push('facturación'); }
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
    if (answer !== 'unsure') snapshotJourney();
    var checkedIn = answer === 'yes';
    checkInStatusAnswer = answer;
    journeyContext.checkedIn = checkedIn;
    if (answer === 'no') journeyContext.boardingPassDetected = false;
    journeyContext.needsAssistance = answer === 'unsure';
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

  function dismissBoardingPassPreset() {
    if (!currentAction || currentAction.stepType !== 'CHECK_IN_QUESTION') return;
    currentAction.presetDismissed = true;
    checkInStatusAnswer = null;
    journeyContext.boardingPassDetected = false;
    renderJourneyResponse();
    persistJourney();
    announce(localeText('已取消登机牌预设，请选择实际值机状态。', 'Boarding-pass preset cleared. Choose your actual check-in status.', 'Se quitó la opción de la tarjeta de embarque. Elige tu estado real de facturación.'));
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

  function renderErrorMessage() {
    var message = currentError ? currentError[currentLocale] || currentError.en : t('errorLead');
    document.querySelector('[data-error-message]').textContent = message;
    var details = document.querySelector('[data-error-technical]');
    var content = document.querySelector('[data-error-technical-content]');
    var lines = currentError && currentError.technicalLines || [];
    details.hidden = !lines.length;
    details.open = false;
    content.textContent = lines.join('\n');
  }

  function showError(error, retry) {
    var details = error && error.apiError;
    var providerLines = details && details.providerResults
      ? Object.keys(details.providerResults).sort().map(function (name) {
        var result = details.providerResults[name] || {};
        return name + ': ' + (result.outcome || 'UNKNOWN') + (result.errorCode ? ' (' + result.errorCode + ')' : '');
      })
      : [];
    var lookup = lastLookupContext ? JSON.parse(JSON.stringify(lastLookupContext)) : null;
    var code = details && details.code || error && error.code || 'REQUEST_FAILED';
    var timedOut = code === 'CLIENT_FLIGHT_TIMEOUT';
    currentError = {
      code: code,
      zh: timedOut ? '航班查询超过 20 秒，已安全停止。请检查网络后重试。' : code.indexOf('INVALID') >= 0 ? '请检查航班号和出发日期，然后重试。' : '暂时无法取得这次航班信息。请重试，或请工作人员帮忙。',
      en: timedOut ? 'The flight lookup took more than 20 seconds and was stopped safely. Check your connection and try again.' : code.indexOf('INVALID') >= 0 ? 'Check the flight number and departure date, then try again.' : 'We cannot get this flight information right now. Try again or ask a member of staff.',
      es: timedOut ? 'La consulta tardó más de 20 segundos y se detuvo de forma segura. Comprueba la conexión y vuelve a intentarlo.' : code.indexOf('INVALID') >= 0 ? 'Comprueba el número de vuelo y la fecha y vuelve a intentarlo.' : 'No podemos obtener este vuelo ahora. Reinténtalo o pide ayuda al personal.',
      lookupContext: lookup,
      technicalLines: [code].concat(providerLines)
    };
    retryRequest = retry;
    var flight = lookup && lookup.flightNumber ? formatFlightNumber(lookup.flightNumber) : '';
    var date = lookup && lookup.departureDate;
    setStaffContent(flight ? '请帮我查找航班 ' + flight : '请帮我查找航班',
      flight ? 'Please help me find flight ' + flight : 'Please help me find my flight',
      flight
        ? '您好，请帮我查询 ' + flight + '。出发日期是 ' + formatDateForLocale(date, 'zh-CN') + '。请帮我确认航站楼、值机或行李托运区域，以及登机口。'
        : '您好，我暂时无法取得航班信息。请帮我确认航站楼、值机或行李托运区域，以及登机口。',
      flight
        ? 'Hello. Please help me check flight ' + flight + '. The departure date is ' + formatDateForLocale(date, 'en-GB') + '. Please confirm the terminal, check-in or bag-drop area, and boarding gate.'
        : 'Hello. I cannot get my flight information. Please confirm the terminal, check-in or bag-drop area, and boarding gate.');
    renderErrorMessage();
    showScreen('error');
    announce(currentError[currentLocale] || currentError.en);
  }

  function stopSpeech(showMessage) {
    if (!('speechSynthesis' in window)) return;
    var wasSpeaking = speakingActive || window.speechSynthesis.speaking || window.speechSynthesis.pending;
    window.speechSynthesis.cancel();
    speakingActive = false;
    var button = document.querySelector('[data-action="speak"]');
    if (button) {
      button.setAttribute('aria-pressed', 'false');
      button.setAttribute('aria-label', t('speak'));
      var label = button.querySelector('span');
      if (label) label.textContent = t('speak');
    }
    if (showMessage && wasSpeaking) showToast(localeText('朗读已停止。', 'Reading stopped.', 'Lectura detenida.'));
  }

  function speak() {
    if (!('speechSynthesis' in window)) {
      showToast(localeText('当前浏览器不支持朗读。', 'Speech is not supported in this browser.', 'Este navegador no admite la lectura en voz alta.'));
      return;
    }
    if (speakingActive || window.speechSynthesis.speaking || window.speechSynthesis.pending) {
      stopSpeech(true);
      return;
    }
    var largeTitle = currentAction ? actionLocaleText(currentAction, 'title') : t('welcomeTitle');
    var destination = currentAction && currentAction.stepType === 'ACTION' && currentDestinationCopy
      ? (currentLocale === 'zh' ? currentDestinationCopy.primaryZh : currentLocale === 'es' ? currentDestinationCopy.primaryEs : currentDestinationCopy.primaryEn)
      : '';
    var text = destination || largeTitle;
    var utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = currentLocale === 'zh' ? 'zh-CN' : currentLocale === 'es' ? 'es-ES' : 'en-US';
    var voicePrefix = currentLocale === 'zh' ? 'zh' : currentLocale === 'es' ? 'es' : 'en';
    var matchingVoice = window.speechSynthesis.getVoices().find(function (voice) {
      return String(voice.lang || '').toLowerCase().indexOf(voicePrefix) === 0;
    });
    if (matchingVoice) utterance.voice = matchingVoice;
    utterance.rate = 0.88;
    utterance.onend = function () { stopSpeech(false); };
    utterance.onerror = function () { stopSpeech(false); };
    speakingActive = true;
    var button = document.querySelector('[data-action="speak"]');
    if (button) {
      button.setAttribute('aria-pressed', 'true');
      button.setAttribute('aria-label', t('stopSpeaking'));
      var label = button.querySelector('span');
      if (label) label.textContent = t('stopSpeaking');
    }
    window.speechSynthesis.speak(utterance);
  }

  function showToast(message) {
    var toast = document.querySelector('[data-toast]');
    toast.textContent = message;
    toast.classList.add('is-visible');
    setTimeout(function () { toast.classList.remove('is-visible'); }, 2600);
  }

  function showStaff() {
    renderStaffCard();
    openDialog('[data-staff-overlay]', '[data-action="close-staff"]');
  }

  function closeStaff() {
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
    if (journeyContext.checkedIn || journeyContext.boardingPassDetected) steps.push('CHECK_IN');
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
    if (!currentAction) return null;
    if (currentAction.actionCode) return currentAction.actionCode;
    return currentAction.stepType === 'CHECK_IN_QUESTION' && checkInStatusAnswer === 'unsure'
      ? 'ASK_FOR_HELP' : null;
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
    if (!currentFlight || !currentAction || !shareActionCode) return null;
    return {
      flightNumber: normalizeFlightNumber(currentFlight.flightNumber),
      departureDate: currentFlight.departureDate || null,
      departureAirport: currentFlight.departureAirport || null,
      arrivalAirport: currentFlight.arrivalAirport || null,
      actionCode: shareActionCode,
      urgency: currentAction.urgency || 'NORMAL',
      destination: shareDestinationValue(shareActionCode),
      completedSteps: completedStepCodes(),
      terminal: currentFlight.terminal || null,
      gate: currentFlight.gate || null,
      checkInDeskOrZone: currentFlight.checkInDeskOrZone || currentFlight.checkInCounter || null,
      dedicatedBagDropDesk: currentFlight.dedicatedBagDropDesk || null,
      arrivalBaggageBelt: currentFlight.arrivalBaggageBelt || null,
      status: currentFlight.status || null,
      lastUpdated: currentFlight.dataLastUpdatedAt || currentFlight.lastUpdated || new Date().toISOString(),
      completed: currentAction.stepType === 'COMPLETE' || Boolean(journeyContext && journeyContext.exitedAirport)
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

  function shareViewUrl() {
    if (!shareSession || !shareSession.viewToken) return '';
    return new URL('/share/' + encodeURIComponent(shareSession.viewToken), window.location.origin).href;
  }

  function renderFamilyShareControls() {
    var active = Boolean(shareSession && shareSession.viewToken && shareSession.updateToken);
    var url = shareViewUrl();
    setHidden(document.querySelector('[data-share-link-box]'), !active);
    setHidden(document.querySelector('[data-action="preview-family-share"]'), !active);
    setHidden(document.querySelector('[data-action="share-family"]'), !active);
    setHidden(document.querySelector('[data-action="stop-family-share"]'), !active);
    var createButton = document.querySelector('[data-action="create-family-share"]');
    if (createButton) createButton.hidden = active;
    var input = document.querySelector('[data-family-share-url]');
    if (input) input.value = url;
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
    renderFamilyShareControls();
    setShareStatus(!currentFlight || !currentAction
      ? localeText('请先查询航班并进入乘机步骤。', 'Search for a flight and start the journey first.', 'Busca un vuelo e inicia el viaje primero.') : '');
    openDialog('[data-family-overlay]', '[data-action="close-family"]');
    if (currentFlight) prepareFamilyCard().catch(function () {});
    else setPngPreparing(false);
  }

  function closeFamily() {
    closeDialog('[data-family-overlay]');
  }

  function createFamilyShare() {
    if (shareCreationInFlight || shareSession && shareSession.viewToken) return;
    var payload = safeSharePayload();
    if (!payload) {
      setShareStatus(localeText('请先进入乘机步骤。', 'Start the journey first.', 'Inicia el viaje primero.'));
      return;
    }
    shareCreationInFlight = true;
    var createButton = document.querySelector('[data-action="create-family-share"]');
    if (createButton) createButton.disabled = true;
    setShareStatus(localeText('正在创建安全链接…', 'Creating a secure link…', 'Creando un enlace seguro…'));
    postJson('/api/share-sessions', payload).then(function (result) {
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
      setShareStatus(localeText('实时共享已开启。', 'Live sharing is active.', 'El enlace en directo está activo.'));
    }).catch(function () {
      setShareStatus(localeText('暂时无法创建链接，请检查网络后重试。', 'The link could not be created. Check your connection and try again.', 'No se pudo crear el enlace. Comprueba la conexión e inténtalo de nuevo.'));
    }).finally(function () {
      shareCreationInFlight = false;
      if (createButton && !shareSession) createButton.disabled = false;
    });
  }

  function shareFamily() {
    var url = shareViewUrl();
    if (!url) return createFamilyShare();
    var message = familyMessage() + '\n' + url;
    if (navigator.share) {
      navigator.share({ title: 'OneStep Flight', text: message, url: url }).catch(function (error) {
        if (error && error.name !== 'AbortError') copyText(url, localeText('链接已复制。', 'Link copied.', 'Enlace copiado.'));
      });
    } else {
      copyText(url, localeText('链接已复制。', 'Link copied.', 'Enlace copiado.'));
    }
  }

  function stopFamilyShare() {
    if (!shareSession) return;
    var session = shareSession;
    fetchJson('/api/share-sessions/' + encodeURIComponent(session.viewToken), {
      method: 'DELETE', headers: { 'X-Share-Update-Token': session.updateToken }
    }).then(function () {
      shareSession = null;
      localStorage.removeItem(SHARE_SESSION_KEY);
      localStorage.removeItem(SHARE_PENDING_KEY);
      renderFamilyShareControls();
      setShareStatus(localeText('共享已停止，旧链接不能再查看。', 'Sharing stopped. The old link can no longer be viewed.', 'Se dejó de compartir. El enlace anterior ya no se puede ver.'));
    }).catch(function () {
      setShareStatus(localeText('暂时无法停止共享，请重试。', 'Sharing could not be stopped. Try again.', 'No se pudo detener el enlace. Inténtalo de nuevo.'));
    });
  }

  function syncFamilyShare() {
    if (!shareSession || !shareSession.viewToken || !shareSession.updateToken) return;
    var payload = safeSharePayload();
    if (!payload) return;
    var fingerprint = JSON.stringify(payload);
    if (fingerprint === shareSession.lastFingerprint) return;
    if (!navigator.onLine) {
      localStorage.setItem(SHARE_PENDING_KEY, JSON.stringify({
        viewToken: shareSession.viewToken, updateToken: shareSession.updateToken, payload: payload
      }));
      return;
    }
    fetchJson('/api/share-sessions/' + encodeURIComponent(shareSession.viewToken), {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-Share-Update-Token': shareSession.updateToken },
      body: JSON.stringify(payload)
    }).then(function () {
      shareSession.lastFingerprint = fingerprint;
      localStorage.setItem(SHARE_SESSION_KEY, JSON.stringify(shareSession));
      localStorage.removeItem(SHARE_PENDING_KEY);
    }).catch(function () {
      localStorage.setItem(SHARE_PENDING_KEY, JSON.stringify({
        viewToken: shareSession.viewToken, updateToken: shareSession.updateToken, payload: payload
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
    var preview = document.querySelector('[data-png-preview]');
    if (preview) preview.hidden = true;
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
    context.fillStyle = '#F1F3F4';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#172B3B';
    context.fillRect(0, 0, canvas.width, 180);
    context.fillStyle = '#FFFFFF';
    context.font = '800 58px Arial, sans-serif';
    context.fillText('OneStep Flight', 80, 112);
    context.fillStyle = '#2F6FA9';
    context.fillRect(70, 235, 14, 930);
    context.fillStyle = '#172B3B';
    context.font = '800 62px Arial, sans-serif';
    context.fillText(formatFlightNumber(payload.flightNumber), 120, 330);
    context.font = '700 42px Arial, sans-serif';
    context.fillStyle = '#344B5D';
    context.fillText((payload.departureAirport || '—') + '  →  ' + (payload.arrivalAirport || '—'), 120, 405);
    context.font = '800 72px Arial, sans-serif';
    context.fillStyle = '#172B3B';
    var cardAction = currentAction ? actionTextForLocale(currentAction, 'title', currentLocale)
      : localeText('确认航班信息', 'Confirm flight information', 'Confirmar la información del vuelo');
    var y = wrapCanvasText(context, cardAction, 120, 545, 840, 88);
    context.font = '650 40px Arial, sans-serif';
    context.fillStyle = '#344B5D';
    y = wrapCanvasText(context, familyMessageForLocale(currentLocale).split('\n').slice(1).join(' · '), 120, y + 35, 840, 58);
    var safeFacts = [];
    if (payload.terminal) safeFacts.push(formatTerminalValue(payload.terminal));
    if (payload.checkInDeskOrZone) safeFacts.push(formatCheckInValue(payload.checkInDeskOrZone));
    if (payload.gate) safeFacts.push(formatGateValue(payload.gate));
    if (payload.arrivalBaggageBelt) safeFacts.push(formatBeltValue(payload.arrivalBaggageBelt));
    context.font = '700 42px Arial, sans-serif';
    context.fillStyle = '#2F6FA9';
    wrapCanvasText(context, safeFacts.join(' · '), 120, y + 45, 840, 58);
    context.fillStyle = '#687B89';
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
    var previewButton = document.querySelector('[data-action="preview-family-card"]');
    if (button) {
      button.disabled = preparing || !pngCardCache;
      button.textContent = preparing ? t('preparingPng') : t('sharePng');
    }
    if (previewButton) previewButton.disabled = preparing || !pngCardCache;
  }

  function reportPngError(error) {
    var code = error && error.code || 'PNG_RENDER_FAILED';
    console.error(code, error && error.cause ? error.cause.name || 'Error' : 'Error');
    var messages = {
      PNG_RENDER_FAILED: ['卡片绘制失败，请重试。', 'The card could not be drawn. Try again.', 'No se pudo dibujar la tarjeta. Inténtalo de nuevo.'],
      PNG_TO_BLOB_FAILED: ['卡片图片编码失败，请重试。', 'The card image could not be encoded. Try again.', 'No se pudo codificar la imagen. Inténtalo de nuevo.'],
      PNG_FILE_FAILED: ['无法准备分享文件，请重试。', 'The sharing file could not be prepared. Try again.', 'No se pudo preparar el archivo. Inténtalo de nuevo.'],
      PNG_SHARE_FAILED: ['系统分享失败，请重试或预览后保存。', 'System sharing failed. Try again or save from the preview.', 'No se pudo compartir. Inténtalo de nuevo o guarda desde la vista previa.']
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

  function showCachedPngPreview(showSaveHint) {
    if (!pngCardCache) return;
    var preview = document.querySelector('[data-png-preview]');
    var image = document.querySelector('[data-png-preview-image]');
    var hint = document.querySelector('[data-png-save-hint]');
    if (image) image.src = pngCardCache.url;
    if (hint) hint.textContent = showSaveHint
      ? localeText('请长按下方图片保存。', 'Touch and hold the image below to save it.', 'Mantén pulsada la imagen para guardarla.')
      : localeText('PNG 卡片预览', 'PNG card preview', 'Vista previa de la tarjeta PNG');
    if (preview) preview.hidden = false;
  }

  function isIosBrowser() {
    return /iPad|iPhone|iPod/.test(navigator.userAgent)
      || navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1;
  }

  function saveCachedPngFallback() {
    if (isIosBrowser()) {
      showCachedPngPreview(true);
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
    if (!pngCardCache) return;
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

  function previewFamilyCard() {
    if (pngCardCache) showCachedPngPreview(false);
  }

  function showEndConfirmation() {
    openDialog('[data-end-overlay]', '[data-action="cancel-end"]');
  }

  function closeEndConfirmation() {
    closeDialog('[data-end-overlay]');
  }

  function showBoardConfirmation() {
    openDialog('[data-board-overlay]', '[data-action="confirm-board"]');
  }

  function closeBoardConfirmation() {
    closeDialog('[data-board-overlay]');
  }

  function confirmBoarding() {
    closeBoardConfirmation();
    applyActionCompletion('BOARD_NOW');
  }

  function showLandedConfirmation() {
    openDialog('[data-landed-overlay]', '[data-action="confirm-landed"]');
  }

  function closeLandedConfirmation() {
    closeDialog('[data-landed-overlay]');
  }

  function confirmLanding() {
    closeLandedConfirmation();
    applyActionCompletion('IN_FLIGHT');
  }

  function clearJourney() {
    sessionStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(OFFLINE_STORAGE_KEY);
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
    lastRefreshAttemptAt = 0;
    refreshStatusState = null;
    restoredFromOffline = false;
    currentDestinationCopy = null;
    checkInStatusAnswer = null;
    documentSegments = [];
    selectedDocumentSegmentIndex = null;
    documentRootFields = {};
    documentRootReviewFields = [];
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
    if (!currentFlight) return;
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
        selectedDocumentSegmentIndex: selectedDocumentSegmentIndex,
        documentRootFields: documentRootFields,
        documentRootReviewFields: documentRootReviewFields,
        conflictsConfirmed: conflictsConfirmed,
        clockAnchor: clockAnchor,
        lastRefreshAttemptAt: lastRefreshAttemptAt,
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
      'flightNumber', 'departureDate', 'boardingPassDetected', 'departureAirport', 'departureAirportName',
      'arrivalAirport', 'arrivalAirportName', 'scheduledDeparture', 'estimatedDeparture',
      'actualDeparture', 'effectiveDeparture', 'scheduledArrival', 'estimatedArrival',
      'actualArrival', 'boardingTime', 'documentBoardingTime', 'terminal', 'gate',
      'checkInCounter', 'checkInDeskOrZone', 'dedicatedBagDropDesk', 'arrivalBaggageBelt',
      'status', 'delayMinutes', 'arrivalDelayMinutes', 'dataLastUpdatedAt', 'dataRetrievedAt',
      'retrievedAt', 'freshestSourceUpdatedAt', 'live', 'demo', 'demoScenario',
      'checkInCounterNeedsConfirmation', 'stale'
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

  function safeActionSnapshot(action) {
    return action ? pick(action, ['stepType', 'actionCode', 'titleZh', 'descriptionZh',
      'titleEn', 'descriptionEn', 'destination', 'urgency', 'reason',
      'needsOnSiteConfirmation', 'lastUpdated']) : null;
  }

  function normalizeJourneyContext(context, flight) {
    if (!context) return null;
    var normalized = Object.assign({}, context);
    ['boardingPassDetected', 'checkedIn', 'bagDropped', 'securityCleared', 'atGate',
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
      'documentType', 'boardingPassDetected', 'checkedIn', 'hasCheckedBaggage', 'bagDropped',
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
        clockAnchor: clockAnchor
      }));
    } catch (error) {
      // Storage can be unavailable or full; online guidance still works.
    }
  }

  function applySavedJourney(saved, fromOfflineStorage) {
    if (!saved || !saved.flight) return false;
    if (saved.savedAt && Date.now() - Number(saved.savedAt) > OFFLINE_MAX_AGE_MS) return false;
    currentFlight = saved.flight;
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
    selectedDocumentSegmentIndex = Number.isInteger(saved.selectedDocumentSegmentIndex)
      ? saved.selectedDocumentSegmentIndex : documentSegments.length === 1 ? 0 : null;
    documentRootFields = Object.assign({}, saved.documentRootFields || pendingDocument && pendingDocument.fields || {});
    documentRootReviewFields = Array.isArray(saved.documentRootReviewFields)
      ? saved.documentRootReviewFields.slice()
      : pendingDocument && Array.isArray(pendingDocument.needsReviewFields) ? pendingDocument.needsReviewFields.slice() : [];
    conflictsConfirmed = Boolean(saved.conflictsConfirmed);
    clockAnchor = saved.clockAnchor || null;
    lastRefreshAttemptAt = fromOfflineStorage ? 0 : Number(saved.lastRefreshAttemptAt) || 0;
    refreshStatusState = fromOfflineStorage && !navigator.onLine ? { code: 'OFFLINE_SNAPSHOT', details: {} }
      : saved.refreshStatusState || null;
    checkInStatusAnswer = saved.checkInStatusAnswer || (journeyContext && journeyContext.checkedIn ? 'yes' : null);
    restoredFromOffline = fromOfflineStorage;
    lastLookupContext = {
      flightNumber: normalizeFlightNumber(currentFlight.flightNumber),
      departureDate: journeyContext && journeyContext.departureDate || currentFlight.departureDate || null,
      departureAirport: currentFlight.departureAirport || null,
      arrivalAirport: currentFlight.arrivalAirport || null
    };
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
      if ((journeyContext || currentAction) && currentScreen !== 'welcome') showEndConfirmation(); else returnHome();
    }
    if (action === 'journey-back') goBack();
    if (action === 'manual') {
      document.querySelector('[data-manual-partial-notice]').hidden = true;
      if (currentFlight) {
        document.getElementById('flight-input').value = currentFlight.flightNumber || '';
        setDateInputValue(document.getElementById('date-input'), currentFlight.departureDate);
      }
      navigate('manual');
    }
    if (action === 'unusable-manual') {
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
      if (departureStopReason(currentFlight)) {
        renderDeparturePassedConfirmation(currentFlight);
        showScreen('confirm');
        announce(localeText('这趟航班已经离港，请不要开始普通乘机步骤。', 'This flight has departed. Do not start the normal airport steps.', 'Este vuelo ya ha salido. No inicies los pasos normales del aeropuerto.'));
        return;
      }
      if (pendingDocument && !currentFlight.timeContext) {
        resolveUploadedFlight();
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
    if (action === 'review-confirm') confirmDocumentField(false);
    if (action === 'review-edit') confirmDocumentField(true);
    if (action === 'select-segment') selectDocumentSegment(Number(actionElement.dataset.segmentIndex));
    if (action === 'checkin-status') answerCheckIn(actionElement.dataset.answer);
    if (action === 'checkin-preset-wrong') dismissBoardingPassPreset();
    if (action === 'baggage') answerBaggage(actionElement.dataset.answer);
    if (action === 'complete-action') completeCurrentAction();
    if (action === 'retry' && retryRequest) retryRequest();
    if (action === 'speak') speak();
    if (action === 'staff') showStaff();
    if (action === 'close-staff') closeStaff();
    if (action === 'copy-staff') copyText(staffCardText(), localeText('大字卡内容已复制。', 'Staff card copied.', 'Texto de la tarjeta copiado.'));
    if (action === 'family') showFamily();
    if (action === 'close-family') closeFamily();
    if (action === 'create-family-share') createFamilyShare();
    if (action === 'preview-family-share' && shareViewUrl()) window.open(shareViewUrl(), '_blank', 'noopener');
    if (action === 'share-family') shareFamily();
    if (action === 'share-family-card') shareFamilyCard();
    if (action === 'preview-family-card') previewFamilyCard();
    if (action === 'copy-family') copyText(familyMessage(), localeText('当前步骤已复制。', 'Current step copied.', 'Paso actual copiado.'));
    if (action === 'stop-family-share') stopFamilyShare();
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
    stopSpeech(false);
    currentLocale = normalizeLocale(event.target.value);
    storeLocale(currentLocale);
    updateLanguage();
  });

  document.querySelector('[data-action="manual-submit"]').addEventListener('submit', function (event) {
    event.preventDefault();
    var flightNumber = document.getElementById('flight-input').value;
    var departureDate = document.getElementById('date-input').value;
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
        || (!currentFlight.live && !currentFlight.demoScenario) || journeyContext && journeyContext.boarded) return;
    if (Date.now() - lastRefreshAttemptAt < REFRESH_COOLDOWN_MS) return;
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
    updateRefreshButtonCooldown();
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

  var pngShareButton = document.querySelector('[data-action="share-family-card"]');
  if (pngShareButton && !document.querySelector('[data-action="preview-family-card"]')) {
    var pngPreviewButton = document.createElement('button');
    pngPreviewButton.className = 'button button-secondary';
    pngPreviewButton.type = 'button';
    pngPreviewButton.dataset.action = 'preview-family-card';
    pngPreviewButton.dataset.i18n = 'previewPng';
    pngShareButton.parentNode.insertBefore(pngPreviewButton, pngShareButton);
  }
  updateLanguage();
  var restoredJourney = restoreJourney();
  updateLanguage();
  if (qaJourneyEnabled && (!restoredJourney || !currentFlight
      || normalizeFlightNumber(currentFlight.flightNumber) !== 'BA117'
      || currentFlight.departureDate !== '2026-08-24' || currentFlight.demo)) {
    startFlightLookup('BA117', '2026-08-24', false, null);
  }
}());
