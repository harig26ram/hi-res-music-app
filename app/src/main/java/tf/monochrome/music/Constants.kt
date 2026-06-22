package tf.monochrome.music

object Constants {
    const val SITE_URL = "https://monochrome.tf/"

    const val ACTION_PLAY = "tf.monochrome.music.PLAY"
    const val ACTION_PAUSE = "tf.monochrome.music.PAUSE"
    const val ACTION_NEXT = "tf.monochrome.music.NEXT"
    const val ACTION_PREVIOUS = "tf.monochrome.music.PREVIOUS"
    const val ACTION_UPDATE_STATE = "tf.monochrome.music.UPDATE_STATE"
    const val EXTRA_IS_PLAYING = "is_playing"

    const val MIME_MPEG = "audio/mpeg"
    const val MIME_FLAC = "audio/flac"
    const val MIME_OCTET_STREAM = "application/octet-stream"
    const val MIME_HTML = "text/html"

    const val DEFAULT_ORIGIN = "https://monochrome.tf"
    const val PROXY_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36"

    const val JS_HOOKS = """
(function(){
  try {
    var ms = navigator.mediaSession;
    if (!ms || typeof ms.setActionHandler !== 'function') {
      var stub = {
        metadata: null, playbackState: 'none',
        setActionHandler: function(){}, setPositionState: function(){},
        setMicrophoneActive: function(){}, setCameraActive: function(){}
      };
      Object.defineProperty(navigator, 'mediaSession', { get: function(){ return stub; }, configurable: true });
    }
    
    var origMs = navigator.mediaSession;
    if(!window.__mcHandlers) window.__mcHandlers = {};
    
    var _origSAH = origMs.setActionHandler.bind(origMs);
    origMs.setActionHandler = function(action, handler){
      window.__mcHandlers[action] = handler;
      try { return _origSAH(action, handler); } catch(e){}
    };

    var _metadata = origMs.metadata;
    Object.defineProperty(origMs, 'metadata', {
      get: function() { return _metadata; },
      set: function(value) {
        _metadata = value;
        if (value) {
           var art = (value.artwork && value.artwork.length > 0) ? value.artwork[0].src : '';
           var localUri = '';
           try {
             var a = document.querySelector('audio,video');
             if(a && a.src && a.src.includes('local-file.monochrome.tf')){
               var url = new URL(a.src);
               localUri = url.searchParams.get('uri') || '';
             }
           } catch(e){}
           if(typeof MonochromeApp !== 'undefined') MonochromeApp.onMetadataChanged(value.title || '', value.artist || '', art, localUri);
        }
      },
      configurable: true
    });
  } catch(e){}

  if(!window.__mcBlob){
    window.__mcBlob = true;
    var cache = {}, nameCache = {};
    var origCreate = URL.createObjectURL.bind(URL);
    URL.createObjectURL = function(blob){
      var url = origCreate(blob);
      cache[url] = blob;
      return url;
    };
    var origClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function(){
      if(this.download && this.href && this.href.startsWith('blob:')) nameCache[this.href] = this.download;
      return origClick.call(this);
    };
    document.addEventListener('click', function(e){
      var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
      if(a && a.download && a.href && a.href.startsWith('blob:')) nameCache[a.href] = a.download;
    }, true);
    window.__mcGetBlob = function(url, fallback, mime){
      var blob = cache[url];
      if(!blob){ if(typeof MonochromeApp !== 'undefined') MonochromeApp.onBlobError('Blob not cached'); return; }
      var name = nameCache[url] || fallback;
      var r = new FileReader();
      r.onloadend = function(){ if(typeof MonochromeApp !== 'undefined') MonochromeApp.onBlobData(r.result, name, mime); };
      r.onerror = function(){ if(typeof MonochromeApp !== 'undefined') MonochromeApp.onBlobError('FileReader failed'); };
      r.readAsDataURL(blob);
    };
  }

  if(!window.__mcTrack){
    window.__mcTrack = true;
    var lastT = '', lastA = '', lastArt = '', lastSrc = '';
    var lastState = false;

    function getTitle(){
      if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.title) return navigator.mediaSession.metadata.title;
      var sel = ['.now-playing-title','.player-title','.track-title','.song-title','.current-track-name','.playing-title','[class*="nowPlaying"] [class*="title"]','[class*="player"] [class*="title"]'];
      for(var i=sel.length-1; i>=0; i--){
        var el = document.querySelector(sel[i]);
        if(el && el.innerText && el.innerText.trim()) return el.innerText.trim();
      }
      return document.title || 'Monochrome';
    }

    function getArtist(){
       if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artist) return navigator.mediaSession.metadata.artist;
       var sel = ['.now-playing-artist','.artist-name','[class*="artist"]'];
       for(var i=0; i<sel.length; i++){
          var el = document.querySelector(sel[i]);
          if(el && el.innerText && el.innerText.trim()) return el.innerText.trim();
       }
       return 'Monochrome';
    }

    function getArtwork(){
       if (navigator.mediaSession && navigator.mediaSession.metadata && navigator.mediaSession.metadata.artwork && navigator.mediaSession.metadata.artwork.length > 0) {
          return navigator.mediaSession.metadata.artwork[0].src;
       }
       var el = document.querySelector('.now-playing-art img, .album-art img, [class*="player"] img, [class*="nowPlaying"] img');
       return el ? el.src : '';
    }

    function isPlaying(){
      var a = document.querySelector('audio,video');
      return a ? !a.paused : false;
    }

    function check(){
      var t = getTitle(), a = getArtist(), art = getArtwork();
      var localUri = '', currentSrc = '';
      try {
        var aud = document.querySelector('audio,video');
        if(aud && aud.src) {
          currentSrc = aud.src;
          if(currentSrc.includes('local-file.monochrome.tf')){
            var url = new URL(currentSrc);
            localUri = url.searchParams.get('uri') || '';
          }
        }
      } catch(e){}

      if(t !== lastT || a !== lastA || art !== lastArt || currentSrc !== lastSrc){
        lastT = t; lastA = a; lastArt = art; lastSrc = currentSrc;
        if(typeof MonochromeApp !== 'undefined') MonochromeApp.onMetadataChanged(t, a, art, localUri);
      }
      var s = isPlaying();
      if(s !== lastState){
        lastState = s;
        if(typeof MonochromeApp !== 'undefined') MonochromeApp.onPlaybackStateChanged(s);
      }
    }

    var obs = new MutationObserver(check);
    obs.observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true });
    setInterval(check, 3000);
    check();

    window.__mcPlay = function(){ 
      if(window.__mcHandlers && window.__mcHandlers['play']) { window.__mcHandlers['play'](); return; }
      var a=document.querySelector('audio,video'); if(a) a.play(); 
    };
    window.__mcPause = function(){ 
      if(window.__mcHandlers && window.__mcHandlers['pause']) { window.__mcHandlers['pause'](); return; }
      var a=document.querySelector('audio,video'); if(a) a.pause(); 
    };
    window.__mcNext = function(){ 
      if(window.__mcHandlers && window.__mcHandlers['nexttrack']) { window.__mcHandlers['nexttrack'](); return; }
      var btn = document.querySelector('.next-button, .player-next, [class*="next"], [id*="next"], [aria-label*="next"], [title*="next"]');
      if(btn) { btn.click(); return; }
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackNext', bubbles: true }));
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', shiftKey: true, bubbles: true }));
    };
    window.__mcPrev = function(){ 
      if(window.__mcHandlers && window.__mcHandlers['previoustrack']) { window.__mcHandlers['previoustrack'](); return; }
      var btn = document.querySelector('.prev-button, .player-prev, [class*="prev"], [id*="prev"], [aria-label*="prev"], [title*="prev"]');
      if(btn) { btn.click(); return; }
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'MediaTrackPrevious', bubbles: true }));
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', shiftKey: true, bubbles: true }));
    };

    if(!window.__mcAudioFix){
      window.__mcAudioFix = true;
      var _audioRetryCount = {};
      var MAX_AUDIO_RETRIES = 3;

      function patchAudioElement(a){
        if(a.__mcPatched) return;
        a.__mcPatched = true;

        a.addEventListener('error', function(e){
          var src = a.src || a.currentSrc || '';
          if(!src) return;
          var retryKey = src.substring(0, 100);
          if(!_audioRetryCount[retryKey]) _audioRetryCount[retryKey] = 0;

          if(_audioRetryCount[retryKey] < MAX_AUDIO_RETRIES){
            _audioRetryCount[retryKey]++;
            var delay = _audioRetryCount[retryKey] * 1000;
            setTimeout(function(){
              a.src = src;
              a.load();
            }, delay);
          } else {
            if(typeof MonochromeApp !== 'undefined') MonochromeApp.onPlaybackError('Audio load failed after retries: ' + src);
            delete _audioRetryCount[retryKey];
          }
        }, true);

        a.addEventListener('playing', function(){
          var src = a.src || a.currentSrc || '';
          var retryKey = src.substring(0, 100);
          delete _audioRetryCount[retryKey];
        }, true);
      }

      function scanAndPatchAudio(){
        document.querySelectorAll('audio, video').forEach(patchAudioElement);
      }

      var _audioObs = new MutationObserver(scanAndPatchAudio);
      _audioObs.observe(document.documentElement, { childList: true, subtree: true });
      scanAndPatchAudio();
    }
  }

  if(!window.__mcLocal){
    window.__mcLocal = true;
    var sAI = Symbol.asyncIterator || Symbol.for('Symbol.asyncIterator');

    (function(){
      var FSHandle = function(){};
      Object.defineProperties(FSHandle.prototype, {
        'kind': { get: function(){ return this._kind; }, set: function(v){ this._kind = v; }, configurable: true },
        'name': { get: function(){ return this._name; }, set: function(v){ this._name = v; }, configurable: true }
      });
      var FSFileHandle = function(){};
      FSFileHandle.prototype = Object.create(FSHandle.prototype);
      var FSDirHandle = function(){};
      FSDirHandle.prototype = Object.create(FSHandle.prototype);
      if(typeof Symbol !== 'undefined' && Symbol.toStringTag){
        Object.defineProperty(FSHandle.prototype, Symbol.toStringTag, { value: 'FileSystemHandle' });
        Object.defineProperty(FSFileHandle.prototype, Symbol.toStringTag, { value: 'FileSystemFileHandle' });
        Object.defineProperty(FSDirHandle.prototype, Symbol.toStringTag, { value: 'FileSystemDirectoryHandle' });
      }
      window.FileSystemHandle = FSHandle;
      window.FileSystemFileHandle = FSFileHandle;
      window.FileSystemDirectoryHandle = FSDirHandle;
    })();

    function makeFileHandle(info){
      var h = new window.FileSystemFileHandle();
      h.kind = 'file';
      h.name = info.name;
      Object.assign(h, {
        __mcHandle: true,
        __mcUri: info.uri,
        queryPermission: async function(){ return 'granted'; },
        requestPermission: async function(){ return 'granted'; },
        isSameEntry: async function(o){ return o && (o.__mcUri === info.uri || (o.__mcHandle && o.name === info.name && o.kind === 'file')); },
        getFile: async function(){
          try {
            var url = 'https://local-file.monochrome.tf/?uri=' + encodeURIComponent(info.uri) + '&t=' + Date.now();
            var resp = await fetch(url);
            if(!resp.ok) throw new Error('Fetch failed: ' + resp.status);
            var blob = await resp.blob();
            return new File([blob], info.name, {type: blob.type || 'audio/mpeg'});
          } catch(e){
            return new Promise(function(res, rej){
              var id = 'fc_' + Date.now() + '_' + Math.random().toString(36).slice(2);
              window['__monochromeFile_' + id] = function(b64, mime){
                delete window['__monochromeFile_' + id];
                if(!b64){ rej(new Error('read failed')); return; }
                try {
                  var bin = atob(b64), bytes = new Uint8Array(bin.length);
                  for(var i=0; i<bin.length; i++) bytes[i] = bin.charCodeAt(i);
                  res(new File([new Blob([bytes], {type: mime || 'audio/mpeg'})], info.name, {type: mime || 'audio/mpeg'}));
                } catch(err){ rej(err); }
              };
              if(typeof MonochromeApp !== 'undefined') MonochromeApp.requestFileContent(info.uri, id);
            });
          }
        }
      });
      return h;
    }

    function makeDirHandle(files, hid){
      var id = hid || ('mch_' + Date.now());
      var h = new window.FileSystemDirectoryHandle();
      h.kind = 'directory';
      h.name = 'Local Files';
      Object.assign(h, {
        __mcHandle: true,
        __mcHandleId: id,
        __mcFiles: files,
        queryPermission: async function(){ return 'granted'; },
        requestPermission: async function(){ return 'granted'; },
        isSameEntry: async function(o){ return o && o.__mcHandleId === id; },
        getFileHandle: async function(n){
          var f = files.find(function(x){ return x.name === n; });
          if(!f) throw new DOMException('Not found', 'NotFoundError');
          return makeFileHandle(f);
        },
        getDirectoryHandle: async function(){ throw new DOMException('Not found', 'NotFoundError'); },
        resolve: async function(handle){
          if(handle.kind === 'file'){
            var f = files.find(function(x){ return x.name === handle.name; });
            return f ? [f.name] : null;
          }
          return null;
        }
      });
      var createIterator = function(mapFn){
        return function(){
          var i = 0;
          var it = {
            next: function(){
              if(i >= files.length) return Promise.resolve({done: true, value: undefined});
              return Promise.resolve({value: mapFn(files[i++]), done: false});
            }
          };
          it[sAI] = function(){ return this; };
          return it;
        };
      };
      h.entries = createIterator(function(f){ return [f.name, makeFileHandle(f)]; });
      h.values = createIterator(function(f){ return makeFileHandle(f); });
      h.keys = createIterator(function(f){ return f.name; });
      h[sAI] = h.entries;
      return h;
    }

    function unwrap(v){
      if(!v || typeof v !== 'object') return v;
      if(Array.isArray(v)) return v.map(unwrap);
      if(v.__mcHandle) return { __mcHandle: true, kind: v.kind, name: v.name, __mcUri: v.__mcUri, __mcHandleId: v.__mcHandleId, __mcFiles: v.__mcFiles };
      if(Object.prototype.toString.call(v) === '[object Object]'){
        var r = {};
        for(var k in v) if(Object.prototype.hasOwnProperty.call(v, k)) r[k] = unwrap(v[k]);
        return r;
      }
      return v;
    }
    var _put = IDBObjectStore.prototype.put;
    var _add = IDBObjectStore.prototype.add;
    IDBObjectStore.prototype.put = function(v, k){ return _put.call(this, unwrap(v), k); };
    IDBObjectStore.prototype.add = function(v, k){ return _add.call(this, unwrap(v), k); };

    function wrap(res){
      if(!res || typeof res !== 'object') return res;
      if(Array.isArray(res)) return res.map(wrap);
      if(res.__mcHandle){
        if(!res.getFile && !res.getFileHandle){
          if(res.kind === 'directory') return makeDirHandle(res.__mcFiles || [], res.__mcHandleId);
          return makeFileHandle({ name: res.name, uri: res.__mcUri });
        }
        return res;
      }
      if(Object.prototype.toString.call(res) === '[object Object]'){
        for(var k in res) {
          if(Object.prototype.hasOwnProperty.call(res, k)) {
            try {
              var v = wrap(res[k]);
              if(v !== res[k]) res[k] = v;
            } catch(e){}
          }
        }
      }
      return res;
    }

    var rProto = IDBRequest.prototype;
    var rDesc = Object.getOwnPropertyDescriptor(rProto, 'result');
    if(rDesc && rDesc.get){
      var _resG = rDesc.get;
      Object.defineProperty(rProto, 'result', { get: function(){ return wrap(_resG.call(this)); }, configurable: true });
    }
    if(typeof IDBCursorWithValue !== 'undefined'){
      var cProto = IDBCursorWithValue.prototype;
      var vDesc = Object.getOwnPropertyDescriptor(cProto, 'value');
      if(vDesc && vDesc.get){
        var _valG = vDesc.get;
        Object.defineProperty(cProto, 'value', { get: function(){ return wrap(_valG.call(this)); }, configurable: true });
      }
    }

    window.__mcResolveFolder = function(cbId){
      var cb = window['__mcFolderCb_' + cbId];
      if(!cb) return;
      delete window['__mcFolderCb_' + cbId];
      var files = window.__mcPendingFolder;
      window.__mcPendingFolder = null;
      if(!files || !files.length){ cb(null, 'No files'); return; }
      cb(files, null);
    };
    window.showDirectoryPicker = async function(){
      return new Promise(function(res, rej){
        var cbId = 'fp_' + Date.now();
        window['__mcFolderCb_' + cbId] = function(files, err){
          if(err || !files){ rej(new DOMException(err || 'Cancelled', 'AbortError')); return; }
          res(makeDirHandle(files));
        };
        if(typeof MonochromeApp !== 'undefined') MonochromeApp.requestFolderPicker(cbId);
      });
    };
    window.showOpenFilePicker = async function(){
      throw new DOMException('Not implemented', 'NotSupportedError');
    };
  }

  // Service Worker Auto-Update
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.addEventListener('controllerchange', function() {
      window.location.reload();
    });
    navigator.serviceWorker.getRegistration().then(function(reg) {
      if (reg) {
        reg.addEventListener('updatefound', function() {
          var newWorker = reg.installing;
          if (newWorker) {
            newWorker.addEventListener('statechange', function() {
              if (newWorker.state === 'installed' && navigator.serviceWorker.controller) {
                newWorker.postMessage({ type: 'SKIP_WAITING' });
              }
            });
          }
        });
        setInterval(function() { reg.update(); }, 3600000);
      }
    });
  }

  // Source Indicator
  if(!window.__mcSourceInd){
    window.__mcSourceInd = true;
    var _srcColors = { tidal:'#00FFFF', qobuz:'#FF8C00', deezer:'#A855F7', amazon:'#3B82F6', 'internet-archive':'#3B82F6', default:'#888' };
    var _srcLabels = { tidal:'TIDAL', qobuz:'Qobuz', deezer:'Deezer', amazon:'Amazon', 'internet-archive':'Archive' };

    function detectSource(src){
      if(!src) return null;
      var s = src.toLowerCase();
      if(s.includes('tidal.com') || s.includes('tidal-hifi') || s.includes('listen.tidal')) return 'tidal';
      if(s.includes('qobuz.com') || s.includes('static.qobuz')) return 'qobuz';
      if(s.includes('deezer.com') || s.includes('dzcdn') || s.includes('deemix')) return 'deezer';
      if(s.includes('amazon') || s.includes('cloudfront') || s.includes('cloudfront.net')) return 'amazon';
      if(s.includes('archive.org') || s.includes('internetarchive') || s.includes('ia8')) return 'internet-archive';
      return null;
    }

    function detectSourceFromPage(){
      try {
        var el = document.querySelector('[class*="source"], [data-source], [data-service]');
        if(el){
          var text = (el.textContent || el.getAttribute('data-source') || el.getAttribute('data-service') || '').toLowerCase();
          if(text.includes('tidal')) return 'tidal';
          if(text.includes('qobuz')) return 'qobuz';
          if(text.includes('deezer')) return 'deezer';
          if(text.includes('amazon')) return 'amazon';
          if(text.includes('archive')) return 'internet-archive';
        }
      } catch(e){}
      return null;
    }

    function createIndicator(){
      if(document.getElementById('mc-source-badge')) return;
      var badge = document.createElement('div');
      badge.id = 'mc-source-badge';
      badge.style.cssText = 'position:fixed;bottom:80px;left:12px;z-index:99999;font-size:10px;padding:2px 6px;border-radius:4px;color:#fff;font-weight:600;pointer-events:none;opacity:0;transition:opacity 0.3s;font-family:system-ui,sans-serif;text-transform:uppercase;letter-spacing:0.5px;';
      document.body.appendChild(badge);
    }

    function updateBadge(){
      var badge = document.getElementById('mc-source-badge');
      if(!badge) return;
      var src = '';
      try { var a = document.querySelector('audio,video'); if(a) src = a.src || a.currentSrc || ''; } catch(e){}
      var key = detectSource(src) || detectSourceFromPage();
      if(key){
        badge.textContent = _srcLabels[key] || key;
        badge.style.backgroundColor = _srcColors[key] || _srcColors.default;
        badge.style.opacity = '0.85';
      } else {
        badge.style.opacity = '0';
      }
    }

    createIndicator();
    var _srcObs = new MutationObserver(function(){ requestAnimationFrame(updateBadge); });
    _srcObs.observe(document.documentElement, { childList:true, subtree:true, attributes:true, attributeFilter:['src'] });

    var _origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function(){
      var self = this;
      setTimeout(updateBadge, 100);
      return _origPlay.apply(this, arguments);
    };

    setInterval(updateBadge, 2000);
    updateBadge();
  }

  console.log('Monochrome Hooks Injected');
})();
"""

