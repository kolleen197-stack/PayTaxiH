const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');

const staticRoot = path.resolve(__dirname, '../../main/resources/static');
const projectRoot = path.resolve(__dirname, '../../..');
const htmlFiles = fs.readdirSync(staticRoot).filter(file => file.endsWith('.html'));
const engineSource = fs.readFileSync(path.join(staticRoot, 'app_engine.js'), 'utf8');

test('PayTaxiHub PTH logo is wired into public and admin navigation', () => {
  const mark = fs.readFileSync(path.join(staticRoot, 'pth-monogram.svg'), 'utf8');
  const styles = fs.readFileSync(path.join(staticRoot, 'professional.css'), 'utf8');
  const admin = fs.readFileSync(path.join(staticRoot, 'admin.html'), 'utf8');

  assert.match(mark, /<svg\b[^>]*viewBox="0 0 56 56"/);
  assert.match(mark, /#FBBF24/);
  assert.match(styles, /\.pth-logo\{[^}]*font-family:"Space Grotesk"/);
  assert.match(styles, /\.pth-logo>span\{[^}]*#956300/);
  assert.match(styles, /\.pth-logo-mark\{[^}]*42px/);
  assert.match(admin, /\.logo\{[^}]*font-family:"Space Grotesk"/);
  assert.match(admin, /\.logo>span\{[^}]*#956300/);
  assert.match(admin, /class="logo-mark"[^>]+pth-monogram\.svg/);
  for (const file of htmlFiles.filter(file => file !== 'admin.html')) {
    const html = fs.readFileSync(path.join(staticRoot, file), 'utf8');
    if (html.includes('class="pth-logo"')) {
      assert.match(html, /class="pth-logo-mark"[^>]+pth-monogram\.svg/, `${file} should use the PTH monogram`);
      assert.match(html, />Pay<span>Taxi<\/span>Hub<\/a>/, `${file} should pair the mark with the full brand name`);
    }
  }
  assert.match(styles, /:focus-visible\{outline:3px solid/);
});

test('production pages use locally built Tailwind styles, not the runtime CDN', () => {
  const css = fs.readFileSync(path.join(staticRoot, 'tailwind.generated.css'), 'utf8');
  assert.match(css, /\.flex\{display:flex\}/);
  assert.match(css, /\.hidden\{display:none\}/);

  for (const file of htmlFiles) {
    const html = fs.readFileSync(path.join(staticRoot, file), 'utf8');
    assert.doesNotMatch(html, /cdn\.tailwindcss\.com/i, `${file} must not load Tailwind from its CDN`);
    const isRedirect = /<meta\b[^>]*http-equiv\s*=\s*["']refresh["']/i.test(html);
    if (html.includes('professional.css') && !isRedirect) {
      assert.match(html, /href="tailwind\.generated\.css"/, `${file} should load the built Tailwind stylesheet`);
    }
  }
});

function makeEngineContext(overrides = {}) {
  const context = {
    location: { protocol: 'http:', origin: 'http://localhost', pathname: '/' },
    document: {
      addEventListener() {},
      querySelectorAll() { return []; }
    },
    window: { addEventListener() {} },
    URL,
    URLSearchParams,
    setTimeout,
    console,
    ...overrides
  };
  vm.createContext(context);
  vm.runInContext(engineSource, context, { filename: 'app_engine.js' });
  return context;
}

test('every inline browser script parses', () => {
  assert.ok(htmlFiles.length > 0, 'static pages should be present');
  for (const file of htmlFiles) {
    const html = fs.readFileSync(path.join(staticRoot, file), 'utf8');
    const scripts = html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi);
    let inlineIndex = 0;
    for (const [, attributes, source] of scripts) {
      if (/\bsrc\s*=/i.test(attributes) || !source.trim()) continue;
      assert.doesNotThrow(
        () => new vm.Script(source, { filename: `${file} inline script ${++inlineIndex}` }),
        `${file} inline script ${inlineIndex} should parse`
      );
    }
  }
});

test('local page, script, stylesheet, and fragment links resolve', () => {
  for (const file of htmlFiles) {
    const html = fs.readFileSync(path.join(staticRoot, file), 'utf8');
    for (const [, attribute, rawValue] of html.matchAll(/\b(href|src)\s*=\s*["']([^"']+)["']/gi)) {
      const value = rawValue.replace(/&amp;/g, '&').trim();
      if (!value || value.startsWith('#') || /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(value)) continue;

      const targetUrl = new URL(value, `http://localhost/${file}`);
      const targetFile = path.join(staticRoot, decodeURIComponent(targetUrl.pathname).replace(/^\/+/, ''));
      assert.ok(fs.existsSync(targetFile), `${file} ${attribute} target "${value}" should exist`);

      if (targetUrl.hash && targetUrl.hash !== '#') {
        const targetHtml = fs.readFileSync(targetFile, 'utf8');
        const fragment = decodeURIComponent(targetUrl.hash.slice(1));
        assert.match(targetHtml, new RegExp(`\\bid\\s*=\\s*["']${fragment.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}["']`),
          `${file} fragment "${value}" should identify an element`);
      }
    }
  }
});

test('form fields have programmatic accessible labels', () => {
  const failures = [];
  for (const file of htmlFiles) {
    const html = fs.readFileSync(path.join(staticRoot, file), 'utf8');
    for (const match of html.matchAll(/<(input|select|textarea)\b([^>]*)>/gi)) {
      const [, tag, attributes] = match;
      const type = attributes.match(/\btype\s*=\s*["']([^"']+)["']/i)?.[1]?.toLowerCase();
      if (['hidden', 'submit', 'button', 'reset', 'image'].includes(type)) continue;

      const id = attributes.match(/\bid\s*=\s*["']([^"']+)["']/i)?.[1];
      const hasAriaName = /\baria-label\s*=\s*["'][^"']+["']/i.test(attributes)
        || /\baria-labelledby\s*=\s*["'][^"']+["']/i.test(attributes);
      const hasForLabel = id && new RegExp(
        `<label\\b[^>]*\\bfor\\s*=\\s*["']${id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}["']`,
        'i'
      ).test(html);
      const openLabel = html.lastIndexOf('<label', match.index);
      const previousClose = html.lastIndexOf('</label>', match.index);
      const nextClose = html.indexOf('</label>', match.index);
      const isWrappedByLabel = openLabel > previousClose && nextClose > match.index;
      if (!hasAriaName && !hasForLabel && !isWrappedByLabel) {
        failures.push(`${file}: <${tag}${id ? ` id="${id}"` : ''}>`);
      }
    }
  }
  assert.deepEqual(failures, [], `Form fields need a label, aria-label, or aria-labelledby:\n${failures.join('\n')}`);
});

test('contact form prepares a properly encoded email without claiming it was sent', () => {
  const html = fs.readFileSync(path.join(staticRoot, 'contact.html'), 'utf8');
  const inlineScript = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script\s*>/gi)]
    .find(([, attributes, source]) => !/\bsrc\s*=/i.test(attributes) && source.includes('contactForm'))?.[2];
  assert.ok(inlineScript, 'contact page should handle form submission');

  let submitHandler;
  const values = {
    name: { value: 'Sam & Co' },
    email: { value: 'sam@example.com' },
    message: { value: 'Please help with my account? Thanks!' }
  };
  const form = {
    elements: { namedItem: name => values[name] },
    addEventListener(type, handler) {
      assert.equal(type, 'submit');
      submitHandler = handler;
    }
  };
  const status = { textContent: '' };
  const location = { href: '' };
  vm.runInNewContext(inlineScript, {
    document: {
      getElementById(id) {
        return id === 'contactForm' ? form : status;
      }
    },
    location
  });

  let prevented = false;
  submitHandler({ currentTarget: form, preventDefault() { prevented = true; } });
  const draft = new URL(location.href);
  assert.equal(prevented, true);
  assert.equal(draft.protocol, 'mailto:');
  assert.equal(draft.pathname, 'PTH.support@gmail.com');
  assert.equal(draft.searchParams.get('subject'), 'PayTaxiHub enquiry from Sam & Co');
  assert.equal(draft.searchParams.get('body'), 'Name: Sam & Co\nEmail: sam@example.com\n\nPlease help with my account? Thanks!');
  assert.match(status.textContent, /Review and send/i);
  assert.doesNotMatch(status.textContent, /sent successfully/i);
});

test('shared utility helpers normalize roles and escape untrusted text', () => {
  const context = makeEngineContext();
  assert.equal(context.normalizeRole('fleet owner'), 'FleetOwner');
  assert.equal(context.rolePath('Admin'), 'admin.html');
  assert.equal(context.money(15.5), 'R 15.50');
  assert.equal(context.escapeHtml(`<img src=x onerror="alert('x')">`),
    '&lt;img src=x onerror=&quot;alert(&#39;x&#39;)&quot;&gt;');
});

test('browser sessions stay tab-scoped and contain only the minimum UI identity', () => {
  const values = new Map();
  const localValues = new Map();
  const storage = map => ({
    getItem(key) { return map.get(key) ?? null; },
    setItem(key, value) { map.set(key, String(value)); },
    removeItem(key) { map.delete(key); }
  });
  const context = makeEngineContext({
    sessionStorage: storage(values),
    localStorage: storage(localValues)
  });

  const identity = context.setSession({
    userId: 41,
    fullName: 'Test Admin',
    email: 'private@example.test',
    phoneNumber: '0123456789',
    role: 'Admin'
  }, 'session-token');
  assert.deepEqual(JSON.parse(values.get('pth_session')), {
    userId: 41,
    role: 'Admin',
    fullName: 'Test Admin'
  });
  assert.equal(values.get('pth_token'), 'session-token');
  assert.deepEqual([...localValues], []);
  assert.equal(identity.email, undefined);
  context.clearSession();
  assert.equal(values.has('pth_token'), false);
  assert.equal(values.has('pth_session'), false);
});

test('sign out clears local state, asks the server to revoke, then replaces history', async () => {
  const values = new Map([
    ['pth_token', 'session-token'],
    ['pth_session', JSON.stringify({ userId: 41, role: 'Admin' })]
  ]);
  const localValues = new Map();
  const storage = map => ({
    getItem(key) { return map.get(key) ?? null; },
    setItem(key, value) { map.set(key, String(value)); },
    removeItem(key) { map.delete(key); }
  });
  let request;
  let destination;
  const context = makeEngineContext({
    sessionStorage: storage(values),
    localStorage: storage(localValues),
    location: {
      protocol: 'http:',
      origin: 'http://localhost',
      pathname: '/admin.html',
      replace(path) { destination = path; }
    },
    fetch: async (...args) => {
      request = args;
      return { ok: true };
    }
  });

  await context.logout();
  assert.equal(values.has('pth_token'), false);
  assert.equal(values.has('pth_session'), false);
  assert.equal(destination, 'login.html');
  assert.match(request[0], /\/api\/auth\/logout$/);
  assert.equal(request[1].method, 'POST');
  assert.equal(request[1].headers.Authorization, 'Bearer session-token');
  assert.equal(request[1].cache, 'no-store');
});

test('server sign-out failures are not reported as successful revocation', async () => {
  const values = new Map([['pth_token', 'session-token']]);
  let destination;
  const context = makeEngineContext({
    sessionStorage: {
      getItem(key) { return values.get(key) ?? null; },
      setItem(key, value) { values.set(key, String(value)); },
      removeItem(key) { values.delete(key); }
    },
    localStorage: { removeItem() {} },
    location: {
      protocol: 'http:',
      origin: 'http://localhost',
      pathname: '/admin.html',
      replace(path) { destination = path; }
    },
    fetch: async () => ({ ok: false, status: 500 })
  });

  await context.logout();
  assert.equal(values.get('pth_logout_warning'), 'true');
  assert.equal(values.has('pth_token'), false);
  assert.equal(destination, 'login.html');
});

test('auth pages disable field restoration and clear inputs on history navigation', () => {
  const values = new Map();
  const fields = [
    { value: 'remembered@example.test', type: 'text', checked: false },
    { value: 'secret-password', type: 'password', checked: false }
  ];
  const attributes = {};
  const fieldAutocomplete = {};
  const form = {
    setAttribute(name, value) { attributes[name] = value; },
    querySelectorAll(selector) { return selector.startsWith('input:not') ? fields : []; }
  };
  const elements = {
    loginForm: form,
    role: { value: 'Admin' },
    email: { setAttribute(name, value) { fieldAutocomplete.email = value; } },
    password: { setAttribute(name, value) { fieldAutocomplete.password = value; } },
    mfaCode: { setAttribute(name, value) { fieldAutocomplete.mfaCode = value; } }
  };
  const listeners = {};
  const storage = map => ({
    getItem(key) { return map.get(key) ?? null; },
    setItem(key, value) { map.set(key, String(value)); },
    removeItem(key) { map.delete(key); }
  });
  const context = makeEngineContext({
    location: { protocol: 'http:', origin: 'http://localhost', pathname: '/login.html' },
    sessionStorage: storage(values),
    localStorage: storage(new Map()),
    document: {
      addEventListener(type, callback) { listeners[type] = callback; },
      querySelectorAll() { return []; },
      getElementById(id) { return elements[id] ?? null; }
    },
    window: { addEventListener(type, callback) { listeners[`window:${type}`] = callback; } }
  });

  context.bindAuthFormPrivacy();
  assert.equal(attributes.autocomplete, 'off');
  assert.equal(fieldAutocomplete.email, 'off');
  assert.equal(fieldAutocomplete.password, 'new-password');
  assert.equal(fieldAutocomplete.mfaCode, 'off');
  assert.equal(fields[0].value, '');
  assert.equal(fields[1].value, '');
  assert.equal(elements.role.value, '');
  fields[0].value = 'restored@example.test';
  listeners['window:pageshow']();
  assert.equal(fields[0].value, '');
});

test('login reports when a previous server session could not be revoked', () => {
  const login = fs.readFileSync(path.join(staticRoot, 'login.html'), 'utf8');
  assert.match(login, /async function endOldSession\(\)[\s\S]*?if\s*\(\s*!response\.ok\s*\)\s*throw new Error/);
  assert.match(login, /catch\s*\{[\s\S]*?showError\('We could not confirm that the previous session was ended/);
  assert.match(login, /cache\s*:\s*'no-store'/);
  assert.match(login, /resetForm\(\);\s*endOldSession\(\);/);
  assert.match(login, /catch\s*\(\s*ex\s*\)\s*\{[\s\S]*?emailEl\.value\s*=\s*'';\s*pwEl\.value\s*=\s*'';\s*mfaEl\.value\s*=\s*'';/);
  assert.match(login, /Please select your account role\./);
  assert.match(login, /Please enter your email address\./);
  assert.match(login, /Please enter your password\./);
  assert.doesNotMatch(login, /(?:URLSearchParams|qs)\([^)]*\).*?(?:email|password)/i);
  assert.match(login, /id="email"[^>]*autocomplete="off"[^>]*readonly/);
  assert.match(login, /id="password"[^>]*autocomplete="new-password"[^>]*readonly/);
  assert.match(login, /emailEl\.readOnly\s*=\s*true\s*;\s*pwEl\.readOnly\s*=\s*true/);
  assert.match(login, /\[\s*emailEl\s*,\s*pwEl\s*\]\.forEach\(el\s*=>\s*el\.addEventListener\('focus'\s*,\s*\(\)\s*=>\s*\{\s*el\.readOnly\s*=\s*false\s*;\s*\}\s*\)\s*\)/);
  assert.match(login, /src="app_engine\.js\?v=4"/);
});

test('driver shift QR opens commuter login and warns when its host is local-only', () => {
  const context = makeEngineContext({
    location: {
      protocol: 'http:',
      origin: 'http://localhost:8080',
      href: 'http://localhost:8080/driver_dash.html',
      hostname: 'localhost',
      pathname: '/driver_dash.html'
    }
  });
  const loginUrl = new URL(context.buildShiftLoginUrl('PTH-SHIFT:test-token'));
  const driverDashboard = fs.readFileSync(path.join(staticRoot, 'driver_dash.html'), 'utf8');
  const shiftPage = fs.readFileSync(path.join(staticRoot, 'shift.html'), 'utf8');
  const login = fs.readFileSync(path.join(staticRoot, 'login.html'), 'utf8');

  assert.equal(loginUrl.pathname, '/login.html');
  assert.equal(loginUrl.searchParams.get('role'), 'Commuter');
  assert.equal(loginUrl.searchParams.get('shiftCode'), 'PTH-SHIFT:test-token');
  assert.match(context.shiftQrNetworkNotice(), /localhost.*only works on this computer/i);
  assert.match(driverDashboard, /text:loginUrl/);
  assert.match(driverDashboard, /src="app_engine\.js\?v=4"/);
  assert.match(shiftPage, /buildShiftLoginUrl\(code\)/);
  assert.match(shiftPage, /src="app_engine\.js\?v=4"/);
  assert.match(login, /roleEl\.value\s*=\s*shiftCode\s*\?\s*'Commuter'\s*:\s*''/);
  assert.match(login, /src="app_engine\.js\?v=4"/);
});

test('QR login keeps the commuter role selected while clearing restored credentials', () => {
  const elements = {
    loginForm: { setAttribute() {}, querySelectorAll() { return []; } },
    role: { value: 'Admin' },
    email: { setAttribute() {} },
    password: { setAttribute() {} },
    mfaCode: { setAttribute() {} }
  };
  const context = makeEngineContext({
    location: {
      protocol: 'http:',
      origin: 'http://localhost:8080',
      href: 'http://localhost:8080/login.html?shiftCode=PTH-SHIFT%3Atest-token',
      hostname: 'localhost',
      pathname: '/login.html',
      search: '?shiftCode=PTH-SHIFT%3Atest-token'
    },
    sessionStorage: { getItem() { return null; } },
    document: {
      addEventListener() {},
      querySelectorAll() { return []; },
      getElementById(id) { return elements[id] ?? null; }
    }
  });

  context.bindAuthFormPrivacy();
  assert.equal(elements.role.value, 'Commuter');
});

test('authentication failures are generic and private pages disable browser caching', () => {
  const controller = fs.readFileSync(path.join(projectRoot, 'src/main/java/com/paytaxihub/controller/ApiController.java'), 'utf8');
  const filter = fs.readFileSync(path.join(projectRoot, 'src/main/java/com/paytaxihub/auth/AuthFilter.java'), 'utf8');
  assert.match(controller, /BAD_CREDENTIALS\s*=\s*"Sign-in failed\. Check your details and try again\."/);
  assert.match(controller, /DUPLICATE_MESSAGE\s*=\s*"We could not complete registration with those details\. Check the information and try again\."/);
  assert.doesNotMatch(controller, /DUPLICATE_MESSAGE\s*=\s*"[^"]*(?:already have an account|email address is registered|email is already registered)/i);
  assert.match(controller, /If an active account exists for that email, reset instructions will be provided\./);
  assert.match(controller, /ou\.isEmpty\(\) \|\| !passwordOk \|\| ou\.get\(\)\.getRole\(\) != wantedRole/);
  assert.match(filter, /Cache-Control","no-store, no-cache, must-revalidate, max-age=0"/);
  assert.match(filter, /Pragma","no-cache"/);
});

test('a protected page restored after sign-out clears the tab session and redirects', () => {
  const values = new Map([
    ['pth_session', JSON.stringify({ userId: 41, role: 'Admin' })]
  ]);
  const location = {
    protocol: 'http:',
    origin: 'http://localhost',
    pathname: '/admin.html',
    replace(path) { this.destination = path; }
  };
  const context = makeEngineContext({
    location,
    sessionStorage: {
      getItem(key) { return values.get(key) ?? null; },
      setItem(key, value) { values.set(key, String(value)); },
      removeItem(key) { values.delete(key); }
    },
    localStorage: { removeItem() {} },
    document: {
      documentElement: { style: {} },
      addEventListener() {},
      querySelectorAll() { return []; }
    }
  });

  context.guardRestoredProtectedPage({ persisted: true });
  assert.equal(location.destination, 'login.html');
  assert.equal(values.has('pth_token'), false);
  assert.equal(values.has('pth_session'), false);
  assert.equal(context.document.documentElement.style.visibility, 'hidden');
});

test('mobile navigation announces state and supports Escape and link activation', () => {
  const listeners = {};
  const attributes = {};
  const classes = new Set();
  let focused = false;
  const menu = {
    id: '',
    classList: {
      contains: name => classes.has(name),
      toggle(name, force) {
        if (force) classes.add(name);
        else classes.delete(name);
      }
    },
    addEventListener(type, callback) { listeners[`menu:${type}`] = callback; }
  };
  const button = {
    setAttribute(name, value) { attributes[name] = value; },
    addEventListener(type, callback) { listeners[`button:${type}`] = callback; },
    focus() { focused = true; }
  };
  const keyListeners = {};
  const context = makeEngineContext({
    document: {
      addEventListener(type, callback) { keyListeners[type] = callback; },
      querySelectorAll() { return []; },
      querySelector(selector) {
        return selector === '.pth-hamburger' ? button : menu;
      }
    }
  });

  context.bindSessionUi();
  assert.equal(attributes['aria-expanded'], 'false');
  assert.equal(attributes['aria-controls'], 'mobile-navigation');
  listeners['button:click']();
  assert.equal(attributes['aria-expanded'], 'true');
  assert.equal(attributes['aria-label'], 'Close menu');
  keyListeners.keydown({ key: 'Escape' });
  assert.equal(attributes['aria-expanded'], 'false');
  assert.equal(focused, true);
  listeners['button:click']();
  listeners['menu:click']({ target: { closest: () => ({}) } });
  assert.equal(attributes['aria-expanded'], 'false');
});
