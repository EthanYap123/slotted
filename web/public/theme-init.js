// Sets the theme before the first paint, so the page never flashes the wrong colours.
// A separate file, not an inline script, so a strict Content Security Policy can forbid inline scripts.
;(function () {
  var theme = 'light'
  try {
    var saved = localStorage.getItem('slotted.theme')
    if (saved === 'dark' || saved === 'system') theme = saved
  } catch (e) {
    // Storage can be blocked; light is the default.
  }
  document.documentElement.setAttribute('data-theme', theme)
})()
