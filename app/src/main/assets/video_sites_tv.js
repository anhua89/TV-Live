(function () {
    'use strict';
    if (window.__jianshiTv) return;
    var state = {active: null, time: -1, frames: -1, movingAt: 0, playError: ''};
    function layout(width) {
        var head = document.head;
        if (!head) return false;
        var meta = document.querySelector('meta[name="viewport"]');
        if (!meta) { meta = document.createElement('meta'); meta.name = 'viewport'; head.appendChild(meta); }
        var value = width > 0 ? 'width=' + width + ', user-scalable=yes' : 'width=device-width, initial-scale=1, user-scalable=yes';
        var changed = meta.content !== value;
        if (changed) meta.content = value;
        var style = document.getElementById('jianshi-video-layout');
        if (!style) { style = document.createElement('style'); style.id = 'jianshi-video-layout'; head.appendChild(style); }
        style.textContent = 'html,body{-webkit-text-size-adjust:100%!important;text-size-adjust:100%!important}';
        if (changed && window.dispatchEvent && document.createEvent) {
            var event = document.createEvent('Event'); event.initEvent('resize', false, false); window.dispatchEvent(event);
        }
        return true;
    }
    function media() {
        var docs = [document], list = [], blocked = 0, frameCount = 0, best = null, bestArea = 0;
        // Bound both frame traversal and layout reads on large, dynamic video websites.
        var frames = document.getElementsByTagName('iframe'); frameCount = frames.length;
        for (var i = 0; i < frames.length && i < 4; i++) {
            try { if (frames[i].contentDocument) docs.push(frames[i].contentDocument); else blocked++; }
            catch (ignore) { blocked++; }
        }
        for (var d = 0; d < docs.length && list.length < 8; d++) {
            var nodes = docs[d].getElementsByTagName('video');
            for (var j = 0; j < nodes.length && list.length < 8; j++) {
                var v = nodes[j], rect = v.getBoundingClientRect(), area = Math.max(0, rect.width * rect.height);
                list.push(v);
                if (area > 0 && !v.paused && v.readyState >= 2) area += 100000000;
                if (area > bestArea) { best = v; bestArea = area; }
            }
        }
        return {videos: list, best: best, frames: frameCount, blocked: blocked};
    }
    function snapshot() {
        var m = media(), v = m.best, time = v && isFinite(v.currentTime) ? v.currentTime : -1, frames = -1;
        if (v) {
            try { frames = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality().totalVideoFrames : (typeof v.webkitDecodedFrameCount === 'number' ? v.webkitDecodedFrameCount : -1); } catch (ignore) {}
            if (state.active !== v) { state.active = v; state.time = -1; state.frames = -1; state.movingAt = 0; state.playError = ''; }
            if (!v.paused && v.readyState >= 2 && ((state.time >= 0 && time > state.time + 0.05) || (state.frames >= 0 && frames > state.frames))) state.movingAt = Date.now();
            state.time = time; state.frames = frames;
        } else { state.active = null; state.movingAt = 0; }
        return {title: String(document.title || '').slice(0, 100), width: window.innerWidth,
            videos: m.videos.length, iframes: m.frames, blockedFrames: m.blocked,
            ready: v ? v.readyState : 0, paused: v ? v.paused : true, time: time, frames: frames,
            network: v ? v.networkState : -1, hasSource: !!(v && (v.currentSrc || v.src)),
            videoWidth: v ? v.videoWidth : 0, videoHeight: v ? v.videoHeight : 0,
            mse: !!(window.MediaSource || window.WebKitMediaSource), error: v && v.error ? v.error.code : 0,
            playError: state.playError, moving: !!(v && !v.paused && v.readyState >= 2 && state.movingAt && Date.now() - state.movingAt < 6000)};
    }
    function play() {
        var v = media().best;
        if (!v) return {ok: false, detail: '网页播放器尚未创建；请从播放修复切换手机网页或重新打开'};
        if (state.active !== v) { state.active = v; state.time = -1; state.frames = -1; state.movingAt = 0; }
        state.playError = '';
        try {
            v.controls = true;
            var result = v.play();
            if (result && result.catch) result.catch(function (e) { state.playError = String(e.name || 'PlayRejected'); });
            return {ok: true, detail: '已请求播放并显示视频控制条；仍需等待网站提供视频数据'};
        } catch (e) { state.playError = String(e.name || 'PlayRejected'); return {ok: false, detail: '网页未能开始播放，可打开播放修复'}; }
    }
    function fullscreen() {
        var v = media().best, request = v && (v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen);
        if (!request) return {ok: false, detail: '请先选择节目，或点击网站播放器自己的全屏按钮'};
        try {
            var result = request.call(v);
            if (result && result.catch) result.catch(function () { state.playError = 'FullscreenNeedsClick'; });
            return {ok: true, detail: '已请求网页全屏；如未进入，请点击视频内部全屏按钮'};
        } catch (ignore) { return {ok: false, detail: '请点击网站播放器自己的全屏按钮'}; }
    }
    function pause() { var list = media().videos; for (var i = 0; i < list.length; i++) { try { list[i].pause(); } catch (ignore) {} } }
    document.addEventListener('click', function (event) {
        var node = event.target;
        while (node && node.tagName !== 'A') node = node.parentElement;
        if (node && /^https?:/i.test(node.href) && node.target === '_blank') node.target = '_self';
    }, true);
    window.__jianshiTv = {layout: layout, snapshot: snapshot, play: play, fullscreen: fullscreen, pause: pause};
}());
