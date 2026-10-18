/* LiveWebMap virtual folder bridge (direct in-game GPS).
 * Runs ONLY in the top-level https://projectzomboidmap.com/ renderer.
 * Supplies the existing website GPS implementation with its expected
 * Zomboid/Lua/pzm_live/live.txt directory tree via File System Access API.
 * No custom map marker and no remote API calls.
 */
(function () {
  'use strict';
  if (window.top !== window || !/^https:\/\/(www\.)?projectzomboidmap\.com$/i.test(location.origin)) return;
  if (window.__lwmGpsInstalled) {
    if (typeof window.liveWebMapGpsQuery === 'function') {
      window.liveWebMapGpsQuery({request: 'LWM_GPS:READY:already-installed'});
    }
    return;
  }
  window.__lwmGpsInstalled = true;

  function signal(message) {
    try {
      if (typeof window.liveWebMapGpsQuery === 'function') {
        window.liveWebMapGpsQuery({request: 'LWM_GPS:' + message});
      }
    } catch (_) { /* diagnostic only */ }
  }

  const state = { folder: null, liveDirectory: null, mode: 'initializing',
                  content: null, inFlight: Promise.resolve() };

  // Fallback FileSystemHandle implementation if OPFS is disabled in this CEF
  // build. Unlike OPFS handles, fallback handles cannot be saved to IndexedDB.
  function virtualFile(name) {
    return {
      kind: 'file', name,
      async getFile() {
        if (name === 'live.txt' && state.content === null) {
          throw new DOMException('Live GPS position not available', 'NotFoundError');
        }
        const content = name === 'live.txt' ? state.content : '';
        return new File([content], name, {type: 'text/plain', lastModified: Date.now()});
      },
      async queryPermission() { return 'granted'; },
      async requestPermission() { return 'granted'; },
      async isSameEntry(other) { return other === this; }
    };
  }

  function virtualDirectory(name, children) {
    const dir = {
      kind: 'directory', name,
      async getDirectoryHandle(child) {
        if (children[child] && children[child].kind === 'directory') return children[child];
        throw new DOMException('Directory not found: ' + child, 'NotFoundError');
      },
      async getFileHandle(child) {
        if (children[child] && children[child].kind === 'file') return children[child];
        throw new DOMException('File not found: ' + child, 'NotFoundError');
      },
      async *entries() {
        for (const [child, handle] of Object.entries(children)) {
          if (child === 'live.txt' && state.content === null) continue;
          yield [child, handle];
        }
      },
      async *values() { for await (const [, handle] of this.entries()) yield handle; },
      async *keys() { for await (const [child] of this.entries()) yield child; },
      async queryPermission() { return 'granted'; },
      async requestPermission() { return 'granted'; },
      async isSameEntry(other) { return other === this; },
      async resolve(other) {
        if (other === this) return [];
        for await (const [child, handle] of this.entries()) {
          if (handle === other) return [child];
          if (handle.kind === 'directory') {
            const inside = await handle.resolve(other);
            if (inside !== null) return [child].concat(inside);
          }
        }
        return null;
      }
    };
    return dir;
  }

  async function getFolder() {
    if (state.folder) return state.folder;
    try {
      if (navigator.storage && typeof navigator.storage.getDirectory === 'function') {
        const originStorage = await navigator.storage.getDirectory();
        const zomboid = await originStorage.getDirectoryHandle('Zomboid', {create: true});
        const lua = await zomboid.getDirectoryHandle('Lua', {create: true});
        state.liveDirectory = await lua.getDirectoryHandle('pzm_live', {create: true});
        // Some websites validate the selected Zomboid folder by the existence
        // of console.txt. An empty placeholder avoids disclosing the real log.
        try {
          const consoleHandle = await zomboid.getFileHandle('console.txt', {create: true});
          const existing = await consoleHandle.getFile();
          if (existing.size === 0) {
            const writer = await consoleHandle.createWritable();
            await writer.write('LiveWebMap virtual Zomboid folder\n');
            await writer.close();
          }
        } catch (_) { /* nonessential */ }
        state.folder = zomboid;
        state.mode = 'opfs';
        return state.folder;
      }
      throw new Error('Origin Private File System unavailable');
    } catch (e) {
      const live = virtualFile('live.txt');
      const pzm = virtualDirectory('pzm_live', {'live.txt': live});
      const lua = virtualDirectory('Lua', {'pzm_live': pzm});
      state.folder = virtualDirectory('Zomboid', {
        'Lua': lua, 'console.txt': virtualFile('console.txt')
      });
      state.liveDirectory = pzm;
      state.mode = 'fallback';
      signal('ERROR:OPFS unavailable; using nonpersistent folder shim (' + String(e).slice(0, 100) + ')');
      return state.folder;
    }
  }

  // Overriding ONLY this browser page's picker means its original GPS code
  // follows its original code path, including parsing the actual live.txt.
  window.showDirectoryPicker = async function () {
    const folder = await getFolder();
    signal('PICKED:' + state.mode);
    return folder;
  };

  // Called by the Java game integration when the in-game player GPS sample changes.
  // Serialized to avoid overlapping OPFS writes when the player is moving.
  window.__lwmGpsPush = function (base64) {
    state.inFlight = state.inFlight.then(async function () {
      const text = base64 === null ? null : new TextDecoder().decode(
        Uint8Array.from(atob(base64), c => c.charCodeAt(0)));
      state.content = text;
      await getFolder();
      if (state.mode !== 'opfs') return;
      if (text === null) {
        try { await state.liveDirectory.removeEntry('live.txt'); }
        catch (_) { /* absent is normal */ }
        return;
      }
      const file = await state.liveDirectory.getFileHandle('live.txt', {create: true});
      const writer = await file.createWritable();
      await writer.write(text);
      await writer.close();
      if (!state.reportedFirstWrite) {
        state.reportedFirstWrite = true;
        signal('WRITE:first');
      }
    }).catch(function (e) { signal('ERROR:Failed to update GPS file: ' + String(e).slice(0, 140)); });
  };

  signal('READY:picker-shim-installed');
  // Create native OPFS handles early. Site picker can also call getFolder().
  getFolder().then(function () { signal('READY:' + state.mode); });
})();
