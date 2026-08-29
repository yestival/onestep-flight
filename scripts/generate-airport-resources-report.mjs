import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptPath = fileURLToPath(import.meta.url);
const root = path.resolve(path.dirname(scriptPath), '..');
const defaultLiveRoot = path.join(root, 'output',
  'airport-resources-new-seven-20260829-live-run-01');
const defaultGoldenPath = path.join(root, 'src', 'test', 'resources', 'fixtures',
  'airport-resources-new-seven-golden.json');
const defaultReportPath = path.join(root, 'output',
  'airport-resources-new-seven-20260829-live-report.md');

function md(value) {
  return String(value == null || value === '' ? '—' : value)
    .replaceAll('|', '\\|').replaceAll('\n', '<br>');
}

function block(value, language = 'text') {
  return `\n\`\`\`${language}\n${String(value == null ? '' : value)
    .replaceAll('```', '` ` `')}\n\`\`\`\n`;
}

function normalizedPhone(value) {
  if (!value) return null;
  const stripped = String(value).replace(/^tel:/i, '').replace(/[^+\d]/g, '');
  return stripped.startsWith('+')
    ? `+${stripped.slice(1).replace(/\D/g, '')}`
    : stripped.replace(/\D/g, '');
}

function parsedUrl(value) {
  try { return value ? new URL(value) : null; } catch { return null; }
}

function normalizedUrl(value) {
  const url = parsedUrl(value);
  if (!url) return null;
  const host = url.hostname.toLowerCase().replace(/^www\./, '');
  const pathname = (url.pathname || '/').replace(/\/+$/, '') || '/';
  return `${host}${pathname}${url.search}`;
}

function urlAllowed(value, allowed = []) {
  const normalized = normalizedUrl(value);
  return Boolean(normalized && allowed.some((item) => normalizedUrl(item) === normalized));
}

function sameOfficialAuthority(value, allowed = []) {
  const actual = parsedUrl(value);
  if (!actual || actual.protocol !== 'https:') return false;
  const host = actual.hostname.toLowerCase().replace(/^www\./, '');
  return allowed.some((item) => {
    const expected = parsedUrl(item);
    if (!expected) return false;
    const expectedHost = expected.hostname.toLowerCase().replace(/^www\./, '');
    return host === expectedHost || host.endsWith(`.${expectedHost}`)
      || expectedHost.endsWith(`.${host}`);
  });
}

function exactGoogleAirportLocation(response) {
  return response?.map?.resultType === 'GOOGLE_MAPS_AIRPORT_LOCATION'
    && /^https:\/\/(?:www\.)?google\.[^/]+\/maps\/place\//i.test(response?.map?.url || '');
}

export function evaluateRecord(record, truth) {
  if (!record || record.completed !== true || !record.response) {
    return { verdict: '无法核实', differences: ['live 记录未完成或没有保存响应'], notes: [] };
  }
  const response = record.response;
  if (response.identityStatus !== 'VERIFIED') {
    return { verdict: '无法核实', differences: [
      `身份等级为 ${response.identityStatus || '未记录'}，无法确认电话或地图属于目标机场`
    ], notes: [] };
  }

  const differences = [];
  const notes = [];
  const acceptedPhones = truth.acceptablePhoneE164?.length
    ? truth.acceptablePhoneE164 : [truth.primaryPhoneE164];
  const actualPhone = normalizedPhone(response.contact?.telUri || response.contact?.phoneDisplay);
  if (!acceptedPhones.includes(actualPhone)) {
    differences.push(`电话 ${actualPhone || '未返回'}，独立核验可接受号码为 ${acceptedPhones.join(' / ')}`);
  }
  if (response.contact?.contactUrl
      && !urlAllowed(response.contact.contactUrl, truth.contactUrls)
      && !sameOfficialAuthority(response.contact.contactUrl, truth.contactUrls)) {
    differences.push(`contactUrl 不属于独立核验的官方联系来源：${response.contact.contactUrl}`);
  }

  const mapUrl = response.map?.url;
  const exactOfficialMap = urlAllowed(mapUrl, truth.mapUrls)
    || urlAllowed(response.map?.sourceUrl, truth.mapUrls);
  const authorityOfficialMap = sameOfficialAuthority(mapUrl, truth.mapUrls)
    || sameOfficialAuthority(response.map?.sourceUrl, truth.mapUrls);
  const googleFallback = exactGoogleAirportLocation(response);
  if (!(exactOfficialMap || authorityOfficialMap || googleFallback)) {
    differences.push(`地图不是独立核验的官方机场地图或精确 Google Maps 机场位置：${mapUrl || '未返回'}`);
  } else if (authorityOfficialMap && !exactOfficialMap) {
    notes.push(`返回同一官方运营方域的不同地图入口：${mapUrl}`);
  }
  if (differences.length) return { verdict: '错误', differences, notes };
  if (googleFallback) {
    return { verdict: '可接受的降级', differences: [
      '未选中官方机场内部地图，返回了已确认机场的精确 Google Maps 位置；不把它描述为官方航站楼地图'
    ], notes };
  }
  return { verdict: '准确', differences: [], notes };
}

