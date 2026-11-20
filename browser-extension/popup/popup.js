const enabled = document.getElementById('enabled')
const server = document.getElementById('server')
const captureButton = document.getElementById('capture')
const last = document.getElementById('last')

function showResult(result) {
    if (!result) return
    last.classList.toggle('last--error', !!result.error)

    if (result.error) {
        last.textContent = result.error
        return
    }

    const host = new URL(result.url).hostname
    const time = new Date(result.at).toLocaleTimeString([], {hour: 'numeric', minute: '2-digit'})
    last.textContent = `Last page: ${host} at ${time}. ` +
        `${result.found} jobs found, ${result.saved} new, ${result.described} descriptions added.`
}

async function load() {
    const stored = await browser.storage.local.get(['enabled', 'server', 'last'])
    enabled.checked = stored.enabled !== false
    server.value = stored.server || 'http://localhost:8080'
    showResult(stored.last)
}

enabled.addEventListener('change', () => {
    browser.storage.local.set({enabled: enabled.checked})
})

server.addEventListener('change', () => {
    const value = server.value.trim().replace(/\/+$/, '')
    browser.storage.local.set({server: value || 'http://localhost:8080'})
})

captureButton.addEventListener('click', async () => {
    const [tab] = await browser.tabs.query({active: true, currentWindow: true})
    last.classList.remove('last--error')
    last.textContent = 'Capturing…'
    try {
        showResult(await browser.runtime.sendMessage({type: 'capture-tab', tab: tab}))
    } catch (e) {
        last.classList.add('last--error')
        last.textContent = "Couldn't capture this page. Firefox doesn't allow it on some pages, like its own settings."
    }
})

load()
