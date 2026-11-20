# Scrapers

Job boards to scrape, grouped by how they're scraped. Each board has its own package,
and each group is a package under `co.aisaac.scrapers`, e.g. `co.aisaac.scrapers.public_apis.nodesk`.

Boards without a scraper yet have just a `package-info.java` noting how to scrape them.
`co.aisaac.scrapers.Utils` and `co.aisaac.scrapers.Http` (plain GETs) are shared.

`co.aisaac.scrapers.RunScrapers` runs every scraper that works without a browser, one after another,
or only those named as arguments, e.g. `nodesk remotive arcdev`. Run it from the folder that holds `jobs/` and `companies/`.

## Public APIs and feeds — `public_apis`

The easiest: ask for JSON or RSS, no page parsing.

The company job boards (Ashby, Greenhouse, Lever, Recruitee, SmartRecruiters, Workable) have one endpoint per
company, so each reads its companies from `companies/{board}.txt`, next to `jobs/`: one per line, the company's
name in its board urls, then optionally a display name. They keep only titles that look technical.
`co.aisaac.scrapers.CompanyBoards` reads the lists and holds the title filter.

| Board | Package | How |
|---|---|---|
| Ashby | `ashby` | JSON API per company, `api.ashbyhq.com/posting-api/job-board/{company}?includeCompensation=true`. `ScrapeAshby` |
| Greenhouse | `greenhouse` | JSON API per company, `boards-api.greenhouse.io/v1/boards/{company}/jobs?content=true`. Descriptions come html escaped. `ScrapeGreenhouse` |
| Hacker News "Who is hiring?" | `hackernews` | Algolia HN API: the newest thread by `whoishiring`, then `hn.algolia.com/api/v1/items/{id}` for all its comments. Each top-level comment is a job. `ScrapeHackerNews` |
| Himalayas | `himalayas` | Search API, `himalayas.app/jobs/api/search?q=software+engineer&sort=recent&page=N`; the plain feed has no category filter. `ScrapeHimalayas` |
| Jobspresso | `jobspresso` | RSS feed with full descriptions, `jobspresso.co/?feed=job_feed&job_types=developer&posts_per_page=50`. Categories are filtered with `job_types` (swapped on their side). `ScrapeJobspresso` |
| Lever | `lever` | JSON API per company, `api.lever.co/v0/postings/{company}?mode=json`. `ScrapeLever` |
| NoDesk | `nodesk` | RSS feed of the newest jobs, `nodesk.co/remote-jobs/engineering/index.xml`; full descriptions from each job page's JSON-LD. `ScrapeNoDesk` |
| Recruitee | `recruitee` | Careers API per company, `{company}.recruitee.com/api/offers/`. `ScrapeRecruitee` |
| Remote OK | `remoteok` | Public JSON API, `remoteok.com/api?tag=dev`. Text comes double encoded and is repaired. Their terms ask you to credit them. `ScrapeRemoteOk` |
| Remotive | `remotive` | Public JSON API, `remotive.com/api/remote-jobs`. Ignores its `category` parameter, so `ScrapeRemotive` filters by category itself. Max 4 requests a day. |
| SmartRecruiters | `smartrecruiters` | Postings API per company, `api.smartrecruiters.com/v1/companies/{company}/postings`; descriptions need one more GET per job. `ScrapeSmartRecruiters` |
| We Work Remotely | `weworkremotely` | RSS feed per category with full descriptions, `weworkremotely.com/categories/{category}.rss`. `ScrapeWeWorkRemotely` |
| Workable | `workable` | Widget API per company, `apply.workable.com/api/v1/widget/accounts/{company}?details=true`. `ScrapeWorkable` |
| Working Nomads | `workingnomads` | Elasticsearch search API with full descriptions, `workingnomads.com/jobsapi/_search?q=category_name:"Development"&sort=pub_date:desc`. `ScrapeWorkingNomads` |

## Jobs embedded in the page as JSON — `embedded_json`

Fetch the page, pull out the JSON it already carries for its own scripts.

| Board | Package | How |
|---|---|---|
| Arc.dev | `arcdev` | `__NEXT_DATA__` holds `arcJobs` and `externalJobs`, 30 each, no paging; descriptions from each job's page, also in `__NEXT_DATA__`. `ScrapeArcDev` |
| JustRemote | `justremote` | `window.__PRELOADED_STATE__` holds the ~80 newest jobs from every category; descriptions from each job's page, in the same state. `ScrapeJustRemote` |
| SimplyHired | `simplyhired` | `__NEXT_DATA__` holds `jobs`: key, title, snippet, 20 a page, plus `pageCursors`. `t=1` limits it to the last day. Full descriptions from `/job/{key}`. `ScrapeSimplyHired` |

## HTML — `html`

Fetch the page and parse it with jsoup.

| Board | Package | How |
|---|---|---|
| Built In | `builtin` | `/jobs/remote/dev-engineering?daysSinceUpdated=1&page=N`, ~25 a page; job links at `/job/{slug}/{id}`, with JSON-LD for the full posting. Has a Cloudflare script that hasn't blocked requests yet. `ScrapeBuiltIn` |
| Dice | `dice` | Search pages rendered on the server, ~30 job cards a page, jobs at `/job-detail/{uuid}` with JSON-LD for the full posting. `ScrapeDice` |
| Skip The Drive | `skipthedrive` | `/job-category/remote-software-development-jobs/page/N/`, 20 a page, not in date order, so all pages are read; descriptions from each job's page. Its JSON API refuses requests. Cloudflare script, not blocking yet. `ScrapeSkipTheDrive` |

## Browser extension — `browser_extension`

Need a login or block scrapers, so they come in through the Firefox extension in `browser-extension/`
as you browse. `PageParser` in the webapp pulls the jobs out.

| Board | Package | Why |
|---|---|---|
| Glassdoor | `glassdoor` | Login wall |
| Google Jobs | `googlejobs` | Built with JavaScript, and Google forbids scraping it. Mostly repeats other boards |
| Indeed | `indeed` | Cloudflare challenges |
| LinkedIn | `linkedin` | Login wall and bot detection. Its terms forbid scraping, so only pages you open yourself |
| Remote.co | `remoteco` | Refuses plain requests |
| Welcome to the Jungle (Otta) | `welcometothejungle` | Needs an account |
| Wellfound (Angel) | `wellfound` | Login and bot protection |
| Work at a Startup (Y Combinator) | `workatastartup` | Needs a login |
| ZipRecruiter | `ziprecruiter` | Blocked by a Cloudflare challenge (403) |

Greenhouse, Lever and Ashby job pages also come in through the extension when you browse them.

## Gone

- Hired has shut down: hired.com now redirects to LHH.
- DailyRemote (`html.dailyremote`) is paywalled: every job's company is hidden, the description is placeholder
  text, and Apply leads to a paid signup. Only the title, an AI summary, location and tags are public, so it's skipped.