function stageCandidates(stage) {
  const values = [...(stage.candidates || [])];
  const response = stage.sanitizedResponse || {};
  if (response.place_results) values.push({ jsonPath: '$.place_results', ...response.place_results,
    url: response.place_results.website || response.place_results.link });
  const local = Array.isArray(response.local_results)
    ? response.local_results : response.local_results?.places || [];
  local.forEach((item, index) => values.push({ jsonPath: `$.local_results[${index}]`, ...item,
    url: item.website || item.link }));
  const seen = new Set();
  return values.filter((item) => {
    const key = `${item.jsonPath || ''}|${item.url || ''}|${item.phone || ''}|${item.title || ''}`;
    return !seen.has(key) && seen.add(key);
  });
}

function renderObservedStages(record) {
  let text = '';
  for (const stage of [...(record.stages || [])].sort((a, b) => a.sequence - b.sequence)) {
    const error = stage.error || stage.responseParseError || stage.serpApiError;
    text += `\n#### ${stage.sequence}. ${md(stage.stage)}\n\n`;
    text += `- 实际查询词：${block(stage.query)}`;
    text += `- engine/type/hl/no_cache：${md(stage.engine)} / ${md(stage.type)} / ${md(stage.hl)} / ${md(stage.noCache)}\n`;
    text += `- HTTP / SerpApi 状态：${md(stage.httpStatus)} ${md(stage.httpStatusText)} / ${md(stage.serpApiStatus)}；耗时 ${md(stage.elapsedMs)} ms。\n`;
    text += `- 脱敏请求 URL：${md(stage.requestUrl)}\n`;
    text += `- 最终重定向 URL：${stage.finalRedirectUrl ? md(stage.finalRedirectUrl) : '未记录；本报告不推断。'}\n`;
    if (error) text += `- 准确错误文本：${block(error)}`;
    const candidates = stageCandidates(stage);
    if (!candidates.length) {
      text += '- 上游观测候选：无。候选采用/拒绝仅以下方生产 DecisionTrace 为准。\n';
      continue;
    }
    text += '\n| JSON path | 标题 | URL | 电话 | 类别/摘要 | 生产决定 |\n|---|---|---|---|---|---|\n';
    for (const item of candidates) {
      const category = [item.type, ...(Array.isArray(item.types) ? item.types : []), item.snippet]
        .filter(Boolean).join(' / ');
      text += `| ${md(item.jsonPath)} | ${md(item.title || item.name)} | ${md(item.url)} | ${md(item.phone || item.phone_number)} | ${md(category)} | 见下方真实 DecisionTrace；此表不推断 |\n`;
    }
  }
  return text;
}

