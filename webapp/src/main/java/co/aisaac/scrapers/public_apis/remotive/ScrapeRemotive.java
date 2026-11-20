package co.aisaac.scrapers.public_apis.remotive;

import co.aisaac.scrapers.Http;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Entities;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Scrapes Remotive's public API: one GET returns every listed job with its full html description.
 * <p>
 * The API ignores the {@code category} parameter, so jobs are filtered by category here.
 * Remotive asks for at most 4 requests a day, links back to their job pages (the saved url is theirs),
 * and says jobs show up in the API 24 hours after they're posted.
 */
public class ScrapeRemotive {

	private static final String API_URL = "https://remotive.com/api/remote-jobs?category=software-dev";
	private static final String JOB_SITE = "remotive";

	// Remotive's category names to keep. The rest are sales, marketing, writing, customer service and so on.
	private static final Set<String> CATEGORIES = Set.of("Software Development");

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeRemotive().run();
	}

	public void run() {
		Optional<String> body = Http.get(API_URL);
		if (body.isEmpty()) {
			System.out.println(JOB_SITE + ": couldn't fetch the API");
			return;
		}

		JsonNode postings;
		try {
			postings = mapper.readTree(body.get()).path("jobs");
		} catch (Exception e) {
			System.out.println(JOB_SITE + ": couldn't read the API response: " + e.getMessage());
			return;
		}

		int found = 0;
		int saved = 0;

		for (JsonNode posting : postings) {
			if (!CATEGORIES.contains(posting.path("category").asText())) {
				continue;
			}
			found++;

			Job job = new Job();
			job.url = posting.path("url").asText("").trim();
			job.title = posting.path("title").asText("").trim();
			job.company = posting.path("company_name").asText("").trim();
			job.description = summary(posting) + posting.path("description").asText("");
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

		System.out.println(JOB_SITE + ": " + found + " found, " + saved + " new");
	}

	// Location and salary aren't always in the description, so they go at the top.
	private static String summary(JsonNode posting) {
		StringBuilder html = new StringBuilder();
		appendLine(html, "Location", posting.path("candidate_required_location").asText(""));
		appendLine(html, "Salary", posting.path("salary").asText(""));
		appendLine(html, "Type", posting.path("job_type").asText("").replace('_', ' '));
		return html.toString();
	}

	private static void appendLine(StringBuilder html, String label, String value) {
		if (!value.isBlank()) {
			html.append("<p><strong>").append(label).append(":</strong> ")
					.append(Entities.escape(value.trim())).append("</p>\n");
		}
	}
}
