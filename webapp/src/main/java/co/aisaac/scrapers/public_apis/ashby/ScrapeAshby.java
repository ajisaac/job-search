package co.aisaac.scrapers.public_apis.ashby;

import co.aisaac.scrapers.CompanyBoards;
import co.aisaac.scrapers.CompanyBoards.Company;
import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes Ashby job boards, one GET per company from companies/ashby.txt, full descriptions and pay included.
 * <p>
 * The API doesn't give the company's name, so it's the display name from the file, or the url name like
 * the browser extension uses. The saved url, jobs.ashbyhq.com/{company}/{id}, is the same one the extension saves.
 */
public class ScrapeAshby {

	private static final String JOB_SITE = "ashby";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeAshby().run();
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

			Optional<JsonNode> response = readJson("https://api.ashbyhq.com/posting-api/job-board/"
					+ company.slug() + "?includeCompensation=true");
			if (response.isEmpty()) {
				continue;
			}

			JsonNode postings = response.get().path("jobs");
			int technical = 0;
			int savedHere = 0;

			for (JsonNode posting : postings) {
				String title = posting.path("title").asText("").strip();
				if (!posting.path("isListed").asBoolean(true) || !CompanyBoards.isTechTitle(title)) {
					continue;
				}
				technical++;

				Job job = new Job();
				job.url = posting.path("jobUrl").asText("").strip();
				job.title = title;
				job.company = company.nameOr("");
				job.description = summary(posting) + posting.path("descriptionHtml").asText("");
				job.job_site = JOB_SITE;
				job.status = "new";
				job.job_posting_date = LocalDate.now();

				if (!job.url.isEmpty() && store.saveNew(job)) {
					savedHere++;
				}
			}

			CompanyBoards.report(company.slug(), postings.size(), technical, savedHere);
			found += technical;
			saved += savedHere;
		}

		System.out.println(JOB_SITE + ": " + companies.size() + " companies, " + found + " found, " + saved + " new");
	}

	private static String summary(JsonNode posting) {
		String workplace = posting.path("workplaceType").asText("");
		if (posting.path("isRemote").asBoolean(false) && !workplace.equalsIgnoreCase("remote")) {
			workplace = workplace.isEmpty() ? "Remote" : workplace + ", remote allowed";
		}

		return CompanyBoards.line("Location", posting.path("location").asText(""))
				+ CompanyBoards.line("Workplace", workplace)
				+ CompanyBoards.line("Type", posting.path("employmentType").asText(""))
				+ CompanyBoards.line("Team", posting.path("department").asText(""))
				+ CompanyBoards.line("Pay", posting.path("compensation").path("compensationTierSummary").asText(""));
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
