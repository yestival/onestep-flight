(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.OneStepLocale = api;
}(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  var SUPPORTED_LOCALES = ['zh', 'en', 'es'];
  var STABLE_OPTION_ORDER = ['en', 'zh', 'es'];

  function isSupportedLocale(value) {
    return SUPPORTED_LOCALES.indexOf(value) >= 0;
  }

  function normalizeLocale(value) {
    var locale = String(value || '').trim().toLowerCase();
    return isSupportedLocale(locale) ? locale : 'en';
  }

  function localeFromLanguageTag(value) {
    var primaryLanguage = String(value || '').trim().toLowerCase().split(/[-_]/)[0];
    return isSupportedLocale(primaryLanguage) ? primaryLanguage : null;
  }

  function resolveLocalePreference(stored, languages, language) {
    var storedLocale = String(stored || '').trim().toLowerCase();
    if (isSupportedLocale(storedLocale)) return storedLocale;

    var preferences = Array.isArray(languages) ? languages : [];
    for (var index = 0; index < preferences.length; index += 1) {
      var matchedLocale = localeFromLanguageTag(preferences[index]);
      if (matchedLocale) return matchedLocale;
    }

    return localeFromLanguageTag(language) || 'en';
  }

  function orderLocaleOptions(select, locale) {
    if (!select) return;
    var current = normalizeLocale(locale);
    var order = [current].concat(STABLE_OPTION_ORDER.filter(function (value) { return value !== current; }));
    order.forEach(function (value) {
      var option = Array.prototype.find.call(select.options, function (candidate) { return candidate.value === value; });
      if (option) select.appendChild(option);
    });
    select.value = current;
  }

  return {
    normalizeLocale: normalizeLocale,
    localeFromLanguageTag: localeFromLanguageTag,
    resolveLocalePreference: resolveLocalePreference,
    orderLocaleOptions: orderLocaleOptions
  };
}));
