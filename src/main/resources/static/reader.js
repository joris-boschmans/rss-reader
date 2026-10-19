/*
 * Everything here is an addition, never a requirement: keyboard shortcuts for the
 * reading view, saving without losing your place, and loading an image when — and only
 * when — you ask for it. With JavaScript off, every one of these actions still has a
 * button or a link behind it.
 */
(function () {
  'use strict';

  var IN_PLACE_HEADER = 'X-Quiet-Reader';
  var IN_PLACE = 'in-place';

  /* Goes through the submit event, so the save handler below still gets a say. */
  function submit(id) {
    var form = document.getElementById(id);
    if (!form) return false;
    if (form.requestSubmit) form.requestSubmit();
    else form.submit();
    return true;
  }

  function typing(target) {
    if (!target) return false;
    var tag = target.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || target.isContentEditable;
  }

  document.addEventListener('keydown', function (e) {
    if (e.metaKey || e.ctrlKey || e.altKey || typing(e.target)) return;
    if (!document.getElementById('f-next')) return;

    var handled = false;
    switch (e.key) {
      case ' ':
      case 'j':
        handled = submit('f-next');
        break;
      case 'Backspace':
        handled = submit('f-back');
        break;
      case 's':
        handled = submit('f-save');
        break;
      case 'Escape':
        handled = submit('f-leave');
        break;
      default:
        break;
    }
    if (handled) e.preventDefault();
  });

  /*
   * Saving is the one action that shouldn't move you: you press it halfway down a long
   * post, and reloading the page would throw you back to the title. So it posts quietly
   * and swaps the button's own label, which the server supplied in both states.
   */
  var saveForm = document.getElementById('f-save');
  if (saveForm) {
    saveForm.addEventListener('submit', function (e) {
      var button = saveForm.querySelector('button');
      if (!button || !window.fetch) return; // let the plain form post happen instead

      e.preventDefault();
      if (button.disabled) return;
      button.disabled = true;

      var headers = {};
      headers[IN_PLACE_HEADER] = IN_PLACE;

      fetch(saveForm.action, { method: 'POST', headers: headers })
        .then(function (response) {
          if (!response.ok) throw new Error('save refused');
          var saved = button.getAttribute('data-saved');
          var unsaved = button.getAttribute('data-unsaved');
          button.textContent = button.textContent.trim() === saved ? unsaved : saved;
          button.disabled = false;
        })
        .catch(function () {
          // Something went wrong out here; fall back to the form that always works.
          button.disabled = false;
          saveForm.submit();
        });
    });
  }

  document.addEventListener('click', function (e) {
    var placeholder = e.target.closest ? e.target.closest('.deferred-image') : null;
    if (!placeholder) return;
    var src = placeholder.getAttribute('data-src');
    if (!src) return;

    e.preventDefault();
    var img = document.createElement('img');
    img.setAttribute('src', src);
    img.setAttribute('alt', placeholder.textContent.replace(/^Image — ?/, ''));
    img.setAttribute('loading', 'lazy');
    placeholder.replaceWith(img);
  });
})();
