// Runs before the chart module (a plain script, so it still runs if the module cannot load).
// Any script error, or a chart that has not started within 8 s, is reported to the app,
// which shows it on the chart screen instead of a blank page.
(function () {
  function tell(msg) {
    try { window.IraBridge && window.IraBridge.fail(String(msg).slice(0, 300)); } catch (e) { /* nothing to tell */ }
  }
  var chrome = (/Chrome\/(\d+)/.exec(navigator.userAgent) || [])[1] || '?';
  window.addEventListener('error', function (e) {
    tell('Chart script error: ' + (e.message || 'unknown') + ' (WebView ' + chrome + ')');
  });
  window.addEventListener('unhandledrejection', function (e) {
    tell('Chart error: ' + ((e.reason && e.reason.message) || e.reason || 'unknown') + ' (WebView ' + chrome + ')');
  });
  setTimeout(function () {
    if (!window.__iraBooted) tell('The chart did not start (Android System WebView ' + chrome + '). Update "Android System WebView" in the Play Store, then tap Retry.');
  }, 8000);
})();
