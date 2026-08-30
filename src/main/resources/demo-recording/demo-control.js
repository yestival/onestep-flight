(function () {
  'use strict';

  var UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  var CONTROL_PREFIX = 'onestep-flight-demo-control-v1:';
  var channel = null;
  var sessionId = null;
  var passengerState = null;
  var integrationStatus = null;
  var pending = Object.create(null);

  function newUuid() {
    if (crypto && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (token) {
      var value = Math.random() * 16 | 0;
      return (token === 'x' ? value : value & 3 | 8).toString(16);
    });
  }

  function passengerUrl() {
    return new URL('/?demoSession=' + encodeURIComponent(sessionId), location.origin).href;
  }

  function controlKey() { return CONTROL_PREFIX + sessionId; }

  function setResult(message, error) {
    var result = document.querySelector('[data-result]');
    result.textContent = message;
    result.style.color = error ? '#9c2929' : '#34445f';
  }

  function saveControlState() {
    localStorage.setItem(controlKey(), JSON.stringify({
      demoSessionId: sessionId,
      savedAt: Date.now(),
      passengerState: passengerState,
      integrationStatus: integrationStatus
    }));
  }

  function renderSession() {
    document.querySelector('[data-session-id]').textContent = sessionId;
    document.querySelector('[data-passenger-url]').textContent = passengerUrl();
    var connected = Boolean(passengerState && passengerState.connected);
    var connection = document.querySelector('[data-connection]');
    connection.textContent = connected ? '旅客页已连接' : '等待旅客页';
    connection.classList.toggle('online', connected);
    document.querySelector('[data-passenger-state]').textContent = connected ? '已连接' : '未连接';
    document.querySelector('[data-current-step]').textContent = passengerState && passengerState.actionCode || passengerState && passengerState.stepType || '—';
    document.querySelector('[data-current-gate]').textContent = passengerState && passengerState.gate || '—';
    document.querySelector('[data-share-state]').textContent = passengerState && passengerState.shareUrl ? '已生成' : '未生成';
    document.querySelector('[data-action="open-family"]').disabled = !(passengerState && passengerState.shareUrl);
    document.querySelector('[data-countdown]').textContent = passengerState && passengerState.countdownSeconds != null
      ? '旅客页倒计时：' + passengerState.countdownSeconds + ' 秒' : '计时由旅客页执行。';
  }

  function connect(nextId) {
    if (channel) channel.close();
    sessionId = nextId;
    passengerState = null;
    try {
      var stored = JSON.parse(localStorage.getItem(controlKey()) || 'null');
      integrationStatus = stored && stored.demoSessionId === sessionId ? stored.integrationStatus || null : null;
    } catch (error) { integrationStatus = null; }
    channel = new BroadcastChannel('onestep-flight-demo-' + sessionId);
    channel.addEventListener('message', function (event) {
      var message = event.data || {};
      if (message.demoSessionId !== sessionId) return;
      if (message.type === 'STATE') {
        passengerState = Object.assign({ connected: true }, message.state || {});
        renderSession();
        saveControlState();
      }
      if (message.type === 'RESULT') {
        var waiter = pending[message.requestId];
        if (waiter) {
          delete pending[message.requestId];
          clearTimeout(waiter.timer);
          (message.ok ? waiter.resolve : waiter.reject)(message);
        }
        setResult(message.ok ? message.message || '旅客页已执行。' : message.message || '旅客页执行失败。', !message.ok);
      }
    });
    history.replaceState(null, '', '/demo-control?demoSession=' + encodeURIComponent(sessionId));
    renderSession();
    renderIntegrationStatus(integrationStatus);
    saveControlState();
    channel.postMessage({ type: 'REQUEST_STATE', demoSessionId: sessionId });
  }

  function send(command, payload) {
    var requestId = newUuid();
    setResult('已发送命令：' + command);
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () {
        delete pending[requestId];
        reject(new Error('旅客页未响应，请先打开旅客页。'));
      }, 8000);
      pending[requestId] = { resolve: resolve, reject: reject, timer: timer };
      channel.postMessage({ type: 'COMMAND', command: command, payload: payload || {},
        requestId: requestId, demoSessionId: sessionId });
    }).catch(function (error) {
      setResult(error.message || '命令执行失败。', true);
    });
  }

  function text(value) { return value == null || value === '' ? '—' : String(value); }
  function time(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'; }

  function renderIntegrationStatus(status) {
    status = status || {};
    var nutrient = status.nutrient;
    var serp = status.serpApi;
    document.querySelector('[data-nutrient-success]').textContent = nutrient ? (nutrient.success ? '是' : '否') : '—';
    document.querySelector('[data-nutrient-segments]').textContent = nutrient ? text(nutrient.segmentCount) : '—';
    document.querySelector('[data-nutrient-time]').textContent = nutrient ? time(nutrient.calledAt) : '—';
    document.querySelector('[data-serp-outcome]').textContent = serp ? text(serp.outcome) : '—';
    document.querySelector('[data-serp-live]').textContent = serp ? text(serp.live) : '—';
    document.querySelector('[data-serp-cache]').textContent = serp ? text(serp.fromCache) : '—';
    document.querySelector('[data-serp-time]').textContent = serp ? time(serp.calledAt) : '—';
    document.querySelector('[data-serp-terminal]').textContent = serp ? text(serp.terminal) : '—';
    document.querySelector('[data-serp-gate]').textContent = serp ? text(serp.gate) : '—';
    document.querySelector('[data-serp-status]').textContent = serp ? text(serp.status) : '—';
  }

  function refreshIntegrationStatus() {
    return fetch('/api/demo-recording/integration-status', { cache: 'no-store' }).then(function (response) {
      if (!response.ok) throw new Error('无法读取真实调用状态。');
      return response.json();
    }).then(function (status) {
      integrationStatus = status;
      renderIntegrationStatus(status);
      saveControlState();
    }).catch(function (error) { setResult(error.message, true); });
  }

  document.addEventListener('click', function (event) {
    var restore = event.target.closest('[data-restore]');
    if (restore) send('RESTORE_STEP', { target: restore.dataset.restore });
    var button = event.target.closest('[data-action]');
    if (!button) return;
    var action = button.dataset.action;
    if (action === 'new-session') connect(newUuid());
    if (action === 'reset-session') send('RESET_SESSION');
    if (action === 'open-passenger') window.open(passengerUrl(), '_blank', 'noopener');
    if (action === 'open-family' && passengerState && passengerState.shareUrl) window.open(passengerState.shareUrl, '_blank', 'noopener');
    if (action === 'clear-session') send('CLEAR_SESSION');
    if (action === 'prepare-gate') send('PREPARE_GATE_CHANGE');
    if (action === 'schedule-gate') send('SCHEDULE_GATE_CHANGE', { delayMs: 5000 });
    if (action === 'trigger-gate') send('TRIGGER_GATE_CHANGE');
    if (action === 'restore-gate') send('RESTORE_GATE_CHANGE');
    if (action === 'refresh-status') refreshIntegrationStatus();
  });

  document.querySelector('[data-copy]').addEventListener('click', function () {
    navigator.clipboard.writeText(sessionId).then(function () { setResult('会话 ID 已复制。'); });
  });

  var requested = new URLSearchParams(location.search).get('demoSession');
  connect(UUID_PATTERN.test(requested || '') ? requested.toLowerCase() : newUuid());
  refreshIntegrationStatus();
}());
