'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const locale = require('../../main/resources/static/locale.js');
const app = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/app.js'), 'utf8');

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

test('document review and field staff copy is complete in all supported locales', () => {
  assert.match(app, /fieldStaffHelp: '请工作人员帮忙'/);
  assert.match(app, /fieldStaffHelp: 'Ask staff for help'/);
  assert.match(app, /fieldStaffHelp: 'Pedir ayuda al personal'/);
  assert.match(app, /staffCardInstruction: '请把这个页面给工作人员看。需要时可联系机场。'/);
  assert.match(app, /staffCardInstruction: 'Please show this screen to a member of staff\. Contact the airport if needed\.'/);
  assert.match(app, /staffCardInstruction: 'Muestre esta pantalla a un miembro del personal\. Contacte con el aeropuerto si lo necesita\.'/);
  assert.match(app, /请帮我确认航班号/);
  assert.match(app, /Please help me confirm my flight number/);
  assert.match(app, /Ayúdeme a confirmar mi número de vuelo/);
  assert.match(app, /请帮我确认出发日期/);
  assert.match(app, /Please help me confirm my departure date/);
  assert.match(app, /Ayúdeme a confirmar mi fecha de salida/);
  assert.match(app, /titleEn\.hidden = currentLocale === 'en'/);
  assert.match(app, /bodyEn\.hidden = currentLocale === 'en'/);
});

test('passenger copy replaces internal lookup-boundary explanations', () => {
  assert.match(app, /部分航班信息缺失，请补全后继续。/);
  assert.match(app, /manualStatusMissing: 'Some flight information is missing\. Complete it to continue\.'/);
  assert.match(app, /manualStatusMissing: 'Falta parte de la información del vuelo\. Complétala para continuar\.'/);
  assert.match(app, /请核对识别到的信息。/);
  assert.match(app, /manualStatusReview: 'Check the information we found\.'/);
  assert.match(app, /manualStatusReview: 'Comprueba la información encontrada\.'/);
  assert.match(app, /识别到了不同的结果，请选择正确的信息。/);
  assert.match(app, /manualStatusConflict: 'We found different results\. Choose the correct information\.'/);
  assert.match(app, /manualStatusConflict: 'Encontramos resultados diferentes\. Elige la información correcta\.'/);
  assert.match(app, /manualStatusReady: 'The information is complete\. You can search the live flight\.'/);
  assert.match(app, /manualStatusReady: 'La información está completa\. Ya puedes buscar el vuelo en directo\.'/);
  assert.doesNotMatch(app, /保存不会查询真实航班|补全并保存不会查询真实航班/);
  assert.doesNotMatch(app, /Completing and saving do not search live flights|saving do not search live flights/);
  assert.doesNotMatch(app, /no consulta vuelos reales|guardar no consulta vuelos reales/);
});
