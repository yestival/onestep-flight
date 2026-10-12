(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory;
  else root.OneStepLiveActivity = factory;
}(typeof window !== 'undefined' ? window : this, function (bridge) {
  'use strict';
  var queue = Promise.resolve(), state = { supported: false, enabled: false, active: false, journeyKey: null };
  function serialize(operation) { var result = queue.then(operation); queue = result.catch(function () {}); return result; }
  async function invoke(operation, name) {
    var timer;
    try {
      state = await Promise.race([Promise.resolve().then(operation), new Promise(function (_, reject) {
        timer = setTimeout(function () {
          var error = new Error('Native activity call did not finish');
          error.code = 'LIVE_ACTIVITY_BRIDGE_TIMEOUT';
          reject(error);
        }, 10000);
      })]);
      return state;
    } catch (error) {
      reportFailure(error.code === 'LIVE_ACTIVITY_BRIDGE_TIMEOUT' ? error.code : 'LIVE_ACTIVITY_BRIDGE_FAILED', name);
      throw error;
    } finally { clearTimeout(timer); }
  }
  function reportFailure(code, operation) { state = Object.assign({}, state, { errorCode: code, errorReference: code + (operation ? ':' + operation : '') }); }
  function background(operation) {
    // Native clears are scoped to the previous journey. A push operation must
    // never hold the local ActivityKit queue, even if its bridge call hangs.
    try { Promise.resolve(operation()).catch(function () {}); } catch (_) {}
  }
  function status() { return serialize(function () { return invoke(function () { return bridge.status(); }, 'status'); }); }
  function sync(content, prepareJourney) {
    return serialize(async function () {
      if (!content) return state;
      if (prepareJourney) background(prepareJourney);
      await invoke(function () { return bridge.status(); }, 'status');
      if (content.journeyEndsAt <= Math.floor(Date.now() / 1000)) return state;
      if (state.active && state.journeyKey !== content.journeyKey) {
        var previousKey = state.journeyKey;
        await invoke(function () { return bridge.end({ journeyKey: previousKey }); }, 'end');
      }
      if (!state.localPersistenceSupported && (state.dismissedJourneyKey === content.journeyKey || !state.enabled)) return state;
      var updating = state.active && state.journeyKey === content.journeyKey;
      await invoke(function () { return updating ? bridge.update(content) : bridge.start(content); }, updating ? 'update' : 'start');
      return state;
    });
  }
  return { status: status, current: function () { return state; }, reportFailure: reportFailure, sync: sync, start: sync, update: sync,
    end: function (journeyKey, clearJourney) { return serialize(async function () {
      if (clearJourney) background(clearJourney);
      await invoke(function () { return bridge.end({ journeyKey: journeyKey || '' }); }, 'end');
      await invoke(function () { return bridge.status(); }, 'status');
      return state;
    }); } };
}));
