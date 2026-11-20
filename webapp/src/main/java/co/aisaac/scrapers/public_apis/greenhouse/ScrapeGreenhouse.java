package co.aisaac.scrapers.public_apis.greenhouse;

import co.aisaac.scrapers.CompanyBoards;
import co.aisaac.scrapers.CompanyBoards.Company;
import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.parser.Parser;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes Greenhouse job boards, one GET per company from companies/greenhouse.txt, full descriptions included.
 * <p>
 * The description ({@code content}) comes html escaped ("&amp;lt;p&amp;gt;"), so it's unescaped first.
 * The saved url is always job-boards.greenhouse.io/{company}/jobs/{id}, even when the company's own
 * {@code absolute_url} points at its careers site, so jobs captured by the browser extension match.
 */
public class ScrapeGreenhouse {

	private static final String JOB_SITE = "greenhouse";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeGreenhouse().run();
	}

	public void run() {
		List<Company> companies = CompanyBoards.read(JOB_SITE);
		int found = 0;
		int saved = 0;

		for (int i = 0; i < companies.size(); i++) {
			if (i > 0) {
				Utils.sleepRandom(3);
			}
			Company company = companies.get(i);

			Optional<JsonNode> response = readJson(
					"https://boards-api.greenhouse.io/v1/boards/" + company.slug() + "/jobs?content=true");
			if (response.isEmpty()) {
				continue;
			}

			JsonNode postings = response.get().path("jobs");
			int technical = 0;
			int savedHere = 0;

			for (JsonNode posting : postings) {
				String title = posting.path("title").asText("").strip();
				if (!CompanyBoards.isTechTitle(title)) {
					continue;
				}
				technical++;

				long id = posting.path("id").asLong();
				String url = "https://job-boards.greenhouse.io/" + company.slug() + "/jobs/" + id;
				// The browser extension may have saved it under the older host.
				if (store.exists("https://boards.greenhouse.io/" + company.slug() + "/jobs/" + id)) {
					continue;
				}

				Job job = new Job();
				job.url = url;
				job.title = title;
				job.company = company.nameOr(posting.path("company_name").asText(""));
				job.description = CompanyBoards.line("Location", posting.path("location").path("name").asText(""))
						+ Parser.unescapeEntities(posting.path("content").asText(""), false);
				job.job_site = JOB_SITE;
				job.status = "new";
				job.job_posting_date = LocalDate.now();

				if (store.saveNew(job)) {
					savedHere++;
				}
			}

			CompanyBoards.report(company.slug(), postings.size(), technical, savedHere);
			found += technical;
			saved += savedHere;
		}

		System.out.println(JOB_SITE + ": " + companies.size() + " companies, " + found + " found, " + saved + " new");
	}

	private Optional<JsonNode> readJson(String url) {
		return Http.get(url).flatMap(body -> {
			try {
				return Optional.of(mapper.readTree(body));
			} catch (Exception e) {
				System.out.println("Couldn't read " + url + ": " + e.getMessage());
				return Optional.empty();
			}
		});
	}
}
