package co.aisaac.scrapers.html.skipthedrive;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Scrapes Skip The Drive's remote software development jobs, a WordPress site, with plain GETs and jsoup.
 * <p>
 * The category's pages, /page/N/, list 20 jobs each, about 27 pages, but not in date order, so every page is read.
 * Each card ({@code .post-content}) has the title link, company, an age like "6 days ago" or "30+ days ago",
 * and sometimes job type and pay. Jobs "30+ days ago" are left out. The site's JSON API for jobs refuses
 * requests (403), so each new job's page is fetched for its description, at most {@link #MAX_NEW_JOBS} a run;
 * the rest are picked up on later runs.
 * <p>
 * The site loads a Cloudflare script that hasn't blocked plain requests so far. If several job pages in a row
 * fail, it probably has, so the run stops there.
 */
public class ScrapeSkipTheDrive {

	private static final String LISTING_URL = "https://www.skipthedrive.com/job-category/remote-software-development-jobs/";
	private static final int MAX_PAGES = 40;
	private static final int MAX_NEW_JOBS = 100;
	private static final int MAX_FAILURES_IN_A_ROW = 3;

	// Same job_site the old Selenium scraper used, so jobs it saved and these share a folder and a filter.
	private static final String JOB_SITE = "skip_the_drive";

	private final Jobs store = new Jobs();

	/** What a listing card says about a job. */
	private record Card(String url, String title, String company, String type, String pay) {
	}

	public static void main(String[] args) {
		new ScrapeSkipTheDrive().run();
	}

	public void run() {
		Map<String, Card> cards = readListing();

		int fetched = 0;
		int saved = 0;
		int failuresInARow = 0;

		for (Card card : cards.values()) {
			if (store.exists(card.url())) {
				continue;
			}
			if (fetched >= MAX_NEW_JOBS) {
				System.out.println("Fetched " + MAX_NEW_JOBS + " job pages, the rest wait for the next run");
				break;
			}

			Utils.sleepRandom(4);
			fetched++;
			Optional<Document> page = Http.get(card.url()).map(html -> Jsoup.parse(html, card.url()));
			Element content = page.map(p -> p.selectFirst(".entry-content")).orElse(null);
			if (content == null) {
				if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
					System.out.println(MAX_FAILURES_IN_A_ROW + " job pages in a row failed, stopping");
					break;
				}
				continue;
			}
			failuresInARow = 0;

			Element h1 = page.get().selectFirst("h1.post-title");
			String original = applyLink(content);
			if (!original.isEmpty() && store.exists(original)) {
				continue;
			}

			Job job = new Job();
			job.url = card.url();
			job.title = h1 == null ? card.title() : h1.text().strip();
			job.company = card.company();
			job.description = summary(card, content, original) + description(content);
			job.job_site = JOB_SITE;
			job.status = "new";
			job.job_posting_date = LocalDate.now();

			if (!job.title.isEmpty() && store.saveNew(job)) {
				saved++;
				System.out.println("Saved " + job.title + " at " + job.company);
			}
		}

		System.out.println(JOB_SITE + ": " + cards.size() + " found, " + saved + " new");
	}

	// Every listing page, until one has no cards. Cards for jobs "30+ days ago" are left out.
	private Map<String, Card> readListing() {
		Map<String, Card> cards = new LinkedHashMap<>();

		for (int page = 1; page <= MAX_PAGES; page++) {
			if (page > 1) {
				Utils.sleepRandom(3);
			}
			String url = page == 1 ? LISTING_URL : LISTING_URL + "page/" + page + "/";
			Optional<String> html = Http.get(url);
			if (html.isEmpty()) {
				break;
			}

			List<Element> found = Jsoup.parse(html.get(), url).select(".post-content");
			if (found.isEmpty()) {
				break;
			}
			for (Element element : found) {
				Element link = element.selectFirst(".post-title a[href]");
				if (link == null) {
					continue;
				}
				String age = text(element, ".custom_fields_job_date_display_search_results");
				if (age.startsWith("30+")) {
					continue;
				}
				String jobUrl = link.absUrl("href");
				cards.putIfAbsent(jobUrl, new Card(
						jobUrl,
						link.text().strip(),
						text(element, ".custom_fields_company_name_display_search_results"),
						text(element, ".custom_fields_job_type_inner"),
						text(element, ".custom_fields_compensation_inner")));
			}
		}
		return cards;
	}

	// The job page's meta block holds "Company - Company site", the date and "Remote - Czech Republic".
	private static String summary(Card card, Element content, String original) {
		String location = "";
		for (Element field : content.select(".job-meta-div .custom_fields_display")) {
			if (field.selectFirst(".fa-map-marker") != null) {
				location = field.text().strip();
			}
		}

		StringBuilder html = new StringBuilder();
		line(html, "Location", location);
		line(html, "Type", card.type());
		line(html, "Pay", card.pay());
		if (!original.isEmpty()) {
			html.append("<p><strong>Original posting:</strong> <a href=\"").append(Entities.escape(original)).append("\">")
					.append(Entities.escape(original)).append("</a></p>\n");
		}
		return html.toString();
	}

	// The entry content without the meta block, the share buttons and the apply button.
	private static String description(Element content) {
		Element copy = content.clone();
		copy.select(".job-meta-div, .fa-sharing-icon-wrapper, .apply_to_position_div, script, style").remove();
		return copy.html();
	}

	private static String applyLink(Element content) {
		Element link = content.selectFirst(".apply_to_position_div a[href^=http]");
		return link == null ? "" : link.attr("href").strip();
	}

	// Text with the &nbsp; padding the site puts in front of values removed.
	private static String text(Element parent, String selector) {
		Element element = parent.selectFirst(selector);
		return element == null ? "" : element.text().replace(' ', ' ').strip();
	}

	private static void line(StringBuilder html, String label, String value) {
		if (value != null && !value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.strip())).append("</p>\n");
		}
	}
}
