package co.aisaac.scrapers.html.builtin;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Scrapes Built In's remote software engineering jobs. Listing pages are html, about 25 job cards a page, each
 * title a link {@code <a data-id="job-card-title" href="/job/{slug}/{id}">}.
 * <p>
 * Without a filter the listing isn't in date order, so it asks for jobs updated in the last day
 * ({@code daysSinceUpdated=1}), which are. That's several pages a day, so only the first {@link #MAX_PAGES} are read.
 * Each new job's page carries a schema.org JobPosting as JSON-LD with the title, company, pay and full description.
 * <p>
 * Built In loads a Cloudflare script that hasn't blocked plain requests so far. If several job pages in a row
 * fail, it probably has, so the run stops there.
 */
public class ScrapeBuiltIn {

	private static final String LISTING_URL = "https://builtin.com/jobs/remote/dev-engineering?daysSinceUpdated=1&page=";
	private static final int MAX_PAGES = 4;
	private static final int MAX_FAILURES_IN_A_ROW = 3;
	private static final String JOB_SITE = "builtin";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeBuiltIn().run();
	}

	public void run() {
		// "https://builtin.com/job/software-engineer/12298447", in listing order, without repeats
		Set<String> urls = new LinkedHashSet<>();
		for (int page = 1; page <= MAX_PAGES; page++) {
			if (page > 1) {
				Utils.sleepRandom(4);
			}
			Optional<String> html = Http.get(LISTING_URL + page);
			if (html.isEmpty()) {
				break;
			}
			int before = urls.size();
			for (Element link : Jsoup.parse(html.get(), "https://builtin.com/").select("a[data-id=job-card-title][href]")) {
				String url = link.absUrl("href").replaceFirst("[?#].*$", "");
				if (url.startsWith("https://builtin.com/job/")) {
					urls.add(url);
				}
			}
			if (urls.size() == before) {
				break;
			}
		}

		int saved = 0;
		int failuresInARow = 0;

		for (String url : urls) {
			if (store.exists(url)) {
				continue;
			}

			Utils.sleepRandom(4);
			Optional<JsonNode> posting = Http.get(url).flatMap(html -> jobPosting(html, url));
			if (posting.isEmpty()) {
				if (++failuresInARow >= MAX_FAILURES_IN_A_ROW) {
					System.out.println(MAX_FAILURES_IN_A_ROW + " job pages in a row failed, stopping");
					break;
				}
				continue;
			}
			failuresInARow = 0;

			Job job = new Job();
			job.url = url;
			job.title = posting.get().path("title").asText("").strip();
			job.company = posting.get().path("hiringOrganization").path("name").asText("").strip();
			job.description = summary(posting.get()) + posting.get().path("description").asText("");
			job.job_site = JOB_SITE;
			job.status = "new";
			job.job_posting_date = LocalDate.now();

			if (!job.title.isEmpty() && store.saveNew(job)) {
				saved++;
				System.out.println("Saved " + job.title + " at " + job.company);
			}
		}

		System.out.println(JOB_SITE + ": " + urls.size() + " found, " + saved + " new");
	}

	private static String summary(JsonNode posting) {
		StringBuilder html = new StringBuilder();

		String location = posting.path("jobLocation").path("address").path("addressLocality").asText("");
		if (location.isEmpty()) {
			location = posting.path("applicantLocationRequirements").path("name").asText("");
		}
		line(html, "Location", location);
		line(html, "Workplace", "TELECOMMUTE".equals(posting.path("jobLocationType").asText()) ? "Remote" : "");
		line(html, "Type", posting.path("employmentType").asText("").replace('_', ' ').toLowerCase());
		line(html, "Pay", pay(posting.path("baseSalary")));
		return html.toString();
	}

	// baseSalary.value is either text or a QuantitativeValue with min, max and unit.
	private static String pay(JsonNode salary) {
		JsonNode value = salary.path("value");
		if (value.isTextual()) {
			return value.asText();
		}
		long min = value.path("minValue").asLong(0);
		long max = value.path("maxValue").asLong(0);
		if (min == 0 && max == 0) {
			return "";
		}
		String range = min > 0 && max > min ? min + " - " + max : String.valueOf(Math.max(min, max));
		return (range + " " + salary.path("currency").asText("") + " " + value.path("unitText").asText("").toLowerCase())
				.replaceAll("\\s+", " ").strip();
	}

	private static void line(StringBuilder html, String label, String value) {
		if (value != null && !value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.strip())).append("</p>\n");
		}
	}

	// The page's JSON-LD scripts hold a JobPosting and a BreadcrumbList, either alone or in an @graph.
	private Optional<JsonNode> jobPosting(String html, String url) {
		for (Element script : Jsoup.parse(html, url).select("script")) {
			if (!"application/ld+json".equalsIgnoreCase(script.attr("type"))) {
				continue;
			}
			try {
				JsonNode node = mapper.readTree(script.data());
				for (JsonNode item : node.has("@graph") ? node.get("@graph") : node.isArray() ? node : mapper.createArrayNode().add(node)) {
					if ("JobPosting".equals(item.path("@type").asText())) {
						return Optional.of(item);
					}
				}
			} catch (Exception e) {
				System.out.println("Couldn't read the JSON-LD on " + url + ": " + e.getMessage());
			}
		}
		System.out.println("No job posting data on " + url);
		return Optional.empty();
	}
}
