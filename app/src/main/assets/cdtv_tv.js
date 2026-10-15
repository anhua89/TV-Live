(function () {
    'use strict';
    if (window.__jianshiTv) return;
    var state = {playError: '', liveLocated: false, activeVideo: null, lastTime: -1, lastFrames: -1, movingAt: 0};
    function shortText(value) { return String(value || '').replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, ''); }
    function documents() {
        var result = [], blocked = 0, frames = 0;
        function visit(doc, depth) {
            if (!doc || depth > 3 || result.length >= 16) return;
            result.push(doc);
            var list = doc.querySelectorAll('iframe'); frames += list.length;
            for (var i = 0; i < list.length && i < 16; i++) {
                try {
                    var child = list[i].contentDocument;
                    if (child) visit(child, depth + 1); else blocked++;
                } catch (ignore) { blocked++; }
            }
        }
        visit(document, 0);
        return {list: result, blocked: blocked, frames: frames};
    }
    function media() {
        var docs = documents(), videos = [], best = null, area = -1;
        for (var d = 0; d < docs.list.length; d++) {
            var nodes = docs.list[d].querySelectorAll('video');
            for (var i = 0; i < nodes.length && videos.length < 64; i++) {
                var v = nodes[i], r = v.getBoundingClientRect(), a = Math.max(0, r.width * r.height);
                videos.push(v);
                // Prefer an actively playing visible video to a hidden advertisement/preload element.
                if (!v.paused && v.readyState >= 2 && a > 0) a += 100000000;
                if (a > 0 && a > area) { area = a; best = v; }
            }
        }
        return {videos: videos, best: best, frames: docs.frames, blocked: docs.blocked};
    }
    function desktop() {
        var head = document.head || document.documentElement;
        var viewport = document.querySelector('meta[name="viewport"]');
        if (!viewport) { viewport = document.createElement('meta'); viewport.name = 'viewport'; head.appendChild(viewport); }
        // The screenshot shows the desktop site, whose central column is roughly 1200 CSS pixels.
        viewport.content = 'width=1280, user-scalable=yes';
        var style = document.getElementById('jianshi-tv-layout');
        if (!style) { style = document.createElement('style'); style.id = 'jianshi-tv-layout'; head.appendChild(style); }
        style.textContent = 'html,body{-webkit-text-size-adjust:100%!important;text-size-adjust:100%!important}';
        return true;
    }
    function focusLive(channel, force) {
        if (state.liveLocated && !force) return true;
        var list = document.querySelectorAll('a,button,li,span,div'), chosen = null;
        var number = channel > 0 && channel <= 8 ? channel : 1;
        var pattern = new RegExp('^CDTV\\s*[-－]?\\s*' + number + '(?:\\s|新闻|经济|都市|影视|公共|少儿|$)', 'i');
        for (var i = 0; i < list.length; i++) {
            var el = list[i], label = shortText(el.textContent), rect = el.getBoundingClientRect();
            if (label.length < 60 && rect.width > 0 && rect.height > 0 && pattern.test(label) &&
                    !el.querySelector('a,button,li,span,div')) { chosen = el; break; }
        }
        if (!chosen) return false;
        var r = chosen.getBoundingClientRect();
        window.scrollTo(0, Math.max(0, window.pageYOffset + r.top - 32));
        if (channel > 0 && chosen.click) chosen.click();
        state.liveLocated = true;
        return true;
    }
    function play() {
        var found = media(), video = found.best;
        if (!video) return {ok: false, detail: found.blocked ? '播放器位于独立网页，请用光标点击网页播放按钮' : '尚未找到视频，请先在网页里选择频道'};
        state.playError = '';
        try {
            video.controls = true;
            var result = video.play();
            if (result && result.catch) result.catch(function (e) { state.playError = String(e.name || 'PlayRejected'); });
            return {ok: true, detail: '已请求网页播放，正在检测视频进度'};
        } catch (e) { state.playError = String(e.name || 'PlayRejected'); return {ok: false, detail: '网页未能开始播放'}; }
    }
    function fullscreen() {
        var v = media().best;
        if (!v) return {ok: false, detail: '请用光标点击网页播放器的全屏按钮'};
        var request = v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen;
        if (!request) return {ok: false, detail: '网页未提供可调用的全屏功能'};
        try {
            var result = request.call(v);
            if (result && result.catch) result.catch(function () { state.playError = 'FullscreenNeedsClick'; });
            return {ok: true, detail: '已请求全屏；如未进入，请点击网页内的全屏按钮'};
        } catch (e) { return {ok: false, detail: '请用光标点击网页播放器的全屏按钮'}; }
    }
    function pause() {
        var found = media();
        for (var i = 0; i < found.videos.length; i++) { try { found.videos[i].pause(); } catch (ignore) {} }
    }
    function snapshot() {
        var found = media(), v = found.best, time = v && isFinite(v.currentTime) ? v.currentTime : -1, frames = -1;
        if (v) {
            try { frames = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality().totalVideoFrames : (typeof v.webkitDecodedFrameCount === 'number' ? v.webkitDecodedFrameCount : -1); } catch (ignore) {}
            if (state.activeVideo !== v) { state.activeVideo = v; state.lastTime = -1; state.lastFrames = -1; state.movingAt = 0; }
            if (!v.paused && v.readyState >= 2 && ((state.lastTime >= 0 && time > state.lastTime + 0.05) || (state.lastFrames >= 0 && frames > state.lastFrames))) state.movingAt = Date.now();
            state.lastTime = time; state.lastFrames = frames;
        } else { state.activeVideo = null; state.movingAt = 0; }
        return {title: shortText(document.title).slice(0, 100), width: window.innerWidth,
            videos: found.videos.length, iframes: found.frames, blockedFrames: found.blocked,
            ready: v ? v.readyState : 0, paused: v ? v.paused : true, time: time, frames: frames,
            error: v && v.error ? v.error.code : 0, playError: state.playError,
            moving: !!(v && !v.paused && v.readyState >= 2 && state.movingAt && Date.now() - state.movingAt < 6000),
            liveLocated: state.liveLocated};
    }
    // Keep ordinary target=_blank links inside this window; scripted popups use Android's window handler.
    document.addEventListener('click', function (event) {
        var node = event.target;
        while (node && node.tagName !== 'A') node = node.parentElement;
        if (node && /^https?:/i.test(node.href) && node.target === '_blank') node.target = '_self';
    }, true);
    window.__jianshiTv = {desktop: desktop, focusLive: focusLive, play: play, fullscreen: fullscreen, pause: pause, snapshot: snapshot};
}());
