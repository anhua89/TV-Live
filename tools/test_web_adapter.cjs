// Logic regressions for the shipped WebView adapter. These DOM fixtures are not TV playback tests.
const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../app/src/main/assets/cdtv_tv.js'), 'utf8');

function fixture() {
    let clock = 1000000;
    const listeners = {};
    let win;
    function matches(node, selector) {
        if (selector === 'meta[name="viewport"]') return node.tagName === 'META' && node.name === 'viewport';
        return selector.split(',').some(tag => node.tagName === tag.trim().toUpperCase());
    }
    function descendants(node) { return node.children.flatMap(child => [child, ...descendants(child)]); }
    function element(tag, text = '', options = {}) {
        return Object.assign({tagName: tag.toUpperCase(), textContent: text, children: [], parentElement: null,
            width: 320, height: 60, top: 0,
            appendChild(child) { child.parentElement = this; this.children.push(child); return child; },
            querySelectorAll(selector) { return descendants(this).filter(node => matches(node, selector)); },
            querySelector(selector) { return this.querySelectorAll(selector)[0] || null; },
            getBoundingClientRect() { return {width: this.width, height: this.height, top: this.top - win.pageYOffset}; },
            click() { this.clicked = (this.clicked || 0) + 1; }
        }, options);
    }
    const html = element('html'), head = element('head'), body = element('body');
    html.appendChild(head); html.appendChild(body);
    const doc = {
        title: '电视直播测试页', documentElement: html, head, body,
        createElement: tag => element(tag),
        getElementById: id => descendants(html).find(node => node.id === id) || null,
        querySelectorAll: selector => html.querySelectorAll(selector),
        querySelector: selector => html.querySelector(selector),
        addEventListener(name, callback) { (listeners[name] ||= []).push(callback); }
    };
    win = {document: doc, innerWidth: 960, pageYOffset: 0, scrollTo(x, y) { this.pageYOffset = y; }};
    const context = vm.createContext({window: win, document: doc, Date: {now: () => clock}, Promise});
    vm.runInContext(script, context);
    function video(options = {}) {
        const v = element('video', '', Object.assign({width: 640, height: 360, currentTime: 0, readyState: 4, paused: true,
            frames: 0, error: null, controls: false,
            getVideoPlaybackQuality() { return {totalVideoFrames: this.frames}; },
            play() { this.paused = false; }, pause() { this.paused = true; }
        }, options));
        body.appendChild(v); return v;
    }
    return {doc, win, context, element, video, listeners, api: win.__jianshiTv,
        advance(ms) { clock += ms; }, injectAgain() { vm.runInContext(script, context); }};
}

test('desktop layout replaces a phone viewport and prevents automatic text enlargement', () => {
    const f = fixture(), meta = f.element('meta', '', {name: 'viewport', content: 'width=device-width,initial-scale=1'});
    f.doc.head.appendChild(meta); f.api.desktop();
    assert.equal(meta.content, 'width=1280, user-scalable=yes');
    assert.match(f.doc.getElementById('jianshi-tv-layout').textContent, /text-size-adjust:100%/);
});
test('repeated adapter injection does not duplicate styles or click listeners', () => {
    const f = fixture(); f.api.desktop(); f.injectAgain(); f.api.desktop();
    assert.equal(f.doc.querySelectorAll('meta').length, 1);
    assert.equal(f.doc.querySelectorAll('style').length, 1);
    assert.equal(f.listeners.click.length, 1);
});
test('home entry locates live section without clicking a different channel', () => {
    const f = fixture(), button = f.element('button', 'CDTV-1 新闻综合频道', {top: 1000}); f.doc.body.appendChild(button);
    assert.equal(f.api.focusLive(0), true); assert.equal(f.win.pageYOffset, 968); assert.equal(button.clicked, undefined);
    f.win.scrollTo(0, 0); assert.equal(f.api.focusLive(0, true), true); assert.equal(f.win.pageYOffset, 968);
});
test('channel selection uses actual visible labels, not guessed URLs', () => {
    const f = fixture(), one = f.element('button', 'CDTV-1 新闻综合频道'), two = f.element('button', 'CDTV-2 经济资讯频道');
    f.doc.body.appendChild(one); f.doc.body.appendChild(two); assert.equal(f.api.focusLive(2), true);
    assert.equal(two.clicked, 1); assert.equal(one.clicked, undefined);
});
test('a loaded page without video is never reported as playing', () => {
    const f = fixture(), s = f.api.snapshot(); assert.equal(s.videos, 0); assert.equal(s.moving, false);
    assert.equal(f.api.play().ok, false);
});
test('paused and frozen media do not count as video progress', () => {
    const f = fixture(), v = f.video({currentTime: 10}); assert.equal(f.api.snapshot().moving, false);
    v.paused = false; assert.equal(f.api.snapshot().moving, false); f.advance(2000); assert.equal(f.api.snapshot().moving, false);
});
test('media must advance before playing status, and a later stall clears the status', () => {
    const f = fixture(), v = f.video({paused: false}); assert.equal(f.api.snapshot().moving, false);
    v.currentTime = 1; f.advance(1000); assert.equal(f.api.snapshot().moving, true);
    f.advance(7000); assert.equal(f.api.snapshot().moving, false);
});
test('unsupported-media error is retained for the native diagnostic panel', () => {
    const f = fixture(); f.video({error: {code: 4}}); assert.equal(f.api.snapshot().error, 4);
});
test('autoplay rejection is visible instead of being silently swallowed', async () => {
    const f = fixture(); f.video({play() { return Promise.reject(Object.assign(new Error('blocked'), {name: 'NotAllowedError'})); }});
    assert.equal(f.api.play().ok, true); await Promise.resolve(); assert.equal(f.api.snapshot().playError, 'NotAllowedError');
});
test('ordinary new-tab links are routed within the current web window', () => {
    const f = fixture(), a = f.element('a', 'CDTV-1', {href: 'https://cditv.cn/live', target: '_blank'});
    f.listeners.click[0]({target: a}); assert.equal(a.target, '_self');
});
test('separate frames are reported without claiming their videos are verified', () => {
    const f = fixture(), frame = f.element('iframe');
    Object.defineProperty(frame, 'contentDocument', {get() { throw new Error('cross-origin'); }});
    f.doc.body.appendChild(frame); const s = f.api.snapshot();
    assert.equal(s.iframes, 1); assert.equal(s.blockedFrames, 1); assert.equal(s.moving, false);
});
test('a hidden playing advertisement does not override visible paused program video', () => {
    const f = fixture(), ad = f.video({width: 0, height: 0, paused: false}), program = f.video({paused: true});
    f.api.snapshot(); ad.currentTime = 1; const s = f.api.snapshot();
    assert.equal(s.videos, 2); assert.equal(s.paused, program.paused); assert.equal(s.moving, false);
});
