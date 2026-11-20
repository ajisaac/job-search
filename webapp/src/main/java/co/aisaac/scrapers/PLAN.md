# Plan: public API scrapers

How each scraper in `public_apis` will work. One class per board, in its package, with a `main()`,
like the old scrapers but without Selenium.

## 1. Decide what to fetch

- **One feed or API for everyone**: NoDesk, Jobspresso, We Work Remotely, Remotive, Remote OK, Himalayas,
  Working Nomads. Ask for the developer or engineering category where the API supports it, otherwise everything.
- **Company job boards**: Greenhouse, Lever, Ashby, Workable, SmartRecruiters, Recruitee. There's no "all jobs"
  endpoint, only one per company, so these read a list of company names from `companies/{board}.txt`,
  next to `jobs/`, one per line (`CompanyBoards`). The browser extension already works out the company from
  Greenhouse, Lever and Ashby urls, so the list could later fill itself from what you browse.
- **Hacker News**: find the latest "Who is hiring?" thread through the Algolia API, then fetch its top-level
  comments. Each comment is one job.

## 2. Fetch

A plain HTTP GET with Java's built-in `HttpClient`:

- a normal User-Agent and a timeout
- a short random pause between requests, `Utils.sleepRandom`
- follow pagination where an API has it (Working Nomads' `from`, Himalayas' offset, Algolia's pages)
- if a request fails, log it and move on, so one bad company doesn't stop the run

One small shared helper next to `Utils`, e.g. `Http.get(url)`, keeps the User-Agent, timeout and error
handling in one place. Each scraper still lives in one file.

## 3. Parse

- JSON: Jackson's `JsonNode`, reading only the handful of fields needed.
- RSS: jsoup's XML parser (`Parser.xmlParser()`), reading the `<item>` elements.

## 4. Map each posting to a `Job`

| Field | Value |
|---|---|
| `title`, `company` | from the posting |
| `url` | the posting's own page; also what makes duplicates detectable |
| `description` | the html the API returns; nearly all include it, and `Jobs` sanitizes it |
| `job_site` | the package name, e.g. `nodesk`, so each board gets its own folder in `jobs/` |
| `status` | `new` |
| `job_posting_date` | today |

## 5. Save

`store.saveNew(job)` skips a job whose url is already saved, or the same title at the same company
(the same job posted on another board). The webapp picks up new files on its next page load, no restart needed.

## 6. Report

One line at the end, e.g. `nodesk: 48 found, 7 new`.

## Later

- **Filtering**: company boards include sales, legal and other roles. A loose keyword filter on titles
  (engineer, developer, software…) keeps the clutter down; the webapp's filters do the rest.
  ✅ Done for the company boards (`CompanyBoards.isTechTitle`). Remote OK and Jobspresso could use it too.
- **Scheduling**: run them by hand first. Later, one runner class calls every public API scraper,
  and `cron` or `launchd` runs it daily. ✅ The runner is `RunScrapers`; scheduling it is still to do.
- **Old scrapers**: ✅ the `Old…` Selenium scrapers and the Selenium dependencies are deleted. Indeed and
  Wellfound weren't replaced; the browser extension covers those sites.

## Order

1. ✅ **NoDesk**, to settle the pattern: `nodesk.ScrapeNoDesk`, with the shared `Http.get`. Its feed only has the
   newest ~10 jobs with short excerpts, so it reads the full description from each new job's page (JSON-LD).
2. The other single-feed boards: ✅ Remotive (`remotive.ScrapeRemotive`), ✅ Jobspresso (`jobspresso.ScrapeJobspresso`),
   ✅ We Work Remotely (`weworkremotely.ScrapeWeWorkRemotely`), ✅ Remote OK (`remoteok.ScrapeRemoteOk`),
   ✅ Himalayas (`himalayas.ScrapeHimalayas`), ✅ Working Nomads (`workingnomads.ScrapeWorkingNomads`),
   ✅ Hacker News (`hackernews.ScrapeHackerNews`).
3. ✅ The company boards, each reading `companies/{board}.txt`: Greenhouse (`greenhouse.ScrapeGreenhouse`),
   Lever (`lever.ScrapeLever`), Ashby (`ashby.ScrapeAshby`), Workable (`workable.ScrapeWorkable`),
   SmartRecruiters (`smartrecruiters.ScrapeSmartRecruiters`), Recruitee (`recruitee.ScrapeRecruitee`).
