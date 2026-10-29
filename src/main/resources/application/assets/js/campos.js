/* Café_Café · formato automático de campos numéricos.
   Se activa con data-fmt en el <input>:
     int   solo dígitos                       dec / dec2  decimales (hasta 3 / 2 cifras)
     rtn   0000-0000-000000 (14 dígitos)      pref  000-001-01
     fecha AAAA-MM-DD                         tel   0000-0000 (si empieza con + se deja libre)
   Quita lo que no corresponde (letras, símbolos) y pone los guiones solos, también al pegar.
   Va en fase de captura para correr ANTES que el código de cada pantalla (así ve ya el valor limpio). */
(function(){
  function decimales(v,n){
    v=v.replace(',','.').replace(/[^\d.]/g,'');
    var i=v.indexOf('.');if(i<0)return v;
    return v.slice(0,i+1)+v.slice(i+1).replace(/\./g,'').slice(0,n);
  }
  function grupos(v,t){
    var d=v.replace(/\D/g,'').slice(0,t.reduce(function(a,b){return a+b},0)),out=[],p=0;
    for(var i=0;i<t.length&&p<d.length;i++){out.push(d.slice(p,p+t[i]));p+=t[i]}
    return out.join('-');
  }
  var F={
    int:function(v){return v.replace(/\D/g,'')},
    dec:function(v){return decimales(v,3)},
    dec2:function(v){return decimales(v,2)},
    rtn:function(v){return grupos(v,[4,4,6])},
    pref:function(v){return grupos(v,[3,3,2])},
    fecha:function(v){return grupos(v,[4,2,2])},
    tel:function(v){return v.trim().charAt(0)==='+'?v.replace(/[^\d+ -]/g,'').replace(/(.)\+/g,'$1'):grupos(v,[4,4])}
  };
  var SIG=/[0-9.,+]/;   // lo que cuenta para recolocar el cursor (los guiones se agregan o quitan solos)
  document.addEventListener('input',function(e){
    var el=e.target,f=el&&el.dataset&&el.dataset.fmt;
    if(!f||!F[f]||typeof el.value!=='string')return;
    var antes=el.value,nuevo=F[f](antes);
    if(nuevo===antes)return;
    var pos=el.selectionStart,n=0,i;
    if(pos==null)pos=antes.length;
    for(i=0;i<pos;i++)if(SIG.test(antes.charAt(i)))n++;
    el.value=nuevo;
    for(i=0;i<nuevo.length&&n>0;i++)if(SIG.test(nuevo.charAt(i)))n--;
    try{el.setSelectionRange(i,i)}catch(x){}
  },true);
})();
