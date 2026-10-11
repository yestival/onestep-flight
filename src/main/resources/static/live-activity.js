(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory;
  else root.OneStepLiveActivity = factory;
}(typeof window !== 'undefined' ? window : this, function (bridge) {
  'use strict';
  var queue = Promise.resolve(), state = { supported: false, enabled: false, active: false, journeyKey: null };
  function serialize(operation) { var result = queue.then(operation); queue = result.catch(function () {}); return result; }
  async function invoke(operation) {
    try { state = await operation(); return state; }
    catch (error) { state = Object.assign({}, state, {errorCode: 'LIVE_ACTIVITY_BRIDGE_FAILED'}); throw error; }
  }
  function status() { return serialize(function () { return invoke(function () { return bridge.status(); }); }); }
  function sync(content) {
    return serialize(async function () {
      if (!content) return state;
      await invoke(function () { return bridge.status(); });
      if (content.journeyEndsAt <= Math.floor(Date.now() / 1000)) return state;
      if (state.active && state.journeyKey !== content.journeyKey) await bridge.end();
      if (state.dismissedJourneyKey === content.journeyKey || !state.enabled) return state;
      await invoke(function () { return state.active && state.journeyKey === content.journeyKey ? bridge.update(content) : bridge.start(content); });
      return state;
    });
  }
  return { status: status, current: function () { return state; }, sync: sync, start: sync, update: sync,
    end: function () { return serialize(async function () { await bridge.end(); state = await bridge.status(); return state; }); } };
}));
