package co.aisaac.scrapers.html.dice;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Scrapes Dice. Its search pages are rendered on the server: each result is a
 * {@code <div data-testid="job-card" data-job-guid="...">}, about 30 a page, with the job at /job-detail/{guid}.
 * <p>
 * Searches remote software engineer jobs posted in the last day ({@code filters.postedDate=ONE}), a few hundred
 * a day, so only the first {@link #MAX_PAGES} pages are read. Each new job's page carries a schema.org JobPosting
 * as JSON-LD with the title, company, pay and full description, which is read instead of the page's html.
 * If several job pages in a row fail, Dice is probably blocking, so the run stops there.
 */
public class ScrapeDice {

	private static final String SEARCH_URL = "https://www.dice.com/jobs?q=software%20engineer"
			+ "&filters.workplaceTypes=Remote&filters.postedDate=ONE&page=";
	private static final int MAX_PAGES = 3;
	private static final int MAX_FAILURES_IN_A_ROW = 3;
	private static final String JOB_SITE = "dice";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeDice().run();
	}

	public void run() {
		// The same job can show up on two pages as results shift, so collect them all first.
		Set<String> guids = new LinkedHashSet<>();
		for (int page = 1; page <= MAX_PAGES; page++) {
			if (page > 1) {
				Utils.sleepRandom(4);
			}
			Optional<String> html = Http.get(SEARCH_URL + page);
			if (html.isEmpty()) {
				break;
			}
			int before = guids.size();
			for (Element card : Jsoup.parse(html.get(), SEARCH_URL).select("[data-testid=job-card][data-job-guid]")) {
				guids.add(card.attr("data-job-guid").strip());
			}
			if (guids.size() == before) {
				break;
			}
		}

		int saved = 0;
		int failuresInARow = 0;

		for (String guid : guids) {
			String url = "https://www.dice.com/job-detail/" + guid;
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
			job.description = summary(posting.get()) + body(posting.get().path("description").asText(""));
			job.job_site = JOB_SITE;
			job.status = "new";
			job.job_posting_date = LocalDate.now();

			if (!job.title.isEmpty() && store.saveNew(job)) {
				saved++;
				System.out.println("Saved " + job.title + " at " + job.company);
			}
		}

		System.out.println(JOB_SITE + ": " + guids.size() + " found, " + saved + " new");
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

	// baseSalary.value is either text ("Depends on Experience") or a QuantitativeValue with min, max and unit.
	private static String pay(JsonNode salary) {
		JsonNode value = salary.path("value");
		if (value.isTextual()) {
			return value.asText();
		}
		long min = value.path("minValue").asLong(0);
		long max = value.path("maxValue").asLong(0);
		if (min == 0 && max == 0) {
			return value.path("value").asText("");
		}
		String range = min > 0 && max > min ? min + " - " + max : String.valueOf(Math.max(min, max));
		return (range + " " + salary.path("currency").asText("") + " " + value.path("unitText").asText("").toLowerCase())
				.replaceAll("\\s+", " ").strip();
	}

	// The description is a whole html document; only its body is kept.
	private static String body(String description) {
		return Jsoup.parse(description).body().html();
	}

	private static void line(StringBuilder html, String label, String value) {
		if (value != null && !value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.strip())).append("</p>\n");
		}
	}

	private Optional<JsonNode> jobPosting(String html, String url) {
		Document page = Jsoup.parse(html, url);
		for (Element script : page.select("script")) {
			if (!"application/ld+json".equalsIgnoreCase(script.attr("type"))) {
				continue;
			}
			try {
				JsonNode node = mapper.readTree(script.data());
				if ("JobPosting".equals(node.path("@type").asText())) {
					return Optional.of(node);
				}
			} catch (Exception e) {
				System.out.println("Couldn't read the JSON-LD on " + url + ": " + e.getMessage());
			}
		}
		System.out.println("No job posting data on " + url);
		return Optional.empty();
	}
}