function renderDecisionTrace(response) {
  const trace = response?.decisionTrace;
  if (!trace) return '\n未记录生产决策；报告不会依据最终 URL 或字符串位置猜测采用/拒绝。\n';
  let text = `\n- canonicalAirportId：${md(trace.canonicalAirportId)}\n- airportDatasetVersion：${md(trace.airportDatasetVersion)}\n- identityVerifierVersion：${md(trace.identityVerifierVersion)}\n- identityStatus：${md(trace.identityStatus)}\n- 时间：${md(trace.startedAt)} → ${md(trace.completedAt)}\n`;
  text += '\n| candidateId | stage / JSON path | 来源/解析模式 | 原始 URL / 电话 | 标准化 URL / 电话 | 主证据/受众/角色 | 决定 | 具体原因 | provenance / 绑定路径 |\n|---|---|---|---|---|---|---|---|---|\n';
  for (const item of trace.candidates || []) {
    const evidence = `${item.domainAuthority || ''} / ${item.pageAudience || ''} / ${item.resourceRole || ''}`;
    const provenance = `${(item.provenanceSignals || []).join(', ')} / ${item.structuralBindingPath || ''}`;
    text += `| ${md(item.candidateId)} | ${md(`${item.stage || ''} ${item.sourceJsonPath || ''}`)} | ${md(`${item.sourceType || ''} / ${item.extractionMode || ''}`)} | ${md(`${item.rawUrl || ''} / ${item.rawPhone || ''}`)} | ${md(`${item.normalizedUrl || ''} / ${item.normalizedPhone || ''}`)} | ${md(evidence)} | ${md(item.decision)} | ${md((item.candidateSpecificReasons || []).join(', '))} | ${md(provenance)} |\n`;
    if (item.context || item.referenceTitle || item.referenceSnippet) {
      text += `\n  - ${md(item.candidateId)} 上下文：${md(item.context)}；reference title：${md(item.referenceTitle)}；reference snippet：${md(item.referenceSnippet)}。\n`;
    }
  }
  text += '\n| HTTP stage | 状态 | Content-Type | provider request ID | 长度 | 安全正文指纹 | 错误 | 起止时间 |\n|---|---:|---|---|---:|---|---|---|\n';
  for (const item of trace.httpStages || []) {
    text += `| ${md(item.stage)} | ${md(item.httpStatus)} | ${md(item.contentType)} | ${md(item.providerRequestId)} | ${md(item.responseLength)} | ${md(item.safeBodyFingerprint)} | ${md(item.error)} | ${md(item.startedAt)} → ${md(item.completedAt)} |\n`;
  }
  text += '\n| AI stage | 响应形态 | JSON 尝试/失败 | strict JSON | references | snippet links | Markdown | 去重后 URL | 最终选中 | diagnostics |\n|---|---|---|---:|---:|---:|---:|---:|---:|---|\n';
  if (!(trace.aiStages || []).length) text += '| — | 未调用 | — | 0 | 0 | 0 | 0 | 0 | 0 | — |\n';
  for (const item of trace.aiStages || []) {
    text += `| ${md(item.stage)} | ${md(item.responseMode)} | ${md(`${item.jsonParseAttempted} / ${item.jsonParseFailure || '无'}`)} | ${md(item.strictJsonCandidates)} | ${md(item.referenceCandidates)} | ${md(item.snippetLinkCandidates)} | ${md(item.markdownCandidates)} | ${md(item.uniqueUrlCandidates)} | ${md(item.selectedCandidates)} | ${md((item.diagnostics || []).join(', '))} |\n`;
  }
  return text;
}

function buttonAudit(response) {
  const contact = response.contact || {};
  const map = response.map || {};
  const parts = [];
  if (contact.telUri) parts.push(`电话按钮 href=${contact.telUri}`);
  else if (contact.contactUrl) parts.push(`无电话；官方联系按钮 href=${contact.contactUrl}`);
  else parts.push('无电话且无通过门槛的联系页，不渲染虚假联系 href');
  if (map.url) parts.push(`地图按钮 href=${map.url}`);
  else parts.push('无通过门槛的地图，不渲染地图 href');
  return `${parts.join('；')}。`;
}

function aggregate(records, order) {
  const result = { officialMaps: 0, googleFallbacks: 0, specializedWrong: 0,
    thirdPartyWrong: 0, mapNotFound: 0, mapAiRequests: 0, naturalCandidates: 0,
    selectedAiMaps: 0, strictJsonSuccess: 0, strictJsonFailure: 0 };
  for (const code of order) {
    const response = records[code]?.response || {};
    const map = response.map || {};
    if (map.resultType === 'GOOGLE_MAPS_AIRPORT_LOCATION') result.googleFallbacks += 1;
    else if (map.status === 'NOT_FOUND' || !map.url) result.mapNotFound += 1;
    else if (/PARKING|TRANSPORT|NOISE|SHOPPING|COMMERCIAL|SITEMAP/.test(map.resultType || '')) result.specializedWrong += 1;
    else if (/THIRD_PARTY/.test(map.resultType || '')) result.thirdPartyWrong += 1;
    else result.officialMaps += 1;
    for (const ai of response.decisionTrace?.aiStages || []) {
      if (ai.stage === 'MAP_AI') result.mapAiRequests += 1;
      if (ai.responseMode === 'NATURAL_LANGUAGE') result.naturalCandidates += ai.uniqueUrlCandidates || 0;
      if (ai.stage === 'MAP_AI') result.selectedAiMaps += ai.selectedCandidates || 0;
      if (ai.jsonParseAttempted) {
        if (ai.jsonParsed === false || ai.jsonParseFailure) result.strictJsonFailure += 1;
        else result.strictJsonSuccess += 1;
      }
    }
  }
  return result;
}

