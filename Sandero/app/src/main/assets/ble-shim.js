/* Pont Bluetooth : fait fonctionner l'appli comme avec Web Bluetooth, via le code Android natif. */
(function () {
  if (!window.AndroidBLE || navigator.bluetooth) return;
  var P = {}, N = 0, CH = {};

  function call(fn) {
    var args = Array.prototype.slice.call(arguments, 1);
    return new Promise(function (res, rej) {
      var id = ++N;
      P[id] = { res: res, rej: rej };
      try { window.AndroidBLE[fn].apply(window.AndroidBLE, [id].concat(args)); }
      catch (e) { delete P[id]; rej(e); }
    });
  }
  function b64u8(b) {
    var s = atob(b), a = new Uint8Array(s.length), i;
    for (i = 0; i < s.length; i++) a[i] = s.charCodeAt(i);
    return a;
  }
  function u8b64(u) {
    var s = '', i;
    for (i = 0; i < u.length; i++) s += String.fromCharCode(u[i]);
    return btoa(s);
  }
  function toU8(v) {
    if (v instanceof Uint8Array) return v;
    if (v && v.buffer) return new Uint8Array(v.buffer, v.byteOffset, v.byteLength);
    return new Uint8Array(v);
  }
  function Emitter(o) {
    var L = {};
    o.addEventListener = function (t, f) { (L[t] = L[t] || []).push(f); };
    o.removeEventListener = function (t, f) { L[t] = (L[t] || []).filter(function (x) { return x !== f; }); };
    o._fire = function (t, ev) { (L[t] || []).slice().forEach(function (f) { try { f(ev); } catch (e) {} }); };
    return o;
  }
  function mkChar(c) {
    var o = Emitter({
      uuid: c.uuid, value: null,
      properties: { notify: !!c.p.notify, indicate: !!c.p.indicate, write: !!c.p.write, writeWithoutResponse: !!c.p.writeNoResp }
    });
    o.startNotifications = function () { return call('subscribe', c.i).then(function () { return o; }); };
    o.writeValue = function (v) { return call('write', c.i, u8b64(toU8(v)), false).then(function () {}); };
    o.writeValueWithoutResponse = function (v) { return call('write', c.i, u8b64(toU8(v)), true).then(function () {}); };
    o._emit = function (b) {
      var u = b64u8(b);
      o.value = new DataView(u.buffer, u.byteOffset, u.byteLength);
      o._fire('characteristicvaluechanged', { target: o });
    };
    CH[c.i] = o;
    return o;
  }

  var current = null;
  window.__ble = {
    res: function (id, v) { var p = P[id]; if (p) { delete P[id]; p.res(v); } },
    rej: function (id, m) { var p = P[id]; if (p) { delete P[id]; p.rej(new Error(m)); } },
    val: function (i, b) { var c = CH[i]; if (c) c._emit(b); },
    disc: function () {
      if (current) {
        current.gatt.connected = false;
        current._fire('gattserverdisconnected', { target: current });
      }
    }
  };

  function mkDev(info) {
        var dev = Emitter({ name: info.name || '', id: info.addr });
        var server = {
          connected: false, device: dev, _s: [],
          connect: function () {
            return call('connect', info.addr).then(function (r) {
              server._s = r.services.map(function (s) {
                return { uuid: s.uuid, getCharacteristics: function () { return Promise.resolve(s.chars.map(mkChar)); } };
              });
              server.connected = true;
              current = dev;
              return server;
            });
          },
          getPrimaryServices: function () { return Promise.resolve(server._s); },
          disconnect: function () {
            try { window.AndroidBLE.disconnect(); } catch (e) {}
            if (server.connected) { server.connected = false; dev._fire('gattserverdisconnected', { target: dev }); }
          }
        };
        dev.gatt = server;
        return dev;
  }

  navigator.bluetooth = {
    requestDevice: function () { return call('pick').then(mkDev); },
    /* dernier adaptateur utilisé : connexion automatique sans repasser par la liste */
    getKnownDevice: function (addr, name) { return Promise.resolve(mkDev({ addr: addr, name: name || '' })); }
  };
})();
