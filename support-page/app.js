(function () {
  "use strict";

  var SUPPORTED = ["ru", "en", "es", "zh", "hi", "pt", "id"];
  var STORAGE_KEY = "xhc_lang";
  var FALLBACK = "en";

  var currentLang = detectLang();

  // Автоопределение: сохранённый выбор > префикс navigator.language > en
  function detectLang() {
    try {
      var saved = localStorage.getItem(STORAGE_KEY);
      if (saved && SUPPORTED.indexOf(saved) !== -1) return saved;
    } catch (e) { /* localStorage может быть недоступен */ }

    var nav = (navigator.language || "").toLowerCase();
    var prefix = nav.split("-")[0];
    if (SUPPORTED.indexOf(prefix) !== -1) return prefix;
    return FALLBACK;
  }

  function t(key) {
    var dict = I18N[currentLang] || I18N[FALLBACK] || {};
    var fallback = I18N[FALLBACK] || {};
    return dict[key] || fallback[key] || "";
  }

  function setText(id, value) {
    var el = document.getElementById(id);
    if (el) el.textContent = value;
  }

  function applyLang(lang) {
    currentLang = lang;
    document.documentElement.lang = lang;

    setText("cryptoTitle", t("cryptoTitle"));
    setText("russiaTitle", t("russiaTitle"));
    setText("telegramTitle", t("telegramTitle"));
    setText("tonLabel", t("tonLabel"));
    setText("tonOnlyWarning", t("tonOnlyWarning"));
    setText("trc20Label", t("trc20Label"));
    setText("trc20OnlyWarning", t("trc20OnlyWarning"));
    setText("telegramExplanation", t("telegramExplanation"));
    setText("footerDisclaimer", t("footerDisclaimer"));
    setText("noTracking", t("noTracking"));
    setText("langLabel", t("langLabel"));

    // Кнопки Copy сбрасываются на текущий язык
    var btns = document.querySelectorAll(".copy-btn");
    for (var i = 0; i < btns.length; i++) {
      btns[i].textContent = t("copy");
      btns[i].classList.remove("copied");
    }
  }

  function copyToClipboard(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      try {
        var ta = document.createElement("textarea");
        ta.value = text;
        ta.style.position = "fixed";
        ta.style.opacity = "0";
        document.body.appendChild(ta);
        ta.select();
        document.execCommand("copy");
        document.body.removeChild(ta);
        resolve();
      } catch (e) {
        reject(e);
      }
    });
  }

  function bindCopy(btnId, getText) {
    var btn = document.getElementById(btnId);
    if (!btn) return;
    btn.addEventListener("click", function () {
      copyToClipboard(getText()).then(function () {
        btn.textContent = t("copied");
        btn.classList.add("copied");
        setTimeout(function () {
          btn.textContent = t("copy");
          btn.classList.remove("copied");
        }, 1500);
      }).catch(function () { /* копирование недоступно — игнорируем */ });
    });
  }

  function renderQR() {
    if (typeof QRCode === "undefined" || !QRCode.toCanvas) return; // CDN не загрузился
    var tonCanvas = document.getElementById("qrTon");
    var trcCanvas = document.getElementById("qrTrc20");
    if (tonCanvas) {
      QRCode.toCanvas(tonCanvas, "ton://transfer/" + CONFIG.tonUniversal, { width: 220, margin: 1 });
    }
    if (trcCanvas) {
      QRCode.toCanvas(trcCanvas, CONFIG.usdtTrc20, { width: 220, margin: 1 });
    }
  }

  function setupContent() {
    document.title = CONFIG.pageTitle;
    setText("pageTitle", CONFIG.pageTitle);
    setText("tonAddress", CONFIG.tonUniversal);
    setText("trc20Address", CONFIG.usdtTrc20);

    var boosty = document.getElementById("boostyLink");
    if (boosty) boosty.href = CONFIG.boosty;
    var yoomoney = document.getElementById("yoomoneyLink");
    if (yoomoney) yoomoney.href = CONFIG.yoomoney;
    var cloudtips = document.getElementById("cloudtipsLink");
    if (cloudtips) cloudtips.href = CONFIG.cloudtips;

    var tgUrl = "https://t.me/" + CONFIG.telegram;
    var tgLink = document.getElementById("telegramLink");
    if (tgLink) {
      tgLink.href = tgUrl;
      tgLink.textContent = "@" + CONFIG.telegram;
    }
  }

  function init() {
    setupContent();
    applyLang(currentLang);
    renderQR();

    bindCopy("copyTon", function () { return CONFIG.tonUniversal; });
    bindCopy("copyTrc20", function () { return CONFIG.usdtTrc20; });

    var select = document.getElementById("langSelect");
    if (select) {
      select.value = currentLang;
      select.addEventListener("change", function () {
        try { localStorage.setItem(STORAGE_KEY, select.value); } catch (e) { /* ignore */ }
        applyLang(select.value);
      });
    }
  }

  init();
})();
