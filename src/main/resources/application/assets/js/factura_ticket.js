/* Café_Café · comprobante de factura (diseño tipo ticket de 80 mm)
 * Lo usan el punto de venta (pos.html) y la pantalla Facturas (facturas.html), así lo que se ve en pantalla
 * es idéntico a lo que se imprime.
 *
 * Recibe el JSON que devuelve javaFacturas.ver(id):
 *  { num, cai, desde, hasta, limite (AAAA-MM-DD), rtn, cli, sub, isv, pct (15 % = 1500), tot, user, fecha (AAAA-MM-DD HH:MM:SS),
 *    an (anulada), pago, rec, cam, l:[{n,q,p,s}], neg:{n,rtn,dir,tel,mail} }   (dinero en centavos)
 *
 * Uso:  FacturaTicket.cuerpo(f)     -> HTML del ticket (para mostrar dentro de una pantalla)
 *       FacturaTicket.documento(f)  -> documento HTML completo (para mandar a imprimir con javaFacturas.imprimir)
 */
window.FacturaTicket = (function () {
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]));
  const n2 = c => (c / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const fechaLatina = iso => /^\d{4}-\d{2}-\d{2}/.test(iso || '') ? iso.slice(8, 10) + '/' + iso.slice(5, 7) + '/' + iso.slice(0, 4) : (iso || '');
  const hora = iso => (iso || '').slice(11, 19);

  // ---------- Monto en letras: "Son: (VEINTICUATRO LEMPIRAS 00/100 M.N.)" ----------
  const U = ['', 'UN', 'DOS', 'TRES', 'CUATRO', 'CINCO', 'SEIS', 'SIETE', 'OCHO', 'NUEVE', 'DIEZ', 'ONCE', 'DOCE', 'TRECE', 'CATORCE', 'QUINCE',
    'DIECISEIS', 'DIECISIETE', 'DIECIOCHO', 'DIECINUEVE', 'VEINTE', 'VEINTIUN', 'VEINTIDOS', 'VEINTITRES', 'VEINTICUATRO', 'VEINTICINCO',
    'VEINTISEIS', 'VEINTISIETE', 'VEINTIOCHO', 'VEINTINUEVE'];
  const D = ['', '', '', 'TREINTA', 'CUARENTA', 'CINCUENTA', 'SESENTA', 'SETENTA', 'OCHENTA', 'NOVENTA'];
  const C = ['', 'CIENTO', 'DOSCIENTOS', 'TRESCIENTOS', 'CUATROCIENTOS', 'QUINIENTOS', 'SEISCIENTOS', 'SETECIENTOS', 'OCHOCIENTOS', 'NOVECIENTOS'];
  function menosDeMil(n) {
    if (n === 100) return 'CIEN';
    const c = Math.floor(n / 100), r = n % 100;
    let s = C[c];
    if (r) {
      if (s) s += ' ';
      if (r < 30) s += U[r];
      else s += D[Math.floor(r / 10)] + (r % 10 ? ' Y ' + U[r % 10] : '');
    }
    return s;
  }
  function enLetras(n) {   // entero de 0 a 999 999
    if (n === 0) return 'CERO';
    const m = Math.floor(n / 1000), r = n % 1000;
    let s = m ? (m === 1 ? 'MIL' : menosDeMil(m) + ' MIL') : '';
    if (r) s += (s ? ' ' : '') + menosDeMil(r);
    return s;
  }
  function son(centavos) {
    const l = Math.floor(centavos / 100), c = centavos % 100;
    const txt = l > 999999 ? String(l) : enLetras(l);
    return '(' + txt + (l === 1 ? ' LEMPIRA ' : ' LEMPIRAS ') + String(c).padStart(2, '0') + '/100 M.N.)';
  }

  // ---------- Diseño ----------
  const CSS = `
.tk{width:72mm;max-width:100%;margin:0 auto;text-align:left;background:#fff;color:#000;font:11.5px/1.35 'Courier New',Consolas,monospace;padding:4mm 3mm}
.tk .c{text-align:center}.tk .b{font-weight:700}
.tk .f{display:flex;justify-content:space-between;gap:8px}
.tk .f span:last-child{text-align:right;white-space:nowrap}
.tk hr{border:0;border-top:1px dashed #000;margin:6px 0}.tk hr.s{border-top-style:solid}
.tk .cai{word-break:break-all}
.tk .art{display:grid;grid-template-columns:38px 1fr 50px 54px;gap:4px}
.tk .art .n{text-align:right}
.tk .art .p{grid-column:2/5}
.tk .raya{border-bottom:1px solid #000;display:inline-block;width:100px}
.tk .an{margin:6px 0;padding:4px;border:2px solid #000;text-align:center;font-weight:700;letter-spacing:.1em}
`;

  function fila(a, b, neg) { return `<div class="f${neg ? ' b' : ''}"><span>${a}</span><span>${b}</span></div>`; }

  function cuerpo(f) {
    const nb = f.neg || {};
    const cf = !f.rtn;
    const p = (f.pct || 0) / 100;                 // 15 % -> 15
    const sub = f.sub || 0, isv = f.isv || 0;
    const g15 = p > 0 && p !== 18 ? sub : 0, i15 = p > 0 && p !== 18 ? isv : 0;
    const g18 = p === 18 ? sub : 0, i18 = p === 18 ? isv : 0;
    const exento = p === 0 ? sub : 0;
    const efectivo = f.pago === 'Efectivo';
    const lineas = (f.l || []).map(x => `<div class="art"><span class="n">${x.q}</span><span>${esc(x.n).toUpperCase()}</span><span class="n">${n2(x.p)}</span><span class="n">${n2(x.s)}</span></div>`).join('');
    return `<div class="tk">
 <div class="c b">${esc(nb.n).toUpperCase()}</div>
 ${nb.dir ? `<div class="c">${esc(nb.dir).toUpperCase()}</div>` : ''}
 ${nb.tel ? `<div class="c">TELEFONO: ${esc(nb.tel)}</div>` : ''}
 <div class="c">RTN: ${esc(nb.rtn)}</div>
 ${nb.mail ? `<div class="c">email:<br>${esc(nb.mail)}</div>` : ''}
 <hr>
 ${f.an ? '<div class="an">FACTURA ANULADA</div>' : ''}
 <div class="b">FACTURA &nbsp;${esc(f.num)}</div>
 <div>CAI:</div><div class="cai">${esc(f.cai)}</div>
 <div>DESDE:${esc(f.desde)}</div><div>HASTA:${esc(f.hasta)}</div>
 ${fila('Fecha Límite:', fechaLatina(f.limite))}
 <div>Cliente: ${esc(f.cli).toUpperCase()}</div>
 <div>RTN : ${cf ? 'C/F' : esc(f.rtn)}</div>
 <hr>
 <div>Fecha: ${fechaLatina(f.fecha)}</div><div>Hora: ${hora(f.fecha)}</div>
 <div>Hecho Por : ${esc(f.user).toUpperCase()}</div>
 <div>Pago : ${esc(f.pago)}</div>
 <hr class="s">
 <div class="art b"><span class="n">CANT.</span><span>PRODUCTO</span><span class="n">PRECIO</span><span class="n">TOTAL</span></div>
 <hr class="s">
 ${lineas}
 <hr class="s">
 <div>Nro Ord. de Compra Exenta <span class="raya"></span></div>
 <div>Nro Const Reg. Exonerado <span class="raya"></span></div>
 <div>Nro Registro de la SAG <span class="raya"></span></div>
 <hr class="s">
 ${fila('Sub Total L.', n2(sub))}
 ${fila('Importe Exonerado', '0.00')}
 ${fila('Importe Exento L.', n2(exento))}
 ${fila('Importe Gravado ' + (p > 0 && p !== 18 ? p : 15) + '%', n2(g15))}
 ${fila('Impuesto S/Ventas', n2(i15))}
 ${fila('Importe Gravado 18%', n2(g18))}
 ${fila('Impuesto S/Ventas', n2(i18))}
 ${fila('Descuentos Y Rebajas', '0.00')}
 <hr class="s">
 ${fila('Total Pagar L.', n2(f.tot), true)}
 <hr class="s">
 ${efectivo ? fila('Efectivo Recibido', n2(f.rec)) + fila('Su Cambio L.', n2(f.cam)) : fila('Pagado con ' + esc(f.pago), n2(f.tot))}
 <div>Son: ${son(f.tot)}</div>
 <br>
 <div class="c">Un Placer Servirles!</div>
 <div class="c">Original: cliente · Copia: emisor</div>
</div>`;
  }

  function documento(f) {
    return '<!DOCTYPE html><html><head><meta charset="UTF-8"><style>@page{margin:0}html,body{margin:0;background:#fff}' + CSS + '</style></head><body>' + cuerpo(f) + '</body></html>';
  }

  // El estilo del ticket se agrega solo a la pantalla que carga este archivo
  document.head.insertAdjacentHTML('beforeend', '<style id="tk-css">' + CSS + '.tk{border:1px dashed #b9c4cc;border-radius:6px;box-shadow:0 1px 6px rgba(16,34,47,.08)}</style>');

  return { cuerpo, documento, son, css: CSS };
})();
