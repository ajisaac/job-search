package co.aisaac.scrapers.public_apis.jobspresso;

import co.aisaac.scrapers.Http;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Scrapes Jobspresso's RSS feed (a WordPress Job Manager feed). One request, full html descriptions included.
 * <p>
 * Jobspresso's taxonomies are swapped: its categories ("Engineer", "AI &amp; Data", ...) are stored as job types,
 * and its job types ("Full Time", "Contract") as categories. So engineering jobs are asked for with
 * {@code job_types=developer}, and the feed's {@code job_listing:job_type} field holds the category names.
 * Other category slugs: ai-data, security, designer, product-mgmt; several can be joined with commas.
 */
public class ScrapeJobspresso {

	private static final String FEED_URL = "https://jobspresso.co/?feed=job_feed&job_types=developer&posts_per_page=50";
	private static final String JOB_SITE = "jobspresso";

	private final Jobs store = new Jobs();

	public static void main(String[] args) {
		new ScrapeJobspresso().run();
	}

	public void run() {
		Optional<String> feed = Http.get(FEED_URL);
		if (feed.isEmpty()) {
			System.out.println(JOB_SITE + ": couldn't fetch the feed");
			return;
		}

		Elements items = Jsoup.parse(feed.get(), FEED_URL, Parser.xmlParser()).select("item");
		int saved = 0;

		for (Element item : items) {
			Job job = new Job();
			job.url = text(item, "link");
			job.title = text(item, "title");
			job.company = unescape(text(item, "job_listing:company"));
			job.description = summary(item) + text(item, "content:encoded");
			job.job_site = JOB_SITE;
			job.status = "new";
			job.job_posting_date = LocalDate.now();

			if (job.url.isEmpty() || job.title.isEmpty()) {
				continue;
			}
			if (store.saveNew(job)) {
				saved++;
				System.out.println("Saved " + job.title + " at " + job.company);
			}
		}

		System.out.println(JOB_SITE + ": " + items.size() + " found, " + saved + " new");
	}

	// Location and salary are separate fields in the feed, not always in the description.
	private static String summary(Element item) {
		StringBuilder html = new StringBuilder();
		appendLine(html, "Location", unescape(text(item, "job_listing:location")));
		appendLine(html, "Salary", unescape(text(item, "job_listing:salary")));
		appendLine(html, "Category", unescape(text(item, "job_listing:job_type")));
		return html.toString();
	}

	private static void appendLine(StringBuilder html, String label, String value) {
		if (!value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.trim())).append("</p>\n");
		}
	}

	// The text of the first child with this tag, CDATA included. Tags with a namespace prefix ("content:encoded")
	// are looked up by their full name, since a css selector would read the colon as a pseudo class.
	private static String text(Element parent, String tag) {
		Elements elements = parent.getElementsByTag(tag);
		return elements.isEmpty() ? "" : elements.first().wholeText().trim();
	}

	// CDATA fields are taken literally, so "AI &amp; Data" stays escaped. Decode it, the description is html anyway.
	private static String unescape(String s) {
		return Parser.unescapeEntities(s, false);
	}
}
