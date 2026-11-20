package co.aisaac.scrapers.public_apis.workingnomads;

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
 * Scrapes Working Nomads' search API, which is Elasticsearch's URI search with full html descriptions.
 * <p>
 * Asks for the "Development" category, newest first, {@link #PAGE_SIZE} at a time. Paging stops after
 * {@link #MAX_JOBS}, or at the first page with nothing new, since everything after it was seen on an earlier run.
 */
public class ScrapeWorkingNomads {

	private static final String QUERY = "category_name:\"Development\"";
	private static final int PAGE_SIZE = 50;
	private static final int MAX_JOBS = 200;

	// Same job_site the old Selenium scraper used, so jobs it saved and these share a folder and a filter.
	private static final String JOB_SITE = "working_nomads";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeWorkingNomads().run();
	}

	public void run() {
		int found = 0;
		int saved = 0;

		for (int from = 0; from < MAX_JOBS; from += PAGE_SIZE) {
			if (from > 0) {
				Utils.sleepRandom(3);
			}

			String url = "https://www.workingnomads.com/jobsapi/_search?sort=pub_date:desc"
					+ "&size=" + PAGE_SIZE + "&from=" + from
					+ "&q=" + URLEncoder.encode(QUERY, StandardCharsets.UTF_8);
			List<JsonNode> postings = fetch(url);
			if (postings.isEmpty()) {
				break;
			}

			int savedThisPage = 0;
			for (JsonNode posting : postings) {
				if (posting.path("expired").asBoolean(false)) {
					continue;
				}
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

			if (savedThisPage == 0 || postings.size() < PAGE_SIZE) {
				break;
			}
		}

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	// The jobs are in hits.hits[]._source.
	private List<JsonNode> fetch(String url) {
		Optional<String> body = Http.get(url);
		List<JsonNode> postings = new ArrayList<>();
		if (body.isEmpty()) {
			return postings;
		}
		try {
			for (JsonNode hit : mapper.readTree(body.get()).path("hits").path("hits")) {
				postings.add(hit.path("_source"));
			}
		} catch (Exception e) {
			System.out.println("Couldn't read " + url + ": " + e.getMessage());
		}
		return postings;
	}

	private static Job toJob(JsonNode posting) {
		String slug = posting.path("slug").asText("").trim();

		Job job = new Job();
		job.url = slug.isEmpty() ? "" : "https://www.workingnomads.com/jobs/" + slug;
		job.title = posting.path("title").asText("").trim();
		job.company = posting.path("company").asText("").trim();
		job.description = summary(posting) + posting.path("description").asText("");
		job.job_site = JOB_SITE;
		job.status = "new";
		job.job_posting_date = LocalDate.now();
		return job;
	}

	// Location, salary, level and tags are separate fields, not always in the description.
	private static String summary(JsonNode posting) {
		StringBuilder html = new StringBuilder();
		appendLine(html, "Location", posting.path("location_base").asText(""));
		appendLine(html, "Salary", posting.path("salary_range").asText(""));
		appendLine(html, "Level", posting.path("experience_level").asText("").replace('_', ' ').toLowerCase());

		List<String> tags = new ArrayList<>();
		posting.path("tags").forEach(tag -> tags.add(tag.asText()));
		appendLine(html, "Tags", String.join(", ", tags));
		return html.toString();
	}

	private static void appendLine(StringBuilder html, String label, String value) {
		if (!value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.trim())).append("</p>\n");
		}
	}
}
