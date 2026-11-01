const fs = require('fs'), vm = require('vm'), assert = require('assert');
const dir = require('path').join(__dirname, '..', 'main', 'assets', 'chart') + '/';
function mkSeries(name) {
  const s = { name, setCalls: 0, updates: [], lines: [], data: [], removed: 0 };
  s.setData = d => { s.setCalls++; s.data = d; };
  s.update = p => s.updates.push(JSON.parse(JSON.stringify(p)));
  s.createPriceLine = o => { s.lines.push(o); return o; };
  s.removePriceLine = l => { s.removed++; s.lines = s.lines.filter(x => x !== l); };
  s.priceScale = () => ({ applyOptions() {} });
  return s;
}
const created = [];
const LW = { createChart(el, o) {
  const c = { el, series: [], opts: o, handlers: {} };
  const add = n => () => { const s = mkSeries(n); c.series.push(s); return s; };
  c.addCandlestickSeries = add('candle'); c.addHistogramSeries = add('hist'); c.addLineSeries = add('line');
  c.priceScale = () => ({ applyOptions() {} });
  c.timeScale = () => ({ subscribeVisibleTimeRangeChange(f) { c.handlers.range = f; }, setVisibleRange() {}, fitContent() {} });
  c.subscribeCrosshairMove = f => { c.handlers.cross = f; };
  c.applyOptions = () => {};
  created.push(c); return c;
} };
const els = {};
const document = { getElementById: id => els[id] || (els[id] = { id, innerHTML: '' }), body: { className: '' } };
let loadHandler;
const win = { LightweightCharts: LW, document, addEventListener: (e, f) => { if (e === 'load') loadHandler = f; }, Android: { onReady() { win.ready = true; } } };
win.window = win; win.globalThis = win;
const ctx = vm.createContext(Object.assign(win, { document, LightweightCharts: LW, console, Date, Math, JSON, Number, String, Object }));
vm.runInContext(fs.readFileSync(dir + 'oi-profile.js', 'utf8'), ctx);
vm.runInContext(fs.readFileSync(dir + 'chart.js', 'utf8'), ctx);
loadHandler();
assert(win.ready, 'Android.onReady called');
assert.strictEqual(created.length, 3);

const IST = 19800;
const open = (Math.floor(Date.now() / 1000 / 86400) * 86400) ;
function sessionOpenShifted(nowShifted) { return Math.floor(nowShifted / 86400) * 86400 + 9 * 3600 + 15 * 60; }
const nowS = Math.floor(Date.now() / 1000) + IST;
const so = sessionOpenShifted(nowS);
const step = 300;
const curBucket = so + Math.floor((nowS - so) / step) * step;
const mk = t => ({ time: t, open: 100, high: 101, low: 99, close: 100 });
const d = { und: [mk(curBucket - 300)], opt: [mk(curBucket - 300)], vol: [{ time: curBucket - 300, value: 10, up: true }], vwap: [{ time: curBucket - 300, value: 100 }], oi: [{ time: curBucket - 300, value: 1000 }], oiChg: [{ time: curBucket - 300, value: 0 }] };
win.app.setData(JSON.parse(JSON.stringify(d)), 5);
const optSeries = created[1].series[0], volSeries = created[1].series[1];
assert.strictEqual(optSeries.setCalls, 1);

// live ticks: new bar then in-bar updates, never a full reload
win.app.tick('opt', 110, 1000);
win.app.tick('opt', 120, 1010);
win.app.tick('opt', 105, 1015);
assert.strictEqual(optSeries.setCalls, 1, 'no full reload on ticks');
assert.strictEqual(optSeries.updates.length, 3);
const last = optSeries.updates[2];
assert.strictEqual(last.time, curBucket);
assert.strictEqual(last.open, 110); assert.strictEqual(last.high, 120); assert.strictEqual(last.low, 105); assert.strictEqual(last.close, 105);
// volume: first tick sets baseline (0 delta), then +10, +5
const lastVol = volSeries.updates[volSeries.updates.length - 1];
assert.strictEqual(lastVol.value, 15);
// OI tick
win.app.oiTick(1200);
const oiSeries = created[2].series[0], chgSeries = created[2].series[1];
assert.strictEqual(oiSeries.updates[0].value, 1200);
assert.strictEqual(chgSeries.updates[0].value, 200);
win.app.oiTick(1250);
assert.strictEqual(chgSeries.updates[1].value, 250);

// levels: underlying -> und chart only, premium -> opt chart only
win.app.setLevels({ underlying: [{ label: 'CE OI #1 25500', value: 25500, kind: 'ce' }, { label: 'OI POC 25400', value: 25400, kind: 'poc' }],
  premium: [{ label: 'Prem POC 125', value: 125, kind: 'poc' }], strip: [{ label: 'Highest CE OI', strike: 25500, oi: 100000, change: 5000, pct: 5.2 }] }, '10:15:00');
const undSeries = created[0].series[0];
assert.strictEqual(undSeries.lines.length, 2);
assert.strictEqual(optSeries.lines.length, 1);
assert(undSeries.lines.every(l => l.price > 10000), 'strike levels on underlying only');
assert(optSeries.lines.every(l => l.price < 1000), 'premium levels on premium chart only');
assert(els.strip.innerHTML.includes('Highest CE OI') && els.strip.innerHTML.includes('10:15:00'));
// re-setting levels removes old lines
win.app.setLevels({ underlying: [], premium: [], strip: [] }, 'x');
assert.strictEqual(undSeries.lines.length, 0); assert.strictEqual(undSeries.removed, 2);

// replay shows fewer candles and ignores ticks; replayOff restores
const many = { und: [], opt: [], vol: [], vwap: [], oi: [], oiChg: [] };
for (let i = 0; i < 10; i++) { const t = so + i * 300; many.und.push(mk(t)); many.opt.push(mk(t)); many.vol.push({ time: t, value: 5, up: true }); many.oi.push({ time: t, value: 100 + i }); many.oiChg.push({ time: t, value: 1 }); many.vwap.push({ time: t, value: 100 }); }
win.app.setData(many, 5);
win.app.replay(4);
assert.strictEqual(optSeries.data.length, 4);
const before = optSeries.updates.length;
win.app.tick('opt', 130, 5);
assert.strictEqual(optSeries.updates.length, before, 'ticks ignored while replaying');
win.app.replayOff();
assert.strictEqual(optSeries.data.length, 10);
console.log('JS logic tests passed');