    const val APPLE_MUSIC_CSS = """
(function(){
  if(window.__mcAppleCSS) return;
  window.__mcAppleCSS = true;
  var s = document.createElement('style');
  s.id = 'mc-apple-css';
  s.textContent = `
    * { -webkit-tap-highlight-color: transparent; }
    body { -webkit-font-smoothing: antialiased; -moz-osx-font-smoothing: grayscale; }
    ::-webkit-scrollbar { width: 0; height: 0; }
    img { image-rendering: -webkit-optimize-contrast; }
    .card, .album-card, .playlist-card, [class*="card"] {
      border-radius: 12px !important;
      transition: transform 0.2s ease, box-shadow 0.2s ease !important;
    }
    .card:active, .album-card:active, .playlist-card:active, [class*="card"]:active {
      transform: scale(0.97) !important;
    }
    button, [role="button"] {
      -webkit-tap-highlight-color: transparent;
      transition: opacity 0.15s ease !important;
    }
    button:active, [role="button"]:active {
      opacity: 0.7 !important;
    }
    img[loading="lazy"] {
      background: linear-gradient(135deg, #1a1a1a 0%, #2a2a2a 100%);
    }
    input, textarea {
      -webkit-appearance: none;
      border-radius: 10px !important;
    }
    input:focus, textarea:focus {
      outline: none;
      box-shadow: 0 0 0 2px rgba(255,255,255,0.15) !important;
    }
    a { text-decoration: none !important; }
    [class*="player"], [class*="Player"] {
      backdrop-filter: blur(40px) saturate(180%) !important;
      -webkit-backdrop-filter: blur(40px) saturate(180%) !important;
    }
    [class*="progress"], [class*="slider"] {
      -webkit-appearance: none !important;
      height: 4px !important;
      border-radius: 2px !important;
    }
    ::selection {
      background: rgba(255,255,255,0.2);
    }

    /* Player action buttons - reorder: hide cast, put queue first */
    [class*="player-actions"], [class*="player"] [class*="actions"], [class*="player"] [class*="controls-row"] {
      display: flex !important;
      align-items: center !important;
      gap: 4px !important;
    }
    [class*="player"] button[class*="cast"], [class*="player"] button[class*="chromecast"],
    [class*="player"] [class*="cast"], [class*="player"] [data-action="cast"] {
      order: 99 !important;
      opacity: 0.4 !important;
    }

    /* Bottom panel alignment */
    [class*="bottom-bar"], [class*="bottom-nav"], [class*="bottom-panel"],
    [class*="player-bar"], [class*="now-playing-bar"], [class*="mini-player"] {
      position: fixed !important;
      bottom: 0 !important;
      left: 0 !important;
      right: 0 !important;
      z-index: 9999 !important;
      padding-bottom: env(safe-area-inset-bottom, 0) !important;
    }

    /* Cast button - bottom right corner */
    [class*="cast"], [class*="chromecast"], [data-action="cast"],
    button[aria-label*="cast" i], button[aria-label*="Cast" i] {
      position: fixed !important;
      bottom: 80px !important;
      right: 12px !important;
      z-index: 10000 !important;
      opacity: 0.7 !important;
    }

    /* Safe area padding for notched devices */
    @supports (padding-bottom: env(safe-area-inset-bottom)) {
      [class*="player"], [class*="Player"], [class*="bottom"] {
        padding-bottom: env(safe-area-inset-bottom) !important;
      }
    }

    /* Smooth content load */
    @keyframes fadeIn { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
    [class*="home"] > *, [class*="content"] > *, main > * {
      animation: fadeIn 0.25s ease both;
    }

    /* Reduce motion for performance */
    @media (prefers-reduced-motion: reduce) {
      *, *::before, *::after {
        animation-duration: 0.01ms !important;
        transition-duration: 0.01ms !important;
      }
    }
  `;
  document.head.appendChild(s);
})();
"""
}
