# Job capture (Firefox extension)

Sends the job pages you browse to the webapp, which saves the jobs it finds into `jobs/`.
For sites that block scrapers or need you logged in: LinkedIn, Indeed, Wellfound, Glassdoor,
Work at a Startup, Welcome to the Jungle (Otta), Remote.co, and Greenhouse, Lever and Ashby job boards.

## How it works

1. On those sites, `capture.js` sends the whole page to `background.js` when it loads, when the url
   changes, and when more results load in (at most every 10 seconds per page).
2. `background.js` posts it to the webapp: `POST http://localhost:8080/api/pages` with `{url, title, html}`.
3. The webapp keeps a copy of the page in `pages/{host}/`, then `PageParser` pulls out the jobs:
   - single job pages: title, company and description from their schema.org JobPosting data
   - search results: each job link's title, and the company when the card shows it
   - LinkedIn and Indeed: the description of the job open beside the results
4. New jobs are saved with `Jobs.saveNew`. A job first seen in a list gets its description when you open it.

The toolbar badge shows how many new jobs the page added, or `!` if the webapp couldn't be reached.

## The popup

- **Capture job pages as I browse**: switch automatic capturing on or off.
- **Capture this page**: send the current tab, on any site, even when capturing is off.
- **Webapp address**: where the webapp runs, `http://localhost:8080` by default.

## Installing

The webapp must be running for anything to be saved.

For a session: open `about:debugging#/runtime/this-firefox`, choose **Load Temporary Add-on…**, and pick
`manifest.json` in this folder. Firefox removes it when it closes.

To keep it installed, sign it as an unlisted add-on with Mozilla (`web-ext sign`), or use Firefox
Developer Edition with `xpinstall.signatures.required` set to `false` in `about:config`.

## Notes

- Whole pages include whatever else is on them, like your name and LinkedIn notifications.
  They only go to your own webapp, and are stored in `pages/`, which git ignores.
- Site layouts change. If a site stops producing jobs, the saved pages in `pages/` show what it looks like now,
  and the selectors to update are in `PageParser.java`.
- LinkedIn's terms forbid scraping. This only sends pages you open yourself, at your own pace.
