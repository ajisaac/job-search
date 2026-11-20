package co.aisaac.scrapers.public_apis.nodesk;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Scrapes NoDesk's engineering jobs.
 * <p>
 * The RSS feed lists the newest jobs (about 10), each with a short plain text excerpt.
 * For each job not saved yet, the full description, title and company come from the
 * JobPosting JSON-LD on the job's own page. If that page can't be read, the feed's
 * title ("Engineer at Company") and excerpt are used instead.
 */
public class ScrapeNoDesk {

	private static final String FEED_URL = "https://nodesk.co/remote-jobs/engineering/index.xml";
	private static final String JOB_SITE = "nodesk";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeNoDesk().run();
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
			String url = text(item, "link");
			if (url.isEmpty()) {
				url = text(item, "guid");
			}
			if (url.isEmpty() || store.exists(url)) {
				continue;
			}

			Utils.sleepRandom(3);
			String jobUrl = url;
			Job job = fromJobPage(jobUrl).orElseGet(() -> fromFeedItem(item, jobUrl));
			job.job_site = JOB_SITE;
			job.status = "new";
			job.job_posting_date = LocalDate.now();

			if (store.saveNew(job)) {
				saved++;
				System.out.println("Saved " + job.title + " at " + job.company);
			}
		}

		System.out.println(JOB_SITE + ": " + items.size() + " found, " + saved + " new");
	}

	// The job page carries a schema.org JobPosting with the full description as html.
	private Optional<Job> fromJobPage(String url) {
		Optional<String> html = Http.get(url);
		if (html.isEmpty()) {
			return Optional.empty();
		}

		for (Element script : Jsoup.parse(html.get(), url).select("script")) {
			if (!"application/ld+json".equalsIgnoreCase(script.attr("type"))) {
				continue;
			}
			try {
				JsonNode posting = findJobPosting(mapper.readTree(script.data()));
				if (posting == null) {
					continue;
				}

				Job job = new Job();
				job.url = url;
				job.title = posting.path("title").asText("").trim();
				job.company = posting.path("hiringOrganization").path("name").asText("").trim();
				job.description = posting.path("description").asText("");
				if (!job.title.isEmpty()) {
					return Optional.of(job);
				}
			} catch (Exception e) {
				System.out.println("Couldn't read the JSON-LD on " + url + ": " + e.getMessage());
			}
		}

		System.out.println("No job posting data on " + url + ", using the feed's excerpt");
		return Optional.empty();
	}

	// JSON-LD can be one object, an array of them, or an object with an @graph array.
	private static JsonNode findJobPosting(JsonNode node) {
		if (node == null) {
			return null;
		}
		if (node.isArray()) {
			for (JsonNode child : node) {
				JsonNode found = findJobPosting(child);
				if (found != null) {
					return found;
				}
			}
			return null;
		}
		if ("JobPosting".equals(node.path("@type").asText())) {
			return node;
		}
		return node.has("@graph") ? findJobPosting(node.get("@graph")) : null;
	}

	// Feed titles look like "Applied AI Engineer at Revolut". The description is a plain text excerpt.
	private static Job fromFeedItem(Element item, String url) {
		String title = text(item, "title");
		String company = "";
		int at = title.lastIndexOf(" at ");
		if (at > 0) {
			company = title.substring(at + " at ".length()).trim();
			title = title.substring(0, at).trim();
		}

		StringBuilder description = new StringBuilder();
		for (String line : text(item, "description").split("\\R")) {
			if (!line.isBlank()) {
				description.append("<p>").append(Entities.escape(line.trim())).append("</p>\n");
			}
		}

		Job job = new Job();
		job.url = url;
		job.title = title;
		job.company = company;
		job.description = description.toString();
		return job;
	}

	private static String text(Element parent, String tag) {
		Element element = parent.selectFirst(tag);
		return element == null ? "" : element.wholeText().trim();
	}
}
