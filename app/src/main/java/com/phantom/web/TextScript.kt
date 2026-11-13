package com.phantom.web

import org.json.JSONObject

fun buildTextScript(value: String): String {
    val quoted = JSONObject.quote(value)
    return """
        (function() {
            var el = document.activeElement;
            if (!el) return;
            var v = $quoted;
            if (el.isContentEditable) {
                el.focus();
                document.execCommand('selectAll', false, null);
                document.execCommand('insertText', false, v);
            } else if (el.tagName && (el.tagName.toLowerCase() === 'input' || el.tagName.toLowerCase() === 'textarea')) {
                var proto = el.tagName.toLowerCase() === 'textarea'
                    ? window.HTMLTextAreaElement.prototype
                    : window.HTMLInputElement.prototype;
                var desc = Object.getOwnPropertyDescriptor(proto, 'value');
                if (desc && desc.set) {
                    desc.set.call(el, v);
                } else {
                    el.value = v;
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                if (typeof el.setSelectionRange === 'function') {
                    var len = el.value ? el.value.length : 0;
                    el.setSelectionRange(len, len);
                }
            }
        })();
    """.trimIndent()
}
