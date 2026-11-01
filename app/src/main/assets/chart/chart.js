/* Individual option chart: 3 synchronized Lightweight Charts panels (underlying, option premium, OI).
   Kotlin pushes full datasets with app.setData / app.setLevels and live ticks with app.tick / app.oiTick.
   Live ticks update only the last bar with series.update(); the chart is never rebuilt per tick. */
(function (root) {
  var SESSION_OPEN = 9 * 3600 + 15 * 60;   // seconds after IST midnight (times are already IST-shifted)
  var IST = 19800;
  var tf = 5, dark = true;
  var charts = {}, series = {};
  var data = { und: [], opt: [], vol: [], vwap: [], oi: [], oiChg: [] };
  var full = null;           // full dataset kept while replaying
  var replaying = false;
  var lastCumVol = null;
  var priceLines = { und: [], opt: [] };
  var syncing = false;

  function pal() {
    return dark
      ? { bg: '#131722', text: '#d1d4dc', grid: '#2a2e39', up: '#26a69a', down: '#ef5350' }
      : { bg: '#ffffff', text: '#131722', grid: '#e5e7eb', up: '#16a34a', down: '#dc2626' };
  }
  function baseOpts() {
    var p = pal();
    return {
      autoSize: true,
      layout: { background: { type: 'solid', color: p.bg }, textColor: p.text },
      grid: { vertLines: { color: p.grid }, horzLines: { color: p.grid } },
      rightPriceScale: { borderColor: p.grid },
      timeScale: { borderColor: p.grid, timeVisible: true, secondsVisible: false, rightOffset: 4 },
      crosshair: { mode: 0 }
    };
  }

  function bucket(ts, minutes) {
    var step = minutes * 60;
    var open = Math.floor(ts / 86400) * 86400 + SESSION_OPEN;
    return open + Math.floor((ts - open) / step) * step;
  }

  function volPoint(v) {
    var p = pal();
    return { time: v.time, value: v.value, color: v.up ? p.up + '99' : p.down + '99' };
  }
  function chgPoint(v) {
    var p = pal();
    return { time: v.time, value: v.value, color: v.value >= 0 ? p.up + 'cc' : p.down + 'cc' };
  }

  function init() {
    var p = pal();
    charts.und = LightweightCharts.createChart(document.getElementById('und'), baseOpts());
    charts.opt = LightweightCharts.createChart(document.getElementById('opt'), baseOpts());
    charts.oic = LightweightCharts.createChart(document.getElementById('oic'), baseOpts());

    series.und = charts.und.addCandlestickSeries({ upColor: p.up, downColor: p.down, borderVisible: false, wickUpColor: p.up, wickDownColor: p.down });
    series.opt = charts.opt.addCandlestickSeries({ upColor: p.up, downColor: p.down, borderVisible: false, wickUpColor: p.up, wickDownColor: p.down });
    series.vol = charts.opt.addHistogramSeries({ priceFormat: { type: 'volume' }, priceScaleId: 'vol', lastValueVisible: false, priceLineVisible: false });
    charts.opt.priceScale('vol').applyOptions({ scaleMargins: { top: 0.82, bottom: 0 } });
    series.vwap = charts.opt.addLineSeries({ color: '#f5a623', lineWidth: 1, priceLineVisible: false, lastValueVisible: false, title: 'VWAP' });
    series.oi = charts.oic.addHistogramSeries({ color: '#5c6bc0', priceFormat: { type: 'volume' }, priceLineVisible: false, title: 'OI' });
    series.oiChg = charts.oic.addHistogramSeries({ priceFormat: { type: 'volume' }, priceScaleId: 'chg', priceLineVisible: false, lastValueVisible: false, title: 'ΔOI' });
    charts.oic.priceScale('chg').applyOptions({ scaleMargins: { top: 0.65, bottom: 0 } });
    series.oi.priceScale().applyOptions({ scaleMargins: { top: 0.05, bottom: 0.4 } });

    ['und', 'opt', 'oic'].forEach(function (k) {
      charts[k].timeScale().subscribeVisibleTimeRangeChange(function (r) {
        if (syncing || !r) return;
        syncing = true;
        ['und', 'opt', 'oic'].forEach(function (o) { if (o !== k) { try { charts[o].timeScale().setVisibleRange(r); } catch (e) {} } });
        syncing = false;
      });
    });

    charts.opt.subscribeCrosshairMove(function (param) {
      if (!param || !param.time) return;
      var c = param.seriesData.get(series.opt);
      if (!c) return;
      var t = param.time;
      var v = find(data.vol, t), w = find(data.vwap, t), o = find(data.oi, t), d = find(data.oiChg, t);
      legend(c, v, w, o, d);
    });
  }

  function find(arr, t) {
    for (var i = arr.length - 1; i >= 0; i--) { if (arr[i].time === t) return arr[i]; if (arr[i].time < t) break; }
    return null;
  }

  var meta = {};
  function legend(c, v, w, o, d) {
    var n = OiProfile.n;
    var el = document.getElementById('legend');
    var head = '<b>' + (meta.title || '') + '</b> ';
    if (!c) { el.innerHTML = head; return; }
    el.innerHTML = head + 'O ' + c.open + ' H ' + c.high + ' L ' + c.low + ' C ' + c.close +
      (v ? ' · Vol ' + n(v.value) : '') + (w ? ' · VWAP ' + w.value.toFixed(2) : '') +
      (o ? ' · OI ' + n(o.value) : '') + (d ? ' · ΔOI ' + (d.value > 0 ? '+' : '') + n(d.value) : '') +
      (meta.greeks ? '<br>' + meta.greeks : '');
  }

  function apply(d) {
    series.und.setData(d.und);
    series.opt.setData(d.opt);
    series.vol.setData(d.vol.map(volPoint));
    series.vwap.setData(d.vwap);
    series.oi.setData(d.oi);
    series.oiChg.setData(d.oiChg.map(chgPoint));
  }

  var api = {
    setTheme: function (isDark) {
      dark = !!isDark;
      document.body.className = dark ? '' : 'light';
      var p = pal();
      ['und', 'opt', 'oic'].forEach(function (k) {
        charts[k].applyOptions({ layout: { background: { type: 'solid', color: p.bg }, textColor: p.text }, grid: { vertLines: { color: p.grid }, horzLines: { color: p.grid } } });
      });
    },

    setMeta: function (m) {
      meta = m || {};
      var last = data.opt.length ? data.opt[data.opt.length - 1] : null;
      legend(last, null, null, null, null);
    },

    setData: function (d, timeframe) {
      if (timeframe) tf = timeframe;
      data = { und: d.und || [], opt: d.opt || [], vol: d.vol || [], vwap: d.vwap || [], oi: d.oi || [], oiChg: d.oiChg || [] };
      full = null; replaying = false; lastCumVol = null;
      apply(data);
      syncing = true;
      ['und', 'opt', 'oic'].forEach(function (k) { charts[k].timeScale().fitContent(); });
      syncing = false;
      var last = data.opt.length ? data.opt[data.opt.length - 1] : null;
      legend(last, null, null, null, null);
    },

    /* levels: {underlying:[{label,value,kind}], premium:[...], strip:[...]}.
       Underlying levels are strike values and go ONLY to the underlying chart.
       Premium levels are option-premium values and go ONLY to the premium chart. */
    setLevels: function (lv, stamp) {
      ['und', 'opt'].forEach(function (k) {
        priceLines[k].forEach(function (l) { series[k].removePriceLine(l); });
        priceLines[k] = [];
      });
      (lv.underlying || []).forEach(function (l) { addLine('und', l); });
      (lv.premium || []).forEach(function (l) { addLine('opt', l); });
      document.getElementById('strip').innerHTML = OiProfile.stripHtml(lv.strip, stamp);
    },

    /* live tick: kind 'und' | 'opt'; cumVol is the day's cumulative volume (optional). */
    tick: function (kind, price, cumVol) {
      if (replaying || !(price > 0)) return;
      var ts = Math.floor(Date.now() / 1000) + IST;
      var b = bucket(ts, tf);
      var arr = kind === 'und' ? data.und : data.opt;
      var s = kind === 'und' ? series.und : series.opt;
      var last = arr.length ? arr[arr.length - 1] : null;
      if (last && b < last.time) return;
      if (last && last.time === b) {
        last.high = Math.max(last.high, price); last.low = Math.min(last.low, price); last.close = price;
      } else {
        last = { time: b, open: price, high: price, low: price, close: price };
        arr.push(last);
      }
      s.update(last);
      if (kind === 'opt') {
        if (cumVol !== undefined && cumVol !== null) {
          var delta = lastCumVol === null ? 0 : Math.max(0, cumVol - lastCumVol);
          lastCumVol = cumVol;
          var vb = data.vol.length ? data.vol[data.vol.length - 1] : null;
          if (!vb || vb.time !== b) { vb = { time: b, value: 0, up: true }; data.vol.push(vb); }
          vb.value += delta; vb.up = last.close >= last.open;
          series.vol.update(volPoint(vb));
        }
        var vw = vwapLast(b);
        if (vw !== null) {
          var wl = data.vwap.length ? data.vwap[data.vwap.length - 1] : null;
          if (!wl || wl.time !== b) { wl = { time: b, value: vw }; data.vwap.push(wl); } else { wl.value = vw; }
          series.vwap.update(wl);
        }
      }
    },

    /* live OI for the option: updates current bar's OI and OI-change histogram. */
    oiTick: function (oi) {
      if (replaying || !(oi >= 0)) return;
      var ts = Math.floor(Date.now() / 1000) + IST;
      var b = bucket(ts, tf);
      var cur = data.oi.length ? data.oi[data.oi.length - 1] : null;
      var prevOi = null;
      if (cur && cur.time === b) { prevOi = data.oi.length > 1 ? data.oi[data.oi.length - 2].value : cur.value; cur.value = oi; }
      else { prevOi = cur ? cur.value : oi; cur = { time: b, value: oi }; data.oi.push(cur); }
      series.oi.update(cur);
      var chg = { time: b, value: oi - prevOi };
      var cl = data.oiChg.length ? data.oiChg[data.oiChg.length - 1] : null;
      if (cl && cl.time === b) cl.value = chg.value; else data.oiChg.push(chg);
      series.oiChg.update(chgPoint(chg));
    },

    /* Historical replay: show only the first n option candles (and everything up to that time). */
    replay: function (n) {
      if (!full) full = JSON.parse(JSON.stringify(data));
      replaying = true;
      var total = full.opt.length;
      n = Math.max(1, Math.min(n, total));
      var T = full.opt[n - 1].time;
      function cut(a) { return a.filter(function (x) { return x.time <= T; }); }
      apply({ und: cut(full.und), opt: cut(full.opt), vol: cut(full.vol), vwap: cut(full.vwap), oi: cut(full.oi), oiChg: cut(full.oiChg) });
      var c = full.opt[n - 1];
      legend(c, null, null, null, null);
    },

    replayOff: function () {
      if (!full) { replaying = false; return; }
      data = full; full = null; replaying = false;
      apply(data);
    },

    _debug: function () { return { data: data, tf: tf, replaying: replaying }; }
  };

  function addLine(k, l) {
    var st = OiProfile.styleFor(l.kind);
    var pl = series[k].createPriceLine({ price: l.value, color: st.color, lineWidth: st.width, lineStyle: st.style, axisLabelVisible: true, title: l.label });
    priceLines[k].push(pl);
  }

  function vwapLast(b) {
    // session VWAP over bars of the same IST day up to b
    var day = Math.floor(b / 86400), pv = 0, v = 0;
    for (var i = 0; i < data.opt.length; i++) {
      var c = data.opt[i];
      if (Math.floor(c.time / 86400) !== day) continue;
      var vb = find(data.vol, c.time);
      var vol = vb ? vb.value : 0;
      pv += (c.high + c.low + c.close) / 3 * vol; v += vol;
    }
    return v > 0 ? pv / v : null;
  }

  root.app = api;
  root.addEventListener && root.addEventListener('load', function () {
    init();
    if (root.Android && root.Android.onReady) root.Android.onReady();
  });
})(typeof window !== 'undefined' ? window : globalThis);
