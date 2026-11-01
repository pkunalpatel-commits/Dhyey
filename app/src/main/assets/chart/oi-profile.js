/* Helpers for OI levels and the OI reference strip. Strike levels and premium levels are kept in
   separate lists; this file never mixes the two coordinate systems. */
(function (root) {
  var KIND_STYLE = {
    ce:   { color: '#ef5350', style: 0, width: 1 },
    pe:   { color: '#26a69a', style: 0, width: 1 },
    poc:  { color: '#f5a623', style: 0, width: 2 },
    vah:  { color: '#7e57c2', style: 2, width: 1 },
    val:  { color: '#7e57c2', style: 2, width: 1 },
    add:  { color: '#ff9800', style: 2, width: 1 },
    hvn:  { color: '#29b6f6', style: 1, width: 1 },
    lvn:  { color: '#9e9e9e', style: 1, width: 1 },
    prev: { color: '#9e9e9e', style: 3, width: 1 }
  };
  function styleFor(kind) { return KIND_STYLE[kind] || KIND_STYLE.prev; }
  function n(v) { return (v === null || v === undefined) ? '—' : Number(v).toLocaleString('en-IN'); }
  function signed(v) { return (v > 0 ? '+' : '') + n(v); }
  function esc(s) { return String(s).replace(/[&<>"]/g, function (c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]; }); }

  function stripHtml(rows, stamp) {
    var h = '<table><tr><th>Level</th><th>Strike</th><th>OI</th><th>Chg</th><th>Chg %</th></tr>';
    (rows || []).forEach(function (r) {
      var cls = /CE/.test(r.label) ? 'ce' : (/PE/.test(r.label) ? 'pe' : '');
      h += '<tr><td class="' + cls + '">' + esc(r.label) + '</td><td>' + n(r.strike) + '</td><td>' + n(r.oi) +
        '</td><td>' + signed(r.change) + '</td><td>' + (r.pct === null || r.pct === undefined ? '—' : r.pct.toFixed(1) + '%') + '</td></tr>';
    });
    h += '</table><div class="stamp">Updated ' + esc(stamp || '—') + '</div>';
    return h;
  }
  root.OiProfile = { styleFor: styleFor, stripHtml: stripHtml, n: n };
})(typeof window !== 'undefined' ? window : globalThis);
