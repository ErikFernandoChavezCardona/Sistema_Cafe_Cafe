/* Selector con buscador: convierte cualquier <select data-buscar> en un botón que abre una ventana
   con buscador y la lista de opciones. El <select> sigue existiendo (oculto), así que el código de cada
   pantalla lo usa igual: .value, .innerHTML y el evento "change". */
(function () {
  var css = document.createElement('style');
  css.textContent =
    '.sb-b{display:block;width:100%;text-align:left;cursor:pointer;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;position:relative;padding-right:36px}' +
    '.sb-b:after{content:"";position:absolute;right:16px;top:50%;width:7px;height:7px;border-right:2px solid currentColor;border-bottom:2px solid currentColor;transform:translateY(-70%) rotate(45deg);opacity:.5}' +
    '.sb-b[disabled]{opacity:.5;cursor:default}' +
    '.sb-f{position:fixed;inset:0;background:rgba(16,34,47,.45);display:none;place-items:center;z-index:30}' +
    '.sb-f.on{display:grid}' +
    '.sb-m{width:min(440px,92vw);max-height:92vh;display:flex;flex-direction:column;gap:12px;background:var(--crema,#f6f1ea);border-radius:18px;padding:26px}' +
    '.sb-m h2{margin:0;font-size:22px}' +
    '.sb-l{max-height:46vh;overflow-y:auto;border:1px solid var(--linea,#e4dccf);border-radius:12px;background:#fff}' +
    '.sb-o{display:flex;justify-content:space-between;align-items:center;gap:10px;width:100%;min-height:46px;padding:8px 14px;text-align:left;border-bottom:1px solid var(--linea,#e4dccf);background:none}' +
    '.sb-o:last-child{border-bottom:0}.sb-o:active{background:var(--niebla,#e8eef3)}' +
    '.sb-o.on{font-weight:700}.sb-o[disabled]{opacity:.5}' +
    '.sb-l p{padding:14px;color:var(--suave,#6b7b88);margin:0}';
  document.head.appendChild(css);

  var norm = function (t) { return String(t).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase(); };
  var esc = function (s) { return String(s).replace(/[&<>"]/g, function (m) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[m]; }); };

  var fondo = document.createElement('div');
  fondo.className = 'sb-f';
  fondo.innerHTML = '<div class="sb-m" role="dialog" aria-modal="true"><h2 class="h" id="sb-t"></h2>' +
    '<input class="in" id="sb-q" placeholder="Buscar…" autocomplete="off" aria-label="Buscar">' +
    '<div class="sb-l" id="sb-l"></div>' +
    '<button type="button" class="pill" id="sb-x">Cancelar</button></div>';
  var activo = null;

  function cerrar() { fondo.classList.remove('on'); activo = null; }
  function pintar() {
    if (!activo) return;
    var t = norm(document.getElementById('sb-q').value.trim());
    var html = '', n = 0;
    Array.prototype.forEach.call(activo.options, function (o, i) {
      if (t && norm(o.text).indexOf(t) < 0) return;
      n++;
      html += '<button type="button" class="sb-o' + (i === activo.selectedIndex ? ' on' : '') + '" data-i="' + i + '"' +
        (o.disabled ? ' disabled' : '') + '><span>' + esc(o.text) + '</span></button>';
    });
    document.getElementById('sb-l').innerHTML = n ? html : '<p>No hay resultados.</p>';
  }
  function abrir(sel) {
    activo = sel;
    document.getElementById('sb-t').textContent = sel.getAttribute('data-buscar') || 'Elegir';
    document.getElementById('sb-q').value = '';
    pintar();
    fondo.classList.add('on');
    setTimeout(function () { document.getElementById('sb-q').focus(); }, 30);
  }
  function elegir(i) {
    var sel = activo;
    if (!sel) return;
    sel.selectedIndex = i;
    cerrar();
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    if (sel._sbBtn) sel._sbBtn.focus();
  }

  function mejorar(sel) {
    if (sel._sbBtn) return;
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'in sb-b';
    b.id = sel.id ? sel.id + '-sb' : '';
    b.setAttribute('aria-label', sel.getAttribute('aria-label') || sel.getAttribute('data-buscar') || 'Elegir');
    sel.parentNode.insertBefore(b, sel);
    sel.style.display = 'none';
    sel._sbBtn = b;
    var refrescar = function () {
      var o = sel.options[sel.selectedIndex];
      b.textContent = o ? o.text : 'Elegir…';
      b.disabled = sel.disabled;
    };
    // Si el código de la pantalla asigna .value directamente, el botón se actualiza solo
    var desc = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
    Object.defineProperty(sel, 'value', {
      get: function () { return desc.get.call(sel); },
      set: function (v) { desc.set.call(sel, v); refrescar(); }
    });
    new MutationObserver(refrescar).observe(sel, { childList: true, subtree: true, attributes: true });
    sel.addEventListener('change', refrescar);
    b.addEventListener('click', function () { abrir(sel); });
    refrescar();
  }

  function iniciar() {
    document.body.appendChild(fondo);
    fondo.addEventListener('click', function (e) {
      if (e.target === fondo || e.target.id === 'sb-x') { cerrar(); return; }
      var o = e.target.closest('.sb-o');
      if (o && !o.disabled) elegir(+o.getAttribute('data-i'));
    });
    document.getElementById('sb-q').addEventListener('input', pintar);
    document.addEventListener('keydown', function (e) {
      if (!fondo.classList.contains('on')) return;
      if (e.key === 'Escape') { e.stopPropagation(); e.preventDefault(); cerrar(); }
      else if (e.key === 'Enter' && e.target.id === 'sb-q') {
        e.preventDefault();
        var o = fondo.querySelector('.sb-o:not([disabled])');
        if (o) elegir(+o.getAttribute('data-i'));
      }
    }, true);
    Array.prototype.forEach.call(document.querySelectorAll('select[data-buscar]'), mejorar);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', iniciar); else iniciar();
})();
