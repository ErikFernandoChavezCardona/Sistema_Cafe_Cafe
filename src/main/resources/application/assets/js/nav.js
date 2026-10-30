/* Café_Café · menú lateral según quién inició sesión. Lo cargan todas las pantallas con menú.
   - Cajero: solo Venta y Caja.  - Administrador: todo, más Usuarios, Respaldos y Auditoría.
   - Agrega "Mi cuenta" y "Cerrar sesión".  - Sin sesión: manda al login.
   La seguridad real está en Java (cada puente vuelve a comprobar el rol); esto solo evita mostrar lo que no se puede usar. */
(function () {
  var SOLO_ADMIN = ['productos.html', 'insumos.html', 'egresos.html', 'facturas.html', 'reportes.html'];
  var ADMIN_EXTRA = [['usuarios.html', 'Usuarios'], ['respaldos.html', 'Respaldos'], ['auditoria.html', 'Auditoría']];
  var CSS = 'nav .sp{flex:1;min-height:12px}'
    + 'nav .sec{font-size:10px;letter-spacing:.2em;text-transform:uppercase;color:rgba(255,255,255,.5);padding:14px 10px 4px}'
    + 'nav .who{padding:10px;border-top:1px solid rgba(255,255,255,.18);margin-top:6px;font-size:12px;line-height:1.35}'
    + 'nav .who b{display:block;font-weight:600;color:#fff;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}'
    + 'nav .who span{color:rgba(255,255,255,.65)}';
  function pagina() { return (location.pathname.split('/').pop() || '').toLowerCase() }
  function link(href, texto) { var a = document.createElement('a'); a.href = href; a.textContent = texto; if (pagina() === href) a.className = 'on'; return a }
  function aplicar() {
    var nav = document.querySelector('body > nav');
    if (!nav) return true;                              // el login no tiene menú
    if (!window.javaApp || !javaApp.sesion) return false; // Java todavía no entregó el puente
    var s = null; try { s = JSON.parse(javaApp.sesion()) } catch (e) { }
    if (!s) { javaApp.irA('login.html'); return true }
    var st = document.createElement('style'); st.textContent = CSS; document.head.appendChild(st);
    var as = [].slice.call(nav.querySelectorAll('a'));
    if (s.cambiar) { as.forEach(function (a) { a.style.display = 'none' }) }                 // contraseña temporal: nada más hasta cambiarla
    else if (!s.admin) { as.forEach(function (a) { var h = (a.getAttribute('href') || '').toLowerCase(); if (SOLO_ADMIN.indexOf(h) >= 0) a.style.display = 'none' }) }
    if (s.admin && !s.cambiar) {
      var t = document.createElement('div'); t.className = 'sec'; t.textContent = 'Administración'; nav.appendChild(t);
      ADMIN_EXTRA.forEach(function (x) { nav.appendChild(link(x[0], x[1])) });
    }
    var sp = document.createElement('div'); sp.className = 'sp'; nav.appendChild(sp);
    var w = document.createElement('div'); w.className = 'who';
    var n = document.createElement('b'); n.textContent = s.nombre; var r = document.createElement('span'); r.textContent = s.usuario + ' · ' + s.rol;
    w.appendChild(n); w.appendChild(r); nav.appendChild(w);
    if (!s.cambiar) nav.appendChild(link('cuenta.html', 'Mi cuenta'));
    var out = document.createElement('a'); out.href = '#'; out.textContent = 'Cerrar sesión';
    out.addEventListener('click', function (e) { e.preventDefault(); javaApp.cerrarSesion() });
    nav.appendChild(out);
    return true;
  }
  // Cierre automático de sesión: si nadie toca el sistema durante varios minutos (según el rol), vuelve al login.
  var INACTIVIDAD_ADMIN_MIN = 5, INACTIVIDAD_CAJERO_MIN = 10;   // administrador 5 min, cajero 10 min
  function vigilarInactividad() {
    if (!window.javaApp || !javaApp.sesion) return false;
    var s = null; try { s = JSON.parse(javaApp.sesion()) } catch (e) { }
    if (!s) return true;                                   // sin sesión no hay nada que cerrar
    var limite = (s.admin ? INACTIVIDAD_ADMIN_MIN : INACTIVIDAD_CAJERO_MIN) * 60000;
    var ultima = Date.now(), cerrada = false;
    function actividad() { ultima = Date.now() }
    ['mousemove', 'mousedown', 'keydown', 'wheel', 'touchstart', 'scroll'].forEach(function (ev) { document.addEventListener(ev, actividad, true) });
    setInterval(function () {
      if (!cerrada && Date.now() - ultima >= limite) { cerrada = true; javaApp.cerrarSesion() }
    }, 15000);
    return true;
  }
  var intentos = 0, menuOk = false, vigiaOk = false;
  function probar() {
    if (!menuOk) menuOk = aplicar();
    if (!vigiaOk) vigiaOk = vigilarInactividad();
    if ((menuOk && vigiaOk) || ++intentos > 50) return;
    setTimeout(probar, 100);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', probar); else probar();
})();
