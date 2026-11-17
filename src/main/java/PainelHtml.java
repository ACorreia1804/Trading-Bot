class PainelHtml {
    static final String HTML = """
<!doctype html>
<html lang="pt">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Trading Bot</title>
<style>
  :root {
    --bg: #131722; --panel: #1e222d; --panel2: #262b38; --line: #2a2e39;
    --fg: #d1d4dc; --muted: #787b86; --up: #26a69a; --down: #ef5350; --wait: #f0b429; --accent: #2962ff;
    color-scheme: dark;
  }
  * { box-sizing: border-box; }
  html, body { margin: 0; background: var(--bg); color: var(--fg);
    font: 14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
  body { min-height: 100vh; }
  header { display: flex; align-items: center; gap: 18px; flex-wrap: wrap; padding: 10px 16px; border-bottom: 1px solid var(--line); background: var(--panel); }
  header h1 { font-size: 16px; margin: 0; font-weight: 600; }
  header .sp { flex: 1; }
  .stat { display: flex; flex-direction: column; font-size: 12px; color: var(--muted); }
  .stat b { color: var(--fg); font-size: 14px; font-weight: 600; font-variant-numeric: tabular-nums; }
  .up { color: var(--up) !important; } .down { color: var(--down) !important; }
  .btn { color: #fff; background: var(--accent); text-decoration: none; font-size: 13px; padding: 7px 12px; border-radius: 6px; }
  .layout { display: grid; grid-template-columns: 270px minmax(0, 1fr); min-height: calc(100vh - 56px); }
  @media (max-width: 900px) { .layout { grid-template-columns: minmax(0, 1fr); } }
  aside { border-right: 1px solid var(--line); background: var(--panel); }
  @media (max-width: 900px) { aside { border-right: 0; border-bottom: 1px solid var(--line); } }
  .lh { padding: 10px 14px; font-size: 11px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); }
  .item { display: grid; grid-template-columns: 1fr auto; gap: 2px 8px; padding: 11px 14px; cursor: pointer; border-top: 1px solid var(--line); border-left: 3px solid transparent; }
  .item:hover { background: var(--panel2); }
  .item.sel { background: var(--panel2); border-left-color: var(--accent); }
  .item .sym { font-weight: 600; }
  .item .nm { grid-column: 1; color: var(--muted); font-size: 12px; }
  .item .px { text-align: right; font-variant-numeric: tabular-nums; }
  .item .ch { text-align: right; font-size: 12px; font-variant-numeric: tabular-nums; }
  .tag { display: inline-block; font-size: 11px; font-weight: 600; padding: 1px 7px; border-radius: 4px; border: 1px solid currentColor; }
  .tag.COMPRAR { color: var(--up); } .tag.VENDER { color: var(--down); }
  .tag.MANTER { color: #5aa2ff; } .tag.ESPERAR, .tag.SEMDADOS, .tag.SEMESTRATEGIA { color: var(--muted); }
  main { padding: 14px; display: flex; flex-direction: column; gap: 14px; min-width: 0; }
  .card { background: var(--panel); border: 1px solid var(--line); border-radius: 8px; min-width: 0; }
  .ctitle { display: flex; align-items: baseline; gap: 12px; flex-wrap: wrap; padding: 12px 14px 0; }
  .ctitle h2 { margin: 0; font-size: 18px; }
  .ctitle .big { font-size: 18px; font-weight: 600; font-variant-numeric: tabular-nums; }
  .ctitle .sub { color: var(--muted); font-size: 12px; }
  #chart { height: 440px; margin: 8px 0 0; }
  .legend { display: flex; gap: 14px; flex-wrap: wrap; padding: 6px 14px 12px; font-size: 12px; color: var(--muted); }
  .legend i { display: inline-block; width: 10px; height: 3px; margin-right: 5px; vertical-align: middle; }
  .two { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 14px; }
  @media (max-width: 1100px) { .two { grid-template-columns: minmax(0, 1fr); } }
  .pad { padding: 14px; }
  .adv-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
  .adv-head .tag { font-size: 16px; padding: 4px 12px; }
  .adv-text { margin: 10px 0 0; }
  .kv { display: grid; grid-template-columns: auto 1fr; gap: 4px 14px; margin-top: 12px; font-size: 13px; }
  .kv span:nth-child(odd) { color: var(--muted); } .kv span:nth-child(even) { text-align: right; font-variant-numeric: tabular-nums; }
  .note { color: var(--muted); font-size: 12px; margin-top: 10px; }
  h3 { margin: 0 0 10px; font-size: 12px; letter-spacing: .08em; text-transform: uppercase; color: var(--muted); font-weight: 600; }
  .tw { overflow-x: auto; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th { text-align: left; color: var(--muted); font-weight: 500; font-size: 12px; padding: 6px 8px; border-bottom: 1px solid var(--line); }
  td { padding: 7px 8px; border-bottom: 1px solid var(--line); font-variant-numeric: tabular-nums; }
  #ordens td { white-space: nowrap; }
  td.r, th.r { text-align: right; }
  .err { color: var(--down); padding: 14px; }
  .foot { color: var(--muted); font-size: 12px; padding: 4px 14px 18px; }
</style>
</head>
<body>
<header>
  <h1>Trading Bot</h1>
  <span class="tag MANTER" id="modo">PAPER</span>
  <div class="sp"></div>
  <div class="stat"><span>Conta (simula\u00e7\u00e3o)</span><b id="equity">n/d</b></div>
  <div class="stat"><span>Hoje</span><b id="dia">n/d</b></div>
  <div class="stat"><span>Bolsa dos EUA</span><b id="bolsa">n/d</b></div>
  <div class="stat"><span>Atualizado</span><b id="upd">n/d</b></div>
  <a class="btn" href="https://claude.ai/artifact/4eZ3i8UQsiYx7KaDGarVfv" target="_blank" rel="noopener">Analisar um gr\u00e1fico</a>
</header>

<div class="layout">
  <aside>
    <div class="lh">Mercados</div>
    <div id="lista"></div>
  </aside>
  <main>
    <section class="card">
      <div class="ctitle">
        <h2 id="titulo">...</h2>
        <span class="big" id="preco"></span>
        <span class="sub" id="sub"></span>
      </div>
      <div id="chart"></div>
      <div class="legend" id="legenda"></div>
    </section>

    <section class="two">
      <div class="card pad">
        <h3>Conselho do bot</h3>
        <div class="adv-head"><span class="tag ESPERAR" id="acao">...</span><span id="estrat" class="sub" style="color:var(--muted)"></span></div>
        <p class="adv-text" id="texto"></p>
        <div class="kv" id="posicao"></div>
        <p class="note">\u00c9 o que a estrat\u00e9gia do bot faria com a \u00faltima vela fechada, escolhida num backtest. N\u00e3o \u00e9 uma previs\u00e3o nem aconselhamento financeiro.</p>
      </div>
      <div class="card pad">
        <h3>Resultados por mercado</h3>
        <div class="tw"><table>
          <thead><tr><th>Mercado</th><th>Estrat\u00e9gia</th><th class="r">Trades fechados</th><th class="r">Resultado (USD)</th></tr></thead>
          <tbody id="res"></tbody>
        </table></div>
      </div>
    </section>

    <section class="card pad">
      <h3>\u00daltimas ordens</h3>
      <div class="tw"><table>
        <thead><tr><th>Data</th><th>Mercado</th><th>Lado</th><th class="r">Quantidade</th><th class="r">Pre\u00e7o</th><th class="r">Valor (USD)</th><th>Ambiente</th></tr></thead>
        <tbody id="ordens"></tbody>
      </table></div>
      <p class="note" id="semordens" hidden>Ainda n\u00e3o h\u00e1 ordens. O bot s\u00f3 compra quando a estrat\u00e9gia d\u00e1 sinal.</p>
    </section>
    <div class="foot">Projeto educativo com dinheiro de simula\u00e7\u00e3o (Alpaca paper). S\u00f3 leitura: este painel n\u00e3o envia ordens.</div>
  </main>
</div>

<script src="https://cdn.jsdelivr.net/npm/lightweight-charts@4.1.3/dist/lightweight-charts.standalone.production.js"></script>
<script>
(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };
  var nf = new Intl.NumberFormat("pt-PT", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  var nq = new Intl.NumberFormat("pt-PT", { maximumSignificantDigits: 6 });
  var estado = null, sel = null, chart = null, candles = null, vol = null, linhas = [], lastSel = null;
  var CORES = ["#f0b429", "#5aa2ff", "#c792ea"];

  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function money(n) { return typeof n === "number" && isFinite(n) ? nf.format(n) : "n/d"; }
  function pct(n) { return typeof n === "number" && isFinite(n) ? (n >= 0 ? "+" : "") + nf.format(n * 100) + "%" : ""; }
  function tagCls(a) { return (a || "").replace(/ /g, "").replace("\u00c3", "A").replace("\u00c7", "C").replace("\u00c9", "E"); }
  function data(sec) { var d = new Date(sec * 1000); return d.toLocaleString("pt-PT", { day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit" }); }

  function setTag(e, acao) { e.textContent = acao; e.className = "tag " + tagCls(acao); }

  /* ---------- gr\u00e1fico ---------- */
  function iniciarGrafico() {
    if (chart) return;
    if (typeof LightweightCharts === "undefined") { $("chart").appendChild(el("div", "err", "N\u00e3o foi poss\u00edvel carregar a biblioteca de gr\u00e1ficos (precisa de internet).")); return; }
    chart = LightweightCharts.createChart($("chart"), {
      autoSize: true,
      layout: { background: { color: "#1e222d" }, textColor: "#d1d4dc" },
      grid: { vertLines: { color: "#262b38" }, horzLines: { color: "#262b38" } },
      rightPriceScale: { borderColor: "#2a2e39" },
      timeScale: { borderColor: "#2a2e39", timeVisible: true, secondsVisible: false },
      crosshair: { mode: 0 }
    });
    candles = chart.addCandlestickSeries({ upColor: "#26a69a", downColor: "#ef5350", borderVisible: false, wickUpColor: "#26a69a", wickDownColor: "#ef5350" });
    vol = chart.addHistogramSeries({ priceFormat: { type: "volume" }, priceScaleId: "vol" });
    chart.priceScale("vol").applyOptions({ scaleMargins: { top: 0.86, bottom: 0 } });
  }

  function snap(tempos, t) {
    // vela onde a ordem caiu: a \u00faltima que abriu antes (ou \u00e0 hora) da ordem
    var lo = 0, hi = tempos.length - 1, r = -1;
    while (lo <= hi) { var mid = (lo + hi) >> 1; if (tempos[mid] <= t) { r = mid; lo = mid + 1; } else hi = mid - 1; }
    return r < 0 ? null : tempos[r];
  }

  function desenhar(d) {
    iniciarGrafico();
    if (!chart) return;
    var tempos = d.velas.map(function (v) { return v[0]; });
    candles.setData(d.velas.map(function (v) { return { time: v[0], open: v[1], high: v[2], low: v[3], close: v[4] }; }));
    vol.setData(d.velas.map(function (v) { return { time: v[0], value: v[5], color: v[4] >= v[1] ? "rgba(38,166,154,.35)" : "rgba(239,83,80,.35)" }; }));
    linhas.forEach(function (l) { chart.removeSeries(l); });
    linhas = [];
    $("legenda").replaceChildren();
    d.medias.forEach(function (m, i) {
      var s = chart.addLineSeries({ color: CORES[i % CORES.length], lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
      s.setData(m.dados.map(function (p) { return { time: p[0], value: p[1] }; }));
      linhas.push(s);
      var li = el("span"); var sw = el("i"); sw.style.background = CORES[i % CORES.length]; li.appendChild(sw); li.appendChild(document.createTextNode(m.nome));
      $("legenda").appendChild(li);
    });
    var marc = [];
    d.ordens.forEach(function (o) {
      var t = snap(tempos, o.tempo);
      if (t === null) return;
      var compra = o.lado === "BUY";
      marc.push({ time: t, position: compra ? "belowBar" : "aboveBar", color: compra ? "#26a69a" : "#ef5350", shape: compra ? "arrowUp" : "arrowDown", text: (compra ? "Compra " : "Venda ") + money(o.preco) });
    });
    marc.sort(function (a, b) { return a.time - b.time; });
    candles.setMarkers(marc);
    if (lastSel !== d.simbolo) { chart.timeScale().fitContent(); lastSel = d.simbolo; }
    if (marc.length) { var l = el("span"); l.textContent = "Setas: compras e vendas do bot"; $("legenda").appendChild(l); }
  }

  /* ---------- listas e cart\u00f5es ---------- */
  function mercadoSel() { return estado && estado.mercados.filter(function (m) { return m.simbolo === sel; })[0]; }

  function desenharLista() {
    var box = $("lista"); box.replaceChildren();
    estado.mercados.forEach(function (m) {
      var it = el("div", "item" + (m.simbolo === sel ? " sel" : ""));
      it.tabIndex = 0;
      var a = el("div", "sym", m.simbolo);
      var px = el("div", "px", m.preco != null ? money(m.preco) : "n/d");
      var nm = el("div", "nm", m.nome);
      var ch = el("div", "ch"); ch.textContent = pct(m.variacao); if (m.variacao > 0) ch.classList.add("up"); if (m.variacao < 0) ch.classList.add("down");
      var tg = el("div"); var t = el("span", "tag"); setTag(t, m.acao); tg.appendChild(t);
      var pos = el("div", "ch", m.emPosicao ? "em posi\u00e7\u00e3o" : "fora");
      pos.style.color = "#787b86";
      it.append(a, px, nm, ch, tg, pos);
      var go = function () { sel = m.simbolo; render(); carregarVelas(); };
      it.addEventListener("click", go);
      it.addEventListener("keydown", function (e) { if (e.key === "Enter") go(); });
      box.appendChild(it);
    });
  }

  function desenharConselho() {
    var m = mercadoSel(); if (!m) return;
    $("titulo").textContent = m.simbolo + " \u00b7 " + m.nome;
    $("preco").textContent = m.preco != null ? money(m.preco) : "";
    $("sub").textContent = (m.aoVivo ? "pre\u00e7o ao vivo" : "\u00faltimo fecho") + (m.tf ? " \u00b7 velas de " + m.tf : "") + (m.tempo ? " \u00b7 \u00faltima vela " + data(m.tempo) : "");
    setTag($("acao"), m.acao);
    $("estrat").textContent = m.estrategia || "";
    $("texto").textContent = m.texto || "";
    var kv = $("posicao"); kv.replaceChildren();
    var add = function (k, v, cls) { var a = el("span", null, k); var b = el("span", cls || "", v); kv.append(a, b); };
    if (m.posicao) {
      add("Posi\u00e7\u00e3o aberta", nq.format(m.posicao.qtd) + " unidades");
      add("Pre\u00e7o de entrada", money(m.posicao.entrada));
      add("Pre\u00e7o atual", money(m.posicao.atual));
      add("Lucro/preju\u00edzo aberto", money(m.posicao.pl) + " USD (" + pct(m.posicao.plpct) + ")", m.posicao.pl >= 0 ? "up" : "down");
      add("Stop de seguran\u00e7a do bot (-10%)", money(m.posicao.stop));
    } else {
      add("Posi\u00e7\u00e3o", m.emPosicao ? "aberta (sem detalhe da Alpaca)" : "nenhuma");
    }
  }

  function desenharTabelas() {
    var rb = $("res"); rb.replaceChildren();
    estado.mercados.forEach(function (m) {
      var tr = el("tr");
      tr.append(el("td", null, m.simbolo), el("td", null, m.estrategia || "sem estrat\u00e9gia"), el("td", "r", String(m.fechados)));
      var r = el("td", "r", money(m.resultado)); if (m.resultado > 0) r.classList.add("up"); if (m.resultado < 0) r.classList.add("down");
      tr.append(r); rb.appendChild(tr);
    });
    var ob = $("ordens"); ob.replaceChildren();
    estado.ordens.slice(0, 20).forEach(function (o) {
      var tr = el("tr");
      var lado = el("td", o.lado === "BUY" ? "up" : "down", o.lado === "BUY" ? "Compra" : "Venda");
      tr.append(el("td", null, data(o.tempo)), el("td", null, o.simbolo), lado, el("td", "r", nq.format(o.qtd)), el("td", "r", money(o.preco)), el("td", "r", money(o.valor)), el("td", null, o.ambiente));
      ob.appendChild(tr);
    });
    $("semordens").hidden = estado.ordens.length > 0;
  }

  function desenharTopo() {
    var c = estado.conta;
    $("equity").textContent = c ? money(c.equity) + " USD" : "sem liga\u00e7\u00e3o \u00e0 Alpaca";
    var dia = $("dia"); dia.className = "";
    if (c && c.ultimoEquity > 0) { var d = c.equity - c.ultimoEquity; dia.textContent = (d >= 0 ? "+" : "") + money(d) + " (" + pct(d / c.ultimoEquity) + ")"; dia.className = d >= 0 ? "up" : "down"; }
    else dia.textContent = "n/d";
    $("bolsa").textContent = estado.bolsaAberta === true ? "aberta" : estado.bolsaAberta === false ? "fechada" : "n/d";
    $("upd").textContent = new Date(estado.atualizado * 1000).toLocaleTimeString("pt-PT", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
  }

  function render() { desenharTopo(); desenharLista(); desenharConselho(); desenharTabelas(); }

  /* ---------- pedidos ---------- */
  function erro(msg) { var e = $("texto"); e.textContent = msg; e.style.color = "#ef5350"; }
  async function carregarEstado() {
    try {
      var r = await fetch("/api/estado", { cache: "no-store" });
      var j = await r.json();
      if (!r.ok) throw new Error(j.erro || r.status);
      estado = j;
      if (!sel || !mercadoSel()) sel = j.mercados[0].simbolo;
      $("texto").style.color = "";
      render();
      return true;
    } catch (e) { erro("N\u00e3o consegui ler os dados: " + e.message + ". Confirma que a base de dados est\u00e1 a correr."); return false; }
  }
  async function carregarVelas() {
    if (!sel) return;
    var m = mercadoSel();
    if (m && !m.tf) return;
    try {
      var r = await fetch("/api/velas?simbolo=" + encodeURIComponent(sel), { cache: "no-store" });
      var j = await r.json();
      if (!r.ok) throw new Error(j.erro || r.status);
      desenhar(j);
    } catch (e) { /* o cart\u00e3o de conselho j\u00e1 mostra o erro principal */ }
  }
  var n = 0;
  async function ciclo() {
    var ok = await carregarEstado();
    if (ok && n % 4 === 0) carregarVelas(); // o gr\u00e1fico muda s\u00f3 quando fecha uma vela: 1 em cada 4 ciclos chega
    n++;
  }
  ciclo();
  setInterval(ciclo, 15000);
})();
</script>
</body>
</html>
""";
}