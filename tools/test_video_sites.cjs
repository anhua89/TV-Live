// Bounded DOM logic fixtures, not real Youku/Migu playback or renderer tests.
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/video_sites_tv.js'), 'utf8');
function fixture() {
    let clock = 100000, reads = 0, resizes = 0;
    const nodes = [], listeners = {}, videos = [], frames = [];
    const doc = {title: '视频测试页',
        head: {appendChild(node) { nodes.push(node); }},
        querySelector(q) { return q === 'meta[name="viewport"]' ? nodes.find(x => x.name === 'viewport') || null : null; },
        getElementById(id) { return nodes.find(x => x.id === id) || null; },
        createElement(tag) { return {tagName: tag.toUpperCase()}; },
        createEvent() { return {initEvent(name) { this.type = name; }}; },
        getElementsByTagName(tag) { return tag === 'video' ? videos : tag === 'iframe' ? frames : []; },
        addEventListener(name, callback) { (listeners[name] ||= []).push(callback); }
    };
    const win = {document: doc, innerWidth: 1920, MediaSource: function () {}, dispatchEvent() { resizes++; }};
    const context = vm.createContext({window: win, document: doc, Promise, Date: {now: () => clock}});
    vm.runInContext(script, context);
    function video(options = {}) {
        const v = Object.assign({currentTime: 0, frames: 0, paused: true, readyState: 0, networkState: 0,
            src: '', currentSrc: '', width: 640, height: 360, videoWidth: 0, videoHeight: 0, controls: false,
            getBoundingClientRect() { reads++; return {width: this.width, height: this.height}; },
            getVideoPlaybackQuality() { return {totalVideoFrames: this.frames}; },
            play() { this.paused = false; }, pause() { this.paused = true; }
        }, options);
        videos.push(v); return v;
    }
    return {api: win.__jianshiTv, doc, win, nodes, frames, listeners, video,
        reads: () => reads, resizes: () => resizes, advance(ms) { clock += ms; }, injectAgain() { vm.runInContext(script, context); }};
}
test('Migu desktop shrinks to its own 1920 viewport and notifies dynamic layout once', () => {
    const f = fixture(); f.api.layout(1920); f.api.layout(1920); f.injectAgain();
    assert.equal(f.nodes.find(x => x.name === 'viewport').content, 'width=1920, user-scalable=yes');
    assert.equal(f.resizes(), 1); assert.equal(f.nodes.length, 2); assert.equal(f.listeners.click.length, 1);
});
test('mobile layout releases desktop viewport and its zoom lock', () => {
    const f = fixture(); f.api.layout(1920); f.api.layout(0);
    assert.equal(f.nodes.find(x => x.name === 'viewport').content, 'width=device-width, initial-scale=1, user-scalable=yes');
});
test('large dynamic pages have bounded video layout reads and frame traversal', () => {
    const f = fixture(); for (let i = 0; i < 200; i++) f.video();
    for (let i = 0; i < 30; i++) f.frames.push({get contentDocument() { throw new Error('cross-origin'); }});
    const s = f.api.snapshot(); assert.equal(f.reads(), 8); assert.equal(s.videos, 8);
    assert.equal(s.iframes, 30); assert.equal(s.blockedFrames, 4); assert.equal(s.moving, false);
});
test('page load and video metadata alone never count as playback', () => {
    const f = fixture(), v = f.video({currentSrc: 'blob:source', readyState: 1, networkState: 2});
    const s = f.api.snapshot(); assert.equal(s.hasSource, true); assert.equal(s.ready, 1); assert.equal(s.moving, false);
    v.readyState = 4; v.paused = false; assert.equal(f.api.snapshot().moving, false);
    v.currentTime = 1; f.advance(1000); assert.equal(f.api.snapshot().moving, true);
    f.advance(7000); assert.equal(f.api.snapshot().moving, false);
});
test('no video reports missing player instead of manufacturing a play button', () => {
    const f = fixture(); assert.equal(f.api.play().ok, false); assert.equal(f.api.snapshot().hasSource, false);
});
test('user playback exposes existing controls and retains rejected playback error', async () => {
    const f = fixture(), v = f.video({play() { return Promise.reject({name: 'NotSupportedError'}); }});
    f.api.play(); await Promise.resolve(); assert.equal(v.controls, true);
    assert.equal(f.api.snapshot().playError, 'NotSupportedError');
});
test('hidden preloads are not treated as the visible program and media errors survive', () => {
    const f = fixture(); f.video({width: 0, height: 0, paused: false, readyState: 4});
    f.video({error: {code: 4}}); assert.equal(f.api.snapshot().error, 4); assert.equal(f.api.snapshot().moving, false);
});
test('public new-window links remain in the webpage', () => {
    const f = fixture(), link = {tagName: 'A', href: 'https://v.youku.com/v_show/id_X123.html', target: '_blank'};
    f.listeners.click[0]({target: link}); assert.equal(link.target, '_self');
});
