(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory;
  else root.OneStepLiveActivity = factory;
}(typeof window !== 'undefined' ? window : this, function (bridge) {
  'use strict';
  var queue = Promise.resolve();
  var state = { supported: false, enabled: false, active: false, journeyKey: null };
  function serialize(operation) {
    var result = queue.then(operation);
    queue = result.catch(function () {});
    return result;
  }
  function refresh() {
    return serialize(async function () { state = await bridge.status(); return state; });
  }
  return {
    status: refresh,
    current: function () { return state; },
    start: function (content) {
      return serialize(async function () {
        if (!content || content.expiresAt <= Date.now()) throw new Error('Expired journey');
        state = await bridge.start(content);
        return state;
      });
    },
    update: function (content) {
      return serialize(async function () {
        state = await bridge.status();
        if (!state.active) return state; // A dismissed activity is never recreated automatically.
        if (!content || content.journeyKey !== state.journeyKey || content.expiresAt <= Date.now()) {
          await bridge.end(); state = await bridge.status(); return state;
        }
        state = await bridge.update(content);
        return state;
      });
    },
    end: function () {
      return serialize(async function () { await bridge.end(); state = await bridge.status(); return state; });
    }
  };
}));
