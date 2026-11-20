/**
 * Scraper for SmartRecruiters company job boards: {@link co.aisaac.scrapers.public_apis.smartrecruiters.ScrapeSmartRecruiters}.
 * <p>
 * How: postings API, one company at a time: api.smartrecruiters.com/v1/companies/{company}/postings, then one GET per job for its description
 * Companies come from companies/smartrecruiters.txt, see {@link co.aisaac.scrapers.CompanyBoards}.
 */
package co.aisaac.scrapers.public_apis.smartrecruiters;
