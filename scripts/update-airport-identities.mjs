import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const SCRIPT_VERSION = '1';
const SOURCES = {
  airports: 'https://davidmegginson.github.io/ourairports-data/airports.csv',
  countries: 'https://davidmegginson.github.io/ourairports-data/countries.csv'
};
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const output = path.join(root, 'src', 'main', 'resources', 'airport-identities.json');

function parseCsv(text) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quoted) {
      if (character === '"' && text[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') quoted = false;
      else field += character;
    } else if (character === '"') quoted = true;
    else if (character === ',') {
      row.push(field);
      field = '';
    } else if (character === '\n') {
      row.push(field.replace(/\r$/, ''));
      if (row.some((value) => value !== '')) rows.push(row);
      row = [];
      field = '';
    } else field += character;
  }
  if (quoted) throw new Error('Unterminated quoted CSV field');
  if (field || row.length) {
    row.push(field.replace(/\r$/, ''));
    rows.push(row);
  }
  const [headers, ...values] = rows;
  if (!headers) return [];
  return values.map((items, rowIndex) => {
    if (items.length !== headers.length) {
      throw new Error(`CSV row ${rowIndex + 2} has ${items.length} columns; expected ${headers.length}`);
    }
    return Object.fromEntries(headers.map((header, index) => [header, items[index]]));
  });
}

function sha256(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

function number(value, field, code) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`${code}: invalid ${field} ${JSON.stringify(value)}`);
  return parsed;
}

function aliases(keywords) {
  return [...new Set(String(keywords || '').split(/[;,]/)
    .map((value) => value.trim()).filter((value) => value.length >= 2))];
}

async function download(name, url) {
  const response = await fetch(url, { redirect: 'follow', headers: { 'user-agent': 'OneStep-Flight-airport-identity-updater/1' } });
  if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
  const text = await response.text();
  return { url: response.url, text, sha256: sha256(text) };
}

const [airportsSource, countriesSource] = await Promise.all([
  download('airports.csv', SOURCES.airports),
  download('countries.csv', SOURCES.countries)
]);
const countries = new Map(parseCsv(countriesSource.text).map((country) => [country.code, country.name]));
const byIata = new Map();
for (const airport of parseCsv(airportsSource.text)) {
  const iataCode = airport.iata_code.trim().toUpperCase();
  if (!iataCode || airport.type === 'closed_airport') continue;
  const identity = {
    iataCode,
    icaoCode: airport.icao_code.trim() || null,
    name: airport.name.trim(),
    municipality: airport.municipality.trim() || null,
    isoCountry: airport.iso_country.trim().toUpperCase(),
    countryName: countries.get(airport.iso_country.trim().toUpperCase()) || null,
    latitude: number(airport.latitude_deg, 'latitude', iataCode),
    longitude: number(airport.longitude_deg, 'longitude', iataCode),
    type: airport.type,
    scheduledService: airport.scheduled_service === 'yes',
    aliases: aliases(airport.keywords)
  };
  const existing = byIata.get(iataCode);
  if (existing) {
    throw new Error(`Duplicate IATA ${iataCode}: ${existing.name} (${existing.icaoCode}) <> ${identity.name} (${identity.icaoCode})`);
  }
  byIata.set(iataCode, identity);
}

const airports = [...byIata.values()].sort((left, right) => left.iataCode.localeCompare(right.iataCode));
const fetchedAt = new Date().toISOString();
const combinedHash = sha256(`${airportsSource.sha256}\n${countriesSource.sha256}\n${SCRIPT_VERSION}`);
const document = {
  metadata: {
    datasetVersion: combinedHash.slice(0, 16),
    fetchedAt,
    generatorVersion: SCRIPT_VERSION,
    sources: {
      airports: { url: airportsSource.url, sha256: airportsSource.sha256 },
      countries: { url: countriesSource.url, sha256: countriesSource.sha256 }
    },
    recordCount: airports.length
  },
  airports
};
await mkdir(path.dirname(output), { recursive: true });
await writeFile(output, `${JSON.stringify(document, null, 2)}\n`, 'utf8');
process.stdout.write(`${JSON.stringify({ output, recordCount: airports.length, datasetVersion: document.metadata.datasetVersion })}\n`);
