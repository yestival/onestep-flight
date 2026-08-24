'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const locale = require('../../main/resources/static/locale.js');

test('resolves supported browser language tags', () => {
  assert.equal(locale.resolveLocalePreference(null, ['zh-CN'], 'en-US'), 'zh');
  assert.equal(locale.resolveLocalePreference(null, ['en-GB'], 'zh-CN'), 'en');
  assert.equal(locale.resolveLocalePreference(null, ['es-ES'], 'en-US'), 'es');
});

test('falls back to English for unsupported or empty preferences', () => {
  assert.equal(locale.resolveLocalePreference(null, ['fr-FR'], 'fr-FR'), 'en');
  assert.equal(locale.resolveLocalePreference(null, [], ''), 'en');
  assert.equal(locale.resolveLocalePreference(null, undefined, undefined), 'en');
});

test('checks navigator.language when navigator.languages is missing', () => {
  assert.equal(locale.resolveLocalePreference(null, undefined, 'zh-CN'), 'zh');
  assert.equal(locale.resolveLocalePreference(null, undefined, 'es-ES'), 'es');
});

test('valid stored selection wins and invalid stored selection is ignored', () => {
  assert.equal(locale.resolveLocalePreference('es', ['zh-CN'], 'zh-CN'), 'es');
  assert.equal(locale.resolveLocalePreference('zh', ['en-GB'], 'en-GB'), 'zh');
  assert.equal(locale.resolveLocalePreference('fr', ['es-ES'], 'en-GB'), 'es');
  assert.equal(locale.resolveLocalePreference('', ['en-GB'], 'zh-CN'), 'en');
});

test('places the current locale first and keeps the remainder stable', () => {
  function selectFixture() {
    const select = {
      options: [
        { value: 'en' },
        { value: 'zh' },
        { value: 'es' }
      ],
      appendChild(option) {
        this.options.splice(this.options.indexOf(option), 1);
        this.options.push(option);
      },
      value: ''
    };
    return select;
  }

  [['zh', ['zh', 'en', 'es']], ['es', ['es', 'en', 'zh']], ['en', ['en', 'zh', 'es']]].forEach(([current, expected]) => {
    const select = selectFixture();
    locale.orderLocaleOptions(select, current);
    assert.deepEqual(select.options.map(option => option.value), expected);
    assert.equal(select.value, current);
  });
});
