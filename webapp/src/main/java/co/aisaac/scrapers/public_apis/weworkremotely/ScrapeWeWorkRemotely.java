package co.aisaac.scrapers.public_apis.weworkremotely;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes We Work Remotely's RSS feeds, one per category, with full html descriptions.
 * <p>
 * Feed titles look like "Company: Job title". The description starts with the company's logo, which is dropped.
 * Other categories: remote-devops-sysadmin-jobs, remote-design-jobs, remote-programming-jobs (a mixed bag).
 */
public class ScrapeWeWorkRemotely {

	private static final List<String> CATEGORIES = List.of(
			"remote-full-stack-programming-jobs",
			"remote-back-end-programming-jobs",
			"remote-front-end-programming-jobs"
	);

	// Same job_site the old Selenium scraper used, so jobs it saved and these share a folder and a filter.
	private static final String JOB_SITE = "we_work_remotely";

	private final Jobs store = new Jobs();

	public static void main(String[] args) {
		new ScrapeWeWorkRemotely().run();
	}

	public void run() {
		int found = 0;
		int saved = 0;

		for (int i = 0; i < CATEGORIES.size(); i++) {
			if (i > 0) {
				Utils.sleepRandom(3);
			}

			String feedUrl = "https://weworkremotely.com/categories/" + CATEGORIES.get(i) + ".rss";
			Optional<String> feed = Http.get(feedUrl);
			if (feed.isEmpty()) {
				continue;
			}

			Elements items = Jsoup.parse(feed.get(), feedUrl, Parser.xmlParser()).select("item");
			found += items.size();

			for (Element item : items) {
				Job job = fromItem(item);
				if (job.url.isEmpty() || job.title.isEmpty()) {
					continue;
				}
				if (store.saveNew(job)) {
					saved++;
					System.out.println("Saved " + job.title + " at " + job.company);
				}
			}
		}

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	private static Job fromItem(Element item) {
		String title = text(item, "title");
		String company = "";
		int colon = title.indexOf(": ");
		if (colon > 0) {
			company = title.substring(0, colon).trim();
			title = title.substring(colon + 2).trim();
		}

		// The description is escaped html, which the parser has already unescaped back into html.
		String description = text(item, "description").replaceFirst("^\\s*<img[^>]*>", "");

		Job job = new Job();
		job.url = text(item, "link");
		job.title = title;
		job.company = company;
		job.description = summary(item) + description;
		job.job_site = JOB_SITE;
		job.status = "new";
		job.job_posting_date = LocalDate.now();
		return job;
	}

	// Region, type and skills are separate fields in the feed, not always in the description.
	private static String summary(Element item) {
		StringBuilder html = new StringBuilder();
		appendLine(html, "Region", text(item, "region"));
		appendLine(html, "Type", text(item, "type"));
		appendLine(html, "Skills", text(item, "skills"));
		return html.toString();
	}

	private static void appendLine(StringBuilder html, String label, String value) {
		if (!value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.trim())).append("</p>\n");
		}
	}

	private static String text(Element parent, String tag) {
		Elements elements = parent.getElementsByTag(tag);
		return elements.isEmpty() ? "" : elements.first().wholeText().trim();
	}
}