export function generateReport(records, golden) {
  const order = Object.keys(golden.airports);
  const evaluations = Object.fromEntries(order.map((code) => [code,
    evaluateRecord(records[code], golden.airports[code])]));
  const totals = aggregate(records, order);
  const totalRequests = order.reduce((sum, code) =>
    sum + (records[code]?.response?.serpApiRequestCount || 0), 0);
  let report = '# 新七机场生产 live 字段级诊断（2026-08-29）\n\n';
  report += '## 方法与冻结边界\n\n';
  report += `- 固定顺序 ${order.join(' → ')}；每个机场只输入 IATA code，airportName=null、terminal=null、forceRefresh=true、refreshTarget=BOTH。\n`;
  report += '- 每机场只执行一次生产入口，响应立即保存；所有记录均来自同一冻结源码状态。\n';
  report += `- 官方真值在 live 前独立冻结，核验时间：${md(golden.verifiedAt)}；程序返回来源没有被当作唯一真值。\n`;
  report += '- 本报告仍按已保存的唯一一次 live 响应判定；live 后未修改生产规则，也未重复请求。\n';
  report += '- HTTP 只保存脱敏 URL、状态、请求 ID、长度、指纹与准确错误文本；报告不包含 API key 或 Authorization。\n\n';
  report += '## 七机场汇总\n\n';
  report += '| 机场 | 身份 | 电话来源 / 电话 | 地图类型 / URL | Map AI 形态/贡献 | 请求数 | 耗时 | 缓存 | 结论 | 具体偏差 |\n|---|---|---|---|---|---:|---:|---|---|---|\n';
  for (const code of order) {
    const record = records[code]; const response = record?.response || {}; const result = evaluations[code];
    const mapAi = (response.decisionTrace?.aiStages || []).filter((item) => item.stage === 'MAP_AI');
    const ai = mapAi.length ? mapAi.map((item) => `${item.responseMode}; unique=${item.uniqueUrlCandidates}; selected=${item.selectedCandidates}`).join(' / ') : '未调用';
    report += `| ${code} | ${md(response.identityStatus)} | ${md(`${response.contact?.sourceType || ''} / ${response.contact?.phoneDisplay || '无'}`)} | ${md(`${response.map?.resultType || 'NOT_FOUND'} / ${response.map?.url || '无'}`)} | ${md(ai)} | ${md(response.serpApiRequestCount)} | ${md(record?.elapsedMs)} ms | ${md(response.fromCache)} | **${result.verdict}** | ${md([...result.differences, ...result.notes].join('；'))} |\n`;
  }
  report += `\n**总 SerpApi 请求数：${totalRequests}。**\n\n`;
  report += '## 地图与 AI 独立统计\n\n';
  report += '| 指标 | 数量 |\n|---|---:|\n';
  report += `| 正确官方地图 | ${totals.officialMaps} |\n| 正确 Google Maps 机场位置降级 | ${totals.googleFallbacks} |\n| 错误专业地图 | ${totals.specializedWrong} |\n| 错误第三方地图 | ${totals.thirdPartyWrong} |\n| 地图未找到 | ${totals.mapNotFound} |\n| Map AI 请求数 | ${totals.mapAiRequests} |\n| AI 自然语言去重候选数 | ${totals.naturalCandidates} |\n| AI 最终选中地图数 | ${totals.selectedAiMaps} |\n| 严格 JSON 解析成功 | ${totals.strictJsonSuccess} |\n| 严格 JSON 解析失败 | ${totals.strictJsonFailure} |\n`;

  for (const code of order) {
    const record = records[code]; const response = record?.response || {}; const truth = golden.airports[code];
    const evaluation = evaluations[code];
    const rawRelative = `airport-resources-new-seven-20260829-live-run-01/${code}.json`;
    const traceRelative = `airport-resources-new-seven-20260829-live-run-01/decision-traces/${code}.json`;
    const acceptedPhones = truth.acceptablePhoneE164?.length
      ? truth.acceptablePhoneE164 : [truth.primaryPhoneE164];
    const actualPhone = normalizedPhone(response.contact?.telUri || response.contact?.phoneDisplay);
    report += `\n## ${code} — ${evaluation.verdict}\n\n`;
    report += '### 输入、状态与完整脱敏响应\n\n';
    report += `- 输入：airportCode=${code}；airportName=null；terminal=null；forceRefresh=true；refreshTarget=BOTH。\n`;
    report += `- runId=${md(record?.runId)}；completed=${md(record?.completed)}；耗时=${md(record?.elapsedMs)} ms；fromCache=${md(response.fromCache)}；请求数=${md(response.serpApiRequestCount)}；identity=${md(response.identityStatus)}。\n`;
    report += `- contact：status=${md(response.contact?.status)}；sourceType=${md(response.contact?.sourceType)}；language=${md(response.contact?.sourceLanguage)}；role=${md(response.contact?.phoneRole)}；tier=${md(response.contact?.evidenceTier)}；rawMapsPhone=${md(response.contact?.rawMapsPhonePresent)}；acceptedMapsPhone=${md(response.contact?.acceptedMapsPhonePresent)}；contactUrl=${md(response.contact?.contactUrl)}。\n`;
    report += `- map：status=${md(response.map?.status)}；type=${md(response.map?.resultType)}；language=${md(response.map?.sourceLanguage)}；scope=${md(response.map?.mapScope)}；verification=${md(response.map?.verificationReason)}；rejected categories=${md((response.map?.rejectedCandidateTypes || []).join(', '))}。\n`;
    report += `- public rejectionReasons：${md((response.rejectionReasons || []).join(', '))}。\n`;
    report += `- 完整脱敏 raw：[${code}.json](${rawRelative})；独立 DecisionTrace：[${code}.json](${traceRelative})。\n`;
    report += block(JSON.stringify(response, null, 2), 'json');
    report += '\n### 独立官方电话真值与标准化比较\n\n';
    report += `- 官方主号码：${truth.primaryPhoneE164}；可接受号码：${acceptedPhones.join(' / ')}；角色：${truth.phoneRole}。\n`;
    report += `- 程序显示/按钮：${md(response.contact?.phoneDisplay)} / ${md(response.contact?.telUri)}；标准化：${md(actualPhone)}。\n`;
    report += `- 比较：${acceptedPhones.includes(actualPhone) ? '号码标准化后属于独立核验集合。' : `不一致；${evaluation.differences.filter((item) => item.startsWith('电话')).join('；')}`}\n`;
    report += `- 页面与按钮：sourceTitle=${md(response.contact?.sourceTitle)}；sourceUrl=${md(response.contact?.sourceUrl)}；contactUrl=${md(response.contact?.contactUrl)}；${buttonAudit(response)}\n`;
    report += '\n### 地图范围、类别与偏差\n\n';
    report += `- 返回：title=${md(response.map?.sourceTitle)}；URL=${md(response.map?.url)}；sourceUrl=${md(response.map?.sourceUrl)}；type=${md(response.map?.resultType)}；scope=${md(response.map?.mapScope)}。\n`;
    report += `- 独立核验官方地图：${truth.mapUrls.length ? truth.mapUrls.map((url) => `[${url}](${url})`).join('；') : '未获得稳定官方地图 URL'}。\n`;
    report += '- 判定排除停车、公共交通、噪音、sitemap、购物专用、商业开发、普通指南、错误航站楼和无官方链接链的第三方页面。\n';
    if (evaluation.notes.length) report += `- URL 级偏差：${evaluation.notes.join('；')}。\n`;
    if (exactGoogleAirportLocation(response)) report += '- 这是精确 Google Maps 机场位置降级，不是官方内部/航站楼地图。\n';
    report += '\n### 独立官方真值证据\n\n';
    for (const source of truth.sources) {
      report += `- ${source.kind}：[${source.title}](${source.url}) — ${source.evidence}\n`;
    }
    report += `\n### 逐阶段实际查询、HTTP/SerpApi 状态与上游候选\n${renderObservedStages(record)}\n`;
    report += `\n### 生产 DecisionTrace：采用、拒绝、AI 解析与 provenance\n${renderDecisionTrace(response)}\n`;
    report += '\n### 结论\n\n';
    const details = [...evaluation.differences, ...evaluation.notes];
    report += `**${evaluation.verdict}。** ${details.length ? details.join('；') : '电话与官方地图均符合冻结真值及通用产品规则。'}\n`;
  }
  return { report, totalRequests, evaluations, totals };
}

function main() {
  const golden = JSON.parse(fs.readFileSync(defaultGoldenPath, 'utf8'));
  const order = Object.keys(golden.airports);
  const records = Object.fromEntries(order.map((code) => [code,
    JSON.parse(fs.readFileSync(path.join(defaultLiveRoot, `${code}.json`), 'utf8'))]));
  const { report, totalRequests, evaluations, totals } = generateReport(records, golden);
  fs.mkdirSync(path.dirname(defaultReportPath), { recursive: true });
  fs.writeFileSync(defaultReportPath, report, 'utf8');
  console.log(JSON.stringify({ reportPath: defaultReportPath, totalRequests, totals,
    verdicts: Object.fromEntries(order.map((code) => [code, evaluations[code].verdict])),
    bytes: Buffer.byteLength(report) }));
}

if (process.argv[1] && path.resolve(process.argv[1]) === scriptPath) main();
