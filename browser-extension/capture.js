// Runs on the job sites listed in manifest.json.
// Sends the whole page to background.js as you browse: when it loads, when the url changes
// (these sites are mostly single page apps), and when more results load in.
// The webapp works out which jobs are on the page.
(function () {
    const SETTLE_MS = 2500     // let the page finish rendering before taking it
    const MIN_GAP_MS = 10000   // while one page keeps changing, send it at most every 10 seconds
    const MIN_CHANGE = 2000    // ignore changes smaller than this many characters

    let lastUrl = null
    let lastLength = 0
    let lastSent = 0
    let timer = null

    // LinkedIn is one big app; only the jobs pages are worth sending.
    function wanted() {
        if (location.hostname.endsWith('linkedin.com')) return location.pathname.startsWith('/jobs')
        return true
    }

    function capture() {
        if (!wanted()) return

        const html = document.documentElement.outerHTML
        if (location.href === lastUrl && Math.abs(html.length - lastLength) < MIN_CHANGE) return

        lastUrl = location.href
        lastLength = html.length
        lastSent = Date.now()

        browser.runtime.sendMessage({
            type: 'capture',
            page: {url: location.href, title: document.title, html: html}
        })
    }

    // Wait for the page to settle, then send it. Changes while waiting don't push the time back,
    // so a page that never stops changing still gets sent.
    function schedule() {
        if (timer) return

        let wait = SETTLE_MS
        if (location.href === lastUrl) {
            wait = Math.max(wait, lastSent + MIN_GAP_MS - Date.now())
        }

        timer = setTimeout(() => {
            timer = null
            capture()
        }, wait)
    }

    new MutationObserver(schedule).observe(document.documentElement, {childList: true, subtree: true})
    schedule()
})()
