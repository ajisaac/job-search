// Posts captured pages to the webapp, and remembers how the last one went for the popup.

const DEFAULTS = {
    enabled: true,
    server: 'http://localhost:8080'
}

async function settings() {
    const stored = await browser.storage.local.get(Object.keys(DEFAULTS))
    return Object.assign({}, DEFAULTS, stored)
}

// page: {url, title, html}. force: send even when capturing is switched off (the popup's button).
async function send(page, force, tabId) {
    const {enabled, server} = await settings()
    if (!enabled && !force) return

    let result
    try {
        const response = await fetch(server + '/api/pages', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(page)
        })
        result = response.ok ? await response.json() : {error: 'The webapp answered ' + response.status}
    } catch (e) {
        result = {error: "Couldn't reach " + server + '. Is the webapp running?'}
    }

    result.url = page.url
    result.at = Date.now()
    await browser.storage.local.set({last: result})

    // badge on the tab: how many new jobs this page added, or ! if it failed
    const text = result.error ? '!' : (result.saved ? '+' + result.saved : '')
    if (tabId !== undefined) {
        browser.browserAction.setBadgeText({text: text, tabId: tabId})
    }
    return result
}

// Grab any tab's page on request, for sites the extension doesn't watch by itself.
async function captureTab(tab) {
    const [html] = await browser.tabs.executeScript(tab.id, {code: 'document.documentElement.outerHTML'})
    return send({url: tab.url, title: tab.title, html: html}, true, tab.id)
}

browser.runtime.onMessage.addListener((message, sender) => {
    if (message.type === 'capture') {
        return send(message.page, false, sender.tab && sender.tab.id)
    }
    if (message.type === 'capture-tab') {
        return captureTab(message.tab)
    }
})
