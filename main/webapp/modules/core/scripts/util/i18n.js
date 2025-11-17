I18NUtil = {};

/*
 * Initialize i18n and load message translation file for
 * the given module.
 * @public
 * @param {string} module The module name to load the translation file for.
 */
I18NUtil.init = function (module) {
    /*
     * Note that the browser language is only used to show a warning if the server
     * replies with another. The language is instead picked form the `userLang` preference.
     */
    var browserLang = (navigator.language || navigator.userLanguage || 'en');
    var lang = browserLang.split("-")[0];
    var dictionary = {}; // ensure object to avoid spurious network fetches

    $.ajax({
        url: "command/core/load-language?",
        type: "POST",
        async: false,
        data: {
            module: module
        },
        success: function (data) {
            if (data && data['dictionary']) { dictionary = data['dictionary']; }
            var langFromServer = data['lang'];
            if (lang !== langFromServer) {
                console.warn('Language \'' + lang + '\' missing translation. Defaulting to \''+langFromServer+'\'.');
                if (typeof langFromServer === 'string' && langFromServer.length) { lang = langFromServer; }
            }
        }
    }).fail(function( jqXhr, textStatus, errorThrown ) {
        var errorMessage = $.i18n ? $.i18n('core-index/prefs-loading-failed') : '';
        if (errorMessage && errorMessage != 'core-index/prefs-loading-failed') {
            alert(errorMessage);
        } else {
            // Non-blocking: log and continue with fallback
            console.warn("Failed to load language for module '" + module + "': " + textStatus + ':' + errorThrown);
        }
    });
    try {
        $.i18n().load(dictionary || {}, lang || 'en');
    } catch (e) {
        console.warn("i18n load failed, falling back to 'en':", e);
        try { $.i18n().load(dictionary || {}, 'en'); } catch (e2) {}
    }
    $('html').attr('lang', (lang || 'en').replace('_', '-'));
    if (module === 'core') {
      $.i18n({ locale: (lang || 'en') });
      // TODO: This should be globally accessible, but this is OK for our current needs
      Refine.userLang = (lang || 'en');
    }
}
