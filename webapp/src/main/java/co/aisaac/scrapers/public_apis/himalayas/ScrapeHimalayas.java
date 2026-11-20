package co.aisaac.scrapers.public_apis.himalayas;

import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Entities;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes Himalayas' search API, newest first, 20 jobs a page, full html descriptions included.
 * <p>
 * Himalayas lists over 100,000 jobs, and its plain feed ({@code /jobs/api}) has no category filter,
 * so this searches instead. Paging stops after {@link #MAX_PAGES}, or at the first page with nothing new,
 * since everything after it was seen on an earlier run. The first few results of a search are
 * promoted jobs that aren't in date order, so a page counts as "nothing new" only after the first.
 */
public class ScrapeHimalayas {

	private static final List<String> QUERIES = List.of("software engineer");
	private static final int MAX_PAGES = 5;
	private static final String JOB_SITE = "himalayas";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeHimalayas().run();
	}

	public void run() {
		int found = 0;
		int saved = 0;
		boolean first = true;

		for (String query : QUERIES) {
			for (int page = 1; page <= MAX_PAGES; page++) {
				if (!first) {
					Utils.sleepRandom(3);
				}
				first = false;

				String url = "https://himalayas.app/jobs/api/search?sort=recent&page=" + page
						+ "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
				List<JsonNode> postings = fetch(url);
				if (postings.isEmpty()) {
					break;
				}

				int savedThisPage = 0;
				for (JsonNode posting : postings) {
					found++;
					Job job = toJob(posting);
					if (job.url.isEmpty() || job.title.isEmpty()) {
						continue;
					}
					if (store.saveNew(job)) {
						savedThisPage++;
						System.out.println("Saved " + job.title + " at " + job.company);
					}
				}
				saved += savedThisPage;

				if (savedThisPage == 0 && page > 1) {
					break;
				}
			}
		}

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	private List<JsonNode> fetch(String url) {
		Optional<String> body = Http.get(url);
		List<JsonNode> postings = new ArrayList<>();
		if (body.isEmpty()) {
			return postings;
		}
		try {
			mapper.readTree(body.get()).path("jobs").forEach(postings::add);
		} catch (Exception e) {
			System.out.println("Couldn't read " + url + ": " + e.getMessage());
		}
		return postings;
	}

	private static Job toJob(JsonNode posting) {
		Job job = new Job();
		// guid is the job's page without the "?utm_source=api" that applicationLink has.
		job.url = posting.path("guid").asText(posting.path("applicationLink").asText("")).trim();
		job.title = posting.path("title").asText("").trim();
		job.company = posting.path("companyName").asText("").trim();
		job.description = summary(posting) + posting.path("description").asText("");
		job.job_site = JOB_SITE;
		job.status = "new";
		job.job_posting_date = LocalDate.now();
		return job;
	}

	// Location, salary, type and seniority are separate fields, not always in the description.
	private static String summary(JsonNode posting) {
		StringBuilder html = new StringBuilder();

		String locations = join(posting.path("locationRestrictions"));
		appendLine(html, "Location", locations.isEmpty() ? "Worldwide" : locations);

		long min = posting.path("minSalary").asLong(0);
		long max = posting.path("maxSalary").asLong(0);
		if (min > 0 || max > 0) {
			String range = min > 0 && max > min ? min + " - " + max : String.valueOf(Math.max(min, max));
			String currency = posting.path("currency").asText("");
			String period = posting.path("salaryPeriod").asText("");
			appendLine(html, "Salary", (range + " " + currency + " " + period).trim().replaceAll("\\s+", " "));
		}

		appendLine(html, "Type", posting.path("employmentType").asText(""));
		appendLine(html, "Seniority", join(posting.path("seniority")));
		return html.toString();
	}

	private static String join(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(value -> values.add(value.asText()));
		return String.join(", ", values);
	}

	private static void appendLine(StringBuilder html, String label, String value) {
		if (!value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.trim())).append("</p>\n");
		}
	}
}
