// ==UserScript==
// @name         X: no For You
// @description  Hides the "For you" tab on x.com and keeps the home timeline on "Following".
// @match        https://x.com/*
// @match        https://twitter.com/*
// @run-at       document-idle
// @grant        none
// ==/UserScript==

(() => {
  const tabs = () => [...document.querySelectorAll('[role="tab"]')];
  const label = (el) => el.textContent.trim().toLowerCase();

  function update() {
    const forYou = tabs().find((t) => label(t) === 'for you');
    if (!forYou) return;
    // Hide the whole tab cell, not just the inner text.
    (forYou.closest('[role="presentation"]') || forYou).style.display = 'none';
    if (forYou.getAttribute('aria-selected') === 'true') {
      tabs().find((t) => label(t) === 'following')?.click();
    }
  }

  new MutationObserver(update).observe(document.body, { childList: true, subtree: true });
  update();
})();
